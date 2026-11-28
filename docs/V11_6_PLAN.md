# Plan V_11.6 — CHƯA LÀM, chờ duyệt

> Quy tắc phiên bản: các mục dưới đây là thay đổi tính năng nên tăng MINOR thành **V_11.6**.
> Nếu bạn coi việc dựng lại tab Excel/Workspace (mục C) là đổi kiến trúc thì đổi thành **V_12.6**.

Mục lục

- **A.** Request bị chặn bấm "Gửi lên Server" không hiện trong Logger++, và thêm phạm vi chặn
- **B.** Bấm chip Excel mở đúng dòng trong file Excel, và lỗi gộp API `/api/a/{id}/c/{id}`
- **C.** Dựng lại tab Excel và quản lý Workspace/Dự án (gồm cả P1–P8 đã đề xuất trước)
- **D.** Trả lời chi tiết các câu hỏi về nhóm
- **E.** Luồng xuất Excel hỏi khách hàng và cách biết khách đã trả lời
- **F.** Kiểm thử và phát hành
- **G.** Các điểm cần bạn chọn

---

## A. Chặn request → "Gửi lên Server" không tới Logger++ / server

### A1. Nguyên nhân (đọc code, chưa sửa)

Có hai đường gửi khác nhau:

| Trường hợp | Code đang làm | Logger++ |
|---|---|---|
| API đã xong (có response), bấm gửi lại | `api.http().sendRequest(...)` — JCrawl tự gửi qua bộ HTTP của Burp | **Thấy** (tool = Extensions) |
| Request đang **chặn chờ duyệt**, bấm "Gửi lên Server" | `doNotIntercept(finalReq)`: trả request về cho **Proxy Burp** tự gửi tiếp. JCrawl không tự gửi | Thường **không thấy** |

Đường thứ hai hỏng vì:

1. Luồng Proxy bị giữ (tối đa 10 phút) trong lúc bạn duyệt. Sau vài chục giây trình duyệt thường đã hủy kết nối (XHR timeout, HTTP/2 stream đóng). Khi đó Burp **bỏ** request, không gửi.
2. Bộ dự phòng `ForwardDeliveryGuard` chỉ chạy khi Burp **không gọi** `handleRequestToBeSent` trong 1,2 giây. Nếu Burp đã gọi hàm đó (guard coi như "đã gửi") rồi mới hủy vì phía trình duyệt đã đóng, thì dự phòng **không chạy** và request mất luôn.
3. Logger++ ghi theo phản hồi. Request mà Proxy không gửi đi thì không có dòng nào cả.

Kết luận: dựa vào Proxy để gửi một request đã giữ lâu là không chắc chắn. Đường "đã xong thì gửi được" chạy ổn vì JCrawl tự gửi.

### A2. Sửa

1. **Mặc định mới cho "Gửi lên Server" khi đang chặn: JCrawl tự gửi qua `api.http()`**, giống hệt đường đã chạy được.
   - Request luôn tới server và Logger++ luôn thấy (Extensions).
   - Response thật được trả lại cho trình duyệt bằng spoof, khi Burp hỗ trợ (cách mà "Gửi riêng" đang làm). Nếu Burp không hỗ trợ spoof thì drop phía trình duyệt; API vẫn lưu đủ request và response.
   - API được lưu vào Cây với response thật. Dòng [Chờ duyệt] chuyển thành "Đã gửi · HTTP 200".
