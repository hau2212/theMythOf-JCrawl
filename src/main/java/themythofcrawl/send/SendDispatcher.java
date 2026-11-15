package themythofcrawl.send;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Single gate for every outgoing request. Per target host: FIFO order, at most {@code concurrency}
 * in flight, a randomised gap between requests measured from the previous answer (the rhythm of a
 * person clicking around). A 429 / 503 pauses the host (Retry-After honoured, else exponential
 * back-off), halves its speed, forces one-at-a-time and retries the request first. Successful
 * streaks speed it back up. Workers never sleep: pacing is done by scheduling a wake-up.
 */
public final class SendDispatcher<J> {
    public static final double MAX_PENALTY = 16.0;
    public static final int RECOVER_AFTER = 8;

    /** Pacing settings, read on every decision so changes apply immediately. */
    public record Pacing(long delayMs, int jitterPct, int concurrency, int maxRetries) {
    }

    /** Schedules {@code task} after {@code delayMs}. */
    public interface Scheduler {
        void schedule(Runnable task, long delayMs);
    }

    /** A queued request: what to send and to which host. */
    public static final class Job<J> {
        public final J payload;
        public final String hostKey;
        int retries;

        public Job(J payload, String hostKey) {
            this.payload = payload;
            this.hostKey = hostKey;
        }

        public int retries() {
            return retries;
        }
    }

    /** Queue state for the status bar. */
    public record Snapshot(int queued, int inFlight, double penalty, double waitSeconds) {
    }

    private static final class Host<J> {
        final ArrayDeque<Job<J>> queue = new ArrayDeque<>();
        int inFlight;
        double nextOk;
        double pausedUntil;
        double penalty = 1.0;
        int okStreak;
    }

    private final Consumer<Job<J>> runner;
    private final Executor executor;
    private final Scheduler scheduler;
    private final Supplier<Pacing> pacing;
    private final Consumer<String> log;
    private final LongSupplier clockMs;
    private final DoubleSupplier random;
    private final Map<String, Host<J>> hosts = new LinkedHashMap<>();
    private Double wakeAt;

    public SendDispatcher(Consumer<Job<J>> runner, Executor executor, Scheduler scheduler, Supplier<Pacing> pacing,
                          Consumer<String> log, LongSupplier clockMs, DoubleSupplier random) {
        this.runner = runner;
        this.executor = executor;
        this.scheduler = scheduler;
        this.pacing = pacing;
        this.log = log;
        this.clockMs = clockMs;
        this.random = random;
    }

    private double now() {
        return clockMs.getAsLong() / 1000.0;
    }

    private Host<J> host(String key) {
        return hosts.computeIfAbsent(key, k -> new Host<>());
    }

    private double gap(Host<J> h, Pacing p) {
        double g = p.delayMs() / 1000.0 * h.penalty;
        if (p.jitterPct() > 0) {
            g *= 1.0 + p.jitterPct() / 100.0 * (2.0 * random.getAsDouble() - 1.0);
        }
        return Math.max(0.0, g);
    }

    public void submit(List<Job<J>> jobs) {
        synchronized (this) {
            for (Job<J> j : jobs) {
                host(j.hostKey).queue.addLast(j);
            }
        }
        pump();
    }

    /** Drops every queued (not yet sent) job and returns them. */
    public synchronized List<Job<J>> cancelAll() {
        List<Job<J>> out = new ArrayList<>();
        for (Host<J> h : hosts.values()) {
            out.addAll(h.queue);
            h.queue.clear();
        }
        return out;
    }

    public synchronized Snapshot snapshot() {
        double t = now();
        int queued = 0;
        int inFlight = 0;
        double penalty = 1.0;
        double wait = 0.0;
        for (Host<J> h : hosts.values()) {
            queued += h.queue.size();
            inFlight += h.inFlight;
            penalty = Math.max(penalty, h.penalty);
            wait = Math.max(wait, h.pausedUntil - t);
        }
        return new Snapshot(queued, inFlight, penalty, Math.max(0.0, wait));
    }

    private void wake() {
        synchronized (this) {
            wakeAt = null;
        }
        pump();
    }

    public void pump() {
        List<Job<J>> run = new ArrayList<>();
        Long wakeDelay = null;
        synchronized (this) {
            double t = now();
            Pacing p = pacing.get();
            Double earliest = null;
            for (Host<J> h : hosts.values()) {
                int limit = h.penalty > 1.0 ? 1 : Math.max(1, p.concurrency());
                while (!h.queue.isEmpty() && h.inFlight < limit) {
                    double ready = Math.max(h.nextOk, h.pausedUntil);
                    if (ready > t) {
                        earliest = earliest == null ? ready : Math.min(earliest, ready);
                        break;
                    }
                    Job<J> j = h.queue.pollFirst();
                    h.inFlight++;
                    h.nextOk = t + gap(h, p);
                    run.add(j);
                }
            }
            if (earliest != null && (wakeAt == null || earliest < wakeAt - 0.005)) {
                wakeAt = earliest;
                wakeDelay = (long) ((earliest - t) * 1000) + 1;
            }
        }
        if (wakeDelay != null) {
            scheduler.schedule(this::wake, wakeDelay);
        }
        for (Job<J> j : run) {
            executor.execute(() -> runner.accept(j));
        }
    }

    public static boolean retryable(int status) {
        return status == 429 || status == 503;
    }

    public boolean willRetry(Job<J> job, int status) {
        return retryable(status) && job.retries < pacing.get().maxRetries();
    }

    /** Reports a finished request; returns false when it was queued again for a retry. */
    public boolean done(Job<J> job, int status, Double retryAfterSeconds) {
        boolean requeued = false;
        String notice = null;
        synchronized (this) {
            double t = now();
            Host<J> h = host(job.hostKey);
            h.inFlight = Math.max(0, h.inFlight - 1);
            Pacing p = pacing.get();
            if (retryable(status)) {
                h.okStreak = 0;
                h.penalty = Math.min(MAX_PENALTY, h.penalty * 2.0);
                double wait = retryAfterSeconds != null ? retryAfterSeconds
                        : Math.min(60.0, Math.max(2.0, p.delayMs() / 1000.0 * h.penalty * 2.0));
                if (t + wait > h.pausedUntil + 0.5) {
                    notice = String.format(Locale.ROOT, "Host %s trả HTTP %d -> tạm dừng %.1fs, giảm tốc x%d, "
                            + "gửi từng request một.", job.hostKey, status, wait, (int) h.penalty);
                }
                h.pausedUntil = Math.max(h.pausedUntil, t + wait);
                if (job.retries < p.maxRetries()) {
                    job.retries++;
                    h.queue.addFirst(job);
                    requeued = true;
                }
            } else {
                h.okStreak++;
                if (h.penalty > 1.0 && h.okStreak >= RECOVER_AFTER) {
                    h.penalty = Math.max(1.0, h.penalty / 2.0);
                    h.okStreak = 0;
                }
                h.nextOk = Math.max(h.nextOk, t + gap(h, p));
            }
        }
        if (notice != null && log != null) {
            log.accept(notice);
        }
        pump();
        return !requeued;
    }

    /** Seconds from a Retry-After header value (delta-seconds or HTTP-date), capped at 300; null if absent. */
    public static Double parseRetryAfter(String value, long nowMillis) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.chars().allMatch(Character::isDigit)) {
            try {
                return (double) Math.min(Long.parseLong(v), 300L);
            } catch (NumberFormatException e) {
                return 300.0;
            }
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            double secs = (when.toInstant().toEpochMilli() - nowMillis) / 1000.0;
            return Math.max(0.0, Math.min(secs, 300.0));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
