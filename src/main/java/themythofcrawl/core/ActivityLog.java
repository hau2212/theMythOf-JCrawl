package themythofcrawl.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * V_8.2 "Nhật ký thao tác": every click, menu, tab change, send, capture, setting change and error, in order,
 * so the user can hand ONE file to Claude (or a teammate) and it can replay what happened and why it broke.
 *
 * <p>Kept in memory (last {@value #MAX} events) and appended to {@code activity.jsonl} in the workspace folder
 * when the workspace is closed / switched or the log is exported (rotated at 4 MB). Secrets are masked BEFORE anything is stored: Authorization / Cookie / Set-Cookie values,
 * Bearer tokens, JWTs, api keys, passwords. Never contains request / response bodies.
 */
public final class ActivityLog {
    /** REPORT = the user's own "Báo lỗi" note; STALL = the UI did not respond for a while; SLOW = a click took long. */
    public enum Kind { UI, TAB, SEND, CAPTURE, CONFIG, ENGINE, ERROR, REPORT, STALL, SLOW }

    /** @param ms how long the action took (click handler, send...), 0 when not measured */
    public record Event(long atMs, Kind kind, String text, String detail, long ms) {
        public Event(long atMs, Kind kind, String text, String detail) {
            this(atMs, kind, text, detail, 0);
        }

        public String time() {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(atMs), ZoneId.systemDefault()).format(TIME);
        }
    }

    static final int MAX = 4000;
    private static final long ROTATE_BYTES = 4L * 1024 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final ArrayDeque<Event> RING = new ArrayDeque<>();
    private static final List<Event> UNSAVED = new ArrayList<>();   // written by persistTo() with the workspace
    private static volatile String lastUi = "";
    private static volatile long lastUiAt;

    private static final Pattern[] SECRETS = {
        Pattern.compile("(?i)\\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key|api[-_]?key|x-auth-token|x-csrf-token)"
                + "(\\s*[:=]\\s*)([^\\r\\n,;]{0,4000})"),
        Pattern.compile("(?i)\\b(bearer|basic|token)\\s+[A-Za-z0-9._~+/=-]{8,}"),
        Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*"),
        Pattern.compile("(?i)(\"?(?:password|passwd|pwd|secret|access_token|refresh_token|id_token|session(?:id)?)\"?\\s*[:=]\\s*\"?)"
                + "([^\"&\\s,}]{1,500})"),
        Pattern.compile("(?i)([?&](?:token|access_token|id_token|api_key|apikey|key|sig|signature|session|sid|password|code)=)[^&\\s#]+"),
    };

    private ActivityLog() {
    }

    /** Mask tokens, cookies, passwords and JWTs. Public so exports can reuse it. */
    public static String redact(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String out = SECRETS[0].matcher(s).replaceAll("$1$2[đã che]");
        out = SECRETS[1].matcher(out).replaceAll("$1 [đã che]");
        out = SECRETS[2].matcher(out).replaceAll("[JWT đã che]");
        out = SECRETS[3].matcher(out).replaceAll("$1[đã che]");
        out = SECRETS[4].matcher(out).replaceAll("$1[đã che]");
        return out;
    }

    public static void add(Kind kind, String text, String detail) {
        add(kind, text, detail, 0);
    }

    public static void add(Kind kind, String text, String detail, long ms) {
        Event e = new Event(System.currentTimeMillis(), kind, cut(redact(text), 600), cut(redact(detail), 6000), ms);
        synchronized (RING) {
            RING.addLast(e);
            while (RING.size() > MAX) {
                RING.removeFirst();
            }
        }
        synchronized (UNSAVED) {
            UNSAVED.add(e);
            if (UNSAVED.size() > MAX) {
                UNSAVED.remove(0);
            }
        }
    }

    /** A click / menu choice; the same label twice within 300 ms (one click seen by two hooks) is kept once. */
    public static void ui(String text) {
        long now = System.currentTimeMillis();
        if (text == null || text.isBlank()) {
            return;
        }
        int ctx = text.indexOf(" [");
        String key = (ctx > 0 ? text.substring(0, ctx) : text).replace("Bật/tắt: ", "Bấm: ");
        if (key.equals(lastUi) && now - lastUiAt < 400) {
            return;
        }
        lastUi = key;
        lastUiAt = now;
        add(Kind.UI, text, "");
    }

    public static void error(String text, Throwable t) {
        add(Kind.ERROR, text, t == null ? "" : stack(t));
    }

    public static List<Event> recent(int n) {
        synchronized (RING) {
            List<Event> all = new ArrayList<>(RING);
            return all.subList(Math.max(0, all.size() - Math.max(0, n)), all.size());
        }
    }

    public static List<Event> errors(int n) {
        List<Event> out = new ArrayList<>();
        for (Event e : recent(MAX)) {
            if (e.kind() == Kind.ERROR) {
                out.add(e);
            }
        }
        return out.subList(Math.max(0, out.size() - n), out.size());
    }

    public static Map<Kind, Integer> counts() {
        Map<Kind, Integer> m = new LinkedHashMap<>();
        for (Kind k : Kind.values()) {
            m.put(k, 0);
        }
        for (Event e : recent(MAX)) {
            m.merge(e.kind(), 1, Integer::sum);
        }
        return m;
    }

    /** A click that took {@code ms} on the UI thread; slow ones (>= 300 ms) are also kept as SLOW. */
    public static void timed(String label, long ms) {
        if (ms >= 300) {
            add(Kind.SLOW, label + " (" + ms + " ms)", "", ms);
        }
        synchronized (RING) {
            for (java.util.Iterator<Event> it = RING.descendingIterator(); it.hasNext(); ) {
                Event e = it.next();
                if (e.kind() == Kind.UI && label.startsWith(e.text().split(" \\[")[0])) {
                    // attach the duration to the click event already recorded for this label
                    RING.remove(e);
                    RING.addLast(new Event(e.atMs(), e.kind(), e.text(), e.detail(), Math.max(1, ms)));
                    break;
                }
                if (System.currentTimeMillis() - e.atMs() > 5000) {
                    break;
                }
            }
        }
    }

    /** Feature use: label -> [count, total ms, max ms]. */
    public static Map<String, long[]> usage() {
        Map<String, long[]> m = new LinkedHashMap<>();
        for (Event e : recent(MAX)) {
            if (e.kind() != Kind.UI && e.kind() != Kind.TAB) {
                continue;
            }
            String key = e.text().split(" \\[")[0];
            long[] v = m.computeIfAbsent(key, k -> new long[3]);
            v[0]++;
            v[1] += e.ms();
            v[2] = Math.max(v[2], e.ms());
        }
        return m;
    }

    /**
     * Habits: sequences of 2 or 3 clicks done one after another (each step within 15 s) at least twice, most frequent
     * first. A long sequence repeated often is a candidate for a one-click shortcut.
     */
    public static List<Map.Entry<String, Integer>> habits(int top) {
        List<Event> ui = new ArrayList<>();
        for (Event e : recent(MAX)) {
            if (e.kind() == Kind.UI || e.kind() == Kind.TAB) {
                ui.add(e);
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int len = 2; len <= 3; len++) {
            for (int i = 0; i + len <= ui.size(); i++) {
                boolean close = true;
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < len; k++) {
                    Event e = ui.get(i + k);
                    if (k > 0 && e.atMs() - ui.get(i + k - 1).atMs() > 15000) {
                        close = false;
                        break;
                    }
                    sb.append(k == 0 ? "" : "  ->  ").append(e.text().split(" \\[")[0]);
                }
                if (close) {
                    counts.merge(sb.toString(), 1, Integer::sum);
                }
            }
        }
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() >= 2) {
                out.add(e);
            }
        }
        out.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue()
                : Integer.compare(b.getKey().length(), a.getKey().length()));
        return out.subList(0, Math.min(top, out.size()));
    }

    /** Every event as compact JSON lines (machine-readable, secrets already masked). */
    public static String jsonl(int n) {
        StringBuilder sb = new StringBuilder();
        for (Event e : recent(n)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", e.atMs());
            m.put("k", e.kind().name());
            m.put("x", e.text());
            if (e.ms() > 0) {
                m.put("ms", e.ms());
            }
            if (!e.detail().isEmpty()) {
                m.put("d", e.detail());
            }
            sb.append(Json.write(m)).append('\n');
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (RING) {
            RING.clear();
        }
        synchronized (UNSAVED) {
            UNSAVED.clear();
        }
    }

    /**
     * Append the events not saved yet to {@code <dir>/activity.jsonl} (rotated at 4 MB). Called by the engine
     * when the workspace is closed / switched and when the log is exported, so the file always goes to the
     * workspace that was open while the events happened.
     */
    public static void persistTo(Path dir) {
        List<Event> batch;
        synchronized (UNSAVED) {
            if (UNSAVED.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(UNSAVED);
            UNSAVED.clear();
        }
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        Path f = dir.resolve("activity.jsonl");
        try {
            if (Files.exists(f) && Files.size(f) > ROTATE_BYTES) {
                Files.move(f, f.resolveSibling("activity.1.jsonl"), StandardCopyOption.REPLACE_EXISTING);
            }
            StringBuilder sb = new StringBuilder();
            for (Event e : batch) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("at", Instant.ofEpochMilli(e.atMs()).toString());
                m.put("kind", e.kind().name());
                m.put("text", e.text());
                if (e.ms() > 0) {
                    m.put("ms", e.ms());
                }
                if (!e.detail().isEmpty()) {
                    m.put("detail", e.detail());
                }
                sb.append(Json.write(m)).append('\n');
            }
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // the in-memory log still has it
        }
    }

    static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 6000 ? s.substring(0, 6000) + "\n..." : s;
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
