package themythofcrawl.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import themythofcrawl.model.Clock;

/** Log lines: system / actions, and per captured API (bounded). */
public final class Logbook {
    public static final int SYSTEM_MAX = 3000;
    public static final int PER_API_MAX = 200;

    public record Line(String time, String text, Integer gid) {
    }

    private final Deque<Line> system = new ArrayDeque<>();
    private final Map<Integer, Deque<Line>> perApi = new HashMap<>();

    public synchronized Line add(String text, Integer gid) {
        Line l = new Line(Clock.hms(), text, gid);
        Deque<Line> d = gid == null ? system : perApi.computeIfAbsent(gid, k -> new ArrayDeque<>());
        d.addLast(l);
        while (d.size() > (gid == null ? SYSTEM_MAX : PER_API_MAX)) {
            d.removeFirst();
        }
        return l;
    }

    public synchronized List<Line> system() {
        return new ArrayList<>(system);
    }

    public synchronized List<Line> of(int gid) {
        Deque<Line> d = perApi.get(gid);
        return d == null ? List.of() : new ArrayList<>(d);
    }

    public synchronized int count(int gid) {
        Deque<Line> d = perApi.get(gid);
        return d == null ? 0 : d.size();
    }

    public synchronized void clearSystem() {
        system.clear();
    }

    public synchronized void forget(int gid) {
        perApi.remove(gid);
    }

    public synchronized void clearAll() {
        system.clear();
        perApi.clear();
    }
}
