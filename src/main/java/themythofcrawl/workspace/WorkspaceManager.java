package themythofcrawl.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Holds the open workspace and saves it in the background: workspace.json is rewritten at most once
 * every {@link #SAVE_INTERVAL_MS} while something changed, and immediately on switch / unload.
 */
public final class WorkspaceManager {
    public static final long SAVE_INTERVAL_MS = 1500;

    private final WorkspaceStore store;
    private final Consumer<String> errorLog;
    private final List<Consumer<Workspace>> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService saver;
    private volatile Workspace current;

    public WorkspaceManager(WorkspaceStore store, Consumer<String> errorLog) {
        this.store = store;
        this.errorLog = errorLog;
        this.saver = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "theMythOfCrawl-autosave");
            t.setDaemon(true);
            return t;
        });
        saver.scheduleWithFixedDelay(this::saveIfDirty, SAVE_INTERVAL_MS, SAVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public WorkspaceStore store() {
        return store;
    }

    public Workspace current() {
        return current;
    }

    public void addListener(Consumer<Workspace> listener) {
        listeners.add(listener);
    }

    public synchronized void saveIfDirty() {
        Workspace w = current;
        if (w != null && w.isDirty()) {
            try {
                store.save(w);
            } catch (IOException | RuntimeException e) {
                errorLog.accept("Không lưu được workspace " + w.dir() + ": " + e);
            }
        }
    }

    /** Saves the current workspace, then makes {@code next} current and notifies listeners. */
    public void switchTo(Workspace next) {
        synchronized (this) {
            saveIfDirty();
            current = next;
        }
        for (Consumer<Workspace> l : listeners) {
            l.accept(next);
        }
    }

    public Workspace openOrCreate(String project, String name) throws IOException {
        Workspace w = store.exists(project, name) ? store.open(project, name) : store.create(project, name);
        switchTo(w);
        return w;
    }

    public Workspace open(Path dir) throws IOException {
        Workspace w = store.open(dir);
        switchTo(w);
        return w;
    }

    public void shutdown() {
        saveIfDirty();
        saver.shutdownNow();
    }
}
