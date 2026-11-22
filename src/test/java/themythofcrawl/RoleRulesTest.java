// === BEGIN ANTIGRAVITY CHANGES ===
package themythofcrawl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import themythofcrawl.app.Bridge;
import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Config;
import themythofcrawl.core.HttpBytes;
import themythofcrawl.core.Json;
import themythofcrawl.core.Jwt;
import themythofcrawl.export.Reports;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Blob;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.ResultState;
import themythofcrawl.model.Role;
import themythofcrawl.model.RoleResult;
import themythofcrawl.model.Roles;
import themythofcrawl.model.RuleMode;
import themythofcrawl.model.Service;
import themythofcrawl.rolereq.RoleRules;

/** Ported role-rule and Engine request-building tests from Python tests/test_themythofcrawl.py. */
class RoleRulesTest {
    private static final List<String> SAMPLE_LINES = List.of(
            "GET /a HTTP/1.1",
            "Host: h",
            "Authorization: old",
            "Cookie: a=1",
            "cookie: b=2"
    );

    private static final byte[] BASE_REQ = (
            "POST /v1/transfer?x=1 HTTP/1.1\r\n"
            + "Host: api.example.test\r\n"
            + "Authorization: Bearer original\r\n"
            + "Cookie: sid=orig\r\n"
            + "X-Debug: 1\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 13\r\n"
            + "\r\n"
            + "{\"amount\":10}"
    ).getBytes(StandardCharsets.ISO_8859_1);

    private static final byte[] BASE_RESP = (
            "HTTP/1.1 200 OK\r\n"
            + "Content-Type: application/json\r\n"
            + "\r\n"
            + "{\"ok\":true}"
    ).getBytes(StandardCharsets.ISO_8859_1);

    private Engine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    private static List<String> headerLinesOf(byte[] req) {
        int off = HttpBytes.bodyOffset(req);
        String head = new String(req, 0, off, StandardCharsets.ISO_8859_1);
        head = head.replaceAll("(\r?\n){1,2}$", "");
        return Arrays.asList(head.split("\r?\n"));
    }

    private static byte[] bodyOf(byte[] req) {
        int off = HttpBytes.bodyOffset(req);
        return Arrays.copyOfRange(req, off, req.length);
    }

    // ------------------------------------------------------------------ HeaderRuleTests

    @Test
    void replaceExistingAndDropsDuplicates() {
        List<String> out = RoleRules.applyHeaderRules(
                SAMPLE_LINES,
                List.of(new HeaderRule("Cookie", "z=9", RuleMode.REPLACE))
        );
        assertEquals(List.of("GET /a HTTP/1.1", "Host: h", "Authorization: old", "Cookie: z=9"), out);
    }

    @Test
    void addMissingHeader() {
        List<String> out = RoleRules.applyHeaderRules(
                SAMPLE_LINES,
                List.of(new HeaderRule("X-Role", "admin", null))
        );
        assertEquals("X-Role: admin", out.get(out.size() - 1));
    }

    @Test
    void addModeAppendsDuplicate() {
        List<String> out = RoleRules.applyHeaderRules(
                SAMPLE_LINES,
                List.of(new HeaderRule("Cookie", "c=3", RuleMode.ADD))
        );
        long cookieCount = out.stream().filter(l -> l.toLowerCase().startsWith("cookie")).count();
        assertEquals(3, cookieCount);
    }

    @Test
    void removeTokenStripsAllCaseInsensitive() {
        List<String> out = RoleRules.applyHeaderRules(
                SAMPLE_LINES,
                List.of(new HeaderRule("COOKIE", "[REMOVE]", RuleMode.REPLACE))
        );
        assertFalse(out.stream().anyMatch(l -> l.toLowerCase().startsWith("cookie")));
    }

    @Test
    void requestLineUntouchedAndCrlfSanitized() {
        List<String> out = RoleRules.applyHeaderRules(
                SAMPLE_LINES,
                List.of(new HeaderRule("X-A", "v\r\nInjected: 1", RuleMode.REPLACE))
        );
        assertEquals("GET /a HTTP/1.1", out.get(0));
        assertTrue(out.contains("X-A: vInjected: 1"), () -> "actual headers: " + out);
    }

