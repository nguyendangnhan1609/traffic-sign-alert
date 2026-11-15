package vn.trafficalert.app.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Chuyển ImageProxy (RGBA_8888) thành Bitmap đã xoay đúng chiều rồi giao cho [onFrame].
 * Giới hạn tốc độ xử lý để giảm nóng máy và hao pin.
 */
class FrameAnalyzer(
    private val minFrameIntervalMs: Long = 80,
    private val onFrame: (bitmap: Bitmap, timeMs: Long) -> Unit,
) : ImageAnalysis.Analyzer {

    private var lastFrameMs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameMs < minFrameIntervalMs) return
            lastFrameMs = now

            val raw = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val bitmap = if (rotation == 0) raw else {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            }
            onFrame(bitmap, now)
        } catch (t: Throwable) {
            Log.e("FrameAnalyzer", "Lỗi xử lý khung hình", t)
        } finally {
            image.close()
        }
    }
}
