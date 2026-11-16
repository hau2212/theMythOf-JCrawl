# theMythOf-JCrawl - Kế hoạch thiết kế lại giao diện (UI Redesign Plan)

> **Dành cho AI agent thực hiện (Gemini).** Đọc hết file này trước khi sửa code. Làm đúng thứ tự các
> giai đoạn (Phase 0 → 8), mỗi giai đoạn là 1 commit (hoặc 1 PR) riêng, build + test xanh rồi mới sang
> giai đoạn sau. Mọi chỗ ghi **PHẢI** là bắt buộc; **NÊN** là khuyến nghị.

---

## 0. Bối cảnh

- Repo: `hau2212/theMythOf-JCrawl` (Java 21, Gradle, Montoya API, Swing). Extension cho Burp Suite.
- Code giao diện: `src/main/java/themythofcrawl/ui/` (~7.400 dòng, 32 file). Lõi (không phải UI):
  `app/`, `core/`, `model/`, `place/`, `excel/`, `export/`, `send/`, `store/`, `burp/`, `rolereq/`.
- Bản gốc là extension Python/Jython **theMythOf-PCrawl** (repo `hau2212/theMythOfCrawl`). Bản Java đã có
  đủ chức năng; việc của kế hoạch này là **giao diện**: màu sắc, phân cấp thị giác, dễ nhìn, hiện đại.
- **Burp Suite dùng FlatLaf làm Look & Feel từ bản 2020.12** (sáng / tối / theme bên thứ ba). Mọi thiết kế
  phải đẹp ở **cả theme sáng và tối** của Burp.

### Người dùng phản ánh

1. Trong Burp, giao diện JCrawl "mất hết màu, chỉ còn chữ", không giống bản Python.
2. Tab **Lịch sử**: phần tổng hợp bên phải chỉ là 1 khối chữ đơn sắc (JTextArea monospace) → khó đọc.
3. Muốn phân cấp rõ ràng, thẩm mỹ hiện đại, tận dụng ưu điểm của Java.

### Kết quả kiểm tra trước khi viết kế hoạch

- Chạy giao diện ngoài Burp với FlatLaf 3.5 (`FlatLightLaf`, `FlatDarkLaf`) và mô phỏng việc theme lại
  toàn bộ cây component (`SwingUtilities.updateComponentTreeUI`): **màu vẫn hiển thị** (cây, bảng, chip,
  các mục Cấu hình). Vì vậy nguyên nhân (1) chưa được xác định chắc chắn. Có 2 khả năng:
  - Người dùng đang nạp **jar cũ** (bản prototype đầu tiên chỉ có chữ).
  - Hoặc có khác biệt chỉ xảy ra trong Burp thật (ví dụ `applyThemeToComponent`, font, theme của
    BurpCustomizer, hoặc emoji hiển thị thành ô vuông trên Windows).
- **Ảnh chụp người dùng gửi từ Burp thật (jar mới, có editor Pretty/Raw/Hex của Burp):** chữ trong cây
  vẫn đúng định dạng của extension (`Toppage · 0 API · -`), nhưng **mất toàn bộ phần vẽ của
  `TreeRenderer`**: không có nền bo tròn theo cấp, không icon, thư mục không in đậm. Nút bấm có màu thì
  vẫn hiện. → **Nguyên nhân gần như chắc chắn: Burp đã thay cell renderer của extension bằng renderer
  mặc định của Burp**. Khả năng cao nhất là lúc `MainPanel` gọi `host.applyTheme(root)`
  (`ui/MainPanel.java` ~dòng 134; `ui/TreeTab.java` ~dòng 201 cho cửa sổ riêng). Hàm này chạy
  `api.userInterface().applyThemeToComponent(...)` **sau khi** cây / bảng đã gắn renderer.
  Có thể Burp làm điều tương tự với renderer của bảng (chưa thấy được vì bảng trong ảnh đang trống).
