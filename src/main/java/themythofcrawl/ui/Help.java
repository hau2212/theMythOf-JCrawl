package themythofcrawl.ui;

/** Texts of the info icons: first line = hover summary, the whole text = click popover. */
public final class Help {
    private Help() {
    }

    public static final String CAPTURE = "Bắt request từ Proxy.\n\n• Ô Capture: bật / tắt bắt.\n• CHỈ CRAWL / GỬI: chỉ ghi lại API, hoặc "
            + "bắt xong gửi luôn theo hàng đợi chống 429. Bấm để đổi.\n• Hàng đợi: số request đang chờ gửi, bấm để hủy.";
    public static final String DEST = "Đích: mọi request MỚI sẽ vào đường dẫn này.\n\nChỉ đổi khi bạn chọn lại ở nút này (chọn từ danh "
            + "sách, lấy nhánh đang chọn trong cây, tạo nhánh mới, đổi tên). Bấm chọn chỗ khác trong cây hay bảng KHÔNG làm đổi đích."
            + "\n\nAPI đã từng xóa sẽ quay về chỗ cũ thay vì vào đích (xem Audit).";
    public static final String TODO = "Việc cần bạn quyết định:\n\n• API mới chờ xác nhận vị trí (màu xám).\n• API đã xóa quay lại "
            + "chỗ cũ (kể cả khi path cũ đã bị xóa).\n• API trùng gặp ở chỗ mới (màu tím): giữ thêm ở chỗ mới hay ẩn đi.\n\n"
            + "Chi tiết, giải thích và nút xử lý ở tab Audit > Cần xử lý.";
    public static final String COLORS = "Màu của API:\n\n• Xám: chờ xác nhận vị trí\n• Vàng: có role lỗi / lệch quyền\n• Cam: nghi "
            + "bypass (Guest được 2xx)\n• Xanh lá: mọi role 2xx\n• Đỏ: lỗi 5xx / mạng\n• Xanh dương: 3xx hoặc chưa gửi\n• Viền / nhãn "
            + "tím [#2/3]: API trùng (bản số 2 trong 3 bản) - bấm nhãn để xem nhanh các bản.";
    public static final String DECIDE = "Mỗi dòng là 1 API đang chờ bạn quyết định. Chọn 1 hoặc nhiều dòng rồi bấm nút:\n\n"
            + "• Giữ chỗ hiện tại: xác nhận (với path cũ đã mất = khôi phục path cũ; với API trùng = giữ thêm bản ở chỗ mới).\n"
            + "• Về nơi vừa gặp: chuyển API vào đích lúc nó được bắt.\n• Path khác: chọn / tạo path rồi chuyển vào đó (gộp "
            + "vào path có sẵn).\n• Không giữ: ẩn khỏi màn hình crawl, vẫn xem lại được ở tab API trùng & ẩn.\n• Xóa.";
    public static final String PLACES = "Giữ chỗ: mỗi endpoint (method + host + path) nhớ vị trí cuối cùng của nó - vị trí đã xác "
            + "nhận, hoặc nơi nó nằm lúc bị xóa. Nhớ cả khi xóa API, Xóa toàn bộ, xóa path, reset chống trùng, đóng / mở lại Burp. "
            + "Lần sau bắt lại, API quay về đúng chỗ này.\n\n• Sửa: đổi chỗ sẽ quay về.\n• Thêm: giữ chỗ trước cho 1 endpoint chưa "
            + "bắt.\n• Quên: lần sau vào đích hiện tại.\n• Cho phép bắt lại: xóa bộ nhớ chống trùng của endpoint.";
    public static final String DUPS = "API trùng: cùng một endpoint gặp ở nhiều chỗ khác nhau. Bản đầu là #1, các lần gặp ở chỗ mới "
            + "là #2, #3 ... (số không bao giờ dùng lại).\n\nGặp lại ở chỗ đã có bản thì chỉ đếm (bỏ qua do trùng). Bản bị ẩn không "
            + "hiện ở màn hình crawl nhưng vẫn ở đây; bấm Hiện để đưa lại.";
    public static final String LOG = "Nhật ký mọi thay đổi vị trí: bắt mới, quay về chỗ cũ, gặp trùng, xác nhận, chuyển, hoàn tác, "
            + "ẩn / hiện, xóa, đổi tên nhánh, sửa giữ chỗ. Chọn 1 dòng rồi bấm Hoàn tác để quay lại trạng thái trước dòng đó.";
    public static final String COPY = "Bấm icon để copy 'STT. path' của API (STT lấy từ file Excel). Ctrl+Shift+C copy các API "
            + "đang chọn.";
    public static final String EXCEL = "Dòng khớp trong file Excel: #STT · số lần đã gặp. Bấm để mở đúng dòng đó ở tab Excel.";
    public static final String SELECT = "Chọn nhiều: Ctrl + click (từng mục) hoặc Shift + click (một khoảng) trong cây hoặc bảng. "
            + "Có thể chọn cả nhánh A và vài API của nhánh B cùng lúc; mọi thao tác (gửi, đẩy, copy, chuyển, xác nhận, bật/tắt role) "
            + "áp dụng cho tất cả.";
    public static final String WORKSPACE = "Workspace: toàn bộ API, kết quả role, cây nhánh, giữ chỗ, file Excel và bộ đếm được lưu "
            + "vào thư mục theMythOfCrawl_workspace (mặc định trong thư mục dự án của file jar). Đóng Burp hay đổi project rồi quay "
            + "lại vẫn còn nguyên. Mỗi project Burp nhớ workspace dùng lần cuối.";
}
