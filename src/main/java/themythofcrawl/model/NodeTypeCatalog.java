package themythofcrawl.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/**
 * Danh mục loại node cho cây cấu trúc crawl (Thư mục, Link, Button, Icon, Tab, Popup/Modal, Form...)
 * kèm 42 biểu tượng vector chia 5 nhóm, 12 màu chuẩn + mã Hex tùy ý, Monogram 3 kiểu và ảnh nhúng an toàn.
 */
public final class NodeTypeCatalog {
    public static final String DEFAULT_ID = "folder";

    public record VectorMeta(String id, String label, String category, String keywords) {
        public boolean matches(String q) {
            if (q == null || q.isBlank()) {
                return true;
            }
            String needle = q.trim().toLowerCase(Locale.ROOT);
            return id.contains(needle)
                    || label.toLowerCase(Locale.ROOT).contains(needle)
                    || category.toLowerCase(Locale.ROOT).contains(needle)
                    || keywords.toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    public static final List<String> CATEGORIES = List.of(
            "Tất cả", "Cấu trúc", "Tương tác UI", "Nghiệp vụ", "Hệ thống & API", "Bảo mật"
    );

    public static final List<VectorMeta> VECTOR_META = List.of(
            // Cấu trúc & Điều hướng (10)
            new VectorMeta("folder", "Thư mục", "Cấu trúc", "folder thu muc directory man hinh page"),
            new VectorMeta("folder-open", "Thư mục mở", "Cấu trúc", "folder open mo nhom group"),
            new VectorMeta("page", "Trang / Màn hình", "Cấu trúc", "page screen trang view document"),
            new VectorMeta("tab", "Tab / Sub-tab", "Cấu trúc", "tab subtab the chuyen"),
            new VectorMeta("sidebar", "Thanh bên / Drawer", "Cấu trúc", "sidebar drawer panel menu ben"),
            new VectorMeta("menu", "Danh sách Menu", "Cấu trúc", "menu list nav dieu huong"),
            new VectorMeta("link", "Đường Link / URL", "Cấu trúc", "link url href dieu huong anchor"),
            new VectorMeta("home", "Trang chủ", "Cấu trúc", "home toppage dashboard chinh"),
            new VectorMeta("layers", "Lớp / Cụm chức năng", "Cấu trúc", "layers stack nhom group"),
            new VectorMeta("sitemap", "Sơ đồ nhánh", "Cấu trúc", "sitemap tree cay cau truc"),
            // Tương tác UI (10)
            new VectorMeta("button", "Button / Nút bấm", "Tương tác UI", "button btn nut bam click action"),
            new VectorMeta("icon", "Icon / Biểu tượng", "Tương tác UI", "icon glyph bieu tuong badge"),
            new VectorMeta("modal", "Popup / Modal", "Tương tác UI", "modal popup dialog hop thoai"),
            new VectorMeta("form", "Form / Biểu mẫu", "Tương tác UI", "form bieu mau submit nhap lieu"),
            new VectorMeta("input", "Ô nhập liệu", "Tương tác UI", "input field text textbox"),
            new VectorMeta("search", "Tìm kiếm / Lọc", "Tương tác UI", "search tim kiem filter lens"),
            new VectorMeta("dropdown", "Danh sách xổ xuống", "Tương tác UI", "dropdown select combobox"),
            new VectorMeta("toggle", "Nút bật/tắt", "Tương tác UI", "toggle switch bat tat"),
            new VectorMeta("upload", "Tải lên (Upload)", "Tương tác UI", "upload file import tai len"),
            new VectorMeta("download", "Tải xuống (Export)", "Tương tác UI", "download export file tai xuong"),
            // Nghiệp vụ & Web App (10)
            new VectorMeta("user", "Người dùng / Hồ sơ", "Nghiệp vụ", "user profile account tai khoan"),
            new VectorMeta("users", "Nhóm / Vai trò", "Nghiệp vụ", "users roles team nhom quyen"),
            new VectorMeta("cart", "Giỏ hàng / Đơn hàng", "Nghiệp vụ", "cart order gio hang don mua"),
            new VectorMeta("credit-card", "Thanh toán / Ví", "Nghiệp vụ", "payment card billing thanh toan"),
            new VectorMeta("receipt", "Hóa đơn / Giao dịch", "Nghiệp vụ", "receipt invoice hoa don"),
            new VectorMeta("bell", "Thông báo", "Nghiệp vụ", "bell notification alert thong bao"),
            new VectorMeta("mail", "Email / Hộp thư", "Nghiệp vụ", "mail email inbox gui thu"),
            new VectorMeta("chat", "Tin nhắn / Hỗ trợ", "Nghiệp vụ", "chat message comment binh luan"),
            new VectorMeta("calendar", "Lịch / Đặt hẹn", "Nghiệp vụ", "calendar date schedule lich"),
            new VectorMeta("chart", "Báo cáo / Thống kê", "Nghiệp vụ", "chart analytics stats bao cao"),
            // Hệ thống & API (6)
            new VectorMeta("api", "API Endpoint", "Hệ thống & API", "api endpoint rest graphql"),
            new VectorMeta("database", "Cơ sở dữ liệu", "Hệ thống & API", "database db storage du lieu"),
            new VectorMeta("server", "Máy chủ / Dịch vụ", "Hệ thống & API", "server backend service host"),
            new VectorMeta("terminal", "Lệnh / Script", "Hệ thống & API", "terminal console cli command"),
            new VectorMeta("code", "Mã nguồn / JSON", "Hệ thống & API", "code json payload dev"),
            new VectorMeta("webhook", "Webhook / Callback", "Hệ thống & API", "webhook event callback trigger"),
            // Bảo mật & Trạng thái (6)
            new VectorMeta("lock", "Đăng nhập / Khóa", "Bảo mật", "lock auth login khoa bao mat"),
            new VectorMeta("key", "Token / API Key", "Bảo mật", "key token jwt otp mat khau"),
            new VectorMeta("shield", "Phân quyền / Bảo vệ", "Bảo mật", "shield security admin guard"),
            new VectorMeta("star", "Quan trọng / Ưu tiên", "Bảo mật", "star favorite quan trong goc"),
            new VectorMeta("flag", "Đánh dấu / Cờ", "Bảo mật", "flag mark checkpoint co"),
            new VectorMeta("tag", "Nhãn / Phân loại", "Bảo mật", "tag label nhan phan loai")
    );

    public static final List<String> VECTOR_ICONS = VECTOR_META.stream().map(VectorMeta::id).toList();

    public static final List<String> COLOR_KEYS = List.of(
            "info", "indigo", "purple", "pink", "bad", "accent",
            "warn", "amber", "ok", "emerald", "teal", "cyan", "muted"
    );

    public static final List<String> MONOGRAM_STYLES = List.of("soft", "solid", "outline");

    public record NodeType(
            String id,
            String label,
            String iconKind,
            String colorKey,
            String monogram,
            String imagePath,
            boolean builtIn,
            String customHex,
            String monogramStyle,
            boolean boxed
    ) {
        public NodeType(String id, String label, String iconKind, String colorKey,
                        String monogram, String imagePath, boolean builtIn) {
            this(id, label, iconKind, colorKey, monogram, imagePath, builtIn, "", "soft", false);
        }

        public NodeType {
            id = Text.blank(id) ? DEFAULT_ID : id.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
            label = Text.blank(label) ? id : label.trim();
            iconKind = Text.blank(iconKind) ? "folder" : iconKind.trim().toLowerCase(Locale.ROOT);
            colorKey = Text.blank(colorKey) ? "info" : colorKey.trim().toLowerCase(Locale.ROOT);
            monogram = monogram == null ? "" : monogram.trim();
            if (monogram.length() > 3) {
                monogram = monogram.substring(0, 3).toUpperCase(Locale.ROOT);
            }
            imagePath = imagePath == null ? "" : imagePath.trim();
            customHex = normalizeHex(customHex);
            monogramStyle = Text.blank(monogramStyle) ? "soft" : monogramStyle.trim().toLowerCase(Locale.ROOT);
            if (!MONOGRAM_STYLES.contains(monogramStyle)) {
                monogramStyle = "soft";
            }
        }

        private static String normalizeHex(String raw) {
            if (raw == null || raw.isBlank()) {
                return "";
            }
            String s = raw.trim().toUpperCase(Locale.ROOT);
            if (s.startsWith("#")) {
                s = s.substring(1);
            }
            return s.matches("[0-9A-F]{6}") ? "#" + s : "";
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", label);
            m.put("icon_kind", iconKind);
            m.put("color_key", colorKey);
            m.put("monogram", monogram);
            m.put("image_path", imagePath);
            m.put("built_in", builtIn);
            if (!customHex.isEmpty()) {
                m.put("custom_hex", customHex);
            }
            if (!"soft".equals(monogramStyle)) {
                m.put("monogram_style", monogramStyle);
            }
            if (boxed) {
                m.put("boxed", true);
            }
            return m;
        }

        public static NodeType fromMap(Map<String, Object> m) {
            return new NodeType(
                    Json.str(m, "id", DEFAULT_ID),
                    Json.str(m, "label", "Thư mục"),
                    Json.str(m, "icon_kind", "folder"),
                    Json.str(m, "color_key", "info"),
                    Json.str(m, "monogram", ""),
                    Json.str(m, "image_path", ""),
                    Json.bool(m, "built_in", false),
                    Json.str(m, "custom_hex", ""),
                    Json.str(m, "monogram_style", "soft"),
                    Json.bool(m, "boxed", false)
            );
        }
    }

    private final Map<String, NodeType> types = new LinkedHashMap<>();

    public NodeTypeCatalog() {
        resetDefaults();
    }

    public static List<NodeType> defaults() {
        return List.of(
                new NodeType("folder", "Thư mục / Màn hình", "folder", "info", "", "", true),
                new NodeType("link", "Đường Link / Menu", "link", "accent", "", "", true),
                new NodeType("button", "Button / Nút bấm", "button", "warn", "", "", true),
                new NodeType("icon", "Icon / Biểu tượng", "icon", "purple", "", "", true),
                new NodeType("tab", "Tab / Sub-tab", "tab", "teal", "", "", true),
                new NodeType("modal", "Popup / Modal", "modal", "bad", "", "", true),
                new NodeType("form", "Form / Biểu mẫu", "form", "ok", "", "", true)
        );
    }

    public synchronized void resetDefaults() {
        types.clear();
        for (NodeType t : defaults()) {
            types.put(t.id(), t);
        }
    }

    public synchronized List<NodeType> all() {
        return new ArrayList<>(types.values());
    }

    public synchronized NodeType byId(String id) {
        if (id != null) {
            NodeType found = types.get(id.trim().toLowerCase(Locale.ROOT));
            if (found != null) {
                return found;
            }
        }
        NodeType def = types.get(DEFAULT_ID);
        return def != null ? def : defaults().get(0);
    }

    public synchronized boolean contains(String id) {
        return id != null && types.containsKey(id.trim().toLowerCase(Locale.ROOT));
    }

    public synchronized void upsert(NodeType t) {
        if (t == null) {
            return;
        }
        types.put(t.id(), t);
    }

    /** Reorders a custom/built-in node type up or down in the catalog display order. */
    public synchronized boolean move(String id, int delta) {
        if (Text.blank(id) || delta == 0) {
            return false;
        }
        String k = id.trim().toLowerCase(Locale.ROOT);
        List<NodeType> list = new ArrayList<>(types.values());
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(k)) {
                idx = i;
                break;
            }
        }
        int target = idx + delta;
        if (idx < 0 || target < 0 || target >= list.size()) {
            return false;
        }
        NodeType item = list.remove(idx);
        list.add(target, item);
        types.clear();
        for (NodeType nt : list) {
            types.put(nt.id(), nt);
        }
        return true;
    }

