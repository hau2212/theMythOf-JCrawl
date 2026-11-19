package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import javax.swing.Icon;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Bridge;
import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.Config;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ApiTagCatalog;
import themythofcrawl.model.GroupCluster;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.NodeTypeCatalog;
import themythofcrawl.model.Role;
import themythofcrawl.model.Service;
import themythofcrawl.place.Decisions;
import themythofcrawl.store.FileStorage;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.kit.Breadcrumb;
import themythofcrawl.ui.theme.Theme;

/**
 * End-to-end verification suite for all 7 V_2.0_beta features:
 * 1. Uniform square/rectangular 24px Breadcrumb & Header
 * 2. Focus Mode (exact branch vs recursive isolation)
 * 3. Visual Clusters (ZERO mutation of ApiGroup.branch, Copy STT+Path, PlacementBook)
 * 4. NodeTypeCatalog CRUD, custom icons & persistence
 * 5. Redesigned RolesTab (SquareStatusRenderer, Role.Tier, live probeRole)
 * 6. Duplicate Lineage, SmartAdvice, Promote to Origin #1, Keep Origin & Hide #2+, Move Copy
 * 7. API Tag System & REFRESH Auto-Detection + M1–M4 RefreshPolicy
 */
class V2BetaFeaturesTest {
    private static final class StubBridge implements Bridge {
        volatile int status = 200;

        @Override
        public boolean inScope(String url) {
            return true;
        }

        @Override
        public Sent send(Service s, byte[] request) {
            String resp = "HTTP/1.1 " + status + " OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}";
            return new Sent(resp.getBytes(StandardCharsets.ISO_8859_1), 5, null);
        }

        @Override
        public void toRepeater(Service s, byte[] request, String tabName) {
        }

        @Override
        public void out(String line) {
        }

        @Override
        public void err(String line) {
        }
    }

    private static Engine newEngine(StubBridge bridge, Prefs prefs) {
        Engine e = new Engine(bridge, prefs, null);
        e.open(new MemoryStorage());
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.autoSend = false;
        c.confirmNewApis = false;
        e.applyConfig(c);
        return e;
    }

    @Test
    void feature1UniformBreadcrumbAnd24pxHeader(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        prefs.set(Workspaces.PREF_ROOT, dir.toString());
        Workspaces ws = new Workspaces(prefs, null, null);
        Engine e = newEngine(new StubBridge(), prefs);
        e.setDestination("Toppage > Orders > Detail");
        e.setPathNodeType("Toppage > Orders", "tab");
        e.setPathNodeType("Toppage > Orders > Detail", "button");

        SwingUtilities.invokeAndWait(() -> {
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v2f1");
            ui.tick(true);
            try {
                Breadcrumb bc = ui.dest;
                assertEquals("Toppage > Orders > Detail", bc.path());
                Dimension bcPref = bc.getPreferredSize();
                assertEquals(Theme.current().scaled(24), bcPref.height,
                        "Breadcrumb must maintain uniform 24px scaled height");
                assertTrue(ui.headerPanel().getPreferredSize().width <= 1100,
                        "Header preferred width must fit 1100px, was " + ui.headerPanel().getPreferredSize().width);
            } finally {
                ui.dispose();
            }
        });
        e.close();
    }

