package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Bridge;
import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.app.Prefs;
import themythofcrawl.burp.Extension;
import themythofcrawl.core.ActionEngine.ActionMode;
import themythofcrawl.core.Config;
import themythofcrawl.core.ProxyInterceptQueue;
import themythofcrawl.core.ProxyInterceptQueue.PendingRequest;
import themythofcrawl.core.ProxyInterceptQueue.ResolutionKind;
import themythofcrawl.model.ActionPolicy;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.Role;
import themythofcrawl.model.RuleMode;
import themythofcrawl.model.Service;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.kit.MethodChipBar;
import themythofcrawl.ui.kit.ModernToggleSwitch;
import themythofcrawl.ui.theme.FontManager;
import themythofcrawl.ui.theme.Theme;

/**
 * Comprehensive verification suite for V_3.5_beta:
 * 1) Immutable version metadata (V_3.5_beta)
 * 2) Deliverable 1: Single-row Top Header Bar (<= 1100px) + live Action status + 1-click ON/OFF ModernToggleSwitch
 * 3) Deliverable 2: Tree-First live Proxy-held requests ([CHỜ DUYỆT · CHƯA GỬI] inside target Branch) + inline Raw HTTP edit + Format JSON
 * 4) Deliverable 3: Detailed Proxy Queue (8-col table with Nhánh đích & Host, Branch/Host filters, MethodChipBar, ModernToggleSwitch, autoOpenWindow=false)
 * 5) Deliverable 4: RolesTab unified UI (ModernToggleSwitch in header + table, Burp-LAF-proof buttons)
 * 6) Deliverable 5 & 6: Streamlined 5-tab Action Workbench + Progressive CRUD Form (fixed Save button, auto-naming) + Integrated Guide & 5 Playbooks
 * 7) Headless Light & Dark PNG visual verification (V35_SCREENSHOTS=1)
 */
final class V35TreeFirstUxTest {

    private static final Service SVC = new Service("app.example.com", 443, true);

    private static byte[] rawReq(String method, String path, String body) {
        String b = body == null ? "" : body;
        String raw = method + " " + path + " HTTP/1.1\r\n"
                + "Host: app.example.com\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + b.getBytes(StandardCharsets.ISO_8859_1).length + "\r\n"
                + "\r\n" + b;
        return raw.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] rawResp(int code, String body) {
        String b = body == null ? "" : body;
        String raw = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + b.getBytes(StandardCharsets.ISO_8859_1).length + "\r\n"
                + "\r\n" + b;
        return raw.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static final class StubBridge implements Bridge {
        @Override public boolean inScope(String url) { return true; }
        @Override public Sent send(Service s, byte[] req) { return new Sent(rawResp(200, "{\"ok\":true}"), 11, null); }
        @Override public void toRepeater(Service service, byte[] request, String tabName) { }
        @Override public void out(String line) { }
        @Override public void err(String line) { }
    }

    private static Engine newEngine(Prefs prefs) {
        Engine e = new Engine(new StubBridge(), prefs, null);
        e.open(new MemoryStorage());
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.autoSend = false;
        c.confirmNewApis = false;
        e.applyConfig(c);
        return e;
    }

    private static Workspaces newWorkspaces(Prefs prefs, Path dir) {
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        return ws;
    }

    @AfterEach
    void resetGlobals() throws Exception {
        edt(() -> {
            UIManager.put("html.disable", null);
            FontManager.setUiChoice(FontManager.UiFontChoice.BE_VIETNAM_PRO);
            FontManager.setMonoChoice(FontManager.MonoFontChoice.JETBRAINS_MONO);
            UiDisplayMode.set(UiDisplayMode.ICON_AND_TEXT);
            Theme.setFontScaleMultiplier(1.0f);
        });
    }

    @Test
    void versionMetadataIsConsistent() throws Exception {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("version.properties"))) {
            props.load(in);
        }
        String major = props.getProperty("major");
        String minor = props.getProperty("minor");
        String channel = props.getProperty("channel");
        boolean stable = "stable".equals(channel);   // official release: V_9.2 / 9.2 (VERSIONING.md 1.3)
        String expectedVer = major + "." + minor + (stable ? "" : "-" + channel);
        String expectedTag = "V_" + major + "." + minor + (stable ? "" : "_" + channel);
        assertEquals(expectedTag, props.getProperty("releaseTag"));
        assertEquals(expectedVer, props.getProperty("versionName"));
        assertEquals("theMythOf-JCrawl_" + expectedTag + ".jar", props.getProperty("jarName"));
        assertEquals(expectedVer, Extension.VERSION);
        assertEquals(expectedVer, Diagnostics.VERSION);