    public synchronized boolean remove(String id) {
        if (Text.blank(id)) {
            return false;
        }
        String k = id.trim().toLowerCase(Locale.ROOT);
        if (DEFAULT_ID.equals(k)) {
            return false;
        }
        return types.remove(k) != null;
    }

    /** Gợi ý loại node hợp lý cho các nhánh cũ chưa gán loại tường minh (VD: 'button "Login"'). */
    public synchronized NodeType resolveForPath(String explicitTypeId, String leafName) {
        if (!Text.blank(explicitTypeId) && contains(explicitTypeId)) {
            return byId(explicitTypeId);
        }
        String n = Text.nz(leafName).trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("button ") || n.startsWith("btn ") || n.startsWith("nút ")) {
            return byId("button");
        }
        if (n.startsWith("link ") || n.startsWith("url ") || n.startsWith("menu")) {
            return byId("link");
        }
        if (n.startsWith("icon ")) {
            return byId("icon");
        }
        if (n.startsWith("tab ")) {
            return byId("tab");
        }
        if (n.startsWith("modal ") || n.startsWith("popup ") || n.startsWith("dialog ")) {
            return byId("modal");
        }
        if (n.startsWith("form ")) {
            return byId("form");
        }
        return byId(DEFAULT_ID);
    }

    public synchronized String toJson() {
        List<Object> list = new ArrayList<>();
        for (NodeType t : types.values()) {
            list.add(t.toMap());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 2);
        root.put("types", list);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public synchronized void loadJson(String raw) {
        resetDefaults();
        if (Text.blank(raw)) {
            return;
        }
        try {
            Map<String, Object> root = Json.parseObject(raw);
            List<Object> list = Json.list(root, "types");
            if (list.isEmpty()) {
                return;
            }
            types.clear();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    NodeType t = NodeType.fromMap((Map<String, Object>) m);
                    types.put(t.id(), t);
                }
            }
            if (!types.containsKey(DEFAULT_ID)) {
                types.put(DEFAULT_ID, defaults().get(0));
            }
        } catch (RuntimeException ignored) {
            resetDefaults();
        }
    }
}