    @Test
    void invalidHeaderNamesIgnored() {
        List<HeaderRule> badRules = new ArrayList<>();
        badRules.add(null);
        badRules.add(new HeaderRule("Bad Name", "x", RuleMode.REPLACE));
        badRules.add(new HeaderRule("a:b", "x", RuleMode.REPLACE));
        badRules.add(new HeaderRule("Bad\r\nName", "x", RuleMode.REPLACE));
        badRules.add(new HeaderRule("   ", "x", RuleMode.REPLACE));
        badRules.add(new HeaderRule(null, "x", RuleMode.REPLACE));
        List<String> out = RoleRules.applyHeaderRules(SAMPLE_LINES, badRules);
        assertEquals(SAMPLE_LINES, out);
    }

    @Test
    void cookieSetAndCookieRemoveGranular() {
        List<String> lines = List.of(
                "GET /a HTTP/1.1",
                "Host: h",
                "Cookie: theme=dark; JSESSIONID=old_sess; lang=vi"
        );
        List<String> out = RoleRules.applyHeaderRules(lines, List.of(
                new HeaderRule("JSESSIONID", "new_role_sess", RuleMode.COOKIE_SET),
                new HeaderRule("tenant_id", "t42", RuleMode.COOKIE_SET)
        ));
        assertEquals(
                List.of("GET /a HTTP/1.1", "Host: h", "Cookie: theme=dark; JSESSIONID=new_role_sess; lang=vi; tenant_id=t42"),
                out
        );

        List<String> out2 = RoleRules.applyHeaderRules(out, List.of(
                new HeaderRule("JSESSIONID", "[REMOVE]", RuleMode.COOKIE_REMOVE)
        ));
        assertEquals(
                List.of("GET /a HTTP/1.1", "Host: h", "Cookie: theme=dark; lang=vi; tenant_id=t42"),
                out2
        );

        // Stripping the last cookie removes the Cookie header completely (even if mode was COOKIE_SET with [REMOVE])
        List<String> out3 = RoleRules.applyHeaderRules(
                List.of("GET /a HTTP/1.1", "Host: h", "Cookie: sid=123"),
                List.of(new HeaderRule("sid", "[REMOVE]", RuleMode.COOKIE_SET))
        );
        assertEquals(List.of("GET /a HTTP/1.1", "Host: h"), out3);

        // Setting a cookie when no Cookie header existed appends a Cookie header, and respects case-sensitive cookie keys
        List<String> out4 = RoleRules.applyHeaderRules(
                List.of("GET /a HTTP/1.1", "Host: h"),
                List.of(
                        new HeaderRule("SID", "upper", RuleMode.COOKIE_SET),
                        new HeaderRule("sid", "lower", RuleMode.COOKIE_SET)
                )
        );
        assertEquals(List.of("GET /a HTTP/1.1", "Host: h", "Cookie: SID=upper; sid=lower"), out4);

        // Merging multiple Cookie headers preserves other cookies cleanly
        List<String> out5 = RoleRules.applyHeaderRules(
                SAMPLE_LINES,
                List.of(new HeaderRule("b", "22", RuleMode.COOKIE_SET))
        );
        assertEquals(List.of("GET /a HTTP/1.1", "Host: h", "Authorization: old", "Cookie: a=1; b=22"), out5);
    }

