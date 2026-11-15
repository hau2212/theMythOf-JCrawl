package themythofcrawl.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import themythofcrawl.util.Json;
import themythofcrawl.util.Names;

/**
 * Workspaces on disk, two levels below one root folder:
 * <pre>
 *   &lt;root&gt;/theMythOfCrawl_workspace/
 *       &lt;Burp project&gt;/            level 1
 *           &lt;workspace&gt;/            level 2
 *               workspace.json        branches, API list, settings, Excel list
 *               messages/api-N.request, api-N.response
 * </pre>
 * The files belong to the extension, not to Burp, so they survive Burp restarts, project switches
 * and a move between Burp Pro and Community (which only has temporary projects).
 */
public final class WorkspaceStore {
    public static final String ROOT_NAME = "theMythOfCrawl_workspace";
    public static final String META = "workspace.json";
    public static final String MESSAGES = "messages";

    private final Path root;

    public WorkspaceStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /**
     * Default root: next to the extension jar, i.e. inside the folder the jar was loaded from.
     * A jar built in place ({@code <project>/build/libs/x.jar}) points at the project folder itself.
     */
    public static Path defaultRoot(String extensionFile) {
        Path base = null;
        if (extensionFile != null && !extensionFile.isBlank()) {
            try {
                Path jar = Paths.get(extensionFile).toAbsolutePath();
                base = jar.getParent();
                if (base != null && base.getFileName() != null && base.getParent() != null
                        && "libs".equals(base.getFileName().toString())
                        && "build".equals(base.getParent().getFileName().toString())
                        && base.getParent().getParent() != null) {
                    base = base.getParent().getParent();
                }
            } catch (RuntimeException ignored) {
                base = null;
            }
        }
        if (base == null) {
            base = Paths.get(System.getProperty("user.home"));
        }
        return base.resolve(ROOT_NAME);
    }

    public Path projectDir(String project) {
        return Names.child(root, project);
    }

    public List<String> projects() throws IOException {
        return childDirs(root);
    }

    public List<String> workspaces(String project) throws IOException {
        List<String> out = new ArrayList<>();
        for (String d : childDirs(projectDir(project))) {
            if (Files.isRegularFile(projectDir(project).resolve(d).resolve(META))) {
                out.add(d);
            }
        }
        return out;
    }

    private static List<String> childDirs(Path dir) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path p : ds) {
                out.add(p.getFileName().toString());
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public boolean exists(String project, String name) {
        return Files.isRegularFile(Names.child(projectDir(project), name).resolve(META));
    }

    public Workspace create(String project, String name) throws IOException {
        Path dir = Names.child(projectDir(project), name);
        if (Files.exists(dir.resolve(META))) {
            throw new IOException("Workspace already exists: " + dir);
        }
        Files.createDirectories(dir.resolve(MESSAGES));
        Workspace w = new Workspace(dir, project, name, System.currentTimeMillis());
        save(w);
        return w;
    }

    public Workspace open(Path dir) throws IOException {
        Path meta = dir.resolve(META);
        String text = Files.readString(meta, StandardCharsets.UTF_8);
        Map<String, Object> m = Json.parseObject(text);
        String project = dir.getParent() != null ? dir.getParent().getFileName().toString() : "Project";
        Workspace w = Workspace.fromJson(dir, m, project, dir.getFileName().toString());
        Files.createDirectories(dir.resolve(MESSAGES));
        return w;
    }

    public Workspace open(String project, String name) throws IOException {
        return open(Names.child(projectDir(project), name));
    }

    /** Writes workspace.json atomically (temp file + move): a crash never leaves a half-written file. */
    public synchronized void save(Workspace w) throws IOException {
        Files.createDirectories(w.dir());
        Path meta = w.dir().resolve(META);
        Path tmp = w.dir().resolve(META + ".tmp");
        Files.writeString(tmp, Json.pretty(w.toJson()), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, meta, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, meta, StandardCopyOption.REPLACE_EXISTING);
        }
        w.markClean();
    }

    // ---- request / response bytes
    private static Path message(Workspace w, int apiId, String kind) {
        return w.dir().resolve(MESSAGES).resolve(String.format("api-%05d.%s", apiId, kind));
    }

    public void writeMessage(Workspace w, int apiId, String kind, byte[] data) throws IOException {
        if (data == null) {
            return;
        }
        Path target = message(w, apiId, kind);
        Files.createDirectories(target.getParent());
        Files.write(target, data);
    }

    public byte[] readMessage(Workspace w, int apiId, String kind) {
        Path p = message(w, apiId, kind);
        try {
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            return null;
        }
    }

    public void deleteMessages(Workspace w, int apiId) {
        for (String kind : new String[] {"request", "response"}) {
            try {
                Files.deleteIfExists(message(w, apiId, kind));
            } catch (IOException ignored) {
                // a leftover file is harmless
            }
        }
    }

    /** Recursively deletes one workspace folder (only folders that hold a workspace.json). */
    public void delete(Workspace w) throws IOException {
        Path dir = w.dir();
        if (!Files.isRegularFile(dir.resolve(META)) || !dir.startsWith(root)) {
            throw new IOException("Not a workspace folder: " + dir);
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
