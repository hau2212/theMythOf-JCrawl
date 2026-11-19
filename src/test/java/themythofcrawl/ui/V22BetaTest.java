package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.app.Prefs;
import themythofcrawl.burp.Extension;
import themythofcrawl.core.Config;
import themythofcrawl.excel.PathMatch;
import themythofcrawl.excel.PlaceholderCatalog;
import themythofcrawl.excel.PlaceholderCatalog.MatchKind;
import themythofcrawl.excel.TargetEntry;
import themythofcrawl.excel.TargetList;
import themythofcrawl.model.ActionInterceptRule;
import themythofcrawl.model.ActionInterceptRule.Effect;
import themythofcrawl.model.ActionInterceptRule.PathMatchType;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.Role;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.theme.FontManager;
import themythofcrawl.ui.theme.Theme;

/**
 * Comprehensive verification suite for V_2.2_beta covering all 7 deliverables:
 * 1) Strict immutable versioning metadata (version.properties + VERSIONING.md + CI guard)
 * 2) Proactive Proxy interception rules (branch/path/method matrix, BLOCK_SPOOF/BLOCK_DROP/ALLOW, priority, persistence)
 * 3) SettingsTab GridBagLayout anti-crushing fix at 900px, 1100px (2-col), and 1440px
 * 4) Aligned Dich badge + collapsible & split-screen responsive top chrome (MainPanel + ApiTab)
 * 5) Ultra-slim 24px collapsible Request/Response Inspector Bar (default collapsed)
 * 6) Redesigned minimalist RolesTab (protected 36px rows, AUTO_RESIZE_OFF, full "Trang thai", SquareStatusRenderer)
 * 7) Excel custom {placeholder} catalog, segment replacement, and Live Match Verifier
 */
final class V22BetaTest {