2. **Phương án cũ vẫn giữ** dưới tên "Trả về Proxy (Burp tự gửi)". Bộ dự phòng được sửa: nếu sau 5 giây **không có response Proxy** cho `messageId` đó (kiểm bằng `handleResponseReceived`, chứ không chỉ "đã gọi ToBeSent") thì JCrawl tự gửi bù đúng một lần.
3. **Chỉ báo đường gửi** trên mỗi kết quả: `qua Burp (Logger++ thấy)` / `qua Proxy` / `gửi riêng (Logger++ KHÔNG thấy)`.
4. **Chip cảnh báo trên thanh trên** khi "Gửi riêng" hoặc `quietSend` đang bật: "⚠ Gửi riêng: Logger++ không thấy". Bấm chip để tắt.
5. **Nút "Kiểm tra Logger++"** trong Cài đặt → Gửi:
   - gửi một request `GET` vô hại (tới host đang chọn, đường dẫn `/?jcrawl-check=<số>`) qua đúng đường đang dùng;
   - nhắc bạn tìm `jcrawl-check` trong Logger++;
   - nhắc bật "Log Extensions/Other tools" trong Logger++ nếu không thấy.
6. **Gửi hàng loạt request đang giữ** (FAKE_200 / Proxy-Held, bấm "Gửi" trên Cây) đã đi qua `bridge.send` = `api.http()` nên Logger++ thấy. Có kiểm lại trong test, trừ khi "Gửi riêng" đang bật.

### A3. Phạm vi chặn mới

Hiện chỉ có: Mọi host / 1 host / 1 host + path / Khi đang crawl 1 nhánh. Thêm:

| Phạm vi | Ý nghĩa |
|---|---|
| **Trong Scope của Burp** | `api.scope().isInScope(url)` — dùng đúng Target → Scope của Burp |
| **Danh sách host/domain** | Ô nhập nhiều dòng: `api.example.com`, `*.example.com` (wildcard), `example.com:8443` (host kèm cổng) |
| **Loại trừ** | Danh sách không chặn (ví dụ `*.google-analytics.com`, `cdn.*`); áp dụng sau mọi phạm vi |
| **Chỉ đường dẫn khớp** | Tùy chọn thêm: tiền tố hoặc mẫu path (`/api/*`, `/admin/**`) |
| **Chỉ API trong file Excel** | Chỉ chặn request khớp một dòng Excel chuẩn |

- Kết hợp được: (Scope **hoặc** danh sách host) **và** path **và không** nằm trong loại trừ.
- Nằm ở Cổng chặn → Phạm vi, với phần xem trước "Request vừa rồi: có/không bị chặn vì ...".
- Lưu trong cấu hình workspace. Có nút "Chép từ Scope Burp" để điền nhanh danh sách host.

---

## B. Chip Excel và lỗi gộp API

### B1. Bấm chip Excel mở đúng dòng trong file Excel

Hiện trạng: khi tải Excel chỉ lưu **tên file** (`TargetList.fromRows(..., f.getName(), ...)`) nên không mở lại được.

Sửa:

1. Khi tải Excel chuẩn, lưu **đường dẫn đầy đủ**, tên sheet và số dòng thật của từng mục.
   - Đồng thời chép một bản vào thư mục dự án (`du-an/excel-chuan/<tên>.xlsx`) để file chuyển máy hoặc đổi tên vẫn mở được. Thành viên nhận bản này khi đồng bộ.
2. **Bấm trái chip Excel** (trên Cây, bảng API, Inspector, tab Dự án):
   - JCrawl ghi "ô đang chọn" = đúng dòng đó vào file (activeCell + topLeftCell của sheet trong `xl/worksheets/sheetN.xml` và `workbookView activeTab`), lên một **bản sao tạm** chứ không phải file gốc;
   - rồi mở bằng Excel (`Desktop.open`). Excel mở ra đứng ngay dòng đó, dòng được tô vàng nhạt.
   - Nếu file đang mở sẵn trong Excel thì Windows không nhảy dòng được. Lúc đó hiện thông báo "Dòng 57, sheet API" và tự chép "A57" để bạn bấm Ctrl+G.
