package themythofcrawl;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.core.ActivityLog;

/** V_8.2: the activity log masks secrets before storing and keeps clicks / errors in order. */
class ActivityLogTest {
    @Test
    void secretsAreMasked() {
        String raw = "Authorization: Bearer abcdefghijklmnop123 Cookie: SESSION=xyz; other=1 "
                + "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.sig123 {\"password\":\"hunter2\"} /api?token=SECRET123&x=1";
        String red = ActivityLog.redact(raw);
        assertFalse(red.contains("abcdefghijklmnop123"), red);
        assertFalse(red.contains("SESSION=xyz"), red);
        assertFalse(red.contains("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0"), red);
        assertFalse(red.contains("hunter2"), red);
        assertFalse(red.contains("SECRET123"), red);
        assertTrue(red.contains("x=1"), red);
    }

    @Test
    void eventsAreKeptAndWrittenToWorkspace(@TempDir Path dir) throws Exception {
        ActivityLog.clear();
        ActivityLog.ui("Bấm: Gửi lại [tab Cây]");
        ActivityLog.ui("Bấm: Gửi lại");   // same click seen by a second hook
        ActivityLog.add(ActivityLog.Kind.SEND, "API #3 DELETE /api/orders/91 · R1 -> 200", "");
        ActivityLog.error("Lỗi khi bấm \"X\"", new IllegalStateException("boom"));
        // (other tests' background threads may add events too: check ours, not exact totals)
        List<ActivityLog.Event> ev = ActivityLog.recent(4000);
        assertEquals(1, ev.stream().filter(x -> x.text().startsWith("Bấm: Gửi lại")).count(), "one click, two hooks -> one event");
        assertTrue(ev.stream().anyMatch(x -> x.kind() == ActivityLog.Kind.ERROR && x.detail().contains("IllegalStateException")));
        ActivityLog.persistTo(dir);
        List<String> lines = Files.readAllLines(dir.resolve("activity.jsonl"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("DELETE /api/orders/91")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"kind\":\"ERROR\"")), lines.toString());
    }
}
