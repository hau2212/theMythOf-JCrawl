# Chính sách Đánh số Phiên bản & Phát hành Bất biến (Versioning & Immutable Releases)

## 1. Quy ước Đánh số Phiên bản (`V_<MAJOR>.<MINOR>_beta`)

Mọi phiên bản được quản lý tập trung tại file [`version.properties`](version.properties):

1. **Nâng cấp tính năng, giao diện (UI/UX), bộ luật, tối ưu hiển thị, sửa lỗi**:
   - Giữ nguyên `MAJOR`, tăng `MINOR` lên `+1`:
   - `V_2.1_beta` → `V_2.2_beta` → `V_2.3_beta` → `V_2.4_beta` → ...
2. **Nâng cấp thuật toán lõi / kiến trúc hệ thống**:
   - Tăng `MAJOR` lên `+1` và **giữ nguyên `MINOR` của phiên bản nền** để luôn truy vết được thuật toán mới được cải tiến từ phiên bản nào:
   - Nếu nâng cấp thuật toán dựa trên bản `2.2` → đặt tên **`V_3.2_beta`** (hoặc `V_3.2`).
   - Nếu nâng cấp thuật toán dựa trên bản `2.3` → đặt tên **`V_3.3_beta`** (hoặc `V_3.3`).

## 2. Nguyên tắc Release Bất Biến (Cấm tuyệt đối sửa bản cũ)

- **Mỗi phiên bản mới phải có Tag mới + GitHub Release mới + File JAR tên riêng** (ví dụ `theMythOf-JCrawl_V_2.2_beta.jar`).
- **Cấm tuyệt đối** ghi đè (`git tag -f`), xóa asset (`--clobber`), chỉnh sửa (`gh release edit`) hoặc xóa bất kỳ bản phát hành cũ nào (`latest`, `V_1.0`, `V_2.0_beta`, `V_2.1_beta`, ...).
- Workflow `.github/workflows/build.yml` kiểm tra tồn tại trước khi phát hành: nếu `releaseTag` đã có trên GitHub thì **dừng phát hành (không ghi đè)** và yêu cầu tăng phiên bản trong `version.properties`.
