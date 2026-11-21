package themythofcrawl.collab;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import themythofcrawl.core.Json;

/**
 * Pure Java 21 Real-Time Team Collaboration Service (Host + Client) with zero external JAR dependencies.
 * <ul>
 *   <li><b>Host Mode</b>: embeds {@link HttpServer} directly inside Burp Extension so teammates on LAN
 *       or via Cloudflare Tunnel / ngrok can join with Room URL + Passcode in 2 seconds.</li>
 *   <li><b>Client Mode</b>: polls incremental event sequence every 700ms and pushes local discoveries,
 *       status overrides, custom tags, live branch presence, and pre-flight action locks immediately.</li>
 *   <li><b>Git-Style Conflict Detection</b>: routes conflicting updates from teammates into
 *       {@link InventoryCatalog} so both members can resolve cleanly without silently overwriting data.</li>
 * </ul>
 */
public final class TeamSyncService implements AutoCloseable {

    public enum Mode {
        OFFLINE("Độc lập (Offline)"),
        HOST("Đang mở Phòng (Host)"),
        CLIENT("Đã nối Phòng (Client)");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    public record Presence(String member, String branch, String activeEndpoint, long updatedAtMs) {
        public boolean alive(long nowMs) {
            return (nowMs - updatedAtMs) < 35_000L;
        }
    }

    public record ActionNotice(String member, String method, String host, String pathTemplate, String phase, long atMs) {
        public boolean recent(long nowMs) {
            return (nowMs - atMs) < 60_000L;
        }
    }

    private final InventoryCatalog catalog;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    // Shared in-memory Hub state (active when Mode == HOST)
    private final AtomicLong hostSeq = new AtomicLong(0);
    private final List<Map<String, Object>> hostEvents = new ArrayList<>();
    private final ConcurrentHashMap<String, Presence> presenceMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActionNotice> recentActions = new ConcurrentHashMap<>();

    private volatile Mode mode = Mode.OFFLINE;
    private volatile HttpServer server;
    private volatile int boundPort;
    private volatile String roomUrl = "";
    private volatile String passcode = "";
    private volatile String lastStatus = "Chưa kết nối nhóm";
    private volatile String localBranch = "Toppage";
    private volatile long lastSeenSeq = 0;

    private ScheduledExecutorService poller;

    public TeamSyncService(InventoryCatalog catalog) {
        this.catalog = catalog;
    }

    public void addListener(Runnable r) {
        if (r != null) listeners.addIfAbsent(r);
    }

    public void removeListener(Runnable r) {
        if (r != null) listeners.remove(r);
    }

