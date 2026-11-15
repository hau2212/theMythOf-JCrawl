package themythofcrawl.place;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.PlaceHint;

/** The 'Cần xử lý', 'Giữ chỗ', 'API trùng & ẩn' and 'Nhật ký' rows of the Audit tab. */
public final class Decisions {
    public enum Kind {
        DUP("⧉ Trùng ở chỗ mới"), RETURN_MISSING("↩ Path cũ đã bị xóa"), RETURN("↩ Quay về chỗ cũ"), NEW("⏳ API mới");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    private Decisions() {
    }

    public static Kind kind(ApiGroup g) {
        if (g.dupPending()) {
            return Kind.DUP;
        }
        PlaceHint h = g.placeHint();
        if (h != null) {
            return h.oldMissing() ? Kind.RETURN_MISSING : Kind.RETURN;
        }
        return Kind.NEW;
    }

    public static String pendingLabel(ApiGroup g) {
        if (!g.pending()) {
            return "";
        }
        if (g.dupPending()) {
            return "⏳ API trùng #" + g.dupNo() + " - giữ ở chỗ mới?";
        }
        return g.placeHint() != null ? "⏳ Quay về chỗ cũ - chờ xác nhận" : "⏳ Chờ xác nhận vị trí";
    }

    /** '[⧉2/3]' on an API that has duplicates (copy number / copies), else ''. */
    public static String dupBadge(ApiGroup g) {
        return g.dupTotal() <= 1 ? "" : "[⧉" + g.dupNo() + "/" + g.dupTotal() + "]";
    }

    /** Plain explanation of what happened to one waiting API and what each choice does. */
    public static String text(ApiGroup g, Collection<ApiGroup> copies) {
        Kind k = kind(g);
        PlaceHint h = g.placeHint();
        String api = Text.apiLabel(g.gid()) + " " + g.endpoint();
        switch (k) {
            case RETURN_MISSING:
                return api + " từng nằm ở '" + h.old() + "', nhưng path đó đã bị xóa. Extension đã tạo lại '" + h.old()
                        + "' để API hiện đúng chỗ cũ.\nLần này nó được bắt khi đích là '" + h.found() + "'.\n\nBạn chọn:\n"
                        + "• ✔ Giữ chỗ hiện tại = khôi phục path cũ.\n• ↪ Về nơi vừa gặp = chuyển vào '" + h.found()
                        + "' (path cũ tạo lại sẽ trống, xóa được).\n• 📁 Path khác = gộp vào path có sẵn hoặc tạo path mới.";
            case RETURN:
                return api + " đã bị xóa trước đây khi đang ở '" + h.old() + "', nên được đưa về đúng chỗ đó.\n"
                        + "Lần này nó được bắt khi đích là '" + h.found() + "'.\n\n• ✔ Giữ chỗ hiện tại = để ở '" + h.old()
                        + "'.\n• ↪ Về nơi vừa gặp = chuyển vào '" + h.found() + "'.\n• 📁 Path khác = chọn chỗ khác.";
            case DUP: {
                List<String> where = new ArrayList<>();
                copies.stream().filter(c -> c != g).sorted(Comparator.comparingInt(ApiGroup::dupNo)).forEach(c ->
                        where.add("   #" + c.dupNo() + " · " + Text.apiLabel(c.gid()) + " · " + c.branch()
                                + (c.hidden() ? " (đang ẩn)" : "")));
                return api + " là bản trùng #" + g.dupNo() + ": endpoint này đã có ở\n"
                        + (where.isEmpty() ? "   (bản trước đã bị xóa)" : String.join("\n", where))
                        + "\nLần này gặp lại ở chỗ mới '" + g.branch() + "'.\n\n• ✔ Giữ = thêm bản trùng #" + g.dupNo()
                        + " ở '" + g.branch() + "' (hiện tím ⧉ trên màn hình crawl).\n• 🙈 Không giữ = ẩn khỏi màn hình "
                        + "crawl; vẫn nằm trong tab API trùng & ẩn, bấm Hiện để đưa lại.";
            }
            default:
                return api + " mới được bắt vào đích '" + g.branch() + "'.\n\n• ✔ Giữ chỗ hiện tại = xác nhận.\n"
                        + "• 📁 Path khác = chuyển sang path khác.";
        }
    }

    // ------------------------------------------------------------------ rows

    /** A row of one of the Audit tables: payload, colour, cells. */
    public record Row<T>(T payload, AuditLevel level, List<Object> cells) {
    }

    public static final List<String> DECIDE_COLS = List.of("Việc", "API", "Endpoint", "Đang ở", "Vừa gặp tại (đích lúc bắt)");
    public static final List<String> DUP_COLS = List.of("Endpoint", "Bản", "API", "Đang ở", "Trạng thái", "Excel", "Bắt lúc");
    public static final List<String> PLACE_COLS = List.of("Method", "Path", "Host", "Vị trí giữ chỗ", "API hiện tại",
            "Trạng thái", "Đã xóa (lần)", "Lần gặp", "Bỏ qua do trùng", "Gặp lần cuối", "Gặp lần cuối tại (đích lúc đó)",
            "Đổi vị trí (lần)");
    public static final List<String> LOG_COLS = List.of("#", "Thời gian", "Hành động", "API #", "Method", "Path", "Từ",
            "Sang", "Ghi chú");