    @Test
    void feature2FocusModeExactVsRecursiveIsolation(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        prefs.set(Workspaces.PREF_ROOT, dir.toString());
        Workspaces ws = new Workspaces(prefs, null, null);
        Engine e = newEngine(new StubBridge(), prefs);
        e.setDestination("Toppage > Orders");
        e.process(UiSmokeTest.msg("GET", "/api/v1/orders"), "Proxy", true, e.activeBranch().id());
        e.setDestination("Toppage > Orders > Detail");
        e.process(UiSmokeTest.msg("GET", "/api/v1/orders/1"), "Proxy", true, e.activeBranch().id());
        e.setDestination("Toppage > Users");
        e.process(UiSmokeTest.msg("GET", "/api/v1/users"), "Proxy", true, e.activeBranch().id());

        SwingUtilities.invokeAndWait(() -> {
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v2f2");
            ui.tick(true);
            try {
                GroupTableModel tm = new GroupTableModel(e, g -> 0);
                tm.setAll(e.groups());
                assertEquals(3, tm.getRowCount(), "Normal mode shows all 3 APIs");

                // Exact branch isolation: only Toppage > Orders (excludes Toppage > Orders > Detail)
                tm.setBranchFilter("Toppage > Orders", true);
                assertEquals(1, tm.getRowCount(), "Exact Focus Mode must isolate strictly to Toppage > Orders");
                assertEquals("/api/v1/orders", tm.at(0).path());

                // Recursive branch isolation: Toppage > Orders + Toppage > Orders > Detail
                tm.setBranchFilter("Toppage > Orders", false);
                assertEquals(2, tm.getRowCount(), "Recursive Focus Mode must include child branch APIs");

                // BranchTree Focus Mode toggling
                BranchTree tree = ui.api.tree;
                tree.setFocus("Toppage > Orders", false);
                assertEquals("Toppage > Orders", tree.focusPath());
                assertFalse(tree.focusRecursive());

                tree.setFocus("Toppage > Orders", true);
                assertTrue(tree.focusRecursive());

                tree.clearFocus();
                assertNull(tree.focusPath());
            } finally {
                ui.dispose();
            }
        });
        e.close();
    }

    @Test
    void feature3VisualClustersNeverMutateBranchCopyOrPlacement(@TempDir Path dir) {
        Prefs prefs = Prefs.memory();
        prefs.set(Workspaces.PREF_ROOT, dir.toString());
        Workspaces ws = new Workspaces(prefs, null, null);
        FileStorage st = ws.start("p-cluster", "ClusterProj");

        StubBridge b = new StubBridge();
        Engine e = new Engine(b, prefs, null);
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.autoSend = false;
        c.confirmNewApis = false;
        e.applyConfig(c);
        e.open(st);

        e.setDestination("Toppage > Checkout");
        ApiGroup g1 = e.process(UiSmokeTest.msg("POST", "/api/v1/cart"), "Proxy", true, e.activeBranch().id());
        ApiGroup g2 = e.process(UiSmokeTest.msg("POST", "/api/v1/checkout"), "Proxy", true, e.activeBranch().id());
        assertNotNull(g1);
        assertNotNull(g2);

        String branchBefore1 = g1.branch();
        String branchBefore2 = g2.branch();
        String copyBefore1 = e.copyLabel(g1, true);
        String copyBefore2 = e.copyLabel(g2, true);
        String placeBefore1 = e.book().get(g1.bookKey()).place;

        // Create cluster, rename, collapse
        GroupCluster cl = e.createCluster("Toppage > Checkout", "Luồng Thanh Toán", "info", List.of(g1, g2));
        assertNotNull(cl);
        assertEquals(2, cl.gids().size());
        assertEquals(cl.id(), e.clusterOf(g1.gid()).id());
        assertTrue(e.renameCluster(cl.id(), "Thanh Toán Chính", "accent"));
        assertTrue(e.toggleClusterCollapsed(cl.id()));
        assertTrue(e.clusterById(cl.id()).collapsed());

        // CRITICAL INVARIANT: branch, Copy STT+Path, and PlacementBook MUST NOT change!
        assertEquals(branchBefore1, g1.branch(), "Cluster MUST NOT mutate ApiGroup.branch()");
        assertEquals(branchBefore2, g2.branch(), "Cluster MUST NOT mutate ApiGroup.branch()");
        assertEquals(copyBefore1, e.copyLabel(g1, false), "Cluster MUST NOT mutate Copy STT+Path");
        assertEquals(copyBefore2, e.copyLabel(g2, false), "Cluster MUST NOT mutate Copy STT+Path");
        assertEquals(placeBefore1, e.book().get(g1.bookKey()).place, "Cluster MUST NOT mutate PlacementBook");
        e.close();

        // Reload workspace from disk and verify clusters persist cleanly
        Engine e2 = new Engine(b, prefs, null);
        e2.open(ws.start("p-cluster", "ClusterProj"));
        List<GroupCluster> reloaded = e2.clustersInBranch("Toppage > Checkout");
        assertEquals(1, reloaded.size());
        assertEquals("Thanh Toán Chính", reloaded.get(0).name());
        assertEquals(2, reloaded.get(0).gids().size());
        assertTrue(reloaded.get(0).collapsed());

        // Dissolve cluster keeps both APIs intact
        assertTrue(e2.dissolveCluster(reloaded.get(0).id()));
        assertTrue(e2.clusters().isEmpty());
        assertEquals(2, e2.groups().size());
        e2.close();
    }

