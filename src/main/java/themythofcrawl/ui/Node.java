package themythofcrawl.ui;

import javax.swing.tree.DefaultMutableTreeNode;

import themythofcrawl.core.AuditLevel;

/** A tree row: what it is, what it points to, its label, colour and a stable key (selection / expansion restore). */
public final class Node extends DefaultMutableTreeNode {
    public enum Kind { ROOT, FOLDER, GROUP, ORIG, ROLE, PLACE, GHOSTS, GHOST, LOADING, LOGBRANCH, LOGAPI, LOGSYS, LOGLINE }

    public final Kind kind;
    public final Object payload;
    public final String label;
    public final AuditLevel level;
    public final Object key;
    public final int depth;
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
