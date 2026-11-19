package themythofcrawl.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import themythofcrawl.model.ActionPolicy;

/**
 * Live Action Protocol Gate Auditor (V_3.4_beta).
 * <p>
 * Thread-safe ring buffer recording EVERY Action protocol request (POST/PUT/PATCH/DELETE or
 * action-hinted path) AND every request matching any {@link ActionPolicy} seen at Burp Proxy —
 * BOTH blocked/held/waited AND passed-through (ALLOW or default passthrough).
 * Allows users to verify and calibrate rules in 1 click directly from observed traffic.
 */
public final class ActionGateAuditLog {

    public static final int DEFAULT_CAPACITY = 500;

    public record Entry(
            long seq,
            long timestampMs,
            String method,
            String host,
            String path,
            String branch,
            boolean actionProtocol,
            String verdict,
            ActionPolicy.ProxyEffect effect,
            String policyId,
            String policyName,
            String policyScopeLabel,
            String reason,
            String finalOutcome) {

        public boolean isBlocked() {
            return effect != null && effect.isBlock();
        }

        public Entry withFinalOutcome(String outcome) {
            return new Entry(seq, timestampMs, method, host, path, branch, actionProtocol,
                    verdict, effect, policyId, policyName, policyScopeLabel, reason,
                    outcome == null ? finalOutcome : outcome);
        }
    }

    public record Stats(int total, int actionTotal, int blocked, int passedExplicit, int passedDefault) {
    }

    private final int capacity;
    private final AtomicLong seqGen = new AtomicLong(1);
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public ActionGateAuditLog() {
        this(DEFAULT_CAPACITY);
    }

    public ActionGateAuditLog(int capacity) {
        this.capacity = Math.max(50, capacity);
    }

    public long record(
            String method,
            String host,
            String path,
            String branch,
            boolean actionProtocol,
            ActionPolicy.ProxyEffect effect,
            String policyId,
            String policyName,
            String policyScopeLabel,
            String reason,
            String initialOutcome) {
        long seq = seqGen.getAndIncrement();
        ActionPolicy.ProxyEffect eff = effect == null ? ActionPolicy.ProxyEffect.PASS_NONE : effect;
        String verdict = switch (eff) {
            case INTERCEPT_WAIT -> "CHẶN CHỜ DUYỆT";
            case HOLD_SPOOF -> "GIỮ PROXY (200 ẢO)";
            case BLOCK_DROP -> "CHẶN & DROP";
            case ALLOW_EXPLICIT -> "CHO QUA (ALLOW)";
            case PASS_NONE -> "CHO QUA (NGUYÊN BẢN)";
        };
        String outcome = (initialOutcome == null || initialOutcome.isBlank())
                ? switch (eff) {
                    case INTERCEPT_WAIT -> "Đang đợi duyệt";
                    case HOLD_SPOOF -> "Đã giữ & trả 200 ảo";
                    case BLOCK_DROP -> "Đã ngắt kết nối";
                    case ALLOW_EXPLICIT -> "Đã đi thẳng (ALLOW)";
                    case PASS_NONE -> "Đã đi thẳng (Nguyên bản)";
                }
                : initialOutcome;
        Entry entry = new Entry(
                seq,
                System.currentTimeMillis(),
                Text.nz(method).toUpperCase(Locale.ROOT),
                Text.nz(host),
                Text.nz(path),
                Text.nz(branch),
                actionProtocol,
                verdict,
                eff,
                Text.nz(policyId),
                Text.blank(policyName) ? "(Mặc định — Nguyên bản)" : policyName,
                Text.blank(policyScopeLabel) ? "Mặc định" : policyScopeLabel,
                Text.nz(reason),
                outcome);
        synchronized (entries) {
            entries.addFirst(entry);
            while (entries.size() > capacity) {
                entries.removeLast();
            }
        }
        fireChanged();
        return seq;
    }

    public void updateFinalOutcome(long seq, String finalOutcome) {
        if (seq <= 0 || finalOutcome == null || finalOutcome.isBlank()) {
            return;
        }
        boolean changed = false;
        synchronized (entries) {
            List<Entry> copy = new ArrayList<>(entries);
            for (int i = 0; i < copy.size(); i++) {
                if (copy.get(i).seq() == seq) {
                    copy.set(i, copy.get(i).withFinalOutcome(finalOutcome));
                    changed = true;
                    break;
                }
            }
            if (changed) {
                entries.clear();
                entries.addAll(copy);
            }
        }
        if (changed) {
            fireChanged();
        }
    }

    /** Current count of recorded entries. */
    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /** Newest first snapshot. */
    public List<Entry> all() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    /**
     * Filter audit entries.
     *
     * @param actionOnly    when true, only POST/PUT/PATCH/DELETE or action-hinted requests
     * @param verdictMode   "ALL", "BLOCKED", or "PASSED"
     * @param methodFilter  "ALL" or specific HTTP method
     * @param query         free-text substring against path, host, branch, policyName, reason
     */
    public List<Entry> filter(boolean actionOnly, String verdictMode, String methodFilter, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String vm = verdictMode == null ? "ALL" : verdictMode.trim().toUpperCase(Locale.ROOT);
        String mf = methodFilter == null ? "ALL" : methodFilter.trim().toUpperCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry e : all()) {
            if (actionOnly && !e.actionProtocol()) {
                continue;
            }
            if ("BLOCKED".equals(vm) && !e.isBlocked()) {
                continue;
            }
            if ("PASSED".equals(vm) && e.isBlocked()) {
                continue;
            }
            if (!"ALL".equals(mf) && !mf.isEmpty() && !mf.equalsIgnoreCase(e.method())) {
                continue;
            }
            if (!q.isEmpty()) {
                String hay = (e.method() + " " + e.host() + " " + e.path() + " "
                        + e.branch() + " " + e.policyName() + " " + e.reason() + " " + e.verdict())
                        .toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) {
                    continue;
                }
            }
            out.add(e);
        }
        return out;
    }

    public Stats stats() {
        int total = 0;
        int actionTotal = 0;
        int blocked = 0;
        int passedExplicit = 0;
        int passedDefault = 0;
        synchronized (entries) {
            for (Entry e : entries) {
                total++;
                if (e.actionProtocol()) actionTotal++;
                if (e.isBlocked()) {
                    blocked++;
                } else if (e.effect() == ActionPolicy.ProxyEffect.ALLOW_EXPLICIT) {
                    passedExplicit++;
                } else {
                    passedDefault++;
                }
            }
        }
        return new Stats(total, actionTotal, blocked, passedExplicit, passedDefault);
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
        fireChanged();
    }

    public void addListener(Runnable r) {
        if (r != null) listeners.add(r);
    }

    public void removeListener(Runnable r) {
        if (r != null) listeners.remove(r);
    }

    private void fireChanged() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // listener errors must never disrupt proxy thread
            }
        }
    }
}
