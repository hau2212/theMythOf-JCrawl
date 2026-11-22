# Plan V_10.5 → V_11.4: Dự án nhiều tài khoản, Excel nâng cao, Q&A, làm chung

Trạng thái: **đã triển khai trong V_11.4** (theo quy tắc phiên bản: kiến trúc mới -> MAJOR 11, giữ MINOR 4).

Khác với plan:
- Không dùng Microsoft Graph (công ty chặn ứng dụng ngoài): Excel chung là **Excel của mỗi người** tự cập nhật trên OneDrive riêng,
  Google Sheet là trung tâm đồng bộ (đúng mô hình người dùng chốt cuối cùng).
- Ảnh trên Google Sheet là **link** tới file trong thư mục Drive của dự án (Apps Script không đặt ảnh vào ô bền vững khi sheet được vẽ lại);
  file Excel (của tôi, báo cáo, gửi khách hàng) luôn có ảnh chèn thật.
- Ảnh khách hàng dán vào file được gắn thẳng vào Q&A của workspace đang mở; Q&A của người khác: ảnh lưu trong anh-qa/<mã Q&A>/.

## 0. Quyết định đã chốt

| Câu hỏi | Quyết định |
|---|---|
| Bộ trạng thái ô tài khoản | `Done (X)` · `Denied (X)` · `Error (X)` · `Missing` · `Doing (X)` · `Info (X)` · `Q&A (X)` · `OOS (X)` |
| API "Chỉ B có" (con có, cha không) | Thêm dòng đánh dấu, **chờ PIC duyệt** mới vào danh sách chuẩn |
| Sửa tay trên Excel / Sheet | **Sửa tay luôn thắng** kết quả tự động, ô có dấu |
| Cách làm chung mặc định | **Kết hợp**: thư mục chung (OneDrive) = kho gốc + bảng làm việc trực tiếp (Excel Microsoft 365 công ty qua Graph, hoặc Google Sheet) + Phòng LAN tùy chọn |
| Excel Microsoft | Người dùng dùng **Microsoft 365 công ty** → ưu tiên đồng bộ 2 chiều qua Microsoft Graph; nếu bị chặn quyền → đọc tự động file đồng bộ + "Copy cột" 1 cú bấm |
| Thứ tự | P1 → P2 → P3 → P4 → P5a → P5b → P5c → P5d |

## 1. Khái niệm

| Tên | Nghĩa |
|---|---|
| Dự án | Một website / khách hàng: workspace tài khoản cha + các workspace con |
| Tài khoản | Mỗi workspace 1 tài khoản, ký hiệu chữ: **A** = cha (cao nhất), B, C… + tên ("A · admin") |
| Danh sách chuẩn | Danh sách API của dự án, mọi tài khoản đối chiếu theo |
| PIC | Quản lý dự án: tạo, danh sách chuẩn, phân công, gộp, xung đột, xuất bản |
| Thành viên | Người crawl 1 hoặc nhiều tài khoản |

## 2. P1: Danh sách chuẩn & kế thừa (V_10.5)

- Nguồn: (1) file Excel chuẩn; (2) không có → **dựng từ API cha đã crawl** (method, path theo mẫu `{ID}`, nhánh, STT tự đánh, nguồn "Từ crawl A"); (3) cả hai: chuẩn + "Ngoài danh sách" chờ PIC.
- Lưu ở cấp dự án (`du-an.json` cạnh workspace cha), không chép rời.
- Workspace con: tab Excel hiện **toàn bộ** danh sách, mỗi dòng có trạng thái của **từng** tài khoản; dòng con chưa gọi = `Missing (B)`.
- API con có mà cha không: dòng "Chỉ B có" (màu cam), PIC chọn "Thêm vào danh sách chuẩn".
- Cây của con: công tắc "Hiện API cha mà con chưa gọi" (mặc định tắt) → API thiếu hiện mờ đúng nhánh.
- Cảnh báo phân quyền: `Done (B)` ở API mà B không được phép → nghi BAC, nối tab Phân quyền.

## 3. P2: Cột, mẫu cột, báo cáo (V_10.5)