3. **Bấm phải chip**: Mở file gốc · Mở bản sao tại dòng · Xem dòng ngay trong JCrawl (popup gồm các cột của dòng đó) · Chép "Sheet!A57".
4. **Các chip khác đều dẫn thẳng tới chỗ của nó** (P7): Q&A → mở Q&A đó; Lỗi → tab Lỗi đúng dòng; Trùng → API gốc; Vai trò → cột vai trò; Nhánh → nhánh trên Cây; Tài khoản → cột tài khoản trong tab Dự án.
   - Có **Quay lại / Tiến** (Alt+← / Alt+→) giống trình duyệt.

### B2. Lỗi `/api/a/{id}/c/{id}` mỗi ID thành một API gốc mới

Đã chạy thử hàm gộp với template này: `/api/a/1/c/2`, `/api/a/abc/c/xyz`, `?x=1`, `/` cuối, `a-b_c` đều về **một** khóa `/api/a/{id}/c/{id}`. Lõi gộp đúng. Các trường hợp dưới đây làm tách sai (khả năng cao):

| # | Trường hợp | Vì sao thành API mới |
|---|---|---|
| 1 | Request có **tiền tố** trước path Excel: `/tenant/55/api/a/1/c/2`, `/v2/api/...` (khớp nhờ "cho phép tiền tố") | Chỉ phần khớp template được thay `{id}`. **Tiền tố giữ nguyên** nên mỗi tenant hay ID ở tiền tố tạo khóa mới |
| 2 | Cùng API nhưng **khác host/cổng/http-https** (api.x và www.x, :443 và :8443) | Khóa gộp có host + cổng + giao thức |
| 3 | Dòng Excel có **query** (`?type=1`) và chế độ khớp query ≠ Bỏ qua | Request thiếu hoặc khác query thì không khớp dòng nên dùng path thô |
| 4 | Dòng Excel có **cột host** khác host request | Không khớp dòng |
| 5 | Mở Excel **sau** khi đã bắt API, hoặc đổi workspace | Đã có "regroup" nhưng chỉ chạy khi khóa cũ thô hoặc có `{`. Cần kiểm lại khi tải lại file |
| 6 | API gốc đã bị **xóa/ẩn** | Request sau trở thành gốc mới ("Đã xóa trước đây") |
| 7 | **Không có Excel**, chỉ dùng mẫu "Cùng 1 API" | Mẫu chỉ lưu ở **workspace đó**. Workspace khác và máy thành viên không có nên tách |

Sửa:

1. **Gộp theo dòng Excel trước tiên**: request nào khớp cùng một dòng Excel **trên cùng host** thì khóa gộp = `METHOD + host:cổng + "row:" + số dòng`, bỏ qua tiền tố và query. **Đã chốt: khác host thì luôn tách riêng.**
2. Tiền tố: các đoạn trông như ID trong tiền tố cũng thay bằng `{id}`.
3. Query của dòng Excel chỉ dùng để **chọn dòng** khi có nhiều dòng cùng path. Không có dòng khác thì vẫn khớp.
4. Tải lại Excel hoặc mở workspace thì **luôn regroup**, có thông báo "Đã gộp N API vào M dòng Excel". Mỗi API hoàn tác được bằng "Tách ra".
5. Mẫu "Cùng 1 API" được **lưu ở dự án và đồng bộ** cho cả nhóm (bản ghi `pattern`).
6. **Inspector → "Vì sao là API mới / trùng?"**: hiện khóa gộp, dòng Excel đã khớp (hoặc lý do không khớp: host, query, tiền tố...) và nút "Gộp với API ..." / "Thêm mẫu".
7. Test: bảng 20+ ca từ bảng trên (tiền tố, host, query, chữ/số/uuid, hai `{id}` trùng tên, Excel tải sau).

---

## C. Dựng lại tab Excel và Workspace/Dự án

### C1. Vấn đề hiện tại

- Tab Excel có quá nhiều tab con chồng chéo (File Excel, Dự án, Hỏi đáp, Đồng đội cũ, Phòng LAN, Báo cáo, Sheet...).
- Workspace, Dự án và Nhóm đang nằm ở ba chỗ khác nhau. Bấm "Workspace mới..." **lại** tạo trong thư mục dự án cũ. Mở Burp lên **tự mở lại** dự án cũ.

