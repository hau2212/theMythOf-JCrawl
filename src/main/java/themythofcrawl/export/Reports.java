package themythofcrawl.export;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import themythofcrawl.core.Audit;
import themythofcrawl.core.CopyFormat;
import themythofcrawl.core.Json;
import themythofcrawl.core.Text;
import themythofcrawl.excel.HitLog;
import themythofcrawl.excel.NearMissIndex;
import themythofcrawl.excel.TargetEntry;
import themythofcrawl.excel.TargetList;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Branch;
import themythofcrawl.model.ResultState;
import themythofcrawl.model.RoleResult;
import themythofcrawl.place.Decisions;
import themythofcrawl.place.PlacementBook;

/** Excel workbook, history report and JSON matrix exports. */
public final class Reports {
    public static final List<String> API_HEADER = List.of("STT", "Method", "Path", "Vị trí tìm thấy", "Host", "URL đầy đủ",
            "Status gốc", "Đánh giá", "Đã gửi", "Nguồn", "Thời gian", "STT trong file Excel", "Path để copy", "Đã copy");
    static final int[] API_WIDTHS = {6, 9, 48, 44, 26, 70, 11, 40, 9, 9, 10, 12, 50, 9};
    public static final List<String> ROLE_HEADER = List.of("STT", "API #", "Method", "Path", "Vị trí tìm thấy", "Role",
            "Trạng thái", "Status", "Body Len", "Lệch so với gốc", "Thời gian (ms)", "Đánh giá");
    static final int[] ROLE_WIDTHS = {6, 7, 9, 44, 40, 22, 12, 8, 10, 14, 14, 40};
    public static final List<String> COVERAGE_EXTRA = List.of("Trạng thái", "Số lần bắt", "API #", "Path thực tế",
            "Vị trí tìm thấy", "Đánh giá", "Path để copy", "Đã copy", "Gợi ý gần đúng (dòng chưa bắt)", "Lượt gặp",
            "Các vị trí đã gặp");
    static final int[] COVERAGE_WIDTHS = {6, 12, 48, 44, 12, 11, 10, 48, 44, 40, 50, 9, 60, 26, 60};
    public static final List<String> HITS_HEADER = List.of("STT (file)", "Method (file)", "Path (trong file)", "Lần",
            "Thời gian", "Kết quả", "API #", "Method", "Path thực tế", "Host", "Vị trí đang đứng", "Lưu vào nhánh",
            "Nguồn", "Ghi chú");
    static final int[] HITS_WIDTHS = {8, 10, 44, 6, 17, 16, 8, 9, 48, 24, 44, 44, 9, 30};
    public static final List<String> OUTSIDE_HEADER = List.of("STT", "API #", "Method", "Path", "Vị trí tìm thấy", "Host",
            "Status gốc", "Path để copy");
    static final int[] OUTSIDE_WIDTHS = {6, 8, 9, 48, 44, 26, 11, 50};

    private Reports() {
    }

    private static List<ApiGroup> byGid(Collection<ApiGroup> groups) {
        List<ApiGroup> out = new ArrayList<>(groups);
        out.sort(Comparator.comparingInt(ApiGroup::gid));
        return out;
    }

    /** Excel row -> APIs captured for it (in API order). */
    public static Map<TargetEntry, List<ApiGroup>> coverage(TargetList targets, Collection<ApiGroup> groups) {
        Map<TargetEntry, List<ApiGroup>> found = new IdentityHashMap<>();
        for (ApiGroup g : byGid(groups)) {
            if (g.target() != null) {
                found.computeIfAbsent(g.target(), k -> new ArrayList<>()).add(g);
            }
        }
        Map<TargetEntry, List<ApiGroup>> out = new LinkedHashMap<>();
        if (targets != null) {
            for (TargetEntry e : targets.entries()) {
                out.put(e, found.getOrDefault(e, List.of()));
            }
        }
        return out;
    }

    public static List<List<Object>> apiRows(Collection<ApiGroup> groups, Function<ApiGroup, String> label) {
        List<List<Object>> rows = new ArrayList<>();
        int i = 1;
        for (ApiGroup g : byGid(groups)) {
            long done = g.results().stream().filter(r -> ResultState.finished(r.state())).count();
            rows.add(Arrays.asList(i++, g.method(), Text.stripQuery(g.path()), g.branch(), g.host(), g.url(),
                    g.origStatus(), Audit.summary(g), done + "/" + g.results().size(), g.source(), g.created(),
                    g.target() != null ? g.target().stt() : null, label != null ? label.apply(g) : null,
                    g.copiedLabel() != null ? "Có" : null));
        }
        return rows;
    }

