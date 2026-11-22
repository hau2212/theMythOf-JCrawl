package themythofcrawl.project;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import javax.imageio.ImageIO;

import themythofcrawl.core.Text;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.excel.Xlsx.CellTone;
import themythofcrawl.excel.Xlsx.Formula;
import themythofcrawl.excel.Xlsx.StyledCell;
import themythofcrawl.model.QaItem;

/**
 * V_11.0: the project workbook. The same tables become the .xlsx files (personal copy, reports, client rounds) and the
 * team Google Sheet. The first column of every table sheet is the hidden row code ("Mã") so a sheet that comes back
 * (client answers, sorted / filtered) is matched row by row. "Tổng quan" uses COUNTIFS formulas on "Tổng hợp", so
 * numbers follow any hand edit in Excel / Google Sheets (cached values are written too).
 */
public final class ProjectWorkbook {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Hidden helper columns of Tổng hợp (after Mã): 1 = in the project list, number of accounts that reached it. */
    static final String IN_LIST = "Trong danh sách";
    static final String REACHED_N = "Số TK gọi được (số)";

    /**
     * @param imageLink when set (team sheet), image cells become links to the screenshot instead of embedded pictures
     */
    public record Options(List<String> letters, String profile, boolean images, int maxImagePx, boolean forClient,
                          List<Map<String, Object>> log, BiFunction<ProjectData.QaRef, String, byte[]> remoteImage, String title,
                          BiFunction<ProjectData.QaRef, String, String> imageLink) {
        public Options(List<String> letters, String profile, boolean images, int maxImagePx, boolean forClient,
                       List<Map<String, Object>> log, BiFunction<ProjectData.QaRef, String, byte[]> remoteImage, String title) {
            this(letters, profile, images, maxImagePx, forClient, log, remoteImage, title, null);
        }

        public static Options internal(ProjectData d) {
            return new Options(d.letters(), "Nội bộ", true, 320, false, List.of(), null, d.config().name);
        }

        public Options withLog(List<Map<String, Object>> l) {
            return new Options(letters, profile, images, maxImagePx, forClient, l, remoteImage, title, imageLink);
        }

        public Options withRemote(BiFunction<ProjectData.QaRef, String, byte[]> r) {
            return new Options(letters, profile, images, maxImagePx, forClient, log, r, title, imageLink);
        }

        public Options forSheet(BiFunction<ProjectData.QaRef, String, String> link) {
            return new Options(letters, profile, false, maxImagePx, forClient, log, remoteImage, title, link);
        }
    }

    /** One sheet, ready for Excel or the team sheet. {@code editable} = column index -> what an edit means. */
    public record Table(String name, SheetDef.Type type, List<String> header, List<List<Object>> rows, int[] widths,
                        Set<Integer> hidden, List<Xlsx.Picture> pictures, Xlsx.Chart chart, boolean headerRow,
                        Map<Integer, Integer> heights, Map<Integer, String> editable) {
        public Xlsx.OutSheet out() {
            return new Xlsx.OutSheet(name, header, rows, widths, new Xlsx.Layout(headerRow, hidden, heights, chart));
        }
    }

    private ProjectWorkbook() {
    }

    public static byte[] xlsx(List<Table> tables) throws IOException {
        List<Xlsx.OutSheet> out = new ArrayList<>();
        List<List<Xlsx.Picture>> pics = new ArrayList<>();
        for (Table t : tables) {
            out.add(t.out());
            pics.add(t.pictures());
        }
        return Xlsx.write(out, pics);
    }

    public static byte[] xlsx(ProjectData d, List<SheetDef> defs, Options o) throws IOException {
        return xlsx(tables(d, defs, o));
    }

