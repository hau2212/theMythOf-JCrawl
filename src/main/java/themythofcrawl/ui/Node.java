package themythofcrawl.ui;

import javax.swing.tree.DefaultMutableTreeNode;

import themythofcrawl.core.AuditLevel;

/** A tree row: what it is, what it points to, its label, colour and a stable key (selection / expansion restore). */
public final class Node extends DefaultMutableTreeNode {
    /**
     * {@code APIS}: collapsible bucket holding the APIs that sit directly in a folder which also has
     * sub-folders (payload = the folder path). Keeps APIs on top and sub-folders below instead of mixing them.
     */
    public enum Kind { ROOT, FOLDER, APIS, CLUSTER, HELD_PENDING, GROUP, ORIG, ROLE, PLACE, GHOSTS, GHOST, LOADING, LOGBRANCH, LOGAPI, LOGSYS, LOGLINE }

    /** Precomputed chip labels for folder / root rows so TreeRenderer allocates nothing while painting. */
    public record FolderMeta(String name, String levelChip, String countChip, String verdictChip, String pendingChip,
                             String ghostChip) {
    }

    public final Kind kind;
    public final Object payload;
    public final String label;
    public final AuditLevel level;
    public final Object key;
    public final int depth;
    public FolderMeta folderMeta;
    public Object renderCache;
    public int renderStamp;
    public boolean loaded = true;

    public Node(Kind kind, Object payload, String label, AuditLevel level, Object key, int depth) {
        super(label);
        this.kind = kind;
        this.payload = payload;
        this.label = label;
        this.level = level == null ? AuditLevel.NONE : level;
        this.key = key;
        this.depth = depth;
    }

    public Node(Kind kind, Object payload, String label, AuditLevel level, Object key, int depth, FolderMeta folderMeta) {
        this(kind, payload, label, level, key, depth);
        this.folderMeta = folderMeta;
    }

    public static Node placeholder() {
        return new Node(Kind.LOADING, null, "...", AuditLevel.NONE, null, 0);
    }

    /** Lazy node: a placeholder child until expanded. */
    public Node lazy() {
        loaded = false;
        add(placeholder());
        return this;
    }

    @Override
    public String toString() {
        return label;
    }
}