    @Test
    void feature4NodeTypeCatalogCrudCustomIconsAndPersistence() {
        NodeTypeCatalog cat = new NodeTypeCatalog();
        assertTrue(cat.all().size() >= 7, "Should include 7 built-in UI element types");
        assertNotNull(cat.byId("folder"));
        assertNotNull(cat.byId("link"));
        assertNotNull(cat.byId("button"));
        assertNotNull(cat.byId("icon"));
        assertNotNull(cat.byId("tab"));
        assertNotNull(cat.byId("modal"));
        assertNotNull(cat.byId("form"));

        // Add custom NodeType with monogram icon
        NodeTypeCatalog.NodeType webhook = new NodeTypeCatalog.NodeType(
                "webhook", "Webhook", "monogram", "critical", "WH", "", false);
        cat.upsert(webhook);
        assertTrue(cat.contains("webhook"));

        // JSON round-trip
        String json = cat.toJson();
        NodeTypeCatalog cat2 = new NodeTypeCatalog();
        cat2.loadJson(json);
        NodeTypeCatalog.NodeType loaded = cat2.byId("webhook");
        assertNotNull(loaded);
        assertEquals("Webhook", loaded.label());
        assertEquals("WH", loaded.monogram());

        // Icon rendering for built-in and custom monogram types
        Theme th = Theme.current();
        for (NodeTypeCatalog.NodeType nt : cat2.all()) {
            Icon ic = Icons.nodeIcon(nt, th, 14);
            assertNotNull(ic);
            assertEquals(14, ic.getIconWidth());
            assertEquals(14, ic.getIconHeight());
        }

        // Cannot remove built-in; can remove custom
        assertFalse(cat2.remove("folder"));
        assertTrue(cat2.remove("webhook"));
        assertFalse(cat2.contains("webhook"));

        // Engine path -> NodeType binding + heuristic fallback
        Engine e = newEngine(new StubBridge(), Prefs.memory());
        e.setPathNodeType("Toppage > LoginBtn", "button");
        assertEquals("button", e.nodeTypeOf("Toppage > LoginBtn").id());
        assertEquals("link", e.nodeTypeOf("Toppage > link Privacy").id());
        e.close();
    }