    @Test
    void jwtBearerAndDecoder() {
        assertEquals("Bearer abc.def.ghi", RoleRules.formatJwtBearer("abc.def.ghi", "Bearer"));
        assertEquals("Bearer abc.def.ghi", RoleRules.formatJwtBearer("Bearer abc.def.ghi", "Bearer"));
        assertEquals("Bearer abc.def.ghi", RoleRules.formatJwtBearer("Authorization: Bearer abc.def.ghi", "Bearer"));
        assertEquals("Bearer abc.def.ghi", RoleRules.formatJwtBearer("Authorization: abc.def.ghi", "Bearer"));
        assertEquals("rawtoken", RoleRules.formatJwtBearer("rawtoken", ""));
        assertEquals("[REMOVE]", RoleRules.formatJwtBearer("[remove]", "Bearer"));
        assertEquals("", RoleRules.formatJwtBearer("  \r\n ", "Bearer"));

        // Sample JWT: {"alg":"HS256"} . {"sub":"u1","role":"admin"}
        String jwtTok = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSIsInJvbGUiOiJhZG1pbiJ9.sig";
        String decoded = Jwt.preview("Authorization: " + RoleRules.formatJwtBearer(jwtTok, "Bearer"));
        assertTrue(decoded.contains("\"role\": \"admin\""), decoded);
        assertTrue(decoded.contains("\"sub\": \"u1\""), decoded);
    }

    // ------------------------------------------------------------------ RoleRules.build raw bytes & contract

    @Test
    void buildContractEdgeCasesAndBinaryBodies() {
        assertSame(null, RoleRules.INSTANCE.build(null, List.of(new HeaderRule("X-A", "1", RuleMode.REPLACE))));
        byte[] empty = new byte[0];
        assertSame(empty, RoleRules.INSTANCE.build(empty, List.of(new HeaderRule("X-A", "1", RuleMode.REPLACE))));
        assertSame(BASE_REQ, RoleRules.INSTANCE.build(BASE_REQ, null));
        assertSame(BASE_REQ, RoleRules.INSTANCE.build(BASE_REQ, List.of()));

        byte[] origCopy = BASE_REQ.clone();
        byte[] built = RoleRules.INSTANCE.build(BASE_REQ, List.of(
                new HeaderRule("Authorization", "Bearer role-tok", RuleMode.REPLACE),
                new HeaderRule("X-Debug", null, RuleMode.REMOVE)
        ));
        assertArrayEquals(origCopy, BASE_REQ); // original is never mutated
        assertNotEquals(Arrays.toString(BASE_REQ), Arrays.toString(built));
        List<String> lines = headerLinesOf(built);
        assertEquals("POST /v1/transfer?x=1 HTTP/1.1", lines.get(0));
        assertTrue(lines.contains("Authorization: Bearer role-tok"));
        assertFalse(lines.contains("X-Debug: 1"));
        assertArrayEquals("{\"amount\":10}".getBytes(StandardCharsets.ISO_8859_1), bodyOf(built));

        // Binary payload with embedded \r\n\r\n and non-ASCII bytes preserved byte-for-byte
        byte[] binHead = "POST /upload HTTP/1.1\r\nHost: api.example.test\r\nCookie: sid=1\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] binBody = new byte[] {0x00, (byte) 0x80, (byte) 0xFF, '\r', '\n', '\r', '\n', 0x42};
        byte[] binReq = new byte[binHead.length + binBody.length];
        System.arraycopy(binHead, 0, binReq, 0, binHead.length);
        System.arraycopy(binBody, 0, binReq, binHead.length, binBody.length);

        byte[] binBuilt = RoleRules.INSTANCE.build(binReq, List.of(
                new HeaderRule("sid", "2", RuleMode.COOKIE_SET),
                new HeaderRule("X-Extra", "yes", RuleMode.ADD)
        ));
        assertArrayEquals(binBody, bodyOf(binBuilt));
        assertEquals(
                List.of("POST /upload HTTP/1.1", "Host: api.example.test", "Cookie: sid=2", "X-Extra: yes"),
                headerLinesOf(binBuilt)
        );

        // LF-only request and request without trailing blank line still work cleanly
        byte[] lfReq = "GET /ping HTTP/1.1\nHost: api.example.test\n\nhello".getBytes(StandardCharsets.ISO_8859_1);
        byte[] lfBuilt = RoleRules.INSTANCE.build(lfReq, List.of(new HeaderRule("X-Role", "1", RuleMode.REPLACE)));
        assertEquals(List.of("GET /ping HTTP/1.1", "Host: api.example.test", "X-Role: 1"), headerLinesOf(lfBuilt));
        assertArrayEquals("hello".getBytes(StandardCharsets.ISO_8859_1), bodyOf(lfBuilt));

        byte[] noSepReq = "GET /ping HTTP/1.1\r\nHost: api.example.test".getBytes(StandardCharsets.ISO_8859_1);
        byte[] noSepBuilt = RoleRules.INSTANCE.build(noSepReq, List.of(new HeaderRule("X-Role", "1", RuleMode.REPLACE)));
        assertEquals(List.of("GET /ping HTTP/1.1", "Host: api.example.test", "X-Role: 1"), headerLinesOf(noSepBuilt));

        // Degenerate leading-newline request does not throw
        byte[] blankRl = "\r\n\r\nbody".getBytes(StandardCharsets.ISO_8859_1);
        assertSame(blankRl, RoleRules.INSTANCE.build(blankRl, List.of(new HeaderRule("X-Role", "1", RuleMode.REPLACE))));
    }

