# Kiến trúc

Ứng dụng Android gắn trên xe: camera đọc biển báo, đọc cảnh báo bằng giọng nói, ngừng nhắc khi tài xế nói "đã biết".

## Luồng xử lý

```
CameraX (khung hình)
  -> TFLite YOLO (on-device)        app/detect
  -> YoloDecoder + DetectionFilter  core
  -> SignTracker (xác nhận qua nhiều khung hình)  core
  -> AlertEngine.onSign             core
       |-- biển NEEDS_CLOUD -> CloudEscalationPolicy -> Claude API (ảnh cắt) -> AlertEngine.onCloudMessage
  GPS -> AlertEngine.onSpeed
  Giọng nói/nút bấm -> AckPhraseMatcher -> AlertEngine.acknowledge
  Mỗi 500 ms: AlertEngine.tick -> TTS tiếng Việt
```

## Thư mục

| Thư mục | Nội dung | Ngôn ngữ |
|---|---|---|
| `core/` | Logic thuần, không phụ thuộc Android, có unit test | Kotlin/JVM |
| `app/` | App Android: camera, TFLite, TTS, nhận giọng nói, GPS, gọi Claude API, giao diện | Kotlin, Compose |
| `ml/` | Huấn luyện và xuất model YOLO sang TFLite | Python |
| `tools/labeler/` | Gán nhãn sơ bộ dữ liệu bằng Claude API | Python |

## Hợp đồng giữa các phần

1. **Danh sách lớp**: `core/.../SignClass.kt` là nguồn gốc. `ml/classes.yaml` phải có `names` cùng thứ tự,
   tên lớp là tên enum (ví dụ `SPEED_50`). Có script kiểm tra hai nơi khớp nhau.
2. **Model**: file `app/src/main/assets/signs.tflite`, đầu vào `[1, 640, 640, 3]` float32 RGB 0..1,
   đầu ra `[1, 4 + numClasses, numAnchors]` float32 (định dạng Ultralytics, toạ độ chuẩn hoá 0..1).
   App chuyển đầu ra thành `FloatArray` phẳng theo hàng rồi gọi `YoloDecoder.decode(output, numAnchors)`.
3. **Cloud**: app gửi ảnh cắt JPEG của biển (nới rộng hộp bao để lấy cả biển phụ bên dưới) kèm
   `CloudEscalationPolicy.PROMPT`, nhận JSON `{is_traffic_sign, speech, speed_limit_kmh, confidence}`
   và chuyển thành `CloudSignResult`. API key do người dùng nhập trong phần cài đặt, lưu mã hoá trên máy.
4. **App không chứa logic quyết định cảnh báo**: mọi quy tắc nằm trong `core` để test được trên JVM.

## Nguyên tắc an toàn

- Tài xế không phải chạm vào màn hình khi lái: xác nhận bằng giọng nói, nút bấm chiếm cả nửa dưới màn hình.
- Mất mạng hoặc cloud lỗi thì app vẫn hoạt động đầy đủ với các biển on-device.
- Kết quả cloud về trễ quá 6 giây hoặc độ tin cậy thấp thì bỏ, không đọc.
