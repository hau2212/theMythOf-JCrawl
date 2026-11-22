package themythofcrawl.ui;

import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Branch;
import themythofcrawl.model.PlaceHint;
import themythofcrawl.model.RoleResult;
import themythofcrawl.place.Decisions;
import themythofcrawl.place.PlacementBook;
import themythofcrawl.ui.kit.ThemedTree;

/**
 * The branch tree (Toppage > menu > button "Search" > API > API gốc / roles). Rebuilt from the engine
 * when something changed, keeping what was open and selected; API rows load their children only when
 * opened. Several rows can be selected (Ctrl / Shift + click).
 */
public final class BranchTree {
    public enum Chip { COPY, EXCEL, DUP }

    /** Clicks on the copy glyph / [Excel #STT] / duplicate badge [#n/m] of an API row. */
    public interface ChipHandler {
        void chip(Chip chip, ApiGroup g, JTree tree, int x, int y);
    }

    private final Engine engine;
    public final JTree tree;
    public final DefaultTreeModel model;
    private final Set<Object> collapsed = new HashSet<>();
    private final Set<Object> expanded = new HashSet<>();
    private boolean restoring;
    private ChipHandler chips;
    private BiConsumer<List<ApiGroup>, String> moveHandler;
    private String focusPath;
    private boolean focusRecursive;
    private boolean showApis = true;

