package vn.trafficalert.app

import android.app.Application
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.trafficalert.app.cloud.ClaudeClient
import vn.trafficalert.app.detect.DetectorStatus
import vn.trafficalert.app.detect.SignDetector
import vn.trafficalert.app.location.SpeedProvider
import vn.trafficalert.app.settings.AppSettings
import vn.trafficalert.app.settings.SettingsStore
import vn.trafficalert.app.speech.AckListener
import vn.trafficalert.app.speech.Speaker
import vn.trafficalert.app.speech.SpeakerStatus
import vn.trafficalert.core.AlertConfig
import vn.trafficalert.core.AlertEngine
import vn.trafficalert.core.AlertState
import vn.trafficalert.core.CloudEscalationPolicy
import vn.trafficalert.core.Detection
import vn.trafficalert.core.DetectionFilter
import vn.trafficalert.core.SignTracker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class FrameSize(val width: Int, val height: Int)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings

    private val detector = SignDetector(app)

    /**
     * Một luồng phân tích duy nhất, sống cùng ViewModel: GPU delegate của TFLite phải luôn chạy
     * trên cùng một luồng, kể cả khi màn hình camera được tạo lại (xoay máy, mở cài đặt).
     */
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val filter = DetectionFilter()
    private val tracker = SignTracker()
    private val policy = CloudEscalationPolicy()
    private val claude = ClaudeClient()

    // AlertEngine không an toàn đa luồng: mọi lời gọi phải nằm trong synchronized(engineLock).
    private val engineLock = Any()
    private var engine = AlertEngine(configFor(_settings.value))

    private val speaker = Speaker(app)
    private val ackListener = AckListener(app) { acknowledge() }
    private val speedProvider = SpeedProvider(app) { kmh ->
        synchronized(engineLock) { engine.onSpeed(kmh, SystemClock.elapsedRealtime()) }
    }

    private val _alert = MutableStateFlow(AlertState(null, false, null, false, null))
    val alert: StateFlow<AlertState> = _alert

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections

    private val _frameSize = MutableStateFlow<FrameSize?>(null)
    val frameSize: StateFlow<FrameSize?> = _frameSize

    private val _micDenied = MutableStateFlow(false)
    private val _locationDenied = MutableStateFlow(false)

    /** Thông báo hiển thị cho người dùng (model thiếu, chưa có giọng tiếng Việt...). */
    val messages: StateFlow<List<String>>
    private val _messages = MutableStateFlow<List<String>>(emptyList())

    init {
        messages = _messages
        viewModelScope.launch {
            combine(speaker.status, _micDenied, _locationDenied) { tts, mic, loc -> Triple(tts, mic, loc) }
                .collect { (tts, mic, loc) -> _messages.value = buildMessages(tts, mic, loc) }
        }
        // Tạm dừng nghe khi app đang đọc để không tự nghe chính mình.
        viewModelScope.launch { speaker.speaking.collect { ackListener.setPaused(it) } }
        // Nhịp 500 ms: lấy câu cần đọc và cập nhật giao diện.
        viewModelScope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val (utterances, state) = synchronized(engineLock) { engine.tick(now) to engine.state(now) }
                utterances.forEach { speaker.speak(it.text, it.urgent) }
                _alert.value = state
                delay(500)
            }
        }
    }

    private fun buildMessages(tts: SpeakerStatus, micDenied: Boolean, locDenied: Boolean): List<String> {
        val app = getApplication<Application>()
        val out = ArrayList<String>()
        when (detector.status) {
            DetectorStatus.MODEL_MISSING -> out += app.getString(R.string.msg_model_missing)
            DetectorStatus.MODEL_INCOMPATIBLE -> out += app.getString(R.string.msg_model_incompatible)
            DetectorStatus.READY -> Unit
        }
        when (tts) {
            SpeakerStatus.NO_VIETNAMESE -> out += app.getString(R.string.msg_tts_no_vietnamese)
            SpeakerStatus.FAILED -> out += app.getString(R.string.msg_tts_failed)
            else -> Unit
        }
        if (micDenied) out += app.getString(R.string.msg_mic_denied)
        else if (!ackListener.isAvailable) out += app.getString(R.string.msg_voice_unavailable)
        if (locDenied) out += app.getString(R.string.msg_location_denied)
        if (settingsStore.storageError) out += app.getString(R.string.settings_storage_error)
        return out
    }

    /** Gọi từ luồng phân tích camera. */
    fun onFrame(frame: Bitmap, nowMs: Long) {
        if (_frameSize.value?.let { it.width == frame.width && it.height == frame.height } != true) {
            _frameSize.value = FrameSize(frame.width, frame.height)
        }
        if (!detector.isAvailable) return
        val detections = filter.filter(detector.detect(frame))
        _detections.value = detections
        val confirmed = tracker.update(detections, nowMs)
        if (confirmed.isEmpty()) return
        synchronized(engineLock) { confirmed.forEach { engine.onSign(it) } }

        val s = _settings.value
        if (!s.cloudReady) return
        for (c in confirmed) {
            if (!policy.shouldEscalate(c, nowMs, isOnline())) continue
            val jpeg = ClaudeClient.cropSign(frame, c.box)
            viewModelScope.launch {
                val result = claude.readSign(jpeg, s.apiKey, s.model) ?: return@launch
                val now = SystemClock.elapsedRealtime()
                if (policy.shouldSpeak(result, nowMs, now)) {
                    synchronized(engineLock) { engine.onCloudMessage(result.speech, result.speedLimitKmh, now) }
                }
            }
        }
    }

    /** Tài xế nói "đã biết" hoặc bấm nút. */
    fun acknowledge() {
        synchronized(engineLock) { engine.acknowledge() }
        speaker.stop()
        speaker.speak(getApplication<Application>().getString(R.string.ack_confirmed), urgent = true)
    }

    fun onMicPermission(granted: Boolean) {
        _micDenied.value = !granted
        if (granted) ackListener.start()
    }

    fun onLocationPermission(granted: Boolean) {
        _locationDenied.value = !granted
        if (granted) speedProvider.start()
    }

    fun saveSettings(new: AppSettings) {
        val old = _settings.value
        settingsStore.save(new)
        _settings.value = new
        if (old.repeatMode != new.repeatMode || old.repeatIntervalSec != new.repeatIntervalSec) {
            synchronized(engineLock) { engine.config = configFor(new) }
        }
    }

    private fun configFor(s: AppSettings) =
        AlertConfig(repeatMode = s.repeatMode, repeatIntervalMs = s.repeatIntervalSec * 1000L)

    private fun isOnline(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onCleared() {
        speedProvider.stop()
        ackListener.destroy()
        speaker.shutdown()
        // Đóng detector trên chính luồng phân tích: không đóng khi đang chạy dở một khung hình,
        // và GPU delegate được giải phóng đúng luồng đã tạo nó.
        analysisExecutor.execute { detector.close() }
        analysisExecutor.shutdown()
    }
}
