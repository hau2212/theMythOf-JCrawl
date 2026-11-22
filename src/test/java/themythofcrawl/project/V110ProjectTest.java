package themythofcrawl.project;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.Config;
import themythofcrawl.rolereq.RoleRules;
import themythofcrawl.excel.TargetList;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.QaItem;
import themythofcrawl.model.Service;
import themythofcrawl.store.FileStorage;
import themythofcrawl.store.Workspaces;

/** V_11.0: project list x accounts, marks, the project workbook (formulas, chart, Q&A pictures). */
public class V110ProjectTest {
    static final Service SVC = new Service("shop.test", 443, true);

    public static Engine engine() {
        Engine e = new Engine(new themythofcrawl.ui.V70MultiRoleTeamTest.Server(), Prefs.memory(), RoleRules.INSTANCE);
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.autoSend = false;
        c.confirmNewApis = false;
        c.ignoreStatic = false;
        c.apiRegexEnabled = false;
        e.applyConfig(c);
        return e;
    }

    public static Engine.Message msg(String method, String path, int status) {
        String req = method + " " + path + " HTTP/1.1\r\nHost: shop.test\r\nAuthorization: Bearer T\r\n\r\n";
        return new Engine.Message(SVC, req.getBytes(StandardCharsets.ISO_8859_1),
                ("HTTP/1.1 " + status + " X\r\n\r\n{}").getBytes(StandardCharsets.ISO_8859_1), "https://shop.test" + path);
    }

    public static ApiGroup cap(Engine e, String method, String path, int status) {
        return e.process(msg(method, path, status), "Proxy", true, e.activeBranch().id());
    }

