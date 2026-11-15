package vn.trafficalert.core

/** Kết quả cloud trả về sau khi đọc ảnh cắt của một biển phức tạp. */
data class CloudSignResult(
    val isTrafficSign: Boolean,
    /** Câu ngắn để đọc cho tài xế, tiếng Việt. Rỗng nếu không cần đọc. */
    val speech: String,
    val speedLimitKmh: Int?,
    val confidence: Float,
)

/**
 * Quyết định khi nào gửi ảnh cắt lên cloud (Claude API). Mục tiêu: vài phút mới gọi một lần,
 * chỉ cho biển mà model on-device không tự đọc được.
 */
class CloudEscalationPolicy(
    private val minIntervalMs: Long = 20_000,
    private val minScore: Float = 0.6f,
    private val minBoxSide: Float = 0.05f,
    private val maxCallsPerHour: Int = 40,
) {
    private val callTimes = ArrayDeque<Long>()

    fun shouldEscalate(c: ConfirmedSign, nowMs: Long, online: Boolean): Boolean {
        if (!online) return false
        if (c.sign.category != SignCategory.NEEDS_CLOUD) return false
        if (c.score < minScore) return false
        // Ảnh cắt quá nhỏ thì cloud cũng không đọc được chữ.
        if (c.box.width < minBoxSide || c.box.height < minBoxSide) return false
        while (callTimes.isNotEmpty() && nowMs - callTimes.first() > 3_600_000) callTimes.removeFirst()
        if (callTimes.size >= maxCallsPerHour) return false
        val last = callTimes.lastOrNull()
        if (last != null && nowMs - last < minIntervalMs) return false
        callTimes.addLast(nowMs)
        return true
    }

    /** Chỉ đọc kết quả cloud nếu đủ tin cậy và còn kịp thời. */
    fun shouldSpeak(result: CloudSignResult, requestedAtMs: Long, nowMs: Long, maxAgeMs: Long = 6_000): Boolean =
        result.isTrafficSign && result.confidence >= 0.7f && result.speech.isNotBlank() &&
            nowMs - requestedAtMs <= maxAgeMs

    companion object {
        /** Prompt dùng chung cho app và công cụ gán nhãn, yêu cầu trả về JSON thuần. */
        const val PROMPT: String =
            "Đây là ảnh cắt từ camera hành trình ô tô tại Việt Nam, có thể chứa một biển báo giao thông " +
                "(kèm biển phụ nếu có). Hãy đọc biển và trả về DUY NHẤT một đối tượng JSON, không thêm chữ nào khác: " +
                "{\"is_traffic_sign\": true|false, " +
                "\"speech\": \"một câu tiếng Việt ngắn dưới 15 từ để đọc cho tài xế, gồm cả điều kiện trên biển phụ\", " +
                "\"speed_limit_kmh\": số nguyên hoặc null, " +
                "\"confidence\": số từ 0 đến 1}. " +
                "Nếu ảnh mờ, không phải biển báo, hoặc bạn không chắc chắn thì đặt is_traffic_sign=false và confidence thấp. " +
                "Tuyệt đối không đoán nội dung không nhìn rõ."
    }
}
