package vn.trafficalert.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

enum class SpeakerStatus { INITIALIZING, READY, NO_VIETNAMESE, FAILED }

/** Đọc cảnh báo bằng giọng tiếng Việt của hệ thống. */
class Speaker(context: Context) {

    private val _status = MutableStateFlow(SpeakerStatus.INITIALIZING)
    val status: StateFlow<SpeakerStatus> = _status

    private val _speaking = MutableStateFlow(false)
    /** Đang phát tiếng: dùng để tạm dừng nhận giọng nói, tránh app tự nghe chính nó. */
    val speaking: StateFlow<Boolean> = _speaking

    private val pending = AtomicInteger(0)
    private val ids = AtomicInteger(0)
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { result ->
            if (result != TextToSpeech.SUCCESS) {
                _status.value = SpeakerStatus.FAILED
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            val lang = engine.setLanguage(Locale("vi", "VN"))
            _status.value = if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                SpeakerStatus.NO_VIETNAMESE
            } else {
                SpeakerStatus.READY
            }
            engine.setSpeechRate(1.05f)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _speaking.value = true }
                override fun onDone(utteranceId: String?) { finished() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { finished() }
                override fun onError(utteranceId: String?, errorCode: Int) { finished() }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { finished() }
            })
        }
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            _speaking.value = false
        }
    }

    /** [urgent] = ngắt câu đang đọc để đọc ngay. */
    fun speak(text: String, urgent: Boolean = false) {
        val engine = tts ?: return
        val s = _status.value
        if (s != SpeakerStatus.READY && s != SpeakerStatus.NO_VIETNAMESE) return
        val mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (urgent) pending.set(0)
        pending.incrementAndGet()
        _speaking.value = true
        val result = engine.speak(text, mode, null, "u${ids.incrementAndGet()}")
        if (result != TextToSpeech.SUCCESS) finished()
    }

    fun stop() {
        tts?.stop()
        pending.set(0)
        _speaking.value = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
