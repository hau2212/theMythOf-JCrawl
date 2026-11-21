# theMythOf-JCrawl V_7.0_beta — Kế hoạch chi tiết

> Bối cảnh sử dụng thực tế: **~70% thời gian là crawl để thu API** → đưa sang **Logger++** (bật log tool *Extensions*)
> → Logger++ đẩy API kèm response **2xx** lên server quản lý → đánh dấu API có bug, mỗi API là một test case.
> Website có **N roles**, mỗi role chỉ thấy một phần tính năng → cần crawl theo từng role mà **không bị trùng**,
> và phát hiện **BAC** (Broken Access Control) khi role truy cập được thứ nó không được thấy.

Trạng thái: ✅ đã hoàn thành trong V_7.0_beta

---

## 1. Bảng API kiểu Burp HTTP History ✅

- Thứ tự cột mặc định giống Burp: `# | Method | Host | Path | Status | Length | Kết quả Role | Nhánh | Đánh giá | Time`.
- Path font monospace, luôn thẳng một cột; số căn phải; Method là nhãn màu cố định độ rộng.
- Cột **Kết quả Role**: dải ô vuông, mỗi ô = 1 role (xanh OK · vàng bị chặn · cam **bypass** · đỏ lỗi · xám chưa gửi).
- Cột ít dùng (`Group`, `Copy`, `Đã copy`, `Source`, `Excel`, `Trùng`, `Đã gửi`) ẩn mặc định, bật lại bằng chuột phải lên header.
  Bố cục lưu bằng key mới `tmoc.ui.columns.api.v7` (người dùng cũ tự nhận bố cục mới).
- Cây bên trái chỉ còn **nhánh + số đếm** (giống Site map) → bấm nhánh để lọc bảng. Công tắc "Hiện API trong cây" để quay về kiểu cũ.

## 2. Giao diện ẩn/hiện tối đa ✅

| Phím | Ẩn / hiện |
|---|---|
| `Ctrl+Alt+1` | Cây nhánh bên trái |
| `Ctrl+Alt+2` | Khung chi tiết dưới (Role + Request/Response) |
| `Ctrl+Alt+3` | Thanh công cụ của tab API |
| `Ctrl+Alt+H` | Thanh trên cùng của extension |
| `Ctrl+Alt+0` | **Chế độ Tối đa**: chỉ còn bảng API |

- 3 bố cục sẵn: **Crawl** (cây + bảng), **Phân tích** (bảng + chi tiết), **Tối đa** (chỉ bảng). Nút `Bố cục ▾` trên toolbar.
- Trạng thái ẩn/hiện được nhớ (`tmoc.ui.layout.v7`).

## 3. Crawl N roles không trùng & phát hiện BAC ✅ (đề xuất trọng tâm)

### 3.1 Vấn đề
Crawl role A thu được tập API(A); crawl role B thu được API(B). Phần giao nhau bị bắt 2 lần; phần riêng thì không biết
role kia *có gọi được không* (UI giấu ≠ server chặn). BAC = **server cho phép** cái mà **UI của role không cho thấy**
(hoặc ma trận quyền nói là không được).

### 3.2 Giải pháp: "Thấy bởi" (crawl) vs "Gọi được" (replay)
1. **Tự nhận diện role đang crawl**: mỗi request đi qua Proxy được so token/cookie với các rule của Role
   (`Authorization`, `Cookie: JSESSIONID=...`). Khớp role nào → gán `crawledBy = {role}`. Không khớp → hỏi/gán "Acc Crawl".
   Có ô chọn thủ công `Đang crawl bằng: [Admin ▾]` trên thanh trên cùng để ép.
2. **Dedup theo endpoint, không theo role**: gặp lại endpoint đã có → **không tạo API mới**, chỉ thêm role vào tập
   "Thấy bởi" (+ ghi nhánh UI nơi role đó thấy). Hết trùng crawl.
3. **Replay chéo tự động**: mọi API được gửi lại bằng token của *tất cả* role (đã có sẵn) → tập "Gọi được".
4. **Ma trận Truy cập** (tab Báo cáo): hàng = API, cột = role, ô có 4 trạng thái:

   | Ô | Nghĩa |
   |---|---|
   | 👁✓ | Role thấy trong UI và gọi được → hợp lệ |
   | ·✗ | Không thấy, server chặn (401/403) → hợp lệ |
   | **·✓ (đỏ)** | **Không thấy trong UI nhưng gọi được 2xx và body giống bản gốc → NGHI BAC** |
   | 👁✗ | Thấy nhưng bị chặn → lạ, cần xem (token hết hạn?) |

5. **So sánh body** (không chỉ status/độ dài): độ giống % giữa response role và response gốc (bỏ timestamp/CSRF/nonce).
   2xx nhưng body là trang lỗi/login → không tính BAC (giảm false positive).
6. **Ma trận quyền mong đợi (tùy chọn)**: khai báo "nhánh `Admin > *` chỉ ADMIN" → vi phạm được đánh dấu chắc chắn,
   dùng cho trường hợp role crawl thấy hết (ví dụ role thấp vẫn thấy menu nhưng lẽ ra không được).
