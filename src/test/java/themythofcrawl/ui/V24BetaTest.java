package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Bridge;
import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.burp.Extension;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.ActionEngine.ActionMode;
import themythofcrawl.core.Config;
import themythofcrawl.core.ProxyInterceptQueue;
import themythofcrawl.core.ProxyInterceptQueue.PendingRequest;
import themythofcrawl.core.ProxyInterceptQueue.Resolution;
import themythofcrawl.core.ProxyInterceptQueue.ResolutionKind;
import themythofcrawl.model.ActionInterceptRule;
import themythofcrawl.model.ActionInterceptRule.Effect;
import themythofcrawl.model.ActionInterceptRule.PathMatchType;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.Role;
import themythofcrawl.model.Service;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.theme.FontManager;
import themythofcrawl.ui.theme.Theme;

/**
 * Comprehensive verification suite for V_2.4_beta:
 * 1) Immutable version metadata (V_2.4_beta)
 * 2) Multi-threaded live ProxyInterceptQueue blocking & resolution (Forward w/ edits, Hold, Drop, Timeout, ReleaseAll)
 * 3) All 4 Live Intercept Wait scopes (Global+Method, Branch+Sub-branch, API endpoint, Proactive Rule) even with Capture OFF
 * 4) Root-cause regression guards for Strategy #1 (per-API HOLD_AT_PROXY, autoSend never fires held requests, reflection safety)
 * 5) Content-Length recalculation on raw HTTP edits
 * 6) Headless UI, zero-HTML & header width <= 1120px + optional PNG screenshot verification (V24_SCREENSHOTS=1)
 */
final class V24BetaTest {

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

