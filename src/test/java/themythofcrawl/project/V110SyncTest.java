package themythofcrawl.project;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.Json;
import themythofcrawl.excel.TargetList;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.QaItem;
import themythofcrawl.store.FileStorage;
import themythofcrawl.store.Workspaces;

/**
 * V_11.0 "Làm chung" end to end: two people, each with their own workspace and project folder, sync through the real
 * Apps Script (run by node against a fake of the Google services); a client answers in the sheet; hand marks;
 * conflicts; the personal Excel copy and snapshots.
 */
class V110SyncTest {
    private Process node;
    private String url;

    @AfterEach
    void stop() {
        if (node != null) {
            node.destroyForcibly();
        }
    }

    void startHub(Path dir) throws Exception {
        boolean hasNode;
        try {
            hasNode = new ProcessBuilder("node", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            hasNode = false;
        }
        Assumptions.assumeTrue(hasNode, "node is not installed");
        Path gs = dir.resolve("hub.gs");
        Files.writeString(gs, SheetHub.script());
        Path harness = dir.resolve("harness.js");
        try (var in = V110SyncTest.class.getResourceAsStream("/hub/harness.js")) {
            Files.write(harness, in.readAllBytes());
        }
        node = new ProcessBuilder("node", harness.toString(), gs.toString(), "0").redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(node.getInputStream(), StandardCharsets.UTF_8));
        String line = r.readLine();
        assertNotNull(line);
        assertTrue(line.startsWith("READY "), line);
        url = "http://127.0.0.1:" + line.substring(6).trim();
    }

    static final HttpClient HTTP = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build();

