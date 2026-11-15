package themythofcrawl.place;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Json;
import themythofcrawl.core.Text;
import themythofcrawl.model.Clock;

/**
 * 'Giữ chỗ': for every endpoint (method + host + path, the dedup identity) the branch it was last
 * confirmed in (or, never confirmed, where it was when deleted), whether an API is alive for it, how
 * often it was deleted / met / skipped as duplicate, and an audit log of every placement change. It
 * survives deleting APIs, clearing everything and dedup resets, so a deleted API that comes back
 * returns to its old place.
 */
public final class PlacementBook {
    public static final int MAX_LOG = 5000;
    public static final int MAX_HISTORY = 30;

    public enum Action {
        CAPTURE("Bắt mới"),
        PENDING("⏳ Bắt mới · chờ xác nhận"),
        RETURN("↩ Quay về chỗ cũ · chờ xác nhận"),
        CONFIRM("✔ Xác nhận vị trí"),
        MOVE("↪ Chuyển vị trí"),
        REVERT("↶ Hoàn tác vị trí"),
        DELETE("🗑 Xóa API (giữ chỗ)"),
        RENAME("✏ Đổi tên nhánh"),
        EDIT("✏ Sửa giữ chỗ"),
        FORGET("✖ Quên giữ chỗ"),
        MANUAL("➕ Thêm giữ chỗ"),
        RECAPTURE("♻ Cho phép bắt lại"),
        DUP("⧉ Gặp API trùng ở chỗ mới · chờ quyết định"),
        DUP_KEEP("⧉ Giữ API trùng ở chỗ mới"),
        HIDE("🙈 Ẩn khỏi màn hình crawl"),
        SHOW("👁 Hiện lại");

        public final String label;

        Action(String label) {
            this.label = label;
        }
    }

    /** One remembered endpoint. */
    public static final class Record {
        public final String key;
        public String method;
        public String host;
        public String path;
        public String place;
        public boolean placeConfirmed;
        public Integer gid;
        public boolean pending;
        public int deleted;
        public String deletedAt;
        public int seen;
        public int skipped;
        public String lastSeen;
        public String lastFoundAt;
        public int nextNo;
        public final List<String[]> history = new ArrayList<>();   // [time, place]

        Record(String key) {
            this.key = key;
        }

        public State state() {
            if (gid != null) {
                return pending ? State.PENDING : State.LIVE;
            }
            return place != null ? State.DELETED : State.NONE;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("method", method);
            m.put("host", host);
            m.put("path", path);
            m.put("place", place);
            m.put("place_confirmed", placeConfirmed);
            m.put("gid", gid);
            m.put("pending", pending);
            m.put("deleted", deleted);
            m.put("deleted_at", deletedAt);
            m.put("seen", seen);
            m.put("skipped", skipped);
            m.put("last_seen", lastSeen);
            m.put("last_found_at", lastFoundAt);
            m.put("next_no", nextNo);
            List<Object> h = new ArrayList<>();
            for (String[] e : history) {
                h.add(List.of(e[0], e[1]));
            }
            m.put("history", h);
            return m;
        }
    }

    public enum State {
        LIVE("Đang có API"), PENDING("⏳ Chờ xác nhận"), DELETED("👻 Đã xóa (giữ chỗ)"), NONE("Chưa có vị trí");

        public final String label;

        State(String label) {
            this.label = label;
        }
    }

    /** One line of the placement log. */
    public record Event(int id, String time, Action action, String key, Integer gid, String method, String path,
                        String src, String dst, String note) {
    }

    private final Map<String, Record> entries = new LinkedHashMap<>();
    private final List<Event> log = new ArrayList<>();
    private int seq;
    private volatile boolean dirty;

    public synchronized Record get(String key) {
        return key == null ? null : entries.get(key);
    }

