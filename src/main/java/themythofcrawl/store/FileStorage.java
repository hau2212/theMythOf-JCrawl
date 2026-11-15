package themythofcrawl.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import themythofcrawl.app.Storage;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Blob;

/**
 * A workspace folder: state.json + apis/&lt;gid&gt;/meta.json and the raw messages of each API. Only the
 * APIs that changed are rewritten, and messages are read back only when shown or sent.
 */
public final class FileStorage implements Storage {
    public static final String META = "workspace.json";
    public static final String STATE = "state.json";
    private final Path dir;
    private final String name;

    public FileStorage(Path dir, String name) {
        this.dir = dir;
        this.name = name;
        try {
            Files.createDirectories(dir.resolve("apis"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path dir() {
        return dir;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String location() {
        return dir.toAbsolutePath().toString();
    }

    private Path apiDir(int gid) {
        return dir.resolve("apis").resolve(String.valueOf(gid));
    }

    static void writeAtomic(Path target, byte[] data) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Blob put(int gid, String file, byte[] data) {
        if (data == null) {
            return null;
        }
        Path p = apiDir(gid).resolve(file);
        writeAtomic(p, data);
        return new FileBlob(p, data.length);
    }

    @Override
    public Blob blob(int gid, String file) {
        Path p = apiDir(gid).resolve(file);
        try {
            return Files.isRegularFile(p) ? new FileBlob(p, (int) Files.size(p)) : null;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void saveGroups(Collection<ApiGroup> groups, Function<ApiGroup, String> codec) {
        for (ApiGroup g : groups) {
            if (Files.isDirectory(apiDir(g.gid())) || g.origRequest() != null) {
                writeAtomic(apiDir(g.gid()).resolve("meta.json"), codec.apply(g).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    @Override
    public void deleteGroup(int gid) {
        Path d = apiDir(gid);
        if (!Files.exists(d)) {
            return;
        }
        try (Stream<Path> s = Files.walk(d)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // left over, harmless
                }
            });
        } catch (IOException ignored) {
            // nothing else to do
        }
    }

    @Override
    public List<String> loadGroupDocs() {
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir.resolve("apis"))) {
            for (Path d : ds) {
                Path meta = d.resolve("meta.json");
                if (Files.isRegularFile(meta)) {
                    out.add(Files.readString(meta, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    @Override
    public void saveState(String json) {
        writeAtomic(dir.resolve(STATE), json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String loadState() {
        Path p = dir.resolve(STATE);
        try {
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
    }

    /** Message bytes in a file, read when needed. */
    record FileBlob(Path path, int size) implements Blob {
        @Override
        public byte[] bytes() {
            try {
                return Files.readAllBytes(path);
            } catch (IOException e) {
                return null;
            }
        }
    }
}