    public static byte[] png(int w, int h) throws Exception {
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bi, "png", bo);
        return bo.toByteArray();
    }

    /** A (admin) with an Excel list of 3 rows, B (staff) as a child workspace. Returns B's workspace dir. */
    public static Path family(Path dir) throws Exception {
        Workspaces ws = new Workspaces(Prefs.memory(), Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine a = engine();
        FileStorage admin = ws.start("p", "Shop");
        a.open(admin);
        a.setTargets(TargetList.fromRows(List.of(List.of("STT", "PROTOCOL", "PATH", "VỊ TRÍ"),
                List.of("1", "GET", "/api/users/{id}", "Users"), List.of("2", "GET", "/api/admin/stats", "Admin"),
                List.of("3", "GET", "/api/orders", "Orders")), "chuan.xlsx", "S"));
        a.setDestination("Users");
        ApiGroup users = cap(a, "GET", "/api/users/15", 200);
        cap(a, "GET", "/api/admin/stats", 200);
        cap(a, "GET", "/api/profile", 200);
        String img = a.addQaImage(users, png(600, 400));
        String img2 = a.addQaImage(users, png(300, 200));
        QaItem q = QaItem.create(QaItem.Kind.QA, "User thường xem được user khác?", List.of(img, img2), "A", "PIC");
        q = q.withImages(List.of(new QaItem.QaImage(img, QaItem.QaImage.Role.STEP, 1, "Đăng nhập bằng B", "Tài khoản staff", ""),
                new QaItem.QaImage(img2, QaItem.QaImage.Role.ACTUAL, 0, "Thấy dữ liệu user 15", "", "")));
        a.putQa(users, q);
        a.flush();
        FileStorage child = ws.createChild(admin.dir(), "staff", a.templateStateJson());
        Workspaces.setMeta(child.dir(), "letter", "B");
        Engine b = engine();
        b.open(child);
        b.setDestination("Users");
        cap(b, "GET", "/api/users/99", 200);
        cap(b, "GET", "/api/admin/stats", 403);
        cap(b, "GET", "/api/staff/only", 200);
        b.flush();
        a.close();
        b.close();
        return child.dir();
    }

    static ProjectData.Row row(ProjectData d, String path) {
        return d.rows().stream().filter(r -> r.path.equals(path)).findFirst().orElseThrow(() -> new AssertionError(path + " in "
                + d.rows().stream().map(r -> r.path).toList()));
    }

    @Test
    void listCellsMarksAndWorkbook(@TempDir Path dir) throws Exception {
        Path bDir = family(dir);
        ProjectStore store = ProjectStore.open(ProjectStore.projectDirOf(bDir));
        List<ProjectData.LocalAccount> locals = ProjectData.loadLocal(bDir);
        assertEquals(List.of("A", "B"), locals.stream().map(ProjectData.LocalAccount::letter).toList());
        ProjectData d = ProjectData.build(store.config(), locals, store.records(), "An");

        ProjectData.Row users = row(d, "/api/users/{id}");
        assertEquals(ProjectData.Source.STANDARD, users.source);
        assertEquals(AccStatus.DONE, users.cell("A").status);
        assertEquals(AccStatus.DONE, users.cell("B").status, "B's /api/users/99 falls on the same Excel row");
        ProjectData.Row stats = row(d, "/api/admin/stats");
        assertEquals(AccStatus.DONE, stats.cell("A").status);
        assertEquals(AccStatus.DENIED, stats.cell("B").status);
        ProjectData.Row orders = row(d, "/api/orders");
        assertEquals(AccStatus.MISSING, orders.cell("A").status, "an Excel row nobody called still shows");
        assertEquals(AccStatus.MISSING, orders.cell("B").status);
        assertEquals(ProjectData.Source.OUTSIDE, row(d, "/api/profile").source, "A found it, the standard list does not have it");
        ProjectData.Row only = row(d, "/api/staff/only");
        assertEquals(ProjectData.Source.ONLY, only.source);
        assertEquals("Chỉ B có", only.sourceLabel());
        assertTrue(users.cell("A").qaOpen);

        ProjectData.Stats sa = d.stats("A");
        assertEquals(3, sa.list());
        assertEquals(2, sa.done());
        assertEquals(1, sa.missing());
        assertEquals(1, sa.qaOpen());
        ProjectData.Stats sb = d.stats("B");
        assertEquals(1, sb.done());
        assertEquals(1, sb.denied());
        assertEquals(1, d.countDone(List.of("A"), List.of("B")), "stats: A yes, B no");

        // hand-set marks win; PIC approves "Chỉ B có" into the list
        store.mark(orders.id, "B", AccStatus.OOS, "không có quyền theo thiết kế", "An", "JCrawl");
        store.mark(users.id, "A", AccStatus.DENIED, "", "An", "JCrawl");
        store.config().added.add(new ProjectConfig.Added(only.id, only.method, only.path, only.branch, only.host));
        d = ProjectData.build(store.config(), locals, store.records(), "An");
        assertEquals(AccStatus.OOS, row(d, "/api/orders").cell("B").status);
        assertTrue(row(d, "/api/orders").cell("B").manual);
        assertEquals(ProjectData.Source.ADDED, row(d, "/api/staff/only").source);
        assertTrue(row(d, "/api/users/{id}").bac.contains("B gọi được, A bị chặn"));
        assertEquals(2, store.pending().size(), "two marks wait to be pushed");

        // workbook
        ProjectWorkbook.Options o = ProjectWorkbook.Options.internal(d);
        List<ProjectWorkbook.Table> tables = ProjectWorkbook.tables(d, store.config().sheets, o);
        List<String> names = tables.stream().map(ProjectWorkbook.Table::name).toList();
        assertTrue(names.containsAll(List.of("Tổng quan", "Tổng hợp", "A · Default", "B · staff", "Chênh lệch", "Q&A", "Chi tiết Q&A")),
                names.toString());
        byte[] x = ProjectWorkbook.xlsx(tables);
        Path f = dir.resolve("r.xlsx");
        Files.write(f, x);
        Map<String, String> parts = unzip(x);
        assertTrue(parts.keySet().stream().anyMatch(k -> k.startsWith("xl/charts/chart")), parts.keySet().toString());
        String overview = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(overview.contains("<f>COUNTIFS('Tổng hợp'!$"), overview.substring(0, Math.min(2000, overview.length())));
        assertTrue(parts.get("xl/workbook.xml").contains("fullCalcOnLoad"));
        List<Xlsx.Sheet> back = Xlsx.readAll(f);
        Xlsx.Sheet sum = back.stream().filter(s -> s.name().equals("Tổng hợp")).findFirst().orElseThrow();
        assertEquals("Mã", sum.rows().get(0).get(0));
        assertTrue(sum.rows().stream().anyMatch(r -> r.contains("Denied (B) 403")), sum.rows().toString());
        List<Xlsx.InPicture> pics = Xlsx.readPictures(f);
        assertTrue(pics.stream().filter(p -> p.sheet().equals("Q&A")).count() >= 2);
        assertTrue(pics.stream().anyMatch(p -> p.sheet().equals("Chi tiết Q&A")));
        // cached COUNTIF value of Done for A is 1 after the hand-set Denied on users (stats Done)
        Xlsx.Sheet ov = back.stream().filter(s -> s.name().equals("Tổng quan")).findFirst().orElseThrow();
        List<Object> doneRow = ov.rows().stream().filter(r -> !r.isEmpty() && String.valueOf(r.get(0)).startsWith("Done")).findFirst()
                .orElseThrow();
        assertEquals(1L, doneRow.get(1));
        assertEquals(2L, doneRow.get(2), "B: users + the approved \"Chỉ B có\" API");
    }

    static Map<String, String> unzip(byte[] x) throws Exception {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(x))) {
            for (ZipEntry en; (en = z.getNextEntry()) != null; ) {
                out.put(en.getName(), en.getName().endsWith(".xml") || en.getName().endsWith(".rels")
                        ? new String(z.readAllBytes(), StandardCharsets.UTF_8) : "");
            }
        }
        return out;
    }

    @Test
    void qaItemReadsOldFormat() {
        QaItem old = QaItem.fromMap(themythofcrawl.core.Json.parseObject(
                "{\"id\":\"qa-1234abcd\",\"kind\":\"QA\",\"question\":\"x\",\"images\":[\"a.png\"],\"status\":\"OPEN\"}"));
        assertEquals(List.of("a.png"), old.images());
        assertEquals(QaItem.QaImage.Role.EXTRA, old.imgs().get(0).role());
        assertEquals("QA-1234AB", old.code());
        QaItem ans = old.withMessage(new QaItem.Msg("Khách hàng", 5L, "Đúng thiết kế", true));
        assertEquals(QaItem.Status.ANSWERED, ans.status());
        assertEquals("Đúng thiết kế", ans.answer());
        QaItem again = QaItem.fromMap(ans.toMap());
        assertEquals(1, again.thread().size());
        assertEquals(ans.answer(), again.answer());
        List<String> none = new ArrayList<>();
        assertTrue(none.isEmpty());
    }
}
