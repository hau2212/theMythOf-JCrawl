package themythofcrawl.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import themythofcrawl.model.Clock;
import themythofcrawl.model.Service;

/**
 * Thread-safe live Proxy interception queue:
 * holds matching browser requests on the Burp Proxy worker thread until the user explicitly decides
 * in the Extension UI to:
 * <ul>
 *   <li>{@link ResolutionKind#FORWARD} — send directly to the Server (optionally with edited raw bytes)</li>
 *   <li>{@link ResolutionKind#HOLD_AND_SPOOF} — keep inside Extension API table (no Server send) + spoof 200 OK / drop</li>
 *   <li>{@link ResolutionKind#DROP_AND_SAVE} — drop at Proxy (no Server send) + save draft into Extension API table</li>
 *   <li>{@link ResolutionKind#DROP} — drop completely without sending to Server</li>
 * </ul>
 */
public final class ProxyInterceptQueue {

    public static final long DEFAULT_TIMEOUT_MS = 10L * 60L * 1000L; // 10 minutes safety cap

    public enum ResolutionKind {
        FORWARD("Gửi lên Server"),
        /** V_10.4: JCrawl sends it itself outside Burp (Logger++ does not log the server call); the real response goes back to the browser. */
        FORWARD_QUIET("Gửi riêng (không qua Logger++)"),
        HOLD_AND_SPOOF("Lưu vào bảng & Giữ (200 ảo)"),
        DROP_AND_SAVE("Hủy & Lưu vào bảng"),
        DROP("Hủy bỏ (Drop)");

        public final String label;

        ResolutionKind(String label) {
            this.label = label;
        }
    }

    public record Resolution(ResolutionKind kind, byte[] editedRequest, String spoofResponse) {
        public static Resolution of(ResolutionKind kind, byte[] editedRequest, String spoofResponse) {
            return new Resolution(kind == null ? ResolutionKind.DROP : kind,
                    editedRequest == null ? null : Arrays.copyOf(editedRequest, editedRequest.length),
                    spoofResponse);
        }
    }

    public static final class PendingRequest {
        private final long id;
        private final Service service;
        private final String method;
        private final String path;
        private final String url;
        private final String branchName;
        private final String reason;
        private final String spoofResponse;
        private final byte[] originalRequest;
        private final long createdAtMs;
        private final String timeHms;
        private final CompletableFuture<Resolution> future = new CompletableFuture<>();

        PendingRequest(
                long id,
                Service service,
                String method,
                String path,
                String url,
                String branchName,
                String reason,
                String spoofResponse,
                byte[] originalRequest) {
            this.id = id;
            this.service = service;
            this.method = method == null ? "GET" : method;
            this.path = path == null ? "/" : path;
            this.url = url != null ? url : (service != null ? service.protocol() + "://" + service.hostLabel() + this.path : this.path);
            this.branchName = branchName == null ? "" : branchName;
            this.reason = reason == null ? "Chặn chờ duyệt" : reason;
            this.spoofResponse = spoofResponse;
            this.originalRequest = originalRequest == null ? new byte[0] : Arrays.copyOf(originalRequest, originalRequest.length);
            this.createdAtMs = System.currentTimeMillis();
            this.timeHms = Clock.hms();
        }

        public long id() { return id; }
        public Service service() { return service; }
        public String method() { return method; }
        public String path() { return path; }
        public String url() { return url; }
        public String branchName() { return branchName; }
        public String reason() { return reason; }
        public String spoofResponse() { return spoofResponse; }
        public byte[] originalRequest() { return Arrays.copyOf(originalRequest, originalRequest.length); }
        public long createdAtMs() { return createdAtMs; }
        public String timeHms() { return timeHms; }
        public boolean isDone() { return future.isDone(); }

        CompletableFuture<Resolution> future() {
            return future;
        }
    }

    private final AtomicLong seq = new AtomicLong(1);
    private final CopyOnWriteArrayList<PendingRequest> items = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** Tree-First default: held requests appear live inside their target Branch on the Tree without hijacking focus. */
    private volatile boolean autoOpenWindow = false;

    public boolean isAutoOpenWindow() {
        return autoOpenWindow;
    }

