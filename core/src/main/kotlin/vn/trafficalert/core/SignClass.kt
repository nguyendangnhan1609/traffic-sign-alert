package vn.trafficalert.core

enum class SignCategory { SPEED_LIMIT, END_RESTRICTION, PROHIBITION, ZONE, NEEDS_CLOUD }

/**
 * Danh sách lớp biển báo mà model on-device nhận diện.
 *
 * QUAN TRỌNG: thứ tự (ordinal) ở đây PHẢI trùng với thứ tự `names` trong ml/classes.yaml.
 * Thêm lớp mới thì thêm vào CUỐI ở cả hai nơi rồi huấn luyện lại model.
 */
enum class SignClass(
    val code: String,
    val category: SignCategory,
    val speedLimitKmh: Int?,
    /** Câu đọc cho tài xế (tiếng Việt). */
    val speech: String,
) {
    SPEED_30("P.127-30", SignCategory.SPEED_LIMIT, 30, "Giới hạn tốc độ 30 ki lô mét trên giờ"),
    SPEED_40("P.127-40", SignCategory.SPEED_LIMIT, 40, "Giới hạn tốc độ 40 ki lô mét trên giờ"),
    SPEED_50("P.127-50", SignCategory.SPEED_LIMIT, 50, "Giới hạn tốc độ 50 ki lô mét trên giờ"),
    SPEED_60("P.127-60", SignCategory.SPEED_LIMIT, 60, "Giới hạn tốc độ 60 ki lô mét trên giờ"),
    SPEED_70("P.127-70", SignCategory.SPEED_LIMIT, 70, "Giới hạn tốc độ 70 ki lô mét trên giờ"),
    SPEED_80("P.127-80", SignCategory.SPEED_LIMIT, 80, "Giới hạn tốc độ 80 ki lô mét trên giờ"),
    SPEED_90("P.127-90", SignCategory.SPEED_LIMIT, 90, "Giới hạn tốc độ 90 ki lô mét trên giờ"),
    SPEED_100("P.127-100", SignCategory.SPEED_LIMIT, 100, "Giới hạn tốc độ 100 ki lô mét trên giờ"),
    SPEED_120("P.127-120", SignCategory.SPEED_LIMIT, 120, "Giới hạn tốc độ 120 ki lô mét trên giờ"),
    END_SPEED_LIMIT("DP.134", SignCategory.END_RESTRICTION, null, "Hết giới hạn tốc độ tối đa"),
    END_ALL_RESTRICTIONS("DP.135", SignCategory.END_RESTRICTION, null, "Hết tất cả các lệnh cấm"),
    NO_OVERTAKING("P.125", SignCategory.PROHIBITION, null, "Cấm vượt"),
    NO_STOPPING_PARKING("P.130", SignCategory.PROHIBITION, null, "Cấm dừng xe và đỗ xe"),
    NO_PARKING("P.131", SignCategory.PROHIBITION, null, "Cấm đỗ xe"),
    RESIDENTIAL_START("R.420", SignCategory.ZONE, null, "Bắt đầu khu đông dân cư, chú ý giảm tốc độ"),
    RESIDENTIAL_END("R.421", SignCategory.ZONE, null, "Hết khu đông dân cư"),

    /** Biển phụ có chữ/số (giờ hiệu lực, loại xe...). Model chỉ phát hiện, nội dung do cloud đọc. */
    SUPPLEMENTARY_PLATE("S", SignCategory.NEEDS_CLOUD, null, ""),

    /** Biển báo khác chưa có lớp riêng. Nội dung do cloud đọc. */
    OTHER_SIGN("OTHER", SignCategory.NEEDS_CLOUD, null, "");

    companion object {
        fun fromId(id: Int): SignClass? = entries.getOrNull(id)
    }
}
