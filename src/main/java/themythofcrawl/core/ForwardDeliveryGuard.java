package themythofcrawl.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Guarantees that a user-approved {@code FORWARD} from the live Proxy Intercept Queue
 * reaches the target Server even if the browser closed/timed out its Proxy connection while
 * waiting in {@code [CHỜ DUYỆT]}.
 * <p>
 * How it avoids double-sends:
 * <ol>
 *   <li>Right before returning {@code doNotIntercept(...)} from {@code handleRequestReceived},
 *       the Proxy handler arms a guard entry keyed by {@code messageId}.</li>
 *   <li>If Burp Proxy dispatches the request normally, {@code handleRequestToBeSent} calls
 *       {@link #markProxyDispatched(int)}, atomically disarming the fallback.</li>
 *   <li>If Burp Proxy silently discards the request (e.g. browser XHR timeout / closed TCP/HTTP2
 *       stream) and {@link #markProxyDispatched(int)} is never called within {@code graceMs},
 *       the fallback action fires once to send the request directly via Burp's HTTP client so
 *       it always reaches the Server, shows up in Logger++, and records its real response.</li>
 * </ol>
 */
public final class ForwardDeliveryGuard implements AutoCloseable {

    public static final long DEFAULT_GRACE_MS = 1200L;

    private final ConcurrentHashMap<Integer, AtomicBoolean> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final long graceMs;
    private volatile boolean closed;

    public ForwardDeliveryGuard() {
        this(DEFAULT_GRACE_MS);
    }

    public ForwardDeliveryGuard(long graceMs) {
        this.graceMs = Math.max(50L, graceMs);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "JCrawl-forward-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Arms a single-shot watchdog for {@code messageId}. If {@link #markProxyDispatched(int)}
     * is not called within {@code graceMs}, {@code onProxyDroppedFallback} executes exactly once.
     */
    public void arm(int messageId, Runnable onProxyDroppedFallback) {
        if (closed || onProxyDroppedFallback == null) {
            return;
        }
        AtomicBoolean handled = new AtomicBoolean(false);
        pending.put(messageId, handled);
        try {
            scheduler.schedule(() -> {
                pending.remove(messageId, handled);
                if (!closed && handled.compareAndSet(false, true)) {
                    try {
                        onProxyDroppedFallback.run();
                    } catch (Throwable ignored) {
                        // caller logs its own errors; never kill the watchdog thread
                    }
                }
            }, graceMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            pending.remove(messageId, handled);
        }
    }

    /**
     * Called from {@code ProxyRequestHandler.handleRequestToBeSent} (or {@code HttpHandler})
     * to confirm that Burp Proxy actually dispatched the request over the wire.
     *
     * @return {@code true} if a pending guard was disarmed (meaning Proxy won the race).
     */
    public boolean markProxyDispatched(int messageId) {
        AtomicBoolean handled = pending.remove(messageId);
        return handled != null && handled.compareAndSet(false, true);
    }

    public int pendingCount() {
        return pending.size();
    }

    @Override
    public void close() {
        closed = true;
        pending.clear();
        scheduler.shutdownNow();
    }
}
