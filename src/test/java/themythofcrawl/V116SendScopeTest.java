package themythofcrawl;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import themythofcrawl.app.Bridge;
import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.Config;
import themythofcrawl.core.ForwardDeliveryGuard;
import themythofcrawl.core.GateScope;
import themythofcrawl.core.ProxyGate;
import themythofcrawl.model.Service;

/** V_11.6: held "Gửi lên Server" goes through Burp, the Proxy-mode guard waits for a response, extra gate scope. */
class V116SendScopeTest {

    private static final class StubBridge implements Bridge {
        final AtomicInteger sends = new AtomicInteger();
        final AtomicInteger quiet = new AtomicInteger();
        volatile String lastReq = "";
        boolean scope = true;

        @Override public boolean inScope(String url) { return scope && url.contains("shop.vn"); }
        @Override public Sent send(Service s, byte[] req) {
            sends.incrementAndGet();
            lastReq = new String(req, StandardCharsets.ISO_8859_1);
            return new Sent("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.ISO_8859_1), 3, null);
        }
        @Override public Sent sendQuiet(Service s, byte[] req) {
            quiet.incrementAndGet();
            return send(s, req);
        }
        @Override public void toRepeater(Service service, byte[] request, String tabName) { }
        @Override public void out(String line) { }
        @Override public void err(String line) { }
    }

    @Test
    void guardWaitsForResponseAfterToBeSent() throws Exception {
        try (ForwardDeliveryGuard g = new ForwardDeliveryGuard(100)) {
            CountDownLatch fired = new CountDownLatch(1);
            AtomicInteger runs = new AtomicInteger();
            Runnable fb = () -> {
                runs.incrementAndGet();
                fired.countDown();
            };
            // Burp calls ToBeSent but the browser side is gone: no response -> fallback after the wait
            g.arm(1, fb);
            assertTrue(g.markSent(1, 150, fb));
            assertTrue(fired.await(3, TimeUnit.SECONDS));
            Thread.sleep(300);
            assertEquals(1, runs.get(), "fallback runs exactly once");

            // normal case: ToBeSent then a response -> never falls back
            AtomicInteger runs2 = new AtomicInteger();
            Runnable fb2 = runs2::incrementAndGet;
            g.arm(2, fb2);
            assertTrue(g.markSent(2, 150, fb2));
            assertTrue(g.markResponse(2));
            Thread.sleep(400);
            assertEquals(0, runs2.get());

            // Burp never even called ToBeSent -> the first grace fires
            AtomicInteger runs3 = new AtomicInteger();
            g.arm(3, runs3::incrementAndGet);
            Thread.sleep(400);
            assertEquals(1, runs3.get());
            assertFalse(g.markSent(3, 150, runs3::incrementAndGet), "already handled");
        }
    }

    @Test
    void defaultHeldForwardIsSentByJCrawl() {
        Config c = new Config();
        assertFalse(c.heldForwardViaProxy, "default: JCrawl sends it through Burp");
        Config back = Config.fromMap(c.toMap());
        assertFalse(back.heldForwardViaProxy);
        c.heldForwardViaProxy = true;
        c.gateHosts = "*.shop.vn\napi.x.com:8443";
        c.gateBurpScope = true;
        assertTrue(Config.fromMap(c.toMap()).heldForwardViaProxy);
        assertEquals("*.shop.vn\napi.x.com:8443", Config.fromMap(c.toMap()).gateHosts);
        assertTrue(Config.fromMap(c.toMap()).gateBurpScope);
    }