    private static Engine newEngine(Bridge bridge, Prefs prefs) {
        Engine e = new Engine(bridge, prefs, null);
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
    void versionMetadataIsConsistentWithProperties() throws Exception {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("version.properties"))) {
            props.load(in);
        }
        String tag = props.getProperty("releaseTag");
        String ver = props.getProperty("versionName");
        String jar = props.getProperty("jarName");
        assertTrue(tag != null && tag.matches("V_\\d+\\.\\d+(_\\w+)?"));
        assertEquals("theMythOf-JCrawl_" + tag + ".jar", jar);
        assertEquals(ver, Extension.VERSION);
        assertEquals(ver, Diagnostics.VERSION);
    }

    @Test
    void proxyInterceptQueueLiveBlockingAndResolution() throws Exception {
        ProxyInterceptQueue q = new ProxyInterceptQueue();
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            byte[] orig = rawReq("POST", "/api/profile/save", "{\"name\":\"old\"}");
            byte[] edited = rawReq("POST", "/api/profile/save", "{\"name\":\"edited\"}");

            Future<Resolution> worker = exec.submit(() -> q.enqueueAndAwait(
                    SVC, "POST", "/api/profile/save", "https://app.example.com/api/profile/save",
                    "Profile > Edit", "Chặn nhánh: Profile", null, orig, 5_000));

            // Wait briefly for worker thread to enqueue & block
            long deadline = System.currentTimeMillis() + 2_000;
            while (q.pendingCount() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, q.pendingCount(), "Worker thread must pause with 1 pending item");
            assertFalse(worker.isDone(), "Proxy worker thread must remain blocked until user clicks Forward/Drop");

            PendingRequest pr = q.pending().get(0);
            assertTrue(q.resolve(pr.id(), ResolutionKind.FORWARD, edited));

            Resolution res = worker.get(2, TimeUnit.SECONDS);
            assertEquals(ResolutionKind.FORWARD, res.kind());
            assertEquals(new String(edited, StandardCharsets.ISO_8859_1),
                    new String(res.editedRequest(), StandardCharsets.ISO_8859_1));
            assertEquals(0, q.pendingCount());

            // Timeout fail-safe must return DROP (never silently send to Server)
            Resolution timedOut = q.enqueueAndAwait(
                    SVC, "DELETE", "/api/orders/55", "https://app.example.com/api/orders/55",
                    "Orders", "Timeout test", null, orig, 40);
            assertEquals(ResolutionKind.DROP, timedOut.kind());
            assertEquals(0, q.pendingCount());

            // releaseAll must release all queued requests cleanly
            PendingRequest p1 = q.enqueue(SVC, "POST", "/a", "https://app.example.com/a", "B", "R", null, orig);
            PendingRequest p2 = q.enqueue(SVC, "PUT", "/b", "https://app.example.com/b", "B", "R", null, orig);
            assertEquals(2, q.pendingCount());
            assertEquals(2, q.releaseAll(ResolutionKind.DROP));
            assertEquals(0, q.pendingCount());
            assertTrue(p1.isDone() && p2.isDone());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void evaluateProxyInterceptAllFourScopesEvenWhenCaptureOff() {
        Engine e = newEngine(UiSmokeTest.BRIDGE, Prefs.memory());
        try {
            // Turn Capture OFF + strict API regex ON -> explicit Intercept Wait MUST STILL BLOCK Save requests!
            Config c = e.config().copy();
            c.captureEnabled = false;
            c.apiRegexEnabled = true;
            c.apiRegex = "^/api/";
            e.applyConfig(c);

            // Scope 1: Global Live Intercept Wait with POST/PUT/PATCH/DELETE
            e.setInterceptWaitEnabled(true);
            e.actionEngine().setInterceptWaitAllScope(true);
            e.actionEngine().setInterceptWaitMethods(Set.of("POST", "PUT", "DELETE"));

            Engine.ProxyInterceptOutcome saveOut = e.evaluateProxyIntercept(
                    SVC, rawReq("POST", "/profile/save", "{\"ok\":1}"), "https://app.example.com/profile/save");
            assertNotNull(saveOut, "Global INTERCEPT_WAIT must block POST Save even with capture OFF and non-/api/ path");
            assertTrue(saveOut.shouldWait());
            assertEquals(Effect.INTERCEPT_WAIT, saveOut.effect());

            // GET static asset must NOT spam the queue
            assertNull(e.evaluateProxyIntercept(
                    SVC, rawReq("GET", "/assets/app.css", ""), "https://app.example.com/assets/app.css"));

            // Scope 2: Branch-specific Live Intercept Wait (exact + sub-branch)
            e.actionEngine().setInterceptWaitAllScope(false);
            e.toggleBranchInterceptWait("Orders > Edit", true);

            e.setDestination("Dashboard");
            assertNull(e.evaluateProxyIntercept(
                    SVC, rawReq("POST", "/api/orders/save", "{}"), "https://app.example.com/api/orders/save"),
                    "Unrelated branch must not match branch-scoped wait");

            e.setDestination("Orders > Edit > Button Save");
            Engine.ProxyInterceptOutcome branchOut = e.evaluateProxyIntercept(
                    SVC, rawReq("POST", "/api/orders/save", "{}"), "https://app.example.com/api/orders/save");
            assertNotNull(branchOut, "Sub-branch under pinned branch must match INTERCEPT_WAIT");
            assertTrue(branchOut.shouldWait());

            // Scope 3: Specific API endpoint (normalized ID template!) even on unrelated branch
            e.setDestination("Dashboard");
            e.actionEngine().setEndpointInterceptWait("DELETE", "/api/items/101", true);
            Engine.ProxyInterceptOutcome epOut = e.evaluateProxyIntercept(
                    SVC, rawReq("DELETE", "/api/items/999", ""), "https://app.example.com/api/items/999");
            assertNotNull(epOut, "Pinned API endpoint must match any concrete numeric ID");
            assertTrue(epOut.shouldWait());

            // Scope 4: Proactive ActionInterceptRule with Effect.INTERCEPT_WAIT (works even if master wait switch is OFF!)
            e.setInterceptWaitEnabled(false);
            e.actionEngine().upsertInterceptRule(new ActionInterceptRule(
                    "rule-wait-1", "Chặn chờ duyệt Thanh toán", true, Effect.INTERCEPT_WAIT,
                    "", true, "", PathMatchType.WILDCARD, "/checkout/*", Set.of("POST"), 200, null));
            Engine.ProxyInterceptOutcome ruleOut = e.evaluateProxyIntercept(
                    SVC, rawReq("POST", "/checkout/confirm", "{}"), "https://app.example.com/checkout/confirm");
            assertNotNull(ruleOut, "Proactive INTERCEPT_WAIT rule must work even when master toggle is OFF");
            assertTrue(ruleOut.shouldWait());
            assertEquals("rule-wait-1", ruleOut.matchedRule().id());
        } finally {
            e.close();
        }
    }

    @Test
    void rootCauseFixes_perApiHoldAndAutoSendGuardAndReflectionSafety() throws Exception {
        AtomicInteger sentCount = new AtomicInteger(0);
        Bridge spyBridge = new Bridge() {
            @Override public boolean inScope(String url) { return true; }
            @Override public Sent send(Service s, byte[] req) {
                sentCount.incrementAndGet();
                return new Sent("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes(StandardCharsets.ISO_8859_1), 5, null);
            }
            @Override public void toRepeater(Service s, byte[] req, String tabName) { }
            @Override public void out(String line) { }
            @Override public void err(String line) { }
        };

        Engine e = newEngine(spyBridge, Prefs.memory());
        try {
            // Configure 1 enabled Role (keep autoSend=false during initial normal capture)
            e.roles().setAll(List.of(new Role("01_Admin", true, List.of(HeaderRule.of("X-Role", "admin", "REPLACE")), Role.Tier.ADMIN)));

            // Capture a Save endpoint, then choose Strategy #1 HOLD_AT_PROXY in per-API scope
            e.process(UiSmokeTest.msg("POST", "/api/users/42/save"), "Proxy", true, e.activeBranch().id());
            ApiGroup g = e.groups().get(0);

            e.setApiActionMode(g, ActionMode.HOLD_AT_PROXY);
            assertTrue(e.actionEngine().isEndpointProxyHold("POST", "/api/users/42/save"),
                    "Selecting HOLD_AT_PROXY for a specific API must register it in proxyHoldEndpoints");

            Engine.ProxyInterceptOutcome holdOut = e.evaluateProxyIntercept(
                    SVC, rawReq("POST", "/api/users/88/save", "{\"a\":1}"), "https://app.example.com/api/users/88/save");
            assertNotNull(holdOut);
            assertTrue(holdOut.shouldBlock());
            assertEquals(Effect.BLOCK_SPOOF, holdOut.effect());

            // Now enable autoSend=true — recording a held request MUST NOT trigger autoSend back to the Server!
            Config c = e.config().copy();
            c.autoSend = true;
            e.applyConfig(c);
            sentCount.set(0);
            e.onProxyHeldRequest(SVC, rawReq("POST", "/api/orders/77/save", "{\"k\":1}"),
                    "https://app.example.com/api/orders/77/save", null, "Proxy-Wait[Test]");
            e.awaitCaptures();
            Thread.sleep(50);
            assertEquals(0, sentCount.get(),
                    "autoSend=true must NEVER automatically fire requests that were just held at Proxy!");
            ApiGroup heldGroup = e.groups().stream()
                    .filter(x -> x.path().contains("/orders/77/save"))
                    .findFirst().orElseThrow();
            assertTrue(heldGroup.heldAtProxy(), "Held request from Proxy-Wait[...] must be marked heldAtProxy=true");

            // Selecting INTERCEPT_WAIT on a specific API must enable master wait + endpoint wait
            e.setApiActionMode(g, ActionMode.INTERCEPT_WAIT);
            assertTrue(e.actionEngine().isInterceptWaitEnabled());
            assertTrue(e.actionEngine().isEndpointInterceptWait("POST", "/api/users/42/save"));

            // Reflection probes must never throw
            Extension.supportsSpoof();
            Extension.supportsHttpDrop();
        } finally {
            e.close();
        }
    }

    @Test
    void normalizeRawHttpRequestUpdatesContentLength() {
        String edited = "POST /api/save HTTP/1.1\n"
                + "Host: app.example.com\n"
                + "Content-Type: application/json\n"
                + "Content-Length: 5\n"
                + "\n"
                + "{\"name\":\"updated-value-123\"}";
        byte[] wire = ProxyInterceptDialog.normalizeRawHttpRequest(edited);
        String asText = new String(wire, StandardCharsets.ISO_8859_1);
        int expectedLen = "{\"name\":\"updated-value-123\"}".length();
        assertTrue(asText.contains("Content-Length: " + expectedLen + "\r\n"),
                "Content-Length must be recalculated to " + expectedLen + " but got:\n" + asText);
        assertTrue(asText.contains("\r\n\r\n{\"name\":\"updated-value-123\"}"));
    }

    @Test
    void proxyInterceptDialogAndHeaderVerification(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(UiSmokeTest.BRIDGE, prefs);
        e.setDestination("Quản lý hồ sơ > Chỉnh sửa > Nút Save");
        e.process(UiSmokeTest.msg("POST", "/api/v1/profile/save"), "Proxy", true, e.activeBranch().id());

        e.setInterceptWaitEnabled(true);
        e.toggleBranchInterceptWait("Quản lý hồ sơ > Chỉnh sửa", true);
        e.proxyQueue().enqueue(SVC, "POST", "/api/v1/profile/save",
                "https://app.example.com/api/v1/profile/save",
                "Quản lý hồ sơ > Chỉnh sửa", "Chặn nhánh: Quản lý hồ sơ > Chỉnh sửa (POST)",
                null, rawReq("POST", "/api/v1/profile/save", "{\"fullName\":\"Nguyen Van A\",\"role\":\"user\"}"));
        e.proxyQueue().enqueue(SVC, "DELETE", "/api/v1/orders/409",
                "https://app.example.com/api/v1/orders/409",
                "Đơn hàng > Chi tiết", "Chặn toàn cục (DELETE)",
                null, rawReq("DELETE", "/api/v1/orders/409", ""));

        Workspaces ws = newWorkspaces(prefs, dir);
        boolean saveShots = "1".equals(System.getenv("V24_SCREENSHOTS"));
        Path outDir = Path.of("/tmp/v24_verify");
        if (saveShots) {
            Files.createDirectories(outDir);
        }

        edt(() -> {
            UIManager.put("html.disable", Boolean.TRUE);
            applyLaf(false);

            MainPanel uiLight = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v24-light");
            try {
                uiLight.tick(true);
                int headerPrefW = uiLight.headerPanel().getPreferredSize().width;
                assertTrue(headerPrefW <= 1120,
                        "MainPanel header preferred width with intercept badge must stay <= 1120px (got " + headerPrefW + ")");

                ProxyInterceptDialog.View viewL = ProxyInterceptDialog.buildView(e, uiLight, () -> { }, pinned -> { });
                try {
                    assertEquals(2, viewL.queueTable().getRowCount());
                    assertTrue(viewL.requestEditor().getText().contains("/api/v1/profile/save"));
                    assertNoRawHtmlAnywhere(viewL.panel(), "ProxyInterceptDialog Light");

                    if (saveShots) {
                        savePng(outDir.resolve("01_intercept_dialog_light.png"),
                                renderOffscreen(viewL.panel(), 1060, 640));
                    }
                } finally {
                    viewL.dispose().run();
                }

                ApiGroup focus = e.groups().get(0);
                JPanel wbL = ActionWorkbenchDialog.buildWorkbenchPanel(
                        uiLight.root(), e, focus, null, focus.branch(), ActionWorkbenchDialog.TAB_STRATEGY, () -> { }, () -> { });
                assertNoRawHtmlAnywhere(wbL, "ActionWorkbenchDialog Tab 1 V2.4");
                if (saveShots) {
                    savePng(outDir.resolve("03_workbench_tab1_v24_light.png"),
                            renderOffscreen(wbL, 1040, 690));
                    savePng(outDir.resolve("04_main_header_intercept_badge_light.png"),
                            renderOffscreen(uiLight.headerPanel(), 1200, 64));
                }
            } finally {
                uiLight.dispose();
            }

            if (saveShots) {
                applyLaf(true);
                MainPanel uiDark = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v24-dark");
                try {
                    uiDark.tick(true);
                    ProxyInterceptDialog.View viewD = ProxyInterceptDialog.buildView(e, uiDark, () -> { }, pinned -> { });
                    try {
                        savePng(outDir.resolve("02_intercept_dialog_dark.png"),
                                renderOffscreen(viewD.panel(), 1060, 640));
                    } finally {
                        viewD.dispose().run();
                    }
                } finally {
                    uiDark.dispose();
                }
            }
        });

        e.proxyQueue().releaseAll(ResolutionKind.DROP);
        e.close();
    }

    private static void assertNoRawHtmlAnywhere(Component root, String where) {
        if (root instanceof JLabel lbl && lbl.getText() != null) {
            String txt = lbl.getText().toLowerCase();
            assertFalse(txt.contains("<html") || txt.contains("<body") || txt.contains("&amp;"),
                    where + " contains raw HTML in JLabel: " + lbl.getText());
        }
        if (root instanceof AbstractButton btn && btn.getText() != null) {
            String txt = btn.getText().toLowerCase();
            assertFalse(txt.contains("<html") || txt.contains("<body"),
                    where + " contains raw HTML in button: " + btn.getText());
        }
        if (root instanceof Container c) {
            for (Component child : c.getComponents()) {
                assertNoRawHtmlAnywhere(child, where);
            }
        }
    }

    private static void applyLaf(boolean dark) {
        try {
            UIManager.setLookAndFeel(dark
                    ? new com.formdev.flatlaf.FlatDarkLaf()
                    : new com.formdev.flatlaf.FlatLightLaf());
        } catch (Exception ignored) {
            // fallback
        }
        Theme.onThemeChanged();
    }

    private static void layoutTree(Component c) {
        c.doLayout();
        if (c instanceof Container ct) {
            for (Component ch : ct.getComponents()) {
                layoutTree(ch);
            }
        }
    }

    private static BufferedImage renderOffscreen(Component c, int w, int h) {
        c.setSize(w, h);
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
            ImageIO.write(img, "png", new File(path.toString()));
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
            if (err.get() instanceof Exception ex) throw ex;
            if (err.get() instanceof Error e) throw e;
            throw new RuntimeException(err.get());
        }
    }
}
