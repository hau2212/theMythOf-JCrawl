package themythofcrawl.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Blob;

/** Storage on the heap (tests; or when no folder can be written). */
public final class MemoryStorage implements Storage {
    private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
    private final Map<Integer, String> docs = new ConcurrentHashMap<>();
    private volatile String state;

    @Override
    public String name() {
        return "Bộ nhớ tạm";
    }

    @Override
    public String location() {
        return "(không lưu ra đĩa)";
    }

    @Override
    public Blob put(int gid, String name, byte[] data) {
        if (data == null) {
            return null;
        }
        blobs.put(gid + "/" + name, data);
        return Blob.of(data);
    }

    @Override
    public void saveGroups(Collection<ApiGroup> groups, Function<ApiGroup, String> codec) {
        for (ApiGroup g : groups) {
            docs.put(g.gid(), codec.apply(g));
        }
    }

    @Override
    public void deleteGroup(int gid) {
        docs.remove(gid);
        blobs.keySet().removeIf(k -> k.startsWith(gid + "/"));
    }

    @Override
    public List<String> loadGroupDocs() {
        return new ArrayList<>(docs.values());
    }

    @Override
    public Blob blob(int gid, String name) {
        return Blob.of(blobs.get(gid + "/" + name));
    }

    @Override
    public void saveState(String json) {
        state = json;
    }

    @Override
    public String loadState() {
        return state;
    }

    @Override
    public void close() {
    }
}