    @Test
    void gateScopeMatching() {
        GateScope none = new GateScope(false, List.of(), List.of(), List.of(), false);
        assertFalse(none.active());
        assertNull(none.rejects("any.host", 443, "/x", null));

        GateScope hosts = new GateScope(false, List.of("*.shop.vn", "api.x.com:8443"), List.of("cdn.*"), List.of(), false);
        assertNull(hosts.rejects("api.shop.vn", 443, "/a", null));
        assertNull(hosts.rejects("shop.vn", 443, "/a", null), "*.shop.vn covers the bare domain");
        assertNull(hosts.rejects("api.x.com", 8443, "/a", null));
        assertNotNull(hosts.rejects("api.x.com", 443, "/a", null), "wrong port");
        assertNotNull(hosts.rejects("evil-shop.vn", 443, "/a", null));
        assertNotNull(hosts.rejects("cdn.shop.vn", 443, "/a", null), "excluded wins");

        GateScope.Facts in = new GateScope.Facts() {
            public boolean inBurpScope() { return true; }
            public boolean inExcel() { return false; }
        };
        GateScope.Facts out = new GateScope.Facts() {
            public boolean inBurpScope() { return false; }
            public boolean inExcel() { return true; }
        };
        GateScope burp = new GateScope(true, List.of(), List.of(), List.of(), false);
        assertNull(burp.rejects("a.b", 443, "/", in));
        assertNotNull(burp.rejects("a.b", 443, "/", out));
        GateScope burpOrHost = new GateScope(true, List.of("a.b"), List.of(), List.of(), false);
        assertNull(burpOrHost.rejects("a.b", 443, "/", out), "Scope OR host list");

        GateScope paths = new GateScope(false, List.of(), List.of(), List.of("/api/*", "/admin/**", "/orders"), false);
        assertNull(paths.rejects("h", 443, "/api/a/b?x=1", null));
        assertNull(paths.rejects("h", 443, "/admin/x/y", null));
        assertNull(paths.rejects("h", 443, "/orders/5", null));
        assertNull(paths.rejects("h", 443, "/orders", null));
        assertNotNull(paths.rejects("h", 443, "/ordersx", null));
        assertNotNull(paths.rejects("h", 443, "/static/app.js", null));

        GateScope excel = new GateScope(false, List.of(), List.of(), List.of(), true);
        assertNull(excel.rejects("h", 443, "/", out));
        assertNotNull(excel.rejects("h", 443, "/", in));
        assertTrue(hosts.sentence().contains("*.shop.vn") && hosts.sentence().contains("trừ cdn.*"), hosts.sentence());
    }

    @Test
    void gateScopeLetsOtherHostsThrough() {
        StubBridge b = new StubBridge();
        Engine e = new Engine(b, Prefs.memory(), null);
        e.open(new MemoryStorage());
        try {
            e.setProxyGate(new ProxyGate.Settings(true, ProxyGate.Scope.ALL_HOSTS, "", "", "",
                    java.util.Set.of("POST"), ProxyGate.Effect.ASK));
            Config c = e.config().copy();
            c.gateHosts = "*.shop.vn";
            c.gateExclude = "pay.shop.vn";
            e.applyConfig(c);
            byte[] post = "POST /api/o HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
            Engine.ProxyInterceptOutcome held = e.evaluateProxyIntercept(new Service("api.shop.vn", 443, true), post,
                    "https://api.shop.vn/api/o");
            assertNotNull(held);
            assertTrue(held.shouldWait(), "listed host is held");
            Engine.ProxyInterceptOutcome other = e.evaluateProxyIntercept(new Service("google.com", 443, true), post,
                    "https://google.com/api/o");
            assertTrue(other == null || !other.shouldWait() && !other.shouldBlock(), "other host goes through");
            assertTrue(e.lastGateSkip().contains("không có trong danh sách host"), e.lastGateSkip());
            Engine.ProxyInterceptOutcome pay = e.evaluateProxyIntercept(new Service("pay.shop.vn", 443, true), post,
                    "https://pay.shop.vn/api/o");
            assertTrue(pay == null || !pay.shouldWait() && !pay.shouldBlock(), "excluded host goes through");
            assertTrue(e.explainProtection("POST", "google.com", "/api/o", null).contains("bỏ qua"));

            // Burp scope
            c = e.config().copy();
            c.gateHosts = "";
            c.gateExclude = "";
            c.gateBurpScope = true;
            e.applyConfig(c);
            assertTrue(e.evaluateProxyIntercept(new Service("www.shop.vn", 443, true), post, "https://www.shop.vn/api/o").shouldWait());
            Engine.ProxyInterceptOutcome o2 = e.evaluateProxyIntercept(new Service("a.com", 443, true), post, "https://a.com/api/o");
            assertTrue(o2 == null || !o2.shouldWait());
        } finally {
            e.close();
        }
    }

    @Test
    void loggerCheckAndRouteNote() {
        StubBridge b = new StubBridge();
        Engine e = new Engine(b, Prefs.memory(), null);
        e.open(new MemoryStorage());
        try {
            assertEquals("HTTP 200", e.sendLoggerCheck("https://api.shop.vn:8443", "77"));
            assertTrue(b.lastReq.startsWith("GET /?jcrawl-check=77 HTTP/1.1\r\nHost: api.shop.vn:8443\r\n"), b.lastReq);
            assertEquals(0, b.quiet.get());
            Config c = e.config().copy();
            c.quietSend = true;
            e.applyConfig(c);
            e.sendLoggerCheck("shop.vn", "78");
            assertEquals(1, b.quiet.get(), "the check follows Gửi riêng");
        } finally {
            e.close();
        }
    }
}
