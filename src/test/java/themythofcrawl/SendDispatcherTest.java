package themythofcrawl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import themythofcrawl.send.SendDispatcher;
import themythofcrawl.send.SendDispatcher.Job;

/** Per-host pacing, 429 / Retry-After handling, retries and cancellation on a fake clock. */
class SendDispatcherTest {
    long now;
    SendDispatcher.Pacing pacing;
    final List<Runnable> executed = new ArrayList<>();
    final List<long[]> scheduledDelays = new ArrayList<>();
    final List<Runnable> scheduled = new ArrayList<>();
    final List<Job<String>> ran = new ArrayList<>();
    SendDispatcher<String> d;

    @BeforeEach
    void setUp() {
        now = 100_000;
        pacing = new SendDispatcher.Pacing(1000, 0, 1, 2);
        d = new SendDispatcher<>(ran::add, executed::add, (task, delay) -> {
            scheduled.add(task);
            scheduledDelays.add(new long[] {delay});
        }, () -> pacing, null, () -> now, () -> 0.5);
    }

    List<Job<String>> jobs(int n, String host) {
        List<Job<String>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Job<>(host + "#" + i, host));
        }
        return out;
    }

    List<Job<String>> runDispatched() {
        List<Runnable> tasks = new ArrayList<>(executed);
        executed.clear();
        tasks.forEach(Runnable::run);
        List<Job<String>> out = new ArrayList<>(ran);
        ran.clear();
        return out;
    }

    void fireScheduled() {
        List<Runnable> tasks = new ArrayList<>(scheduled);
        scheduled.clear();
        scheduledDelays.clear();
        tasks.forEach(Runnable::run);
    }

    @Test
    void oneAtATimeWithAHumanGapMeasuredFromTheAnswer() {
        List<Job<String>> j = jobs(3, "a");
        d.submit(j);
        assertEquals(List.of(j.get(0)), runDispatched());
        now += 300;
        assertTrue(d.done(j.get(0), 200, null));
        assertEquals(List.of(), runDispatched());
        assertEquals(1, scheduled.size());
        assertEquals(1001, scheduledDelays.get(0)[0], 2);
        now += 1010;
        fireScheduled();
        assertEquals(List.of(j.get(1)), runDispatched());
    }

    @Test
    void hostsAreIndependent() {
        List<Job<String>> a = jobs(1, "a");
        List<Job<String>> b = jobs(1, "b");
        List<Job<String>> all = new ArrayList<>(a);
        all.addAll(b);
        d.submit(all);
        assertEquals(all, runDispatched());
    }

    @Test
    void a429PausesTheHostHonoursRetryAfterAndRetriesFirst() {
        List<Job<String>> j = jobs(2, "a");
        d.submit(j);
        runDispatched();
        assertTrue(d.willRetry(j.get(0), 429));
        assertFalse(d.done(j.get(0), 429, 5.0));
        SendDispatcher.Snapshot s = d.snapshot();
        assertEquals(2, s.queued());
        assertEquals(2.0, s.penalty());
        assertEquals(5.0, s.waitSeconds(), 0.01);
        now += 4000;
        fireScheduled();
        assertEquals(List.of(), runDispatched());
        now += 1010;
        fireScheduled();
        assertEquals(List.of(j.get(0)), runDispatched());
        assertEquals(1, j.get(0).retries());
    }

    @Test
    void backOffWithoutRetryAfterAndRetryLimit() {
        Job<String> job = jobs(1, "a").get(0);
        d.submit(List.of(job));
        boolean finished = false;
        for (int i = 0; i < 3; i++) {
            runDispatched();
            finished = d.done(job, 503, null);
            now += 100_000;
            fireScheduled();
        }
        assertTrue(finished);
        assertEquals(2, job.retries());
        assertEquals(8.0, d.snapshot().penalty());
    }

    @Test
    void speedRecoversAfterSuccesses() {
        List<Job<String>> j = jobs(1 + SendDispatcher.RECOVER_AFTER, "a");
        d.submit(j);
        runDispatched();
        d.done(j.get(0), 429, 0.0);
        for (int i = 0; i <= SendDispatcher.RECOVER_AFTER; i++) {
            now += 60_000;
            fireScheduled();
            for (Job<String> job : runDispatched()) {
                d.done(job, 200, null);
            }
        }
        assertEquals(1.0, d.snapshot().penalty());
    }

    @Test
    void cancelAllDropsOnlyQueuedJobs() {
        List<Job<String>> j = jobs(4, "a");
        d.submit(j);
        runDispatched();
        assertEquals(j.subList(1, 4), d.cancelAll());
        assertEquals(0, d.snapshot().queued());
        assertEquals(1, d.snapshot().inFlight());
    }

    @Test
    void retryAfterParsing() {
        assertEquals(7.0, SendDispatcher.parseRetryAfter("7", 0));
        assertEquals(300.0, SendDispatcher.parseRetryAfter("99999", 0));
        assertEquals(null, SendDispatcher.parseRetryAfter("", 0));
        long nowMs = 1_700_000_000_000L;
        String in10s = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs + 10_000), java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertEquals(10.0, SendDispatcher.parseRetryAfter(in10s, nowMs), 1.0);
    }
}
