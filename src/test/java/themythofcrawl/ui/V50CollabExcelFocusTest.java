package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Bridge;
import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.app.Prefs;
import themythofcrawl.collab.ConflictRecord;
import themythofcrawl.collab.GoogleSheetsBridge;
import themythofcrawl.collab.InventoryCatalog;
import themythofcrawl.collab.InventoryCatalog.InventoryRow;
import themythofcrawl.collab.InventoryCatalog.ItemStatus;
import themythofcrawl.collab.InventoryCatalog.ViewContext;
import themythofcrawl.collab.TeamSyncService;
import themythofcrawl.core.Config;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Service;
import themythofcrawl.store.Workspaces;

/**
 * End-to-end verification suite for V_5.0_beta:
 * 1) Focus Mode 2.0 defaults to recursive=true (shows selected branch AND all child paths underneath).
 * 2) Auto-Inventory 7 contexts work with ZERO Excel file loaded + OOS status + Custom Tag CRUD.
 * 3) Colored 4-sheet Final .xlsx export (Master_API_List, CRUD_Matrix, By_Branch_Screen, Team_Summary).
 * 4) Real-time TeamSyncService (Host + Client on ephemeral port) syncs statuses, custom tags, live presence,
 *    pre-flight Action locks, and detects/resolves Git-style Conflicts (Done A, B).
 * 5) Search & Jump-to-Path from Excel/Inventory navigates straight to TreeTab and selects the exact API/branch.
 * 6) GoogleSheetsBridge Apps Script template includes doGet/doPost & semantic cell color palette.
 */
class V50CollabExcelFocusTest {

    private static final class StubBridge implements Bridge {
        @Override public boolean inScope(String url) { return true; }
        @Override public Sent send(Service s, byte[] req) {
            return new Sent("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.ISO_8859_1), 5, null);
        }
        @Override public void toRepeater(Service service, byte[] request, String tabName) { }
        @Override public void out(String line) { }
        @Override public void err(String line) { }
    }

    private static Engine newEngine(String member) {
        Prefs prefs = Prefs.memory();
        Engine e = new Engine(new StubBridge(), prefs, null);
        e.open(new MemoryStorage());
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.autoSend = false;
        c.confirmNewApis = false;
        c.ignoreStatic = false;
        e.applyConfig(c);
        e.inventoryCatalog().setMemberName(member);
        return e;
    }

