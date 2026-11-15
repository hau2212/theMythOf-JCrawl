package themythofcrawl.model;

import java.util.Locale;

/** What one role rule does. Applying it to a request is RoleRequestBuilder's job (see rolereq). */
public enum RuleMode {
    REPLACE("ghi đè hoặc thêm header"),
    ADD("thêm 1 dòng header mới"),
    REMOVE("xóa header"),
    COOKIE_SET("đặt / sửa 1 biến trong Cookie"),
    COOKIE_REMOVE("xóa 1 biến khỏi Cookie");

    public static final String REMOVE_TOKEN = "[REMOVE]";
    public final String hint;

    RuleMode(String hint) {
        this.hint = hint;
    }

    public boolean cookie() {
        return this == COOKIE_SET || this == COOKIE_REMOVE;
    }

    public boolean removes() {
        return this == REMOVE || this == COOKIE_REMOVE;
    }

    /** Stored mode text -> mode; a value of [REMOVE] means the removing variant. */
    public static RuleMode of(Object mode, Object value) {
        String m = mode == null ? "" : String.valueOf(mode).trim().toUpperCase(Locale.ROOT);
        boolean removeValue = value != null && String.valueOf(value).trim().equalsIgnoreCase(REMOVE_TOKEN);
        if (m.equals("COOKIE_SET") || m.equals("COOKIE_REMOVE")) {
            return m.equals("COOKIE_REMOVE") || removeValue ? COOKIE_REMOVE : COOKIE_SET;
        }
        if (removeValue) {
            return REMOVE;
        }
        for (RuleMode r : values()) {
            if (r.name().equals(m)) {
                return r;
            }
        }
        return REPLACE;
    }
}