    // ------------------------------------------------------------------ tables
    public static List<Table> tables(ProjectData d, List<SheetDef> defs, Options o) {
        List<String> letters = o.letters() == null || o.letters().isEmpty() ? d.letters()
                : d.letters().stream().filter(l -> o.letters().contains(l)).toList();
        ProjectConfig.Profile prof = d.config().profile(o.profile());
        List<Table> out = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        Table summary = null;
        List<SheetDef> enabled = defs.stream().filter(SheetDef::enabled).toList();
        // Tổng hợp first (Tổng quan refers to it), then put everything back in the user's order
        Map<SheetDef, List<Table>> built = new LinkedHashMap<>();
        for (SheetDef def : enabled) {
            if (def.type() == SheetDef.Type.SUMMARY && summary == null) {
                summary = summary(d, letters, profileFor(d, def, prof), unique(names, def.name()));
                built.put(def, List.of(summary));
            }
        }
        for (SheetDef def : enabled) {
            if (built.containsKey(def)) {
                continue;
            }
            if (o.forClient() && def.type() == SheetDef.Type.LOG) {
                continue;
            }
            ProjectConfig.Profile p = profileFor(d, def, prof);
            List<Table> ts = new ArrayList<>();
            switch (def.type()) {
                case OVERVIEW -> ts.add(overview(d, letters, summary, unique(names, def.name()), o));
                case ACCOUNT -> {
                    for (String l : letters) {
                        if (def.account().isEmpty() || def.account().equals("*") || def.account().equalsIgnoreCase(l)) {
                            String nm = def.account().equals("*") || def.account().isEmpty() ? d.config().accountDisplay(l) : def.name();
                            ts.add(rowsTable(d, List.of(l), p, unique(names, nm), SheetDef.Type.ACCOUNT,
                                    d.rows().stream().filter(r -> !r.excluded && (r.source.inList() || hasData(r, l))).toList()));
                        }
                    }
                }
                case DIFF -> ts.add(rowsTable(d, letters, p, unique(names, def.name()), def.type(), d.filter("diff")));
                case ONLY -> ts.add(rowsTable(d, letters, withSource(p), unique(names, def.name()), def.type(), d.filter("only")));
                case FILTER -> ts.add(rowsTable(d, letters, p, unique(names, def.name()), def.type(), d.filter(def.filter())));
                case BAC -> ts.add(bac(d, letters, unique(names, def.name())));
                case QA -> ts.add(qa(d, letters, unique(names, def.name()), o, QaItem.Kind.QA));
                case INFO -> ts.add(qa(d, letters, unique(names, def.name()), o, QaItem.Kind.INFO));
                case QA_DETAIL -> ts.add(qaDetail(d, letters, unique(names, def.name()), o));
                case LOG -> ts.add(log(unique(names, def.name()), o.log()));
                case BLANK -> ts.add(new Table(unique(names, def.name()), def.type(), List.of(), List.of(), new int[] {20}, Set.of(),
                        List.of(), null, false, Map.of(), Map.of()));
                default -> {
                }
            }
            built.put(def, ts);
        }
        for (SheetDef def : enabled) {
            out.addAll(built.getOrDefault(def, List.of()));
        }
        return out;
    }

    private static boolean hasData(ProjectData.Row r, String l) {
        ProjectData.Cell c = r.cells.get(l);
        return c != null && (c.apis > 0 || c.manual);
    }

    private static ProjectConfig.Profile profileFor(ProjectData d, SheetDef def, ProjectConfig.Profile fallback) {
        return def.profile().isEmpty() ? fallback : d.config().profile(def.profile());
    }