7. **Thứ tự crawl khuyến nghị**: role thấp → cao (Guest → User → Manager → Admin). Mỗi role sau chỉ phải đi các màn hình
   *mới*; extension hiện "Màn hình role này chưa đi" dựa trên nhánh đã có.

### 3.3 Đầu ra cho test case
Mỗi ô **NGHI BAC** sinh 1 test case: `API # · Method · Path · Role tấn công · Role sở hữu · Bằng chứng (status, độ giống %)`
→ xuất Excel/JSON và đẩy sang Logger++ (mục 4).

## 4. Luồng Logger++ → server quản lý ✅

- Logger++ ghi được request do extension gửi khi bật log tool **Extensions**. Vì vậy thêm nút **"Đẩy sang Logger++"**:
  - Chọn API (hoặc "mọi API có response gốc 2xx", đã dedup) → gửi lại **1 lần** qua HTTP client của Burp.
  - Gắn header đánh dấu tùy chọn `X-JCrawl-Ref: API#12;role=Admin;branch=Orders` để lọc trong Logger++
    (`Request.Headers CONTAINS "X-JCrawl-Ref"`). Có thể tắt header nếu sợ lộ dấu vết trên server mục tiêu.
  - **Mặc định chỉ GET/HEAD**; POST/PUT/PATCH/DELETE phải xác nhận riêng (tránh thay đổi dữ liệu thật).
  - Dùng chung hàng đợi chống 429.
- Phương án không cần replay: xuất **JSON/HAR/CSV** (request + response gốc 2xx + trường test case) để nạp thẳng vào server quản lý.

## 5. Làm việc nhóm an toàn (không bị SOC đánh giá là bất thường) ✅ + ✅ một phần

### 5.1 Vì sao LAN Host hiện tại rủi ro
- Mở cổng nghe trên `0.0.0.0` từ trong Burp + client poll mỗi 700 ms → giống **beaconing / C2** dưới góc nhìn EDR/SOC.
- Passcode rỗng = ai trong LAN cũng đọc được danh sách API của mục tiêu.

**Đã sửa ngay (✅)**: mặc định chỉ nghe `127.0.0.1`; bắt buộc passcode; so sánh passcode thời gian hằng; mở LAN phải chủ động bật.

### 5.2 Phương án thay thế (xếp theo độ an toàn)

| # | Cách | Lưu lượng mạng từ Burp | Đánh giá |
|---|---|---|---|
| **A (khuyến nghị)** | **Thư mục đồng bộ chung**: OneDrive / SharePoint / Google Drive for desktop / ổ mạng SMB công ty. Mỗi thành viên ghi **file riêng** `team/<tên>.jsonl` (append-only), extension đọc file của người khác (theo dõi thay đổi file). | **Không có** — chỉ là đọc/ghi file local; việc đồng bộ do client đồng bộ chính thức của công ty làm | An toàn nhất với SOC, không mở cổng, không xung đột ghi vì mỗi người một file; merge theo thời gian + phát hiện xung đột như hiện nay |
| **B** | **Excel nhóm (Team Workbook)**: mỗi người xuất `.xlsx` → một người "Gộp" nhiều file thành bản tổng (giữ màu, ai tìm, xung đột). Có thể đặt trên SharePoint để đồng sửa trong Excel Online. | Không | Thủ công hơn nhưng tuyệt đối "bình thường"; hợp quy trình báo cáo |
| C | Google Sheets (đã có) | HTTPS tới `script.google.com` | Ổn nếu công ty cho dùng Google; **cần giới hạn Web App cho tài khoản trong tổ chức**, không để "Anyone" |
| D | Git nội bộ (GitLab/Gitea công ty) qua file JSONL ở cách A | Do git client làm | Có lịch sử, phù hợp nhóm kỹ thuật |
| ✗ | LAN Host | Mở cổng + poll | Chỉ dùng trong lab; ẩn vào "Nâng cao" |

**Dữ liệu chia sẻ**: chỉ metadata (method, host, mẫu path, trạng thái, nhánh, role thấy/gọi được, tag, người tìm, ghi chú bug).
**Không bao giờ** chia sẻ token/cookie/raw request mặc định; nếu cần chia sẻ request thì tự che `Authorization`/`Cookie`.

### 5.3 Tính năng Excel cho nhóm ✅
Team Workbook 6 sheet: `Tổng hợp` · `Ma trận Role` (Thấy/Gọi được/BAC) · `Test case & Bug` (ID, mức độ, trạng thái, người làm)
· `Phân công` (thành viên ↔ nhánh/role) · `Xung đột` · `Theo thành viên`. Nhập ngược để cập nhật trạng thái/phân công.

## 6. Làm lại mục Action → "Bảo vệ dữ liệu" ✅ (đổi kiến trúc lõi)
- Gộp 6 cơ chế chặn (cờ Config, ActionMode/API, endpoint hold, cờ chặn chờ 4 mức, luật Proxy cũ, Sổ cái) về **một danh sách luật**,
  tự chuyển đổi luật cũ khi mở workspace.