    private static Engine newEngine(Prefs prefs) {
        Engine e = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        e.open(new MemoryStorage());
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.autoSend = false;
        c.confirmNewApis = false;
        c.dupNewPlace = true;
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
            FontManager.setUiChoice(FontManager.UiFontChoice.BE_VIETNAM_PRO);
            FontManager.setMonoChoice(FontManager.MonoFontChoice.JETBRAINS_MONO);
            UiDisplayMode.set(UiDisplayMode.ICON_AND_TEXT);
            Theme.setFontScaleMultiplier(1.0f);
        });
    }

    @Test
    void deliverable1VersionPropertiesAndImmutableCiPolicy() throws Exception {
        Path propsPath = Path.of("version.properties");
        assertTrue(Files.exists(propsPath), "version.properties must exist at repository root");
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(propsPath)) {
            props.load(in);
        }
        assertEquals("V_2.2_beta", props.getProperty("releaseTag"));
        assertEquals("2.2-beta", props.getProperty("versionName"));
        assertEquals("theMythOf-JCrawl_V_2.2_beta.jar", props.getProperty("jarName"));
        assertEquals("2.2-beta", Extension.VERSION);
        assertEquals("2.2-beta", Diagnostics.VERSION);

        Path versioningDoc = Path.of("VERSIONING.md");
        assertTrue(Files.exists(versioningDoc), "VERSIONING.md must document minor/major lineage policy");
        String doc = Files.readString(versioningDoc, StandardCharsets.UTF_8);
        assertTrue(doc.contains("3.2") && doc.contains("3.3"), "VERSIONING.md must document 2.X -> 3.X algorithm bump");

        String wf = Files.readString(Path.of(".github/workflows/build.yml"), StandardCharsets.UTF_8);
        String wfNoComments = wf.lines().filter(l -> !l.trim().startsWith("#")).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(wf.contains("IMMUTABLE GUARD"), "CI workflow must enforce IMMUTABLE GUARD");
        assertFalse(wfNoComments.contains("git tag -f"), "CI workflow must NEVER force-overwrite git tags");
        assertFalse(wfNoComments.contains("--clobber"), "CI workflow must NEVER overwrite existing release assets");
    }

    @Test
    void deliverable2ProactiveProxyInterceptRulesAndPriority() throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        e.setDestination("Orders > DeleteFlow");

        // Rule 1 (higher priority): explicit ALLOW exception for GET /api/orders/health
        ActionInterceptRule allowHealth = new ActionInterceptRule(
                "r-allow", "Cho qua health", true, Effect.ALLOW,
                "Orders", true, "", PathMatchType.WILDCARD, "/api/orders/health*",
                Set.of("GET"), 200, ActionInterceptRule.DEFAULT_SPOOF_BODY);

        // Rule 2: BLOCK_SPOOF custom 201 JSON on Orders subtree for POST + DELETE + GET
        ActionInterceptRule blockOrders = new ActionInterceptRule(
                "r-block", "Chặn xóa & tạo Orders", true, Effect.BLOCK_SPOOF,
                "Orders", true, "", PathMatchType.WILDCARD, "/api/orders/*",
                Set.of("POST", "DELETE", "GET"), 201, "{\"ok\":true,\"intercepted\":true}");

        // Rule 3: BLOCK_DROP on /api/danger/* for DELETE anywhere
        ActionInterceptRule dropDanger = new ActionInterceptRule(
                "r-drop", "Drop danger DELETE", true, Effect.BLOCK_DROP,
                "", true, "", PathMatchType.WILDCARD, "/api/danger/*",
                Set.of("DELETE"), 200, ActionInterceptRule.DEFAULT_SPOOF_BODY);

        e.actionEngine().setInterceptRules(List.of(allowHealth, blockOrders, dropDanger));
        e.saveInterceptRules();

        // Health GET must be ALLOWED despite Rule 2 matching GET /api/orders/*
        byte[] getHealth = "GET /api/orders/health HTTP/1.1\r\nHost: api.example.test\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        Engine.ProxyInterceptOutcome outHealth = e.evaluateProxyIntercept(UiSmokeTest.SVC, getHealth, "https://api.example.test/api/orders/health");
        assertNotNull(outHealth);
        assertEquals(Effect.ALLOW, outHealth.effect());
        assertFalse(outHealth.shouldBlock());

        // DELETE /api/orders/99 must be BLOCK_SPOOF with HTTP 201 + custom JSON body
        byte[] delOrder = "DELETE /api/orders/99 HTTP/1.1\r\nHost: api.example.test\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        Engine.ProxyInterceptOutcome outDel = e.evaluateProxyIntercept(UiSmokeTest.SVC, delOrder, "https://api.example.test/api/orders/99");
        assertNotNull(outDel);
        assertEquals(Effect.BLOCK_SPOOF, outDel.effect());
        assertTrue(outDel.shouldBlock());
        assertTrue(outDel.spoofResponse().startsWith("HTTP/1.1 201 "), "Custom 201 status must be applied");
        assertTrue(outDel.spoofResponse().contains("\"intercepted\":true"));

        // Capture held request into Engine via onProxyHeldRequest (runs on captureExec)
        e.onProxyHeldRequest(UiSmokeTest.SVC, delOrder, "https://api.example.test/api/orders/99", outDel.matchedRule());
        long deadline = System.currentTimeMillis() + 2000;
        while (e.groups().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(e.groups().isEmpty(), "Held request must still be recorded inside the extension");

        // DELETE /api/danger/nuke must be BLOCK_DROP
        byte[] delDanger = "DELETE /api/danger/nuke HTTP/1.1\r\nHost: api.example.test\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        Engine.ProxyInterceptOutcome outDrop = e.evaluateProxyIntercept(UiSmokeTest.SVC, delDanger, "https://api.example.test/api/danger/nuke");
        assertNotNull(outDrop);
        assertEquals(Effect.BLOCK_DROP, outDrop.effect());
        assertTrue(outDrop.shouldBlock());

        // JSON persistence round-trip in a fresh Engine sharing the same Prefs
        Engine e2 = newEngine(prefs);
        assertEquals(3, e2.actionEngine().interceptRules().size());
        assertEquals("r-allow", e2.actionEngine().interceptRules().get(0).id());
        assertEquals(201, e2.actionEngine().interceptRules().get(1).spoofStatus());
        assertEquals(Effect.BLOCK_DROP, e2.actionEngine().interceptRules().get(2).effect());
    }

    @Test
    void deliverable3SettingsTabZeroCrushingAcrossWidths(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        Workspaces ws = newWorkspaces(prefs, dir);

        edt(() -> {
            applyLaf(false);
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v22-settings");
            try {
                ui.tick(true);
                for (int w : new int[] {900, 1100, 1440}) {
                    ui.root().setSize(w, 820);
                    ui.tabs.setSelectedComponent(ui.settings.panel);
                    layoutTree(ui.root());
                    ui.settings.panel.setSize(w, 720);
                    ui.settings.relayoutSections();
                    layoutTree(ui.settings.panel);

                    if (w >= 1040) {
                        assertTrue(ui.settings.isTwoColumnLayout(), "SettingsTab should be 2-column at " + w + "px");
                    } else {
                        assertFalse(ui.settings.isTwoColumnLayout(), "SettingsTab should be 1-column at " + w + "px");
                    }

                    List<JTextField> fields = findAll(ui.settings.panel, JTextField.class);
                    assertFalse(fields.isEmpty());
                    for (JTextField tf : fields) {
                        if (tf.isShowing() || tf.getWidth() > 0) {
                            assertTrue(tf.getWidth() >= 70,
                                    "JTextField crushed to " + tf.getWidth() + "px at viewport width " + w + "px (text=" + tf.getText() + ")");
                        }
                    }
                }
            } finally {
                ui.dispose();
            }
        });
    }

    @Test
    void deliverable4And5ResponsiveTopChromeAndUltraSlimInspector(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        e.setDestination("Orders > Checkout");
        e.process(UiSmokeTest.msg("POST", "/api/orders/create"), "Proxy", true, e.activeBranch().id());
        Workspaces ws = newWorkspaces(prefs, dir);

        edt(() -> {
            applyLaf(false);
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v22-chrome");
            try {
                ui.tick(true);

                // Header Dich height & <= 1100px width
                assertEquals(Theme.current().scaled(24), ui.dest.getPreferredSize().height);
                assertTrue(ui.headerPanel().getPreferredSize().width <= 1100,
                        "Header preferred width must fit 1100px, was " + ui.headerPanel().getPreferredSize().width);

                // Default Inspector Bar must be collapsed (ultra-slim <= 28px)
                assertFalse(ui.api.isInspectorExpanded(), "Inspector bar must default to collapsed ultra-slim strip");
                assertTrue(ui.api.inspectorBarHeight() <= 28,
                        "Collapsed inspector bar height must be <= 28px, was " + ui.api.inspectorBarHeight());

                // Expanding inspector bar shows full bar
                ui.api.setInspectorExpanded(true);
                assertTrue(ui.api.isInspectorExpanded());
                ui.api.setInspectorExpanded(false);
                assertFalse(ui.api.isInspectorExpanded());

                // Wide mode (1280px) -> not compact initially
                ui.updateResponsiveChrome(1280);
                assertFalse(ui.isHeaderCompact());
                assertFalse(ui.api.isToolbarCompact());

                // Manual collapse works in wide full-screen mode too!
                ui.setTopCompact(true);
                assertTrue(ui.isHeaderCompact());
                assertTrue(ui.api.isToolbarCompact());
                ui.setTopCompact(false);
                assertFalse(ui.isHeaderCompact());

                // Split-screen mode (760px) -> auto-collapses header & ApiTab toolbar into dropdowns
                ui.updateResponsiveChrome(760);
                assertTrue(ui.isHeaderCompact(), "Split-screen 760px must auto-collapse header into dropdown");
                assertTrue(ui.api.isToolbarCompact(), "Split-screen 760px must auto-collapse ApiTab toolbar");

                // Expanding split-screen opens clean drawer row
                ui.setTopCompact(false);
                assertFalse(ui.isHeaderCompact());
            } finally {
                ui.dispose();
            }
        });
    }

    @Test
    void deliverable6RedesignedRolesTab(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String jwt = b64.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString("{\"sub\":\"admin\",\"exp\":4110000000}".getBytes(StandardCharsets.UTF_8)) + ".sig";
        Role admin = new Role("01_Admin", true, List.of(
                HeaderRule.of("Authorization", "Bearer " + jwt, "REPLACE")));
        Role user = new Role("02_Customer_Standard", false, List.of(
                HeaderRule.of("SESSION", "abc987654", "COOKIE_SET")));
        e.roles().setAll(List.of(admin, user));
        e.saveRoles();
        Workspaces ws = newWorkspaces(prefs, dir);

        edt(() -> {
            applyLaf(false);
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v22-roles");
            try {
                ui.tick(true);
                JTable roleTable = ui.roles.roleTable();
                assertEquals(Theme.current().scaled(36), roleTable.getRowHeight());
                assertEquals(JTable.AUTO_RESIZE_OFF, roleTable.getAutoResizeMode());
                assertTrue(roleTable.getColumnModel().getColumn(1).getPreferredWidth() >= 82,
                        "Status column width must fit full 'Trang thai' header");
                assertInstanceOf(RolesTab.SquareStatusRenderer.class,
                        roleTable.getColumnModel().getColumn(1).getCellRenderer());
            } finally {
                ui.dispose();
            }
        });
    }

    @Test
    void deliverable7ExcelCustomPlaceholderCatalogAndLiveMatchVerifier() {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);

        // Built-in {session} matches random segment & extracts value
        PathMatch.MatchVerification v1 = PathMatch.verifyMatch(
                "/api/a/{session}/c", "/api/a/x9k2m8p1/c", false, true, e.placeholders());
        assertTrue(v1.matched());
        assertTrue(v1.exact());
        assertEquals("x9k2m8p1", v1.capturedValues().get("{session}"));

        // Add custom regex placeholder {tenant} = "org-[0-9]{4}"
        e.placeholders().upsert("tenant", MatchKind.CUSTOM_REGEX, "org-[0-9]{4}", "Mã tổ chức org-XXXX");
        e.savePlaceholders();

        PathMatch.MatchVerification vOk = PathMatch.verifyMatch(
                "/api/{tenant}/users/{num}", "/api/org-2048/users/77", false, true, e.placeholders());
        assertTrue(vOk.matched());
        assertEquals("org-2048", vOk.capturedValues().get("{tenant}"));
        assertEquals("77", vOk.capturedValues().get("{num}"));

        PathMatch.MatchVerification vFail = PathMatch.verifyMatch(
                "/api/{tenant}/users/{num}", "/api/bad-name/users/77", false, true, e.placeholders());
        assertFalse(vFail.matched(), "Custom regex {tenant} must reject non-matching segment");

        // TargetList live path update & retag captured APIs
        List<List<Object>> rows = List.of(
                List.of("STT", "METHOD", "PATH", "POSITION"),
                List.of(1, "GET", "/api/a/oldliteral/c", "Home"));
        TargetList tl = TargetList.fromRows(rows, "demo.xlsx", "Sheet1");
        e.setTargets(tl);
        e.process(UiSmokeTest.msg("GET", "/api/a/9f8e7d6c/c"), "Proxy", true, e.activeBranch().id());
        assertNull(e.groups().get(0).target(), "Before adding {session}, random path should not match oldliteral");

        TargetEntry entry = e.targets().entries().get(0);
        assertTrue(e.updateTargetRowPath(entry.rowNo(), "/api/a/{session}/c"));
        assertEquals("/api/a/{session}/c", e.targets().entries().get(0).path());
        assertNotNull(e.groups().get(0).target(), "After updating Excel row to /api/a/{session}/c, captured API must match!");

        // Persistence round-trip
        Engine e2 = newEngine(prefs);
        PlaceholderCatalog.PlaceholderDef loaded = e2.placeholders().byName("tenant");
        assertNotNull(loaded);
        assertEquals(MatchKind.CUSTOM_REGEX, loaded.kind());
        assertEquals("org-[0-9]{4}", loaded.customRegex());
    }

    @Test
    void renderVisualVerificationScreenshots(@TempDir Path tempDir) throws Exception {
        boolean saveToTmp = Boolean.parseBoolean(System.getProperty("v22.screenshots", "false"))
                || "1".equals(System.getenv("V22_SCREENSHOTS"));
        Path outDir = saveToTmp ? Path.of("/tmp/v22_verify") : tempDir.resolve("v22_verify");
        Files.createDirectories(outDir);

        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        e.setDestination("Orders > Checkout");
        e.process(UiSmokeTest.msg("GET", "/api/v1/orders/list"), "Proxy", true, e.activeBranch().id());
        e.process(UiSmokeTest.msg("POST", "/api/v1/orders/create"), "Proxy", true, e.activeBranch().id());
        e.process(UiSmokeTest.msg("DELETE", "/api/v1/orders/98412"), "Proxy", true, e.activeBranch().id());

        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String jwt = b64.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString("{\"sub\":\"admin\",\"role\":\"ADMIN\",\"exp\":4110000000}".getBytes(StandardCharsets.UTF_8)) + ".sig";
        Role admin = new Role("01_Admin", true, List.of(
                HeaderRule.of("Authorization", "Bearer " + jwt, "REPLACE"),
                HeaderRule.of("X-Tenant-Id", "org-2048", "ADD")));
        Role manager = new Role("02_Manager", true, List.of(
                HeaderRule.of("JSESSIONID", "9A8F7E6D5C4B3A21", "COOKIE_SET")));
        Role guest = new Role("03_Guest_NoAuth", false, List.of(
                HeaderRule.of("Authorization", "[REMOVE]", "REMOVE")));
        e.roles().setAll(List.of(admin, manager, guest));
        e.saveRoles();

        e.actionEngine().setInterceptRules(List.of(
                new ActionInterceptRule("r1", "Cho qua Healthcheck", true, Effect.ALLOW,
                        "Orders", true, "", PathMatchType.WILDCARD, "/api/v1/orders/health*", Set.of("GET"), 200, ActionInterceptRule.DEFAULT_SPOOF_BODY),
                new ActionInterceptRule("r2", "Chặn xóa đơn hàng", true, Effect.BLOCK_SPOOF,
                        "Orders", true, "", PathMatchType.WILDCARD, "/api/v1/orders/*", Set.of("DELETE", "POST"), 200, "{\"ok\":true,\"held\":true}"),
                new ActionInterceptRule("r3", "Drop xóa tài khoản", true, Effect.BLOCK_DROP,
                        "", true, "", PathMatchType.WILDCARD, "/api/v1/users/delete*", Set.of("DELETE"), 200, ActionInterceptRule.DEFAULT_SPOOF_BODY)));

        List<List<Object>> excelRows = List.of(
                List.of("STT", "METHOD", "PATH", "POSITION"),
                List.of(1, "GET", "/api/a/{session}/c", "Orders > Checkout"),
                List.of(2, "DELETE", "/api/v1/orders/{id}", "Orders > Checkout"));
        e.setTargets(TargetList.fromRows(excelRows, "api_list.xlsx", "Sheet1"));

        Workspaces ws = newWorkspaces(prefs, tempDir.resolve("ws"));

        edt(() -> {
            applyLaf(false);
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v22-vis");
            try {
                ui.tick(true);
                ui.updateResponsiveChrome(1280);
                savePng(outDir.resolve("01_main_light_1280.png"), renderOffscreen(ui.root(), 1280, 760));

                // 11. Expanded Inspector Bar 1280
                ui.api.setInspectorExpanded(true);
                savePng(outDir.resolve("11_inspector_expanded_1280.png"), renderOffscreen(ui.root(), 1280, 760));
                ui.api.setInspectorExpanded(false);

                // 3. Split-screen 760px collapsed (auto-dropdowns, zero collision)
                ui.updateResponsiveChrome(760);
                savePng(outDir.resolve("03_split_760_collapsed.png"), renderOffscreen(ui.root(), 760, 760));

                // 4. Split-screen 760px expanded (clean drawer row 2)
                ui.setTopCompact(false);
                savePng(outDir.resolve("04_split_760_expanded.png"), renderOffscreen(ui.root(), 760, 760));
                ui.updateResponsiveChrome(1280);

                // 5 & 6. SettingsTab 1100px (2-col) and 900px (1-col)
                ui.tabs.setSelectedComponent(ui.settings.panel);
                ui.root().setSize(1100, 860);
                layoutTree(ui.root());
                ui.settings.panel.setSize(1100, 780);
                ui.settings.relayoutSections();
                savePng(outDir.resolve("05_settings_1100_2col.png"), renderOffscreen(ui.root(), 1100, 860));

                ui.root().setSize(900, 860);
                layoutTree(ui.root());
                ui.settings.panel.setSize(900, 780);
                ui.settings.relayoutSections();
                savePng(outDir.resolve("06_settings_900_1col.png"), renderOffscreen(ui.root(), 900, 860));

                // 7. RolesTab Light 1280
                ui.tabs.setSelectedComponent(ui.roles.panel);
                ui.roles.roleTable().setRowSelectionInterval(0, 0);
                savePng(outDir.resolve("07_roles_light_1280.png"), renderOffscreen(ui.root(), 1280, 760));

                // 9. ActionWorkbenchDialog Tab 2 (Luật Chặn Proxy) Light
                ApiGroup g = e.groups().isEmpty() ? null : e.groups().get(0);
                JPanel wbPanel = ActionWorkbenchDialog.buildWorkbenchPanel(
                        ui.root(), e, g, null, "Orders > Checkout", ActionWorkbenchDialog.TAB_INTERCEPT_RULES, () -> {}, () -> {});
                savePng(outDir.resolve("09_workbench_proxy_rules_light.png"), renderOffscreen(wbPanel, 1040, 680));

                // 10. ExcelPlaceholderDialog Light
                TargetEntry te = e.targets().entries().get(0);
                JPanel phPanel = ExcelPlaceholderDialog.buildPanel(ui.root(), e, te, () -> {}, () -> {});
                savePng(outDir.resolve("10_excel_placeholder_dialog.png"), renderOffscreen(phPanel, 1040, 660));
            } finally {
                ui.dispose();
            }

            // 2 & 8. Dark theme verification (Main Dark 1280 + Roles Dark 1280)
            applyLaf(true);
            MainPanel uiDark = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v22-vis-dark");
            try {
                uiDark.tick(true);
                uiDark.updateResponsiveChrome(1280);
                savePng(outDir.resolve("02_main_dark_1280.png"), renderOffscreen(uiDark.root(), 1280, 760));

                uiDark.tabs.setSelectedComponent(uiDark.roles.panel);
                uiDark.roles.roleTable().setRowSelectionInterval(0, 0);
                savePng(outDir.resolve("08_roles_dark_1280.png"), renderOffscreen(uiDark.root(), 1280, 760));
            } finally {
                uiDark.dispose();
            }
        });

        assertTrue(Files.exists(outDir.resolve("01_main_light_1280.png")));
        assertTrue(Files.exists(outDir.resolve("10_excel_placeholder_dialog.png")));
        assertTrue(Files.exists(outDir.resolve("11_inspector_expanded_1280.png")));
    }

    // ------------------------------------------------------------------ Helpers
    private static void applyLaf(boolean dark) {
        try {
            UIManager.setLookAndFeel(dark ? new FlatDarkLaf() : new FlatLightLaf());
            Theme.onThemeChanged();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void layoutTree(Component c) {
        c.doLayout();
        if (c instanceof Container ct) {
            for (Component ch : ct.getComponents()) {
                layoutTree(ch);
            }
        }
    }

    private static <T extends Component> List<T> findAll(Container root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends Component> void collect(Component c, Class<T> type, List<T> out) {
        if (type.isInstance(c)) {
            out.add(type.cast(c));
        }
        if (c instanceof Container ct) {
            for (Component ch : ct.getComponents()) {
                collect(ch, type, out);
            }
        }
    }

    private static BufferedImage renderOffscreen(Component c, int w, int h) {
        c.setSize(new Dimension(w, h));
        layoutTree(c);
        c.validate();
        layoutTree(c);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setColor(Theme.current().bg);
            g2.fillRect(0, 0, w, h);
            c.paint(g2);
        } finally {
            g2.dispose();
        }
        return img;
    }

    private static void savePng(Path path, BufferedImage img) {
        try {
            File f = path.toFile();
            ImageIO.write(img, "png", f);
        } catch (Exception ex) {
            throw new RuntimeException("Failed saving screenshot " + path, ex);
        }
    }

    private static void edt(Runnable r) throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                err.set(t);
            }
        });
        if (err.get() != null) {
            if (err.get() instanceof Exception ex) {
                throw ex;
            }
            if (err.get() instanceof Error e) {
                throw e;
            }
            throw new RuntimeException(err.get());
        }
    }
}
