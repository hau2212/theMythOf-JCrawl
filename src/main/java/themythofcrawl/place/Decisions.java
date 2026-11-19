package themythofcrawl.place;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import themythofcrawl.app.Engine;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ApiTagCatalog;
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

    /** Diễn giải bằng lời nói tự nhiên và khuyến nghị hành động rõ ràng cho 1 API / chuỗi trùng lặp. */
    public record SmartAdvice(
            String headline,
            String explanation,
            List<String> recommendations,
            String suggestedAction,
            boolean suspectedRefresh
    ) {
        public String toFormattedText() {
            StringBuilder sb = new StringBuilder();
            if (headline != null && !headline.isBlank()) {
                sb.append(headline).append("\n");
            }
            if (explanation != null && !explanation.isBlank()) {
                sb.append(explanation).append("\n");
            }
            if (recommendations != null && !recommendations.isEmpty()) {
                sb.append("\nKhuyến nghị hành động:\n");
                for (String rec : recommendations) {
                    sb.append("• ").append(rec).append("\n");
                }
            }
            return sb.toString().trim();
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

    /** Natural-language explanation & actionable recommendations for one API group (with lineage & REFRESH awareness). */
    public static SmartAdvice advise(Engine engine, ApiGroup g) {
        if (g == null) {
            return new SmartAdvice("Chưa chọn API", "Chọn 1 API để xem diễn giải chuỗi Gốc / Trùng và khuyến nghị.", List.of(), "", false);
        }
        List<ApiGroup> copies = engine == null ? List.of(g) : new ArrayList<>(engine.copiesOf(g.bookKey()));
        copies.sort(Comparator.comparingInt(ApiGroup::dupNo));
        ApiGroup origin = engine == null ? null : engine.originOf(g.bookKey());
        if (origin == null && !copies.isEmpty()) {
            origin = copies.get(0);
        }
        PlacementBook.Record rec = engine == null || g.bookKey() == null ? null : engine.book().get(g.bookKey());
        boolean refreshTagged = g.hasTag(ApiTagCatalog.TAG_REFRESH) || (rec != null && rec.tags.contains(ApiTagCatalog.TAG_REFRESH));
        boolean suspected = g.suspectedRefresh() || refreshTagged || (rec != null && rec.refreshCount > 0);
        String trigger = !Text.blank(g.triggeredByAction()) ? g.triggeredByAction()
                : (rec != null && !rec.triggeredByActions.isEmpty() ? String.join(", ", rec.triggeredByActions) : "");
        Kind k = kind(g);
        PlaceHint h = g.placeHint();
        String apiLbl = Text.apiLabel(g.gid()) + " (" + Text.groupId(g.gid()) + ") · " + g.endpoint();

        List<String> recs = new ArrayList<>();
        if (suspected && copies.size() > 1) {
            String origWhere = origin != null
                    ? Text.groupId(origin.gid()) + " tại [" + origin.branch() + "]"
                    : (rec != null && rec.originBranch != null ? "[" + rec.originBranch + "]" : "(đã xóa)");
            String head = refreshTagged
                    ? "API tải lại dữ liệu [REFRESH] xuất hiện lặp " + copies.size() + " lần"
                    : "Nghi vấn API tải lại sau hành động (? REFRESH) — lặp " + copies.size() + " lần";
            StringBuilder exp = new StringBuilder();
            exp.append(apiLbl).append(" là API GET xuất hiện ngay sau hành động");
            if (!trigger.isEmpty()) {
                exp.append(" (").append(trigger).append(")");
            }
            exp.append(".\n• API GỐC định hình (#1 ★): ").append(origWhere).append(".");
            exp.append("\n• Bản hiện tại (#").append(g.dupNo()).append(") xuất hiện tại [").append(g.branch()).append("].");
            if (rec != null && rec.refreshCount > 0) {
                exp.append("\n• Đã ghi nhận tự tải lại ").append(rec.refreshCount).append(" lần.");
            }
            recs.add("KHUYÊN DÙNG: Gắn tag [REFRESH] và bấm 'Chỉ giữ GỐC #1 & Ẩn bản lặp' để báo cáo & cây thư mục sạch sẽ.");
            recs.add("Nếu muốn phân quyền lại từ vị trí mới [" + g.branch() + "], hãy bấm 'Đặt làm GỐC #1'.");
            recs.add("Cấu hình chính sách tự động M2 (Tự bỏ qua bản lặp) trong 'Quản lý Tag & REFRESH' để không phải xác nhận lại.");
            return new SmartAdvice(head, exp.toString(), recs, "KEEP_ORIGIN_AND_TAG_REFRESH", true);
        }

        if (k == Kind.DUP || copies.size() > 1) {
            boolean isOrig = origin != null && origin.gid() == g.gid();
            String origDesc = origin != null
                    ? "#" + origin.dupNo() + " · " + Text.groupId(origin.gid()) + " tại [" + origin.branch() + "]"
                            + (origin.copyStt() != null ? " (STT " + origin.copyStt() + ")" : "")
                    : (rec != null && rec.originBranch != null ? "Gốc cũ tại [" + rec.originBranch + "] (đã xóa)" : "(chưa xác định)");
            StringBuilder exp = new StringBuilder();
            if (isOrig) {
                exp.append(apiLbl).append(" là ★ API GỐC ĐỊNH HÌNH (#1) cho endpoint này, đặt tại [").append(g.branch()).append("].");
            } else {
                exp.append(apiLbl).append(" là bản trùng #").append(g.dupNo()).append("/").append(copies.size())
                        .append(" xuất hiện tại [").append(g.branch()).append("].\n")
                        .append("• ★ API GỐC định hình ban đầu nằm ở: ").append(origDesc).append(".");
            }
            if (copies.size() > 1) {
                exp.append("\n• Tổng cộng có ").append(copies.size()).append(" vị trí xuất hiện: ");
                List<String> locs = new ArrayList<>();
                for (ApiGroup c : copies) {
                    locs.add("#" + c.dupNo() + " " + Text.groupId(c.gid()) + " [" + c.branch() + "]"
                            + (c.hidden() ? " (ẩn)" : ""));
                }
                exp.append(String.join("  |  ", locs));
            }
            String head = isOrig
                    ? "★ API GỐC ĐỊNH HÌNH (#1/" + copies.size() + ") · " + g.endpoint()
                    : "⧉ Bản trùng #" + g.dupNo() + "/" + copies.size() + " (Gốc ở " + (origin != null ? origin.branch() : "?") + ")";
            recs.add("Chỉ cần 1 bản duy nhất trong báo cáo: chọn 'Chỉ giữ GỐC #1 & Ẩn #2+' để gom gọn.");
            if (!isOrig) {
                recs.add("API này dùng chung ở màn hình mới [" + g.branch() + "]: chọn 'Giữ bản ở chỗ mới' nếu muốn liệt kê cả 2 nơi.");
                recs.add("Vị trí mới [" + g.branch() + "] mới là nơi chính xác hơn: chọn 'Đặt bản này làm GỐC #1' hoặc 'Đưa về chỗ Gốc'.");
            } else {
                recs.add("Mọi bản trùng sau đều tham chiếu về bản GỐC #1 này; bạn có thể sửa STT hoặc đổi vị trí GỐC bất cứ lúc nào.");
            }
            if ("GET".equalsIgnoreCase(g.method())) {
                recs.add("Nếu API GET này chỉ tự tải lại UI sau thao tác khác: gắn tag [REFRESH].");
            }
            return new SmartAdvice(head, exp.toString(), recs, isOrig ? "KEEP" : "KEEP_ORIGIN_HIDE_OTHERS", suspected);
        }

        if (k == Kind.RETURN_MISSING && h != null) {
            String head = "↩ API quay về chỗ cũ (path cũ đã bị xóa)";
            String exp = apiLbl + " từng giữ chỗ tại [" + h.old() + "], nhưng path đó đã bị xóa nên extension tạo lại ["
                    + h.old() + "] để đặt về đúng chỗ cũ.\nLần này API được bắt khi Đích là [" + h.found() + "].";
            recs.add("Giữ chỗ hiện tại [" + h.old() + "]: khôi phục đúng vị trí cũ trong sơ đồ.");
            recs.add("Về nơi vừa gặp [" + h.found() + "]: chấp nhận vị trí mới (path cũ tạo lại sẽ trống).");
            recs.add("Chọn Path khác: chuyển tới nhánh mới và cập nhật luôn vị trí giữ chỗ.");
            return new SmartAdvice(head, exp, recs, "RETURN_DECIDE", suspected);
        }

        if (k == Kind.RETURN && h != null) {
            String head = "↩ API đã xóa trước đây — vừa tự đưa về chỗ cũ";
            String exp = apiLbl + " từng nằm tại [" + h.old() + "] trước khi bị xóa, nên vừa được tự động trả về ["
                    + h.old() + "].\nLần này API xuất hiện khi Đích là [" + h.found() + "].";
            recs.add("KHUYÊN DÙNG: Bấm 'Giữ chỗ hiện tại' nếu sơ đồ chức năng vẫn giữ nguyên [" + h.old() + "].");
            recs.add("Bấm 'Về nơi vừa gặp' nếu API đã đổi sang chức năng mới [" + h.found() + "].");
            return new SmartAdvice(head, exp, recs, "KEEP", suspected);
        }

        String head = "★ API mới định hình (#1) tại [" + g.branch() + "]";
        String exp = apiLbl + " lần đầu được bắt vào đích [" + g.branch() + "]. Đây sẽ là API GỐC (#1) định hình vị trí cho các lần gặp sau.";
        recs.add("Xác nhận 'Giữ chỗ hiện tại' nếu nhánh [" + g.branch() + "] đã đúng với chức năng vừa thao tác.");
        recs.add("Chọn 'Path khác...' nếu bạn quên đổi Đích trước khi bấm nút trên trình duyệt.");
        if (suspected) {
            recs.add("Phát hiện API GET này xuất hiện ngay sau hành động" + (trigger.isEmpty() ? "" : " (" + trigger + ")")
                    + " — cân nhắc gắn tag [REFRESH].");
        }
        return new SmartAdvice(head, exp, recs, "KEEP", suspected);
    }

    public static SmartAdvice adviseEndpoint(Engine engine, String bookKey) {
        if (engine == null || bookKey == null) {
            return advise(engine, null);
        }
        List<ApiGroup> copies = engine.copiesOf(bookKey);
        if (!copies.isEmpty()) {
            ApiGroup target = copies.stream().filter(ApiGroup::dupPending).findFirst()
                    .orElse(copies.stream().min(Comparator.comparingInt(ApiGroup::dupNo)).orElse(copies.get(0)));
            return advise(engine, target);
        }
        PlacementBook.Record rec = engine.book().get(bookKey);
        if (rec == null) {
            return advise(engine, null);
        }
        String head = "Vị trí giữ chỗ (Ghost) · " + rec.method + " " + rec.path;
        String exp = "Endpoint này từng có API GỐC tại [" + Text.nz(rec.originBranch != null ? rec.originBranch : rec.place)
                + "] nhưng đã bị xóa (" + rec.deleted + " lần). Hệ thống vẫn giữ chỗ để tự trả về đúng nhánh khi bắt lại.";
        List<String> recs = List.of(
                "Bấm 'Sửa vị trí giữ chỗ' nếu muốn lần bắt lại sau tự đi vào nhánh mới.",
                "Bấm 'Quên giữ chỗ' nếu muốn coi như endpoint hoàn toàn mới ở lần crawl sau."
        );
        return new SmartAdvice(head, exp, recs, "GHOST", rec.refreshCount > 0);
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
                        where.add("   " + (c.dupNo() == 1 ? "#1 ★ GỐC" : "#" + c.dupNo() + " ⧉ TRÙNG")
                                + " · " + Text.apiLabel(c.gid()) + " · " + c.branch()
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
    public static final List<String> DUP_COLS = List.of("Endpoint", "Bản / Vai trò", "API", "Đang ở", "Trạng thái", "Excel / STT", "Bắt lúc");
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
            String kindLbl = k.label + (g.suspectedRefresh() ? " [? REFRESH]" : "");
            out.add(new Row<>(g, k == Kind.NEW ? AuditLevel.PENDING : AuditLevel.INFO,
                    List.of(kindLbl, Text.apiLabel(g.gid()), g.endpoint(), g.branch(), found)));
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
                String rolePrefix = c.dupNo() == 1 ? "[GỐC] " : "[TRÙNG] ";
                String state = rolePrefix + (c.hidden() ? "Đang ẩn" : c.dupPending() ? "Chờ quyết định" : "Đang hiện");
                if (c.hasTag(ApiTagCatalog.TAG_REFRESH)) {
                    state += " [REFRESH]";
                } else if (c.suspectedRefresh()) {
                    state += " [? Refresh]";
                }
                String stt = c.target() != null ? "#" + c.target().sttText()
                        : (c.copyStt() != null ? "STT " + c.copyStt() : "");
                AuditLevel lvl = c.hidden() ? AuditLevel.PENDING : c.dupPending() ? AuditLevel.INFO : AuditLevel.OK;
                out.add(new Row<>(c, lvl, List.of(c.endpoint(), "#" + c.dupNo() + " / " + copies.size(),
                        Text.apiLabel(c.gid()), c.branch(), state, stt, c.created())));
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
