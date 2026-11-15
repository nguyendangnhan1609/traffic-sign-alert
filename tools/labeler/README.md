# Gán nhãn dữ liệu bằng Claude

```
pip install anthropic pillow pyyaml
export ANTHROPIC_API_KEY=...
```

## Gán nhãn sơ bộ

```
python tools/labeler/label_with_claude.py --images data/frames --dry-run     # xem prompt, không tốn tiền
python tools/labeler/label_with_claude.py --images data/frames --limit 20    # thử 20 ảnh
python tools/labeler/label_with_claude.py --images data/frames --workers 4
```

- Mỗi ảnh có thêm file `<ảnh>.txt` (định dạng YOLO) và một dòng trong `review.jsonl`.
- Chạy lại sẽ bỏ qua ảnh đã có nhãn (dùng `--overwrite` để gán lại).
- `--model` đổi model Anthropic. Mặc định `claude-sonnet-4-5`; model nhỏ hơn rẻ hơn nhưng có thể đặt hộp kém chính xác hơn.
- Chi phí tăng theo số ảnh. Nên thử `--limit` với vài chục ảnh để ước lượng trước khi chạy hàng chục nghìn ảnh.
  Bỏ bớt khung hình liền nhau giống hệt nhau cũng giảm chi phí đáng kể.

> **Quan trọng:** nhãn do model tạo chỉ là bản nháp. Model có thể đặt hộp lệch, đọc nhầm số trên biển,
> hoặc bỏ sót biển nhỏ. Bắt buộc phải duyệt lại bằng tay trước khi huấn luyện, nếu không model on-device
> sẽ học luôn các lỗi đó.

## Duyệt nhãn

```
python tools/labeler/review_server.py --images data/frames
```

Mở http://localhost:8765:

- Danh sách bên trái: ảnh chưa duyệt, độ tin cậy thấp nhất trước. Dấu ✓ là đã duyệt.
- Bấm vào hộp để chọn, đổi lớp bằng ô chọn, phím **Del** để xoá.
- Kéo chuột để vẽ hộp mới (dùng lớp đang chọn trong ô).
- **S** = lưu và sang ảnh tiếp, **N / P** = ảnh sau / trước.

Sau đó chạy `python ml/prepare_dataset.py ... --only-reviewed` để chỉ dùng ảnh đã duyệt.

## Test

```
pytest tools/labeler/tests ml/tests
```
