package themythofcrawl.collab;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import themythofcrawl.app.Engine;
import themythofcrawl.core.AccessMatrix;
import themythofcrawl.excel.Xlsx;

/**
 * V_7.0 "Excel nhóm": an offline, file-based way to work as a team that never looks unusual to a SOC.
 * Each member exports one workbook; anyone can merge several workbooks back (statuses, owners, tags,
 * notes — Git-style conflicts are detected exactly like live sync). Sheets:
 * <ol>
 *   <li>{@code Tong_hop} — one row per endpoint (the sheet {@link #merge} reads back)</li>
 *   <li>{@code Ma_tran_Role} — Thấy bởi × Gọi được per role</li>
 *   <li>{@code Test_case_Bug} — BAC test cases with empty Trạng thái / Người test / Mức độ to fill in</li>
 *   <li>{@code Phan_cong} — member ↔ branches they covered</li>
 *   <li>{@code Xung_dot} — open conflicts</li>
 *   <li>{@code Theo_thanh_vien} — counts per member and status</li>
 * </ol>
 */
public final class TeamWorkbook {
    public static final String SUMMARY_SHEET = "Tong_hop";
    static final List<String> SUMMARY_HEADER = List.of("Key", "Trạng thái", "Method", "Host", "Path", "Nhánh", "HTTP",
            "Tag", "Người tìm", "Ghi chú", "Cập nhật (ms)");

    private TeamWorkbook() {
    }

    public static byte[] export(Engine engine) throws IOException {
        InventoryCatalog cat = engine.inventoryCatalog();
        for (themythofcrawl.model.ApiGroup g : engine.groups()) {
            cat.recordLocalCapture(g);
        }
        List<InventoryCatalog.EndpointMeta> metas = cat.allMeta();

        List<List<Object>> summary = new ArrayList<>();
        for (InventoryCatalog.EndpointMeta m : metas) {
            summary.add(List.of(m.key, Xlsx.StyledCell.of(m.status.formatWithOwners(m.owners), m.status.xlsxTone),
                    m.method, m.host, m.pathTemplate, String.join(" | ", m.branches), m.httpStatus,
                    String.join(", ", m.tags), String.join(", ", m.owners), m.note, m.updatedAtMs));
        }

        AccessMatrix matrix = engine.accessMatrix(engine.groups());
        List<List<Object>> tests = new ArrayList<>();
        for (List<Object> row : matrix.findingRows()) {
            List<Object> r = new ArrayList<>(row);
            r.add("");  // Mức độ
            tests.add(r);
        }
        List<String> testHeader = new ArrayList<>(AccessMatrix.findingHeader());
        testHeader.add("Mức độ");

        Map<String, Set<String>> branchesByMember = new TreeMap<>();
        Map<String, int[]> countsByMember = new TreeMap<>();
        InventoryCatalog.ItemStatus[] statuses = InventoryCatalog.ItemStatus.values();
        for (InventoryCatalog.EndpointMeta m : metas) {
            Collection<String> owners = m.owners.isEmpty() ? List.of(cat.memberName()) : m.owners;
            for (String o : owners) {
                branchesByMember.computeIfAbsent(o, k -> new LinkedHashSet<>()).addAll(m.branches);
                countsByMember.computeIfAbsent(o, k -> new int[statuses.length + 1])[m.status.ordinal()]++;
                countsByMember.get(o)[statuses.length]++;
            }
        }
        List<List<Object>> assign = new ArrayList<>();
        branchesByMember.forEach((member, branches) -> {
            for (String b : branches) {
                assign.add(List.of(member, b, "", ""));
            }
        });
        List<List<Object>> perMember = new ArrayList<>();
        List<String> perHeader = new ArrayList<>(List.of("Thành viên"));
        for (InventoryCatalog.ItemStatus st : statuses) {
            perHeader.add(st.shortLabel);
        }
        perHeader.add("Tổng");
        countsByMember.forEach((member, counts) -> {
            List<Object> r = new ArrayList<>();
            r.add(member);
            for (int c : counts) {
                r.add(c);
            }
            perMember.add(r);
        });
        List<List<Object>> conflicts = new ArrayList<>();
        for (ConflictRecord cr : cat.openConflicts()) {
            conflicts.add(List.of(cr.endpointKey(), cr.method(), cr.host(), cr.pathTemplate(),
                    cr.localSide().summary(), cr.remoteSide().summary(), cr.autoSuggestion()));
        }

        List<Xlsx.OutSheet> sheets = List.of(
                new Xlsx.OutSheet(SUMMARY_SHEET, SUMMARY_HEADER, summary, new int[] {40, 18, 8, 22, 44, 36, 7, 20, 16, 30, 14}),
                new Xlsx.OutSheet("Ma_tran_Role", matrix.matrixHeader(), matrix.matrixRows(),
                        widths(matrix.matrixHeader().size(), 22)),
                new Xlsx.OutSheet("Test_case_Bug", testHeader, tests, widths(testHeader.size(), 18)),
                new Xlsx.OutSheet("Phan_cong", List.of("Thành viên", "Nhánh đã đi", "Giao thêm", "Ghi chú"), assign,
                        new int[] {16, 50, 30, 30}),
                new Xlsx.OutSheet("Xung_dot", List.of("Key", "Method", "Host", "Path", "Bản của tôi", "Bản đồng đội", "Gợi ý"),
                        conflicts, new int[] {36, 8, 20, 40, 36, 36, 30}),
                new Xlsx.OutSheet("Theo_thanh_vien", perHeader, perMember, widths(perHeader.size(), 12)));
        return Xlsx.write(sheets);
    }