    @Test
    void feature5RolesTabSquareRendererTierAndProbeRole(@TempDir Path dir) throws Exception {
        // Role.Tier inference & map round-trip
        assertEquals(Role.Tier.ADMIN, Role.Tier.infer("01_Admin", List.of()));
        assertEquals(Role.Tier.MANAGER, Role.Tier.infer("02_Manager", List.of()));
        assertEquals(Role.Tier.GUEST, Role.Tier.infer("05_Guest", List.of()));
        Role customRole = new Role("03_QA_Tester", true,
                List.of(HeaderRule.of("Authorization", "Bearer qa-token", "replace")),
                Role.Tier.STAFF);
        Role roundTripped = Role.fromMap(customRole.toMap(), 0);
        assertEquals(Role.Tier.STAFF, roundTripped.tier());
        assertEquals("STF", roundTripped.tier().badge);

        // Live probeRole LIVE (200) and DEAD (401)
        StubBridge b = new StubBridge();
        Prefs prefs = Prefs.memory();
        prefs.set(Workspaces.PREF_ROOT, dir.toString());
        Workspaces ws = new Workspaces(prefs, null, null);
        Engine e = newEngine(b, prefs);
        ApiGroup sample = e.process(UiSmokeTest.msg("GET", "/api/v1/profile"), "Proxy", true, e.activeBranch().id());
        assertNotNull(sample);

        b.status = 200;
        Engine.ProbeResult live = e.probeRole(sample, customRole);
        assertTrue(live.alive());
        assertEquals(200, live.status());

        b.status = 401;
        Engine.ProbeResult dead = e.probeRole(sample, customRole);
        assertFalse(dead.alive());
        assertEquals(401, dead.status());

        // RolesTab UI uses sharp SquareStatusRenderer on column 1 (no legacy rounded SwitchRenderer)
        SwingUtilities.invokeAndWait(() -> {
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v2f5");
            ui.tick(true);
            try {
                assertInstanceOf(RolesTab.SquareStatusRenderer.class,
                        ui.roles.roleTable().getColumnModel().getColumn(1).getCellRenderer());
            } finally {
                ui.dispose();
            }
        });
        e.close();
    }

    @Test
    void feature6DuplicateLineageSmartAdviceAndOperations() {
        Engine e = newEngine(new StubBridge(), Prefs.memory());
        Config c = e.config().copy();
        c.dupNewPlace = true;
        c.confirmNewApis = false;
        e.applyConfig(c);

        e.setDestination("Toppage > ScreenA");
        ApiGroup orig = e.process(UiSmokeTest.msg("GET", "/api/v1/me"), "Proxy", true, e.activeBranch().id());
        assertNotNull(orig);
        assertTrue(e.isOrigin(orig));

        e.setDestination("Toppage > ScreenB");
        ApiGroup dup = e.process(UiSmokeTest.msg("GET", "/api/v1/me"), "Proxy", true, e.activeBranch().id());
        assertNotNull(dup);
        assertEquals(2, dup.dupNo());
        assertFalse(e.isOrigin(dup));

        // SmartAdvice explains origin location and gives recommendations
        Decisions.SmartAdvice adv = Decisions.advise(e, dup);
        assertNotNull(adv);
        assertTrue(adv.explanation().contains("Toppage > ScreenA"), adv.explanation());
        assertFalse(adv.recommendations().isEmpty());
        assertTrue(adv.toFormattedText().contains("Khuyến nghị hành động"));

        // Edit copy STT
        e.setApiCopyStt(orig, 77);
        assertEquals(77, orig.copyStt());

        // Promote copy #2 to Origin #1
        assertTrue(e.promoteToOrigin(dup));
        assertEquals(1, dup.dupNo());
        assertEquals(2, orig.dupNo());
        assertTrue(e.isOrigin(dup));
        assertEquals("Toppage > ScreenB", e.book().get(dup.bookKey()).place);

        // Keep only origin & hide #2+
        int hidden = e.keepOnlyOriginAndHideOthers(dup.bookKey());
        assertEquals(1, hidden);
        assertFalse(dup.hidden());
        assertTrue(orig.hidden());

        // Move copy to origin branch
        assertTrue(e.moveCopyToOrigin(orig));
        assertEquals("Toppage > ScreenB", orig.branch());
        e.close();
    }

