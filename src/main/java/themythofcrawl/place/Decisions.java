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

/** The 'Cần xử lý', 'Giữ chỗ', 'API trùng & ẩn' and 'Nhật ký' rows and clear Vietnamese advice for the Audit tab. */
public final class Decisions {
    public enum Kind {
        DUP("Trùng ở vị trí mới"),
        RETURN_MISSING("Quay về chỗ cũ (đã xóa nhánh)"),
        RETURN("Quay về chỗ cũ"),
        NEW("API mới cần xác nhận");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** Một dòng thông tin có cấu trúc hiển thị trong thẻ giải thích (không dùng văn bản thô). */
    public record FactRow(String label, String value, String badge, AuditLevel level, boolean mono) {
        public FactRow(String label, String value, String badge, AuditLevel level) {
            this(label, value, badge, level, false);
        }
    }

    /** Diễn giải tiếng Việt tự nhiên, rõ ràng cùng các dòng dữ kiện và gợi ý hành động. */
    public record SmartAdvice(
            String badgeLabel,
            String headline,
            String explanation,
            List<FactRow> facts,
            List<String> recommendations,
            String suggestedAction,
            boolean suspectedRefresh
    ) {
        public SmartAdvice(String headline, String explanation, List<String> recommendations,
                           String suggestedAction, boolean suspectedRefresh) {
            this("Thông tin", headline, explanation, List.of(), recommendations, suggestedAction, suspectedRefresh);
        }

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
                    sb.append("- ").append(rec).append("\n");
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

    /** Clear natural-Vietnamese explanation & structured facts for one API group (with Origin/Duplicate & Refresh awareness). */
    public static SmartAdvice advise(Engine engine, ApiGroup g) {
        if (g == null) {
            return new SmartAdvice("Chưa chọn", "Chưa chọn API nào",
                    "Chọn 1 dòng trong bảng để xem vị trí Bản Gốc, các lần xuất hiện lại và cách xử lý phù hợp.",
                    List.of(), List.of(), "", false);
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
        String apiLbl = Text.apiLabel(g.gid()) + " (" + Text.groupId(g.gid()) + ")";

        List<FactRow> facts = new ArrayList<>();
        List<String> recs = new ArrayList<>();

        if (suspected && copies.size() > 1) {
            String origWhere = origin != null
                    ? Text.apiLabel(origin.gid()) + " tại " + origin.branch()
                    : (rec != null && rec.originBranch != null ? rec.originBranch + " (đã xóa)" : "Chưa xác định");
            String badge = refreshTagged ? "TỰ TẢI LẠI" : "NGHI TỰ TẢI LẠI";
            String head = refreshTagged
                    ? "API tự tải lại dữ liệu — đã xuất hiện " + copies.size() + " lần"
                    : "Có thể là API tự tải lại màn hình — đã xuất hiện " + copies.size() + " lần";
            facts.add(new FactRow("Endpoint", g.endpoint(), g.method(), AuditLevel.INFO, true));
            facts.add(new FactRow("Bản Gốc (#1)", origWhere, "Vị trí chuẩn", AuditLevel.OK));
            facts.add(new FactRow("Bản đang chọn (#" + g.dupNo() + ")", apiLbl + " tại " + g.branch(),
                    copyStateShort(g), copyStateLevel(g)));
            if (!trigger.isEmpty()) {
                facts.add(new FactRow("Xuất hiện ngay sau", trigger, "Tự động tải lại", AuditLevel.INFO, true));
            }
            String exp = apiLbl + " (" + g.endpoint() + ") là API GET gọi lại ngay sau thao tác"
                    + (trigger.isEmpty() ? "" : " " + trigger) + ".\n"
                    + "Bản Gốc (#1) đang ở " + origWhere + ". Bản #" + g.dupNo() + " vừa gặp tại " + g.branch() + ".";
            recs.add("Nên làm: Bấm 'Chỉ giữ Bản Gốc (#1) & Ẩn bản lặp' và gắn nhãn Tự tải lại để danh sách sạch gọn.");
            recs.add("Nếu màn hình mới (" + g.branch() + ") mới là vị trí chính của API này, hãy bấm 'Đặt làm Bản Gốc (#1)'.");
            recs.add("Bạn có thể bật tự động bỏ qua các lần tải lại sau trong 'Quản lý Tag & Tự tải lại'.");
            return new SmartAdvice(badge, head, exp, facts, recs, "KEEP_ORIGIN_AND_TAG_REFRESH", true);
        }

        if (k == Kind.DUP || copies.size() > 1) {
            boolean isOrig = origin != null && origin.gid() == g.gid();
            String origWhere = origin != null
                    ? Text.apiLabel(origin.gid()) + " tại " + origin.branch()
                    : (rec != null && rec.originBranch != null ? rec.originBranch + " (đã xóa)" : "Chưa xác định");
            String badge = isOrig ? "BẢN GỐC #1" : "BẢN TRÙNG #" + g.dupNo() + "/" + copies.size();
            String head = isOrig
                    ? "Đây là Bản Gốc (#1) — có thêm " + Math.max(0, copies.size() - 1) + " lần xuất hiện lại ở nơi khác"
                    : "API này đã có Bản Gốc (#1), vừa xuất hiện lại lần #" + g.dupNo() + " ở vị trí mới";
            facts.add(new FactRow("Endpoint", g.endpoint(), copies.size() + " lần gặp", AuditLevel.INFO, true));
            facts.add(new FactRow("Bản Gốc (#1)", origWhere, origin != null ? copyStateShort(origin) : "Đã xóa",
                    origin != null ? copyStateLevel(origin) : AuditLevel.PENDING));
            if (!isOrig) {
                facts.add(new FactRow("Bản trùng (#" + g.dupNo() + ")", apiLbl + " tại " + g.branch(),
                        copyStateShort(g), copyStateLevel(g)));
            }
            StringBuilder exp = new StringBuilder();
            if (isOrig) {
                exp.append(apiLbl).append(" là Bản Gốc (#1) của ").append(g.endpoint())
                        .append(", đang đặt tại ").append(g.branch()).append(".");
            } else {
                exp.append(apiLbl).append(" là bản trùng #").append(g.dupNo()).append("/").append(copies.size())
                        .append(" vừa xuất hiện tại ").append(g.branch())
                        .append(". Bản Gốc (#1) ban đầu nằm tại ").append(origWhere).append(".");
            }
            recs.add("Nếu chỉ cần giữ 1 bản duy nhất trong báo cáo: bấm 'Chỉ giữ Bản Gốc (#1) & Ẩn bản lặp'.");
            if (!isOrig) {
                recs.add("Nếu muốn liệt kê API này ở cả 2 màn hình: bấm 'Giữ cả ở chỗ mới'.");
                recs.add("Nếu '" + g.branch() + "' mới là vị trí đúng hơn: bấm 'Đặt làm Bản Gốc (#1)'.");
            } else {
                recs.add("Mọi bản xuất hiện sau đều đối chiếu theo Bản Gốc (#1) này; bạn có thể sửa STT hoặc đổi vị trí bất cứ lúc nào.");
            }
            return new SmartAdvice(badge, head, exp.toString(), facts, recs,
                    isOrig ? "KEEP" : "KEEP_ORIGIN_HIDE_OTHERS", suspected);
        }

        if (k == Kind.RETURN_MISSING && h != null) {
            facts.add(new FactRow("Endpoint", g.endpoint(), g.method(), AuditLevel.INFO, true));
            facts.add(new FactRow("Chỗ cũ đã nhớ", h.old() + " (vừa tự tạo lại)", "Khôi phục", AuditLevel.WARN));
            facts.add(new FactRow("Đích lúc mới bắt", h.found(), "Vị trí mới", AuditLevel.INFO));
            String head = "API quay về chỗ cũ (nhánh cũ đã xóa nên hệ thống tự tạo lại)";
            String exp = apiLbl + " từng nằm tại " + h.old() + ". Khi bắt lại ở đích " + h.found()
                    + ", hệ thống tự tạo lại nhánh " + h.old() + " để trả API về đúng chỗ cũ.";
            recs.add("Bấm 'Đặt về chỗ cũ' để giữ nguyên sơ đồ cũ tại " + h.old() + ".");
            recs.add("Bấm 'Giữ ở chỗ mới' nếu API này giờ thuộc màn hình " + h.found() + ".");
            recs.add("Bấm 'Chọn nhánh khác...' để chuyển sang bất kỳ thư mục nào khác.");
            return new SmartAdvice("QUAY VỀ CHỖ CŨ", head, exp, facts, recs, "RETURN_DECIDE", suspected);
        }

        if (k == Kind.RETURN && h != null) {
            facts.add(new FactRow("Endpoint", g.endpoint(), g.method(), AuditLevel.INFO, true));
            facts.add(new FactRow("Chỗ cũ đã nhớ", h.old(), "Tự trả về", AuditLevel.OK));
            facts.add(new FactRow("Đích lúc mới bắt", h.found(), "Vị trí mới", AuditLevel.INFO));
            String head = "API từng bị xóa — hệ thống đã tự đưa về đúng chỗ cũ";
            String exp = apiLbl + " trước đây nằm ở " + h.old() + " rồi bị xóa, nên vừa được đưa về lại " + h.old()
                    + " (lần này bắt khi đích đang ở " + h.found() + ").";
            recs.add("Nên làm: Bấm 'Xác nhận chỗ cũ' nếu API vẫn thuộc chức năng " + h.old() + ".");
            recs.add("Bấm 'Chuyển sang chỗ mới' nếu API đã đổi sang chức năng " + h.found() + ".");
            return new SmartAdvice("QUAY VỀ CHỖ CŨ", head, exp, facts, recs, "KEEP", suspected);
        }

        facts.add(new FactRow("Endpoint", g.endpoint(), g.method(), AuditLevel.INFO, true));
        facts.add(new FactRow("Vị trí đang đặt", g.branch(), "Bản đầu tiên #1", AuditLevel.OK));
        String head = "API mới bắt lần đầu — sẽ là Bản Gốc (#1) cho endpoint này";
        String exp = apiLbl + " lần đầu được ghi nhận tại " + g.branch()
                + ". Vị trí này sẽ được nhớ làm Bản Gốc (#1) nếu API xuất hiện lại ở các màn hình sau.";
        recs.add("Bấm 'Xác nhận vị trí này' nếu nhánh " + g.branch() + " đã khớp với nút/màn hình bạn vừa bấm.");
        recs.add("Bấm 'Chọn nhánh khác...' nếu bạn quên đổi Đích trước khi thao tác trên trình duyệt.");
        if (suspected) {
            recs.add("Phát hiện API GET xuất hiện ngay sau hành động" + (trigger.isEmpty() ? "" : " (" + trigger + ")")
                    + " — cân nhắc gắn nhãn Tự tải lại.");
        }
        return new SmartAdvice("API MỚI", head, exp, facts, recs, "KEEP", suspected);
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
        String place = Text.nz(rec.originBranch != null ? rec.originBranch : rec.place);
        List<FactRow> facts = List.of(
                new FactRow("Endpoint", Text.nz(rec.method) + " " + Text.nz(rec.path), Text.nz(rec.host), AuditLevel.PENDING, true),
                new FactRow("Vị trí đã nhớ", place.isEmpty() ? "(chưa gán)" : place, "Đã xóa · Đang giữ chỗ", AuditLevel.PENDING),
                new FactRow("Thống kê", "Gặp " + rec.seen + " lần · Đã xóa " + rec.deleted + " lần · Bỏ qua trùng " + rec.skipped,
                        rec.lastFoundAt != null && !rec.lastFoundAt.isBlank() ? "Cuối: " + rec.lastFoundAt : "", AuditLevel.NONE)
        );
        String head = "API đã bị xóa nhưng hệ thống vẫn nhớ vị trí cũ";
        String exp = "Endpoint " + rec.method + " " + rec.path + " từng đặt tại " + place
                + " (đã xóa " + rec.deleted + " lần). Khi crawl gặp lại, extension sẽ tự trả về đúng nhánh này.";
        List<String> recs = List.of(
                "Bấm 'Sửa vị trí...' để đổi sẵn nhánh đích cho lần gặp lại tiếp theo.",
                "Bấm 'Quên vị trí này' nếu bạn muốn coi như API hoàn toàn mới ở lần crawl sau."
        );
        return new SmartAdvice("ĐANG GIỮ CHỖ", head, exp, facts, recs, "GHOST", rec.refreshCount > 0);
    }

    public static String copyStateShort(ApiGroup c) {
        if (c == null) {
            return "";
        }
        if (c.hidden()) {
            return "Đang ẩn";
        }
        if (c.dupPending()) {
            return "Chờ bạn chọn";
        }
        return "Đang hiện";
    }

    public static AuditLevel copyStateLevel(ApiGroup c) {
        if (c == null) {
            return AuditLevel.NONE;
        }
        if (c.hidden()) {
            return AuditLevel.PENDING;
        }
        if (c.dupPending()) {
            return AuditLevel.INFO;
        }
        return AuditLevel.OK;
    }

    public static String copyTagLabel(ApiGroup c) {
        if (c == null) {
            return "";
        }
        if (c.hasTag(ApiTagCatalog.TAG_REFRESH)) {
            return "Tự tải lại";
        }
        if (c.suspectedRefresh()) {
            return "Nghi tải lại";
        }
        return "";
    }

    /** Plain explanation of what happened to one waiting API and what each choice does. */
    public static String text(ApiGroup g, Collection<ApiGroup> copies) {
        Kind k = kind(g);
        PlaceHint h = g.placeHint();
        String api = Text.apiLabel(g.gid()) + " " + g.endpoint();
        switch (k) {
            case RETURN_MISSING:
                return api + " từng nằm ở '" + h.old() + "', nhưng nhánh đó đã bị xóa nên hệ thống tự tạo lại '" + h.old()
                        + "' để trả API về chỗ cũ.\nLần này API được bắt khi Đích là '" + h.found() + "'.\n\nLựa chọn:\n"
                        + "- Đặt về chỗ cũ: giữ vị trí '" + h.old() + "'.\n- Giữ ở chỗ mới: chuyển vào '" + h.found()
                        + "'.\n- Chọn nhánh khác: đưa vào nhánh bất kỳ.";
            case RETURN:
                return api + " từng bị xóa khi đang ở '" + h.old() + "', nên hệ thống vừa tự trả về chỗ đó.\n"
                        + "Lần này API được bắt khi Đích là '" + h.found() + "'.\n\n"
                        + "- Xác nhận chỗ cũ: để ở '" + h.old() + "'.\n- Chuyển sang chỗ mới: chuyển vào '" + h.found() + "'.";
            case DUP: {
                List<String> where = new ArrayList<>();
                copies.stream().filter(c -> c != g).sorted(Comparator.comparingInt(ApiGroup::dupNo)).forEach(c ->
                        where.add("   " + (c.dupNo() == 1 ? "#1 Bản Gốc" : "#" + c.dupNo() + " Bản trùng")
                                + " · " + Text.apiLabel(c.gid()) + " · " + c.branch()
                                + (c.hidden() ? " (đang ẩn)" : "")));
                return api + " là bản trùng #" + g.dupNo() + ": endpoint này đã xuất hiện ở\n"
                        + (where.isEmpty() ? "   (bản trước đã bị xóa)" : String.join("\n", where))
                        + "\nLần này gặp lại ở vị trí mới '" + g.branch() + "'.\n\n"
                        + "- Giữ ở chỗ mới: hiển thị thêm bản #" + g.dupNo() + " tại '" + g.branch() + "'.\n"
                        + "- Không giữ (Ẩn): chỉ giữ Bản Gốc, ẩn bản trùng này (vẫn xem lại được trong tab API trùng & ẩn).";
            }
            default:
                return api + " mới được bắt vào đích '" + g.branch() + "'.\n\n"
                        + "- Xác nhận vị trí này: giữ tại '" + g.branch() + "'.\n- Chọn nhánh khác: chuyển sang vị trí khác.";
        }
    }

    // ------------------------------------------------------------------ rows

    /** A row of one of the Audit tables: payload, colour, cells. */
    public record Row<T>(T payload, AuditLevel level, List<Object> cells) {
    }

    public static final List<String> DECIDE_COLS = List.of("Loại xử lý", "API", "Endpoint", "Đang đặt tại", "Đích lúc bắt");
    public static final List<String> DUP_COLS = List.of("Endpoint", "Bản", "Vai trò", "Mã API", "Vị trí trong cây", "Trạng thái", "Nhãn", "STT / Excel", "Bắt lúc");
    public static final List<String> PLACE_COLS = List.of("Method", "Path", "Host", "Vị trí giữ chỗ", "API hiện tại",
            "Trạng thái", "Đã xóa (lần)", "Lần gặp", "Bỏ qua do trùng", "Gặp lần cuối", "Gặp lần cuối tại (đích lúc đó)",
            "Đổi vị trí (lần)");
    public static final List<String> PLACE_UI_COLS = List.of("Method", "Endpoint", "Vị trí Gốc (#1)", "API hiện tại",
            "Trạng thái", "Thống kê", "Gặp lần cuối");
    public static final List<String> LOG_COLS = List.of("#", "Thời gian", "Hành động", "API", "Method", "Path", "Từ",
            "Sang", "Ghi chú");

    public static List<Row<ApiGroup>> decideRows(Collection<ApiGroup> pending) {
        List<Row<ApiGroup>> out = new ArrayList<>();
        pending.stream().sorted(Comparator.comparingInt(ApiGroup::gid)).forEach(g -> {
            Kind k = kind(g);
            String found = g.placeHint() != null ? g.placeHint().found() : (g.dupPending() ? g.branch() : "");
            String kindLbl = k.label + (g.suspectedRefresh() ? " · Nghi tải lại" : "");
            out.add(new Row<>(g, k == Kind.NEW ? AuditLevel.PENDING : AuditLevel.INFO,
                    List.of(kindLbl, Text.apiLabel(g.gid()), g.endpoint(), g.branch(), found)));
        });
        return out;
    }

    /** Every copy of endpoints that have several, or a hidden one — separated Status and Tag columns to prevent overlap. */
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
                String role = c.dupNo() == 1 ? "Bản Gốc (#1)" : "Bản trùng";
                String state = copyStateShort(c);
                String tag = copyTagLabel(c);
                String stt = c.target() != null ? "Excel #" + c.target().sttText()
                        : (c.copyStt() != null ? "STT " + c.copyStt() : "");
                AuditLevel lvl = copyStateLevel(c);
                out.add(new Row<>(c, lvl, List.of(c.endpoint(), "#" + c.dupNo() + " / " + copies.size(), role,
                        Text.apiLabel(c.gid()), c.branch(), state, tag, stt, c.created())));
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

    public static String shortPlaceState(PlacementBook.State s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case LIVE -> "Đang có API";
            case PENDING -> "Chờ xác nhận";
            case DELETED -> "Đã xóa · Giữ chỗ";
            case NONE -> "Chưa lưu";
        };
    }

    /** Placement book rows (full 12 columns preserved for exports & tests). */
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

    /** Clean 7-column UI rows for the 'Giữ chỗ' table so zero columns are crushed or truncated. */
    public static List<Row<PlacementBook.Record>> placeUiRows(PlacementBook book, int view, String filter) {
        String f = Text.nz(filter).trim().toLowerCase(Locale.ROOT);
        List<Row<PlacementBook.Record>> out = new ArrayList<>();
        for (PlacementBook.Record r : book.records()) {
            PlacementBook.State st = r.state();
            if ((view == 1 && st != PlacementBook.State.LIVE)
                    || (view == 2 && st != PlacementBook.State.DELETED && st != PlacementBook.State.NONE)
                    || (view == 3 && st != PlacementBook.State.PENDING)) {
                continue;
            }
            String stats = "Gặp " + r.seen + " · Xóa " + r.deleted + (r.skipped > 0 ? " · Bỏ qua " + r.skipped : "");
            String place = Text.nz(r.place);
            List<Object> cells = List.of(
                    Text.nz(r.method),
                    Text.nz(r.path),
                    place.isEmpty() ? "(chưa gán)" : place,
                    r.gid != null ? Text.apiLabel(r.gid) : "—",
                    shortPlaceState(st),
                    stats,
                    Text.nz(r.lastSeen)
            );
            String searchable = (Text.nz(r.method) + " " + Text.nz(r.path) + " " + Text.nz(r.host)
                    + " " + place + " " + Text.nz(r.lastFoundAt)).toLowerCase(Locale.ROOT);
            if (!f.isEmpty() && !searchable.contains(f)) {
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