    public synchronized List<Record> records() {
        return new ArrayList<>(entries.values());
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized Record ensure(String key, String method, String host, String path) {
        Record r = entries.get(key);
        if (r == null) {
            r = new Record(key);
            r.method = method;
            r.host = host;
            r.path = Text.stripQuery(path);
            entries.put(key, r);
            dirty = true;
        }
        return r;
    }

    public synchronized void setPlace(String key, String place) {
        Record r = entries.get(key);
        if (r == null) {
            return;
        }
        if (place != null && !place.equals(r.place)) {
            r.history.add(new String[] {Clock.stamp(), place});
            while (r.history.size() > MAX_HISTORY) {
                r.history.remove(0);
            }
        }
        r.place = place;
        r.placeConfirmed = place != null;
        dirty = true;
    }

    public synchronized void live(String key, Integer gid, boolean pending) {
        Record r = entries.get(key);
        if (r != null) {
            r.gid = gid;
            r.pending = pending;
            dirty = true;
        }
    }

    public synchronized void setPending(String key, boolean pending) {
        Record r = entries.get(key);
        if (r != null) {
            r.pending = pending;
            dirty = true;
        }
    }

    public synchronized void seen(String key, String foundAt, boolean skipped) {
        Record r = entries.get(key);
        if (r == null) {
            return;
        }
        r.seen++;
        if (skipped) {
            r.skipped++;
        }
        r.lastSeen = Clock.stamp();
        r.lastFoundAt = foundAt;
        dirty = true;
    }

    /**
     * One copy of the endpoint was deleted. Its place is kept: the confirmed one, else the branch it was
     * in when deleted (so an API you never confirmed still comes back where it was).
     */
    public synchronized Record deleted(String key, String lastPlace, Integer remainingGid) {
        Record r = entries.get(key);
        if (r == null) {
            return null;
        }
        r.gid = remainingGid;
        if (remainingGid == null) {
            r.pending = false;
        }
        r.deleted++;
        r.deletedAt = Clock.stamp();
        if (r.place == null && lastPlace != null) {
            r.place = lastPlace;
            r.placeConfirmed = false;
            r.history.add(new String[] {r.deletedAt, lastPlace});
        }
        dirty = true;
        return r;
    }

    /** 1 for the first API of an endpoint, then 2, 3 ... for duplicates (never reused). */
    public synchronized int nextCopyNo(String key) {
        Record r = entries.get(key);
        if (r == null) {
            return 1;
        }
        r.nextNo++;
        dirty = true;
        return r.nextNo;
    }

    public synchronized Record forget(String key) {
        dirty = true;
        return entries.remove(key);
    }

    public synchronized int renamePrefix(String oldPrefix, String newPrefix) {
        int n = 0;
        for (Record r : entries.values()) {
            if (r.place != null && Hierarchy.matchesPrefix(r.place, oldPrefix)) {
                r.place = Hierarchy.replacePrefix(r.place, oldPrefix, newPrefix);
                n++;
            }
        }
        dirty = true;
        return n;
    }

    public synchronized Event event(Action action, String key, Integer gid, String method, String path, String src,
                                    String dst, String note) {
        Event e = new Event(++seq, Clock.stamp(), action, key, gid, Text.nz(method), Text.stripQuery(Text.nz(path)),
                src, dst, Text.nz(note));
        log.add(e);
        if (log.size() > MAX_LOG) {
            log.subList(0, log.size() - MAX_LOG).clear();
        }
        dirty = true;
        return e;
    }

    public synchronized List<Event> log() {
        return new ArrayList<>(log);
    }

    public synchronized void clearLog() {
        log.clear();
        dirty = true;
    }

    public synchronized List<Event> eventsFor(String key) {
        return log.stream().filter(e -> key.equals(e.key())).toList();
    }

    /** Remembered places of endpoints with no live API inside `prefix`. */
    public synchronized List<Record> deletedUnder(String prefix) {
        List<Record> out = new ArrayList<>();
        for (Record r : entries.values()) {
            if (r.gid == null && r.place != null && Hierarchy.matchesPrefix(r.place, prefix)) {
                out.add(r);
            }
        }
        return out;
    }

    public boolean dirty() {
        return dirty;
    }

    public synchronized String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> es = new LinkedHashMap<>();
        entries.forEach((k, r) -> es.put(k, r.toMap()));
        m.put("entries", es);
        List<Object> lg = new ArrayList<>();
        for (Event e : log) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("id", e.id());
            em.put("time", e.time());
            em.put("action", e.action().name());
            em.put("key", e.key());
            em.put("gid", e.gid());
            em.put("method", e.method());
            em.put("path", e.path());
            em.put("src", e.src());
            em.put("dst", e.dst());
            em.put("note", e.note());
            lg.add(em);
        }
        m.put("log", lg);
        m.put("seq", seq);
        dirty = false;
        return Json.write(m);
    }

    /**
     * Load a saved book. With `liveGids` the APIs that still exist (a reopened workspace) keep their link;
     * every other endpoint becomes a placeholder.
     */
    @SuppressWarnings("unchecked")
    public synchronized void load(String json, Collection<Integer> liveGids) {
        entries.clear();
        log.clear();
        seq = 0;
        dirty = false;
        if (json == null || json.isBlank()) {
            return;
        }
        Map<String, Object> m;
        try {
            m = Json.parseObject(json);
        } catch (RuntimeException e) {
            return;
        }
        Json.obj(m, "entries").forEach((k, v) -> {
            Map<String, Object> rm = (Map<String, Object>) v;
            Record r = new Record(k);
            r.method = Json.str(rm, "method", "");
            r.host = Json.str(rm, "host", "");
            r.path = Json.str(rm, "path", "/");
            r.place = Json.str(rm, "place", null);
            r.placeConfirmed = Json.bool(rm, "place_confirmed", r.place != null);
            Object gid = rm.get("gid");
            r.gid = gid instanceof Number n && liveGids != null && liveGids.contains(n.intValue()) ? n.intValue() : null;
            r.pending = r.gid != null && Json.bool(rm, "pending", false);
            r.deleted = (int) Json.num(rm, "deleted", 0);
            r.deletedAt = Json.str(rm, "deleted_at", null);
            r.seen = (int) Json.num(rm, "seen", 0);
            r.skipped = (int) Json.num(rm, "skipped", 0);
            r.lastSeen = Json.str(rm, "last_seen", null);
            r.lastFoundAt = Json.str(rm, "last_found_at", null);
            r.nextNo = (int) Json.num(rm, "next_no", 0);
            for (Object h : Json.list(rm, "history")) {
                if (h instanceof List<?> l && l.size() == 2) {
                    r.history.add(new String[] {String.valueOf(l.get(0)), String.valueOf(l.get(1))});
                }
            }
            entries.put(k, r);
        });
        for (Object o : Json.list(m, "log")) {
            Map<String, Object> em = (Map<String, Object>) o;
            Action a;
            try {
                a = Action.valueOf(Json.str(em, "action", "CAPTURE"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            Object gid = em.get("gid");
            log.add(new Event((int) Json.num(em, "id", 0), Json.str(em, "time", ""), a, Json.str(em, "key", null),
                    gid instanceof Number n ? n.intValue() : null, Json.str(em, "method", ""), Json.str(em, "path", ""),
                    Json.str(em, "src", null), Json.str(em, "dst", null), Json.str(em, "note", "")));
        }
        seq = (int) Json.num(m, "seq", log.size());
    }
}