- Nút **Cột...** (tab Excel + mọi hộp xuất): tích hiện / ẩn, kéo sắp xếp (vd STT, Path, Method), đổi tên hiển thị; nhóm cột tài khoản tự có A, B, C….
- Cột: STT, Path, Method, Nhánh, Host, các cột tài khoản, Số tài khoản gọi được, Kết luận phân quyền, Q&A, Info, Ghi chú, Người test, Ngày test, Nguồn.
- **Mẫu cột** theo dự án: "Nộp khách hàng", "Nội bộ", "Q&A" + mẫu tự tạo.
- Hộp **Xuất báo cáo**: phạm vi (cả dự án / cha / 1 tài khoản / chọn nhiều / workspace đang mở); chọn & sắp sheet; chọn mẫu cột; "mỗi tài khoản 1 file".
- Sheet: Tổng quan · Tổng hợp · mỗi tài khoản 1 sheet · Chênh lệch · Q&A · Info · Phân quyền · Nhật ký thay đổi.
- **Tổng quan** (công thức `COUNTIF`, tự cập nhật khi sửa): theo từng tài khoản + "Tất cả": API chuẩn, Done, Denied, Error, Missing, % phủ, Info, Q&A chờ / đã trả lời, OOS, chỉ tài khoản này; so sánh: A và B, chỉ A, chỉ B, hợp, giao, B được mà A không (cảnh báo), Q&A ở A / B / cả hai; biểu đồ cột.

## 4. P3: Q&A nâng cấp (V_10.6)

- Ảnh có **loại**: Bước 1..n / Kết quả thực tế / Kết quả mong đợi / Bổ sung; mỗi ảnh **Tiêu đề + Mô tả**; kéo đổi thứ tự.
- Trường mới: Mức độ, Cần ai trả lời (Khách hàng / Dev / PIC), Hạn, Tài khoản liên quan, request / response đính kèm (đã che token).
- Trao đổi nhiều lượt; trạng thái Mới → Đã gửi khách hàng → Đã trả lời → Đóng.
- Xuất: ảnh theo bước có chú thích dưới ảnh; **sheet chi tiết từng Q&A** (câu hỏi, bước + ảnh xếp dọc, ô trả lời).
- **Nhập câu trả lời** từ file Excel / Sheet theo mã Q&A.

## 5. P4: Sổ dự án, quản lý sheet (V_10.6)

- Excel > Sổ dự án: thêm (mẫu tài khoản / lọc / trống), đổi tên, xóa, sắp xếp sheet; cột riêng mỗi sheet; bộ lọc (vd "Còn thiếu của B"); thống kê mỗi sheet.
- Cùng cấu hình cho `.xlsx`, Excel online và Google Sheet.
- Quản trị (PIC): tài khoản & ký hiệu, thành viên & phân công, khóa danh sách chuẩn, xung đột, lịch sử, xuất bản bản cố định.

## 6. P5: Làm chung (V_11.4)

### 6.1 Kết hợp

| Nơi | Vai trò | Chứa |
|---|---|---|
| Thư mục chung (OneDrive / SharePoint / Drive / ổ mạng của PIC) | Kho gốc | `du-an.json`, `thanh-vien/<tên>.json`, `anh-qa/`, `bao-cao/` (bản xuất bản), sao lưu |
| Bảng làm việc trực tiếp: **Excel Microsoft 365** (Graph) hoặc **Google Sheet** (Apps Script) | Làm & xem liên tục, 2 chiều | Tổng quan, Tổng hợp, sheet từng tài khoản, Q&A |
| Phòng LAN (tùy chọn) | Báo "đang làm" tức thì | – |

Quy tắc:
1. Mỗi người chỉ ghi file riêng + cột tài khoản của mình; cột chung chỉ PIC (khóa cột tùy chọn, mặc định tắt).
2. Sửa tay được kéo về và thắng kết quả tự động.
3. Hai người ghi khác nhau cùng 1 ô → giữ cả hai → **Xung đột** cho PIC.
4. Mất mạng → xếp hàng, gửi lại sau; bảng làm việc hỏng → **Dựng lại từ thư mục**.
5. Không bao giờ gửi token / cookie / mật khẩu.
6. Mỗi dòng có **cột Mã ẩn** + dữ liệu trong **Excel Table** có tên, để sắp xếp / lọc / share toàn quyền không làm lạc JCrawl.

Ảnh: thư mục giữ ảnh gốc; Google Sheet chèn ảnh thu nhỏ (≤ 2 MB, ~1 triệu điểm ảnh) + link gốc; Excel Graph không có lệnh chèn ảnh → link; bản Xuất bản `.xlsx` luôn có ảnh thật.

Độ trễ: thư mục vài giây → 1–2 phút; bảng làm việc ~30–60 giây; Phòng LAN tức thì.

Chống trùng: phân công trước; `Doing (X) · tên` + cảnh báo "đang làm"; xung đột không ghi đè.

### 6.2 Excel Microsoft 365 (Graph)