    private static ProjectConfig.Profile withSource(ProjectConfig.Profile p) {
        List<ProjectConfig.Col> cols = new ArrayList<>(p.cols());
        boolean has = false;
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).id().equals(Columns.SOURCE)) {
                cols.set(i, new ProjectConfig.Col(Columns.SOURCE, cols.get(i).header(), true));
                has = true;
            }
        }
        if (!has) {
            cols.add(new ProjectConfig.Col(Columns.SOURCE, "", true));
        }
        return new ProjectConfig.Profile(p.name(), cols);
    }

    static String unique(Set<String> used, String name) {
        String base = Text.nz(name).replaceAll("[\\\\/?*\\[\\]:]", "_").trim();
        if (base.isEmpty()) {
            base = "Sheet";
        }
        if (base.length() > 31) {
            base = base.substring(0, 31);
        }
        String n = base;
        int k = 2;
        while (used.contains(n.toLowerCase(Locale.ROOT))) {
            String suffix = " (" + k++ + ")";
            n = (base.length() + suffix.length() > 31 ? base.substring(0, 31 - suffix.length()) : base) + suffix;
        }
        used.add(n.toLowerCase(Locale.ROOT));
        return n;
    }

    // ------------------------------------------------------------------ row tables
    /** Expanded visible columns of a profile: ACCOUNTS -> one per letter. */
    public static List<String> columns(ProjectConfig.Profile p, List<String> letters) {
        List<String> out = new ArrayList<>();
        for (ProjectConfig.Col c : p.cols()) {
            if (!c.visible()) {
                continue;
            }
            if (c.id().equals(Columns.ACCOUNTS)) {
                for (String l : letters) {
                    out.add(Columns.ACC_PREFIX + l);
                }
            } else if (c.id().startsWith(Columns.ACC_PREFIX)) {
                if (letters.contains(c.id().substring(Columns.ACC_PREFIX.length()))) {
                    out.add(c.id());
                }
            } else {
                out.add(c.id());
            }
        }
        return out;
    }

    public static String header(ProjectData d, ProjectConfig.Profile p, String id) {
        if (id.startsWith(Columns.ACC_PREFIX)) {
            return d.config().accountDisplay(id.substring(Columns.ACC_PREFIX.length()));
        }
        for (ProjectConfig.Col c : p.cols()) {
            if (c.id().equals(id)) {
                return Columns.header(c);
            }
        }
        return Columns.defaultHeader(id);
    }

    public static CellTone tone(ProjectData.Cell c) {
        if (c.conflict && !c.manual) {
            return CellTone.CONFLICT_ORANGE;
        }
        return switch (c.status) {
            case DONE -> CellTone.DONE_GREEN;
            case DENIED -> CellTone.DENIED_SLATE;
            case ERROR -> CellTone.ERROR_RED;
            case MISSING -> CellTone.MISSING_RED;
            case DOING -> CellTone.DOING_BLUE;
            case OOS -> CellTone.OOS_GRAY;
        };
    }

    public static Object value(ProjectData d, ProjectData.Row r, String id, List<String> letters) {
        if (id.startsWith(Columns.ACC_PREFIX)) {
            String l = id.substring(Columns.ACC_PREFIX.length());
            ProjectData.Cell c = r.cell(l);
            String text = c.text(l) + (c.conflict && !c.manual ? " · Xung đột" : "") + (c.doingBy.isEmpty() ? "" : " · " + c.doingBy);
            return StyledCell.of(text, tone(c));
        }
        return switch (id) {
            case Columns.STT -> r.stt;
            case Columns.PATH -> r.path;
            case Columns.METHOD -> r.method;
            case Columns.BRANCH -> r.branch;
            case Columns.HOST -> r.host;
            case Columns.REACHED -> {
                int n = 0;
                for (String l : letters) {
                    n += r.cell(l).status == AccStatus.DONE ? 1 : 0;
                }
                yield n + "/" + letters.size();
            }
            case Columns.BAC -> r.bac.isEmpty() ? "" : StyledCell.of(r.bac, CellTone.CONFLICT_ORANGE);
            case Columns.QA -> qaSummary(r, QaItem.Kind.QA, letters);
            case Columns.INFO -> qaSummary(r, QaItem.Kind.INFO, letters);
            case Columns.NOTE -> r.note;
            case Columns.TESTER -> r.tester();
            case Columns.TESTED_AT -> ProjectStore.time(r.testedAt());
            case Columns.SOURCE -> r.sourceLabel();
            default -> "";
        };
    }

    private static Object qaSummary(ProjectData.Row r, QaItem.Kind kind, List<String> letters) {
        List<String> parts = new ArrayList<>();
        boolean open = false;
        for (ProjectData.QaRef q : r.qa) {
            if (q.item().kind() == kind && letters.contains(q.letter())) {
                parts.add(q.item().code() + " (" + q.letter() + ") " + q.item().status().label);
                open |= q.item().status().waiting();
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return StyledCell.of(String.join("; ", parts), kind == QaItem.Kind.INFO ? CellTone.INFO_YELLOW
                : open ? CellTone.QA_PURPLE : CellTone.DONE_GREEN);
    }

    private static int width(String id) {
        if (id.startsWith(Columns.ACC_PREFIX)) {
            return 22;
        }
        return switch (id) {
            case Columns.STT -> 7;
            case Columns.PATH -> 46;
            case Columns.METHOD -> 9;
            case Columns.BRANCH -> 30;
            case Columns.HOST -> 22;
            case Columns.REACHED -> 12;
            case Columns.BAC -> 34;
            case Columns.QA, Columns.INFO -> 30;
            case Columns.NOTE -> 36;
            case Columns.TESTER -> 14;
            case Columns.TESTED_AT -> 17;
            case Columns.SOURCE -> 20;
            default -> 14;
        };
    }

    /** Tổng hợp: Mã + 2 hidden helpers + the profile's columns. */
    static Table summary(ProjectData d, List<String> letters, ProjectConfig.Profile p, String name) {
        Table t = rowsTable(d, letters, p, name, SheetDef.Type.SUMMARY, d.visibleRows(), true);
        return t;
    }

    static Table rowsTable(ProjectData d, List<String> letters, ProjectConfig.Profile p, String name, SheetDef.Type type,
                           List<ProjectData.Row> rows) {
        return rowsTable(d, letters, p, name, type, rows, false);
    }

    private static Table rowsTable(ProjectData d, List<String> letters, ProjectConfig.Profile p, String name, SheetDef.Type type,
                                   List<ProjectData.Row> rows, boolean helpers) {
        List<String> cols = columns(p, letters);
        List<String> header = new ArrayList<>();
        header.add(Columns.defaultHeader(Columns.KEY));
        Set<Integer> hidden = new LinkedHashSet<>();
        hidden.add(0);
        if (helpers) {
            header.add(IN_LIST);
            header.add(REACHED_N);
            hidden.add(1);
            hidden.add(2);
        }
        int first = header.size();
        for (String c : cols) {
            header.add(header(d, p, c));
        }
        Map<Integer, String> editable = new LinkedHashMap<>();
        for (int i = 0; i < cols.size(); i++) {
            String c = cols.get(i);
            if (c.startsWith(Columns.ACC_PREFIX)) {
                editable.put(first + i, "mark:" + c.substring(Columns.ACC_PREFIX.length()));
            } else if (c.equals(Columns.NOTE)) {
                editable.put(first + i, "note");
            }
        }
        List<List<Object>> out = new ArrayList<>();
        for (ProjectData.Row r : rows) {
            List<Object> row = new ArrayList<>();
            row.add(r.id);
            if (helpers) {
                row.add(r.source.inList() && !r.excluded ? 1 : 0);
                int n = 0;
                for (String l : letters) {
                    n += r.cell(l).status == AccStatus.DONE ? 1 : 0;
                }
                row.add(n);
            }
            for (String c : cols) {
                row.add(value(d, r, c, letters));
            }
            out.add(row);
        }
        int[] w = new int[header.size()];
        w[0] = 6;
        if (helpers) {
            w[1] = 6;
            w[2] = 6;
        }
        for (int i = 0; i < cols.size(); i++) {
            w[first + i] = width(cols.get(i));
        }
        return new Table(name, type, header, out, w, hidden, List.of(), null, true, Map.of(), editable);
    }

    // ------------------------------------------------------------------ overview
    private static String q(String sheet) {
        return "'" + sheet.replace("'", "''") + "'";
    }

    private static Table overview(ProjectData d, List<String> letters, Table summary, String name, Options o) {
        List<List<Object>> rows = new ArrayList<>();
        Map<Integer, Integer> heights = new LinkedHashMap<>();
        rows.add(List.of(StyledCell.of("Báo cáo dự án " + Text.nz(o.title()), CellTone.TITLE)));
        heights.put(0, 28);
        rows.add(List.of(StyledCell.of("Xuất lúc " + TS.format(Instant.now()) + " · " + letters.size() + " tài khoản · danh sách: "
                + d.config().baseline.label + " · số liệu dưới đây là công thức, tự tính lại khi sửa sheet Tổng hợp", CellTone.HINT)));
        rows.add(List.of());
        List<Object> head = new ArrayList<>();
        head.add(StyledCell.of("Chỉ số", CellTone.HEADER));
        for (String l : letters) {
            head.add(StyledCell.of(d.config().accountDisplay(l), CellTone.HEADER));
        }
        rows.add(head);
        int headRow = rows.size();   // 1-based excel row of the account names
        // column letters on Tổng hợp
        String sum = summary == null ? null : q(summary.name());
        int last = summary == null ? 0 : summary.rows().size() + 1;
        Map<String, String> colOf = new LinkedHashMap<>();
        if (summary != null) {
            for (int i = 0; i < summary.header().size(); i++) {
                for (String l : letters) {
                    if (summary.header().get(i).equals(d.config().accountDisplay(l))) {
                        colOf.put(l, col(i));
                    }
                }
            }
        }
        String inList = sum == null ? null : sum + "!$B$2:$B$" + last;
        String reachedN = sum == null ? null : sum + "!$C$2:$C$" + last;
        record Metric(String label, AccStatus st, String kind) {
        }
        List<Metric> metrics = List.of(new Metric("API trong danh sách", null, "list"), new Metric("Done (gọi được)", AccStatus.DONE, "st"),
                new Metric("Denied (bị chặn)", AccStatus.DENIED, "st"), new Metric("Error (lỗi khác)", AccStatus.ERROR, "st"),
                new Metric("Missing (chưa gọi)", AccStatus.MISSING, "st"), new Metric("Doing (đang test)", AccStatus.DOING, "st"),
                new Metric("OOS (ngoài phạm vi)", AccStatus.OOS, "st"), new Metric("% phủ (Done / danh sách)", null, "pct"),
                new Metric("Chỉ tài khoản này gọi được", null, "only"), new Metric("Info", null, "info"),
                new Metric("Q&A chờ trả lời", null, "qaopen"), new Metric("Q&A đã trả lời", null, "qaans"));
        int doneRow = 0;
        int listRow = 0;
        int firstMetricRow = rows.size() + 1;
        for (Metric m : metrics) {
            List<Object> row = new ArrayList<>();
            row.add(StyledCell.of(m.label(), CellTone.BOLD));
            int excelRow = rows.size() + 1;
            if ("list".equals(m.kind())) {
                listRow = excelRow;
            }
            if (m.st() == AccStatus.DONE) {
                doneRow = excelRow;
            }
            for (int k = 0; k < letters.size(); k++) {
                String l = letters.get(k);
                ProjectData.Stats st = d.stats(l);
                String c = colOf.get(l);
                String rng = c == null ? null : sum + "!$" + c + "$2:$" + c + "$" + last;
                Object v;
                switch (m.kind()) {
                    case "list" -> v = sum == null ? (Object) st.list() : Formula.of("COUNTIF(" + inList + ",1)", st.list());
                    case "st" -> {
                        int n = switch (m.st()) {
                            case DONE -> st.done();
                            case DENIED -> st.denied();
                            case ERROR -> st.error();
                            case MISSING -> st.missing();
                            case DOING -> st.doing();
                            default -> st.oos();
                        };
                        v = rng == null ? (Object) n : Formula.of("COUNTIFS(" + rng + ",\"" + m.st().word + "*\"," + inList + ",1)", n);
                    }
                    case "pct" -> {
                        String cl = col(k + 1);
                        v = new Formula("IF(" + cl + listRow + "=0,0," + cl + doneRow + "/" + cl + listRow + ")", st.coverage(),
                                CellTone.PERCENT);
                    }
                    case "only" -> v = rng == null ? (Object) st.onlyThis()
                            : Formula.of("COUNTIFS(" + rng + ",\"Done*\"," + reachedN + ",1," + inList + ",1)", st.onlyThis());
                    case "info" -> v = st.info();
                    case "qaopen" -> v = st.qaOpen();
                    default -> v = st.qaAnswered();
                }
                row.add(v);
            }
            rows.add(row);
        }
        int lastMetricRow = rows.size();
        rows.add(List.of());
        rows.add(List.of(StyledCell.of("So sánh giữa các tài khoản (chỉ API trong danh sách)", CellTone.HEADER)));
        List<String> listOnly = letters;
        if (sum != null && !letters.isEmpty()) {
            rows.add(List.of(StyledCell.of("Ít nhất 1 tài khoản gọi được (hợp)", CellTone.BOLD),
                    Formula.of("COUNTIFS(" + reachedN + ",\">0\"," + inList + ",1)", d.countAny())));
            rows.add(List.of(StyledCell.of("Mọi tài khoản đều gọi được (giao)", CellTone.BOLD),
                    Formula.of("COUNTIFS(" + reachedN + "," + letters.size() + "," + inList + ",1)", d.countDone(listOnly, List.of()))));
            String a = letters.get(0);
            for (String x : letters.subList(1, letters.size())) {
                String ra = sum + "!$" + colOf.get(a) + "$2:$" + colOf.get(a) + "$" + last;
                String rx = sum + "!$" + colOf.get(x) + "$2:$" + colOf.get(x) + "$" + last;
                rows.add(List.of(StyledCell.of(a + " và " + x + " đều gọi được", CellTone.BOLD),
                        Formula.of("COUNTIFS(" + ra + ",\"Done*\"," + rx + ",\"Done*\"," + inList + ",1)", d.countDone(List.of(a, x), List.of()))));
                rows.add(List.of(StyledCell.of("Chỉ " + a + " (" + x + " không)", CellTone.BOLD),
                        Formula.of("COUNTIFS(" + ra + ",\"Done*\"," + rx + ",\"<>Done*\"," + inList + ",1)", d.countDone(List.of(a), List.of(x)))));
                rows.add(List.of(StyledCell.of("Chỉ " + x + " (" + a + " không)", CellTone.BOLD),
                        Formula.of("COUNTIFS(" + rx + ",\"Done*\"," + ra + ",\"<>Done*\"," + inList + ",1)", d.countDone(List.of(x), List.of(a)))));
                int warn = 0;
                for (ProjectData.Row r : d.listRows()) {
                    warn += r.cell(x).status == AccStatus.DONE && r.cell(a).status == AccStatus.DENIED ? 1 : 0;
                }
                rows.add(List.of(StyledCell.of(x + " gọi được nhưng " + a + " bị chặn (cần xem)", CellTone.CONFLICT_ORANGE),
                        Formula.of("COUNTIFS(" + rx + ",\"Done*\"," + ra + ",\"Denied*\"," + inList + ",1)", warn)));
            }
        }
        rows.add(List.of());
        rows.add(List.of(StyledCell.of("Q&A theo tài khoản (API có Q&A)", CellTone.HEADER)));
        Map<String, Set<String>> qaRows = new LinkedHashMap<>();
        for (String l : letters) {
            qaRows.put(l, new LinkedHashSet<>());
        }
        for (ProjectData.Row r : d.visibleRows()) {
            for (ProjectData.QaRef qr : r.qa) {
                if (qr.item().kind() == QaItem.Kind.QA && qaRows.containsKey(qr.letter())) {
                    qaRows.get(qr.letter()).add(r.id);
                }
            }
        }
        for (String l : letters) {
            rows.add(List.of(StyledCell.of("API có Q&A ở " + l, CellTone.BOLD), qaRows.get(l).size()));
        }
        if (letters.size() > 1) {
            Set<String> both = new LinkedHashSet<>(qaRows.get(letters.get(0)));
            for (String l : letters.subList(1, letters.size())) {
                both.retainAll(qaRows.get(l));
            }
            rows.add(List.of(StyledCell.of("API có Q&A ở mọi tài khoản", CellTone.BOLD), both.size()));
        }
        if (!d.conflicts().isEmpty()) {
            rows.add(List.of());
            rows.add(List.of(StyledCell.of("Xung đột chờ PIC chọn: " + d.conflicts().size(), CellTone.CONFLICT_ORANGE)));
        }
        int[] w = new int[1 + Math.max(1, letters.size())];
        w[0] = 46;
        for (int i = 1; i < w.length; i++) {
            w[i] = 18;
        }
        Xlsx.Chart chart = null;
        if (!letters.isEmpty()) {
            String me = q(name);
            String cats = me + "!$B$" + headRow + ":$" + col(letters.size()) + "$" + headRow;
            List<String[]> series = new ArrayList<>();
            for (int r = firstMetricRow; r <= lastMetricRow; r++) {
                String lbl = String.valueOf(cell(rows.get(r - 1), 0));
                if (lbl.startsWith("Done") || lbl.startsWith("Denied") || lbl.startsWith("Error") || lbl.startsWith("Missing")) {
                    series.add(new String[] {me + "!$A$" + r, me + "!$B$" + r + ":$" + col(letters.size()) + "$" + r});
                }
            }
            int anchorCol = letters.size() + 2;
            chart = new Xlsx.Chart("Kết quả theo tài khoản", cats, series, anchorCol, 3, anchorCol + 8, 22);
        }
        return new Table(name, SheetDef.Type.OVERVIEW, List.of(), rows, w, Set.of(), List.of(), chart, false, heights, Map.of());
    }

    private static Object cell(List<Object> row, int i) {
        Object v = row.size() > i ? row.get(i) : "";
        return v instanceof StyledCell sc ? sc.value() : v;
    }

    static String col(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ BAC / log
    private static Table bac(ProjectData d, List<String> letters, String name) {
        List<String> header = new ArrayList<>(List.of("Mã", "STT", "Method", "Path", "Nhánh"));
        for (String l : letters) {
            header.add(d.config().accountDisplay(l));
        }
        header.add("Kết luận");
        List<List<Object>> rows = new ArrayList<>();
        for (ProjectData.Row r : d.filter("bac")) {
            List<Object> row = new ArrayList<>(List.of(r.id, r.stt, r.method, r.path, r.branch));
            for (String l : letters) {
                ProjectData.Cell c = r.cell(l);
                row.add(StyledCell.of(c.text(l), tone(c)));
            }
            row.add(StyledCell.of(r.bac, CellTone.CONFLICT_ORANGE));
            rows.add(row);
        }
        int[] w = new int[header.size()];
        int[] base = {6, 7, 9, 46, 30};
        for (int i = 0; i < w.length; i++) {
            w[i] = i < base.length ? base[i] : 22;
        }
        w[w.length - 1] = 40;
        return new Table(name, SheetDef.Type.BAC, header, rows, w, Set.of(0), List.of(), null, true, Map.of(), Map.of());
    }

    private static Table log(String name, List<Map<String, Object>> log) {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = log.size() - 1; i >= 0; i--) {
            Map<String, Object> m = log.get(i);
            rows.add(List.of(String.valueOf(m.getOrDefault("t", "")), String.valueOf(m.getOrDefault("by", "")),
                    String.valueOf(m.getOrDefault("k", "")), String.valueOf(m.getOrDefault("id", "")),
                    String.valueOf(m.getOrDefault("d", ""))));
        }
        return new Table(name, SheetDef.Type.LOG, List.of("Lúc", "Ai", "Loại", "Mục", "Chi tiết"), rows, new int[] {19, 16, 10, 36, 80},
                Set.of(), List.of(), null, true, Map.of(), Map.of());
    }

    // ------------------------------------------------------------------ Q&A
    private static List<ProjectData.QaRef> qaOf(ProjectData d, List<String> letters, QaItem.Kind kind) {
        List<ProjectData.QaRef> out = new ArrayList<>();
        for (ProjectData.QaRef q : d.allQa()) {
            if ((kind == null || q.item().kind() == kind) && letters.contains(q.letter())) {
                ProjectData.Row r = d.row(q.rowId());
                if (r == null || !r.excluded) {
                    out.add(q);
                }
            }
        }
        return out;
    }

    static byte[] imageBytes(ProjectData.QaRef q, String name, Options o) {
        byte[] b = q.images() != null ? q.images().image(name) : null;
        if (b == null && o.remoteImage() != null) {
            b = o.remoteImage().apply(q, name);
        }
        return b;
    }

    /** Scales an image to fit maxW x maxH (display size), keeping the bytes unless they are huge (then re-encoded). */
    static Xlsx.Picture picture(byte[] img, int row, int col, int maxW, int maxH, int offY) {
        if (img == null || img.length == 0) {
            return null;
        }
        try {
            java.awt.image.BufferedImage bi = ImageIO.read(new ByteArrayInputStream(img));
            if (bi == null) {
                return null;
            }
            double s = Math.min(1.0, Math.min((double) maxW / bi.getWidth(), (double) maxH / bi.getHeight()));
            int w = Math.max(16, (int) Math.round(bi.getWidth() * s));
            int h = Math.max(16, (int) Math.round(bi.getHeight() * s));
            byte[] data = img;
            String ext = isJpeg(img) ? "jpeg" : "png";
            if (bi.getWidth() > 1600 || bi.getHeight() > 1600 || img.length > 1_500_000) {
                double k = Math.min(1.0, 1600.0 / Math.max(bi.getWidth(), bi.getHeight()));
                java.awt.image.BufferedImage sc = new java.awt.image.BufferedImage(Math.max(1, (int) (bi.getWidth() * k)),
                        Math.max(1, (int) (bi.getHeight() * k)), java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = sc.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(bi, 0, 0, sc.getWidth(), sc.getHeight(), null);
                g.dispose();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                ImageIO.write(sc, "jpeg", bo);
                data = bo.toByteArray();
                ext = "jpeg";
            }
            return new Xlsx.Picture(row, col, data, ext, w, h, offY);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isJpeg(byte[] b) {
        return b.length > 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    private static String others(ProjectData d, ProjectData.QaRef q) {
        ProjectData.Row r = d.row(q.rowId());
        if (r == null) {
            return "";
        }
        List<String> l = new ArrayList<>();
        r.cells.forEach((k, c) -> {
            if (!k.equals(q.letter()) && c.status == AccStatus.DONE) {
                l.add(k);
            }
        });
        return String.join(", ", l);
    }

    private static String thread(QaItem it) {
        StringBuilder sb = new StringBuilder();
        for (QaItem.Msg m : it.thread()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append('[').append(ProjectStore.time(m.atMs())).append("] ").append(m.by()).append(m.fromCustomer() ? " (KH)" : "")
                    .append(": ").append(m.text());
        }
        return sb.toString();
    }

    public static final String ANSWER_HEADER = "Khách hàng trả lời";

    static Table qa(ProjectData d, List<String> letters, String name, Options o, QaItem.Kind kind) {
        List<ProjectData.QaRef> items = qaOf(d, letters, kind);
        int maxImg = 0;
        for (ProjectData.QaRef q : items) {
            maxImg = Math.max(maxImg, q.item().imgs().size());
        }
        if (!o.images() && o.imageLink() == null) {
            maxImg = 0;
        }
        List<String> header = new ArrayList<>(List.of("Mã", "Mã Q&A", "Loại", "Tài khoản", "STT", "Method", "Path", "Nhánh",
                kind == QaItem.Kind.INFO ? "Ghi chú" : "Câu hỏi", "Mức độ", "Cần ai trả lời", "Hạn", "Trạng thái"));
        int firstImg = header.size();
        for (int k = 1; k <= maxImg; k++) {
            header.add("Ảnh " + k);
        }
        int answerCol = header.size();
        header.addAll(List.of(ANSWER_HEADER, "Người trả lời", "Ngày trả lời", "Trao đổi", "Người hỏi", "Ngày hỏi",
                "Tài khoản khác gọi được"));
        List<List<Object>> rows = new ArrayList<>();
        List<Xlsx.Picture> pics = new ArrayList<>();
        for (ProjectData.QaRef q : items) {
            QaItem it = q.item();
            ProjectData.Row r = d.row(q.rowId());
            List<Object> row = new ArrayList<>(List.of(it.id(), it.code(), it.kind().label, d.config().accountDisplay(q.letter()),
                    r == null ? "" : r.stt, r == null ? "" : r.method, r == null ? "" : r.path, r == null ? "" : r.branch,
                    StyledCell.of(it.question(), CellTone.WRAP), it.severity().label, it.askTo().label, it.due(),
                    StyledCell.of(it.status().label, it.status().waiting() ? CellTone.QA_PURPLE
                            : it.status() == QaItem.Status.ANSWERED ? CellTone.DONE_GREEN : CellTone.OOS_GRAY)));
            List<QaItem.QaImage> ims = it.orderedImages();
            for (int k = 0; k < maxImg; k++) {
                if (k < ims.size()) {
                    QaItem.QaImage im = ims.get(k);
                    String cap = im.caption() + (im.desc().isEmpty() ? "" : " - " + im.desc());
                    String link = o.imageLink() == null ? null : o.imageLink().apply(q, im.name());
                    if (link != null && !link.isEmpty()) {
                        row.add(new Formula("HYPERLINK(\"" + link.replace("\"", "") + "\",\"" + cap.replace("\"", "'") + "\")", cap,
                                CellTone.LINK));
                        continue;
                    }
                    row.add(StyledCell.of(cap, CellTone.WRAP));
                    if (!o.images()) {
                        continue;
                    }
                    Xlsx.Picture p = picture(imageBytes(q, im.name(), o), rows.size(), firstImg + k, o.maxImagePx(),
                            o.maxImagePx() * 2 / 3, 34);
                    if (p != null) {
                        pics.add(p);
                    }
                } else {
                    row.add("");
                }
            }
            row.addAll(List.of(StyledCell.of(it.answer(), CellTone.WRAP), it.answeredBy(), ProjectStore.time(it.answeredAtMs()),
                    StyledCell.of(thread(it), CellTone.WRAP), it.by(), ProjectStore.time(it.atMs()), others(d, q)));
            rows.add(row);
        }
        int[] w = new int[header.size()];
        int[] base = {6, 11, 7, 16, 7, 8, 40, 26, 50, 11, 13, 11, 16};
        for (int i = 0; i < w.length; i++) {
            w[i] = i < base.length ? base[i] : (i < answerCol ? Math.max(24, o.maxImagePx() / 7 + 2) : 22);
        }
        w[answerCol] = 50;
        w[answerCol + 3] = 50;
        Map<Integer, String> editable = new LinkedHashMap<>();
        editable.put(answerCol, "answer");
        return new Table(name, kind == QaItem.Kind.INFO ? SheetDef.Type.INFO : SheetDef.Type.QA, header, rows, w, Set.of(0), pics, null,
                true, Map.of(), editable);
    }

    /** One block per Q&A: title, question, details, each step with its caption, description and screenshot, answer box. */
    static Table qaDetail(ProjectData d, List<String> letters, String name, Options o) {
        List<List<Object>> rows = new ArrayList<>();
        List<Xlsx.Picture> pics = new ArrayList<>();
        Map<Integer, Integer> heights = new LinkedHashMap<>();
        int imgW = Math.max(480, o.maxImagePx() * 2);
        for (ProjectData.QaRef q : qaOf(d, letters, null)) {
            QaItem it = q.item();
            ProjectData.Row r = d.row(q.rowId());
            String where = r == null ? "" : r.method + " " + r.path + (r.branch.isEmpty() ? "" : "  ·  " + r.branch);
            rows.add(List.of("", StyledCell.of(it.code() + " · " + it.kind().label + " · " + d.config().accountDisplay(q.letter())
                    + " · " + where, CellTone.TITLE)));
            heights.put(rows.size() - 1, 26);
            rows.add(List.of("", StyledCell.of(it.kind() == QaItem.Kind.INFO ? "Ghi chú" : "Câu hỏi", CellTone.BOLD),
                    StyledCell.of(it.question(), CellTone.WRAP)));
            rows.add(List.of("", StyledCell.of("Chi tiết", CellTone.BOLD), "Mức độ: " + it.severity().label + " · Cần ai trả lời: "
                    + it.askTo().label + (it.due().isEmpty() ? "" : " · Hạn: " + it.due()) + " · Trạng thái: " + it.status().label
                    + " · Người hỏi: " + it.by() + " (" + ProjectStore.time(it.atMs()) + ")"));
            if (!o.images() && o.imageLink() != null) {
                for (QaItem.QaImage im : it.orderedImages()) {
                    String link = o.imageLink().apply(q, im.name());
                    rows.add(List.of("", StyledCell.of(im.caption(), CellTone.BOLD), link == null || link.isEmpty()
                            ? StyledCell.of(im.desc(), CellTone.WRAP)
                            : new Formula("HYPERLINK(\"" + link.replace("\"", "") + "\",\"" + (im.desc().isEmpty() ? "Xem ảnh" : im.desc())
                                    .replace("\"", "'") + "\")", im.desc(), CellTone.LINK)));
                }
            }
            if (o.images()) {
                for (QaItem.QaImage im : it.orderedImages()) {
                    rows.add(List.of("", StyledCell.of(im.caption(), CellTone.BOLD), StyledCell.of(im.desc(), CellTone.WRAP)));
                    Xlsx.Picture p = picture(imageBytes(q, im.name(), o), rows.size(), 2, imgW, imgW * 3 / 4, 4);
                    rows.add(List.of("", ""));
                    if (p != null) {
                        pics.add(new Xlsx.Picture(rows.size() - 1, 2, p.data(), p.ext(), p.widthPx(), p.heightPx(), 4));
                    }
                }
            }
            List<Object> ans = new ArrayList<>(List.of(it.id(), StyledCell.of(ANSWER_HEADER, CellTone.QA_PURPLE),
                    StyledCell.of(it.answer(), CellTone.WRAP)));
            rows.add(ans);
            heights.put(rows.size() - 1, 60);
            if (!it.thread().isEmpty()) {
                rows.add(List.of("", StyledCell.of("Trao đổi", CellTone.BOLD), StyledCell.of(thread(it), CellTone.WRAP)));
            }
            rows.add(List.of());
        }
        if (rows.isEmpty()) {
            rows.add(List.of("", "Chưa có Q&A / Info."));
        }
        Map<Integer, String> editable = new LinkedHashMap<>();
        editable.put(2, "answer-detail");
        return new Table(name, SheetDef.Type.QA_DETAIL, List.of(), rows, new int[] {4, 22, Math.max(90, imgW / 7 + 4)}, Set.of(0),
                pics, null, false, heights, editable);
    }
}
