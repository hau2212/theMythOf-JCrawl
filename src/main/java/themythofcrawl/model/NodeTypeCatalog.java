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
 * kèm hỗ trợ thêm / sửa / xóa tùy biến, đổi biểu tượng vector, chữ viết tắt (monogram) hoặc ảnh tùy chọn.
 */
public final class NodeTypeCatalog {
    public static final String DEFAULT_ID = "folder";

    public static final List<String> VECTOR_ICONS = List.of(
            "folder", "link", "button", "icon", "tab", "modal", "form",
            "star", "bell", "cart", "lock", "key", "globe", "database", "shield", "tag", "terminal"
    );

    public static final List<String> COLOR_KEYS = List.of(
            "info", "accent", "warn", "purple", "teal", "bad", "ok", "muted"
    );

    public record NodeType(
            String id,
            String label,
            String iconKind,
            String colorKey,
            String monogram,
            String imagePath,
            boolean builtIn
    ) {
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
                    Json.bool(m, "built_in", false)
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
        root.put("version", 1);
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
