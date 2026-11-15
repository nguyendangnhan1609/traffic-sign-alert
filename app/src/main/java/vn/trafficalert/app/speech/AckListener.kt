package vn.trafficalert.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import vn.trafficalert.core.AckPhraseMatcher

/**
 * Nghe liên tục câu "đã biết" của tài xế. Mọi hàm phải gọi trên luồng chính.
 * Tự khởi động lại sau mỗi lần nhận dạng kết thúc; tạm dừng khi app đang đọc cảnh báo.
 */
class AckListener(private val context: Context, private val onAcknowledged: () -> Unit) {

    val isAvailable: Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var paused = false
    private var listening = false
    private var lastAckMs = 0L

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private val restart = Runnable { listen() }

    fun start() {
        if (!isAvailable || running) return
        running = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
        listen()
    }

    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        if (!running) return
        if (value) {
            handler.removeCallbacks(restart)
            if (listening) recognizer?.cancel()
            listening = false
        } else {
            scheduleRestart(300)
        }
    }

    fun destroy() {
        running = false
        handler.removeCallbacks(restart)
        recognizer?.destroy()
        recognizer = null
        listening = false
    }

    private fun listen() {
        if (!running || paused || listening) return
        try {
            recognizer?.startListening(intent)
            listening = true
        } catch (t: Throwable) {
            Log.w(TAG, "startListening lỗi", t)
            scheduleRestart(2000)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        handler.removeCallbacks(restart)
        if (running && !paused) handler.postDelayed(restart, delayMs)
    }

    private fun check(bundle: Bundle?) {
        val texts = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        if (texts.any { AckPhraseMatcher.isAcknowledgement(it) }) {
            val now = SystemClock.elapsedRealtime()
            // Kết quả tạm và kết quả cuối có thể cùng chứa câu xác nhận: chỉ tính một lần.
            if (now - lastAckMs > 2000) {
                lastAckMs = now
                onAcknowledged()
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) = check(partialResults)

        override fun onResults(results: Bundle?) {
            listening = false
            check(results)
            scheduleRestart(250)
        }

        override fun onError(error: Int) {
            listening = false
            val delay = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 250L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    // Giải phóng recognizer để lần start() sau không tạo thêm bản mới bị rò rỉ.
                    handler.post { destroy() }
                    return
                }
                else -> 3000L // mất mạng, lỗi máy chủ nhận dạng...
            }
            scheduleRestart(delay)
        }
    }

    companion object { private const val TAG = "AckListener" }
}