    @Test
    void feature7ApiTagCatalogRefreshAutoDetectionAndM1ToM4Policies() {
        // Catalog CRUD + JSON round-trip
        ApiTagCatalog tc = new ApiTagCatalog();
        assertNotNull(tc.byId(ApiTagCatalog.TAG_REFRESH));
        assertNotNull(tc.byId(ApiTagCatalog.TAG_INIT));
        assertNotNull(tc.byId(ApiTagCatalog.TAG_POLLING));
        assertNotNull(tc.byId(ApiTagCatalog.TAG_ACTION_CORE));
        tc.upsert(new ApiTagCatalog.TagDef("TELEMETRY", "Telemetry", "muted", "Analytics", false));
        ApiTagCatalog tc2 = new ApiTagCatalog();
        tc2.loadJson(tc.toJson());
        assertNotNull(tc2.byId("TELEMETRY"));
        assertFalse(tc2.remove(ApiTagCatalog.TAG_REFRESH), "Built-in REFRESH tag cannot be deleted");
        assertTrue(tc2.remove("TELEMETRY"));

        // Auto-detect GET right after POST as suspectedRefresh
        Engine e = newEngine(new StubBridge(), Prefs.memory());
        Config c = e.config().copy();
        c.dupNewPlace = true;
        c.autoDetectRefresh = true;
        c.refreshPolicy = Config.RefreshPolicy.TAG_ONLY;
        e.applyConfig(c);

        e.setDestination("Toppage > Orders");
        ApiGroup postAct = e.process(UiSmokeTest.msg("POST", "/api/v1/orders/create"), "Proxy", true, e.activeBranch().id());
        assertNotNull(postAct);
        ApiGroup getList = e.process(UiSmokeTest.msg("GET", "/api/v1/orders"), "Proxy", true, e.activeBranch().id());
        assertNotNull(getList);
        assertTrue(getList.suspectedRefresh(),
                "GET on same resource immediately after POST should be flagged suspectedRefresh");

        // Tag endpoint as [REFRESH], then test M2 (AUTO_SKIP_DUP)
        e.toggleEndpointTag(getList.bookKey(), ApiTagCatalog.TAG_REFRESH, true);
        assertTrue(getList.hasTag(ApiTagCatalog.TAG_REFRESH));
        assertFalse(getList.suspectedRefresh(), "Confirmed [REFRESH] clears suspected flag");

        Config m2 = e.config().copy();
        m2.refreshPolicy = Config.RefreshPolicy.AUTO_SKIP_DUP;
        e.applyConfig(m2);
        e.setDestination("Toppage > Orders > Edit");
        ApiGroup skipped = e.process(UiSmokeTest.msg("GET", "/api/v1/orders"), "Proxy", true, e.activeBranch().id());
        assertNull(skipped, "M2 AUTO_SKIP_DUP must skip duplicate of [REFRESH]-tagged endpoint");
        assertEquals(1, e.copiesOf(getList.bookKey()).size());

        // Test M4 (AUTO_HIDE)
        Config m4 = e.config().copy();
        m4.refreshPolicy = Config.RefreshPolicy.AUTO_HIDE;
        e.applyConfig(m4);
        e.setDestination("Toppage > Orders > Delete");
        ApiGroup autoHidden = e.process(UiSmokeTest.msg("GET", "/api/v1/orders"), "Proxy", true, e.activeBranch().id());
        assertNotNull(autoHidden);
        assertTrue(autoHidden.hidden(), "M4 AUTO_HIDE must automatically hide duplicate of [REFRESH] endpoint");
        assertFalse(autoHidden.dupPending(), "M4 AUTO_HIDE must not leave duplicate pending");

        // Test M3 (AUTO_CLUSTER)
        Config m3 = e.config().copy();
        m3.refreshPolicy = Config.RefreshPolicy.AUTO_CLUSTER;
        e.applyConfig(m3);
        e.setDestination("Toppage > Orders > Approve");
        ApiGroup clustered = e.process(UiSmokeTest.msg("GET", "/api/v1/orders"), "Proxy", true, e.activeBranch().id());
        assertNotNull(clustered);
        GroupCluster autoCl = e.clusterOf(clustered.gid());
        assertNotNull(autoCl, "M3 AUTO_CLUSTER must place [REFRESH] duplicate into a visual cluster");
        assertTrue(autoCl.name().toLowerCase().contains("refresh"), autoCl.name());
        assertEquals("Toppage > Orders > Approve", clustered.branch(),
                "M3 AUTO_CLUSTER must not alter ApiGroup.branch()");

        e.close();
    }
}