    public BranchTree(Engine engine) {
        this.engine = engine;
        Node root = new Node(Node.Kind.ROOT, "", "Tất cả nhánh & API", AuditLevel.NONE, "ROOT", 0);
        model = new DefaultTreeModel(root);
        tree = new ThemedTree(model, new TreeRenderer(engine));
        tree.setRowHeight(themythofcrawl.ui.theme.Theme.ROW_TREE);
        tree.setLargeModel(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setToggleClickCount(2);
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            tree.setDragEnabled(true);
        }
        tree.setDropMode(DropMode.ON);
        tree.setTransferHandler(new BranchTransferHandler());
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                Node n = (Node) e.getPath().getLastPathComponent();
                if (!n.loaded) {
                    loadChildren(n);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent e) {
            }
        });
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent e) {
                if (!restoring) {
                    Node n = (Node) e.getPath().getLastPathComponent();
                    collapsed.remove(n.key);
                    expanded.add(n.key);
                    if (n.kind == Node.Kind.CLUSTER && n.payload instanceof themythofcrawl.model.GroupCluster cl && cl.collapsed()) {
                        cl.setCollapsed(false);
                    }
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent e) {
                if (!restoring) {
                    Node n = (Node) e.getPath().getLastPathComponent();
                    expanded.remove(n.key);
                    collapsed.add(n.key);
                    if (n.kind == Node.Kind.CLUSTER && n.payload instanceof themythofcrawl.model.GroupCluster cl && !cl.collapsed()) {
                        cl.setCollapsed(true);
                    }
                }
            }
        });
        MouseAdapter chipMouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 1 || !SwingUtilities.isLeftMouseButton(e) || chips == null) {
                    return;
                }
                Object[] z = chipAt(e.getX(), e.getY());
                if (z != null) {
                    chips.chip((Chip) z[0], (ApiGroup) z[1], tree, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                tree.setCursor(Cursor.getPredefinedCursor(chipAt(e.getX(), e.getY()) != null ? Cursor.HAND_CURSOR
                        : Cursor.DEFAULT_CURSOR));
            }
        };
        tree.addMouseListener(chipMouse);
        tree.addMouseMotionListener(chipMouse);
    }

    public void onChip(ChipHandler h) {
        chips = h;
    }

    public void onMove(BiConsumer<List<ApiGroup>, String> handler) {
        this.moveHandler = handler;
    }

    public boolean restoring() {
        return restoring;
    }

    /** Isolates the tree on a single branch (exact or including sub-folders). Pass null to exit Focus Mode. */
    public void setFocus(String path, boolean recursive) {
        this.focusPath = path == null || path.isBlank() ? null : Hierarchy.normalize(path);
        this.focusRecursive = recursive;
        rebuild(engine.config().showGhosts);
    }

    public void clearFocus() {
        if (this.focusPath != null) {
            this.focusPath = null;
            this.focusRecursive = false;
            rebuild(engine.config().showGhosts);
        }
    }

    public String focusPath() {
        return focusPath;
    }

    public boolean focusRecursive() {
        return focusRecursive;
    }

    /** Keep an API open in this tree (a returning API shows its 'vị trí cũ / vừa gặp tại' rows). */
    public void expandApi(int gid) {
        expanded.add("G" + gid);
    }

    Transferable createTransferableForSelection() {
        return tree.getTransferHandler() instanceof BranchTransferHandler bth ? bth.createTransferable(tree) : null;
    }

    /**
     * V_7.0: when false the tree shows only branches (with counts), like Burp's Site map; the API rows
     * live in the flat table. Live held requests ([CHỜ DUYỆT]) are always shown.
     */
    public boolean showApis() {
        return showApis;
    }

    public void setShowApis(boolean on) {
        showApis = on;
    }

    /** Drag payload for API rows dragged from another component (the flat API table) onto a branch. */
    public static Transferable transferableFor(List<ApiGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return null;
        }
        DragPayload payload = new DragPayload(groups);
        return new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[] {DragPayload.FLAVOR};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DragPayload.FLAVOR.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                if (!isDataFlavorSupported(flavor)) {
                    throw new UnsupportedFlavorException(flavor);
                }
                return payload;
            }
        };
    }

    static final class DragPayload {
        static final DataFlavor FLAVOR = createFlavor();
        final List<ApiGroup> groups;

        DragPayload(List<ApiGroup> groups) {
            this.groups = List.copyOf(groups);
        }

        private static DataFlavor createFlavor() {
            return new DataFlavor(DragPayload.class, "theMythOfCrawl APIs");
        }
    }

    private final class BranchTransferHandler extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            List<ApiGroup> picked = collectDraggableGroups();
            if (picked.isEmpty()) {
                return null;
            }
            DragPayload payload = new DragPayload(picked);
            return new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[] {DragPayload.FLAVOR};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor flavor) {
                    return DragPayload.FLAVOR.equals(flavor);
                }

                @Override
                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                    if (!isDataFlavorSupported(flavor)) {
                        throw new UnsupportedFlavorException(flavor);
                    }
                    return payload;
                }
            };
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDataFlavorSupported(DragPayload.FLAVOR)) {
                return false;
            }
            String target = resolveTargetBranch(support);
            if (target == null || target.isBlank()) {
                return false;
            }
            List<ApiGroup> groups = extractGroups(support.getTransferable());
            if (groups.isEmpty()) {
                return false;
            }
            String targetKey = Hierarchy.key(target);
            for (ApiGroup g : groups) {
                if (!Hierarchy.key(g.branch()).equals(targetKey)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            String target = resolveTargetBranch(support);
            List<ApiGroup> groups = extractGroups(support.getTransferable());
            if (target == null || groups.isEmpty()) {
                return false;
            }
            if (moveHandler != null) {
                moveHandler.accept(groups, target);
            } else {
                engine.moveToPath(groups, target, "kéo thả");
            }
            return true;
        }

        private List<ApiGroup> collectDraggableGroups() {
            Set<ApiGroup> out = new LinkedHashSet<>();
            TreePath[] paths = tree.getSelectionPaths();
            if (paths != null) {
                for (TreePath p : paths) {
                    Node n = (Node) p.getLastPathComponent();
                    if (n.kind == Node.Kind.GROUP && n.payload instanceof ApiGroup g) {
                        out.add(g);
                    }
                }
            }
            return new ArrayList<>(out);
        }

        private List<ApiGroup> extractGroups(Transferable t) {
            try {
                Object data = t.getTransferData(DragPayload.FLAVOR);
                if (data instanceof DragPayload dp) {
                    return dp.groups;
                }
            } catch (Exception ignored) {
                // Unsupported or unavailable transferable
            }
            return List.of();
        }

        private String resolveTargetBranch(TransferSupport support) {
            TreePath path = null;
            if (support.isDrop() && support.getDropLocation() instanceof JTree.DropLocation dl) {
                path = dl.getPath();
            } else {
                path = tree.getSelectionPath();
            }
            if (path == null || !(path.getLastPathComponent() instanceof Node n)) {
                return null;
            }
            if ((n.kind == Node.Kind.FOLDER || n.kind == Node.Kind.APIS) && n.payload instanceof String s) {
                return s;
            }
            if (n.kind == Node.Kind.CLUSTER && n.payload instanceof themythofcrawl.model.GroupCluster cl) {
                return cl.branchPath();
            }
            ApiGroup g = groupOf(n);
            return g != null ? g.branch() : null;
        }
    }

    // ------------------------------------------------------------------ labels
    public static String rolesLabel(ApiGroup g) {
        return g.results().isEmpty() ? "Tài khoản crawl" : "Tài khoản crawl + " + g.results().size() + " vai trò";
    }

    public static String excelBadge(ApiGroup g) {
        return g.target() == null || g.target().sttText().isBlank() ? "" : "[Excel #" + g.target().sttText() + "]";
    }

    public static String dupBadge(ApiGroup g) {
        return g.dupTotal() <= 1 ? "" : "[#" + g.dupNo() + "/" + g.dupTotal() + "]";
    }

    public static String groupLabel(ApiGroup g) {
        String badge = excelBadge(g);
        String dup = dupBadge(g);
        String summary = Audit.summary(g);
        String text = Text.apiLabel(g.gid()) + (dup.isEmpty() ? "" : " " + dup) + "   ·   " + g.method() + " "
                + (badge.isEmpty() ? "" : badge + " ") + Text.shortPath(g.path(), 48) + "   ·   " + rolesLabel(g);
        if (g.pending()) {
            text = text + "   ·   " + Icons.stripLeadingSymbol(Decisions.pendingLabel(g));
        }
        if (summary != null && !summary.startsWith("Tài khoản crawl")) {
            text += "   ·   " + summary;
        } else if (summary != null && g.results().isEmpty() && summary.contains("(")) {
            text += "   ·   " + summary.substring(summary.indexOf('('));
        }
        return text;
    }

    public static String resultLabel(ApiGroup g, RoleResult r) {
        Object st = r.status() != null ? r.status() : r.state();
        String len = r.length() != null ? r.length() + " B" : "-";
        return r.roleName() + "   ·   " + st + "   ·   " + len + "   ·   " + Audit.badge(g, r);
    }

    private boolean matchesFocus(String branchPath) {
        if (focusPath == null) {
            return true;
        }
        return focusRecursive ? Hierarchy.matchesPrefix(branchPath, focusPath) : Hierarchy.same(branchPath, focusPath);
    }

    // ------------------------------------------------------------------ build
    /** Rebuild from the engine, keeping open folders / APIs and the selection. */
    public void rebuild(boolean showGhosts) {
        List<Object> selected = new ArrayList<>();
        TreePath[] sel = tree.getSelectionPaths();
        if (sel != null) {
            for (TreePath p : sel) {
                selected.add(((Node) p.getLastPathComponent()).key);
            }
        }
        List<ApiGroup> allGroups = engine.groups();
        List<ApiGroup> groups = focusPath == null ? allGroups
                : allGroups.stream().filter(g -> matchesFocus(g.branch())).toList();
        List<Branch> branches = engine.branches();
        Map<String, int[]> counts = Audit.prefixCounts(groups);
        Map<String, Integer> pend = new HashMap<>();
        for (ApiGroup g : groups) {
            if (g.pending()) {
                List<String> parts = Hierarchy.split(g.branch());
                for (int i = 1; i <= parts.size(); i++) {
                    pend.merge(Hierarchy.key(parts.subList(0, i)), 1, Integer::sum);
                }
            }
        }
        List<themythofcrawl.core.ProxyInterceptQueue.PendingRequest> heldPending = engine.proxyQueue().pending();
        Set<String> heldFolderKeys = new HashSet<>();
        for (themythofcrawl.core.ProxyInterceptQueue.PendingRequest pr : heldPending) {
            String bPath = Hierarchy.normalize(pr.branchName());
            if (!matchesFocus(bPath)) {
                continue;
            }
            List<String> parts = Hierarchy.split(bPath);
            for (int i = 1; i <= parts.size(); i++) {
                String k = Hierarchy.key(parts.subList(0, i));
                pend.merge(k, 1, Integer::sum);
                heldFolderKeys.add("F" + k);
            }
        }
        Map<String, List<PlacementBook.Record>> ghosts = new LinkedHashMap<>();
        Map<String, Integer> ghostCount = new HashMap<>();
        if (showGhosts) {
            for (PlacementBook.Record r : engine.book().records()) {
                if (r.gid == null && r.place != null && matchesFocus(r.place)) {
                    ghosts.computeIfAbsent(Hierarchy.key(r.place), k -> new ArrayList<>()).add(r);
                    List<String> parts = Hierarchy.split(r.place);
                    for (int i = 1; i <= parts.size(); i++) {
                        ghostCount.merge(Hierarchy.key(parts.subList(0, i)), 1, Integer::sum);
                    }
                }
            }
        }
        String heldNote = heldPending.isEmpty() ? "" : " · " + heldPending.size() + " chờ duyệt";
        Node.FolderMeta rootMeta = focusPath == null
                ? new Node.FolderMeta("Tất cả nhánh & API", null,
                        branches.size() + " nhánh · " + groups.size() + " API" + heldNote, null, null, null)
                : new Node.FolderMeta("Tập trung: " + focusPath, null,
                        groups.size() + " API" + heldNote + (focusRecursive ? " (kèm nhánh con)" : " (chỉ nhánh này)"),
                        null, null, null);
        Node root = new Node(Node.Kind.ROOT, focusPath == null ? "" : focusPath,
                focusPath == null
                        ? "Tất cả nhánh & API (" + branches.size() + " Nhánh | " + groups.size() + " API" + heldNote + ")"
                        : "Tập trung: " + focusPath + " (" + groups.size() + " API" + heldNote + ")",
                AuditLevel.NONE, "ROOT", 0, rootMeta);
        Map<Object, Node> nodes = new HashMap<>();
        nodes.put(root.key, root);
        Set<Node> open = new LinkedHashSet<>();
        open.add(root);
        List<Node> expand = new ArrayList<>();
        Map<Node, Boolean> reachable = new HashMap<>();
        reachable.put(root, true);
        Consumer<String> ensure = path -> ensureFolder(root, Hierarchy.split(path), nodes, counts, pend, ghostCount,
                heldFolderKeys, reachable, expand);
        Set<String> paths = new LinkedHashSet<>();
        if (focusPath == null) {
            for (Branch b : branches) {
                paths.add(Hierarchy.normalize(b.name()));
            }
            for (ApiGroup g : groups) {
                paths.add(Hierarchy.normalize(g.branch()));
            }
            for (themythofcrawl.core.ProxyInterceptQueue.PendingRequest pr : heldPending) {
                paths.add(Hierarchy.normalize(pr.branchName()));
            }
        } else {
            paths.add(focusPath);
            if (focusRecursive) {
                for (Branch b : branches) {
                    String bn = Hierarchy.normalize(b.name());
                    if (Hierarchy.matchesPrefix(bn, focusPath)) {
                        paths.add(bn);
                    }
                }
                for (ApiGroup g : groups) {
                    paths.add(Hierarchy.normalize(g.branch()));
                }
                for (themythofcrawl.core.ProxyInterceptQueue.PendingRequest pr : heldPending) {
                    String bn = Hierarchy.normalize(pr.branchName());
                    if (matchesFocus(bn)) {
                        paths.add(bn);
                    }
                }
            }
        }
        paths.forEach(ensure);
        // A folder's own live HELD_PENDING items come FIRST, then regular APIs (and Visual Clusters), then ghosts, then sub-folders.
        Map<Node, List<Node>> ownApis = new LinkedHashMap<>();
        Map<Node, Node> ghostRows = new HashMap<>();
        for (themythofcrawl.core.ProxyInterceptQueue.PendingRequest pr : heldPending) {
            String bPath = Hierarchy.normalize(pr.branchName());
            if (!matchesFocus(bPath)) {
                continue;
            }
            Node leaf = nodes.get("F" + Hierarchy.key(bPath));
            if (leaf == null) {
                continue;
            }
            String hostLbl = pr.service() != null ? pr.service().hostLabel() : "";
            Node hn = new Node(Node.Kind.HELD_PENDING, pr,
                    "[Chờ duyệt] " + pr.method() + " " + Text.shortPath(pr.path(), 48)
                            + (hostLbl.isEmpty() ? "" : " · " + hostLbl),
                    AuditLevel.WARN, "H" + pr.id(), 0);
            nodes.put(hn.key, hn);
            ownApis.computeIfAbsent(leaf, k -> new ArrayList<>()).add(hn);
        }
        for (ApiGroup g : showApis ? groups : List.<ApiGroup>of()) {
            Node leaf = nodes.get("F" + Hierarchy.key(g.branch()));
            if (leaf == null) {
                continue;
            }
            Node n = new Node(Node.Kind.GROUP, g, Text.apiLabel(g.gid()) + " " + g.method() + " " + Text.shortPath(g.path(), 48),
                    Audit.display(g), "G" + g.gid(), 0);
            nodes.put(n.key, n);
            ownApis.computeIfAbsent(leaf, k -> new ArrayList<>()).add(n);
        }
        ghosts.forEach((fkey, recs) -> {
            Node folder = nodes.get("F" + fkey);
            if (folder == null) {
                return;
            }
            Node gn = new Node(Node.Kind.GHOSTS, recs, recs.size()
                    + " API đã xóa từng nằm ở đây (giữ chỗ - bắt lại sẽ về đây)", AuditLevel.NONE, "GH" + fkey, 0);
            nodes.put(gn.key, gn);
            ghostRows.put(folder, gn);
        });
        layoutFolders(nodes, ownApis, ghostRows, heldFolderKeys, reachable, expand);
        restoring = true;
        try {
            model.setRoot(root);
            for (Node n : expand) {
                tree.expandPath(new TreePath(n.getPath()));
            }
            List<TreePath> again = new ArrayList<>();
            for (Object k : selected) {
                Node n = nodes.get(k);
                if (n != null) {
                    again.add(new TreePath(n.getPath()));
                }
            }
            if (!again.isEmpty()) {
                tree.setSelectionPaths(again.toArray(new TreePath[0]));
            }
        } finally {
            restoring = false;
        }
    }

    /** Expands ancestors and scrolls the live {@code HELD_PENDING} node into view without stealing focus. */
    public boolean revealHeldNode(long heldId) {
        if (!(model.getRoot() instanceof Node root)) {
            return false;
        }
        Node found = findByKey(root, "H" + heldId);
        if (found == null) {
            return false;
        }
        TreePath path = new TreePath(found.getPath());
        if (path.getParentPath() != null) {
            tree.expandPath(path.getParentPath());
        }
        tree.scrollPathToVisible(path);
        return true;
    }

    private static Node findByKey(Node parent, Object key) {
        if (key.equals(parent.key)) {
            return parent;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) instanceof Node ch) {
                Node hit = findByKey(ch, key);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private Node ensureFolder(Node root, List<String> parts, Map<Object, Node> nodes, Map<String, int[]> counts,
                              Map<String, Integer> pend, Map<String, Integer> ghostCount, Set<String> heldFolderKeys,
                              Map<Node, Boolean> reachable, List<Node> expand) {
        Node parent = root;
        for (int i = 0; i < parts.size(); i++) {
            String k = Hierarchy.key(parts.subList(0, i + 1));
            Node n = nodes.get("F" + k);
            if (n == null) {
                int[] c = counts.getOrDefault(k, new int[3]);
                Audit.Verdict v = Audit.fromCounts(c[0], c[1], c[2]);
                Integer pCount = pend.get(k);
                Integer gCount = ghostCount.get(k);
                String name = parts.get(i);
                Node.FolderMeta meta = new Node.FolderMeta(name, "Cấp " + (i + 1), c[0] + " API", v.summary(),
                        pCount != null && pCount > 0 ? pCount + " chờ" : null,
                        gCount != null && gCount > 0 ? gCount + " đã xóa" : null);
                n = new Node(Node.Kind.FOLDER, Hierarchy.join(parts.subList(0, i + 1)),
                        name + " · " + c[0] + " API · " + v.summary(), v.level(), "F" + k, i + 1, meta);
                nodes.put(n.key, n);
                parent.add(n);
                boolean hasHeld = heldFolderKeys.contains(n.key);
                if (hasHeld) {
                    collapsed.remove(n.key);
                }
                boolean visible = Boolean.TRUE.equals(reachable.get(parent))
                        && (focusPath != null || hasHeld || !collapsed.contains(n.key));
                reachable.put(n, visible);
                if (visible) {
                    expand.add(n);
                }
            }
            parent = n;
        }
        return parent;
    }

    /** Key of the APIS bucket of a folder key ("F..." -> "A..."). */
    static String bucketKey(Object folderKey) {
        return "A" + String.valueOf(folderKey).substring(1);
    }

    /**
     * Re-orders every folder's children: the folder's own APIs (and Visual Clusters) first — wrapped in a
     * collapsible {@link Node.Kind#APIS} bucket when the folder also has sub-folders — then ghosts, then sub-folders.
     */
    private void layoutFolders(Map<Object, Node> nodes, Map<Node, List<Node>> ownApis, Map<Node, Node> ghostRows,
                               Set<String> heldFolderKeys, Map<Node, Boolean> reachable, List<Node> expand) {
        List<Node> folders = new ArrayList<>();
        for (Node n : nodes.values()) {
            if (n.kind == Node.Kind.FOLDER) {
                folders.add(n);
            }
        }
        for (Node folder : folders) {
            List<Node> apis = ownApis.getOrDefault(folder, List.of());
            Node ghostRow = ghostRows.get(folder);
            List<Node> subfolders = new ArrayList<>();
            for (int i = 0; i < folder.getChildCount(); i++) {
                subfolders.add((Node) folder.getChildAt(i));
            }
            folder.removeAllChildren();
            boolean folderOpen = Boolean.TRUE.equals(reachable.get(folder));
            Node apiParent = folder;
            boolean apisOpen = folderOpen;
            if (!apis.isEmpty() && !subfolders.isEmpty()) {
                Node bucket = apisBucket(folder, apis);
                nodes.put(bucket.key, bucket);
                folder.add(bucket);
                boolean hasDirectHeld = apis.stream().anyMatch(x -> x.kind == Node.Kind.HELD_PENDING);
                if (hasDirectHeld) {
                    collapsed.remove(bucket.key);
                }
                apisOpen = folderOpen && (hasDirectHeld || !collapsed.contains(bucket.key));
                reachable.put(bucket, apisOpen);
                if (apisOpen) {
                    expand.add(bucket);
                }
                apiParent = bucket;
            }

            // Group visual clusters inside this branch's API area (never modifying branch paths)
            String branchPath = folder.payload instanceof String s ? s : "";
            List<themythofcrawl.model.GroupCluster> branchClusters = engine.clustersInBranch(branchPath);
            Set<Integer> clusteredGids = new HashSet<>();
            if (!branchClusters.isEmpty() && !apis.isEmpty()) {
                Map<Integer, Node> byGid = new LinkedHashMap<>();
                for (Node n : apis) {
                    if (n.payload instanceof ApiGroup g) {
                        byGid.put(g.gid(), n);
                    }
                }
                for (themythofcrawl.model.GroupCluster cl : branchClusters) {
                    List<Node> members = new ArrayList<>();
                    for (int gid : cl.gids()) {
                        Node member = byGid.get(gid);
                        if (member != null && clusteredGids.add(gid)) {
                            members.add(member);
                        }
                    }
                    if (!members.isEmpty()) {
                        Node clNode = clusterNode(folder, cl, members);
                        nodes.put(clNode.key, clNode);
                        apiParent.add(clNode);
                        boolean clOpen = apisOpen && !cl.collapsed();
                        reachable.put(clNode, clOpen);
                        if (clOpen) {
                            expand.add(clNode);
                        }
                        for (Node m : members) {
                            if (clOpen && expanded.contains(m.key)) {
                                fillGroup(m, nodes);
                                expand.add(m);
                            } else {
                                m.lazy();
                            }
                            clNode.add(m);
                        }
                    }
                }
            }

            for (Node n : apis) {
                if (n.payload instanceof ApiGroup g && clusteredGids.contains(g.gid())) {
                    continue;
                }
                if (n.kind == Node.Kind.GROUP) {
                    if (apisOpen && expanded.contains(n.key)) {
                        fillGroup(n, nodes);
                        expand.add(n);
                    } else {
                        n.lazy();
                    }
                }
                apiParent.add(n);
            }
            if (ghostRow != null) {
                if (folderOpen && expanded.contains(ghostRow.key)) {
                    fillGhosts(ghostRow, nodes);
                    expand.add(ghostRow);
                } else {
                    ghostRow.lazy();
                }
                folder.add(ghostRow);
            }
            for (Node sub : subfolders) {
                folder.add(sub);
            }
        }
    }

    private static Node clusterNode(Node folder, themythofcrawl.model.GroupCluster cl, List<Node> members) {
        List<ApiGroup> gs = new ArrayList<>(members.size());
        int pending = 0;
        for (Node n : members) {
            ApiGroup g = (ApiGroup) n.payload;
            gs.add(g);
            if (g.pending()) {
                pending++;
            }
        }
        Audit.Verdict v = Audit.branch(gs);
        String count = gs.size() + " API";
        Node.FolderMeta meta = new Node.FolderMeta(cl.name(), "Nhóm", count, v.summary(),
                pending > 0 ? pending + " chờ" : null, null);
        return new Node(Node.Kind.CLUSTER, cl, "[Nhóm] " + cl.name() + " · " + count, v.level(),
                "C" + cl.id(), folder.depth, meta);
    }

    private static Node apisBucket(Node folder, List<Node> apis) {
        List<ApiGroup> gs = new ArrayList<>(apis.size());
        int pending = 0;
        int held = 0;
        for (Node n : apis) {
            if (n.payload instanceof ApiGroup g) {
                gs.add(g);
                if (g.pending()) {
                    pending++;
                }
            } else if (n.kind == Node.Kind.HELD_PENDING) {
                held++;
                pending++;
            }
        }
        Audit.Verdict v = Audit.branch(gs);
        String count = gs.size() + " API" + (held > 0 ? " · " + held + " chờ duyệt" : "");
        Node.FolderMeta meta = new Node.FolderMeta("API của nhánh", null, count, v.summary(),
                pending > 0 ? pending + " chờ" : null, null);
        return new Node(Node.Kind.APIS, folder.payload, "API của nhánh · " + count + " · " + v.summary(), v.level(),
                bucketKey(folder.key), folder.depth, meta);
    }

    /** The APIs held by an {@link Node.Kind#APIS} bucket or {@link Node.Kind#CLUSTER} node. */
    public static List<ApiGroup> directGroups(Node bucket) {
        List<ApiGroup> out = new ArrayList<>();
        if (bucket != null) {
            for (int i = 0; i < bucket.getChildCount(); i++) {
                if (bucket.getChildAt(i) instanceof Node c) {
                    if (c.payload instanceof ApiGroup g) {
                        out.add(g);
                    } else if (c.kind == Node.Kind.CLUSTER) {
                        out.addAll(directGroups(c));
                    }
                }
            }
        }
        return out;
    }

    private void loadChildren(Node n) {
        if (n.kind == Node.Kind.GROUP) {
            fillGroup(n, null);
        } else if (n.kind == Node.Kind.GHOSTS) {
            fillGhosts(n, null);
        }
        model.nodeStructureChanged(n);
    }

    @SuppressWarnings("unchecked")
    private void fillGhosts(Node n, Map<Object, Node> nodes) {
        n.removeAllChildren();
        List<PlacementBook.Record> recs = new ArrayList<>((List<PlacementBook.Record>) n.payload);
        recs.sort(Comparator.comparing((PlacementBook.Record r) -> Text.nz(r.path)).thenComparing(r -> Text.nz(r.method)));
        for (PlacementBook.Record r : recs) {
            String label = r.method + " " + Text.shortPath(Text.nz(r.path), 60) + "   ·   xóa " + r.deleted
                    + " lần (lần cuối " + (r.deletedAt == null ? "-" : r.deletedAt) + ")   ·   gặp " + r.seen + " lần"
                    + (r.lastFoundAt != null ? "   ·   lần cuối gặp tại " + r.lastFoundAt : "");
            Node c = new Node(Node.Kind.GHOST, r, label, AuditLevel.NONE, "GHO" + r.key, 0);
            n.add(c);
            if (nodes != null) {
                nodes.put(c.key, c);
            }
        }
        n.loaded = true;
    }

    private void fillGroup(Node n, Map<Object, Node> nodes) {
        ApiGroup g = (ApiGroup) n.payload;
        n.removeAllChildren();
        PlaceHint h = g.placeHint();
        List<Node> kids = new ArrayList<>();
        if (h != null) {
            kids.add(new Node(Node.Kind.PLACE, new Object[] {g, h.old(), "old"}, "Vị trí cũ (giữ chỗ): " + h.old()
                    + "   ·   đang đặt ở đây" + (h.oldMissing() ? " · nhánh cũ đã bị xóa nên được tạo lại" : ""),
                    AuditLevel.NONE, "P" + g.gid() + "old", 0));
            kids.add(new Node(Node.Kind.PLACE, new Object[] {g, h.found(), "found"}, "Vừa gặp tại: " + h.found()
                    + "   ·   chuột phải để chuyển sang đây", AuditLevel.NONE, "P" + g.gid() + "found", 0));
        }
        kids.add(new Node(Node.Kind.ORIG, new Object[] {g, g.origResult()}, resultLabel(g, g.origResult()),
                Audit.level(g, g.origResult()), "O" + g.gid(), 0));
        for (RoleResult r : g.results()) {
            kids.add(new Node(Node.Kind.ROLE, new Object[] {g, r}, resultLabel(g, r), Audit.level(g, r),
                    "R" + g.gid() + "/" + r.roleKey(), 0));
        }
        for (Node k : kids) {
            n.add(k);
            if (nodes != null) {
                nodes.put(k.key, k);
            }
        }
        n.loaded = true;
    }

    // ------------------------------------------------------------------ selection
    public Node selectedNode() {
        TreePath p = tree.getSelectionPath();
        return p == null ? null : (Node) p.getLastPathComponent();
    }

    public int selectionCount() {
        return tree.getSelectionCount();
    }

    /** The API of a GROUP / ORIG / ROLE / PLACE row. */
    public static themythofcrawl.core.ProxyInterceptQueue.PendingRequest heldOf(Node n) {
        if (n == null || n.kind != Node.Kind.HELD_PENDING) {
            return null;
        }
        return n.payload instanceof themythofcrawl.core.ProxyInterceptQueue.PendingRequest pr ? pr : null;
    }

    public static ApiGroup groupOf(Node n) {
        if (n == null) {
            return null;
        }
        if (n.payload instanceof ApiGroup g) {
            return g;
        }
        if (n.payload instanceof Object[] arr && arr.length > 0 && arr[0] instanceof ApiGroup g) {
            return g;
        }
        return null;
    }

    /** APIs of every selected row (a folder brings all its APIs; a cluster brings its member APIs). */
    public List<ApiGroup> selectedGroups() {
        Set<ApiGroup> out = new LinkedHashSet<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths != null) {
            for (TreePath p : paths) {
                Node n = (Node) p.getLastPathComponent();
                if (n.kind == Node.Kind.ROOT) {
                    if (focusPath == null) {
                        out.addAll(engine.groups());
                    } else {
                        for (ApiGroup g : engine.groups()) {
                            if (matchesFocus(g.branch())) {
                                out.add(g);
                            }
                        }
                    }
                } else if (n.kind == Node.Kind.FOLDER) {
                    out.addAll(engine.groupsUnder((String) n.payload));
                } else if (n.kind == Node.Kind.APIS || n.kind == Node.Kind.CLUSTER) {
                    out.addAll(directGroups(n));
                } else {
                    ApiGroup g = groupOf(n);
                    if (g != null) {
                        out.add(g);
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Live Proxy-held {@code [CHỜ DUYỆT]} requests matching the current tree selection
     * (direct HELD_PENDING rows, or all held requests inside selected FOLDER / APIS / ROOT nodes).
     */
    public List<themythofcrawl.core.ProxyInterceptQueue.PendingRequest> selectedHeld() {
        Map<Long, themythofcrawl.core.ProxyInterceptQueue.PendingRequest> out = new LinkedHashMap<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null || paths.length == 0) {
            return List.of();
        }
        List<themythofcrawl.core.ProxyInterceptQueue.PendingRequest> allPending = engine.proxyQueue().pending();
        if (allPending.isEmpty()) {
            return List.of();
        }
        for (TreePath p : paths) {
            Node n = (Node) p.getLastPathComponent();
            if (n.kind == Node.Kind.HELD_PENDING) {
                themythofcrawl.core.ProxyInterceptQueue.PendingRequest pr = heldOf(n);
                if (pr != null) {
                    out.put(pr.id(), pr);
                }
            } else if (n.kind == Node.Kind.ROOT) {
                for (var pr : allPending) {
                    if (matchesFocus(Hierarchy.normalize(pr.branchName()))) {
                        out.put(pr.id(), pr);
                    }
                }
            } else if (n.kind == Node.Kind.FOLDER && n.payload instanceof String prefix) {
                String normPref = Hierarchy.normalize(prefix);
                for (var pr : allPending) {
                    String bn = Hierarchy.normalize(pr.branchName());
                    if (Hierarchy.matchesPrefix(bn, normPref)) {
                        out.put(pr.id(), pr);
                    }
                }
            } else if (n.kind == Node.Kind.APIS && n.payload instanceof String exact) {
                String normExact = Hierarchy.normalize(exact);
                for (var pr : allPending) {
                    if (Hierarchy.same(Hierarchy.normalize(pr.branchName()), normExact)) {
                        out.put(pr.id(), pr);
                    }
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Branch of the selected row (a folder / its API bucket / cluster, held request, or the branch of the selected API). */
    public String selectedBranch() {
        Node n = selectedNode();
        if (n == null) {
            return null;
        }
        if (n.kind == Node.Kind.FOLDER || n.kind == Node.Kind.APIS) {
            return (String) n.payload;
        }
        if (n.kind == Node.Kind.HELD_PENDING) {
            var pr = heldOf(n);
            return pr != null ? pr.branchName() : null;
        }
        if (n.kind == Node.Kind.CLUSTER && n.payload instanceof themythofcrawl.model.GroupCluster cl) {
            return cl.branchPath();
        }
        ApiGroup g = groupOf(n);
        return g != null ? g.branch() : null;
    }

    public void selectGroup(ApiGroup g) {
        Node root = (Node) model.getRoot();
        for (java.util.Enumeration<?> en = root.depthFirstEnumeration(); en.hasMoreElements(); ) {
            Node n = (Node) en.nextElement();
            if (n.kind == Node.Kind.GROUP && n.payload == g) {
                TreePath p = new TreePath(n.getPath());
                tree.setSelectionPath(p);
                tree.scrollPathToVisible(p);
                return;
            }
        }
    }

    /** Selects and scrolls to a folder node matching {@code branchPath}, returning true if found. */
    public boolean selectBranchPath(String branchPath) {
        if (branchPath == null || branchPath.isBlank()) {
            return false;
        }
        String want = "F" + Hierarchy.key(branchPath);
        Node root = (Node) model.getRoot();
        for (java.util.Enumeration<?> en = root.depthFirstEnumeration(); en.hasMoreElements(); ) {
            Node n = (Node) en.nextElement();
            if (n.kind == Node.Kind.FOLDER && want.equals(n.key)) {
                TreePath p = new TreePath(n.getPath());
                tree.setSelectionPath(p);
                tree.scrollPathToVisible(p);
                return true;
            }
        }
        return false;
    }

    /** Open (true) or close every folder (and every "API của nhánh" bucket and cluster). */
    public void expandFolders(boolean open) {
        if (open) {
            collapsed.clear();
            for (int i = 0; i < tree.getRowCount(); i++) {
                Node n = (Node) tree.getPathForRow(i).getLastPathComponent();
                if (n.kind == Node.Kind.ROOT || n.kind == Node.Kind.FOLDER
                        || n.kind == Node.Kind.APIS || n.kind == Node.Kind.CLUSTER) {
                    tree.expandRow(i);
                }
            }
        } else {
            expanded.clear();
            for (int i = tree.getRowCount() - 1; i > 0; i--) {
                tree.collapseRow(i);
            }
        }
    }

    // ------------------------------------------------------------------ chips
    /** {Chip, ApiGroup} under the mouse on an API row, else null. */
    public Object[] chipAt(int x, int y) {
        TreePath path = tree.getPathForLocation(x, y);
        if (path == null || !(path.getLastPathComponent() instanceof Node n) || n.kind != Node.Kind.GROUP) {
            return null;
        }
        ApiGroup g = (ApiGroup) n.payload;
        Rectangle b = tree.getPathBounds(path);
        if (b == null) {
            return null;
        }
        if (tree.getCellRenderer() instanceof TreeRenderer r) {
            Chip c = r.hitTestChip(tree, n, tree.getRowForPath(path), x - b.x);
            return c != null ? new Object[] {c, g} : null;
        }
        return null;
    }

    public static Collection<ApiGroup> none() {
        return List.of();
    }
}
