package themythofcrawl.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import themythofcrawl.model.ApiGroup;

/**
 * What changed since the UI last looked. Any thread records here (never blocks); the UI timer drains
 * it once per tick, so a burst of hundreds of captures costs a handful of repaints.
 */
public final class Changes {
    public record Batch(List<ApiGroup> added, Set<ApiGroup> dirty, boolean structure, boolean status,
                        List<Logbook.Line> log) {
        public boolean empty() {
            return added.isEmpty() && dirty.isEmpty() && !structure && !status && log.isEmpty();
        }
    }

    private List<ApiGroup> added = new ArrayList<>();
    private Set<ApiGroup> dirty = new LinkedHashSet<>();
    private boolean structure;
    private boolean status;
    private List<Logbook.Line> log = new ArrayList<>();

    public synchronized void added(ApiGroup g) {
        added.add(g);
        structure = true;
    }

    public synchronized void dirty(ApiGroup g) {
        dirty.add(g);
        status = true;
    }

    public synchronized void structure() {
        structure = true;
        status = true;
    }

    public synchronized void status() {
        status = true;
    }

    public synchronized void log(Logbook.Line l) {
        log.add(l);
    }

    public synchronized Batch drain() {
        Batch b = new Batch(added, dirty, structure, status, log);
        added = new ArrayList<>();
        dirty = new LinkedHashSet<>();
        structure = false;
        status = false;
        log = new ArrayList<>();
        return b;
    }
}