    public static List<Row<ApiGroup>> decideRows(Collection<ApiGroup> pending) {
        List<Row<ApiGroup>> out = new ArrayList<>();
        pending.stream().sorted(Comparator.comparingInt(ApiGroup::gid)).forEach(g -> {
            Kind k = kind(g);
            String found = g.placeHint() != null ? g.placeHint().found() : (g.dupPending() ? g.branch() : "");
            out.add(new Row<>(g, k == Kind.NEW ? AuditLevel.PENDING : AuditLevel.INFO,
                    List.of(k.label, Text.apiLabel(g.gid()), g.endpoint(), g.branch(), found)));
        });
        return out;
    }

    /** Every copy of endpoints that have several, or a hidden one. */
    public static List<Row<ApiGroup>> dupRows(Map<String, List<ApiGroup>> copiesByKey, boolean hiddenOnly) {
        List<Row<ApiGroup>> out = new ArrayList<>();
        for (List<ApiGroup> copies : copiesByKey.values()) {
            if (copies.isEmpty() || (copies.size() < 2 && copies.stream().noneMatch(ApiGroup::hidden))) {
                continue;
            }
            for (ApiGroup c : copies.stream().sorted(Comparator.comparingInt(ApiGroup::dupNo)).toList()) {
                if (hiddenOnly && !c.hidden()) {
                    continue;
                }
                String state = c.hidden() ? "🙈 Đang ẩn" : c.dupPending() ? "⏳ Chờ quyết định" : "Đang hiện";
                AuditLevel lvl = c.hidden() ? AuditLevel.PENDING : c.dupPending() ? AuditLevel.INFO : AuditLevel.OK;
                out.add(new Row<>(c, lvl, List.of(c.endpoint(), "#" + c.dupNo() + " / " + copies.size(),
                        Text.apiLabel(c.gid()), c.branch(), state,
                        c.target() != null ? "#" + c.target().sttText() : "", c.created())));
            }
        }
        out.sort(Comparator.comparing((Row<ApiGroup> r) -> String.valueOf(r.cells().get(0)))
                .thenComparingInt(r -> r.payload().dupNo()));
        return out;
    }

    public static AuditLevel levelOf(PlacementBook.State s) {
        return switch (s) {
            case LIVE -> AuditLevel.OK;
            case PENDING -> AuditLevel.PENDING;
            default -> AuditLevel.NONE;
        };
    }

    /** Placement book rows; view 0 all, 1 live, 2 deleted, 3 pending. */
    public static List<Row<PlacementBook.Record>> placeRows(PlacementBook book, int view, String filter) {
        String f = Text.nz(filter).trim().toLowerCase(Locale.ROOT);
        List<Row<PlacementBook.Record>> out = new ArrayList<>();
        for (PlacementBook.Record r : book.records()) {
            PlacementBook.State st = r.state();
            if ((view == 1 && st != PlacementBook.State.LIVE)
                    || (view == 2 && st != PlacementBook.State.DELETED && st != PlacementBook.State.NONE)
                    || (view == 3 && st != PlacementBook.State.PENDING)) {
                continue;
            }
            List<Object> cells = List.of(Text.nz(r.method), Text.nz(r.path), Text.nz(r.host), Text.nz(r.place),
                    r.gid != null ? Text.apiLabel(r.gid) : "", st.label, r.deleted, r.seen, r.skipped, Text.nz(r.lastSeen),
                    Text.nz(r.lastFoundAt), Math.max(r.history.size() - 1, 0));
            if (!f.isEmpty() && !String.join(" ", cells.stream().map(String::valueOf).toList()).toLowerCase(Locale.ROOT)
                    .contains(f)) {
                continue;
            }
            out.add(new Row<>(r, levelOf(st), cells));
        }
        out.sort(Comparator.comparing((Row<PlacementBook.Record> r) -> r.payload().place == null ? "~"
                : r.payload().place.toLowerCase(Locale.ROOT)).thenComparing(r -> Text.nz(r.payload().path))
                .thenComparing(r -> Text.nz(r.payload().method)));
        return out;
    }

    public static AuditLevel levelOf(PlacementBook.Action a) {
        return switch (a) {
            case PENDING, RETURN, DUP, HIDE -> AuditLevel.PENDING;
            case CONFIRM, DUP_KEEP, SHOW -> AuditLevel.OK;
            case MOVE, REVERT -> AuditLevel.REDIRECT;
            case DELETE, FORGET -> AuditLevel.ERROR;
            case EDIT, MANUAL, RENAME, RECAPTURE -> AuditLevel.INFO;
            default -> AuditLevel.NONE;
        };
    }

    /** Placement log rows, newest first; only one endpoint's when `key` is set. */
    public static List<Row<PlacementBook.Event>> logRows(PlacementBook book, String key, String filter) {
        String f = Text.nz(filter).trim().toLowerCase(Locale.ROOT);
        List<PlacementBook.Event> log = book.log();
        List<Row<PlacementBook.Event>> out = new ArrayList<>();
        for (int i = log.size() - 1; i >= 0; i--) {
            PlacementBook.Event e = log.get(i);
            if (key != null && !key.equals(e.key())) {
                continue;
            }
            List<Object> cells = List.of(e.id(), e.time(), e.action().label, e.gid() != null ? Text.apiLabel(e.gid()) : "",
                    e.method(), e.path(), Text.nz(e.src()), Text.nz(e.dst()), e.note());
            if (!f.isEmpty() && !String.join(" ", cells.stream().map(String::valueOf).toList()).toLowerCase(Locale.ROOT)
                    .contains(f)) {
                continue;
            }
            out.add(new Row<>(e, levelOf(e.action()), cells));
        }
        return out;
    }
}