- → **Phase 0 bắt buộc sửa và xác minh** việc này trước khi thiết kế lại.

---

## 1. Ràng buộc (PHẢI tuân thủ)

1. **Không đổi hành vi / dữ liệu.** Chỉ đổi giao diện. Không sửa `app/`, `core/`, `model/`, `place/`,
   `excel/`, `send/`, `store/`, `rolereq/` trừ khi kế hoạch ghi rõ (chỉ được thêm hàm *đọc* dữ liệu cho UI,
   không đổi logic). **Tuyệt đối không sửa `rolereq/RoleRules.java`.**
2. **Không thêm thư viện runtime.** Jar phải tự chạy trong Burp. **Không `import com.formdev.flatlaf.*`**
   (Burp có FlatLaf nhưng không đảm bảo class loader của extension nhìn thấy). Chỉ dùng:
   - `UIManager.getColor/getFont/getInt(key)` (đọc màu của theme hiện tại);
   - client property dạng chuỗi của FlatLaf (`putClientProperty("JButton.buttonType", "toolBarButton")`,
     `"FlatLaf.style"`, `"JTextField.placeholderText"`, `"JTextField.showClearButton"`,
     `"JComponent.outline"`, `"JTabbedPane.tabType"`...). Nếu không phải FlatLaf thì chúng bị bỏ qua vô hại.
3. **Không phụ thuộc emoji.** Emoji (📋 🗑 ⧉ 👻 🙈 ⏳ ✔ ↪ 🎯...) có thể thành ô vuông trên Windows/Java.
   PHẢI thay bằng **icon vẽ bằng Java2D** (mở rộng `ui/Icons.java`). Chữ tiếng Việt giữ nguyên.
4. **Hiệu năng giữ nguyên hoặc tốt hơn** (đo bằng `src/test/java/themythofcrawl/ui/Bench.java`, 5000 API):
   dựng lại cây ≤ 80 ms, nạp lại bảng API ≤ 150 ms, tick UI 200 ms không giật. Renderer **không tạo
   object mới mỗi lần vẽ** (cache Color / Font / Border / Icon). **Không dùng HTML trong ô bảng / cây**
   (HTML renderer của Swing rất chậm với hàng nghìn dòng).
5. **Theme động.** Người dùng đổi theme Burp khi extension đang chạy → màu PHẢI tự cập nhật (xem 3.1).
6. **Test giữ xanh.** `./gradlew build` phải qua toàn bộ test hiện có (49 test). Được thêm test, không xóa test.
7. Mỗi commit: tác giả `hau2212 <81369957+hau2212@users.noreply.github.com>`, không thêm dòng Co-Authored-By.

---

## 2. Phase 0 - Xác minh nguyên nhân "mất màu" (làm trước tiên)

1. Thêm **phiên bản + thông tin theme** vào giao diện để người dùng chụp màn hình là biết:
   - Tooltip của nút `📁 Workspace`/dòng trạng thái: `theMythOf-JCrawl 2.x.y · LAF: <UIManager.getLookAndFeel().getName()> · dark=<bool> · font=<tên, cỡ>`.
   - Trong Cấu hình → mục mới "8. Giới thiệu & chẩn đoán": phiên bản, LAF, `Panel.background`,
     `Table.background`, `Component.accentColor`, scale (`sun.java2d.uiScale`), nút **Copy thông tin chẩn đoán**.
2. Ghi 1 dòng vào Output của extension khi nạp: `theMythOf-JCrawl <ver> loaded · LAF=<name> · dark=<bool>`.
3. Hướng dẫn người dùng (ghi vào README mục "Gặp lỗi giao diện"): mở Extensions → Output kiểm tra dòng trên;
   nếu không có dòng này hoặc tab không tên `theMythOf-JCrawl` → đang chạy jar cũ, gỡ và nạp lại.