- Câu hỏi 1 (khi trình duyệt gửi POST/PUT/PATCH/DELETE): *Cho đi bình thường* / *Hỏi tôi trước* / *Giữ lại, không gửi (200 giả)*
  + phạm vi: tất cả / method / nhánh / API này. Luật hiển thị bằng câu tiếng Việt + ô "Thử luật".
- Câu hỏi 2 (khi gửi lại mà ID đã bị xóa): cột "Cách gửi lại" = *Nguyên bản* / *Thay ID còn sống* / *Tạo mới rồi gửi* / *Dùng lại 200 cũ*.
- Giải thích sau mỗi lần gửi: "ID 15 đã bị xóa → thay bằng 18 (từ GET /orders) → Admin 200, Guest 403".

## 7. Làm gọn tổng thể ✅
- Thanh trên 1 hàng, chữ rõ nghĩa (bỏ `Đợi:0`, `API 12·6N·10/10R`, `Chờ:7(#1)`).
- 8 tab → 5: **API** (gộp Cây) · **Báo cáo** (Lịch sử + Ma trận Role + Excel) · **Roles** · **Cần xử lý** · **Cài đặt** (+ Log).
- Thuật ngữ: Nhả → *Tắt chặn* · Producer → *API tạo mới* · Sổ cái → *Luật chặn* · Khóa 200 → *Dùng lại kết quả cũ* · một mã `#12` duy nhất.

## 8. Lộ trình
| Đợt | Nội dung | Trạng thái |
|---|---|---|
| 7.0-a | Bảng kiểu Burp, cây lọc nhánh, ẩn/hiện bố cục, vá bảo mật TeamSync, nâng version | ✅ |
| 7.0-t | Tab Cây dạng bảng thẳng hàng (lưới cột cố định + header kéo độ rộng), ẩn khung chi tiết / tối đa | ✅ |
| 7.0-b | Crawl N roles (Thấy bởi / Gọi được), Ma trận Truy cập, so sánh body, test case BAC | ✅ |
| 7.0-c | Đẩy sang Logger++ + xuất JSON/HAR | ✅ |
| 7.0-d | Đồng bộ thư mục chung (A) + Team Workbook Excel (B) | ✅ |
| 7.0-e | "Bảo vệ dữ liệu" (gộp Action) + gộp tab + thuật ngữ | ✅ |

## 9. Ghi chú triển khai (đã làm)

- **Cây thẳng hàng**: `ui/TreeColumns` (lưới cột + header) · `TreeRenderer.layout*Grid` vẽ cột theo x tuyệt đối,
  hiệu chỉnh bằng vị trí thật của dòng khi vẽ · test `V70MultiRoleTeamTest.treeTabRowsLineUpOnTheColumnGrid`.
- **Crawl N roles**: `rolereq/CrawlRoleDetector` · `ApiGroup.seenBy` (lưu trong workspace) · `core/AccessMatrix` · `core/ExpectedAccess` · tab `ui/AccessMatrixTab`.
- **Logger++ / HAR**: `Engine.pushToLogger` (luồng riêng, giãn cách, mặc định chỉ GET/HEAD) · `export/HarExport` · `ui/LoggerPushDialog`.
- **Nhóm**: `TeamSyncService` chế độ `FOLDER` (chỉ đọc/ghi file local) · `collab/TeamWorkbook` (xuất 6 sheet + gộp).
- **Bảo vệ dữ liệu**: `core/DataProtection` (2 câu hỏi, câu tiếng Việt, gộp cấu hình cũ) · `Engine.explainProtection` (Thử luật, không ghi log) · `ui/DataProtectionDialog`.
  Gộp cấu hình cũ là **thao tác chủ động** (nút trong dialog), không tự chạy khi mở workspace để không đổi hành vi ngầm.
- **Tab**: `MainPanel.selectTab/selectedTab` cho tab nhóm Báo cáo / Cài đặt.

## 10. V_7.1_beta — Cổng chặn Proxy trực quan

Vấn đề người dùng gặp: menu "Chặn · 1" trộn 6 chế độ (chặn ở Proxy lẫn cách gửi lại), không nói rõ chặn **host nào / path nào**,
và "200 ảo" bị hiểu nhầm là trả 200 cho Server.

Giải pháp (`core/ProxyGate`, `ui/ProxyGateDialog`, `ui/ProxyFlowDiagram`):
- **Một** luật chính (id `gate-main`) trong sổ luật = công tắc + phạm vi (mọi host / 1 host / host + path / nhánh) + method + hành động.
- Hành động chỉ còn 3, mỗi cái có câu "điều gì xảy ra" + "dùng khi" + sơ đồ: Hỏi tôi trước · Giữ lại, trả 200 giả cho **trình duyệt** · Chặn hẳn.
- Cách gửi lại (Kho ID sống, tạo mới, dùng lại 200 cũ) tách hẳn sang câu 2 với thẻ **Đầu vào → Đầu ra**.
- Test: `V71ProxyGateTest`.