    @Test
    void buildIsPureAndThreadSafeAcrossConcurrentThreads() throws Exception {
        byte[] snap = BASE_REQ.clone();
        List<HeaderRule> r1 = List.of(new HeaderRule("Authorization", "Bearer t1", RuleMode.REPLACE));
        List<HeaderRule> r2 = List.of(new HeaderRule("sid", "c2", RuleMode.COOKIE_SET));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                final int idx = i;
                tasks.add(() -> {
                    if (idx % 2 == 0) {
                        byte[] out = RoleRules.INSTANCE.build(BASE_REQ, r1);
                        return headerLinesOf(out).contains("Authorization: Bearer t1")
                                && Arrays.equals(bodyOf(out), "{\"amount\":10}".getBytes(StandardCharsets.ISO_8859_1));
                    }
                    byte[] out = RoleRules.INSTANCE.build(BASE_REQ, r2);
                    return headerLinesOf(out).contains("Cookie: sid=c2")
                            && headerLinesOf(out).contains("Authorization: Bearer original");
                });
            }
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                assertTrue(f.get());
            }
            assertArrayEquals(snap, BASE_REQ);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ EngineTests (ported from Python)

    static final class CapturingBridge implements Bridge {
        record RepeaterCall(Service service, byte[] request, String tabName) {
        }

        final List<RepeaterCall> repeater = Collections.synchronizedList(new ArrayList<>());
        final List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean inScope(String url) {
            return true;
        }

        @Override
        public Sent send(Service s, byte[] request) {
            sent.add(request);
            List<String> lines = headerLinesOf(request);
            String auth = lines.stream()
                    .filter(l -> l.toLowerCase().startsWith("authorization:"))
                    .findFirst()
                    .orElse(null);
            if (auth == null) {
                return new Sent("HTTP/1.1 401 Unauthorized\r\n\r\nno".getBytes(StandardCharsets.ISO_8859_1), 2, null);
            }
            if (auth.contains("admin")) {
                return new Sent("HTTP/1.1 200 OK\r\n\r\n{\"ok\":true,\"admin\":1}".getBytes(StandardCharsets.ISO_8859_1), 2, null);
            }
            return new Sent("HTTP/1.1 403 Forbidden\r\n\r\ndenied".getBytes(StandardCharsets.ISO_8859_1), 2, null);
        }

        @Override
        public void toRepeater(Service s, byte[] request, String tabName) {
            repeater.add(new RepeaterCall(s, request, tabName));
        }

        @Override
        public void out(String line) {
        }

        @Override
        public void err(String line) {
        }
    }

    private Engine startEngineWithPythonRoles(CapturingBridge bridge) {
        Prefs prefs = Prefs.memory();
        List<Role> roles = new ArrayList<>();
        roles.add(new Role("Admin", true, List.of(
                HeaderRule.of("Authorization", "Bearer admin-token", "REPLACE")
        )));
        for (int i = 2; i < 10; i++) {
            roles.add(new Role("User" + i, true, List.of(
                    HeaderRule.of("Authorization", "Bearer user" + i, "REPLACE"),
                    HeaderRule.of("Cookie", "sid=u" + i, "REPLACE")
            )));
        }
        roles.add(new Role("Guest", true, List.of(
                HeaderRule.of("Authorization", "[REMOVE]", "REMOVE"),
                HeaderRule.of("Cookie", "[REMOVE]", "REMOVE")
        )));
        roles.add(new Role("Disabled", false, List.of()));
        prefs.set(Engine.PREF_ROLES, Roles.toJson(roles));
        prefs.set(Engine.PREF_CONFIG, Json.write(Map.of(
                "capture_enabled", true,
                "auto_send", true,
                "auto_repeater", true,
                "send_profile", "CUSTOM",
                "send_delay_ms", 0,
                "send_jitter_pct", 0
        )));
        return new Engine(bridge, prefs, null);
    }

    private static void waitSends(Engine e) throws InterruptedException {
        for (int i = 0; i < 300 && e.activeSends() > 0; i++) {
            Thread.sleep(20);
        }
        e.awaitCaptures();
    }

    @Test
    void oneToNCloneRepeaterAndReplayRecordsRoleDiffs() throws Exception {
        CapturingBridge bridge = new CapturingBridge();
        engine = startEngineWithPythonRoles(bridge);
        Service svc = new Service("api.example.test", 443, true);
        ApiGroup g = engine.process(
                new Engine.Message(svc, BASE_REQ, BASE_RESP, "https://api.example.test/v1/transfer?x=1"),
                "Proxy", true, engine.activeBranch().id()
        );
        waitSends(engine);

        assertEquals(10, bridge.repeater.size());
        assertEquals("[G01:POST /v1/transfer] 01_Admin", bridge.repeater.get(0).tabName());
        assertEquals("[G01:POST /v1/transfer] 10_Guest", bridge.repeater.get(9).tabName());

        for (CapturingBridge.RepeaterCall call : bridge.repeater) {
            assertEquals("api.example.test", call.service().host());
            assertEquals(443, call.service().port());
            assertTrue(call.service().secure());
            List<String> lines = headerLinesOf(call.request());
            assertEquals("POST /v1/transfer?x=1 HTTP/1.1", lines.get(0));
            assertArrayEquals("{\"amount\":10}".getBytes(StandardCharsets.ISO_8859_1), bodyOf(call.request()));
            assertTrue(lines.contains("X-Debug: 1"));
            assertTrue(lines.stream().filter(l -> l.toLowerCase().startsWith("authorization")).count() <= 1);
        }

        List<String> adminLines = headerLinesOf(bridge.repeater.get(0).request());
        assertTrue(adminLines.contains("Authorization: Bearer admin-token"));
        assertTrue(adminLines.contains("Cookie: sid=orig"));

        List<String> user2Lines = headerLinesOf(bridge.repeater.get(1).request());
        assertTrue(user2Lines.contains("Authorization: Bearer user2"));
        assertTrue(user2Lines.contains("Cookie: sid=u2"));

        List<String> guestLines = headerLinesOf(bridge.repeater.get(9).request());
        assertFalse(guestLines.stream().anyMatch(l -> {
            String lower = l.toLowerCase();
            return lower.startsWith("authorization:") || lower.startsWith("cookie:");
        }));

        assertEquals(10, bridge.sent.size());
        assertEquals(200, g.origStatus());
        assertEquals(200, g.results().get(0).status());
        for (int i = 1; i < 9; i++) {
            assertEquals(403, g.results().get(i).status());
        }
        assertEquals(401, g.results().get(9).status());
        assertTrue(g.results().stream().allMatch(r -> ResultState.DONE.equals(r.state()) && r.sentCount() == 1));
        assertTrue(g.results().stream().allMatch(r -> engine.responseOf(g, r) != null && r.elapsedMs() != null));
        assertEquals(AuditLevel.INFO, g.auditLevel());
        assertEquals(AuditLevel.OK, g.results().get(0).auditLevel());
        assertEquals(AuditLevel.INFO, g.results().get(1).auditLevel());
        assertEquals(AuditLevel.INFO, g.results().get(9).auditLevel());

        // If Guest (whose auth was stripped by RoleRules) gets 200 OK -> flagged WARN
        RoleResult guest = g.results().get(9);
        guest.setStatus(200);
        guest.setLength(g.origLength());
        Audit.recalc(g);
        assertEquals(AuditLevel.WARN, guest.auditLevel());
        assertTrue(guest.auditBadge().startsWith("Không đăng nhập vẫn gọi được"), guest.auditBadge());
        guest.setStatus(401);
        guest.setLength(2);
        Audit.recalc(g);

        // Manual replay increments sent count for targeted rows only
        engine.replay(g, g.results().subList(0, 2));
        waitSends(engine);
        assertEquals(List.of(2, 2, 1), List.of(
                g.results().get(0).sentCount(),
                g.results().get(1).sentCount(),
                g.results().get(2).sentCount()
        ));

        Map<String, Object> matrix = Json.parseObject(Reports.matrixJson(List.of(g), List.of()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groupsJson = (List<Map<String, Object>>) matrix.get("groups");
        assertEquals("G01", groupsJson.get(0).get("group_id"));
        assertEquals(10L, ((Number) groupsJson.get(0).get("role_count")).longValue());
    }

    @Test
    void originalProfileRulesApplyToOriginal() {
        CapturingBridge bridge = new CapturingBridge();
        engine = startEngineWithPythonRoles(bridge);
        Config c = engine.config().copy();
        c.autoSend = false;
        c.autoRepeater = false;
        engine.applyConfig(c);

        engine.saveOriginalProfile(new Role(
                Role.ORIGINAL_PROFILE_NAME,
                true,
                List.of(
                        HeaderRule.of("X-Crawler", "acc-1", "REPLACE"),
                        HeaderRule.of("Cookie", "[REMOVE]", "REMOVE")
                )
        ));

        Service svc = new Service("api.example.test", 443, true);
        ApiGroup g1 = engine.process(
                new Engine.Message(svc, BASE_REQ, BASE_RESP, "https://api.example.test/v1/transfer?x=1"),
                "Proxy", true, engine.activeBranch().id()
        );

        byte[] modifiedOrig = engine.requestOf(g1, g1.origResult());
        List<String> lines = headerLinesOf(modifiedOrig);
        assertTrue(lines.contains("X-Crawler: acc-1"));
        assertFalse(lines.stream().anyMatch(l -> l.toLowerCase().startsWith("cookie:")));
        assertEquals("POST /v1/transfer?x=1 HTTP/1.1", lines.get(0));
        assertArrayEquals("{\"amount\":10}".getBytes(StandardCharsets.ISO_8859_1), bodyOf(modifiedOrig));
        assertTrue(g1.origResult().roleName().contains("+ 2 luật"), g1.origResult().roleName());

        // The captured original request bytes themselves stay untouched
        assertTrue(headerLinesOf(Blob.bytesOf(g1.origRequest())).contains("Cookie: sid=orig"));

        // Profile switched off: subsequent captures keep the original untouched
        engine.saveOriginalProfile(engine.originalProfile().withEnabled(false));
        byte[] req2 = new String(BASE_REQ, StandardCharsets.ISO_8859_1)
                .replace("/v1/transfer", "/v1/other")
                .getBytes(StandardCharsets.ISO_8859_1);
        ApiGroup g2 = engine.process(
                new Engine.Message(svc, req2, BASE_RESP, "https://api.example.test/v1/other?x=1"),
                "Proxy", true, engine.activeBranch().id()
        );
        assertArrayEquals(Blob.bytesOf(g2.origRequest()), engine.requestOf(g2, g2.origResult()));
        assertEquals(Role.ORIGINAL_LABEL, g2.origResult().roleName());
    }
}
// === END ANTIGRAVITY CHANGES ===