4. **Sửa lỗi renderer bị Burp thay** (PHẢI làm, đây là lỗi "mất màu" người dùng thấy):
   - Tạo `ui/kit/ThemedTree extends JTree` dùng cho mọi cây (`BranchTree` dòng ~66, `LogTab` dòng ~53).
     Nó giữ 1 renderer "của mình" và override:
     - `setCellRenderer(TreeCellRenderer r)`: nếu `r` không phải renderer của extension thì **bỏ qua**
       (vẫn gọi `super` với renderer của mình).
     - `updateUI()`: gọi `super.updateUI()` rồi gắn lại renderer của mình, đặt lại `rowHeight`,
       `largeModel`, `toggleClickCount`.
   - Tương tự cho bảng: `ui/kit/ThemedTable extends JTable`, override `updateUI()` và
     `setDefaultRenderer(...)`. Lưu map `cột → renderer` của extension và gắn lại sau `updateUI()`,
     đồng thời giữ `rowHeight`, `autoResizeMode`, grid. Thay mọi `new JTable(...)` trong `ui/` bằng
     `ThemedTable` (ApiTab, AuditTab, ExcelTab, DupPopup, RolesTab, CheckList, ResultTable...).
   - Vẫn gọi `host.applyTheme(root)` (để font / màu nền theo Burp), nhưng gọi **trước** khi gắn
     renderer. Nhờ hai lớp trên, renderer của extension luôn được gắn lại dù Burp theme lại lúc nào.
   - Test (chạy được không cần Burp): tạo `ThemedTree` / `ThemedTable` → gọi
     `setCellRenderer(new DefaultTreeCellRenderer())` và `SwingUtilities.updateComponentTreeUI(...)`
     → assert renderer vẫn là của extension.
   - Thêm vào `UiPreview` một chế độ mô phỏng Burp: `applyTheme` gán `DefaultTreeCellRenderer` cho mọi
     `JTree` và `DefaultTableCellRenderer` cho mọi cột bảng (đúng như hiện tượng trong ảnh) → chụp
     ảnh phải vẫn còn màu.
5. Nhờ người dùng nạp jar mới trong Burp, chụp lại tab API (có dữ liệu), Lịch sử, Audit ở cả theme sáng
   và tối để xác nhận.

**Xong Phase 0 khi:** jar mới hiển thị thông tin chẩn đoán; cây / bảng trong Burp có màu như ảnh chụp
ngoài Burp; test xanh.

---

## 3. Phase 1 - Hệ thống thiết kế (Design System)

Tạo package `themythofcrawl.ui.theme` (hoặc `ui/kit/`). Đây là nền tảng; các phase sau chỉ dùng nó.

### 3.1 `Theme` - bảng màu ngữ nghĩa (semantic tokens), thay `Palette`

- Lấy màu nền / chữ / accent **từ theme hiện tại**:
  `Panel.background`, `Label.foreground`, `Table.background`, `Table.selectionBackground`,
  `Component.accentColor` (FlatLaf; fallback `Table.selectionBackground`), `Separator.foreground`,
  `Label.disabledForeground`.
- `dark` = độ sáng nền < 50 % (công thức luminance đang có trong `Palette`).
- **Token ngữ nghĩa** (mỗi token có 4 biến thể `fill` (nền nhạt), `fillStrong`, `border`, `text`), tính
  bằng cách trộn màu gốc với nền theo tỷ lệ khác nhau cho sáng / tối:

| Token | Dùng cho | Màu gốc gợi ý (sáng / tối tự trộn) |
|---|---|---|
| `success` | OK, 2xx, Enforced | #16A34A |
| `warning` | INFO (role bị chặn / lệch) | #D97706 (vàng hổ phách, đọc được trên nền trắng) |
| `danger` | ERROR, 5xx, mất kết nối | #DC2626 |
| `critical` | WARN - nghi bypass (Guest 2xx) | #EA580C (cam đậm) + icon cảnh báo |
| `info` | Redirect 3xx, "đã bắt chưa gửi" | #2563EB |
| `pending` | Chờ xác nhận vị trí | #64748B (xám xanh) |
| `dup` | API trùng | #9333EA |
| `ghost` | API đã xóa (giữ chỗ) | chữ mờ + nghiêng |
| `accent` | nút chính, đích hiện tại | `Component.accentColor` của Burp |
| `orig` | API gốc | #2563EB |

  - Màu method HTTP: GET xanh lá, POST xanh dương, PUT hổ phách, PATCH tím, DELETE đỏ, khác xám.
  - Màu cấp thư mục (1..7, lặp): giữ bộ `LEVEL_HUES` hiện tại.
- **Độ tương phản:** chữ trên nền token PHẢI đạt tỷ lệ ≥ 4.5 : 1 (WCAG AA); chữ phụ (muted) ≥ 3 : 1.
  Viết hàm `Theme.contrast(a, b)` và **unit test** kiểm tra mọi cặp (text trên fill) ở cả nền sáng
  (#FFFFFF, #F2F2F2) và tối (#2B2B2B, #1E1F22).
- **Không chỉ dựa vào màu** (người mù màu): mỗi trạng thái có thêm **icon/ký hiệu** (✓, !, ×, ↗, ⏳ vẽ bằng Java2D).
- **Theme động:** `Theme.current()` cache theo LAF; đăng ký `UIManager.addPropertyChangeListener` cho
  `"lookAndFeel"` → xóa cache → `SwingUtilities.updateComponentTreeUI(root)` → `repaint()`.
  Component tùy biến override `updateUI()` để lấy lại màu từ `Theme.current()`.
- Gộp các `new Color(...)` rải rác (ApiTab 3, MainPanel 2, SettingsTab 6, TreeRenderer 1, Ui 1) vào `Theme`.

### 3.2 Kích thước & chữ (spacing / typography)

- Khoảng cách theo lưới 4 px: 4 / 8 / 12 / 16 / 24.
- Font: dùng font của theme (`UIManager.getFont("Label.font")`), **không** đặt cứng tên font.
  - Tiêu đề mục: +2 pt, đậm. Chữ phụ: -1 pt, màu muted. Path / request: font monospace của theme
    (`UIManager.getFont("monospaced.font")` nếu có, fallback `Font.MONOSPACED`).
- Bo góc: nút / chip 999 (pill) hoặc 6 px; thẻ (card) 10 px. Đường viền 1 px màu `border`.
- Chiều cao hàng: bảng 28 px, cây 26 px (đang có). Hỗ trợ HiDPI: mọi kích thước vẽ tay nhân với
  `UIScale` (đọc `UIManager.getFont("Label.font").getSize() / 13f` làm hệ số).

### 3.3 Bộ component dùng chung (`ui/kit/`)

| Component | Mô tả |
|---|---|
| `Pill` / `Badge` | Nhãn bo tròn (nền `fill`, viền `border`, chữ `text`), có icon trái tùy chọn. Dùng cho trạng thái, method, số đếm, Excel `#STT`, `⧉2/3`. Vẽ bằng `paintComponent`, cache kích thước. |
| `StatusDot` | Chấm tròn 8 px theo token. |
| `Card` | JPanel nền `surfaceAlt`, bo 10 px, viền 1 px, padding 12; có `header` (tiêu đề + phụ đề + nút bên phải). |
| `SectionHeader` | Tiêu đề + mô tả muted + đường kẻ mảnh. |
| `ToolbarGroup` | Nhóm nút cùng chức năng, ngăn bằng separator dọc; nút dùng `JButton.buttonType=toolBarButton`, nút chính dùng accent (`FlatLaf.style: background: @accentColor; foreground: #fff`) hoặc fill token. |
| `SegmentedToggle` | 2-3 lựa chọn dạng tab nhỏ (vd. CHỈ CRAWL / GỬI). |
| `SearchField` | `JTextField` có placeholder + nút xóa (`JTextField.showClearButton`) + icon kính lúp. |
| `StatCard` (KPI) | Số lớn + nhãn nhỏ + thanh tiến độ mảnh (vd. "Đã bắt 4/10 · 40 %"). |
| `EmptyState` | Icon lớn mờ + 1 câu hướng dẫn + nút hành động (khi bảng / cây trống). |
| `Breadcrumb` | Đường dẫn nhánh `Toppage › contact › button "send"`, mỗi cấp 1 chip màu theo cấp, bấm vào cấp nào thì chọn cấp đó. |
| `Icons` (mở rộng) | Vẽ Java2D, cỡ 14/16 px, nhận màu: send, repeater, copy, folder, api, role, check, warn, error, info, hourglass, ghost, dup, hide, show, trash, move, undo, filter, export, excel, window, pin, gear, refresh, plus, minus, search. Thay toàn bộ emoji trong nhãn nút / menu / chip. |

**Xong Phase 1 khi:** có `Theme` + kit + test độ tương phản; `Palette` chỉ còn là lớp chuyển tiếp gọi `Theme`
(hoặc đã thay hẳn); giao diện chưa đổi bố cục nhưng mọi màu lấy từ token; test xanh.

---

## 4. Phase 2 - Thanh trên cùng (header) & dòng trạng thái

Hiện tại: 1 hàng nút + 1 dòng chữ trạng thái dài.

Thiết kế mới (2 hàng, cao ≤ 64 px):

- **Hàng 1** chia 3 vùng:
  - Trái: `SegmentedToggle` **Capture** (bật/tắt, chấm đỏ nhấp nháy nhẹ khi đang bắt), **CHỈ CRAWL | GỬI**
    (GỬI = nền success), pill **Hàng đợi n** (+ thanh tiến độ mảnh khi đang gửi; bấm = hủy).
  - Giữa: **➜ Đích** dạng `Breadcrumb` có màu theo cấp + mũi tên ▾ mở menu đích (giữ menu hiện có).
  - Phải: nút **Cần xử lý** dạng pill có số (pending: nền `pending`; có API trùng: thêm chấm `dup`),
    nút tùy chọn ⚙, nút tách cửa sổ, nút Workspace.
- **Hàng 2** - thay dòng chữ dài bằng các **chip thống kê** nhỏ: `Roles 10/10` · `Nhánh 5` · `API 10`
  · `⚠ 1 lệch` (warning) · `🙈 2 ẩn` · `Excel 4/10` (thanh tiến độ). Thông báo nhanh (flash) hiện dạng
  toast trượt ở góc phải 4 giây thay vì ghi đè dòng trạng thái.
- Icon ⓘ giữ nguyên nhưng đổi thành icon vẽ, popover dùng `Card`.

---

## 5. Phase 3 - Bảng & cây (thứ người dùng nhìn nhiều nhất)

### 5.1 Bảng API (`ApiTab` + `GroupTableModel`), bảng role (`ResultTableModel`), bảng Nhánh, bảng Audit / Excel

- `AuditRenderer` viết lại thành các renderer chuyên biệt (cache sẵn, không HTML):
  - **Cột trạng thái / đánh giá:** `Pill` theo token + icon (✓ OK, ! lệch, ⚠ bypass, × lỗi, ↗ redirect, ⏳ chờ).
  - **Method:** pill nhỏ màu theo method.
  - **Status HTTP:** chữ đậm màu theo nhóm 2xx/3xx/4xx/5xx; 401/403 dùng `warning`.
  - **Path:** monospace; phần tham số `{id}` / số làm mờ; tooltip = URL đầy đủ.
  - **Số (Body Len, Delta, Time, Sends):** canh phải, Delta âm/dương có màu (+ xanh / − đỏ) và ký hiệu.
  - **Cột đầu:** vạch màu 3 px theo nhánh (đang có) + chấm trạng thái.
  - **Copy / Excel / Trùng:** icon/pill bấm được, con trỏ tay khi rê (đang có - giữ).
- Nền hàng: zebra rất nhẹ; hàng có trạng thái chỉ tô **nhạt** (`fill`), không tô đậm cả hàng (đỡ rối);
  rê chuột có hover nhẹ; chọn hàng dùng màu chọn của theme.
- Header bảng: đậm, canh trái, có icon sort; cột quá hẹp hiện "…" + tooltip đầy đủ.
- **Bảng kết quả role** (dưới bảng API): thêm 1 dòng tóm tắt phía trên dạng chuỗi pill
  `OK 6 · Lệch 3 · Bypass 1 · Lỗi 0`; hàng API gốc in đậm màu `orig` và luôn nằm đầu.
- Bộ lọc màu (combo "Màu") đổi thành **chip lọc** bật/tắt nhiều trạng thái cùng lúc.

### 5.2 Cây nhánh (`BranchTree` + `TreeRenderer`) - dùng ở tab API, Lịch sử, Cây

- Mỗi dòng: `[icon loại] Tên chính (đậm)` + **chip bên phải** thay vì nối chuỗi `·` dài:
  - Thư mục: chip cấp (màu theo cấp), `10 API`, pill verdict, `⏳ 5`, `👻 1` (icon vẽ).
  - API: pill method, path monospace, `[Excel #200]`, `⧉1/2`, pill verdict, số role.
  - Role: chấm trạng thái + tên role + status + len + pill đánh giá.
  - Ghost: chữ nghiêng mờ.
- Đường nối (tree lines) mảnh màu `border`; thụt lề theo cấp rõ ràng; chữ không bị cắt (giữ sửa HiDPI).
- Giữ nguyên: tải lười (lazy), giữ trạng thái mở/chọn khi dựng lại, chip bấm được.

---

## 6. Phase 4 - Tab Lịch sử (ưu tiên cao: hiện chỉ là chữ)

Hiện tại: trái = cây; phải = `JTextArea` monospace từ `Reports.history(...)`.

Thiết kế mới - bên phải là **báo cáo trực quan** (`HistoryReportView`, JPanel trong JScrollPane):

1. **Thanh KPI** (StatCard): Tổng nhánh · Tổng API · OK · Lệch · Bypass · Lỗi · Chưa gửi · % độ phủ Excel.
2. **Danh sách thẻ (Card) theo nhánh**, theo thứ tự thời gian:
   - Đầu thẻ: `Breadcrumb` nhánh, giờ tạo, pill verdict, số API, khoảng `API 1 … API 5`.
   - Thân thẻ: bảng nhỏ không viền: `#` · method pill · path (mono) · status gốc · **ma trận role**:
     mỗi role 1 ô vuông 14 px tô theo trạng thái (heatmap), tooltip `01_Admin: 403 (10 B)`.
   - Bấm 1 dòng → chọn API đó ở tab API. Thẻ thu gọn / mở được; nhánh rỗng hiện EmptyState nhỏ.
3. **Chế độ xem** (SegmentedToggle trên cùng): `Thẻ` (mặc định) | `Ma trận` (bảng lớn: hàng = API,
   cột = role, ô = màu trạng thái + mã status; header cột xoay hoặc viết tắt) | `Văn bản` (giữ text cũ,
   cho ai cần copy).
4. Nút **Copy văn bản** / **Xuất Markdown** vẫn dùng `Reports.history(...)` (không đổi định dạng file xuất).
5. Hiệu năng: ảo hóa - chỉ dựng thẻ cho nhánh đang nhìn thấy (dựng dần khi cuộn) hoặc dùng 1 `JTable` với
   renderer kiểu thẻ; với 5000 API phải dựng < 300 ms.
6. Cây bên trái: chọn nhánh trong cây → cuộn tới thẻ tương ứng.

---

## 7. Phase 5 - Các tab còn lại

| Tab | Cải thiện |
|---|---|
| **Roles** | Danh sách role dạng thẻ nhỏ: avatar tròn 2 chữ cái (màu theo index), tên, switch bật/tắt, rule hiển thị bằng chip màu theo mode (REPLACE xanh, ADD lục, REMOVE đỏ, COOKIE_SET tím, COOKIE_REMOVE cam). Thẻ "API gốc" ghim trên cùng màu `orig`. Ô JWT inspector: header/payload tô màu JSON (key/giá trị/số), cảnh báo `exp` đã hết hạn bằng pill `danger`. |
| **Cấu hình** | Giữ 7 mục thẻ màu, chuyển sang `Card` + `SectionHeader`; lưới 2 cột trên màn rộng; ô nhập có placeholder, ô lỗi (regex sai) dùng `JComponent.outline=error`; thanh tìm kiếm cài đặt ở trên cùng (lọc theo chữ). Sửa chữ cũ "chuột phải > theMythOfCrawl" → dùng `Text.EXT_NAME`. Thêm mục 8 chẩn đoán (Phase 0). |
| **Log** | Mỗi dòng: giờ (mono, muted) + icon loại (bắt / gửi / lỗi / di chuyển / hệ thống) + nội dung; dòng lỗi màu `danger`. Ô tìm kiếm + chip lọc loại. Giữ tải lười. |
| **Excel** | Đầu tab: StatCard `Đã bắt 4/10 (40 %)` có thanh tiến độ, `Đã gặp`, `Ngoài danh sách`, `Trùng trong file`. Bộ lọc dạng chip. Cột Trạng thái = pill. Bảng "các lần gặp" dạng timeline (icon kết quả + giờ + vị trí). EmptyState khi chưa nạp file (nút Nạp file to, mô tả định dạng cột). |
| **Cây** | Như 5.2; khung Request/Response giữ editor của Burp. Cửa sổ riêng: tiêu đề có Breadcrumb của mục đang chọn. |
| **Audit** | 4 tab con thành `JTabbedPane` kiểu `card` (`JTabbedPane.tabType=card`) có badge số trên tab. "Cần xử lý": mỗi hàng 1 pill loại việc; khung giải thích bên dưới thành Card có các nút hành động ngay trong thẻ. "Nhật ký": icon theo hành động, màu theo nhóm (xanh xác nhận, xanh dương di chuyển, đỏ xóa). |
| **Hộp thoại** | Push/Gửi, Bật/Tắt Role, Quản lý cấp, Chọn đường dẫn: header có tiêu đề + mô tả muted; nút chính accent bên phải dưới; danh sách check dạng hàng cao 28 px có hover. |

---

## 8. Phase 6 - Tận dụng ưu điểm của Java / Swing

- **Phím tắt** (hiện ở tooltip): Ctrl+F tìm trong tab hiện tại, Ctrl+Shift+C copy STT (đang có),
  Ctrl+Enter gửi mục chọn, Ctrl+R đẩy Repeater, Alt+1..8 chuyển tab, Delete xóa (có xác nhận).
- **Tìm kiếm toàn cục** (Ctrl+K): ô nổi tìm API theo path / #, nhánh, role → Enter nhảy tới.
- **Kéo thả**: kéo API trong cây sang nhánh khác = di chuyển (dùng `Engine.moveToPath`, có hoàn tác).
- **Bảng nhớ cấu hình cột**: độ rộng / thứ tự / ẩn cột lưu vào Prefs (key mới `tmoc.ui.columns.<table>`).
- **Animation nhẹ** (Swing Timer, ≤ 150 ms): toast, mở/đóng thẻ. Tắt được trong Cấu hình.
- **Đổi cỡ chữ giao diện** (90 % / 100 % / 115 %) trong Cấu hình.

---

## 9. Phase 7 - Kiểm thử giao diện (QA)

1. Mở rộng `src/test/java/themythofcrawl/ui/UiPreview.java` để chụp mọi tab + hộp thoại với:
   `-Dlaf=com.formdev.flatlaf.FlatLightLaf`, `FlatDarkLaf`, `FlatIntelliJLaf`, `FlatDarculaLaf`, và scale
   `-Dsun.java2d.uiScale=1.5`. FlatLaf chỉ dùng ở **test** (`testImplementation 'com.formdev:flatlaf:3.5.4'`),
   không đưa vào jar. Chạy: `xvfb-run -a java -Dlaf=... -cp build/classes/java/main:build/classes/java/test:<flatlaf.jar> themythofcrawl.ui.UiPreview <thư mục ảnh>`.
2. Unit test: độ tương phản token (3.1); renderer không ném lỗi với giá trị null; `UiSmokeTest` vẫn xanh.
3. Bench: chạy `Bench 5000`, so với số liệu hiện tại (cây 55 ms, bảng 105 ms, tick ~140 ms khi ép).
4. Danh sách soát tay (ghi kết quả vào PR): chữ không bị cắt; không còn ô vuông emoji; theme sáng/tối
   đều đọc được; đổi theme Burp khi đang chạy → màu đổi theo; cửa sổ hẹp 1100 px không mất nút.

## 10. Phase 8 - Tài liệu

- README: thêm ảnh chụp mới (sáng + tối) và mục "Gặp lỗi giao diện" (Phase 0).
- Cập nhật `ui/Help.java` (nội dung icon ⓘ) theo bố cục mới.

---

## 11. Thứ tự commit đề xuất

1. `ui: diagnostics (version, LAF) + load log line` (Phase 0)
2. `ui: Theme tokens + contrast tests; Palette delegates to Theme` (Phase 1a)
3. `ui: kit components + Java2D icon set; replace emoji` (Phase 1b)
4. `ui: header & status chips` (Phase 2)
5. `ui: table renderers & tree rows with chips` (Phase 3)
6. `ui: History visual report (cards / matrix / text)` (Phase 4)
7. `ui: Roles, Settings, Log, Excel, Audit, dialogs polish` (Phase 5)
8. `ui: shortcuts, global search, drag & drop, column memory` (Phase 6)
9. `test: screenshot matrix + bench` (Phase 7) - `docs: README` (Phase 8)

## 12. Định nghĩa hoàn thành (Definition of Done)

- [ ] `./gradlew build` xanh (≥ 49 test cũ + test mới).
- [ ] Không còn emoji trong nhãn UI; mọi icon vẽ bằng Java2D.
- [ ] Mọi màu lấy từ `Theme`; đổi theme Burp khi đang chạy → giao diện đổi theo.
- [ ] Tab Lịch sử có chế độ Thẻ và Ma trận, có màu theo trạng thái.
- [ ] Ảnh chụp đủ 4 LAF × mọi tab, đọc rõ ở cả sáng và tối.
- [ ] Bench 5000 API không chậm hơn quá 10 %.
- [ ] Không sửa logic lõi / `rolereq/`.

## 13. Tài liệu tham khảo

- FlatLaf - client properties: https://www.formdev.com/flatlaf/client-properties
- FlatLaf - tùy biến / `FlatLaf.style`: https://www.formdev.com/flatlaf/how-to-customize/
- FlatLaf - JTable / JTree: https://www.formdev.com/flatlaf/components/table , https://www.formdev.com/flatlaf/components/tree
- FlatLaf - Button types: https://www.formdev.com/flatlaf/components/button
- Burp dùng FlatLaf (từ 2020.12) & theme: https://github.com/CoreyD97/BurpCustomizer
- Tham khảo màu trạng thái trong extension phân quyền (Bypassed đỏ / Enforced xanh / không rõ vàng):
  https://github.com/PortSwigger/autorize
- Tham khảo tô màu hàng theo bộ lọc: https://github.com/nccgroup/LoggerPlusPlus
- WCAG độ tương phản 4.5 : 1: https://www.w3.org/TR/WCAG21/#contrast-minimum