### C2. Cấu trúc mới (đề xuất)

Thanh trên cùng có một nút duy nhất, **[Dự án: Tên ▾]**:

```
Dự án: ShopABC ▾
 ├─ Dự án mới...            (chọn thư mục MỚI, đặt tên, tạo workspace đầu tiên)
 ├─ Mở dự án...             (chọn thư mục có du-an.json)
 ├─ Dự án gần đây  >        (5 cái gần nhất)
 ├─ ─────────
 ├─ Workspace của dự án này >  A-system (đang mở) · B-staff · C-guest · + Workspace mới trong dự án
 ├─ Nhóm & đồng bộ...       (wizard)
 └─ Mở thư mục dự án
```

- Mở Burp **không còn tự mở dự án cũ**. Nếu dự án Burp chưa gắn workspace thì hỏi: Tiếp tục "ShopABC" / Dự án mới / Mở dự án khác.
- Tạo workspace **trong thư mục khác dự án hiện tại** thì báo rõ để tránh trộn.

Tab Excel chỉ còn **4 tab con**:

| Tab | Nội dung |
|---|---|
| **1. Tổng quan** | Thẻ việc cần làm (checklist P2): ☐ Đặt tên bạn · ☐ Tải Excel chuẩn · ☐ Tài khoản web · ☐ Nhóm (tùy chọn) · ☐ Gửi khách (tùy chọn). Số liệu nhanh và nút tắt |
| **2. Danh sách API** | Bảng dự án (dòng × tài khoản web), lọc, chip. Excel chuẩn được tải ngay ở đây (nút "Tải/Đổi Excel chuẩn") |
| **3. Hỏi đáp & Khách hàng** | Q&A + các vòng gửi khách + bảng trạng thái trả lời (mục E) |
| **4. Báo cáo & Xuất** | Xuất Excel (mẫu: Nội bộ / Gửi khách / Theo tài khoản / Theo người), sao lưu |

- "Đồng đội (cũ)" và "Phòng LAN" được **ẩn**, chuyển vào Cài đặt → Nâng cao.
- Dữ liệu Đồng đội cũ được **chuyển sang dự án một lần** (P4), có nút "Nhập dữ liệu Đồng đội cũ".
- **Trợ giúp nhanh** (P5): mỗi tab có dòng giải thích 1 câu và nút "?" mở hướng dẫn. Từ ngữ thống nhất: *Người làm* (hau, an, bình) ≠ *Tài khoản web* (system, staff).

---

## D. Trả lời các câu hỏi về nhóm (hiện có / sẽ thêm)

