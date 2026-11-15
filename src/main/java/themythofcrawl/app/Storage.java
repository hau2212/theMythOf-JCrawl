package themythofcrawl.app;

import java.util.Collection;
import java.util.List;

import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Blob;

/** Where a workspace keeps its APIs, messages and state. */
public interface Storage {
    /** Name shown in the UI. */
    String name();

    /** Folder (or a description) shown in the UI. */
    String location();

    /** Store message bytes of one API; the returned blob may read them back lazily. */
    Blob put(int gid, String name, byte[] data);

    void saveGroups(Collection<ApiGroup> groups, java.util.function.Function<ApiGroup, String> codec);

    void deleteGroup(int gid);

    /** Saved group documents (JSON), in any order. */
    List<String> loadGroupDocs();

    /** Re-open a stored message of an API. */
    Blob blob(int gid, String name);

    void saveState(String json);

    String loadState();

    void close();
}