    String get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url + path)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    void post(String path, String body) throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create(url + path)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    List<List<Object>> sheet(String name) throws Exception {
        Object o = Json.parse(get("/__sheet?n=" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")));
        assertNotNull(o, "sheet " + name);
        return (List<List<Object>>) ((Map<String, Object>) o).get("cells");
    }

    static TargetList excel() {
        return TargetList.fromRows(List.of(List.of("STT", "PROTOCOL", "PATH", "VỊ TRÍ"), List.of("1", "GET", "/api/users/{id}", "Users"),
                List.of("2", "GET", "/api/admin/stats", "Admin"), List.of("3", "GET", "/api/orders", "Orders")), "chuan.xlsx", "S");
    }

    static final class TestHost implements ProjectSync.Host {
        final Path ws;
        final List<String> answers = new CopyOnWriteArrayList<>();

        TestHost(Path ws) {
            this.ws = ws;
        }

        @Override public Path workspaceDir() { return ws; }
        @Override public void flush() { }
        @Override public boolean applyAnswer(String qaId, QaItem.Msg msg) {
            answers.add(qaId + "=" + msg.text());
            return true;
        }
    }

    ProjectStore store(Path ws, String me) {
        ProjectStore s = ProjectStore.open(ProjectStore.projectDirOf(ws));
        ProjectConfig c = s.config();
        c.id = "prj-shop";
        c.name = "Shop";
        c.sheetUrl = url;
        c.sheetPass = "mat-khau-du-an";
        c.syncOn = true;
        c.me = me;
        return s;
    }

    static ProjectData.Row row(ProjectData d, String path) {
        return d.rows().stream().filter(r -> r.path.equals(path)).findFirst().orElseThrow();
    }

    @Test
    void twoPeopleSyncThroughTheSheet(@TempDir Path dir) throws Exception {
        startHub(dir);
        // PIC (Hậu): workspace A in their own folder
        Workspaces w1 = new Workspaces(Prefs.memory(), Prefs.memory(), dir.resolve("m1/x.jar").toString());
        w1.setRoot(dir.resolve("m1"));
        Engine a = V110ProjectTest.engine();
        FileStorage fa = w1.start("p", "admin");
        a.open(fa);
        a.setTargets(excel());
        ApiGroup users = V110ProjectTest.cap(a, "GET", "/api/users/15", 200);
        V110ProjectTest.cap(a, "GET", "/api/admin/stats", 200);
        String img = a.addQaImage(users, V110ProjectTest.png(320, 200));
        QaItem q = QaItem.create(QaItem.Kind.QA, "B xem được user khác?", List.of(img), "A", "Hậu");
        a.putQa(users, q);
        a.flush();
        a.close();
        // member (Bình): workspace B in their own folder, same Excel list
        Workspaces w2 = new Workspaces(Prefs.memory(), Prefs.memory(), dir.resolve("m2/x.jar").toString());
        w2.setRoot(dir.resolve("m2"));
        Engine b = V110ProjectTest.engine();
        FileStorage fb = w2.start("p", "staff");
        Workspaces.setMeta(fb.dir(), "letter", "B");
        b.open(fb);
        b.setTargets(excel());
        V110ProjectTest.cap(b, "GET", "/api/users/99", 200);
        V110ProjectTest.cap(b, "GET", "/api/admin/stats", 403);
        b.flush();
        b.close();

        ProjectStore s1 = store(fa.dir(), "Hậu");
        s1.config().putMember(new ProjectConfig.Member("Hậu", ProjectConfig.Role.PIC, List.of("A"), ""));
        s1.config().putMember(new ProjectConfig.Member("Bình", ProjectConfig.Role.MEMBER, List.of("B"), ""));
        s1.config().touch("Hậu");
        ProjectStore s2 = store(fb.dir(), "Bình");
        TestHost h1 = new TestHost(fa.dir());
        TestHost h2 = new TestHost(fb.dir());
        ProjectSync m1 = new ProjectSync(s1, h1);
        ProjectSync m2 = new ProjectSync(s2, h2);

        m1.cycle();
        assertEquals(ProjectSync.State.OK, m1.status().state(), m1.status().text());
        m2.cycle();
        assertEquals(ProjectSync.State.OK, m2.status().state(), m2.status().text());
        m1.cycle();

        ProjectData d1 = m1.data();
        assertEquals(AccStatus.DONE, row(d1, "/api/users/{id}").cell("B").status, "B's result reached the PIC");
        assertEquals(AccStatus.DENIED, row(d1, "/api/admin/stats").cell("B").status);
        ProjectData d2 = m2.data();
        assertEquals(AccStatus.DONE, row(d2, "/api/admin/stats").cell("A").status, "A's result reached the member");
        assertEquals(ProjectConfig.Role.PIC, s2.config().roleOf("Hậu"), "project settings came from the PIC");
        assertEquals(1, d2.allQa().size(), "the PIC's Q&A is visible to the member");
        assertTrue(Files.isRegularFile(s1.mirrorFile()) && Files.isRegularFile(s2.mirrorFile()), "each person's own Excel copy");
        assertFalse(s1.snapshots().isEmpty(), "hourly snapshot");

        // the human sheets were drawn
        List<List<Object>> sum = sheet("Tổng hợp");
        assertTrue(sum.toString().contains("Denied (B) 403"), sum.toString());
        List<List<Object>> qa = sheet("Q&A");
        assertTrue(qa.toString().contains("HYPERLINK(\"https://drive.google.com/file/d/"), qa.toString());
        // the member's Excel copy has the PIC's screenshot (downloaded from the team folder)
        List<themythofcrawl.excel.Xlsx.InPicture> pics = themythofcrawl.excel.Xlsx.readPictures(s2.mirrorFile());
        assertTrue(pics.stream().anyMatch(p -> p.sheet().equals("Q&A")), "teammate's screenshot in my Excel");

        // a client answers in the sheet; someone sets OOS by hand
        int ansCol = qa.get(0).indexOf(ProjectWorkbook.ANSWER_HEADER);
        post("/__edit", Json.write(Map.of("sheet", "Q&A", "row", 2, "col", ansCol + 1, "value", "Đúng thiết kế, B được xem")));
        int bCol = -1;
        for (int i = 0; i < sum.get(0).size(); i++) {
            if (String.valueOf(sum.get(0).get(i)).startsWith("B ·")) {
                bCol = i;
            }
        }
        int ordersRow = -1;
        for (int i = 0; i < sum.size(); i++) {
            if (sum.get(i).contains("/api/orders")) {
                ordersRow = i + 1;
            }
        }
        assertTrue(bCol > 0 && ordersRow > 0, sum.get(0).toString());
        post("/__edit", Json.write(Map.of("sheet", "Tổng hợp", "row", ordersRow, "col", bCol + 1, "value", "OOS (B)")));
        m1.cycle();
        m2.cycle();
        assertEquals(List.of(q.id() + "=Đúng thiết kế, B được xem"), h1.answers, "the answer goes to the Q&A owner");
        assertTrue(h2.answers.isEmpty());
        ProjectData.Cell oos = row(m2.data(), "/api/orders").cell("B");
        assertEquals(AccStatus.OOS, oos.status);
        assertTrue(oos.manual);
        assertEquals("Google Sheet", oos.manualVia);

        // two people set different values on the same cell after both saw the same version -> conflict for the PIC
        String rid = row(m1.data(), "/api/orders").id;
        s1.mark(rid, "A", AccStatus.DONE, "", "Hậu", "JCrawl");
        s2.mark(rid, "A", AccStatus.DENIED, "", "Bình", "JCrawl");
        m1.cycle();
        m2.cycle();
        m1.cycle();
        assertFalse(m1.data().conflicts().isEmpty(), "conflict recorded");
        assertEquals(m1.data().rows().size(), m2.data().rows().size());

        // lost sheet: rebuild from a person's copy
        List<SyncRecord> before = new ArrayList<>(s1.records().values());
        assertTrue(m1.rebuildSheet() >= before.size());
        m1.close();
        m2.close();
    }
}
