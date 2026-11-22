package themythofcrawl;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import themythofcrawl.app.Bridge;
import themythofcrawl.core.Config;
import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.Role;
import themythofcrawl.model.RoleResult;
import themythofcrawl.model.Service;

/**
 * V_8.2: DELETE sent to N roles. With one shared ID only the first role can succeed; "Mỗi vai trò 1 bản ghi"
 * gives every role its own object (created by the crawl account just before), the crawl account goes last.
 */
class PerRoleObjectTest {
    static final Service SVC = new Service("api.example.test", 443, true);
    static final Pattern DEL = Pattern.compile("^DELETE /api/orders/(\\d+) ");

    static byte[] resp(int st, String body) {
        return ("HTTP/1.1 " + st + " X\r\nContent-Type: application/json\r\n\r\n" + body).getBytes(StandardCharsets.ISO_8859_1);
    }

    /** A tiny orders server: POST creates, DELETE removes once (404 after). Records every DELETE. */
    static final class Server implements Bridge {
        final Set<String> alive = ConcurrentHashMap.newKeySet();
        final AtomicInteger next = new AtomicInteger(100);
        final List<String> deletes = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public Sent send(Service s, byte[] req) {
            String head = new String(req, StandardCharsets.ISO_8859_1);
            if (head.startsWith("POST /api/orders ")) {
                String id = String.valueOf(next.incrementAndGet());
                alive.add(id);
                return new Sent(resp(201, "{\"id\":" + id + "}"), 3, null);
            }
            Matcher m = DEL.matcher(head);
            if (m.find()) {
                String id = m.group(1);
                String who = head.contains("X-Role: ") ? head.substring(head.indexOf("X-Role: ") + 8).split("\r\n")[0] : "crawl";
                deletes.add(who + "=" + id);
                return alive.remove(id) ? new Sent(resp(200, "{\"deleted\":" + id + "}"), 3, null)
                        : new Sent(resp(404, "{\"error\":\"not found\"}"), 3, null);
            }
            return new Sent(resp(200, "{}"), 3, null);
        }

        @Override public boolean inScope(String url) { return true; }
        @Override public void toRepeater(Service service, byte[] request, String tabName) { }
        @Override public void out(String line) { }
        @Override public void err(String line) { }
    }

    static Engine.Message msg(String method, String path, String body, byte[] response) {
        byte[] req = (method + " " + path + " HTTP/1.1\r\nHost: api.example.test\r\n\r\n" + body).getBytes(StandardCharsets.ISO_8859_1);
        return new Engine.Message(SVC, req, response, "https://api.example.test" + path);
    }

    static void waitIdle(Engine e) throws InterruptedException {
        Thread.sleep(150);
        for (int i = 0; i < 300 && (e.queue().queued() + e.queue().inFlight() > 0 || e.activeSends() > 0); i++) {
            Thread.sleep(50);
        }
    }

    @Test
    void deleteToTenRolesEachGetsOwnObject() throws Exception {
        Server server = new Server();
        server.alive.add("55");
        Engine e = new Engine(server, Prefs.memory(), null);
        e.open(new MemoryStorage());
        try {
            Config c = e.config().copy();
            c.captureEnabled = true;
            c.autoSend = false;
            c.confirmNewApis = false;
            c.sendProfile = Config.SendProfile.CUSTOM;
            c.sendDelayMs = 0;
            c.sendJitterPct = 0;
            c.hostConcurrency = 4;
            e.applyConfig(c);
            List<Role> roles = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                roles.add(new Role("R" + i, true, List.of(HeaderRule.of("X-Role", "r" + i, "REPLACE"))));
            }
            e.roles().setAll(roles);
            e.setDestination("Orders");
            e.process(msg("POST", "/api/orders", "{\"sku\":\"A\"}", resp(201, "{\"id\":55}")), "Proxy", true, e.activeBranch().id());
            ApiGroup del = e.process(msg("DELETE", "/api/orders/55", "", resp(200, "{}")), "Proxy", true, e.activeBranch().id());
            assertNotNull(del);
            assertEquals(10, del.results().size());

            Engine.PerRolePlan plan = e.perRolePlan(del, 11);
            assertTrue(plan.hasId());
            assertNotNull(plan.producer(), "POST /api/orders is the creator of the same family");
            assertTrue(plan.enough());

            e.sendEachRoleOwnObject(del, e.defaultTargets(del, true));
            waitIdle(e);

            Set<String> ids = new HashSet<>();
            for (RoleResult r : del.entries()) {
                assertEquals(Integer.valueOf(200), r.status(), r.roleName() + " must hit its own live object: " + server.deletes);
                assertTrue(r.sentNote().startsWith("ID "), r.sentNote());
                ids.add(r.sentNote().split(" ")[1]);
                assertNotNull(r.sentRequest(), "the request really sent is kept");
                assertTrue(new String(e.shownRequestOf(del, r), StandardCharsets.ISO_8859_1).contains("/api/orders/" + r.sentNote().split(" ")[1]));
            }
            assertEquals(11, ids.size(), "11 different objects: " + server.deletes);
            assertFalse(ids.contains("55"), "the captured object is never touched");
            assertTrue(server.alive.contains("55"));
            // crawl account (control) is prepared last: its object is the newest one
            assertTrue(server.deletes.contains("crawl=111"), server.deletes.toString());
        } finally {
            e.close();
        }
    }

    @Test
    void modeTaoTruocRoutesPlainSendThroughPerRole() throws Exception {
        Server server = new Server();
        server.alive.add("55");
        Engine e = new Engine(server, Prefs.memory(), null);
        e.open(new MemoryStorage());
        try {
            Config c = e.config().copy();
            c.captureEnabled = true;
            c.autoSend = false;
            c.confirmNewApis = false;
            c.sendProfile = Config.SendProfile.CUSTOM;
            c.sendDelayMs = 0;
            c.sendJitterPct = 0;
            c.hostConcurrency = 4;
            c.actionMode = ActionEngine.ActionMode.PRODUCER_RECREATE;
            e.applyConfig(c);
            e.roles().setAll(List.of(new Role("A", true, List.of(HeaderRule.of("X-Role", "a", "REPLACE"))),
                    new Role("B", true, List.of(HeaderRule.of("X-Role", "b", "REPLACE")))));
            e.setDestination("Orders");
            e.process(msg("POST", "/api/orders", "{}", resp(201, "{\"id\":55}")), "Proxy", true, e.activeBranch().id());
            ApiGroup del = e.process(msg("DELETE", "/api/orders/55", "", resp(200, "{}")), "Proxy", true, e.activeBranch().id());
            e.replay(del, e.defaultTargets(del, false));
            waitIdle(e);
            for (RoleResult r : del.results()) {
                assertEquals(Integer.valueOf(200), r.status(), server.deletes.toString());
            }
            assertNotEquals(del.results().get(0).sentNote(), del.results().get(1).sentNote());
        } finally {
            e.close();
        }
    }
}
