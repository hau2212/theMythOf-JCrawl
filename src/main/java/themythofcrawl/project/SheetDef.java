package themythofcrawl.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/**
 * One sheet of the project workbook ("Sổ dự án"): the same list drives the .xlsx reports, the personal Excel copy
 * and the team Google Sheet. {@code account} "*" on an ACCOUNT sheet = one sheet per account.
 *
 * @param filter for FILTER sheets: missing:B / done:A / denied:B / diff / only / qa / info / bac / text:abc
 */
public record SheetDef(String id, String name, Type type, String account, String filter, String profile, boolean enabled) {

    public enum Type {
        OVERVIEW("Tổng quan", "Số liệu theo tài khoản, so sánh, biểu đồ (công thức tự tính)"),
        SUMMARY("Tổng hợp", "Mỗi API 1 dòng, mỗi tài khoản 1 cột"),
        ACCOUNT("Tài khoản", "API của 1 tài khoản (\"*\" = mỗi tài khoản 1 sheet)"),
        DIFF("Chênh lệch", "API mà không phải tài khoản nào cũng gọi được"),
        ONLY("Ngoài danh sách", "API chỉ một số tài khoản có / chưa có trong danh sách chuẩn"),
        QA("Q&A", "Câu hỏi, ảnh theo bước, câu trả lời"),
        QA_DETAIL("Chi tiết Q&A", "Mỗi Q&A 1 khối: câu hỏi, các bước kèm ảnh, ô trả lời"),
        INFO("Info", "Ghi chú Info"),
        BAC("Phân quyền", "Nghi BAC: tài khoản thấp gọi được thứ tài khoản cao bị chặn"),
        LOG("Nhật ký thay đổi", "Ai sửa gì, lúc nào"),
        FILTER("Lọc", "Dòng của Tổng hợp theo bộ lọc (vd còn thiếu của B)"),
        BLANK("Trống", "Sheet trống để ghi tay");

        public final String label;
        public final String explain;

        Type(String label, String explain) {
            this.label = label;
            this.explain = explain;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public SheetDef {
        id = Text.blank(id) ? "sh-" + UUID.randomUUID().toString().substring(0, 8) : id;
        name = Text.nz(name).trim().isEmpty() ? (type == null ? "Sheet" : type.label) : name.trim();
        type = type == null ? Type.BLANK : type;
        account = Text.nz(account);
        filter = Text.nz(filter);
        profile = Text.nz(profile);
    }

    public static SheetDef of(Type t) {
        return new SheetDef(t.name().toLowerCase(java.util.Locale.ROOT), t.label, t, t == Type.ACCOUNT ? "*" : "", "", "", true);
    }

    public static List<SheetDef> defaults() {
        List<SheetDef> out = new ArrayList<>();
        for (Type t : List.of(Type.OVERVIEW, Type.SUMMARY, Type.ACCOUNT, Type.DIFF, Type.ONLY, Type.QA, Type.QA_DETAIL, Type.INFO,
                Type.BAC, Type.LOG)) {
            out.add(of(t));
        }
        return out;
    }

    public SheetDef with(String newName, Type t, String acc, String flt, String prof, boolean on) {
        return new SheetDef(id, newName, t, acc, flt, prof, on);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("type", type.name());
        m.put("account", account);
        m.put("filter", filter);
        m.put("profile", profile);
        m.put("enabled", enabled);
        return m;
    }

    public static SheetDef fromMap(Map<String, Object> m) {
        Type t;
        try {
            t = Type.valueOf(Json.str(m, "type", "BLANK"));
        } catch (RuntimeException e) {
            t = Type.BLANK;
        }
        return new SheetDef(Json.str(m, "id", null), Json.str(m, "name", t.label), t, Json.str(m, "account", ""),
                Json.str(m, "filter", ""), Json.str(m, "profile", ""), Json.bool(m, "enabled", true));
    }
}
