package themythofcrawl.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import themythofcrawl.app.Prefs;
import themythofcrawl.core.Json;
import themythofcrawl.model.Clock;

/**
 * Workspaces live in &lt;root&gt;/theMythOfCrawl_workspace/&lt;Burp project&gt;/&lt;workspace&gt;/. The root is the
 * folder chosen by the user, else the project folder of the extension jar (build/libs/x.jar -> the
 * repository folder). The last workspace of each Burp project is reopened automatically.
 */
public final class Workspaces {
    public static final String FOLDER = "theMythOfCrawl_workspace";
    public static final String PREF_ROOT = "tmoc.workspaceRoot";
    public static final String PREF_LAST = "tmoc.lastWorkspace.";
    public static final String PREF_LAST_ANY = "tmoc.lastWorkspace";
    public static final String PROJECT_LINK = "tmoc.workspaceDir";

    /** A workspace folder with its name and API count. */
    public record Info(Path dir, String project, String name, String created, int apis) {
    }

    private final Prefs prefs;
    private final Prefs project;
    private final String extensionFile;

    public Workspaces(Prefs prefs, Prefs project, String extensionFile) {
        this.prefs = prefs;
        this.project = project;
        this.extensionFile = extensionFile;
    }

    /** Folder that holds theMythOfCrawl_workspace. */
    public Path root() {
        String chosen = prefs.get(PREF_ROOT);
        if (chosen != null && !chosen.isBlank()) {
            return Paths.get(chosen);
        }
        return defaultRoot(extensionFile);
    }

    public void setRoot(Path dir) {
        prefs.set(PREF_ROOT, dir == null ? null : dir.toAbsolutePath().toString());
    }

    public static Path defaultRoot(String extensionFile) {
        if (extensionFile == null || extensionFile.isBlank()) {
            return Paths.get(System.getProperty("user.home"));
        }
        Path jar = Paths.get(extensionFile).toAbsolutePath();
        Path dir = jar.getParent();
        if (dir != null && dir.getFileName() != null && dir.getFileName().toString().equals("libs")
                && dir.getParent() != null && dir.getParent().getFileName() != null
                && dir.getParent().getFileName().toString().equals("build") && dir.getParent().getParent() != null) {
            return dir.getParent().getParent();
        }
        return dir == null ? Paths.get(System.getProperty("user.home")) : dir;
    }

    public Path base() {
        return root().resolve(FOLDER);
    }

    /** File-system safe folder name (Windows forbidden characters removed, length capped). */
    public static String safe(String name) {
        String s = name == null ? "" : name.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_").trim();
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            s = "Workspace";
        }
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    public List<Info> list() {
        List<Info> out = new ArrayList<>();
        Path base = base();
        if (!Files.isDirectory(base)) {
            return out;
        }
        try (DirectoryStream<Path> projects = Files.newDirectoryStream(base, Files::isDirectory)) {
            for (Path p : projects) {
                try (DirectoryStream<Path> ws = Files.newDirectoryStream(p, Files::isDirectory)) {
                    for (Path w : ws) {
                        Info i = info(w);
                        if (i != null) {
                            out.add(i);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing(Info::project).thenComparing(Info::name));
        return out;
    }

    public static Info info(Path dir) {
        Path meta = dir.resolve(FileStorage.META);
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        try {
            Map<String, Object> m = Json.parseObject(Files.readString(meta, StandardCharsets.UTF_8));
            int apis = 0;
            Path a = dir.resolve("apis");
            if (Files.isDirectory(a)) {
                try (Stream<Path> s = Files.list(a)) {
                    apis = (int) s.count();
                }
            }
            return new Info(dir, Json.str(m, "project", dir.getParent().getFileName().toString()),
                    Json.str(m, "name", dir.getFileName().toString()), Json.str(m, "created", ""), apis);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public FileStorage create(String projectName, String name) {
        Path dir = base().resolve(safe(projectName)).resolve(safe(name));
        int n = 2;
        while (Files.exists(dir.resolve(FileStorage.META))) {
            dir = base().resolve(safe(projectName)).resolve(safe(name) + " (" + n++ + ")");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("project", projectName);
        m.put("created", Clock.stamp());
        m.put("version", 2);
        FileStorage.writeAtomic(dir.resolve(FileStorage.META), Json.pretty(m).getBytes(StandardCharsets.UTF_8));
        return new FileStorage(dir, name);
    }

    public FileStorage open(Path dir) {
        Info i = info(dir);
        if (i == null) {
            throw new IllegalArgumentException("Không phải thư mục workspace: " + dir);
        }
        return new FileStorage(dir, i.name());
    }

    public void delete(Path dir) {
        if (info(dir) == null) {
            throw new IllegalArgumentException("Không phải thư mục workspace: " + dir);
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void remember(String projectId, FileStorage st) {
        String d = st.dir().toAbsolutePath().toString();
        prefs.set(PREF_LAST + projectId, d);
        prefs.set(PREF_LAST_ANY, d);
        if (project != null) {
            project.set(PROJECT_LINK, d);
        }
    }

    /** The project's linked workspace, its last one, the last one at all, else a new 'Default'. */
    public FileStorage start(String projectId, String projectName) {
        String[] candidates = {project == null ? null : project.get(PROJECT_LINK), prefs.get(PREF_LAST + projectId),
            prefs.get(PREF_LAST_ANY)};
        for (String c : candidates) {
            if (c != null && !c.isBlank() && info(Paths.get(c)) != null) {
                return open(Paths.get(c));
            }
        }
        Path def = base().resolve(safe(projectName)).resolve("Default");
        return info(def) != null ? open(def) : create(projectName, "Default");
    }
}
