# App Android

## Build

1. Mở thư mục gốc của repo bằng Android Studio (Ladybug trở lên, JDK 17).
2. Đợi Gradle sync xong rồi chọn **Run 'app'** trên điện thoại thật (máy ảo không có camera hành trình thật).
3. Yêu cầu: Android 8.0 (API 26) trở lên. Nên dùng máy tầm trung trở lên để nhận diện đủ nhanh.

## Model

App cần file `app/src/main/assets/signs.tflite`. Repo không kèm sẵn model:
làm theo `ml/README.md` để huấn luyện và xuất. Khi thiếu model, app vẫn mở được và hiện thông báo hướng dẫn.

## Claude API (tuỳ chọn)

Vào **Cài đặt** trong app, bật "Đọc biển phức tạp bằng Claude", nhập khoá API Anthropic và tên model.
Khoá được lưu mã hoá trên máy. App chỉ gửi ảnh cắt nhỏ của biển phụ hoặc biển lạ, tối đa khoảng 40 lần mỗi giờ.

## Giọng nói

- Đọc cảnh báo bằng giọng tiếng Việt của hệ thống. Nếu máy chưa có, vào Cài đặt > Chuyển văn bản thành giọng nói để tải.
- Tài xế nói "đã biết", "biết rồi", "OK"... để ngừng nhắc. Nút **ĐÃ BIẾT** chiếm nửa dưới màn hình để dự phòng.

## Hạn chế đã biết

- Mã nguồn chưa được build hay chạy thử trên thiết bị. Lần build đầu có thể cần sửa lỗi nhỏ.
- Một số điện thoại phát tiếng "bíp" mỗi lần bắt đầu nghe; nhận dạng giọng nói trên nhiều máy cần mạng.
- Nhận dạng giọng nói tạm dừng khi app đang đọc, nên tài xế nói "đã biết" trong lúc app đang đọc sẽ không được nghe.
- Hộp vẽ trên màn hình có thể lệch nhẹ nếu camera chọn tỉ lệ khung hình khác 16:9.
- Gắn máy dưới nắng lâu có thể nóng máy; app giới hạn khoảng 12 khung hình/giây để giảm nhiệt.
