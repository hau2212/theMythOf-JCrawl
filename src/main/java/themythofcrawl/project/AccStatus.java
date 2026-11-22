package themythofcrawl.project;

import java.util.Locale;

import themythofcrawl.core.Text;

/** Result of one account on one API of the project list ("Done (A)", "Denied (B)", "Missing (C)"...). */
public enum AccStatus {
    DONE("Done", "Gọi được (2xx)"),
    DENIED("Denied", "Bị chặn (401 / 403 / chuyển về đăng nhập)"),
    ERROR("Error", "Lỗi khác (4xx / 5xx)"),
    MISSING("Missing", "Chưa gọi tới"),
    DOING("Doing", "Đang test"),
    OOS("OOS", "Ngoài phạm vi");

    public final String word;
    public final String explain;

    AccStatus(String word, String explain) {
        this.word = word;
        this.explain = explain;
    }

    public String label(String letter) {
        return word + " (" + letter + ")";
    }

    @Override
    public String toString() {
        return word + " - " + explain;
    }

    /** From an HTTP status of the account's own request. */
    public static AccStatus ofHttp(Integer http) {
        if (http == null || http <= 0) {
            return DOING;
        }
        if (http >= 200 && http < 300) {
            return DONE;
        }
        if (http == 401 || http == 403 || http >= 300 && http < 400) {
            return DENIED;
        }
        return ERROR;
    }

    /** Parses "Done (A) · Q&A (A)", "done", "Đã xong"... (the first status word wins); null when none. */
    public static AccStatus parse(String text) {
        String t = Text.nz(text).trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return null;
        }
        for (AccStatus s : values()) {
            if (t.startsWith(s.word.toLowerCase(Locale.ROOT))) {
                return s;
            }
        }
        if (t.startsWith("ok") || t.startsWith("pass") || t.startsWith("xong") || t.startsWith("được")) {
            return DONE;
        }
        if (t.startsWith("403") || t.startsWith("401") || t.startsWith("chặn") || t.startsWith("block")) {
            return DENIED;
        }
        if (t.startsWith("-") || t.startsWith("chưa")) {
            return MISSING;
        }
        return null;
    }
}
