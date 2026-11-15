package vn.trafficalert.app.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import vn.trafficalert.core.BBox
import vn.trafficalert.core.Detection
import vn.trafficalert.core.SignClass
import vn.trafficalert.core.YoloDecoder
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

enum class DetectorStatus { READY, MODEL_MISSING, MODEL_INCOMPATIBLE }

/**
 * Chạy model YOLO TFLite (assets/signs.tflite) trên một khung hình.
 * Không an toàn đa luồng: chỉ gọi [detect] từ một luồng (luồng phân tích camera).
 */
class SignDetector(context: Context, private val decoder: YoloDecoder = YoloDecoder()) : Closeable {

    var status: DetectorStatus = DetectorStatus.MODEL_MISSING
        private set

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    private var inputW = 640
    private var inputH = 640
    private var numAnchors = 0
    private var channelsFirst = true // [1, C, N] (mặc định Ultralytics) hay [1, N, C]

    private lateinit var inputBuffer: ByteBuffer
    private lateinit var outputBuffer: ByteBuffer
    private lateinit var outputArray: FloatArray
    private lateinit var decodeArray: FloatArray
    private lateinit var pixels: IntArray
    private lateinit var letterbox: Bitmap
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var model: MappedByteBuffer? = null
    private var gpuAttempted = false

    init {
        val loaded = try {
            loadModel(context)
        } catch (e: IOException) {
            null
        }
        if (loaded != null) {
            model = loaded
            setUp(loaded)
        }
    }

    val isAvailable: Boolean get() = status == DetectorStatus.READY

    private fun loadModel(context: Context): MappedByteBuffer {
        context.assets.openFd(MODEL_FILE).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    /**
     * Kiểm tra model bằng Interpreter CPU (gọi ở luồng nào cũng được).
     * GPU delegate được thử sau, ở lần [detect] đầu tiên, vì GPU delegate (OpenGL) phải được tạo
     * và chạy trên cùng một luồng: luồng phân tích camera.
     */
    private fun setUp(model: MappedByteBuffer) {
        val interp = createCpuInterpreter(model) ?: run {
            status = DetectorStatus.MODEL_INCOMPATIBLE
            return
        }
        val inTensor = interp.getInputTensor(0)
        val outTensor = interp.getOutputTensor(0)
        val inShape = inTensor.shape()  // [1, H, W, 3]
        val outShape = outTensor.shape() // [1, C, N] hoặc [1, N, C]
        val expectedChannels = 4 + SignClass.entries.size
        val ok = inShape.size == 4 && inShape[0] == 1 && inShape[3] == 3 &&
            inTensor.dataType() == DataType.FLOAT32 && outTensor.dataType() == DataType.FLOAT32 &&
            outShape.size == 3 && outShape[0] == 1 &&
            (outShape[1] == expectedChannels || outShape[2] == expectedChannels)
        if (!ok) {
            Log.e(TAG, "Model không khớp: input=${inShape.contentToString()} output=${outShape.contentToString()}, cần ${expectedChannels} kênh")
            interp.close()
            status = DetectorStatus.MODEL_INCOMPATIBLE
            return
        }
        inputH = inShape[1]
        inputW = inShape[2]
        channelsFirst = outShape[1] == expectedChannels
        numAnchors = if (channelsFirst) outShape[2] else outShape[1]

        inputBuffer = ByteBuffer.allocateDirect(4 * inputW * inputH * 3).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(4 * expectedChannels * numAnchors).order(ByteOrder.nativeOrder())
        outputArray = FloatArray(expectedChannels * numAnchors)
        decodeArray = FloatArray(expectedChannels * numAnchors)
        pixels = IntArray(inputW * inputH)
        letterbox = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
        interpreter = interp
        status = DetectorStatus.READY
    }

    private fun createCpuInterpreter(model: MappedByteBuffer): Interpreter? = try {
        Interpreter(model, Interpreter.Options().setNumThreads(4))
    } catch (t: Throwable) {
        Log.e(TAG, "Không tạo được Interpreter", t)
        null
    }

    /** Thử chuyển sang GPU; phải gọi trên luồng sẽ chạy [detect]. Lỗi thì giữ Interpreter CPU. */
    private fun tryUpgradeToGpu() {
        val m = model ?: return
        val cpu = interpreter ?: return
        try {
            val compat = CompatibilityList()
            if (!compat.isDelegateSupportedOnThisDevice) return
            val delegate = GpuDelegate(compat.bestOptionsForThisDevice)
            val gpu = try {
                Interpreter(m, Interpreter.Options().addDelegate(delegate))
            } catch (t: Throwable) {
                delegate.close()
                throw t
            }
            val sameShape = gpu.getInputTensor(0).shape().contentEquals(cpu.getInputTensor(0).shape()) &&
                gpu.getOutputTensor(0).shape().contentEquals(cpu.getOutputTensor(0).shape())
            if (!sameShape) {
                gpu.close()
                delegate.close()
                return
            }
            cpu.close()
            interpreter = gpu
            gpuDelegate = delegate
            Log.i(TAG, "Dùng GPU delegate")
        } catch (t: Throwable) {
            Log.w(TAG, "GPU delegate lỗi, tiếp tục dùng CPU", t)
        }
    }

    /** GPU chạy lỗi giữa chừng: quay về CPU. */
    private fun fallBackToCpu() {
        val m = model ?: return
        interpreter?.close()
        interpreter = null
        closeGpu()
        interpreter = createCpuInterpreter(m)
    }

    /** Trả về các phát hiện với toạ độ chuẩn hoá 0..1 theo [frame] gốc. */
    fun detect(frame: Bitmap): List<Detection> {
        if (!gpuAttempted && interpreter != null) {
            gpuAttempted = true
            tryUpgradeToGpu()
        }
        val interp = interpreter ?: return emptyList()

        // Letterbox: giữ tỉ lệ, đệm màu xám 114 như lúc huấn luyện.
        val scale = minOf(inputW.toFloat() / frame.width, inputH.toFloat() / frame.height)
        val newW = (frame.width * scale).toInt().coerceAtLeast(1)
        val newH = (frame.height * scale).toInt().coerceAtLeast(1)
        val padX = (inputW - newW) / 2
        val padY = (inputH - newH) / 2
        val canvas = Canvas(letterbox)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(frame, null, Rect(padX, padY, padX + newW, padY + newH), paint)

        letterbox.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        inputBuffer.rewind()
        for (p in pixels) {
            inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f)
            inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)
            inputBuffer.putFloat((p and 0xFF) / 255f)
        }
        inputBuffer.rewind()
        outputBuffer.rewind()
        try {
            interp.run(inputBuffer, outputBuffer)
        } catch (t: Throwable) {
            if (gpuDelegate == null) throw t
            Log.w(TAG, "GPU chạy lỗi, chuyển sang CPU", t)
            fallBackToCpu()
            return emptyList()
        }
        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(outputArray)

