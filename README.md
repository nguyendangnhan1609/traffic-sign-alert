# Cảnh báo biển báo giao thông real-time

Ứng dụng Android gắn trên ô tô: camera đọc biển báo bằng AI chạy ngay trên máy, đọc cảnh báo bằng tiếng Việt,
và ngừng nhắc khi tài xế nói "đã biết" hoặc bấm nút.

> Ứng dụng chỉ là công cụ hỗ trợ. Tài xế luôn chịu trách nhiệm quan sát và tuân thủ biển báo thực tế.

## Cách hoạt động

- **YOLO trên máy** phát hiện các biển phổ biến (giới hạn tốc độ, cấm vượt, cấm dừng đỗ, khu dân cư) trong thời gian thực, không cần mạng.
- **GPS** so tốc độ xe với giới hạn. Mặc định chỉ nhắc lại khi xe đang chạy quá tốc độ; có thể đổi sang nhắc liên tục tới khi xác nhận.
- **Claude API** chỉ được gọi cho biển phụ có chữ và biển lạ, với ảnh cắt nhỏ của riêng biển đó.
- **Claude API** cũng dùng để gán nhãn sơ bộ dữ liệu huấn luyện (`tools/labeler`).

Chi tiết: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Cấu trúc

| Thư mục | Nội dung |
|---|---|
| `core/` | Logic cảnh báo thuần Kotlin, có unit test |
| `app/` | App Android |
| `ml/` | Huấn luyện và xuất model YOLO |
| `tools/labeler/` | Gán nhãn dữ liệu bằng Claude API và công cụ duyệt nhãn |

## Bắt đầu

1. Repo chưa kèm Gradle wrapper. Nếu máy có Gradle, chạy `gradle wrapper --gradle-version 8.9` trong thư mục gốc;
   hoặc mở thẳng bằng Android Studio (bản mới, JDK 17) và chọn dùng Gradle 8.9 khi được hỏi.
2. Chạy test logic: `./gradlew :core:test`.
3. **Cần có model trước khi app nhận diện được biển**: làm theo [ml/README.md](ml/README.md) để thu dữ liệu, huấn luyện
   và xuất `signs.tflite` vào `app/src/main/assets/`. Repo này không kèm model đã huấn luyện.
4. Cài app lên điện thoại, cấp quyền camera, micro và vị trí. Nhập API key trong phần Cài đặt nếu muốn dùng cloud.

## Tình trạng

- `core`: đã biên dịch và chạy qua 18 unit test.
- `ml`, `tools`: 20 test Python cho xử lý dữ liệu, gán nhãn và công cụ duyệt; huấn luyện, xuất model và gọi API thật chưa chạy thử.
- `app`: mã nguồn chưa được build hay chạy trên thiết bị thật. Lần build đầu có thể cần sửa lỗi nhỏ.