- Đăng nhập Microsoft (trình duyệt), quyền **Files.ReadWrite.All (delegated)** + `offline_access`; cần Client ID + Tenant ID (người dùng hoặc IT đăng ký ứng dụng trong Microsoft Entra ID).
- Ghi theo vùng ô (PATCH range), đọc lại định kỳ; chỉ OneDrive for Business / SharePoint (OneDrive cá nhân không hỗ trợ chính thức).
- Nút **Kiểm tra quyền Microsoft**: được → đồng bộ 2 chiều; bị chặn ("Cần sự chấp thuận của quản trị viên") → hiện tin nhắn mẫu gửi IT, tạm dùng: **đọc tự động** file Excel chung đã đồng bộ (chỉ đọc) + **Copy cột** 1 cú bấm.

### 6.3 Trình hướng dẫn "Làm chung" (Menu > Làm chung...)

Màn hình đầu: **PIC** / **Thành viên**. Mỗi bước: giải thích, nút làm hộ, nút **Kiểm tra** (✓ / lý do + cách sửa), Quay lại / Tiếp, nhớ bước đang làm.

PIC:
- A. Tạo dự án (workspace cha = A, tên hiển thị)
- B. Chọn cách làm chung (Kết hợp khuyên dùng; Excel Microsoft hoặc Google Sheet; + Phòng LAN)
- C. Thư mục chung: tạo + chia sẻ trên OneDrive (Có thể chỉnh sửa) → chọn thư mục trên máy → kiểm tra ghi / đọc, tạo cấu trúc
- D1. Excel Microsoft: đăng nhập, Kiểm tra quyền, chọn / tạo file Excel chung, tạo sheet
- D2. Google Sheet: tạo Sheet → Apps Script (Copy mã) → Triển khai Web App → dán URL → Kiểm tra kết nối
- E. Danh sách chuẩn & mẫu cột
- F. Tài khoản B, C… & thành viên & phân công
- G. Tạo **mã mời** (`.jcinvite`): gửi riêng từng người; cảnh báo link = mật khẩu; Đổi link khi thu hồi
- H. Bảng điều khiển PIC: tiến độ, ai đang làm gì, Q&A chờ, xung đột, đèn đồng bộ
- I. Xuất bản báo cáo (ảnh thật + công thức, có ngày giờ, có thể khóa)

Thành viên:
1. Nhận mã mời + link thư mục
2. OneDrive: **Thêm lối tắt vào Tệp của tôi**, chờ đồng bộ ✓
3. JCrawl > Làm chung > Thành viên: dán mã mời, chọn thư mục (+ đăng nhập Microsoft nếu dùng Excel)
4. Nhập tên, xác nhận tài khoản / nhánh được giao
5. JCrawl tạo workspace con, nhận danh sách chuẩn + mẫu cột
6. Crawl; đèn **Đồng bộ** xanh / vàng / đỏ; cảnh báo "đang làm"
7. Q&A / Info: ảnh tự lên, câu trả lời tự về
8. **Gửi hết & đóng** trước khi tắt Burp

PIC kiêm thành viên: chọn PIC, tự giao tài khoản ở bước F, các bước 5–8 gộp vào.

Kèm: trang trợ giúp "Làm chung" (sơ đồ + câu hỏi hay gặp), mọi bước ghi Nhật ký.

### 6.4 Chia nhỏ

| Bước | Nội dung |
|---|---|
| P5a | Trình hướng dẫn + thư mục chung + mã mời + phân công + đèn Đồng bộ + Bảng điều khiển PIC |
| P5b | Bảng làm việc 2 chiều: Excel Microsoft 365 (Graph) + Google Sheet; ảnh; dựng lại từ thư mục |
| P5c | Xung đột, nhật ký thay đổi, xuất bản phiên bản |
| P5d | Phòng LAN báo "đang làm" tức thì |

## 7. Kiểm thử

Mỗi bước: test tự động (đọc lại `.xlsx`, kiểm tra công thức & số liệu Tổng quan, mô phỏng 2 thành viên ghi cùng lúc / xung đột / mất mạng), chụp màn hình giao diện, hướng dẫn ngắn trong app.

## 8. Nguồn tham khảo

- Microsoft Graph – Working with Excel: https://learn.microsoft.com/en-us/graph/api/resources/excel
- Microsoft Graph – Update range: https://learn.microsoft.com/en-us/graph/api/range-update
- Google Sheets API – Usage limits: https://developers.google.com/sheets/api/limits
- Apps Script – OverGridImage (2 MB): https://developers.google.com/apps-script/reference/spreadsheet/over-grid-image
