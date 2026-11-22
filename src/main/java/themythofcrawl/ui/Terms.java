package themythofcrawl.ui;

/**
 * V_9.3 glossary: ONE name per concept, used by every screen (see README "Từ điển"). A test
 * ({@code V93TermsTest}) fails when an old name (Role, Group, Sub-folder, Acc Crawl, API gốc, Send, Replay,
 * Composer, Workbench, Lineage, Spotlight, OK: / INFO: / WARN: prefixes) comes back in a UI string.
 */
public final class Terms {
    private Terms() {
    }

    /** The account the tester crawls with (was: API gốc, Acc Crawl, 00_Original, Origin). */
    public static final String CRAWL_ACCOUNT = "Tài khoản crawl";
    /** A simulated account used to test access (was: Role). */
    public static final String ROLE = "Vai trò";
    public static final String ROLES = "vai trò";
    /** A folder of the tree (was: Sub-folder, Group Path). */
    public static final String BRANCH = "Nhánh";
    /** Where new requests land (was: Đích). */
    public static final String CRAWLING_BRANCH = "Nhánh đang crawl";
    public static final String SEND = "Gửi";
    public static final String RESEND = "Gửi lại";
    /** Edit a request then send it (was: Composer). */
    public static final String EDIT_AND_SEND = "Sửa & gửi";
    /** The one window for blocking while crawling and resending (was: Action Workbench, Bảo vệ dữ liệu, Luật chặn). */
    public static final String BLOCK_AND_RESEND = "Chặn & gửi lại";
    /** Every capture of the same API (was: Lineage, Bản Gốc & Trùng). */
    public static final String CAPTURES = "Các lần bắt";
    /** Quick search (was: Spotlight). */
    public static final String SEARCH = "Tìm";
    /** The app's own action log (was: Nhật ký, Log). */
    public static final String ACTIVITY = "Hoạt động";
    /** Requests seen by the block rules (was: Nhật ký Cổng Action). */
    public static final String BLOCK_HISTORY = "Lịch sử chặn";
    public static final String REPORT_TO_CLAUDE = "Báo lỗi cho Claude";
    public static final String NEEDS_ACTION = "Cần xử lý";

    /** "Lần 5 · gốc API 492". */
    public static String captureOf(int no, int originGid) {
        return "Lần " + no + " · gốc API " + originGid;
    }
}
