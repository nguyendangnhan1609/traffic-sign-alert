package vn.trafficalert.core

enum class RepeatMode {
    /** Nhắc lại giới hạn tốc độ chỉ khi xe đang chạy quá giới hạn (mặc định). */
    WHEN_SPEEDING,

    /** Nhắc lại liên tục cho tới khi tài xế xác nhận "đã biết". */
    ALWAYS_UNTIL_ACK,
}

data class AlertConfig(
    val repeatMode: RepeatMode = RepeatMode.WHEN_SPEEDING,
    val repeatIntervalMs: Long = 10_000,
    /** Sai số cho phép của GPS trước khi coi là quá tốc độ. */
    val speedToleranceKmh: Float = 3f,
    /** Số lần nhắc lại tối đa với biển không phải giới hạn tốc độ (cấm vượt, cấm đỗ...). */
    val maxRepeatsNonSpeed: Int = 2,
    /** Không có GPS quá lâu thì coi như không biết tốc độ. */
    val speedStaleMs: Long = 5_000,
)

data class Utterance(val text: String, val urgent: Boolean = false)

/** Trạng thái để giao diện hiển thị. */
data class AlertState(
    val activeSpeedLimitKmh: Int?,
    val speedLimitAcknowledged: Boolean,
    val currentSpeedKmh: Float?,
    val isSpeeding: Boolean,
    val pendingNotice: String?,
)

/**
 * Bộ máy quyết định khi nào đọc cảnh báo. Thuần Kotlin, không phụ thuộc Android:
 * app gọi [onSign] / [onCloudMessage] / [onSpeed] / [acknowledge] rồi gọi [tick] định kỳ (khoảng 2 lần/giây)
 * và đọc các câu trả về bằng TTS.
 */
class AlertEngine(config: AlertConfig = AlertConfig()) {
    /** Đổi cấu hình mà không mất biển đang áp dụng. */
    var config: AlertConfig = config
    private var limitKmh: Int? = null
    private var limitAcked = false
    private var limitLastSpokenMs = 0L

    private var noticeText: String? = null
    private var noticeRepeatsLeft = 0
    private var noticeLastSpokenMs = 0L

    private var speedKmh: Float? = null
    private var speedTimeMs = 0L

    private val queue = ArrayDeque<Utterance>()

    fun onSpeed(kmh: Float, nowMs: Long) { speedKmh = kmh; speedTimeMs = nowMs }

    fun onSign(c: ConfirmedSign) {
        val sign = c.sign
        when (sign.category) {
            SignCategory.SPEED_LIMIT -> {
                // Gặp biển mới (kể cả cùng trị số) thì cảnh báo lại từ đầu.
                limitKmh = sign.speedLimitKmh
                limitAcked = false
                limitLastSpokenMs = c.timeMs
                queue += Utterance("Phía trước " + sign.speech.replaceFirstChar { it.lowercase() })
            }
            SignCategory.END_RESTRICTION -> {
                limitKmh = null
                limitAcked = false
                clearNotice()
                queue += Utterance(sign.speech)
            }
            SignCategory.PROHIBITION -> setNotice(sign.speech, c.timeMs)
            SignCategory.ZONE -> {
                // Biển khu dân cư không ghi số; chỉ thông báo, không tự suy ra giới hạn.
                if (sign == SignClass.RESIDENTIAL_END) clearNotice()
                queue += Utterance(sign.speech)
            }
            SignCategory.NEEDS_CLOUD -> Unit // App gửi cloud, kết quả về qua onCloudMessage.
        }
    }

    /** Nội dung biển phức tạp do cloud đọc (ví dụ "Cấm đỗ xe từ 6 giờ đến 22 giờ"). */
    fun onCloudMessage(text: String, speedLimitKmh: Int?, nowMs: Long) {
        if (speedLimitKmh != null) {
            limitKmh = speedLimitKmh
            limitAcked = false
            limitLastSpokenMs = nowMs
        }
        if (text.isNotBlank()) queue += Utterance(text)
    }

    /** Tài xế nói "đã biết" hoặc bấm nút: ngừng nhắc cho các biển hiện tại. */
    fun acknowledge() {
        limitAcked = true
        clearNotice()
        queue.clear()
    }

    fun tick(nowMs: Long): List<Utterance> {
        val limit = limitKmh
        if (limit != null && !limitAcked && nowMs - limitLastSpokenMs >= config.repeatIntervalMs) {
            val speeding = isSpeeding(nowMs)
            val shouldRepeat = when (config.repeatMode) {
                RepeatMode.ALWAYS_UNTIL_ACK -> true
                RepeatMode.WHEN_SPEEDING -> speeding
            }
            if (shouldRepeat) {
                limitLastSpokenMs = nowMs
                queue += if (speeding) {
                    Utterance("Bạn đang chạy quá tốc độ. Giới hạn $limit ki lô mét trên giờ", urgent = true)
                } else {
                    Utterance("Nhắc lại, giới hạn tốc độ $limit ki lô mét trên giờ")
                }
            }
        }
        val notice = noticeText
        if (notice != null && noticeRepeatsLeft > 0 && nowMs - noticeLastSpokenMs >= config.repeatIntervalMs) {
            noticeLastSpokenMs = nowMs
            noticeRepeatsLeft--
            queue += Utterance("Nhắc lại, $notice")
            if (noticeRepeatsLeft == 0) noticeText = null
        }
        val out = queue.toList()
        queue.clear()
        return out
    }

    fun state(nowMs: Long): AlertState = AlertState(
        activeSpeedLimitKmh = limitKmh,
        speedLimitAcknowledged = limitAcked,
        currentSpeedKmh = freshSpeed(nowMs),
        isSpeeding = isSpeeding(nowMs),
        pendingNotice = noticeText,
    )

    private fun freshSpeed(nowMs: Long): Float? =
        speedKmh?.takeIf { nowMs - speedTimeMs <= config.speedStaleMs }

    private fun isSpeeding(nowMs: Long): Boolean {
        val limit = limitKmh ?: return false
        val speed = freshSpeed(nowMs) ?: return false
        return speed > limit + config.speedToleranceKmh
    }

    private fun setNotice(text: String, nowMs: Long) {
        noticeText = text
        noticeRepeatsLeft = config.maxRepeatsNonSpeed
        noticeLastSpokenMs = nowMs
        queue += Utterance(text)
    }

    private fun clearNotice() { noticeText = null; noticeRepeatsLeft = 0 }
}