    public void setAutoOpenWindow(boolean autoOpen) {
        this.autoOpenWindow = autoOpen;
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Runnable listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // listener errors must never break Proxy worker threads
            }
        }
    }

    /** Enqueues a request without blocking — useful for tests and async flows. */
    public PendingRequest enqueue(
            Service service,
            String method,
            String path,
            String url,
            String branchName,
            String reason,
            String spoofResponse,
            byte[] originalRequest) {
        PendingRequest item = new PendingRequest(
                seq.getAndIncrement(), service, method, path, url, branchName, reason, spoofResponse, originalRequest);
        items.add(item);
        notifyListeners();
        return item;
    }

    /** Enqueues a request and blocks the calling Proxy worker thread until resolved or timed out. */
    public Resolution enqueueAndAwait(
            Service service,
            String method,
            String path,
            String url,
            String branchName,
            String reason,
            String spoofResponse,
            byte[] originalRequest,
            long timeoutMs) {
        PendingRequest item = enqueue(service, method, path, url, branchName, reason, spoofResponse, originalRequest);
        long wait = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        try {
            Resolution res = item.future().get(wait, TimeUnit.MILLISECONDS);
            return res != null ? res : Resolution.of(ResolutionKind.DROP, null, spoofResponse);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            items.remove(item);
            notifyListeners();
            return Resolution.of(ResolutionKind.DROP, null, spoofResponse);
        } catch (Exception timeoutOrCancel) {
            items.remove(item);
            notifyListeners();
            // Fail-safe on timeout: DROP so destructive action never silently reaches Server
            return Resolution.of(ResolutionKind.DROP, null, spoofResponse);
        }
    }

    /** Resolves a specific pending request by ID and wakes its Proxy worker thread immediately. */
    public boolean resolve(long id, ResolutionKind kind, byte[] editedRequest) {
        for (PendingRequest item : items) {
            if (item.id() == id) {
                items.remove(item);
                byte[] payload = (editedRequest != null && editedRequest.length > 0)
                        ? editedRequest
                        : item.originalRequest();
                boolean ok = item.future().complete(Resolution.of(kind, payload, item.spoofResponse()));
                notifyListeners();
                return ok;
            }
        }
        return false;
    }

    /** Resolves and releases all currently held requests (e.g., Forward All, Drop All, or Extension unload). */
    public int releaseAll(ResolutionKind kind) {
        List<PendingRequest> snap = new ArrayList<>(items);
        if (snap.isEmpty()) {
            return 0;
        }
        items.removeAll(snap);
        int count = 0;
        for (PendingRequest item : snap) {
            if (item.future().complete(Resolution.of(kind, item.originalRequest(), item.spoofResponse()))) {
                count++;
            }
        }
        notifyListeners();
        return count;
    }

    public List<PendingRequest> pending() {
        return new ArrayList<>(items);
    }

    public int pendingCount() {
        return items.size();
    }

    public PendingRequest find(long id) {
        for (PendingRequest item : items) {
            if (item.id() == id) {
                return item;
            }
        }
        return null;
    }

    /** Returns all currently held requests targeting the exact normalized branch path. */
    public List<PendingRequest> pendingForBranch(String branchPath) {
        String norm = Hierarchy.normalize(branchPath);
        List<PendingRequest> out = new ArrayList<>();
        for (PendingRequest item : items) {
            if (Hierarchy.normalize(item.branchName()).equalsIgnoreCase(norm)) {
                out.add(item);
            }
        }
        return out;
    }

    /** Returns all distinct hosts among currently pending held requests. */
    public List<String> pendingHosts() {
        java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>();
        for (PendingRequest item : items) {
            String h = item.service() != null ? item.service().hostLabel() : "";
            if (!h.isBlank()) {
                hosts.add(h);
            }
        }
        return new ArrayList<>(hosts);
    }

    /** Returns all distinct normalized branch paths among currently pending held requests. */
    public List<String> pendingBranches() {
        java.util.LinkedHashSet<String> branches = new java.util.LinkedHashSet<>();
        for (PendingRequest item : items) {
            String b = Hierarchy.normalize(item.branchName());
            if (!b.isBlank()) {
                branches.add(b);
            }
        }
        return new ArrayList<>(branches);
    }
}
