package themythofcrawl.store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Json;
import themythofcrawl.core.Text;
import themythofcrawl.excel.TargetEntry;
import themythofcrawl.excel.TargetList;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.model.Blob;
import themythofcrawl.model.QaItem;

/**
 * V_10.4: one website crawled account by account (highest first), one workspace per account ("workspace con").
 * Reads every workspace of the family from disk (read only) and builds: the API x account matrix, the Excel rows
 * each account reached, every Q&A / Info item, and the combined report workbook (with Q&A screenshots embedded).
 */
public final class FamilyReport {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public record Api(String key, int gid, String method, String path, String host, String branch, Integer status, String stt) {
    }

    public record Qa(Member member, Api api, QaItem item) {
    }

    public record Member(Workspaces.Info info, String account, List<Api> apis, Map<String, Api> byKey, List<Qa> qa,
                         FileStorage storage) {
    }

    public record Row(String key, String method, String path, String stt, List<Api> cells) {
        public int reached() {
            int n = 0;
            for (Api a : cells) {
                n += a == null ? 0 : 1;
            }
            return n;
        }
    }

    private final List<Member> members;
    private final List<Row> rows;
    private final TargetList targets;

    private FamilyReport(List<Member> members, List<Row> rows, TargetList targets) {
        this.members = members;
        this.rows = rows;
        this.targets = targets;
    }

    public List<Member> members() {
        return members;
    }

    public List<Row> rows() {
        return rows;
    }

    public TargetList targets() {
        return targets;
    }

    /** Account name shown for a workspace: its "account" (child) or its name (root). */
    public static String accountOf(Workspaces.Info i) {
        return Text.blank(i.account()) ? i.name() : i.account();
    }