    private static byte[] req(String method, String path) {
        return (method + " " + path + " HTTP/1.1\r\nHost: api.shop.vn\r\nContent-Length: 0\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] resp(int status) {
        return ("HTTP/1.1 " + status + " OK\r\nContent-Length: 2\r\n\r\n{}").getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void focusMode2IncludesChildBranchesAndJumpFromExcelWorks(@TempDir Path dir) throws Exception {
        Engine engine = newEngine("A");
        Workspaces ws = new Workspaces(Prefs.memory(), Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        try {
            Service svc = new Service("api.shop.vn", 443, true);
            var parentBranch = engine.startNewBranch("Toppage > Orders");
            var childBranch = engine.startNewBranch("Toppage > Orders > EditModal");
            var otherBranch = engine.startNewBranch("Toppage > Users");

            engine.submitManual(List.of(new Engine.Message(svc, req("GET", "/api/orders"), resp(200), "https://api.shop.vn/api/orders")),
                    false, parentBranch.id());
            engine.submitManual(List.of(new Engine.Message(svc, req("PUT", "/api/orders/101"), resp(200), "https://api.shop.vn/api/orders/101")),
                    false, childBranch.id());
            engine.submitManual(List.of(new Engine.Message(svc, req("GET", "/api/users"), resp(403), "https://api.shop.vn/api/users")),
                    false, otherBranch.id());

            MainPanel[] mpRef = new MainPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                mpRef[0] = new MainPanel(engine, ws, new UiSmokeTest.HeadlessHost(), "v50-focus");
                MainPanel mp = mpRef[0];

                // Focus Mode 2.0 on parent branch with recursive=true must show BOTH parent & child APIs, hiding unrelated Users branch
                mp.treeTab.setFocusBranch("Toppage > Orders", true);
                assertTrue(mp.treeTab.tree.focusRecursive(), "Focus Mode 2.0 must default to recursive=true");
                mp.treeTab.tree.tree.setSelectionRow(0);
                List<ApiGroup> visibleUnderFocus = mp.treeTab.tree.selectedGroups();
                assertEquals(2, visibleUnderFocus.size(), "Parent + child branch APIs must both be visible under Focus Mode 2.0");
                assertTrue(visibleUnderFocus.stream().anyMatch(g -> g.path().contains("/orders/101")),
                        "Child branch API /api/orders/101 MUST be visible when focusing parent branch");

                // Jump-to-Path from Excel/Inventory straight to unrelated Users API on TreeTab
                ApiGroup usersApi = engine.groups().stream().filter(g -> g.path().equals("/api/users")).findFirst().orElseThrow();
                mp.jumpToTreeBranchOrApi(usersApi, usersApi.branch(), false);
                assertEquals("Toppage > Users", mp.treeTab.tree.selectedBranch(),
                        "Jump-to-Path must navigate TreeTab to the exact branch containing the API");
                mp.dispose();
            });
        } finally {
            engine.close();
        }
    }

    @Test
    void autoInventory7ContextsOosTagsAndColoredFinalXlsx(@TempDir Path dir) throws Exception {
        Engine engine = newEngine("A");
        try {
            Service svc = new Service("api.shop.vn", 443, true);
            var ordersB = engine.startNewBranch("Toppage > Orders");
            var detailB = engine.startNewBranch("Toppage > Orders > Detail");

            // Crawl WITHOUT any Excel file loaded!
            engine.submitManual(List.of(
                    new Engine.Message(svc, req("GET", "/api/v1/orders/101"), resp(200), "https://api.shop.vn/api/v1/orders/101"),
                    new Engine.Message(svc, req("POST", "/api/v1/orders"), resp(201), "https://api.shop.vn/api/v1/orders"),
                    new Engine.Message(svc, req("DELETE", "/api/v1/orders/102"), resp(400), "https://api.shop.vn/api/v1/orders/102")
            ), false, ordersB.id());
            engine.submitManual(List.of(
                    new Engine.Message(svc, req("GET", "/api/v1/orders/202"), resp(200), "https://api.shop.vn/api/v1/orders/202"),
                    new Engine.Message(svc, req("GET", "/api/v1/health"), resp(200), "https://api.shop.vn/api/v1/health")
            ), false, detailB.id());

            long dl = System.currentTimeMillis() + 3000;
            while (engine.groups().size() < 5 && System.currentTimeMillis() < dl) {
                Thread.sleep(15);
            }
            assertEquals(5, engine.groups().size(), "All 5 submitted manual APIs must be captured");

            InventoryCatalog cat = engine.inventoryCatalog();
            // Mark /api/v1/health as OOS (Out of Scope) + add Custom Tag "MyCustomTag"
            String healthKey = InventoryCatalog.normalizeKey("GET", "api.shop.vn", "/api/v1/health");
            cat.setManualStatus(healthKey, "GET", "api.shop.vn", "/api/v1/health", ItemStatus.OOS, "Toppage > Orders > Detail", "Static probe");
            assertTrue(cat.addCustomTag("MyCustomTag"));
            String postKey = InventoryCatalog.normalizeKey("POST", "api.shop.vn", "/api/v1/orders");
            cat.toggleTagOnEndpoint(postKey, "POST", "api.shop.vn", "/api/v1/orders", "MyCustomTag");

            // Verify all 7 contexts synthesize non-empty rows cleanly
            for (ViewContext vc : ViewContext.values()) {
                List<InventoryRow> rows = cat.synthesize(engine, vc, null, null, null);
                assertEquals(4, rows.size(), "Expected 4 normalized endpoints in context " + vc);
            }

            // Verify statuses: Done (A), Info (A), OOS (A) + shared screen detection on /api/v1/orders/{id}
            List<InventoryRow> all = cat.synthesize(engine, ViewContext.PATH_TEMPLATE, null, null, null);
            InventoryRow getOrder = all.stream().filter(r -> r.method().equals("GET") && r.pathTemplate().contains("/orders/{id}")).findFirst().orElseThrow();
            assertEquals(ItemStatus.DONE, getOrder.status());
            assertEquals("Done (A)", getOrder.statusDisplay());
            assertEquals(2, getOrder.variantCount(), "101 and 202 must collapse into 2 variants of /orders/{id}");
            assertTrue(getOrder.sharedAcrossScreens(), "Endpoint seen in 2 branches must be flagged sharedAcrossScreens");

            InventoryRow delOrder = all.stream().filter(r -> r.method().equals("DELETE")).findFirst().orElseThrow();
            assertEquals(ItemStatus.INFO, delOrder.status());
            assertEquals("Info (A)", delOrder.statusDisplay());

            InventoryRow health = all.stream().filter(r -> r.pathTemplate().contains("/health")).findFirst().orElseThrow();
            assertEquals(ItemStatus.OOS, health.status());
            assertEquals("OOS (A)", health.statusDisplay());

            // Verify Custom Tag filter
            List<InventoryRow> tagged = cat.synthesize(engine, ViewContext.TIMELINE, null, "MyCustomTag", null);
            assertEquals(1, tagged.size());
            assertEquals("POST", tagged.get(0).method());

            // Verify Final Colored 4-Sheet .xlsx export for Microsoft Excel Online
            byte[] xlsxBytes = cat.exportFinalColoredXlsx(engine);
            assertTrue(xlsxBytes.length > 1500, "Final .xlsx must be valid non-empty zip");
            Path out = dir.resolve("final-report.xlsx");
            Files.write(out, xlsxBytes);
            List<String> sheetNames = Xlsx.sheetNames(out);
            assertEquals(List.of("Master_API_List", "CRUD_Matrix", "By_Branch_Screen", "Team_Summary"), sheetNames);
        } finally {
            engine.close();
        }
    }

    @Test
    void teamSyncHubClientRealTimeSyncActionLockAndGitConflictResolution() throws Exception {
        Engine engineA = newEngine("A");
        Engine engineB = newEngine("B");
        try {
            TeamSyncService hostA = engineA.teamSync();
            TeamSyncService clientB = engineB.teamSync();

            // A starts Host on ephemeral port 0 with passcode
            hostA.startHost(0, "secret123");
            assertTrue(hostA.boundPort() > 0);
            hostA.updateLocalPresence("Toppage > Orders", "POST /api/orders");

            // B connects as Client
            clientB.connectClient("http://127.0.0.1:" + hostA.boundPort(), "secret123");
            clientB.updateLocalPresence("Toppage > Users", "GET /api/users");

            // A creates custom tag + marks POST /api/orders as DONE (200)
            engineA.inventoryCatalog().addCustomTag("TeamTagX");
            hostA.broadcastCustomTag("TeamTagX");
            String orderKey = InventoryCatalog.normalizeKey("POST", "api.shop.vn", "/api/orders");
            var metaA = engineA.inventoryCatalog().setManualStatus(
                    orderKey, "POST", "api.shop.vn", "/api/orders", ItemStatus.DONE, "Toppage > Orders", "OK from A");
            metaA.httpStatus = 200;
            hostA.broadcastEndpoint(metaA);
            hostA.broadcastActionLock("DELETE", "api.shop.vn", "/api/orders/55", "FORWARD");

            // B polls -> receives TeamTagX, Presence of A, POST /api/orders Done (A), and ActionLock on DELETE
            clientB.pollOnceNow();
            assertTrue(engineB.inventoryCatalog().allTags().contains("TeamTagX"), "Custom tag must sync to B");
            assertEquals(List.of("A"), clientB.peersOnBranch("Toppage > Orders"), "Live presence must show A on Orders");
            TeamSyncService.ActionNotice lock = clientB.checkRecentTeammateAction("DELETE", "api.shop.vn", "/api/orders/99");
            assertNotNull(lock, "Pre-flight Action Lock must warn B about A's recent DELETE /api/orders/{id}");
            assertEquals("A", lock.member());

            // Now simulate Git-style Conflict: B locally gets 400 INFO on PUT /api/orders/{id}, A also gets 200 DONE on same endpoint
            String putKey = InventoryCatalog.normalizeKey("PUT", "api.shop.vn", "/api/orders/{id}");
            engineB.inventoryCatalog().setManualStatus(putKey, "PUT", "api.shop.vn", "/api/orders/{id}",
                    ItemStatus.INFO, "Toppage > Cart", "400 missing field");
            ConflictRecord cr = engineB.inventoryCatalog().applyRemoteUpdate(
                    "A", "PUT", "api.shop.vn", "/api/orders/{id}",
                    ItemStatus.DONE, 200, "Toppage > Orders", Set.of("TeamTagX"), "200 OK", System.currentTimeMillis());
            assertNotNull(cr, "Differing status/branch between A and B must raise Git-style ConflictRecord");
            assertEquals(1, engineB.inventoryCatalog().conflictCount());

            // Resolve with MERGE_BOTH -> Done (B, A) preserving both branches & tags
            var resolved = engineB.inventoryCatalog().resolveConflict(putKey, ConflictRecord.Resolution.MERGE_BOTH);
            assertNotNull(resolved);
            assertEquals(ItemStatus.DONE, resolved.status);
            assertTrue(resolved.owners.containsAll(Set.of("A", "B")));
            assertEquals(0, engineB.inventoryCatalog().conflictCount());
            assertTrue(resolved.tags.contains("TeamTagX"));

            // Sanity-check Google Sheets Apps Script template
            assertTrue(GoogleSheetsBridge.APPS_SCRIPT_TEMPLATE.contains("function doGet"));
            assertTrue(GoogleSheetsBridge.APPS_SCRIPT_TEMPLATE.contains("function doPost"));
            assertTrue(GoogleSheetsBridge.APPS_SCRIPT_TEMPLATE.contains("#DCFCE7"));
            assertTrue(GoogleSheetsBridge.APPS_SCRIPT_TEMPLATE.contains("OOS"));
        } finally {
            engineB.close();
            engineA.close();
        }
    }
}
