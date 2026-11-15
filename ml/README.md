# Huấn luyện model nhận diện biển báo

Kết quả cuối cùng: file `app/src/main/assets/signs.tflite` cho app Android.

```
pip install -r ml/requirements.txt
```

## 1. Quay video

- Gắn điện thoại hoặc camera hành trình ở vị trí app sẽ dùng (giữa kính lái), quay 1080p.
- Đi đủ loại đường: nội thành, quốc lộ, cao tốc, khu dân cư. Quay cả **ban đêm, trời mưa, ngược nắng**.
- Đặt tên video không chứa `__`, ví dụ `hn_ql1_dem_01.mp4`.

## 2. Cắt khung hình

```
python ml/extract_frames.py --videos data/videos --out data/frames --every 0.5
```

Tên ảnh có dạng `<video>__<khung>.jpg`. Khi chia tập, mọi khung của cùng một video nằm chung một tập,
tránh việc model "học thuộc" cảnh đã thấy và cho điểm đánh giá ảo.

## 3. Gán nhãn sơ bộ bằng Claude

Xem `tools/labeler/README.md`. Tóm tắt:

```
export ANTHROPIC_API_KEY=...
python tools/labeler/label_with_claude.py --images data/frames --limit 20   # thử trước 20 ảnh
python tools/labeler/label_with_claude.py --images data/frames
```

## 4. Người duyệt lại (bắt buộc)

```
python tools/labeler/review_server.py --images data/frames
```

Mở http://localhost:8765, sửa hoặc xoá hộp sai, vẽ hộp bị sót, bấm Lưu. Ảnh tin cậy thấp hiện trước.

## 5. Chuẩn bị dataset

```
python ml/prepare_dataset.py --src data/frames --out data/dataset --only-reviewed
```

Script kiểm tra nhãn, chia train/val/test 80/10/10 theo video, ghi `data.yaml`
và cảnh báo lớp nào dưới 200 mẫu. Biển tốc độ ít gặp (30, 120) thường cần quay thêm.

## 6. Huấn luyện

```
python ml/train.py --data data/dataset/data.yaml
```

Không có GPU thì dùng **Google Colab** (GPU miễn phí): tải repo và thư mục `data/dataset` lên Google Drive,
cài `pip install -r ml/requirements.txt` rồi chạy lệnh trên. Model mặc định `yolo11n.pt` (nhỏ, nhanh trên điện thoại);
máy mạnh có thể thử `--model yolo11s.pt`.

## 7. Đánh giá

```
python ml/evaluate.py --weights ml/runs/signs/weights/best.pt --data data/dataset/data.yaml
```

Chú ý dòng **"nhầm giữa các biển giới hạn tốc độ"**: đọc 50 thành 80 nguy hiểm hơn bỏ sót biển.
Chưa nên dùng thật khi còn nhầm loại này.

## 8. Xuất sang app

```
python ml/export_tflite.py --weights ml/runs/signs/weights/best.pt
```

Script chép file vào `app/src/main/assets/signs.tflite`. Build lại app là xong.

## Dữ liệu công khai

Bộ dữ liệu phát hiện biển báo của **Zalo AI Challenge 2020** là một điểm khởi đầu có ảnh đường phố Việt Nam.
Trước khi dùng cần kiểm tra giấy phép của bộ dữ liệu và **ánh xạ lại các lớp** của nó sang danh sách trong
`classes.yaml` (hai bên đặt lớp khác nhau). Dữ liệu tự quay vẫn cần thiết cho ban đêm, mưa và loại camera của bạn.

## Thêm loại biển mới

1. Thêm vào **cuối** enum `SignClass` (`core/.../SignClass.kt`) và cuối `ml/classes.yaml`.
2. `python ml/check_classes.py` để kiểm tra hai nơi khớp nhau.
3. Gán nhãn, chuẩn bị lại dataset và huấn luyện lại.