    public static List<List<Object>> roleRows(Collection<ApiGroup> groups) {
        List<List<Object>> rows = new ArrayList<>();
        for (ApiGroup g : byGid(groups)) {
            for (RoleResult r : g.results()) {
                Integer delta = r.length() != null && g.origLength() != null ? r.length() - g.origLength() : null;
                rows.add(Arrays.asList(rows.size() + 1, g.gid(), g.method(), Text.stripQuery(g.path()), g.branch(),
                        r.roleName(), r.state(), r.status(), r.length(), delta, r.elapsedMs(), Audit.badge(g, r)));
            }
        }
        return rows;
    }

    /**
     * Rows mirroring the user's file + what was found for each row. POSITION keeps what the user wrote;
     * when empty it is filled with where the API was found, as 'STT. path' when positionAsCopy is on.
     */
    public static List<List<Object>> coverageRows(TargetList targets, Collection<ApiGroup> groups, String copyFormat,
                                                  boolean positionAsCopy, HitLog hits) {
        List<List<Object>> out = new ArrayList<>();
        NearMissIndex near = new NearMissIndex(groups);
        for (Map.Entry<TargetEntry, List<ApiGroup>> en : coverage(targets, groups).entrySet()) {
            TargetEntry e = en.getKey();
            List<ApiGroup> found = en.getValue();
            ApiGroup first = found.isEmpty() ? null : found.get(0);
            List<String> positions = new ArrayList<>();
            for (ApiGroup g : found) {
                if (!positions.contains(g.branch())) {
                    positions.add(g.branch());
                }
            }
            String copyLine = positions.isEmpty() ? null : CopyFormat.line(copyFormat, e.stt(), positions.get(0));
            String position = !e.position().isEmpty() ? e.position()
                    : positionAsCopy && copyLine != null ? copyLine : (positions.isEmpty() ? null : String.join("; ", positions));
            String hint = null;
            if (found.isEmpty()) {
                NearMissIndex.Suggestion s = near.suggest(e);
                if (s != null) {
                    hint = Text.apiLabel(s.group().gid()) + " " + s.group().endpoint() + " (" + s.note() + ")";
                }
            }
            List<String> labels = new ArrayList<>();
            for (ApiGroup g : found) {
                labels.add(Text.apiLabel(g.gid()));
            }
            out.add(Arrays.asList(e.stt(), e.methodLabel(), e.path(), position, found.isEmpty() ? "Chưa bắt" : "Đã bắt",
                    found.size(), labels.isEmpty() ? null : String.join(", ", labels),
                    first != null ? Text.stripQuery(first.path()) : null,
                    positions.isEmpty() ? null : String.join("; ", positions), first != null ? Audit.summary(first) : null,
                    copyLine, found.stream().anyMatch(g -> g.copiedLabel() != null) ? "Có" : null, hint,
                    hits != null ? hits.cell(e) : null, hits != null ? String.join("; ", hits.positions(e)) : null));
        }
        return out;
    }

    public static List<List<Object>> hitRows(TargetList targets, HitLog hits) {
        List<List<Object>> rows = new ArrayList<>();
        if (targets == null || hits == null) {
            return rows;
        }
        for (TargetEntry e : targets.entries()) {
            for (HitLog.Event ev : hits.events(e)) {
                rows.add(Arrays.asList(e.stt(), e.methodLabel(), e.path(), ev.n(), ev.time(), ev.result().label,
                        ev.gid() != null ? Text.apiLabel(ev.gid()) : null, ev.method(), ev.path(), ev.host(), ev.branch(),
                        ev.filed(), ev.source(), Text.blank(ev.note()) ? null : ev.note()));
            }
        }
        return rows;
    }

    /** Captured APIs that match no row of the Excel file. */
    public static List<List<Object>> outsideRows(Collection<ApiGroup> groups, Function<ApiGroup, String> label) {
        List<List<Object>> rows = new ArrayList<>();
        for (ApiGroup g : byGid(groups)) {
            if (g.target() == null) {
                rows.add(Arrays.asList(rows.size() + 1, g.gid(), g.method(), Text.stripQuery(g.path()), g.branch(),
                        g.host(), g.origStatus(), label != null ? label.apply(g) : null));
            }
        }
        return rows;
    }