        String wf = Files.readString(Path.of(".github/workflows/build.yml"), StandardCharsets.UTF_8);
        assertTrue(wf.contains("IMMUTABLE GUARD"), "CI workflow must preserve immutable release guard");
    }

    @Test
    void deliverable1TopHeaderSingleRowLiveActionStatusAndOneClickToggle(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        e.setDestination("Orders > Delete");
        e.process(UiSmokeTest.msg("DELETE", "/api/orders/77"), "Proxy", true, e.activeBranch().id());
        Workspaces ws = newWorkspaces(prefs, dir);

        try {
            assertFalse(e.isAnyActionControlActive());
            assertEquals("Action: Tắt", e.quickActionStatusLabel());

            // 1-click ON from header -> activates INTERCEPT_WAIT by default
            e.toggleQuickActionFromHeader(true);
            assertTrue(e.isAnyActionControlActive());
            assertTrue(e.actionEngine().isInterceptWaitEnabled());
            assertTrue(e.quickActionStatusLabel().contains("Chặn"), "Got: " + e.quickActionStatusLabel());

            // Switch to HOLD_AT_PROXY via quick mode
            e.applyQuickActionMode(ActionMode.HOLD_AT_PROXY);
            assertEquals("Giữ Proxy", e.quickActionStatusLabel());

            // 1-click OFF from header -> atomically resets everything to NONE
            e.toggleQuickActionFromHeader(false);
            assertFalse(e.isAnyActionControlActive());
            assertEquals(ActionMode.NONE, e.config().actionMode);
            assertEquals("Action: Tắt", e.quickActionStatusLabel());

            edt(() -> {
                MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v35-header");
                try {
                    ui.tick(true);
                    int prefW = ui.headerPanel().getPreferredSize().width;
                    assertTrue(prefW <= 1100, "Single-row Top Header preferred width must stay <= 1100px, got " + prefW);

                    List<ModernToggleSwitch> headerSwitches = findAll(ui.headerPanel(), ModernToggleSwitch.class);
                    assertFalse(headerSwitches.isEmpty(), "Top Header must contain 1-click ModernToggleSwitch for Action");
                    assertFalse(headerSwitches.get(0).isSelected());

                    // Turning Action ON updates header switch & keeps width <= 1100px
                    e.toggleQuickActionFromHeader(true);
                    ui.syncHeader();
                    assertTrue(headerSwitches.get(0).isSelected());
                    assertTrue(ui.headerPanel().getPreferredSize().width <= 1100,
                            "Top Header width with Action ON must remain <= 1100px, got "
                                    + ui.headerPanel().getPreferredSize().width);
                } finally {
                    ui.dispose();
                }
            });
        } finally {
            e.close();
        }
    }

    @Test
    void deliverable2TreeFirstLiveHeldNodeAndInlineQuickEdit(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        // Tree-First default: do NOT auto-open floating modal on every intercepted request
        assertFalse(e.proxyQueue().isAutoOpenWindow(), "Tree-First default must keep autoOpenWindow=false");

        e.setDestination("Orders > Checkout");
        e.process(UiSmokeTest.msg("GET", "/api/orders/10"), "Proxy", true, e.activeBranch().id());
        Workspaces ws = newWorkspaces(prefs, dir);

        // Format JSON helper check
        String uglyRaw = "POST /api/orders/pay HTTP/1.1\r\nHost: app.example.com\r\nContent-Type: application/json\r\nContent-Length: 21\r\n\r\n{\"id\":10,\"amount\":50}";
        String prettyRaw = RequestComposerDialog.formatJsonBodyInRawHttp(uglyRaw);
        assertTrue(prettyRaw.contains("\n  \"id\""), "Format JSON must indent JSON body cleanly: " + prettyRaw);

        AtomicReference<ProxyInterceptQueue.Resolution> resRef = new AtomicReference<>();
        Thread proxyWorker = new Thread(() -> {
            byte[] req = rawReq("DELETE", "/api/orders/99", "{\"reason\":\"test\"}");
            ProxyInterceptQueue.Resolution res = e.proxyQueue().enqueueAndAwait(
                    SVC, "DELETE", "/api/orders/99", "https://app.example.com/api/orders/99",
                    "Orders > Checkout", "Chặn chờ duyệt nhánh", null, req, 15_000L);
            resRef.set(res);
        }, "test-proxy-worker");
        proxyWorker.setDaemon(true);
        proxyWorker.start();

        // Wait until item appears in queue
        long deadline = System.currentTimeMillis() + 3000;
        while (e.proxyQueue().pendingCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(15);
        }
        assertEquals(1, e.proxyQueue().pendingCount());
        PendingRequest pr = e.proxyQueue().pending().get(0);
        assertEquals("Orders > Checkout", pr.branchName());
        assertEquals("app.example.com", pr.service().hostLabel());

        try {
            edt(() -> {
                MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v35-tree");
                try {
                    ui.tick(true);
                    ui.treeTab.tree.rebuild(false);
                    Node heldNode = findHeldNode(
                            (DefaultMutableTreeNode) ui.treeTab.tree.model.getRoot(), pr.id());
                    assertNotNull(heldNode, "Pending proxy request must appear live as HELD_PENDING node inside target branch");
                    assertEquals(Node.Kind.HELD_PENDING, heldNode.kind);
                    assertEquals(pr.id(), ui.treeTab.tree.heldOf(heldNode).id());

                    // Select HELD_PENDING node in TreeTab and confirm live inline display + resolution
                    ui.treeTab.tree.tree.setSelectionPath(new javax.swing.tree.TreePath(heldNode.getPath()));
                    assertEquals(1, e.proxyQueue().pendingForBranch("Orders > Checkout").size());

                    // Resolve from inline action -> Forward with edited payload
                    byte[] edited = rawReq("DELETE", "/api/orders/99", "{\"reason\":\"edited-inline\"}");
                    e.proxyQueue().resolve(pr.id(), ResolutionKind.FORWARD, edited);
                    ui.treeTab.tree.rebuild(false);
                    assertTrue(e.proxyQueue().pending().isEmpty());
                    assertEquals(null, findHeldNode(
                            (DefaultMutableTreeNode) ui.treeTab.tree.model.getRoot(), pr.id()));
                } finally {
                    ui.dispose();
                }
            });
            proxyWorker.join(3000);
            assertNotNull(resRef.get());
            assertEquals(ResolutionKind.FORWARD, resRef.get().kind());
            assertTrue(new String(resRef.get().editedRequest(), StandardCharsets.ISO_8859_1).contains("edited-inline"));
        } finally {
            e.proxyQueue().releaseAll(ResolutionKind.DROP);
            e.close();
        }
    }

    @Test
    void deliverable3To6ProxyQueueRolesWorkbench5TabsCrudSaveAndGuide(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        e.roles().add(new Role("Admin", true, List.of(new HeaderRule("Authorization", "Bearer admin", RuleMode.REPLACE)), Role.Tier.ADMIN));
        e.roles().add(new Role("Staff", false, List.of(new HeaderRule("Authorization", "Bearer staff", RuleMode.REPLACE)), Role.Tier.USER));
        e.setDestination("Orders > Manage");
        e.process(UiSmokeTest.msg("DELETE", "/api/orders/42"), "Proxy", true, e.activeBranch().id());
        ApiGroup g = e.groups().get(0);
        Workspaces ws = newWorkspaces(prefs, dir);

        try {
            edt(() -> {
                UIManager.put("html.disable", Boolean.TRUE);
                MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v35-all");
                try {
                    ui.tick(true);

                    // 1. Detailed Proxy Queue dialog view: 8 columns including Nhánh đích & Host, MethodChipBar, ModernToggleSwitch
                    ProxyInterceptDialog.View pqView = ProxyInterceptDialog.buildView(e, ui, () -> { }, pin -> { });
                    try {
                        JPanel pqPanel = pqView.panel();
                        assertNoHtmlStrings(pqPanel);
                        List<JTable> tables = findAll(pqPanel, JTable.class);
                        assertFalse(tables.isEmpty());
                        JTable qTable = tables.get(0);
                        assertEquals(8, qTable.getColumnCount(), "Proxy Queue table must have 8 context columns");
                        List<String> cols = new ArrayList<>();
                        for (int i = 0; i < qTable.getColumnCount(); i++) {
                            cols.add(String.valueOf(qTable.getColumnName(i)));
                        }
                        assertTrue(cols.contains("Nhánh đích"), "Columns must include Nhánh đích: " + cols);
                        assertTrue(cols.contains("Host"), "Columns must include Host: " + cols);
                        assertTrue(cols.contains("Loại"), "Columns must include Loại (API mới / Trùng): " + cols);
                        assertFalse(findAll(pqPanel, MethodChipBar.class).isEmpty(), "Proxy Queue must use MethodChipBar");
                        assertFalse(findAll(pqPanel, ModernToggleSwitch.class).isEmpty(), "Proxy Queue must use ModernToggleSwitch");
                    } finally {
                        pqView.dispose().run();
                    }

                    // 2. RolesTab unified UI
                    assertNoHtmlStrings(ui.roles.panel);
                    assertFalse(findAll(ui.roles.panel, ModernToggleSwitch.class).isEmpty(),
                            "RolesTab must use ModernToggleSwitch");

                    // 3. ActionWorkbenchDialog: 5 streamlined tabs + legacy TAB_CHAIN/TAB_POOL/TAB_LOCK200 routing
                    JPanel wbPanel = ActionWorkbenchDialog.buildWorkbenchPanel(
                            ui.root(), e, g, null, ActionWorkbenchDialog.TAB_LEDGER, () -> { }, () -> { });
                    assertNoHtmlStrings(wbPanel);
                    List<JTabbedPane> tabPanes = findAll(wbPanel, JTabbedPane.class);
                    assertFalse(tabPanes.isEmpty());
                    JTabbedPane mainTabs = tabPanes.get(0);
                    assertEquals(6, mainTabs.getTabCount(), "Chặn & gửi lại: Tổng quan + 5 tabs");
                    assertEquals(ActionWorkbenchDialog.TAB_LEDGER, mainTabs.getSelectedIndex());

                    // Legacy TAB_CHAIN must route into Tab 4 (TAB_ADVANCED)
                    JPanel wbChain = ActionWorkbenchDialog.buildWorkbenchPanel(
                            ui.root(), e, g, null, ActionWorkbenchDialog.TAB_CHAIN, () -> { }, () -> { });
                    JTabbedPane chainMainTabs = findAll(wbChain, JTabbedPane.class).get(0);
                    assertEquals(ActionWorkbenchDialog.TAB_ADVANCED, chainMainTabs.getSelectedIndex());

                    // 4. ActionLedgerTabBuilder CRUD Save button works even with blank name (auto-naming!)
                    int beforeCount = e.actionEngine().policyLedger().all().size();
                    JButton saveBtn = findButtonContaining(wbPanel, "Lưu chính sách mới");
                    assertNotNull(saveBtn, "CRUD Form must display unmistakable solid primary Save CTA");
                    assertFalse(findAll(wbPanel, MethodChipBar.class).isEmpty(), "CRUD Form must include MethodChipBar");
                    saveBtn.doClick();
                    assertEquals(beforeCount + 1, e.actionEngine().policyLedger().all().size(),
                            "Clicking Save with blank name must auto-name and persist new policy");
                    List<ActionPolicy> allPols = e.actionEngine().policyLedger().all();
                    ActionPolicy saved = allPols.get(allPols.size() - 1);
                    assertFalse(saved.name().isBlank(), "Auto-generated policy name must not be blank");

                    // 5. Tab 5 Integrated Guide & 1-click Playbooks
                    mainTabs.setSelectedIndex(ActionWorkbenchDialog.TAB_GUIDE);
                    JButton playbook1Btn = findButtonContaining(wbPanel, "Áp dụng mẫu A ngay");
                    assertNotNull(playbook1Btn, "Guide tab must provide 1-click Playbook apply buttons");
                    playbook1Btn.doClick();
                    assertTrue(e.isAnyActionControlActive(), "1-click Playbook must activate Action policy immediately");
                } finally {
                    ui.dispose();
                }
            });
        } finally {
            e.close();
        }
    }

    @Test
    void exportVisualVerificationScreenshotsWhenEnabled(@TempDir Path dir) throws Exception {
        if (!"1".equals(System.getenv("V35_SCREENSHOTS"))) {
            return;
        }
        Path outDir = Path.of("/tmp/v35_verify");
        Files.createDirectories(outDir);

        for (boolean dark : new boolean[] {false, true}) {
            String suffix = dark ? "dark" : "light";
            Prefs prefs = Prefs.memory();
            Engine e = newEngine(prefs);
            e.roles().add(new Role("Admin", true, List.of(
                    new HeaderRule("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.admin", RuleMode.REPLACE),
                    new HeaderRule("X-Tenant-Id", "tenant-prod-01", RuleMode.REPLACE)), Role.Tier.ADMIN));
            e.roles().add(new Role("Staff", true, List.of(
                    new HeaderRule("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.staff", RuleMode.REPLACE)), Role.Tier.USER));
            e.roles().add(new Role("Guest", false, List.of(
                    new HeaderRule("Authorization", "", RuleMode.REMOVE)), Role.Tier.GUEST));

            e.setDestination("Orders > Checkout");
            e.process(UiSmokeTest.msg("GET", "/api/v1/orders/101"), "Proxy", true, e.activeBranch().id());
            e.process(UiSmokeTest.msg("DELETE", "/api/v1/orders/101"), "Proxy", true, e.activeBranch().id());
            ApiGroup g = e.groups().get(1);
            e.toggleQuickActionFromHeader(true);
            e.actionEngine().policyLedger().upsert(ActionPolicy.quickBranchWait(
                    "Orders > Checkout", Set.of("POST", "PUT", "PATCH", "DELETE"), "V3.5 Demo"));

            // Enqueue 2 live pending requests on daemon threads so Tree & Proxy Queue show real held entries
            Thread w1 = new Thread(() -> e.proxyQueue().enqueueAndAwait(
                    SVC, "DELETE", "/api/v1/orders/901", "https://app.example.com/api/v1/orders/901",
                    "Orders > Checkout", "Chặn chờ duyệt nhánh Checkout", null,
                    rawReq("DELETE", "/api/v1/orders/901", "{\"reason\":\"cancel\",\"force\":false}"), 20_000L));
            Thread w2 = new Thread(() -> e.proxyQueue().enqueueAndAwait(
                    new Service("billing.example.com", 443, true), "POST", "/api/v1/invoices/refund",
                    "https://billing.example.com/api/v1/invoices/refund", "Billing > Refunds",
                    "Chặn chờ duyệt toàn cục", null,
                    rawReq("POST", "/api/v1/invoices/refund", "{\"invoiceId\":554,\"amount\":1200}"), 20_000L));
            w1.setDaemon(true);
            w2.setDaemon(true);
            w1.start();
            w2.start();
            long dl = System.currentTimeMillis() + 3000;
            while (e.proxyQueue().pendingCount() < 2 && System.currentTimeMillis() < dl) {
                Thread.sleep(15);
            }

            Workspaces ws = newWorkspaces(prefs, dir.resolve(suffix));
            try {
                edt(() -> {
                    installFlatLaf(dark);
                    Theme.onThemeChanged();
                    MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v35-shot-" + suffix);
                    try {
                        ui.tick(true);
                        // 1. Top Bar + TreeTab with live [CHỜ DUYỆT · CHƯA GỬI] node selected
                        ui.selectTab(ui.treeTab.panel);
                        ui.treeTab.tree.rebuild(false);
                        PendingRequest firstHeld = e.proxyQueue().pending().get(0);
                        Node heldNode = findHeldNode(
                                (DefaultMutableTreeNode) ui.treeTab.tree.model.getRoot(), firstHeld.id());
                        if (heldNode != null) {
                            ui.treeTab.tree.tree.setSelectionPath(new javax.swing.tree.TreePath(heldNode.getPath()));
                        }
                        renderPng(ui.root(), 1260, 760, outDir.resolve("topbar_tree_" + suffix + ".png").toFile());

                        // 2. RolesTab with ModernToggleSwitch
                        ui.selectTab(ui.roles.panel);
                        ui.tick(true);
                        renderPng(ui.root(), 1260, 740, outDir.resolve("roles_" + suffix + ".png").toFile());

                        // 3. Detailed Proxy Queue Dialog
                        ProxyInterceptDialog.View pqView = ProxyInterceptDialog.buildView(e, ui, () -> { }, pin -> { });
                        try {
                            renderPng(pqView.panel(), 1160, 720, outDir.resolve("proxy_queue_" + suffix + ".png").toFile());
                        } finally {
                            pqView.dispose().run();
                        }

                        // 4. Action Workbench Tab 2: Progressive CRUD Form
                        JPanel wbCrud = ActionWorkbenchDialog.buildWorkbenchPanel(
                                ui.root(), e, g, null, ActionWorkbenchDialog.TAB_LEDGER, () -> { }, () -> { });
                        renderPng(wbCrud, 1180, 760, outDir.resolve("workbench_crud_" + suffix + ".png").toFile());

                        // 5. Action Workbench Tab 5: Integrated Guide & 5 Playbooks
                        JPanel wbGuide = ActionWorkbenchDialog.buildWorkbenchPanel(
                                ui.root(), e, g, null, ActionWorkbenchDialog.TAB_GUIDE, () -> { }, () -> { });
                        renderPng(wbGuide, 1180, 760, outDir.resolve("workbench_guide_" + suffix + ".png").toFile());
                    } finally {
                        ui.dispose();
                    }
                });
            } finally {
                e.proxyQueue().releaseAll(ResolutionKind.DROP);
                e.close();
            }
        }
    }

    private static Node findHeldNode(DefaultMutableTreeNode root, long pendingId) {
        if (root == null) return null;
        if (root instanceof Node n && n.kind == Node.Kind.HELD_PENDING
                && n.payload instanceof PendingRequest p && p.id() == pendingId) {
            return n;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            Node found = findHeldNode((DefaultMutableTreeNode) root.getChildAt(i), pendingId);
            if (found != null) return found;
        }
        return null;
    }

    private static JButton findButtonContaining(Container root, String needle) {
        for (AbstractButton b : findAll(root, AbstractButton.class)) {
            if (b instanceof JButton jb && jb.getText() != null && jb.getText().contains(needle)) {
                return jb;
            }
        }
        return null;
    }

    private static <T extends Component> List<T> findAll(Container root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends Component> void collect(Container root, Class<T> type, List<T> out) {
        if (root == null) return;
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) {
                out.add(type.cast(c));
            }
            if (c instanceof Container ct) {
                collect(ct, type, out);
            }
        }
    }

    private static void assertNoHtmlStrings(Container root) {
        for (JLabel l : findAll(root, JLabel.class)) {
            String t = l.getText();
            assertFalse(t != null && t.toLowerCase().contains("<html"), "Forbidden <html> in JLabel: " + t);
        }
        for (AbstractButton b : findAll(root, AbstractButton.class)) {
            String t = b.getText();
            assertFalse(t != null && t.toLowerCase().contains("<html"), "Forbidden <html> in Button: " + t);
        }
    }

    private static void installFlatLaf(boolean dark) {
        try {
            String cls = dark ? "com.formdev.flatlaf.FlatDarkLaf" : "com.formdev.flatlaf.FlatLightLaf";
            UIManager.setLookAndFeel(cls);
        } catch (Exception ignored) {
            // fallback to current LAF
        }
    }

    private static void renderPng(Component comp, int w, int h, File out) {
        comp.setSize(w, h);
        layoutRecursively(comp);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setColor(Theme.current().bg);
            g2.fillRect(0, 0, w, h);
            comp.paint(g2);
        } finally {
            g2.dispose();
        }
        try {
            ImageIO.write(img, "png", out);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void layoutRecursively(Component c) {
        if (c instanceof Container ct) {
            ct.doLayout();
            for (Component child : ct.getComponents()) {
                layoutRecursively(child);
            }
            ct.doLayout();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void edt(ThrowingRunnable r) throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                err.set(t);
            }
        });
        if (err.get() != null) {
            if (err.get() instanceof Exception ex) throw ex;
            if (err.get() instanceof Error er) throw er;
            throw new RuntimeException(err.get());
        }
    }
}
