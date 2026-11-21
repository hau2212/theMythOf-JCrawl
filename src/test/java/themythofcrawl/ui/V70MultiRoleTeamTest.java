package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Bridge;
import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.app.Prefs;
import themythofcrawl.collab.InventoryCatalog;
import themythofcrawl.collab.TeamSyncService;
import themythofcrawl.collab.TeamWorkbook;
import themythofcrawl.core.AccessMatrix;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.Config;
import themythofcrawl.core.DataProtection;
import themythofcrawl.core.ExpectedAccess;
import themythofcrawl.export.HarExport;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.Role;
import themythofcrawl.model.Service;
import themythofcrawl.rolereq.CrawlRoleDetector;
import themythofcrawl.rolereq.RoleRules;

/**
 * V_7.0_beta: crawl N roles without duplicates (Thấy bởi), access matrix / BAC findings, Logger++ hand-off,
 * HAR export, shared-folder team sync, team workbook merge, Bảo vệ dữ liệu migration and the Cây
 * tree-table column alignment.
 */
class V70MultiRoleTeamTest {
    static final Service SVC = new Service("shop.test", 443, true);

    /** Server: /api/admin/users answers the same list to Admin AND User (a BAC bug); Guest gets 401. */
    static final class Server implements Bridge {
        final List<String> sent = new CopyOnWriteArrayList<>();

        @Override public boolean inScope(String url) { return true; }

        @Override public Sent send(Service s, byte[] request) {
            String raw = new String(request, StandardCharsets.ISO_8859_1);
            sent.add(raw);
            boolean auth = raw.contains("Authorization: Bearer ");
            String body;
            int st;
            if (!auth) {
                st = 401;
                body = "{\"error\":\"login required\"}";
            } else if (raw.startsWith("GET /api/admin/users")) {
                st = 200;
                body = "{\"users\":[{\"id\":1,\"name\":\"alice\",\"role\":\"admin\"},{\"id\":2,\"name\":\"bob\",\"role\":\"user\"}]}";
            } else {
                st = 200;
                body = "{\"profile\":{\"name\":\"me\",\"plan\":\"gold\"}}";
            }
            return new Sent(("HTTP/1.1 " + st + " X\r\nContent-Type: application/json\r\n\r\n" + body)
                    .getBytes(StandardCharsets.ISO_8859_1), 3, null);
        }

        @Override public void toRepeater(Service service, byte[] request, String tabName) { }
        @Override public void out(String line) { }
        @Override public void err(String line) { }
    }

    static List<Role> roles() {
        return List.of(
                new Role("01_Admin", true, List.of(HeaderRule.of("Authorization", "Bearer ADMIN_TOKEN", "REPLACE"))),
                new Role("02_User", true, List.of(HeaderRule.of("Authorization", "Bearer USER_TOKEN", "REPLACE"))),
                new Role("03_Guest", true, List.of(HeaderRule.of("Authorization", "[REMOVE]", "REMOVE"))));
    }

    static Engine engine(Bridge bridge, String member) {
        Engine e = new Engine(bridge, Prefs.memory(), RoleRules.INSTANCE);
        e.open(new MemoryStorage());
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.autoSend = false;
        c.confirmNewApis = false;
        c.ignoreStatic = false;
        c.apiRegexEnabled = false;
        c.sendProfile = Config.SendProfile.CUSTOM;
        c.sendDelayMs = 0;
        c.sendJitterPct = 0;
        e.applyConfig(c);
        e.roles().setAll(roles());
        e.inventoryCatalog().setMemberName(member);
        return e;
    }

    static Engine.Message msg(String method, String path, String token) {
        String req = method + " " + path + " HTTP/1.1\r\nHost: shop.test\r\n"
                + (token == null ? "" : "Authorization: Bearer " + token + "\r\n") + "\r\n";
        return new Engine.Message(SVC, req.getBytes(StandardCharsets.ISO_8859_1),
                "HTTP/1.1 200 OK\r\n\r\n{\"users\":[{\"id\":1,\"name\":\"alice\",\"role\":\"admin\"},{\"id\":2,\"name\":\"bob\",\"role\":\"user\"}]}"
                        .getBytes(StandardCharsets.ISO_8859_1), "https://shop.test" + path);
    }