| Câu hỏi | Hiện tại (V_11.5) | V_11.6 sẽ làm |
|---|---|---|
| PIC thêm người có đặt tên cho người đó được không? | **Được**: wizard bước B, mã mời chứa tên, vai trò và tài khoản web được gán | Giữ nguyên |
| PIC đặt/gán tài khoản web cho người đó? | **Được**, gán trong mã mời | Giữ nguyên, thêm sửa sau khi đã mời |
| Thành viên **tự đổi tên** mình? | Chưa có ô riêng (tên lấy từ mã mời hoặc tên máy) | **P1**: ô "Tên của bạn" ở Tổng quan. Đổi tên tạo bản ghi `rename cũ→mới`; mọi máy đổi theo: ô "Done (an)" thành "Done (An Nguyễn)", thống kê gộp lại |
| PIC có quyền khóa tên? | — | Tùy chọn "Chỉ PIC đổi tên" (mặc định: ai cũng đổi tên mình, PIC đổi được mọi người) |
| Thành viên **thêm tài khoản web**? | **Được**: ai cũng thêm, sửa, gộp (V_11.5) | Giữ nguyên |
| Có **audit**, PIC có biết không? | Mỗi bản ghi `acct` có createdBy/createdAt/mergedBy; có ghi nhật ký; có thông báo nhưng nhỏ | **Nhật ký thay đổi nhóm**: bảng "Ai · lúc nào · làm gì" (thêm/đổi tên/gộp/tách tài khoản, đổi tên người, đổi vai trò). Chấm đỏ trên [Dự án ▾] khi có thay đổi mới chưa xem. Tài khoản mới có nhãn "Mới · do an thêm" cho tới khi PIC bấm "Đã xem". Tùy chọn **"Tài khoản mới cần PIC duyệt"** (mặc định tắt): trước khi duyệt cột vẫn hiện nhưng có nhãn chờ duyệt |
| Không có Excel chuẩn thì gộp API hai bên ra sao? | Mỗi API thành một dòng theo khóa `METHOD + path` (ID số/uuid tự thành `{id}`). Hai người bắt cùng API thì về **cùng dòng**. ID **dạng chữ** thì tách dòng vì mẫu "Cùng 1 API" không đồng bộ | (1) Mẫu "Cùng 1 API" được đồng bộ cả nhóm (B2.5). (2) **Gợi ý gộp**: nhóm các dòng chỉ khác một đoạn path; PIC hoặc thành viên bấm "Gộp thành /api/a/{id}/c/{id}" và nhóm nhận mẫu mới. (3) Nút **"Lấy danh sách hiện tại làm Excel chuẩn"**: chốt các dòng đã gộp thành danh sách chuẩn cho cả nhóm |

---

## E. Xuất Excel hỏi khách hàng và cách biết khách đã trả lời

### Hiện có (V_11.4–11.5)

1. Hỏi đáp → **"Gửi khách hàng"** tạo vòng `bao-cao/khach-hang/vong-N_ngày/`:
   - `goc/`: bản gốc, chỉ đọc, không gửi;
   - `chia-se/`: bản để gửi khách (chia sẻ link OneDrive hoặc gửi mail).
2. Khách ghi vào cột Trả lời (và dán ảnh) trong file `chia-se`.
3. Bạn bấm **"Nhập phản hồi"**. JCrawl so `chia-se` với `goc` và liệt kê từng thay đổi (trả lời, trạng thái, ghi chú, ảnh, dòng mới hoặc bị xóa) để bạn **nhận từng mục**.
4. Mục đã nhận: Q&A có câu trả lời "Khách hàng" và ô hiện "Done (khách hàng)". Cả nhóm nhận qua đồng bộ.

Điểm khó: bạn **phải tự nhớ** bấm "Nhập phản hồi" và không có chỗ nào cho biết vòng nào khách đã trả lời.

### V_11.6 thêm

1. **Bảng vòng gửi khách** (tab Hỏi đáp & Khách hàng):

   | Vòng | Ngày gửi | Số câu | Đã trả lời | Chờ nhận | Trạng thái |
   |---|---|---|---|---|---|
   | 2 | 20/11 | 14 | 9 | 3 | **Khách đã sửa file · 3 mục chờ nhận** |

2. **Tự phát hiện khách đã trả lời**: JCrawl theo dõi file `chia-se` (thời gian sửa và kích thước, mỗi 1 phút; OneDrive tự kéo file khách sửa về).
   - Khi file đổi: hiện thông báo "Khách hàng đã sửa vòng 2 — 3 câu trả lời mới" và chấm đỏ trên tab;
   - bấm vào thì mở thẳng màn Nhập phản hồi.
3. **Trạng thái mỗi câu Q&A**: Chưa gửi · Đã gửi (vòng 2) · Khách đã trả lời, chờ nhận · Đã nhận · Hỏi lại (vòng 3). Lọc được trên bảng.
4. **File gửi khách dễ hiểu hơn**:
   - sheet đầu "Hướng dẫn" ghi bằng chữ: ghi vào cột vàng, dán ảnh vào cột Ảnh, không xóa cột ID;
   - cột Trả lời được tô vàng; các cột khác khóa (bảo vệ sheet, không mật khẩu);
   - cột "Trạng thái" có danh sách chọn: Đúng / Sai / Cần thêm thông tin.
