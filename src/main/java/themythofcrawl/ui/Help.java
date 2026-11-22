package themythofcrawl.ui;

/** Texts of the info icons: first line = hover summary, the whole text = click popover. */
public final class Help {
    private Help() {
    }

    public static final String CAPTURE = "Bắt request từ Proxy.\n\n"
            + "• Dừng / Capture: bật / tắt bắt (chấm đỏ nhấp nháy nhẹ khi đang bật).\n"
            + "• chỉ CRAWL / gửi: chỉ ghi lại API, hoặc bắt xong tự gửi ngay các vai trò qua hàng đợi chống 429.\n"
            + "• Hàng đợi: số request đang chờ gửi kèm thanh tiến độ mảnh; bấm để hủy hàng đợi.";
    public static final String DEST = "Đích (Breadcrumb): mọi request MỚI sẽ vào đường dẫn này.\n\n"
            + "Mỗi cấp hiển thị thành 1 chip màu; bấm vào một cấp để chuyển nhanh tới cấp đó, hoặc bấm mũi tên để "
            + "mở menu (chọn từ danh sách, lấy nhánh đang chọn trong cây, tạo nhánh mới, đổi tên). Chọn xem nhánh khác "
            + "trong cây / bảng không làm đổi Đích.\n\n"
            + "API đã từng xóa sẽ quay về chỗ cũ thay vì vào Đích (xem tab Audit).";
    public static final String TODO = "Việc cần bạn quyết định:\n\n"
            + "• API mới chờ xác nhận vị trí (màu xám).\n"
            + "• API đã xóa quay lại chỗ cũ (kể cả khi path cũ đã bị xóa).\n"
            + "• API trùng gặp ở chỗ mới (chấm / nhãn tím): giữ thêm ở chỗ mới hay ẩn đi.\n\n"
            + "Chi tiết, thẻ giải thích và các nút xử lý nằm ở tab Cần xử lý.";
    public static final String COLORS = "Màu ngữ nghĩa & chip lọc của API:\n\n"
            + "• Xanh lá: OK (mọi vai trò đều hợp lệ / 2xx)\n"
            + "• Vàng / hổ phách: INFO (có vai trò bị chặn 401/403 hoặc lệch trạng thái)\n"
            + "• Cam: WARN - nghi bypass (Guest / vai trò thấp vẫn được 2xx)\n"
            + "• Đỏ: lỗi 5xx / mất kết nối\n"
            + "• Xanh dương: chuyển hướng 3xx hoặc đã bắt chưa gửi\n"
            + "• Xám: chờ xác nhận vị trí\n"
            + "• Nhãn tím [#2/3]: API trùng (bản số 2 trong 3 bản) - bấm nhãn để xem nhanh các bản.\n\n"
            + "Ở tab API có dải chip lọc bật/tắt nhiều trạng thái cùng lúc.";
    public static final String DECIDE = "Mỗi dòng là 1 API đang chờ bạn quyết định. Chọn 1 hoặc nhiều dòng rồi bấm nút trên thanh công cụ "
            + "hoặc trong thẻ Chi tiết quyết định bên dưới:\n\n"
            + "• Giữ chỗ hiện tại: xác nhận (với path cũ đã mất = khôi phục path cũ; với API trùng = giữ thêm bản ở chỗ mới).\n"
            + "• Về nơi vừa gặp: chuyển API vào Đích lúc nó được bắt.\n"
            + "• Path khác: chọn / tạo path rồi chuyển vào đó (gộp vào path có sẵn).\n"
            + "• Không giữ: ẩn khỏi màn hình crawl, vẫn xem lại được ở tab API trùng & ẩn.\n"
            + "• Xóa (vẫn giữ chỗ).";
    public static final String PLACES = "Giữ chỗ: mỗi endpoint (method + host + path) nhớ vị trí cuối cùng của nó - vị trí đã xác "
            + "nhận, hoặc nơi nó nằm lúc bị xóa. Nhớ cả khi xóa API, Xóa toàn bộ, xóa path, reset chống trùng, đóng / mở lại Burp. "
            + "Lần sau bắt lại, API quay về đúng chỗ này.\n\n"
            + "• Sửa: đổi chỗ sẽ quay về.\n"
            + "• Thêm: giữ chỗ trước cho 1 endpoint chưa bắt.\n"
            + "• Quên: lần sau vào Đích hiện tại.\n"
            + "• Cho phép bắt lại: xóa bộ nhớ chống trùng của endpoint.";
    public static final String DUPS = "API trùng: cùng một endpoint gặp ở nhiều chỗ khác nhau. Bản đầu là #1, các lần gặp ở chỗ mới "
            + "là #2, #3 ... (số không bao giờ dùng lại).\n\nGặp lại ở chỗ đã có bản thì chỉ đếm (bỏ qua do trùng). Bản bị ẩn không "
            + "hiện ở màn hình crawl nhưng vẫn ở đây; bấm Hiện để đưa lại.";
    public static final String LOG = "Nhật ký mọi thay đổi vị trí: bắt mới, quay về chỗ cũ, gặp trùng, xác nhận, chuyển, hoàn tác, "
            + "ẩn / hiện, xóa, đổi tên nhánh, sửa giữ chỗ. Dùng ô tìm kiếm hoặc chip lọc loại để tra cứu; chọn 1 dòng rồi bấm "
            + "Hoàn tác để quay lại trạng thái trước dòng đó.";
    public static final String COPY = "Copy & cấu hình cột bảng:\n\n"
            + "• Bấm icon Copy ở đầu dòng hoặc Ctrl+Shift+C để copy 'STT. path' (STT lấy từ Excel hoặc bộ đếm).\n"
            + "• Chuột phải lên tiêu đề cột bảng bất kỳ để ẩn / hiện cột hoặc Đặt lại mặc định (tự nhớ vào workspace).";
    public static final String EXCEL = "Dòng khớp trong file Excel: [#STT · số lần đã gặp]. Bấm nhãn để nhảy thẳng tới đúng dòng và "
            + "timeline lịch sử các lần gặp ở tab Excel.";
    public static final String SELECT = "Chọn nhiều, kéo thả & phím tắt:\n\n"
            + "• Chọn nhiều: Ctrl + click (từng mục) hoặc Shift + click (một khoảng) trong cây hoặc bảng.\n"
            + "• Kéo thả: kéo API trong cây sang nhánh khác để chuyển nhanh (hiện nút Hoàn tác trên thông báo nổi).\n"
            + "• Phím tắt: Ctrl+K tìm toàn cục · Ctrl+F lọc tab hiện tại · Ctrl+Shift+C copy STT · "
            + "Ctrl+Enter gửi · Ctrl+R đẩy Repeater · Delete xóa · Alt+1..8 chuyển tab.";
    public static final String WORKSPACE = "Workspace: toàn bộ API, kết quả vai trò, cây nhánh, giữ chỗ, file Excel, bộ đếm và cấu hình cột "
            + "bảng được lưu tự động vào thư mục theMythOfCrawl_workspace (mặc định cạnh file jar). Đóng Burp hay đổi project rồi "
            + "quay lại vẫn còn nguyên. Xem LAF / màu theme / cỡ chữ / thông tin chẩn đoán ở tab Cấu hình (mục 8).";
}
