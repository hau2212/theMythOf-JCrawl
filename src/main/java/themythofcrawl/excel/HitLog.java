package themythofcrawl.excel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import themythofcrawl.core.Json;

/**
 * Every time a request matching a row of the Excel list goes through the proxy: when, the real path,
 * which branch was the destination, and whether it was captured, met again (duplicate) or filtered.
 * Counted per row; the last MAX_EVENTS events of each row are kept.
 */
public final class HitLog {
    public static final int MAX_EVENTS = 300;

    public enum Result {
        NEW("Bắt mới"), DUP("Gặp lại (đã bắt)"), FILTERED("Bị lọc");

        public final String label;

        Result(String label) {
            this.label = label;
        }
    }

    public record Event(int n, String time, Result result, Integer gid, String method, String path, String host,
                        String branch, String filed, String source, String note) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", n);
            m.put("time", time);
            m.put("result", result.name());
            m.put("gid", gid);
            m.put("method", method);
            m.put("path", path);
            m.put("host", host);
            m.put("branch", branch);
            m.put("filed", filed);
            m.put("source", source);
            m.put("note", note);
            return m;
        }

        static Event fromMap(Map<String, Object> m) {
            Result r;
            try {
                r = Result.valueOf(Json.str(m, "result", "NEW").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                r = Result.NEW;
            }
            Object gid = m.get("gid");
            return new Event((int) Json.num(m, "n", 0), Json.str(m, "time", ""), r,
                    gid instanceof Number num ? num.intValue() : null, Json.str(m, "method", ""),
                    Json.str(m, "path", ""), Json.str(m, "host", ""), Json.str(m, "branch", ""),
                    Json.str(m, "filed", ""), Json.str(m, "source", ""), Json.str(m, "note", ""));
        }
    }

    private static final class Row {
        int count;
        int[] byResult = new int[3];
        final List<Event> events = new ArrayList<>();
    }

    private String signature;
    private final Map<Integer, Row> rows = new HashMap<>();
    private volatile boolean dirty;
    private volatile long total;

    public synchronized String signature() {
        return signature;
    }

    public synchronized void reset(String sig) {
        signature = sig;
        rows.clear();
        total = 0;
        dirty = true;
    }

    public synchronized int record(TargetEntry e, Result result, Integer gid, String method, String path,
                                   String host, String branch, String filed, String source, String note) {
        Row r = rows.computeIfAbsent(e.rowNo(), k -> new Row());
        r.count++;
        r.byResult[result.ordinal()]++;
        r.events.add(new Event(r.count, themythofcrawl.model.Clock.stamp(), result, gid, method, path, host, branch,
                filed, source, note));
        if (r.events.size() > MAX_EVENTS) {
            r.events.remove(0);
        }
        total++;
        dirty = true;
        return r.count;
    }

    public synchronized int count(TargetEntry e) {
        Row r = e == null ? null : rows.get(e.rowNo());
        return r == null ? 0 : r.count;
    }

    /** {count, new, dup, filtered} */
    public synchronized int[] stats(TargetEntry e) {
        Row r = e == null ? null : rows.get(e.rowNo());
        return r == null ? new int[4] : new int[] {r.count, r.byResult[0], r.byResult[1], r.byResult[2]};
    }

    public synchronized List<Event> events(TargetEntry e) {
        Row r = e == null ? null : rows.get(e.rowNo());
        return r == null ? List.of() : List.copyOf(r.events);
    }

    /** Branches the row was met on, most recent first, each once. */
    public List<String> positions(TargetEntry e) {
        List<String> out = new ArrayList<>();
        List<Event> ev = events(e);
        for (int i = ev.size() - 1; i >= 0; i--) {
            String b = ev.get(i).branch();
            if (b != null && !b.isEmpty() && !out.contains(b)) {
                out.add(b);
            }
        }
        return out;
    }

    public long total() {
        return total;
    }

    public synchronized int rowsSeen() {
        return (int) rows.values().stream().filter(r -> r.count > 0).count();
    }

    public boolean dirty() {
        return dirty;
    }

    /** '5 lần (1 mới · 4 gặp lại)' */
    public String cell(TargetEntry e) {
        int[] s = stats(e);
        if (s[0] == 0) {
            return "0";
        }
        List<String> parts = new ArrayList<>();
        if (s[1] > 0) {
            parts.add(s[1] + " mới");
        }
        if (s[2] > 0) {
            parts.add(s[2] + " gặp lại");
        }
        if (s[3] > 0) {
            parts.add(s[3] + " bị lọc");
        }
        return s[0] + " lần (" + String.join(" · ", parts) + ")";
    }

    public synchronized String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signature", signature);
        Map<String, Object> rs = new LinkedHashMap<>();
        rows.forEach((k, r) -> {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("count", r.count);
            rm.put("new", r.byResult[0]);
            rm.put("dup", r.byResult[1]);
            rm.put("filtered", r.byResult[2]);
            List<Object> evs = new ArrayList<>();
            for (Event ev : r.events) {
                evs.add(ev.toMap());
            }
            rm.put("events", evs);
            rs.put(String.valueOf(k), rm);
        });
        m.put("rows", rs);
        dirty = false;
        return Json.write(m);
    }

    /** Load counters saved for the same file; another file starts from zero. */
    @SuppressWarnings("unchecked")
    public synchronized void load(String json, String sig) {
        rows.clear();
        total = 0;
        signature = sig;
        dirty = false;
        if (json == null || json.isBlank() || sig == null) {
            return;
        }
        Map<String, Object> m;
        try {
            m = Json.parseObject(json);
        } catch (RuntimeException e) {
            return;
        }
        if (!sig.equals(Json.str(m, "signature", null))) {
            return;
        }
        Json.obj(m, "rows").forEach((k, v) -> {
            try {
                Map<String, Object> rm = (Map<String, Object>) v;
                Row r = new Row();
                r.count = (int) Json.num(rm, "count", 0);
                r.byResult[0] = (int) Json.num(rm, "new", 0);
                r.byResult[1] = (int) Json.num(rm, "dup", 0);
                r.byResult[2] = (int) Json.num(rm, "filtered", 0);
                for (Object ev : Json.list(rm, "events")) {
                    r.events.add(Event.fromMap((Map<String, Object>) ev));
                }
                rows.put(Integer.parseInt(k), r);
                total += r.count;
            } catch (RuntimeException ignored) {
                // a damaged row is dropped, the others are kept
            }
        });
    }
}