    public static byte[] workbook(Collection<ApiGroup> groups, TargetList targets, String copyFormat,
                                  boolean positionAsCopy, Function<ApiGroup, String> label, HitLog hits,
                                  PlacementBook places) throws IOException {
        List<Xlsx.OutSheet> sheets = new ArrayList<>();
        if (places != null && places.size() > 0) {
            List<List<Object>> rows = new ArrayList<>();
            for (Decisions.Row<PlacementBook.Record> r : Decisions.placeRows(places, 0, "")) {
                rows.add(r.cells());
            }
            sheets.add(new Xlsx.OutSheet("Vị trí API (giữ chỗ)", Decisions.PLACE_COLS, rows,
                    new int[] {9, 48, 26, 44, 10, 18, 10, 9, 12, 16, 44, 12}));
        }
        if (places != null && !places.log().isEmpty()) {
            List<List<Object>> rows = new ArrayList<>();
            for (Decisions.Row<PlacementBook.Event> r : Decisions.logRows(places, null, "")) {
                rows.add(r.cells());
            }
            sheets.add(new Xlsx.OutSheet("Nhật ký vị trí", Decisions.LOG_COLS, rows, new int[] {6, 16, 28, 9, 9, 48, 40, 40, 50}));
        }
        if (targets != null && targets.size() > 0) {
            List<String> header = new ArrayList<>(targets.headers());
            header.addAll(COVERAGE_EXTRA);
            sheets.add(new Xlsx.OutSheet("Độ phủ", header, coverageRows(targets, groups, copyFormat, positionAsCopy, hits),
                    COVERAGE_WIDTHS));
            if (hits != null) {
                sheets.add(new Xlsx.OutSheet("Nhật ký lượt gặp", HITS_HEADER, hitRows(targets, hits), HITS_WIDTHS));
            }
            sheets.add(new Xlsx.OutSheet("API ngoài danh sách", OUTSIDE_HEADER, outsideRows(groups, label), OUTSIDE_WIDTHS));
        }
        sheets.add(new Xlsx.OutSheet("API", API_HEADER, apiRows(groups, label), API_WIDTHS));
        sheets.add(new Xlsx.OutSheet("Kết quả Role", ROLE_HEADER, roleRows(groups), ROLE_WIDTHS));
        return Xlsx.write(sheets);
    }

    /** Text report: APIs in order under their branches. */
    public static String history(List<Branch> branches, Collection<ApiGroup> groups) {
        List<ApiGroup> gs = byGid(groups);
        List<String> lines = new ArrayList<>();
        String bar = "======================================================================";
        lines.add(bar);
        lines.add(Text.EXT_NAME + " - BÁO CÁO LỊCH SỬ CHỨC NĂNG & API (AUDIT HISTORY)");
        lines.add("Tổng số chức năng (Features): " + branches.size() + "   |   Tổng số API đã đi qua: " + gs.size());
        lines.add(bar);
        if (branches.isEmpty() && gs.isEmpty()) {
            lines.add("\nChưa có API nào được ghi nhận.");
            return String.join("\n", lines);
        }
        Map<Integer, List<ApiGroup>> byBranch = new TreeMap<>();
        for (ApiGroup g : gs) {
            byBranch.computeIfAbsent(g.branchId(), k -> new ArrayList<>()).add(g);
        }
        List<Integer> seen = new ArrayList<>();
        for (Branch b : branches) {
            seen.add(b.id());
            List<ApiGroup> bg = byBranch.getOrDefault(b.id(), List.of());
            String range = bg.isEmpty() ? "chưa có API"
                    : Text.apiLabel(bg.get(0).gid()) + " .. " + Text.apiLabel(bg.get(bg.size() - 1).gid());
            lines.add("");
            lines.add("[*] " + b.label() + "  (Số lượng API: " + bg.size() + " | " + range + " | Đánh giá: "
                    + Audit.branch(bg).summary() + " | Lúc: " + b.created() + ")");
            lines.add("-".repeat(70));
            if (bg.isEmpty()) {
                lines.add("   (Chưa có API nào trong chức năng này)");
                continue;
            }
            int idx = 1;
            for (ApiGroup g : bg) {
                lines.add("   " + idx++ + ". " + Text.apiLabel(g.gid()) + " [" + Text.groupId(g.gid()) + "]  " + g.method()
                        + " " + g.protocol() + "://" + g.host() + ":" + g.port() + g.path() + "  (Orig Status="
                        + (g.origStatus() == null ? "-" : g.origStatus()) + ", Orig Len="
                        + (g.origLength() == null ? "-" : g.origLength()) + " | " + g.results().size() + " roles)  --> ["
                        + Audit.summary(g) + "]");
                List<String> replayed = new ArrayList<>();
                for (RoleResult r : g.results()) {
                    if (r.status() != null || ResultState.ERROR.equals(r.state())) {
                        replayed.add(r.roleName() + ":" + (r.status() != null ? r.status() : "ERR") + "(len=" + r.length() + ")");
                    }
                }
                if (!replayed.isEmpty()) {
                    lines.add("      -> Kết quả Replay: " + String.join(", ", replayed));
                }
            }
        }
        for (Map.Entry<Integer, List<ApiGroup>> e : byBranch.entrySet()) {
            if (seen.contains(e.getKey())) {
                continue;
            }
            lines.add("");
            lines.add("[*] " + Text.featureLabel(e.getKey(), "") + "  (Số lượng API: " + e.getValue().size() + ")");
            int idx = 1;
            for (ApiGroup g : e.getValue()) {
                lines.add("   " + idx++ + ". " + Text.apiLabel(g.gid()) + " [" + Text.groupId(g.gid()) + "]  " + g.method()
                        + " " + g.path());
            }
        }
        return String.join("\n", lines);
    }

