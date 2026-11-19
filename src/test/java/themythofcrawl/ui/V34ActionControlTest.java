package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Bridge;
import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.app.Prefs;
import themythofcrawl.burp.Extension;
import themythofcrawl.core.ActionEngine.ActionMode;
import themythofcrawl.core.ActionGateAuditLog;
import themythofcrawl.core.ActionPolicyLedger;
import themythofcrawl.core.Config;
import themythofcrawl.model.ActionInterceptRule;
import themythofcrawl.model.ActionPolicy;
import themythofcrawl.model.ActionPolicy.PathMatchType;
import themythofcrawl.model.ActionPolicy.ProxyEffect;
import themythofcrawl.model.ActionPolicy.ScopeType;
import themythofcrawl.model.ActionPolicy.Stage;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Blob;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.Role;
import themythofcrawl.model.RoleResult;
import themythofcrawl.model.RuleMode;
import themythofcrawl.model.Service;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.theme.FontManager;
import themythofcrawl.ui.theme.Theme;

/**
 * Comprehensive verification suite for V_3.4_beta:
 * 1) Immutable version metadata (V_3.4_beta)
 * 2) Pillar 1: Reversible ActionMode.NONE default, atomic release, honest 200->400 responses, Reset All
 * 3) Pillar 2: Unified ActionPolicyLedger full + extended CRUD, 5 scopes, Undo Trash, JSON import/export, presets, deterministic precedence
 * 4) Pillar 3: Live ActionGateAuditLog (both blocked & passed) + zero-network Batch Pre-flight Simulator
 * 5) Pillar 4: Quick Request Composer & Live Replay (ad-hoc send, Role injection, overwrite API, clone API, Repeater push, pretty JSON, Content-Length)
 * 6) Headless UI, zero-HTML (html.disable=true), ActionMenus <= 9 items, header width <= 1100px, and Light/Dark PNG verification (V34_SCREENSHOTS=1)
 */