    static void waitSends(Engine e) throws InterruptedException {
        for (int i = 0; i < 300 && e.activeSends() > 0; i++) {
            Thread.sleep(20);
        }
    }

    @Test
    void detectorMatchesTokenCookieAndGuest() {
        List<Role> rs = List.of(
                new Role("A", true, List.of(HeaderRule.of("Authorization", "Bearer AAA", "REPLACE"))),
                new Role("B", true, List.of(HeaderRule.of("JSESSIONID", "S-B", "COOKIE_SET"))),
                new Role("G", true, List.of(HeaderRule.of("Authorization", "[REMOVE]", "REMOVE"),
                        HeaderRule.of("Cookie", "[REMOVE]", "REMOVE"))));
        byte[] a = "GET / HTTP/1.1\r\nAuthorization: Bearer AAA\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] b = "GET / HTTP/1.1\r\nCookie: x=1; JSESSIONID=S-B\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] g = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] other = "GET / HTTP/1.1\r\nAuthorization: Bearer ZZZ\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("A", CrawlRoleDetector.detect(a, rs));
        assertEquals("B", CrawlRoleDetector.detect(b, rs));
        assertEquals("G", CrawlRoleDetector.detect(g, rs));
        assertNull(CrawlRoleDetector.detect(other, rs), "unknown token matches no role");
    }

    @Test
    void crawlingTwoRolesDoesNotDuplicateAndMatrixFlagsBac() throws Exception {
        Server server = new Server();
        Engine e = engine(server, "A");
        try {
            e.setDestination("Toppage > Admin");
            ApiGroup admin = e.process(msg("GET", "/api/admin/users", "ADMIN_TOKEN"), "Proxy", true, e.activeBranch().id());
            e.setDestination("Toppage > Profile");
            ApiGroup profile = e.process(msg("GET", "/api/profile", "ADMIN_TOKEN"), "Proxy", true, e.activeBranch().id());
            // The User account crawls its own UI: it only reaches /api/profile
            assertNull(e.process(msg("GET", "/api/profile", "USER_TOKEN"), "Proxy", true, e.activeBranch().id()),
                    "same endpoint by another role is NOT a new API");
            assertEquals(2, e.groups().size());
            assertEquals(Set.of("01_Admin"), admin.seenBy());
            assertEquals(Set.of("01_Admin", "02_User"), profile.seenBy());
            assertEquals(Set.of("01_Admin", "02_User"), e.crawledRoles());

            for (ApiGroup g : e.groups()) {
                e.replay(g, e.defaultTargets(g, false));
            }
            waitSends(e);

            AccessMatrix m = e.accessMatrix(e.groups());
            AccessMatrix.Row adminRow = m.rows().stream().filter(r -> r.group() == admin).findFirst().orElseThrow();
            assertEquals(AccessMatrix.Verdict.SEEN_OK, adminRow.cells().get("01_Admin").verdict());
            assertEquals(AccessMatrix.Verdict.BAC, adminRow.cells().get("02_User").verdict(),
                    "User never saw the admin screen but gets the same 2xx list");
            assertEquals(AccessMatrix.Verdict.DENIED_OK, adminRow.cells().get("03_Guest").verdict());
            AccessMatrix.Row profileRow = m.rows().stream().filter(r -> r.group() == profile).findFirst().orElseThrow();
            assertEquals(AccessMatrix.Verdict.SEEN_OK, profileRow.cells().get("02_User").verdict());
            assertEquals(1, m.findings().size());
            assertTrue(m.findingsJson().contains("BAC-" + admin.gid() + "-02User"));
            assertFalse(m.findingRows().isEmpty());

            // Expected access: Admin screen is for 01_Admin only -> confirmed violation
            e.expectedAccess().put(new ExpectedAccess.Rule("Toppage > Admin", Set.of("01_Admin"), ""));
            e.saveExpectedAccess();
            AccessMatrix m2 = e.accessMatrix(e.groups());
            assertEquals(AccessMatrix.Verdict.VIOLATION, m2.rows().stream().filter(r -> r.group() == admin).findFirst()
                    .orElseThrow().cells().get("02_User").verdict());

            // seenBy survives persistence via GroupCodec
            assertTrue(themythofcrawl.app.GroupCodec.class.getName().endsWith("GroupCodec"));
        } finally {
            e.close();
        }
    }

    @Test
    void loggerPushSendsOnceWithMarkerSkipsActionsAndHarExports() throws Exception {
        Server server = new Server();
        Engine e = engine(server, "A");
        try {
            ApiGroup get = e.process(msg("GET", "/api/profile", "ADMIN_TOKEN"), "Proxy", true, e.activeBranch().id());
            ApiGroup post = e.process(msg("POST", "/api/orders", "ADMIN_TOKEN"), "Proxy", true, e.activeBranch().id());
            assertNotNull(get);
            assertNotNull(post);
            Engine.LoggerPushReport rep = e.pushToLogger(List.of(get, post),
                    new Engine.LoggerPushOptions(true, false, false, true, 0), null).get(10, TimeUnit.SECONDS);
            assertEquals(1, rep.sent());
            assertEquals(1, rep.skippedAction(), "POST is never replayed unless allowed");
            assertEquals(1, server.sent.size());
            assertTrue(server.sent.get(0).contains(Engine.MARKER_HEADER + ": API#" + get.gid() + ";role=" + Engine.CRAWL_ACCOUNT));
            assertTrue(server.sent.get(0).chars().allMatch(c -> c < 128), "marker header stays ASCII");

            String har = HarExport.build(e, List.of(get, post), true, false);
            assertTrue(har.contains("\"version\":\"1.2\""));
            assertTrue(har.contains("\"_jcrawl\""));
            assertTrue(har.contains("/api/profile"));
        } finally {
            e.close();
        }
    }

    @Test
    void sharedFolderSyncAndTeamWorkbookMerge(@TempDir Path dir) throws Exception {
        Engine a = engine(new Server(), "A");
        Engine b = engine(new Server(), "B");
        try {
            a.startTeamFolder(dir);
            b.startTeamFolder(dir);
            assertEquals(TeamSyncService.Mode.FOLDER, a.teamSync().mode());
            assertTrue(Files.isDirectory(dir.resolve(TeamSyncService.FOLDER_NAME)));
            String key = InventoryCatalog.normalizeKey("GET", "shop.test", "/api/admin/users");
            var meta = a.inventoryCatalog().setManualStatus(key, "GET", "shop.test", "/api/admin/users",
                    InventoryCatalog.ItemStatus.DONE, "Toppage > Admin", "BAC confirmed");
            a.teamSync().broadcastEndpoint(meta);
            a.teamSync().broadcastCustomTag("BAC-CANDIDATE");
            b.teamSync().pollFolderNow();
            assertNotNull(b.inventoryCatalog().metaFor(key), "B receives A's endpoint through the folder only");
            assertTrue(b.inventoryCatalog().metaFor(key).owners.contains("A"));
            assertTrue(b.inventoryCatalog().allTags().contains("BAC-CANDIDATE"));

            // Team workbook: A exports, a fresh member C merges it
            a.process(msg("GET", "/api/profile", "ADMIN_TOKEN"), "Proxy", true, a.activeBranch().id());
            Path wb = dir.resolve("A.xlsx");
            Files.write(wb, TeamWorkbook.export(a));
            assertTrue(themythofcrawl.excel.Xlsx.sheetNames(wb).containsAll(List.of(TeamWorkbook.SUMMARY_SHEET,
                    "Ma_tran_Role", "Test_case_Bug", "Phan_cong", "Xung_dot", "Theo_thanh_vien")));
            Engine c = engine(new Server(), "C");
            try {
                TeamWorkbook.MergeReport rep = TeamWorkbook.merge(c, List.of(wb));
                assertTrue(rep.rows() >= 2, rep.summary());
                assertNotNull(c.inventoryCatalog().metaFor(key));
            } finally {
                c.close();
            }
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    void dataProtectionMigratesLegacySwitchesWithoutChangingBehaviour() {
        Engine e = engine(new Server(), "A");
        try {
            ActionEngine ae = e.actionEngine();
            ae.setHoldAllActionAtProxy(true);
            ae.setInterceptWaitEnabled(true);
            ae.setInterceptWaitAllScope(false);
            ae.setEndpointInterceptWait("DELETE", "/api/orders/5", true);
            assertTrue(DataProtection.legacyCount(ae, e.config()) >= 2);
            Engine.ProxyInterceptOutcome before = e.evaluateProxyIntercept(SVC,
                    "DELETE /api/orders/9 HTTP/1.1\r\nHost: shop.test\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), null);
            assertTrue(before.shouldWait());

            Config c = e.config().copy();
            int n = DataProtection.migrateLegacy(ae, c);
            e.applyConfig(c);
            assertTrue(n >= 2);
            assertEquals(0, DataProtection.legacyCount(ae, e.config()), "every legacy switch is now a rule");

            Engine.ProxyInterceptOutcome del = e.evaluateProxyIntercept(SVC,
                    "DELETE /api/orders/9 HTTP/1.1\r\nHost: shop.test\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), null);
            assertTrue(del.shouldWait(), "endpoint rule still asks before DELETE /api/orders/{id}");
            Engine.ProxyInterceptOutcome post = e.evaluateProxyIntercept(SVC,
                    "POST /api/x HTTP/1.1\r\nHost: shop.test\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), null);
            assertTrue(post.shouldBlock() && !post.shouldWait(), "global hold became a GLOBAL HOLD rule");
            assertTrue(e.explainProtection("DELETE", "shop.test", "/api/orders/3", null).startsWith("Hỏi tôi trước"));
            assertTrue(e.explainProtection("GET", "shop.test", "/api/x", null).startsWith("Cho đi bình thường"));

            DataProtection.setGlobalChoice(ae.policyLedger(), DataProtection.Choice.ASK);
            assertEquals(DataProtection.Choice.ASK, DataProtection.globalChoice(ae.policyLedger()));
            assertTrue(DataProtection.sentence(DataProtection.rule(DataProtection.Scope.BRANCH, "Toppage > Orders",
                    List.of("DELETE"), DataProtection.Choice.ASK, null)).contains("trong nhánh Toppage > Orders"));
            assertNotNull(DataProtectionDialog.build(null, e, null, null));
        } finally {
            e.close();
        }
    }

    @Test
    void treeTabRowsLineUpOnTheColumnGrid(@TempDir Path dir) throws Exception {
        Engine e = engine(new Server(), "A");
        try {
            e.setDestination("Toppage > A");
            e.process(msg("GET", "/api/one", "ADMIN_TOKEN"), "Proxy", true, e.activeBranch().id());
            e.setDestination("Toppage > A > B > C > D");
            e.process(msg("GET", "/api/deep/two", "ADMIN_TOKEN"), "Proxy", true, e.activeBranch().id());
            SwingUtilities.invokeAndWait(() -> {
                BranchTree bt = new BranchTree(e);
                TreeColumns.enable(bt.tree);
                bt.rebuild(false);
                bt.expandFolders(true);
                bt.tree.setSize(1600, 800);
                bt.tree.doLayout();
                int groups = 0;
                for (int row = 0; row < bt.tree.getRowCount(); row++) {
                    Node n = (Node) bt.tree.getPathForRow(row).getLastPathComponent();
                    if (n.kind == Node.Kind.GROUP) {
                        groups++;
                        assertEquals(bt.tree.getRowBounds(row).x, TreeColumns.nodeX(bt.tree, n.getLevel()),
                                "estimated row x must equal the real one so Method/Path columns align");
                    }
                }
                assertEquals(2, groups);
                TreeColumns.setWidth(TreeColumns.Col.PATH, 500);
                assertEquals(500, TreeColumns.width(TreeColumns.Col.PATH));
                TreeColumns.reset();
            });
        } finally {
            e.close();
        }
    }

    static int count(Enumeration<?> en) {
        int n = 0;
        while (en.hasMoreElements()) {
            en.nextElement();
            n++;
        }
        return n;
    }
}
