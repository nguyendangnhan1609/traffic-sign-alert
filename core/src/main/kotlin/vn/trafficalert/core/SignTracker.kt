package vn.trafficalert.core

/** Một biển đã được xác nhận qua nhiều khung hình liên tiếp. */
data class ConfirmedSign(val sign: SignClass, val score: Float, val box: BBox, val timeMs: Long)

/**
 * Chỉ xác nhận một biển khi nó xuất hiện đủ [minHits] lần trong cửa sổ [windowMs],
 * và không phát lại cùng loại biển trong [cooldownMs] (cùng một cột biển nhìn thấy nhiều khung hình).
 */
class SignTracker(
    private val minHits: Int = 3,
    private val windowMs: Long = 1500,
    private val cooldownMs: Long = 8000,
) {
    private val hits = HashMap<SignClass, ArrayDeque<Long>>()
    private val lastEmitted = HashMap<SignClass, Long>()

    fun update(detections: List<Detection>, nowMs: Long): List<ConfirmedSign> {
        val confirmed = ArrayList<ConfirmedSign>()
        // Mỗi lớp chỉ tính một lần mỗi khung hình, lấy phát hiện điểm cao nhất.
        val bestPerClass = detections.groupBy { it.sign }.mapValues { e -> e.value.maxBy { it.score } }
        for ((sign, det) in bestPerClass) {
            val q = hits.getOrPut(sign) { ArrayDeque() }
            q.addLast(nowMs)
            while (q.isNotEmpty() && nowMs - q.first() > windowMs) q.removeFirst()
            if (q.size < minHits) continue
            val last = lastEmitted[sign]
            if (last != null && nowMs - last < cooldownMs) {
                // Vẫn đang nhìn thấy cùng cột biển: kéo dài cooldown.
                lastEmitted[sign] = nowMs
                continue
            }
            lastEmitted[sign] = nowMs
            confirmed += ConfirmedSign(sign, det.score, det.box, nowMs)
        }
        // Dọn các lớp không còn thấy.
        hits.entries.removeAll { (_, q) -> q.isEmpty() || nowMs - q.last() > windowMs }
        return confirmed
    }

    fun reset() { hits.clear(); lastEmitted.clear() }
}
