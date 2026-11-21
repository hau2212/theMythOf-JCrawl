package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ProxyGate;
import themythofcrawl.model.Service;

/**
 * V_7.1_beta "Cổng chặn Proxy": one switch with explicit scope (mọi host / 1 host / host + path / nhánh),
 * methods and effect; persisted as one rule; header label; the dialog form round-trips settings.
 */
class V71ProxyGateTest {
    static final Service SHOP = new Service("shop.test", 443, true);
    static final Service OTHER = new Service("cdn.other.test", 443, true);

    static Engine.ProxyInterceptOutcome eval(Engine e, Service svc, String method, String path) {
        byte[] raw = (method + " " + path + " HTTP/1.1\r\nHost: " + svc.host() + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        return e.evaluateProxyIntercept(svc, raw, null);
    }

    static boolean blocked(Engine e, Service svc, String method, String path) {
        Engine.ProxyInterceptOutcome o = eval(e, svc, method, path);
        return o != null && o.shouldBlock();
    }

    @Test
    void gateScopesMethodsAndEffectsAreExplicit() {
        Engine e = V70MultiRoleTeamTest.engine(new V70MultiRoleTeamTest.Server(), "A");
        try {
            assertFalse(e.proxyGate().on());
            assertEquals("Chặn: TẮT", e.proxyGateHeaderLabel());
            assertFalse(blocked(e, SHOP, "DELETE", "/api/orders/1"));

            // 1 host + path, only DELETE, ask first
            e.setProxyGate(new ProxyGate.Settings(true, ProxyGate.Scope.HOST_PATH, "shop.test", "/api/orders", "",
                    Set.of("DELETE"), ProxyGate.Effect.ASK));
            assertTrue(eval(e, SHOP, "DELETE", "/api/orders/1").shouldWait());
            assertFalse(blocked(e, SHOP, "DELETE", "/api/users/1"), "other path passes");
            assertFalse(blocked(e, OTHER, "DELETE", "/api/orders/1"), "other host passes");
            assertFalse(blocked(e, SHOP, "POST", "/api/orders"), "other method passes");
            assertEquals("Chặn: Hỏi trước", e.proxyGateHeaderLabel());
            assertTrue(e.proxyGate().sentence().contains("shop.test/api/orders"));

            // 1 host, fake 200
            e.setProxyGate(new ProxyGate.Settings(true, ProxyGate.Scope.HOST, "shop.test", "", "",
                    Set.of("POST", "DELETE"), ProxyGate.Effect.FAKE_200));
            Engine.ProxyInterceptOutcome fake = eval(e, SHOP, "POST", "/anything");
            assertTrue(fake.shouldBlock() && !fake.shouldWait());
            assertNotNull(fake.spoofResponse(), "browser gets a fake 200");
            assertFalse(blocked(e, OTHER, "POST", "/anything"));
            assertEquals(ProxyGate.Scope.HOST, e.proxyGate().scope(), "settings read back from the persisted rule");

            // every host, drop
            e.setProxyGate(new ProxyGate.Settings(true, ProxyGate.Scope.ALL_HOSTS, "", "", "", Set.of("PUT"),
                    ProxyGate.Effect.DROP));
            assertTrue(blocked(e, OTHER, "PUT", "/x"));
            assertFalse(blocked(e, OTHER, "GET", "/x"));

            // branch scope
            e.setDestination("Toppage > Checkout");
            e.setProxyGate(new ProxyGate.Settings(true, ProxyGate.Scope.BRANCH, "", "", "Toppage > Checkout",
                    Set.of("POST"), ProxyGate.Effect.ASK));
            assertTrue(blocked(e, SHOP, "POST", "/pay"));
            e.setDestination("Toppage > Home");
            assertFalse(blocked(e, SHOP, "POST", "/pay"), "outside the branch passes");

            // switch off keeps the settings
            e.setProxyGate(e.proxyGate().withOn(false));
            assertFalse(e.proxyGate().on());
            assertEquals(ProxyGate.Scope.BRANCH, e.proxyGate().scope());
            assertFalse(blocked(e, SHOP, "POST", "/pay"));

            // A specific rule (branch) always beats the catch-all gate, even a host-scoped one
            e.setProxyGate(new ProxyGate.Settings(true, ProxyGate.Scope.HOST, "shop.test", "", "", Set.of("POST"),
                    ProxyGate.Effect.FAKE_200));
            e.actionEngine().policyLedger().upsert(themythofcrawl.core.DataProtection.rule(
                    themythofcrawl.core.DataProtection.Scope.BRANCH, "Toppage > Home", List.of("POST"),
                    themythofcrawl.core.DataProtection.Choice.ALLOW, null));
            assertFalse(blocked(e, SHOP, "POST", "/pay"), "branch ALLOW rule wins over the gate");
            e.setDestination("Toppage > Other");
            assertTrue(blocked(e, SHOP, "POST", "/pay"), "outside that branch the gate applies");
            List<themythofcrawl.model.ActionPolicy> all = e.actionEngine().policyLedger().all();
            assertEquals(ProxyGate.ID, all.get(all.size() - 1).id(), "gate is always evaluated last");

            assertEquals("Chưa chọn host", new ProxyGate.Settings(true, ProxyGate.Scope.HOST, "", "", "", Set.of("POST"),
                    ProxyGate.Effect.ASK).problem());
            assertNull(ProxyGate.Settings.defaults().withOn(true).problem());
        } finally {
            e.close();
        }
    }

    @Test
    void dialogFormRoundTripsAndApplies() throws Exception {
        Engine e = V70MultiRoleTeamTest.engine(new V70MultiRoleTeamTest.Server(), "A");
        try {
            e.process(V70MultiRoleTeamTest.msg("DELETE", "/api/orders/9", "ADMIN_TOKEN"), "Proxy", true, e.activeBranch().id());
            SwingUtilities.invokeAndWait(() -> {
                ProxyGateDialog.Gate gate = new ProxyGateDialog.Gate(e, e.groups().get(0), null);
                assertFalse(gate.formSettings().on());
                gate.scopeButtons.get(ProxyGate.Scope.HOST).setSelected(true);
                gate.hostBox.setSelectedItem("shop.test");
                gate.effectButtons.get(ProxyGate.Effect.FAKE_200).setSelected(true);
                gate.master.setOn(true, false);
                gate.applyNow();
                ProxyGate.Settings s = e.proxyGate();
                assertTrue(s.on());
                assertEquals(ProxyGate.Scope.HOST, s.scope());
                assertEquals("shop.test", s.host());
                assertEquals(ProxyGate.Effect.FAKE_200, s.effect());
                assertEquals(Set.of("POST", "PUT", "PATCH", "DELETE"), s.methods(), "default: only data-changing methods");
                gate.runTest();
                assertTrue(gate.testResult.getText().startsWith("Giữ lại"), gate.testResult.getText());
                assertNotNull(DataProtectionDialog.build(null, e, e.groups().get(0), null));
            });
            assertTrue(List.of(ProxyGate.Effect.values()).stream().allMatch(x -> !x.what.isBlank() && !x.when.isBlank()));
        } finally {
            e.close();
        }
    }
}