    /** Branches, APIs and per-role status / length metrics as JSON. */
    public static String matrixJson(Collection<ApiGroup> groups, List<Branch> branches) {
        List<ApiGroup> gs = byGid(groups);
        List<Object> items = new ArrayList<>();
        for (ApiGroup g : gs) {
            List<Object> roles = new ArrayList<>();
            for (RoleResult r : g.results()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("role", r.roleName());
                rm.put("state", r.state());
                rm.put("status", r.status());
                rm.put("length", r.length());
                rm.put("delta_vs_orig", r.length() != null && g.origLength() != null ? r.length() - g.origLength() : null);
                rm.put("audit_level", Audit.level(g, r).name());
                rm.put("audit_note", Audit.badge(g, r));
                rm.put("elapsed_ms", r.elapsedMs());
                rm.put("sends", r.sentCount());
                rm.put("repeater_tab", r.tabName());
                rm.put("error", Text.blank(r.error()) ? null : r.error());
                roles.add(rm);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("api_number", g.gid());
            m.put("api_label", Text.apiLabel(g.gid()));
            m.put("group_id", Text.groupId(g.gid()));
            m.put("feature_id", g.branchId());
            m.put("feature_name", g.branch());
            m.put("feature_label", Text.featureLabel(g.branchId(), g.branch()));
            m.put("audit_level", Audit.level(g).name());
            m.put("audit_summary", Audit.summary(g));
            m.put("source", g.source());
            m.put("created", g.created());
            m.put("method", g.method());
            m.put("url", g.url());
            m.put("host", g.host());
            m.put("port", g.port());
            m.put("protocol", g.protocol());
            m.put("path", g.path());
            m.put("orig_status", g.origStatus());
            m.put("orig_length", g.origLength());
            m.put("role_count", g.results().size());
            m.put("roles", roles);
            items.add(m);
        }
        List<Object> feats = new ArrayList<>();
        for (Branch b : branches) {
            List<ApiGroup> bg = gs.stream().filter(g -> g.branchId() == b.id()).toList();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("feature_id", b.id());
            m.put("name", b.name());
            m.put("label", b.label());
            m.put("created", b.created());
            m.put("api_count", bg.size());
            Audit.Verdict v = Audit.branch(bg);
            m.put("audit_level", v.level().name());
            m.put("audit_summary", v.summary());
            m.put("apis", bg.stream().map(g -> Text.apiLabel(g.gid())).toList());
            feats.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("extension", Text.EXT_NAME);
        root.put("version", 1);
        root.put("feature_count", feats.isEmpty() ? gs.stream().map(ApiGroup::branchId).distinct().count() : feats.size());
        root.put("api_count", items.size());
        root.put("features", feats);
        root.put("groups", items);
        return Json.pretty(root);
    }
}