        val channels = 4 + SignClass.entries.size
        val data = if (channelsFirst) outputArray else {
            // [N, C] -> [C, N]
            for (i in 0 until numAnchors) for (c in 0 until channels) {
                decodeArray[c * numAnchors + i] = outputArray[i * channels + c]
            }
            decodeArray
        }
        normalizeCoordinatesIfNeeded(data)

        return decoder.decode(data, numAnchors).mapNotNull { d ->
            // Từ toạ độ ảnh letterbox về toạ độ khung hình gốc.
            fun mapX(v: Float) = ((v * inputW - padX) / newW).coerceIn(0f, 1f)
            fun mapY(v: Float) = ((v * inputH - padY) / newH).coerceIn(0f, 1f)
            val box = BBox(mapX(d.box.left), mapY(d.box.top), mapX(d.box.right), mapY(d.box.bottom))
            if (box.width <= 0f || box.height <= 0f) null else d.copy(box = box)
        }
    }

    /** Một số bản xuất trả toạ độ theo pixel thay vì 0..1; quy về 0..1 nếu cần. */
    private fun normalizeCoordinatesIfNeeded(data: FloatArray) {
        var max = 0f
        for (i in 0 until 4 * numAnchors) if (data[i] > max) max = data[i]
        if (max > 2f) {
            for (i in 0 until numAnchors) {
                data[i] /= inputW
                data[numAnchors + i] /= inputH
                data[2 * numAnchors + i] /= inputW
                data[3 * numAnchors + i] /= inputH
            }
        }
    }

    private fun closeGpu() {
        try { gpuDelegate?.close() } catch (_: Throwable) {}
        gpuDelegate = null
    }

    /** Gọi trên luồng phân tích camera (cùng luồng với [detect]) để đóng GPU delegate đúng luồng. */
    override fun close() {
        interpreter?.close()
        interpreter = null
        closeGpu()
        model = null
    }

    companion object {
        const val MODEL_FILE = "signs.tflite"
        private const val TAG = "SignDetector"
    }
}