5. **Vòng hỏi lại**: chọn các câu chưa rõ, bấm "Hỏi lại" để tạo vòng mới chỉ với những câu đó, kèm lịch sử trả lời trước.
6. ~~Khách trả lời qua Google Sheet~~ → **BỎ** (đã chốt: khách chỉ dùng Microsoft Excel). Thay bằng hai đường Microsoft:
   - **Link OneDrive** (khuyên dùng): chia sẻ file `chia-se` bằng link OneDrive có quyền sửa. Khách mở bằng Excel Online hoặc Excel desktop rồi ghi vào; OneDrive tự kéo bản khách sửa về máy bạn, nên E2 tự phát hiện mà không cần làm gì.
   - **Gửi mail / file đính kèm**: khách gửi lại file. Bạn bấm "Nhập file khách gửi lại..." (hoặc kéo thả file vào tab), JCrawl chép file vào `chia-se/` của đúng vòng (nhận ra vòng nhờ mã vòng ẩn trong file) rồi mở màn Nhập phản hồi.
   - Mã vòng và ID câu hỏi được ghi ẩn trong file để nhận đúng vòng kể cả khi khách đổi tên file. Ảnh khách dán trong Excel vẫn được lấy ra như hiện nay.

---

## F. Kiểm thử và phát hành

- **Test đơn vị:**
  - đường gửi khi chặn (mock Burp: socket đóng, spoof có/không, guard 5 giây);
  - phạm vi chặn (scope, wildcard, cổng, loại trừ, path);
  - bảng 20+ ca gộp API;
  - ghi activeCell vào xlsx (mở lại bằng thư viện đọc kiểm tra);
  - đổi tên người qua 3 máy;
  - nhật ký nhóm;
  - phát hiện file `chia-se` đổi.
- **Harness hub** (node mock): bản ghi `rename`, `pattern`, `acctok`.
- File khách gửi lại: đổi tên file, gửi nhầm vòng, mở bằng Excel Online (giữ mã vòng ẩn).
- **Ảnh xem trước UI** cho: thanh Dự án ▾, 4 tab Excel, bảng vòng khách, phạm vi chặn, "Vì sao là API mới".
- Cập nhật README, hướng dẫn, version `V_11.6` và release qua PR như cũ.

Thứ tự làm (mỗi bước build + test):

1. A (lỗi Logger++, gấp nhất)
2. B2 (gộp API)
3. B1 + P7 (chip dẫn đường)
4. A3 (phạm vi chặn)
5. C (cấu trúc)
6. D
7. E
8. F

---

## G. Cần bạn chọn

1. "Gửi lên Server" khi chặn: mặc định **JCrawl tự gửi qua Burp** (khuyên dùng) hay giữ "Trả về Proxy"?
2. ~~Gộp API theo dòng Excel bỏ qua host~~ — **Đã chốt: khác host thì tách riêng.**
3. Bấm trái chip Excel: **mở Excel tại dòng** (khuyên dùng) hay xem dòng ngay trong JCrawl?
4. Ẩn "Đồng đội (cũ)" và "Phòng LAN" vào Nâng cao: **đồng ý** (khuyên dùng) hay xóa hẳn?
5. Tài khoản web mới: **chỉ thông báo cho PIC** (khuyên dùng) hay bắt buộc PIC duyệt?
6. Đổi tên người: **ai cũng tự đổi tên mình** (khuyên dùng) hay chỉ PIC đổi?
7. ~~Khách trả lời qua Google Sheet~~ — **Đã chốt: bỏ, khách chỉ dùng Microsoft** (link OneDrive hoặc file gửi mail).
8. Phiên bản: **V_11.6** hay V_12.6?
