package themythofcrawl.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;
import themythofcrawl.excel.Xlsx;

/**
 * V_11.4 client rounds: "Gửi khách hàng" writes the report twice into bao-cao/khach-hang/vong-N_date/ - goc/ (kept,
 * never shared) and chia-se/ (the copy shared with the client, who may write in it). "Nhập phản hồi" compares the two
 * and lists every change the client made (answers, statuses, notes, new rows, pasted screenshots) for the PIC to
 * accept one by one. The original is never touched, so nothing the client deletes or overwrites is lost.
 */
public final class ClientRound {
    public static final String ORIGINAL = "goc";
    public static final String SHARED = "chia-se";

    private ClientRound() {
    }

    public record Sent(Path dir, Path original, Path shared) {
    }

    public static Sent send(ProjectStore store, byte[] workbook, String fileName, String by, Map<String, Object> info) {
        Path dir = store.nextRoundDir();
        String name = Text.blank(fileName) ? "BaoCao_" + ProjectStore.safe(store.config().name) + ".xlsx" : fileName;
        Path goc = dir.resolve(ORIGINAL).resolve(name);
        Path share = dir.resolve(SHARED).resolve(name);
        ProjectStore.write(goc, workbook);
        ProjectStore.write(share, workbook);
        Map<String, Object> m = new LinkedHashMap<>(info == null ? Map.of() : info);
        m.put("file", name);
        m.put("by", Text.nz(by));
        m.put("at", System.currentTimeMillis());
        ProjectStore.write(dir.resolve("vong.json"), Json.pretty(m).getBytes(StandardCharsets.UTF_8));
        try {
            goc.toFile().setReadOnly();
        } catch (RuntimeException ignored) {
            // best effort
        }
        store.log(by, "round", dir.getFileName().toString(), "Gửi khách hàng: " + name);
        return new Sent(dir, goc, share);
    }

    /** What one change means once accepted. */
    public enum Kind {
        ANSWER("Trả lời Q&A"), MARK("Trạng thái tài khoản"), NOTE("Ghi chú"), IMAGE("Ảnh khách hàng chèn"), NEW_ROW("Dòng mới"),
        DELETED_ROW("Dòng bị xóa"), OTHER("Ô khác");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /**
     * @param target Q&A id / row code ("Mã")
     * @param letter account of a MARK change
     */
    public record Change(Kind kind, String sheet, String target, String column, String letter, String before, String after,
                         byte[] image, String imageExt) {
        public String describe() {
            return switch (kind) {
                case IMAGE -> "Ảnh mới ở " + sheet + " (" + target + ")";
                case NEW_ROW -> "Dòng mới ở " + sheet + ": " + after;
                case DELETED_ROW -> "Khách hàng xóa dòng " + target + " ở " + sheet + " (bản gốc vẫn giữ)";
                default -> sheet + " · " + column + ": \"" + before + "\" -> \"" + after + "\"";
            };
        }
    }

    /** Every difference between goc/ and chia-se/ of a round (or another file the client sent back). */
    public static List<Change> diff(Path original, Path returned, ProjectConfig cfg) throws IOException {
        List<Change> out = new ArrayList<>();
        Map<String, Xlsx.Sheet> a = byName(Xlsx.readAll(original));
        Map<String, Xlsx.Sheet> b = byName(Xlsx.readAll(returned));
        for (Map.Entry<String, Xlsx.Sheet> e : b.entrySet()) {
            Xlsx.Sheet ns = e.getValue();
            Xlsx.Sheet os = a.get(e.getKey());
            if (os == null || ns.rows().isEmpty()) {
                continue;
            }
            boolean table = !ns.rows().isEmpty() && "Mã".equals(str(ns.rows().get(0), 0));
            if (table) {
                diffTable(e.getKey(), os, ns, cfg, out);
            } else {
                diffBlocks(e.getKey(), os, ns, out);
            }
        }
        // screenshots the client pasted
        Set<String> had = new LinkedHashSet<>();
        for (Xlsx.InPicture p : Xlsx.readPictures(original)) {
            had.add(sha(p.data()));
        }
        for (Xlsx.InPicture p : Xlsx.readPictures(returned)) {
            if (had.contains(sha(p.data()))) {
                continue;
            }
            Xlsx.Sheet s = b.get(p.sheet());
            String target = "";
            if (s != null) {
                for (int r = Math.min(p.row(), s.rows().size() - 1); r >= 0 && target.isEmpty(); r--) {
                    target = str(s.rows().get(r), 0);
                    if (r == 0 && "Mã".equals(target)) {
                        target = "";
                    }
                }
            }
            out.add(new Change(Kind.IMAGE, p.sheet(), target, "", "", "", "", p.data(), p.ext()));
        }
        return out;
    }