    private void fireChanged() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // never break network threads
            }
        }
    }

    public Mode mode() { return mode; }
    public int boundPort() { return boundPort; }
    public String roomUrl() { return roomUrl; }
    public String passcode() { return passcode; }
    public String lastStatus() { return lastStatus; }

    public List<Presence> activePeers() {
        long now = System.currentTimeMillis();
        String me = catalog.memberName();
        List<Presence> out = new ArrayList<>();
        for (Presence p : presenceMap.values()) {
            if (p.alive(now) && !p.member().equalsIgnoreCase(me)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Returns teammates currently working on {@code branchPath} (for Live Presence badge on the Tree). */
    public List<String> peersOnBranch(String branchPath) {
        if (branchPath == null || branchPath.isBlank()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        String me = catalog.memberName();
        List<String> out = new ArrayList<>();
        for (Presence p : presenceMap.values()) {
            if (p.alive(now) && !p.member().equalsIgnoreCase(me)
                    && branchPath.equalsIgnoreCase(p.branch())) {
                out.add(p.member());
            }
        }
        return out;
    }

    /**
     * Pre-flight Action Lock check: returns a warning notice if another teammate sent or is holding
     * the same mutating endpoint within the last 60 seconds (prevents duplicate POST/DELETE to Server).
     */
    public ActionNotice checkRecentTeammateAction(String method, String host, String path) {
        String key = InventoryCatalog.normalizeKey(method, host, path);
        ActionNotice n = recentActions.get(key);
        if (n == null) return null;
        if (!n.recent(System.currentTimeMillis())) {
            recentActions.remove(key, n);
            return null;
        }
        if (n.member().equalsIgnoreCase(catalog.memberName())) {
            return null;
        }
        return n;
    }

    // ------------------------------------------------------------------ Host / Client lifecycle

    /** Minimum passcode length for a room (V_7.0: a passcode is mandatory). */
    public static final int MIN_PASSCODE = 8;

    /** Opens a room reachable from THIS machine only (127.0.0.1) — safe default, no LAN exposure. */
    public synchronized void startHost(int port, String roomPasscode) throws IOException {
        startHost(port, roomPasscode, false);
    }

    /**
     * Opens a room. {@code lanExposed=false} binds to 127.0.0.1 only (teammates reach it through an approved
     * tunnel such as SSH port-forwarding); {@code true} listens on every interface, which security monitoring
     * may flag as an unusual listening service — use only in a lab network with SOC approval.
     */
    public synchronized void startHost(int port, String roomPasscode, boolean lanExposed) throws IOException {
        String pass = roomPasscode == null ? "" : roomPasscode.trim();
        if (pass.length() < MIN_PASSCODE) {
            throw new IllegalArgumentException("Bắt buộc đặt passcode phòng từ " + MIN_PASSCODE + " ký tự trở lên");
        }
        stop();
        this.passcode = pass;
        InetSocketAddress bind = lanExposed
                ? new InetSocketAddress(Math.max(0, port))
                : new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), Math.max(0, port));
        HttpServer s = HttpServer.create(bind, 0);
        s.createContext("/api/sync/events", this::handleHostEvents);
        s.createContext("/api/sync/push", this::handleHostPush);
        s.createContext("/api/sync/presence", this::handleHostPresence);
        s.createContext("/api/sync/action-lock", this::handleHostActionLock);
        s.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "JCrawl-team-hub");
            t.setDaemon(true);
            return t;
        }));
        s.start();
        this.server = s;
        this.boundPort = s.getAddress().getPort();
        this.roomUrl = "http://127.0.0.1:" + boundPort;
        this.mode = Mode.HOST;
        this.lastStatus = "Đang mở phòng trên cổng " + boundPort + (lanExposed ? " (mở cho LAN)" : " (chỉ máy này)");
        updateLocalPresence(localBranch, "");
        fireChanged();
    }

    public synchronized void connectClient(String targetRoomUrl, String roomPasscode) {
        stop();
        String url = targetRoomUrl == null ? "" : targetRoomUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.isEmpty()) {
            throw new IllegalArgumentException("Room URL không được để trống");
        }
        this.roomUrl = url;
        this.passcode = roomPasscode == null ? "" : roomPasscode.trim();
        this.lastSeenSeq = 0;
        this.mode = Mode.CLIENT;
        this.lastStatus = "Đang kết nối tới " + url + "...";

        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "JCrawl-team-client");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::pollOnceSafe, 50, 700, TimeUnit.MILLISECONDS);
        fireChanged();
    }

    public synchronized void stop() {
        mode = Mode.OFFLINE;
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
        boundPort = 0;
        presenceMap.clear();
        recentActions.clear();
        lastStatus = "Chế độ độc lập (Offline)";
        fireChanged();
    }

    @Override
    public void close() {
        stop();
    }

    // ------------------------------------------------------------------ Outbound broadcasts

    public void updateLocalPresence(String branch, String activeEndpoint) {
        this.localBranch = branch == null ? "" : branch;
        Presence p = new Presence(catalog.memberName(), this.localBranch,
                activeEndpoint == null ? "" : activeEndpoint, System.currentTimeMillis());
        presenceMap.put(p.member(), p);
        if (mode == Mode.CLIENT) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("member", p.member());
            body.put("branch", p.branch());
            body.put("activeEndpoint", p.activeEndpoint());
            postAsync("/api/sync/presence", body);
        }
    }

    public void broadcastEndpoint(InventoryCatalog.EndpointMeta meta) {
        if (meta == null || mode == Mode.OFFLINE) {
            return;
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "ENDPOINT");
        ev.put("member", catalog.memberName());
        ev.put("method", meta.method);
        ev.put("host", meta.host);
        ev.put("pathTemplate", meta.pathTemplate);
        ev.put("status", meta.status.name());
        ev.put("httpStatus", meta.httpStatus);
        ev.put("branch", meta.branches.isEmpty() ? localBranch : meta.branches.iterator().next());
        ev.put("tags", new ArrayList<>(meta.tags));
        ev.put("note", meta.note);
        ev.put("updatedAtMs", meta.updatedAtMs);

        if (mode == Mode.HOST) {
            appendHostEvent(ev);
        } else if (mode == Mode.CLIENT) {
            postAsync("/api/sync/push", ev);
        }
    }

    public void broadcastCustomTag(String tag) {
        if (tag == null || tag.isBlank() || mode == Mode.OFFLINE) {
            return;
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "TAG");
        ev.put("member", catalog.memberName());
        ev.put("tag", tag.trim());
        ev.put("updatedAtMs", System.currentTimeMillis());
        if (mode == Mode.HOST) {
            appendHostEvent(ev);
        } else if (mode == Mode.CLIENT) {
            postAsync("/api/sync/push", ev);
        }
    }

    public void broadcastActionLock(String method, String host, String path, String phase) {
        if (mode == Mode.OFFLINE) {
            return;
        }
        String tpl = themythofcrawl.core.ActionEngine.normalizePathTemplate(path);
        String key = InventoryCatalog.normalizeKey(method, host, tpl);
        ActionNotice notice = new ActionNotice(catalog.memberName(), method, host, tpl, phase, System.currentTimeMillis());
        recentActions.put(key, notice);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("member", notice.member());
        body.put("method", notice.method());
        body.put("host", notice.host());
        body.put("pathTemplate", notice.pathTemplate());
        body.put("phase", notice.phase());
        body.put("atMs", notice.atMs());
        if (mode == Mode.HOST) {
            appendHostEvent(Map.of(
                    "type", "ACTION_LOCK",
                    "member", notice.member(),
                    "method", notice.method(),
                    "host", notice.host(),
                    "pathTemplate", notice.pathTemplate(),
                    "phase", notice.phase(),
                    "atMs", notice.atMs()));
        } else if (mode == Mode.CLIENT) {
            postAsync("/api/sync/action-lock", body);
        }
    }

    // ------------------------------------------------------------------ Host HTTP handlers

    private boolean authorized(HttpExchange ex) {
        String hdr = ex.getRequestHeaders().getFirst("X-JCrawl-Passcode");
        if (passcode.isEmpty() || hdr == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                passcode.getBytes(StandardCharsets.UTF_8), hdr.getBytes(StandardCharsets.UTF_8));
    }

    /** Random room passcode (URL-safe, 16 chars) suggested by the UI instead of a fixed default. */
    public static String randomPasscode() {
        byte[] b = new byte[12];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private void handleHostEvents(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            sendJson(ex, 401, Map.of("error", "Sai passcode phòng"));
            return;
        }
        long since = 0;
        String q = ex.getRequestURI().getQuery();
        if (q != null) {
            for (String kv : q.split("&")) {
                if (kv.startsWith("since=")) {
                    try {
                        since = Long.parseLong(kv.substring(6));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        List<Map<String, Object>> slice = new ArrayList<>();
        synchronized (hostEvents) {
            for (Map<String, Object> ev : hostEvents) {
                long seq = toLong(ev.get("seq"));
                if (seq > since) {
                    slice.add(ev);
                }
            }
        }
        List<Map<String, Object>> presList = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Presence p : presenceMap.values()) {
            if (p.alive(now)) {
                presList.add(Map.of(
                        "member", p.member(),
                        "branch", p.branch(),
                        "activeEndpoint", p.activeEndpoint(),
                        "updatedAtMs", p.updatedAtMs()));
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("seq", hostSeq.get());
        resp.put("events", slice);
        resp.put("presence", presList);
        resp.put("tags", catalog.allTags());
        sendJson(ex, 200, resp);
    }

    @SuppressWarnings("unchecked")
    private void handleHostPush(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            sendJson(ex, 401, Map.of("error", "Sai passcode phòng"));
            return;
        }
        Object parsed = readJson(ex);
        if (parsed instanceof Map<?, ?> raw) {
            Map<String, Object> ev = new LinkedHashMap<>((Map<String, Object>) raw);
            appendHostEvent(ev);
            applyIncomingEvent(ev);
            sendJson(ex, 200, Map.of("ok", true, "seq", hostSeq.get()));
        } else {
            sendJson(ex, 400, Map.of("error", "Invalid JSON"));
        }
    }

    private void handleHostPresence(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            sendJson(ex, 401, Map.of("error", "Sai passcode phòng"));
            return;
        }
        Object parsed = readJson(ex);
        if (parsed instanceof Map<?, ?> m) {
            String member = str(m.get("member"));
            if (!member.isEmpty()) {
                presenceMap.put(member, new Presence(
                        member, str(m.get("branch")), str(m.get("activeEndpoint")), System.currentTimeMillis()));
                fireChanged();
            }
        }
        sendJson(ex, 200, Map.of("ok", true));
    }

    private void handleHostActionLock(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            sendJson(ex, 401, Map.of("error", "Sai passcode phòng"));
            return;
        }
        Object parsed = readJson(ex);
        if (parsed instanceof Map<?, ?> m) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("type", "ACTION_LOCK");
            ev.put("member", str(m.get("member")));
            ev.put("method", str(m.get("method")));
            ev.put("host", str(m.get("host")));
            ev.put("pathTemplate", str(m.get("pathTemplate")));
            ev.put("phase", str(m.get("phase")));
            ev.put("atMs", System.currentTimeMillis());
            appendHostEvent(ev);
            applyIncomingEvent(ev);
        }
        sendJson(ex, 200, Map.of("ok", true));
    }

    private void appendHostEvent(Map<String, Object> ev) {
        Map<String, Object> copy = new LinkedHashMap<>(ev);
        long seq = hostSeq.incrementAndGet();
        copy.put("seq", seq);
        synchronized (hostEvents) {
            hostEvents.add(copy);
            while (hostEvents.size() > 2000) {
                hostEvents.remove(0);
            }
        }
    }

    // ------------------------------------------------------------------ Client polling

    private void pollOnceSafe() {
        if (mode != Mode.CLIENT) {
            return;
        }
        try {
            pollOnceNow();
        } catch (Exception ex) {
            lastStatus = "Mất kết nối tạm: " + ex.getMessage();
            fireChanged();
        }
    }

    /** Public synchronous poll helper (useful for manual refresh and deterministic tests). */
    public void pollOnceNow() throws IOException, InterruptedException {
        if (mode != Mode.CLIENT) {
            return;
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(roomUrl + "/api/sync/events?since=" + lastSeenSeq))
                .timeout(Duration.ofSeconds(4))
                .GET();
        if (!passcode.isEmpty()) {
            rb.header("X-JCrawl-Passcode", passcode);
        }
        HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            lastStatus = "Lỗi phòng (" + resp.statusCode() + ")";
            fireChanged();
            return;
        }
        Object parsed = Json.parse(resp.body());
        if (!(parsed instanceof Map<?, ?> map)) {
            return;
        }
        long newSeq = toLong(map.get("seq"));
        if (map.get("tags") instanceof List<?> tags) {
            for (Object t : tags) {
                if (t != null && !t.toString().isBlank()) {
                    catalog.addCustomTag(t.toString());
                }
            }
        }
        if (map.get("presence") instanceof List<?> pres) {
            long now = System.currentTimeMillis();
            for (Object item : pres) {
                if (item instanceof Map<?, ?> pm) {
                    String member = str(pm.get("member"));
                    if (!member.isEmpty()) {
                        presenceMap.put(member, new Presence(
                                member, str(pm.get("branch")), str(pm.get("activeEndpoint")), now));
                    }
                }
            }
        }
        if (map.get("events") instanceof List<?> evs) {
            for (Object item : evs) {
                if (item instanceof Map<?, ?> em) {
                    applyIncomingEvent(em);
                }
            }
        }
        lastSeenSeq = Math.max(lastSeenSeq, newSeq);
        lastStatus = "Đang đồng bộ (" + activePeers().size() + " đồng đội online)";
        fireChanged();
    }

    private void applyIncomingEvent(Map<?, ?> ev) {
        String type = str(ev.get("type"));
        String sender = str(ev.get("member"));
        if (sender.equalsIgnoreCase(catalog.memberName())) {
            return;
        }
        if ("TAG".equals(type)) {
            catalog.addCustomTag(str(ev.get("tag")));
            return;
        }
        if ("ACTION_LOCK".equals(type)) {
            String method = str(ev.get("method"));
            String host = str(ev.get("host"));
            String tpl = str(ev.get("pathTemplate"));
            String key = InventoryCatalog.normalizeKey(method, host, tpl);
            recentActions.put(key, new ActionNotice(sender, method, host, tpl, str(ev.get("phase")), System.currentTimeMillis()));
            fireChanged();
            return;
        }
        if ("ENDPOINT".equals(type)) {
            Set<String> tags = new LinkedHashSet<>();
            if (ev.get("tags") instanceof Collection<?> col) {
                for (Object t : col) {
                    if (t != null && !t.toString().isBlank()) {
                        tags.add(t.toString().trim());
                    }
                }
            }
            catalog.applyRemoteUpdate(
                    sender,
                    str(ev.get("method")),
                    str(ev.get("host")),
                    str(ev.get("pathTemplate")),
                    InventoryCatalog.ItemStatus.parse(str(ev.get("status"))),
                    (int) toLong(ev.get("httpStatus")),
                    str(ev.get("branch")),
                    tags,
                    str(ev.get("note")),
                    toLong(ev.get("updatedAtMs")));
            fireChanged();
        }
    }

    private void postAsync(String endpointPath, Map<String, Object> body) {
        String json = Json.write(body);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(roomUrl + endpointPath))
                .timeout(Duration.ofSeconds(4))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (!passcode.isEmpty()) {
            rb.header("X-JCrawl-Passcode", passcode);
        }
        http.sendAsync(rb.build(), HttpResponse.BodyHandlers.discarding());
    }

    private static Object readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.isBlank() ? null : Json.parse(text);
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return 0L;
        try {
            return Long.parseLong(o.toString().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
