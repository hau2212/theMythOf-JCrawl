package themythofcrawl.project;

import java.util.ArrayList;
import java.util.List;

/**
 * Columns a project sheet can show, in any order the user picks ("Cột..."). {@link #ACCOUNTS} expands to one
 * column per account (A · admin, B · staff...), or a single account with "ACC:B". {@link #KEY} (Mã) is always
 * written first and hidden in Excel: it lets JCrawl match rows when a sheet comes back (client answers, sorting).
 */
public final class Columns {
    public static final String KEY = "KEY";
    public static final String STT = "STT";
    public static final String PATH = "PATH";
    public static final String METHOD = "METHOD";
    public static final String BRANCH = "BRANCH";
    public static final String HOST = "HOST";
    public static final String ACCOUNTS = "ACCOUNTS";
    public static final String REACHED = "REACHED";
    public static final String BAC = "BAC";
    public static final String QA = "QA";
    public static final String INFO = "INFO";
    public static final String NOTE = "NOTE";
    public static final String TESTER = "TESTER";
    public static final String TESTED_AT = "TESTED_AT";
    public static final String SOURCE = "SOURCE";
    public static final String BUGS = "BUGS";
    public static final String TAGS = "TAGS";
    public static final String ROLES = "ROLES";
    public static final String ACC_PREFIX = "ACC:";

    public static final List<String> ALL = List.of(STT, PATH, METHOD, BRANCH, HOST, ACCOUNTS, REACHED, BAC, BUGS, TAGS, ROLES, QA, INFO,
            NOTE, TESTER, TESTED_AT, SOURCE);

    private Columns() {
    }

    public static String defaultHeader(String id) {
        if (id.startsWith(ACC_PREFIX)) {
            return id.substring(ACC_PREFIX.length());
        }
        return switch (id) {
            case KEY -> "Mã";
            case STT -> "STT";
            case PATH -> "Path";
            case METHOD -> "Method";
            case BRANCH -> "Nhánh / Chức năng";
            case HOST -> "Host";
            case ACCOUNTS -> "Các tài khoản";
            case REACHED -> "Số tài khoản gọi được";
            case BAC -> "Kết luận phân quyền";
            case QA -> "Q&A";
            case INFO -> "Info";
            case NOTE -> "Ghi chú";
            case TESTER -> "Người test";
            case TESTED_AT -> "Ngày test";
            case SOURCE -> "Nguồn";
            case BUGS -> "Lỗi đã đánh dấu";
            case TAGS -> "Tag";
            case ROLES -> "Kết quả theo vai trò";
            default -> id;
        };
    }

    public static String explain(String id) {
        return switch (id) {
            case ACCOUNTS -> "Mỗi tài khoản 1 cột: Done (A), Denied (B), Missing (C)...";
            case REACHED -> "Bao nhiêu tài khoản gọi được (2xx), vd 2/3";
            case BAC -> "Tài khoản thấp gọi được thứ tài khoản cao bị chặn: cần xem";
            case SOURCE -> "File chuẩn / Từ crawl A / Ngoài danh sách / Chỉ B có";
            case TESTER -> "Người cập nhật trạng thái gần nhất";
            case BUGS -> "Lỗi đã đánh dấu ở tab Lỗi (đang mở), theo tài khoản";
            case ROLES -> "Gửi lại theo vai trò: status của từng vai trò, vd 01_Admin 200 · 02_User 403";
            default -> "";
        };
    }

    public static ProjectConfig.Profile profile(String name, String... ids) {
        List<ProjectConfig.Col> cols = new ArrayList<>();
        for (String id : ids) {
            cols.add(new ProjectConfig.Col(id, "", true));
        }
        for (String id : ALL) {
            if (cols.stream().noneMatch(c -> c.id().equals(id))) {
                cols.add(new ProjectConfig.Col(id, "", false));
            }
        }
        return new ProjectConfig.Profile(name, cols);
    }

    public static List<ProjectConfig.Profile> presets() {
        return List.of(
                profile("Nội bộ", STT, METHOD, PATH, BRANCH, ACCOUNTS, REACHED, BAC, BUGS, QA, INFO, NOTE, TESTER, TESTED_AT, SOURCE),
                profile("Nộp khách hàng", STT, PATH, METHOD, BRANCH, ACCOUNTS, QA, NOTE),
                profile("Q&A", STT, PATH, METHOD, QA, ACCOUNTS));
    }

    public static String header(ProjectConfig.Col c) {
        return c.header() == null || c.header().isBlank() ? defaultHeader(c.id()) : c.header();
    }
}