    public static FamilyReport load(Path anyMember) {
        List<Workspaces.Info> fam = Workspaces.family(anyMember);
        List<Member> ms = new ArrayList<>();
        TargetList targets = null;
        for (Workspaces.Info i : fam) {
            FileStorage st = new FileStorage(i.dir(), i.name());
            TargetList tl = null;
            try {
                String state = st.loadState();
                if (state != null && !state.isBlank()) {
                    Object t = Json.parseObject(state).get("targets");
                    tl = t instanceof String ts && !ts.isBlank() ? TargetList.fromJson(ts) : null;
                }
            } catch (RuntimeException ignored) {
                tl = null;
            }
            if (targets == null) {
                targets = tl;
            }
            Map<Integer, String> sttByRow = new LinkedHashMap<>();
            if (tl != null) {
                for (TargetEntry e : tl.entries()) {
                    sttByRow.put(e.rowNo(), e.sttText());
                }
            }
            List<Api> apis = new ArrayList<>();
            Map<String, Api> byKey = new LinkedHashMap<>();
            List<Qa> qa = new ArrayList<>();
            Member m = new Member(i, accountOf(i), apis, byKey, qa, st);
            for (String doc : st.loadGroupDocs()) {
                Map<String, Object> g;
                try {
                    g = Json.parseObject(doc);
                } catch (RuntimeException e) {
                    continue;
                }
                if (Json.bool(g, "hidden", false)) {
                    continue;
                }
                String method = Json.str(g, "method", "GET");
                String path = Json.str(g, "path", "/");
                Object row = g.get("target_row");
                Object os = g.get("orig_status");
                Api a = new Api(Engine.endpointKey(method, path), (int) Json.num(g, "gid", 0), method, path,
                        Json.str(g, "host", ""), Json.str(g, "branch", ""), os instanceof Number n ? n.intValue() : null,
                        row instanceof Number n ? sttByRow.getOrDefault(n.intValue(), "") : "");
                apis.add(a);
                byKey.putIfAbsent(a.key(), a);
                for (Object o : Json.list(g, "qa")) {
                    if (o instanceof Map<?, ?> qm) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> mm = (Map<String, Object>) qm;
                        qa.add(new Qa(m, a, QaItem.fromMap(mm)));
                    }
                }
            }
            apis.sort((x, y) -> Integer.compare(x.gid(), y.gid()));
            ms.add(m);
        }
        Map<String, Row> rows = new LinkedHashMap<>();
        for (Member m : ms) {
            for (Api a : m.apis()) {
                rows.computeIfAbsent(a.key(), k -> new Row(k, a.method(), a.path(), a.stt(), new ArrayList<>()));
            }
        }
        List<Row> out = new ArrayList<>();
        for (Row r : rows.values()) {
            List<Api> cells = new ArrayList<>();
            String stt = r.stt();
            for (Member m : ms) {
                Api a = m.byKey().get(r.key());
                cells.add(a);
                if (Text.blank(stt) && a != null) {
                    stt = a.stt();
                }
            }
            out.add(new Row(r.key(), r.method(), r.path(), stt, cells));
        }
        return new FamilyReport(ms, out, targets);
    }

    public byte[] imageOf(Qa q, String name) {
        try {
            Blob b = q.member().storage().blob(q.api().gid(), name);
            return b == null ? null : b.bytes();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ workbook
    /**
     * @param includeAccounts one sheet per account
     * @param qaKinds which Q&A kinds to export (empty = no Q&A sheet)
     * @param onlyAccount null = every account; else only this account's rows / questions
     */
    public byte[] workbook(boolean includeAccounts, List<QaItem.Kind> qaKinds, String onlyAccount) throws IOException {
        List<Xlsx.OutSheet> sheets = new ArrayList<>();
        List<List<Xlsx.Picture>> pics = new ArrayList<>();
        List<Member> ms = onlyAccount == null ? members : members.stream().filter(m -> m.account().equals(onlyAccount)).toList();

        // Tổng quan
        List<List<Object>> sum = new ArrayList<>();
        for (Member m : ms) {
            long qaOpen = m.qa().stream().filter(q -> q.item().status() == QaItem.Status.OPEN).count();
            sum.add(List.of(m.account(), m.info().name(), m.info().parent() == null ? "(gốc)" : m.info().parent().getFileName().toString(),
                    m.apis().size(), m.qa().size(), (int) qaOpen));
        }
        sheets.add(new Xlsx.OutSheet("Tổng quan", List.of("Tài khoản", "Workspace", "Workspace cha", "Số API", "Q&A / Info", "Chờ trả lời"),
                sum, new int[] {22, 26, 26, 10, 12, 12}));
        pics.add(List.of());

        // Ma trận
        List<String> header = new ArrayList<>(List.of("STT Excel", "Method", "Path"));
        for (Member m : ms) {
            header.add(m.account());
        }
        header.add("Số tài khoản gọi được");
        List<List<Object>> mx = new ArrayList<>();
        for (Row r : rows) {
            List<Object> row = new ArrayList<>(List.of(Text.nz(r.stt()), r.method(), r.path()));
            int n = 0;
            for (int k = 0; k < members.size(); k++) {
                if (!ms.contains(members.get(k))) {
                    continue;
                }
                Api a = r.cells().get(k);
                n += a == null ? 0 : 1;
                row.add(a == null ? Xlsx.StyledCell.of("-", Xlsx.CellTone.OOS_GRAY)
                        : Xlsx.StyledCell.of("Có" + (a.status() != null ? " " + a.status() : ""), Xlsx.CellTone.DONE_GREEN));
            }
            if (n == 0) {
                continue;
            }
            row.add(n);
            mx.add(row);
        }
        int[] w = new int[header.size()];
        w[0] = 10;
        w[1] = 9;
        w[2] = 48;
        for (int k = 3; k < w.length; k++) {
            w[k] = 16;
        }
        sheets.add(new Xlsx.OutSheet("Ma trận tài khoản", header, mx, w));
        pics.add(List.of());

        if (includeAccounts) {
            for (Member m : ms) {
                List<List<Object>> rowsOf = new ArrayList<>();
                for (Api a : m.apis()) {
                    rowsOf.add(List.of(Text.nz(a.stt()), "API " + a.gid(), a.method(), a.host(), a.path(), a.branch(),
                            a.status() == null ? "" : a.status()));
                }
                sheets.add(new Xlsx.OutSheet(sheetName(m.account()),
                        List.of("STT Excel", "API", "Method", "Host", "Path", "Nhánh", "Status"), rowsOf, new int[] {10, 9, 9, 24, 48, 36, 8}));
                pics.add(List.of());
            }
        }

        if (!qaKinds.isEmpty()) {
            addQaSheet(sheets, pics, ms, qaKinds);
        }
        return Xlsx.write(sheets, pics);
    }

    /** Q&A sheet: one row per question, every screenshot embedded (one column each), and the client's answer column. */
    private void addQaSheet(List<Xlsx.OutSheet> sheets, List<List<Xlsx.Picture>> pics, List<Member> ms, List<QaItem.Kind> kinds) {
        List<Qa> items = new ArrayList<>();
        for (Member m : ms) {
            for (Qa q : m.qa()) {
                if (kinds.contains(q.item().kind())) {
                    items.add(q);
                }
            }
        }
        int maxImg = 1;
        for (Qa q : items) {
            maxImg = Math.max(maxImg, q.item().images().size());
        }
        List<String> header = new ArrayList<>(List.of("STT", "Loại", "Tài khoản", "API", "Method", "Path", "Nhánh",
                "STT Excel", "Câu hỏi / Ghi chú"));
        int firstImgCol = header.size();
        for (int k = 1; k <= maxImg; k++) {
            header.add("Ảnh " + k);
        }
        header.addAll(List.of("Trạng thái", "Người hỏi", "Ngày hỏi", "Khách hàng trả lời", "Người trả lời", "Ngày trả lời",
                "Tài khoản khác gọi được"));
        List<List<Object>> out = new ArrayList<>();
        List<Xlsx.Picture> pictures = new ArrayList<>();
        int stt = 1;
        for (Qa q : items) {
            QaItem it = q.item();
            List<Object> row = new ArrayList<>(List.of(stt, it.kind().label, q.member().account(), "API " + q.api().gid(),
                    q.api().method(), q.api().path(), q.api().branch(), Text.nz(q.api().stt()), it.question()));
            for (int k = 0; k < maxImg; k++) {
                row.add(k < it.images().size() ? "" : "");
            }
            List<String> others = new ArrayList<>();
            for (Member m : members) {
                if (m != q.member() && m.byKey().containsKey(q.api().key())) {
                    others.add(m.account());
                }
            }
            row.addAll(List.of(Xlsx.StyledCell.of(it.status().label, it.status() == QaItem.Status.OPEN ? Xlsx.CellTone.INFO_YELLOW
                            : it.status() == QaItem.Status.ANSWERED ? Xlsx.CellTone.DONE_GREEN : Xlsx.CellTone.OOS_GRAY),
                    it.by(), TS.format(Instant.ofEpochMilli(it.atMs())), it.answer(), it.answeredBy(),
                    it.answeredAtMs() > 0 ? TS.format(Instant.ofEpochMilli(it.answeredAtMs())) : "",
                    String.join(", ", others)));
            for (int k = 0; k < it.images().size(); k++) {
                byte[] img = imageOf(q, it.images().get(k));
                Xlsx.Picture p = picture(out.size(), firstImgCol + k, img, it.images().get(k));
                if (p != null) {
                    pictures.add(p);
                }
            }
            out.add(row);
            stt++;
        }
        int[] w = new int[header.size()];
        int[] base = {6, 7, 16, 9, 8, 40, 28, 9, 50};
        for (int k = 0; k < w.length; k++) {
            w[k] = k < base.length ? base[k] : (k < firstImgCol + maxImg ? 36 : 22);
        }
        w[firstImgCol + maxImg + 3] = 50;   // Khách hàng trả lời
        sheets.add(new Xlsx.OutSheet("Q&A", header, out, w));
        pics.add(pictures);
    }

    /** Scales a screenshot to at most 240 x 160 px for the cell. */
    static Xlsx.Picture picture(int row, int col, byte[] img, String name) {
        if (img == null || img.length == 0) {
            return null;
        }
        int wpx = 240;
        int hpx = 160;
        try {
            java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(new ByteArrayInputStream(img));
            if (bi != null) {
                double scale = Math.min(1.0, Math.min(240.0 / bi.getWidth(), 160.0 / bi.getHeight()));
                wpx = Math.max(20, (int) Math.round(bi.getWidth() * scale));
                hpx = Math.max(20, (int) Math.round(bi.getHeight() * scale));
            }
        } catch (IOException | RuntimeException ignored) {
            // keep the default box
        }
        String ext = name != null && name.toLowerCase(Locale.ROOT).endsWith(".jpeg") ? "jpeg" : "png";
        return new Xlsx.Picture(row, col, img, ext, wpx, hpx);
    }

    private static String sheetName(String account) {
        String s = Text.nz(account).replaceAll("[\\\\/?*\\[\\]:]", "_");
        return s.isBlank() ? "Tài khoản" : (s.length() > 31 ? s.substring(0, 31) : s);
    }
}