    private static void diffTable(String sheet, Xlsx.Sheet os, Xlsx.Sheet ns, ProjectConfig cfg, List<Change> out) {
        List<Object> oh = os.rows().get(0);
        List<Object> nh = ns.rows().get(0);
        Map<String, List<Object>> oldRows = new LinkedHashMap<>();
        for (List<Object> r : os.rows().subList(1, os.rows().size())) {
            String id = str(r, 0);
            if (!id.isEmpty()) {
                oldRows.put(id, r);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (List<Object> r : ns.rows().subList(1, ns.rows().size())) {
            String id = str(r, 0);
            if (id.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int c = 1; c < r.size(); c++) {
                    if (!str(r, c).isEmpty()) {
                        sb.append(sb.length() == 0 ? "" : " | ").append(str(nh, c)).append(": ").append(str(r, c));
                    }
                }
                if (sb.length() > 0) {
                    out.add(new Change(Kind.NEW_ROW, sheet, "", "", "", "", sb.toString(), null, null));
                }
                continue;
            }
            seen.add(id);
            List<Object> o = oldRows.get(id);
            if (o == null) {
                continue;
            }
            for (int c = 1; c < nh.size(); c++) {
                String col = str(nh, c);
                int oc = oh.indexOf(nh.get(c));
                String before = oc < 0 ? "" : str(o, oc);
                String after = str(r, c);
                if (before.equals(after)) {
                    continue;
                }
                Kind k = Kind.OTHER;
                String letter = "";
                if (col.equals(ProjectWorkbook.ANSWER_HEADER)) {
                    k = Kind.ANSWER;
                } else if (col.equals(Columns.defaultHeader(Columns.NOTE))) {
                    k = Kind.NOTE;
                } else {
                    for (ProjectConfig.Account acc : cfg.accounts) {
                        if (col.equals(acc.display()) || col.equals(acc.letter())) {
                            k = Kind.MARK;
                            letter = acc.letter();
                        }
                    }
                }
                out.add(new Change(k, sheet, id, col, letter, before, after, null, null));
            }
        }
        for (String id : oldRows.keySet()) {
            if (!seen.contains(id)) {
                out.add(new Change(Kind.DELETED_ROW, sheet, id, "", "", "", "", null, null));
            }
        }
    }

    /** "Chi tiết Q&A": rows whose hidden first cell is a Q&A id hold the answer in the third column. */
    private static void diffBlocks(String sheet, Xlsx.Sheet os, Xlsx.Sheet ns, List<Change> out) {
        Map<String, String> before = new LinkedHashMap<>();
        for (List<Object> r : os.rows()) {
            if (str(r, 1).equals(ProjectWorkbook.ANSWER_HEADER) && !str(r, 0).isEmpty()) {
                before.put(str(r, 0), str(r, 2));
            }
        }
        for (List<Object> r : ns.rows()) {
            if (str(r, 1).equals(ProjectWorkbook.ANSWER_HEADER) && !str(r, 0).isEmpty()) {
                String old = before.getOrDefault(str(r, 0), "");
                if (!old.equals(str(r, 2))) {
                    out.add(new Change(Kind.ANSWER, sheet, str(r, 0), ProjectWorkbook.ANSWER_HEADER, "", old, str(r, 2), null, null));
                }
            }
        }
    }

    /**
     * Accepts the chosen changes: answers become client answers (the Q&A owner's JCrawl applies them), statuses become
     * hand marks "via Khách hàng", notes become row notes, pasted screenshots are saved under anh-qa/ (and handed to
     * {@code imageSink} for Q&A items of the open workspace).
     */
    public static int apply(ProjectStore store, List<Change> chosen, String round, String by,
                            java.util.function.BiPredicate<String, byte[]> imageSink) {
        int n = 0;
        String via = "Khách hàng" + (Text.blank(round) ? "" : " · " + round);
        for (Change c : chosen) {
            switch (c.kind()) {
                case ANSWER -> {
                    store.putLocal(SyncRecord.of(SyncRecord.QANS + ":" + c.target(), by, "", Map.of("answer", c.after(), "via", via), via));
                    n++;
                }
                case MARK -> {
                    AccStatus st = AccStatus.parse(c.after());
                    store.mark(c.target(), c.letter(), st, st == null ? c.after() : "", via, via);
                    n++;
                }
                case NOTE -> {
                    store.rowNote(c.target(), c.after(), via);
                    n++;
                }
                case IMAGE -> {
                    String file = "khach-hang-" + System.currentTimeMillis() % 1_000_000 + "." + (c.imageExt() == null ? "png" : c.imageExt());
                    Path dir = store.qaImageDir(c.target().isEmpty() ? "khong-ro" : c.target());
                    ProjectStore.write(dir.resolve(file), c.image());
                    if (imageSink != null && !c.target().isEmpty()) {
                        imageSink.test(c.target(), c.image());
                    }
                    n++;
                }
                default -> {
                }
            }
        }
        store.log(by, "round", round, "Nhận " + n + " thay đổi của khách hàng");
        return n;
    }

    private static Map<String, Xlsx.Sheet> byName(List<Xlsx.Sheet> ss) {
        Map<String, Xlsx.Sheet> m = new LinkedHashMap<>();
        for (Xlsx.Sheet s : ss) {
            m.put(s.name(), s);
        }
        return m;
    }

    private static String str(List<Object> r, int i) {
        Object v = i < r.size() ? r.get(i) : null;
        if (v == null) {
            return "";
        }
        if (v instanceof Double d && d == Math.rint(d)) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(v).trim();
    }

    private static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            return String.valueOf(java.util.Arrays.hashCode(b));
        }
    }

    public static Path sharedFile(Path roundDir) throws IOException {
        return firstXlsx(roundDir.resolve(SHARED));
    }

    public static Path originalFile(Path roundDir) throws IOException {
        return firstXlsx(roundDir.resolve(ORIGINAL));
    }

    private static Path firstXlsx(Path d) throws IOException {
        if (!Files.isDirectory(d)) {
            return null;
        }
        try (var s = Files.list(d)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")
                    && !p.getFileName().toString().startsWith("~$")).sorted().findFirst().orElse(null);
        }
    }
}