    private static int[] widths(int n, int w) {
        int[] out = new int[n];
        java.util.Arrays.fill(out, w);
        return out;
    }

    /** Result of merging teammates' workbooks. */
    public record MergeReport(int files, int rows, int conflicts) {
        public String summary() {
            return "Đã gộp " + rows + " dòng từ " + files + " file" + (conflicts > 0 ? " · " + conflicts + " xung đột cần chọn" : "");
        }
    }

    /**
     * Reads the {@code Tong_hop} sheet of each teammate's workbook and applies every row as a remote
     * update from its owner (column "Người tìm"). Rows owned by this member are skipped.
     */
    public static MergeReport merge(Engine engine, List<Path> files) throws IOException {
        InventoryCatalog cat = engine.inventoryCatalog();
        int rows = 0;
        int conflicts = 0;
        for (Path f : files) {
            int idx = Math.max(0, Xlsx.sheetNames(f).indexOf(SUMMARY_SHEET));
            Xlsx.Sheet sheet = Xlsx.read(f, idx);
            List<List<Object>> data = sheet.rows();
            if (data.isEmpty()) {
                continue;
            }
            Map<String, Integer> col = new LinkedHashMap<>();
            List<Object> header = data.get(0);
            for (int i = 0; i < header.size(); i++) {
                col.put(String.valueOf(header.get(i)).trim(), i);
            }
            for (int r = 1; r < data.size(); r++) {
                List<Object> row = data.get(r);
                String method = cell(row, col, "Method");
                String path = cell(row, col, "Path");
                if (method.isEmpty() || path.isEmpty()) {
                    continue;
                }
                String owners = cell(row, col, "Người tìm");
                String member = owners.isEmpty() ? f.getFileName().toString().replaceAll("\\.xlsx$", "") : owners.split(",")[0].trim();
                if (member.equalsIgnoreCase(cat.memberName())) {
                    continue;
                }
                Set<String> tags = new LinkedHashSet<>();
                for (String t : cell(row, col, "Tag").split(",")) {
                    if (!t.isBlank()) {
                        tags.add(t.trim());
                    }
                }
                String branch = cell(row, col, "Nhánh").split(" \\| ")[0];
                int http = (int) num(cell(row, col, "HTTP"));
                long ts = (long) num(cell(row, col, "Cập nhật (ms)"));
                ConflictRecord cr = cat.applyRemoteUpdate(member, method, cell(row, col, "Host"), path,
                        InventoryCatalog.ItemStatus.parse(cell(row, col, "Trạng thái")), http, branch, tags,
                        cell(row, col, "Ghi chú"), ts > 0 ? ts : System.currentTimeMillis());
                rows++;
                if (cr != null) {
                    conflicts++;
                }
            }
        }
        engine.saveCollabPrefs();
        return new MergeReport(files.size(), rows, conflicts);
    }

    private static String cell(List<Object> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= row.size() || row.get(i) == null) {
            return "";
        }
        return String.valueOf(row.get(i)).trim();
    }

    private static double num(String s) {
        try {
            return s.isEmpty() ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