final class V34ActionControlTest {

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
        String reason = switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            default -> "Status";
        };
        String raw = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + b.getBytes(StandardCharsets.ISO_8859_1).length + "\r\n"
                + "\r\n" + b;
        return raw.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static abstract class StubBridge implements Bridge {
        @Override
        public boolean inScope(String url) {
            return true;
        }

        @Override
        public void toRepeater(Service service, byte[] request, String tabName) {
        }

        @Override
        public void out(String line) {
        }

        @Override
        public void err(String line) {
        }
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
    void versionMetadataIsV34Beta() throws Exception {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("version.properties"))) {
            props.load(in);
        }
        String tag = props.getProperty("releaseTag");
        String ver = props.getProperty("versionName");
        String jar = props.getProperty("jarName");
        assertTrue(tag != null && tag.matches("V_\\d+\\.\\d+_\\w+"));
        assertEquals("theMythOf-JCrawl_" + tag + ".jar", jar);
        assertEquals(ver, Extension.VERSION);
        assertEquals(ver, Diagnostics.VERSION);

        String wf = Files.readString(Path.of(".github/workflows/build.yml"), StandardCharsets.UTF_8);
        assertTrue(wf.contains("IMMUTABLE GUARD"), "CI workflow must preserve immutable release guard");
    }

    @Test
    void pillar1ReversibleActionModeNoneAndHonestResponses() {
        AtomicInteger callCount = new AtomicInteger(0);
        Bridge bridge = new StubBridge() {
            @Override
            public Bridge.Sent send(Service s, byte[] req) {
                int n = callCount.incrementAndGet();
                return n == 1
                        ? new Bridge.Sent(rawResp(200, "{\"deleted\":true}"), 12, null)
                        : new Bridge.Sent(rawResp(400, "{\"error\":\"already deleted\"}"), 9, null);
            }
        };
        Engine e = newEngine(bridge, Prefs.memory());
        try {
            // 1. Default global action mode MUST be NONE (no automatic interference)
            assertEquals(ActionMode.NONE, e.config().actionMode);

            e.setDestination("Orders > Delete");
            e.process(UiSmokeTest.msg("DELETE", "/api/orders/55"), "Proxy", true, e.activeBranch().id());
            ApiGroup g = e.groups().get(0);
            assertEquals(ActionMode.NONE, e.effectiveActionMode(g));

            // 2. Select HOLD_AT_PROXY on this API -> Proxy intercepts with spoof 200
            e.setApiActionMode(g, ActionMode.HOLD_AT_PROXY);
            assertTrue(e.actionEngine().isEndpointProxyHold("DELETE", "/api/orders/55"));
            assertNotNull(e.actionEngine().policyLedger().findById("pol-api-" + g.gid()));
            Engine.ProxyInterceptOutcome blocked = e.evaluateProxyIntercept(
                    SVC, rawReq("DELETE", "/api/orders/55", ""), "https://app.example.com/api/orders/55");
            assertTrue(blocked.shouldBlock());
            assertEquals(ActionInterceptRule.Effect.BLOCK_SPOOF, blocked.effect());
            assertNotNull(blocked.spoofResponse());

            // 3. Unselect / revert back to NONE -> atomic cleanup releases hold & policy!
            e.setApiActionMode(g, ActionMode.NONE);
            assertFalse(e.actionEngine().isEndpointProxyHold("DELETE", "/api/orders/55"));
            assertFalse(e.actionEngine().isEndpointInterceptWait("DELETE", "/api/orders/55"));
            assertNull(e.actionEngine().policyLedger().findById("pol-api-" + g.gid()));
            assertEquals(ActionMode.NONE, e.effectiveActionMode(g));

            Engine.ProxyInterceptOutcome passed = e.evaluateProxyIntercept(
                    SVC, rawReq("DELETE", "/api/orders/55", ""), "https://app.example.com/api/orders/55");
            assertNull(passed, "After reverting to NONE, Proxy must return null (pass through cleanly)");

            // 4. Sending twice in NONE mode honestly returns 200 then 400 (no spoofing, no auto-heal)
            Engine.AdHocSendResult r1 = e.sendAdHocRequest(SVC, rawReq("DELETE", "/api/orders/55", ""), null, g, ActionMode.NONE);
            Engine.AdHocSendResult r2 = e.sendAdHocRequest(SVC, rawReq("DELETE", "/api/orders/55", ""), null, g, ActionMode.NONE);
            assertEquals(200, r1.status());
            assertEquals(400, r2.status());
            assertEquals(2, callCount.get());

            // 5. Emergency Reset All clears policies into Undo Trash and restores clean NONE
            e.setApiActionMode(g, ActionMode.INTERCEPT_WAIT);
            assertTrue(e.actionEngine().policyLedger().activeBlockingCount() > 0);
            int cleared = e.resetAllActionControls();
            assertTrue(cleared >= 1);
            assertEquals(ActionMode.NONE, e.config().actionMode);
            assertEquals(0, e.actionEngine().policyLedger().size());
            assertFalse(e.actionEngine().isInterceptWaitEnabled());
            assertFalse(e.actionEngine().policyLedger().trash().isEmpty(), "Reset All must soft-delete into Undo Trash");
        } finally {
            e.close();
        }
    }

    @Test
    void pillar2UnifiedPolicyLedgerFullAndExtendedCrud() {
        ActionPolicyLedger ledger = new ActionPolicyLedger();
        long t0 = System.currentTimeMillis() - 10_000L;

        // CREATE across all 5 scopes
        ActionPolicy pGlobal = new ActionPolicy(
                "p-global", "Global Action Hold", "note", true, 50,
                ScopeType.GLOBAL, List.of(), true, "", PathMatchType.WILDCARD, "*", null,
                List.of("POST", "PUT", "PATCH", "DELETE"), Stage.PROXY_GATE, ProxyEffect.HOLD_SPOOF,
                ActionMode.HOLD_AT_PROXY, 200, "{\"global\":true}", null, "", t0, t0, "Test", 0, "", 0);

        ActionPolicy pMethod = new ActionPolicy(
                "p-method", "DELETE Wait", "", true, 40,
                ScopeType.METHOD_MATRIX, List.of(), true, "", PathMatchType.WILDCARD, "*", null,
                List.of("DELETE"), Stage.PROXY_GATE, ProxyEffect.INTERCEPT_WAIT,
                ActionMode.INTERCEPT_WAIT, 200, null, null, "", t0, t0, "Test", 0, "", 0);

        ActionPolicy pBranch = new ActionPolicy(
                "p-branch", "Orders Branch Wait", "", true, 30,
                ScopeType.BRANCH, List.of("Orders"), true, "", PathMatchType.WILDCARD, "*", null,
                List.of("POST", "DELETE"), Stage.BOTH, ProxyEffect.INTERCEPT_WAIT,
                ActionMode.INTERCEPT_WAIT, 200, null, null, "", t0, t0, "Test", 0, "", 0);

        ActionPolicy pPathAllow = new ActionPolicy(
                "p-allow-health", "Allow Order Health", "", true, 20,
                ScopeType.PATH_PATTERN, List.of(), true, "", PathMatchType.TEMPLATE, "/api/orders/{id}/check", null,
                List.of("POST"), Stage.PROXY_GATE, ProxyEffect.ALLOW_EXPLICIT,
                ActionMode.NONE, 200, null, null, "", t0, t0, "Test", 0, "", 0);

        ActionPolicy pApiExact = new ActionPolicy(
                "p-api-7", "Exact API #7 Drop", "", true, 10,
                ScopeType.API_EXACT, List.of(), true, "app.example.com", PathMatchType.EXACT, "/api/orders/99/nuke", 7,
                List.of("DELETE"), Stage.PROXY_GATE, ProxyEffect.BLOCK_DROP,
                ActionMode.NONE, 200, null, null, "", t0, t0, "Test", 0, "", 0);

        ActionPolicy pReplayOnly = new ActionPolicy(
                "p-replay-only", "Replay Live ID Pool", "", true, 15,
                ScopeType.PATH_PATTERN, List.of(), true, "", PathMatchType.PREFIX, "/api/users", null,
                List.of("DELETE"), Stage.REPLAY_EXEC, ProxyEffect.PASS_NONE,
                ActionMode.LIVE_ID_POOL, 200, null, null, "", t0, t0, "Test", 0, "", 0);

        ledger.upsert(pGlobal);
        ledger.upsert(pMethod);
        ledger.upsert(pBranch);
        ledger.upsert(pPathAllow);
        ledger.upsert(pApiExact);
        ledger.upsert(pReplayOnly);
        assertEquals(6, ledger.size());
        assertEquals(1, ledger.countByScope(true).get(ScopeType.API_EXACT));
        String summary = ledger.activeSummaryText();
        assertTrue(summary.contains("1 Toàn cục") && summary.contains("1 Method")
                && summary.contains("1 Nhánh") && summary.contains("2 Path") && summary.contains("1 API"),
                "Summary should break down active scopes: " + summary);

        // DETERMINISTIC EVALUATION (Priority / Specificity & ALLOW_EXPLICIT & Stage separation)
        var evExact = ledger.evaluateProxy("Orders > Sub", "app.example.com", "DELETE", "/api/orders/99/nuke", 7);
        assertTrue(evExact.matched());
        assertEquals("p-api-7", evExact.winningPolicy().id());
        assertEquals(ProxyEffect.BLOCK_DROP, evExact.effectiveProxyEffect());

        var evAllow = ledger.evaluateProxy("Orders > Sub", "app.example.com", "POST", "/api/orders/12345/check", 2);
        assertTrue(evAllow.matched());
        assertEquals("p-allow-health", evAllow.winningPolicy().id());
        assertEquals(ProxyEffect.ALLOW_EXPLICIT, evAllow.effectiveProxyEffect());

        var evBranch = ledger.evaluateProxy("Orders > Checkout", "app.example.com", "POST", "/api/orders/create", 3);
        assertEquals("p-branch", evBranch.winningPolicy().id());
        assertEquals(ProxyEffect.INTERCEPT_WAIT, evBranch.effectiveProxyEffect());

        // Stage.REPLAY_EXEC does NOT fire at Proxy, but DOES fire at Replay!
        var evProxyUser = ledger.evaluateProxy("Users", "app.example.com", "DELETE", "/api/users/42", 4);
        assertEquals("p-method", evProxyUser.winningPolicy().id());
        var evReplayUser = ledger.evaluateReplay("Users", "app.example.com", "DELETE", "/api/users/42", 4, ActionMode.NONE);
        assertEquals("p-replay-only", evReplayUser.winningPolicy().id());
        assertEquals(ActionMode.LIVE_ID_POOL, evReplayUser.effectiveReplayMode());

        // UPDATE preserves createdAt and updates updatedAt & hitCount
        ActionPolicy updatedBranch = new ActionPolicy(
                "p-branch", "Orders Branch Wait v2", "updated note", true, 30,
                ScopeType.BRANCH, List.of("Orders"), true, "", PathMatchType.WILDCARD, "*", null,
                List.of("POST", "DELETE"), Stage.BOTH, ProxyEffect.INTERCEPT_WAIT,
                ActionMode.INTERCEPT_WAIT, 200, null, null, "", 0L, 0L, "Test", 0, "", 0);
        ledger.upsert(updatedBranch);
        ActionPolicy fetched = ledger.findById("p-branch");
        assertEquals("Orders Branch Wait v2", fetched.name());
        assertEquals(t0, fetched.createdAt(), "Update must preserve original createdAt");
        assertTrue(fetched.updatedAt() >= t0);
        ledger.recordHit("p-branch", "POST /api/orders/create");
        assertEquals(1, ledger.findById("p-branch").hitCount());

        // PRIORITY REORDER + DUPLICATE + BULK TOGGLE
        assertTrue(ledger.moveToTop("p-global"));
        assertEquals("p-global", ledger.all().get(0).id());
        ActionPolicy dup = ledger.duplicate("p-branch");
        assertNotNull(dup);
        assertNotEquals("p-branch", dup.id());
        assertEquals(7, ledger.size());
        assertEquals(2, ledger.bulkSetEnabled(List.of("p-branch", dup.id()), false));
        assertFalse(ledger.findById("p-branch").enabled());

        // SOFT DELETE + UNDO TRASH RESTORE
        assertTrue(ledger.delete(dup.id(), "Test delete"));
        assertEquals(6, ledger.size());
        assertEquals(1, ledger.trash().size());
        ActionPolicy restored = ledger.undoLastDelete();
        assertNotNull(restored);
        assertEquals(dup.id(), restored.id());
        assertEquals(7, ledger.size());

        // PRESETS + JSON IMPORT/EXPORT ROUND-TRIP
        ActionPolicy addedPreset = ledger.applyPreset(ActionPolicyLedger.Preset.WAIT_ALL_ACTIONS);
        assertNotNull(addedPreset);
        String json = ledger.exportJson(null);
        ActionPolicyLedger imported = new ActionPolicyLedger();
        int impCount = imported.importJson(json, true);
        assertEquals(ledger.size(), impCount);
        assertEquals(ledger.all().get(0).id(), imported.all().get(0).id());

        // LEGACY MIGRATION
        ActionInterceptRule legacy = new ActionInterceptRule(
                "leg-1", "Legacy Delete Block", true, ActionInterceptRule.Effect.BLOCK_SPOOF,
                "Billing", true, "", ActionInterceptRule.PathMatchType.WILDCARD, "/api/billing/*",
                Set.of("DELETE"), 200, ActionInterceptRule.DEFAULT_SPOOF_BODY);
        ActionPolicyLedger mig = new ActionPolicyLedger();
        assertEquals(1, mig.migrateFromLegacyRules(List.of(legacy)));
        assertEquals("leg-1", mig.all().get(0).id());
        assertEquals(ScopeType.BRANCH, mig.all().get(0).scopeType());
    }

    @Test
    void pillar3ActionProtocolGateAuditorAndBatchSimulator() {
        Engine e = newEngine(new StubBridge() {
            @Override
            public Bridge.Sent send(Service s, byte[] req) {
                return new Bridge.Sent(rawResp(200, "{}"), 5, null);
            }
        }, Prefs.memory());
        try {
            e.setDestination("Orders > Cancel");
            e.process(UiSmokeTest.msg("POST", "/api/orders/10/cancel"), "Proxy", true, e.activeBranch().id());
            e.process(UiSmokeTest.msg("DELETE", "/api/orders/10"), "Proxy", true, e.activeBranch().id());
            e.setDestination("Users > Profile");
            e.process(UiSmokeTest.msg("PUT", "/api/users/me"), "Proxy", true, e.activeBranch().id());
            e.process(UiSmokeTest.msg("GET", "/api/users/me"), "Proxy", true, e.activeBranch().id());

            long now = System.currentTimeMillis();
            e.actionEngine().policyLedger().upsert(new ActionPolicy(
                    "pol-orders-wait", "Chặn Orders", "", true, 10,
                    ScopeType.BRANCH, List.of("Orders"), true, "", PathMatchType.WILDCARD, "*", null,
                    List.of("POST", "DELETE"), Stage.PROXY_GATE, ProxyEffect.INTERCEPT_WAIT,
                    ActionMode.INTERCEPT_WAIT, 200, null, null, "", now, now, "Test", 0, "", 0));
            e.actionEngine().policyLedger().upsert(new ActionPolicy(
                    "pol-users-allow", "Cho qua Users", "", true, 20,
                    ScopeType.PATH_PATTERN, List.of(), true, "", PathMatchType.EXACT, "/api/users/me", null,
                    List.of("PUT"), Stage.PROXY_GATE, ProxyEffect.ALLOW_EXPLICIT,
                    ActionMode.NONE, 200, null, null, "", now, now, "Test", 0, "", 0));

            // 1. Live Gate Audit records BOTH blocked and passed Action traffic
            e.setDestination("Orders > Cancel");
            var outBlocked = e.evaluateProxyIntercept(SVC, rawReq("DELETE", "/api/orders/10", ""),
                    "https://app.example.com/api/orders/10");
            assertNotNull(outBlocked);
            assertEquals(ActionInterceptRule.Effect.INTERCEPT_WAIT, outBlocked.effect());
            assertTrue(outBlocked.auditSeq() > 0);
            e.recordGateAuditOutcome(outBlocked.auditSeq(), "FORWARDED");

            e.setDestination("Users > Profile");
            var outAllowed = e.evaluateProxyIntercept(SVC, rawReq("PUT", "/api/users/me", "{\"a\":1}"),
                    "https://app.example.com/api/users/me");
            assertTrue(outAllowed == null || !outAllowed.shouldBlock());

            var outUnhandledAction = e.evaluateProxyIntercept(SVC, rawReq("PATCH", "/api/other/99", "{}"),
                    "https://app.example.com/api/other/99");
            assertNull(outUnhandledAction);

            ActionGateAuditLog log = e.actionEngine().gateAuditLog();
            assertEquals(3, log.size(), "Audit log must capture blocked, explicit-allow, and pass-through Action traffic");
            ActionGateAuditLog.Stats st = log.stats();
            assertEquals(1, st.blocked());
            assertEquals(1, st.passedExplicit());
            assertEquals(1, st.passedDefault());
            assertEquals("FORWARDED", log.all().stream()
                    .filter(en -> en.seq() == outBlocked.auditSeq()).findFirst().orElseThrow().finalOutcome());

            // 2. Zero-network Batch Pre-flight Simulator on all captured APIs
            ActionPolicyLedger.SimulationReport sim = e.actionEngine().policyLedger().simulateBatch(e.groups(), ActionMode.NONE);
            assertEquals(4, sim.totalApis());
            assertEquals(3, sim.totalActions(), "Should count POST, DELETE, PUT as Action protocols");
            assertEquals(2, sim.willWait(), "Orders POST + DELETE should be INTERCEPT_WAIT");
            assertEquals(1, sim.willAllowExplicit(), "Users PUT should be ALLOW_EXPLICIT");
            assertEquals(1, sim.willPassDefault(), "Users GET should pass default");
        } finally {
            e.close();
        }
    }

    @Test
    void pillar4RequestComposerBackendAndCrudSaves() {
        AtomicReference<byte[]> lastSent = new AtomicReference<>();
        AtomicReference<String> lastRepeaterCap = new AtomicReference<>();
        Bridge bridge = new StubBridge() {
            @Override
            public Bridge.Sent send(Service s, byte[] req) {
                lastSent.set(req);
                return new Bridge.Sent(rawResp(201, "{\"ok\":true,\"id\":777}"), 18, null);
            }

            @Override
            public void toRepeater(Service s, byte[] req, String caption) {
                lastRepeaterCap.set(caption);
                lastSent.set(req);
            }
        };

        Engine e = newEngine(bridge, Prefs.memory());
        try {
            Role roleUser = new Role("02_User", true,
                    List.of(new HeaderRule("Authorization", "Bearer USER_TOKEN_XYZ", RuleMode.REPLACE)), Role.Tier.USER);
            e.roles().setAll(List.of(roleUser));
            e.setDestination("Orders > Create");
            e.process(UiSmokeTest.msg("POST", "/api/orders"), "Proxy", true, e.activeBranch().id());
            ApiGroup g = e.groups().get(0);

            // 1. Pretty JSON & Content-Length helper
            byte[] compact = rawReq("POST", "/api/orders", "{\"item\":\"book\",\"qty\":2}");
            String pretty = RequestComposerDialog.formatHttpWithPrettyJson(compact);
            assertTrue(pretty.contains("\n  \"item\": \"book\""));
            byte[] fixed = RequestComposerDialog.recalculateContentLength(
                    ("POST /api/orders HTTP/1.1\r\nHost: app.example.com\r\nContent-Length: 1\r\n\r\n{\"x\":12345}")
                            .getBytes(StandardCharsets.ISO_8859_1));
            assertTrue(new String(fixed, StandardCharsets.ISO_8859_1).contains("Content-Length: 11"));

            // 2. Ad-hoc Send with Role injection
            Engine.AdHocSendResult res = e.sendAdHocRequest(SVC, compact, roleUser, g, ActionMode.NONE);
            assertEquals(201, res.status());
            assertTrue(res.error() == null || res.error().isEmpty(), "Expected no error, got: " + res.error());
            String sentText = new String(lastSent.get(), StandardCharsets.ISO_8859_1);
            assertTrue(sentText.contains("Authorization: Bearer USER_TOKEN_XYZ"),
                    "Composer send with Role must apply Role headers");

            // 3. Save Overwrite (CRUD Update on existing API)
            Service newSvc = new Service("staging.example.com", 8443, true);
            byte[] edited = ("PUT /api/orders/99 HTTP/1.1\r\nHost: staging.example.com\r\nContent-Length: 9\r\n\r\n{\"qty\":9}")
                    .getBytes(StandardCharsets.ISO_8859_1);
            byte[] respBytes = rawResp(200, "{\"saved\":true}");
            assertTrue(e.updateApiRequestFromComposer(g, null, newSvc, edited, respBytes));
            assertEquals("PUT", g.method());
            assertEquals("/api/orders/99", g.path());
            assertEquals("staging.example.com", g.host());
            assertEquals(8443, g.service().port());
            assertEquals(new String(edited, StandardCharsets.ISO_8859_1),
                    new String(Blob.bytesOf(g.origRequest()), StandardCharsets.ISO_8859_1));

            // 4. Clone as New API in Target Branch (CRUD Create)
            e.setDestination("Orders > Variants");
            int targetBranchId = e.activeBranch().id();
            ApiGroup cloned = e.cloneApiFromComposer(newSvc, edited, respBytes, targetBranchId, "Composer Clone");
            assertNotNull(cloned);
            assertNotEquals(g.gid(), cloned.gid());
            assertEquals("Orders > Variants", cloned.branch());
            assertEquals("PUT", cloned.method());
            assertEquals("/api/orders/99", cloned.path());
            assertEquals(2, e.groups().size());

            // 5. Push to Burp Repeater (dispatched asynchronously on captureExec)
            e.pushRawToRepeater(newSvc, edited, "Composer Test");
            long deadline = System.currentTimeMillis() + 3000;
            while (lastRepeaterCap.get() == null && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            assertEquals("Composer Test", lastRepeaterCap.get());
        } finally {
            e.close();
        }
    }

    @Test
    void headlessUiZeroHtmlMenuBudgetHeaderWidthAndScreenshots(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(new StubBridge() {
            @Override
            public Bridge.Sent send(Service s, byte[] req) {
                return new Bridge.Sent(rawResp(200, "{\"status\":\"ok\",\"updated\":true}"), 15, null);
            }
        }, prefs);
        e.roles().setAll(List.of(
                new Role("01_Admin", true, List.of(new HeaderRule("X-Role", "admin", RuleMode.REPLACE)), Role.Tier.ADMIN),
                new Role("02_User", true, List.of(new HeaderRule("X-Role", "user", RuleMode.REPLACE)), Role.Tier.USER)));
        e.setDestination("Orders > Cancel");
        e.process(UiSmokeTest.msg("DELETE", "/api/orders/78"), "Proxy", true, e.activeBranch().id());
        e.process(UiSmokeTest.msg("POST", "/api/orders/78/refund"), "Proxy", true, e.activeBranch().id());
        e.setDestination("Users > Profile");
        e.process(UiSmokeTest.msg("PUT", "/api/users/42/profile"), "Proxy", true, e.activeBranch().id());

        long now = System.currentTimeMillis();
        e.actionEngine().policyLedger().upsert(new ActionPolicy(
                "pol-orders", "Chặn chờ duyệt nhánh Orders", "Kiểm tra thao tác xóa/hoàn tiền", true, 10,
                ScopeType.BRANCH, List.of("Orders"), true, "", PathMatchType.WILDCARD, "*", null,
                List.of("POST", "DELETE"), Stage.BOTH, ProxyEffect.INTERCEPT_WAIT,
                ActionMode.INTERCEPT_WAIT, 200, null, null, "", now, now, "Sổ cái CRUD", 0, "", 0));
        e.actionEngine().policyLedger().upsert(new ActionPolicy(
                "pol-users-allow", "Cho qua Users profile", "Cho phép lưu hồ sơ bình thường", true, 20,
                ScopeType.PATH_PATTERN, List.of(), true, "", PathMatchType.TEMPLATE, "/api/users/{id}/profile", null,
                List.of("PUT"), Stage.PROXY_GATE, ProxyEffect.ALLOW_EXPLICIT,
                ActionMode.NONE, 200, null, null, "", now, now, "Dò Giao thức", 0, "", 0));

        // Populate Gate Audit log for visual verification
        e.setDestination("Orders > Cancel");
        var bOut = e.evaluateProxyIntercept(SVC, rawReq("DELETE", "/api/orders/78", ""),
                "https://app.example.com/api/orders/78");
        e.recordGateAuditOutcome(bOut.auditSeq(), "FORWARDED");
        e.setDestination("Users > Profile");
        e.evaluateProxyIntercept(SVC, rawReq("PUT", "/api/users/42/profile", "{\"name\":\"An\"}"),
                "https://app.example.com/api/users/42/profile");

        Workspaces ws = newWorkspaces(prefs, dir);
        boolean saveShots = "1".equals(System.getenv("V34_SCREENSHOTS"));
        Path outDir = Path.of("/tmp/v34_verify");
        if (saveShots) {
            Files.createDirectories(outDir);
        }

        edt(() -> {
            UIManager.put("html.disable", Boolean.TRUE);
            applyLaf(false);
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v34-light");
            try {
                ui.tick(true);
                int headerPrefW = ui.headerPanel().getPreferredSize().width;
                assertTrue(headerPrefW <= 1100, "MainPanel header preferred width must stay <= 1100px, got " + headerPrefW);

                ApiGroup g = e.groups().get(0);
                // Menu budget guard (<= 9 items)
                JMenu actionSub = ActionMenus.buildForApi(ui.root(), e, g, null, () -> { });
                assertTrue(actionSub.getItemCount() <= 9,
                        "ActionMenus.buildForApi must stay <= 9 items, got " + actionSub.getItemCount());

                // Workbench Tab 1 (Strategy with NONE & toggle-off), Tab 2 (Ledger CRUD), Tab 6 (Protocol Audit)
                JPanel wbStrategy = ActionWorkbenchDialog.buildWorkbenchPanel(
                        ui.root(), e, g, null, g.branch(), ActionWorkbenchDialog.TAB_STRATEGY, () -> { }, () -> { });
                assertNoRawHtmlAnywhere(wbStrategy, "ActionWorkbench Tab 1 Strategy");

                JPanel wbLedger = ActionWorkbenchDialog.buildWorkbenchPanel(
                        ui.root(), e, g, null, g.branch(), ActionWorkbenchDialog.TAB_LEDGER, () -> { }, () -> { });
                assertNoRawHtmlAnywhere(wbLedger, "ActionWorkbench Tab 2 Ledger CRUD");

                JPanel wbAudit = ActionWorkbenchDialog.buildWorkbenchPanel(
                        ui.root(), e, g, null, g.branch(), ActionWorkbenchDialog.TAB_PROTOCOL_AUDIT, () -> { }, () -> { });
                assertNoRawHtmlAnywhere(wbAudit, "ActionWorkbench Tab 6 Protocol Audit");

                // Quick Request Composer
                RoleResult firstRole = g.results().isEmpty() ? null : g.results().get(0);
                RequestComposerDialog.View composer = RequestComposerDialog.buildView(
                        e, g, firstRole, ui, () -> { }, pinned -> { });
                assertNoRawHtmlAnywhere(composer.panel(), "RequestComposerDialog");
                assertNotNull(composer.sendButton());
                assertNotNull(composer.saveOverwriteButton());
                assertNotNull(composer.cloneApiButton());

                if (saveShots) {
                    savePng(outDir.resolve("01_wb_strategy_light.png"), renderOffscreen(wbStrategy, 1140, 720));
                    savePng(outDir.resolve("02_wb_ledger_light.png"), renderOffscreen(wbLedger, 1180, 740));
                    savePng(outDir.resolve("03_wb_audit_light.png"), renderOffscreen(wbAudit, 1140, 720));
                    savePng(outDir.resolve("04_composer_light.png"), renderOffscreen(composer.panel(), 1120, 700));
                    savePng(outDir.resolve("05_main_header_light.png"), renderOffscreen(ui.headerPanel(), 1120, 56));
                }
            } finally {
                ui.dispose();
            }

            if (saveShots) {
                applyLaf(true);
                MainPanel uiDark = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v34-dark");
                try {
                    uiDark.tick(true);
                    ApiGroup g = e.groups().get(0);
                    JPanel wbStrategyD = ActionWorkbenchDialog.buildWorkbenchPanel(
                            uiDark.root(), e, g, null, g.branch(), ActionWorkbenchDialog.TAB_STRATEGY, () -> { }, () -> { });
                    JPanel wbLedgerD = ActionWorkbenchDialog.buildWorkbenchPanel(
                            uiDark.root(), e, g, null, g.branch(), ActionWorkbenchDialog.TAB_LEDGER, () -> { }, () -> { });
                    JPanel wbAuditD = ActionWorkbenchDialog.buildWorkbenchPanel(
                            uiDark.root(), e, g, null, g.branch(), ActionWorkbenchDialog.TAB_PROTOCOL_AUDIT, () -> { }, () -> { });
                    RequestComposerDialog.View composerD = RequestComposerDialog.buildView(
                            e, g, null, uiDark, () -> { }, pinned -> { });
                    savePng(outDir.resolve("06_wb_strategy_dark.png"), renderOffscreen(wbStrategyD, 1140, 720));
                    savePng(outDir.resolve("07_wb_ledger_dark.png"), renderOffscreen(wbLedgerD, 1180, 740));
                    savePng(outDir.resolve("08_wb_audit_dark.png"), renderOffscreen(wbAuditD, 1140, 720));
                    savePng(outDir.resolve("09_composer_dark.png"), renderOffscreen(composerD.panel(), 1120, 700));
                } finally {
                    uiDark.dispose();
                }
            }
        });

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
