package vn.trafficalert.core

import kotlin.math.max
import kotlin.math.min

/** Hộp bao, toạ độ chuẩn hoá 0..1 theo khung hình (gốc ở góc trên trái). */
data class BBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun iou(other: BBox): Float {
        val iw = max(0f, min(right, other.right) - max(left, other.left))
        val ih = max(0f, min(bottom, other.bottom) - max(top, other.top))
        val inter = iw * ih
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}

data class Detection(val sign: SignClass, val score: Float, val box: BBox)

/**
 * Giải mã đầu ra của model YOLO (v8/v11) xuất bằng Ultralytics sang TFLite.
 *
 * Tensor đầu ra có shape [1, 4 + numClasses, numAnchors], dạng phẳng theo hàng:
 * output[c * numAnchors + i]. Bốn kênh đầu là cx, cy, w, h đã chuẩn hoá 0..1.
 */
class YoloDecoder(
    private val numClasses: Int = SignClass.entries.size,
    private val scoreThreshold: Float = 0.45f,
    private val iouThreshold: Float = 0.5f,
    private val maxDetections: Int = 20,
) {
    fun decode(output: FloatArray, numAnchors: Int): List<Detection> {
        require(output.size == (4 + numClasses) * numAnchors) {
            "Kích thước output ${output.size} không khớp (4+$numClasses)x$numAnchors"
        }
        val candidates = ArrayList<Detection>()
        for (i in 0 until numAnchors) {
            var bestClass = -1
            var bestScore = scoreThreshold
            for (c in 0 until numClasses) {
                val s = output[(4 + c) * numAnchors + i]
                if (s > bestScore) { bestScore = s; bestClass = c }
            }
            if (bestClass < 0) continue
            val sign = SignClass.fromId(bestClass) ?: continue
            val cx = output[i]
            val cy = output[numAnchors + i]
            val w = output[2 * numAnchors + i]
            val h = output[3 * numAnchors + i]
            val box = BBox(
                (cx - w / 2f).coerceIn(0f, 1f), (cy - h / 2f).coerceIn(0f, 1f),
                (cx + w / 2f).coerceIn(0f, 1f), (cy + h / 2f).coerceIn(0f, 1f),
            )
            candidates += Detection(sign, bestScore, box)
        }
        return nms(candidates)
    }

    /** NMS không phân biệt lớp: một vị trí trên hình chỉ là một biển. */
    fun nms(candidates: List<Detection>): List<Detection> {
        val sorted = candidates.sortedByDescending { it.score }
        val kept = ArrayList<Detection>()
        for (d in sorted) {
            if (kept.size >= maxDetections) break
            if (kept.none { it.box.iou(d.box) > iouThreshold }) kept += d
        }
        return kept
    }
}

/**
 * Loại các phát hiện nhiều khả năng là báo sai: biển quá nhỏ (còn xa, dễ đọc nhầm số),
 * hình méo (biển dán sau xe tải nhìn nghiêng), hoặc nằm ở phần dưới khung hình (mặt đường, nắp ca-pô).
 */
class DetectionFilter(
    private val minBoxSide: Float = 0.025f,
    private val maxBoxSide: Float = 0.5f,
    private val maxAspectRatio: Float = 1.6f,
    private val maxCenterY: Float = 0.8f,
) {
    fun accept(d: Detection): Boolean {
        val w = d.box.width
        val h = d.box.height
        if (w < minBoxSide || h < minBoxSide) return false
        if (w > maxBoxSide || h > maxBoxSide) return false
        if (d.box.centerY > maxCenterY) return false
        // Biển phụ là hình chữ nhật nên không áp giới hạn tỉ lệ.
        if (d.sign != SignClass.SUPPLEMENTARY_PLATE) {
            val ratio = if (w > h) w / h else h / w
            if (ratio > maxAspectRatio) return false
        }
        return true
    }

    fun filter(detections: List<Detection>): List<Detection> = detections.filter(::accept)
}
