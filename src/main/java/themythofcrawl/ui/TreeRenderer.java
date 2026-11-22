package themythofcrawl.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.util.List;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.TreeCellRenderer;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Logbook;
import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ApiTagCatalog;
import themythofcrawl.model.GroupCluster;
import themythofcrawl.model.NodeTypeCatalog;
import themythofcrawl.model.RoleResult;
import themythofcrawl.place.Decisions;
import themythofcrawl.ui.kit.ThemedTree;
import themythofcrawl.ui.theme.Theme;

/**
 * Rich, HTML-free tree cell renderer drawing Java2D status markers, clickable chips (Copy, Excel,
 * Duplicate/Origin, Tags), NodeType icons, distinct dashed Visual Cluster rows, and dimmed path parameters.
 */
public final class TreeRenderer extends JComponent implements TreeCellRenderer, ThemedTree.ExtensionRenderer {
    private static final int ARC = 0;
    private static final Stroke DASHED_STROKE = new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            10.0f, new float[] {4.0f, 3.0f}, 0.0f);

    private static final class Chip {
        String text;
        Font font;
        Color fill;
        Color border;
        Color fg;
        Icon icon;
        boolean squareRadius;
        int x;
        int w;
        int textW;
    }

    private static final class NodeLayout {
        Theme theme;
        int gen;
        AuditLevel nodeLevel;
        String summaryRef;
        int resCount;
        boolean pendingFlag;
        int dupTotal;
        int dupNo;
        String targetStt;
        boolean heldFlag;
        String actionNoteRef;
        int tagCount;
        boolean suspectedRefresh;
        String nodeTypeSig;
        boolean originFlag;

        int totalW;
        int totalH;
        Color fill;
        Color edge;
        boolean dashedEdge;

        Icon leadIcon;
        int leadX;
        int leadW;

        Icon copyIcon;
        int copyX0 = -1;
        int copyX1 = -1;

        Icon pinIcon;
        int pinX = -1;

        Icon midIcon;
        int midX = -1;

        String primaryText;
        Font primaryFont;
        Color primaryColor;
        int primaryX;

        char[] monoPath;
        int monoPathLen;
        Font monoFont;
        int monoPathX;

        String statusText;
        Font statusFont;
        Color statusColor;
        int statusX;

        String tailText;
        Font tailFont;
        Color tailColor;
        int tailX;

        // V_7.0 tree-table grid
        boolean grid;
        int colGen;
        int estNodeX;
        int alignFrom = -1;
        Color[] roleColors;
        int roleCount;
        int roleX = -1;

        Chip[] chips;
        int chipCount;
        int excelX0 = -1;
        int excelX1 = -1;
        int dupX0 = -1;
        int dupX1 = -1;

        void resetChips() {
            if (chips == null) {
                chips = new Chip[12];
                for (int i = 0; i < chips.length; i++) {
                    chips[i] = new Chip();
                }
            }
            chipCount = 0;
            excelX0 = -1;
            excelX1 = -1;
            dupX0 = -1;
            dupX1 = -1;
            copyX0 = -1;
            copyX1 = -1;
            copyIcon = null;
            pinIcon = null;
            pinX = -1;
            midIcon = null;
            midX = -1;
            leadIcon = null;
            primaryText = null;
            monoPathLen = 0;
            statusText = null;
            tailText = null;
            fill = null;
            edge = null;
            dashedEdge = false;
            grid = false;
            alignFrom = -1;
            roleCount = 0;
            roleX = -1;
        }
    }

    private final Engine engine;
    private NodeLayout active;
    private boolean activeSelected;
    private Theme activeTheme;
    private String currentText = "";
    private Icon currentIcon;
    private final char[] plainScratch = new char[256];

    public TreeRenderer() {
        this(null);
    }

    public TreeRenderer(Engine engine) {
        this.engine = engine;
        setOpaque(false);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setOpaque(false);
    }

    public String getText() {
        return currentText;
    }

    public Icon getIcon() {
        return currentIcon;
    }

    /** Exact hit-test matching the painted Copy / Excel / Dup regions of a GROUP node. */
    public BranchTree.Chip hitTestChip(JTree tree, Node node, int row, int relX) {
        if (tree == null || node == null || node.kind != Node.Kind.GROUP) {
            return null;
        }
        Theme th = treeTheme(tree);
        NodeLayout nl = ensureLayout(tree, node, th);
        if (nl.copyX0 >= 0 && relX >= nl.copyX0 - 2 && relX <= nl.copyX1 + 2) {
            return BranchTree.Chip.COPY;
        }
        if (nl.grid && nl.alignFrom >= 0) {
            java.awt.Rectangle b = tree.getRowBounds(row);
            if (b != null) {
                relX -= nl.estNodeX - b.x;
            }
        }
        if (nl.excelX0 >= 0 && relX >= nl.excelX0 - 2 && relX <= nl.excelX1 + 2) {
            return BranchTree.Chip.EXCEL;
        }
        if (nl.dupX0 >= 0 && relX >= nl.dupX0 - 2 && relX <= nl.dupX1 + 2) {
            return BranchTree.Chip.DUP;
        }
        return null;
    }

    private static Theme treeTheme(JTree tree) {
        Color bg = tree != null && tree.getBackground() != null ? tree.getBackground() : UIManager.getColor("Tree.background");
        Color fgc = UIManager.getColor("Tree.textForeground") != null ? UIManager.getColor("Tree.textForeground")
                : (tree != null ? tree.getForeground() : null);
        return Theme.of(bg, fgc);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf,
                                                  int row, boolean hasFocus) {
        Theme th = treeTheme(tree);
        this.activeTheme = th;
        this.activeSelected = selected;
        if (!(value instanceof Node n)) {
            this.active = null;
            this.currentText = value == null ? "" : String.valueOf(value);
            this.currentIcon = null;
            return this;
        }
        NodeLayout nl = ensureLayout(tree, n, th);
        this.active = nl;
        this.currentText = nl.primaryText != null ? nl.primaryText : Icons.stripLeadingSymbol(n.label);
        this.currentIcon = nl.leadIcon != null ? nl.leadIcon : nl.midIcon;
        return this;
    }

    private NodeLayout ensureLayout(JTree tree, Node n, Theme th) {
        int gen = Theme.generation();
        boolean grid = gridKind(n) && TreeColumns.enabled(tree);
        int est = grid ? TreeColumns.nodeX(tree, n.getLevel()) : 0;
        if (n.renderCache instanceof NodeLayout cached && cached.theme == th && cached.gen == gen
                && n.renderStamp == gen && cached.nodeLevel == n.level && !groupChanged(n, cached)
                && cached.grid == grid && (!grid || (cached.colGen == TreeColumns.generation() && cached.estNodeX == est))) {
            return cached;
        }
        NodeLayout nl = n.renderCache instanceof NodeLayout existing ? existing : new NodeLayout();
        buildLayout(tree, n, th, nl, gen);
        n.renderCache = nl;
        n.renderStamp = gen;
        return nl;
    }

    private static boolean gridKind(Node n) {
        return n.kind == Node.Kind.GROUP || n.kind == Node.Kind.ROLE || n.kind == Node.Kind.ORIG
                || n.kind == Node.Kind.HELD_PENDING;
    }

    private boolean groupChanged(Node n, NodeLayout nl) {
        if (n.payload instanceof ApiGroup g) {
            String stt = g.target() != null ? g.target().sttText() : null;
            boolean orig = engine != null ? engine.isOrigin(g) : g.dupNo() == 1;
            return g.auditSummary() != nl.summaryRef || g.results().size() != nl.resCount
                    || g.pending() != nl.pendingFlag || g.dupTotal() != nl.dupTotal || g.dupNo() != nl.dupNo
                    || g.heldAtProxy() != nl.heldFlag
                    || g.tags().size() != nl.tagCount || g.suspectedRefresh() != nl.suspectedRefresh
                    || orig != nl.originFlag
                    || !java.util.Objects.equals(g.activeIdNote(), nl.actionNoteRef)
                    || !java.util.Objects.equals(stt, nl.targetStt);
        }
        if (n.kind == Node.Kind.FOLDER && engine != null && n.payload instanceof String path) {
            NodeTypeCatalog.NodeType nt = engine.nodeTypeOf(path);
            String sig = nt.id() + "|" + nt.iconKind() + "|" + nt.colorKey() + "|" + nt.monogram() + "|" + nt.imagePath();
            return !sig.equals(nl.nodeTypeSig);
        }
        return false;
    }

    private void buildLayout(JTree tree, Node n, Theme th, NodeLayout nl, int gen) {
        nl.resetChips();
        nl.theme = th;
        nl.gen = gen;
        nl.nodeLevel = n.level;
        if (n.payload instanceof ApiGroup g) {
            nl.summaryRef = g.auditSummary();
            nl.resCount = g.results().size();
            nl.pendingFlag = g.pending();
            nl.dupTotal = g.dupTotal();
            nl.dupNo = g.dupNo();
            nl.heldFlag = g.heldAtProxy();
            nl.actionNoteRef = g.activeIdNote();
            nl.targetStt = g.target() != null ? g.target().sttText() : null;
            nl.tagCount = g.tags().size();
            nl.suspectedRefresh = g.suspectedRefresh();
            nl.originFlag = engine != null ? engine.isOrigin(g) : g.dupNo() == 1;
        }
        if (n.kind == Node.Kind.FOLDER && engine != null && n.payload instanceof String path) {
            NodeTypeCatalog.NodeType nt = engine.nodeTypeOf(path);
            nl.nodeTypeSig = nt.id() + "|" + nt.iconKind() + "|" + nt.colorKey() + "|" + nt.monogram() + "|" + nt.imagePath();
        } else {
            nl.nodeTypeSig = null;
        }

        nl.grid = gridKind(n) && TreeColumns.enabled(tree);
        nl.colGen = TreeColumns.generation();
        nl.estNodeX = nl.grid ? TreeColumns.nodeX(tree, n.getLevel()) : 0;
        int rowH = Math.max(Theme.ROW_TREE, tree != null && tree.getRowHeight() > 0 ? tree.getRowHeight() : th.scaled(Theme.ROW_TREE));
        int padL = th.scaled(6);
        int padR = th.scaled(8);
        int gap = th.scaled(6);
        int x = padL;

        switch (n.kind) {
            case FOLDER, LOGBRANCH -> x = layoutFolder(n, th, nl, x, gap);
            case ROOT -> x = layoutRoot(n, th, nl, x, gap);
            case APIS -> x = layoutApis(n, th, nl, x, gap);
            case CLUSTER -> x = layoutCluster(n, th, nl, x, gap);
            case HELD_PENDING -> x = nl.grid ? layoutHeldGrid(n, th, nl, x, gap) : layoutHeldPending(n, th, nl, x, gap);
            case LOGSYS -> x = layoutLogSys(n, th, nl, x, gap);
            case GROUP -> x = nl.grid ? layoutGroupGrid(n, th, nl, x, gap) : layoutGroup(n, th, nl, x, gap);
            case LOGAPI -> x = layoutLogApi(n, th, nl, x, gap);
            case ORIG, ROLE -> x = nl.grid ? layoutRoleGrid(n, th, nl, x, gap) : layoutRole(n, th, nl, x, gap);
            case GHOST, GHOSTS -> x = layoutGhost(n, th, nl, x, gap);
            case PLACE -> x = layoutPlace(n, th, nl, x, gap);
            case LOGLINE -> x = layoutLogLine(n, th, nl, x, gap);
            default -> x = layoutSimple(n, th, nl, x, gap);
        }

        nl.totalW = Math.max(th.scaled(40), x + padR);
        nl.totalH = rowH;
    }

    private int addLeadIcon(NodeLayout nl, Icon ic, int x, int gapAfter) {
        if (ic == null) {
            return x;
        }
        nl.leadIcon = ic;
        nl.leadX = x;
        nl.leadW = ic.getIconWidth();
        return x + nl.leadW + gapAfter;
    }

    private int addPrimary(NodeLayout nl, String text, Font f, Color c, int x, int gapAfter) {
        if (text == null || text.isEmpty()) {
            return x;
        }
        nl.primaryText = text;
        nl.primaryFont = f;
        nl.primaryColor = c;
        nl.primaryX = x;
        int w = getFontMetrics(f).stringWidth(text);
        return x + w + gapAfter;
    }

    private int addChip(Theme th, NodeLayout nl, String text, Font f, Color fill, Color border, Color fg, Icon ic,
                        boolean squareRadius, int x, int gapAfter) {
        if ((text == null || text.isEmpty()) && ic == null) {
            return x;
        }
        if (nl.chipCount >= nl.chips.length) {
            return x;
        }
        Chip ch = nl.chips[nl.chipCount++];
        ch.text = text == null ? "" : text;
        ch.font = f;
        ch.fill = fill;
        ch.border = border;
        ch.fg = fg;
        ch.icon = ic;
        ch.squareRadius = squareRadius;
        int ip = th.scaled(6);
        int igap = ic != null && !ch.text.isEmpty() ? th.scaled(3) : 0;
        int icW = ic != null ? ic.getIconWidth() + igap : 0;
        int tw = ch.text.isEmpty() ? 0 : getFontMetrics(f).stringWidth(ch.text);
        ch.textW = tw;
        ch.x = x;
        ch.w = ip * 2 + icW + tw;
        return x + ch.w + gapAfter;
    }

    private int layoutFolder(Node n, Theme th, NodeLayout nl, int x, int gap) {
        Icon lead;
        if (engine != null && n.kind == Node.Kind.FOLDER && n.payload instanceof String path) {
            NodeTypeCatalog.NodeType nt = engine.nodeTypeOf(path);
            if (NodeTypeCatalog.DEFAULT_ID.equals(nt.id()) && nt.monogram().isEmpty() && nt.imagePath().isEmpty()
                    && "folder".equals(nt.iconKind())) {
                lead = Icons.folder(th.level(n.depth).base(), 14);
            } else {
                lead = Icons.nodeIcon(nt, th, 14);
            }
        } else {
            lead = Icons.folder(th.level(n.depth).base(), 14);
        }
        x = addLeadIcon(nl, lead, x, gap);

        Node.FolderMeta meta = n.folderMeta;
        if (meta == null && n.kind == Node.Kind.LOGBRANCH && n.payload instanceof List<?> gs) {
            @SuppressWarnings("unchecked")
            List<ApiGroup> groups = (List<ApiGroup>) gs;
            Audit.Verdict v = Audit.branch(groups);
            String path = groups.isEmpty() ? Icons.stripLeadingSymbol(n.label) : Hierarchy.leaf(groups.get(0).branch());
            meta = new Node.FolderMeta(path, "C" + Math.max(1, n.depth), groups.size() + " API", v.summary(), null, null);
        }

        if (meta != null) {
            x = addPrimary(nl, meta.name(), th.boldFont, th.fg, x, gap);
            if (meta.countChip() != null && !meta.countChip().isEmpty()) {
                x = addTail(nl, th, meta.countChip(), x, gap);
            }
            if (meta.verdictChip() != null && !meta.verdictChip().isEmpty() && !"-".equals(meta.verdictChip())) {
                Theme.Token at = th.audit(n.level);
                Icon ic = Icons.forAudit(n.level, at.text());
                x = addChip(th, nl, compactVerdict(meta.verdictChip()), th.smallBoldFont,
                        at.fill(), at.border(), at.text(), ic, true, x, th.scaled(4));
            }
            if (meta.pendingChip() != null && !meta.pendingChip().isEmpty()) {
                x = addChip(th, nl, meta.pendingChip(), th.smallBoldFont, th.pending.fill(), th.pending.border(),
                        th.pending.text(), Icons.hourglass(th.pending.text(), 11), true, x, th.scaled(4));
            }
            if (meta.ghostChip() != null && !meta.ghostChip().isEmpty()) {
                x = addChip(th, nl, meta.ghostChip(), th.smallBoldFont, th.neutral.fill(), th.border, th.muted,
                        Icons.ghost(th.muted, 11), true, x, th.scaled(4));
            }
        } else {
            x = addPrimary(nl, Icons.stripLeadingSymbol(n.label), th.boldFont, th.fg, x, th.scaled(4));
        }
        return x;
    }

    /** Visual Cluster row: distinct cluster glyph, [CỤM] rectangular badge, and dashed outline (never a folder). */
    private int layoutCluster(Node n, Theme th, NodeLayout nl, int x, int gap) {
        GroupCluster cl = n.payload instanceof GroupCluster c ? c : null;
        Theme.Token tok = Icons.tokenForKey(th, cl != null ? cl.colorKey() : "accent");
        nl.fill = tok.fill();
        nl.edge = tok.border();
        nl.dashedEdge = true;

        x = addLeadIcon(nl, Icons.cluster(tok.text(), 14), x, th.scaled(4));
        x = addChip(th, nl, "Cụm", th.smallBoldFont, tok.fillStrong(), tok.border(), tok.text(), null, true, x, gap);

        Node.FolderMeta m = n.folderMeta;
        String title = m != null ? m.name() : (cl != null ? cl.name() : Icons.stripLeadingSymbol(n.label));
        x = addPrimary(nl, title, th.boldFont, tok.text(), x, gap);
        if (m != null && m.countChip() != null && !m.countChip().isEmpty()) {
            x = addTail(nl, th, m.countChip(), x, gap);
        }
        if (m != null && m.verdictChip() != null && !m.verdictChip().isEmpty() && !"-".equals(m.verdictChip())) {
            Theme.Token at = th.audit(n.level);
            x = addChip(th, nl, compactVerdict(m.verdictChip()), th.smallBoldFont, at.fill(), at.border(),
                    at.text(), Icons.forAudit(n.level, at.text()), true, x, th.scaled(4));
        }
        if (m != null && m.pendingChip() != null && !m.pendingChip().isEmpty()) {
            x = addChip(th, nl, m.pendingChip(), th.smallBoldFont, th.pending.fill(), th.pending.border(),
                    th.pending.text(), Icons.hourglass(th.pending.text(), 11), true, x, th.scaled(4));
        }
        return x;
    }

    /**
     * Short verdict for tree rows: drops the "INFO:" / "OK:" / "WARN:" / "ERROR:" prefix (the chip icon
     * and colour already encode the level) and condenses verbose phrases so tree rows fit without clipping.
     */
    static String compactVerdict(String raw) {
        String s = Icons.stripLeadingSymbol(raw == null ? "" : raw).trim();
        if (s.startsWith("Chưa gửi")) {
            return "Chưa gửi";
        }
        return s.replace("Tài khoản crawl · ", "");
    }

    static String compactPending(ApiGroup g) {
        if (g.dupPending()) {
            return "Giữ lần " + g.dupNo() + "?";
        }
        return g.placeHint() != null ? "Về chỗ cũ?" : "Chờ xác nhận";
    }

    /** Muted plain-text counter after a name (e.g. "5 API"), Burp-style instead of a boxed chip. */
    private int addTail(NodeLayout nl, Theme th, String text, int x, int gap) {
        nl.tailText = text;
        nl.tailFont = th.smallFont;
        nl.tailColor = th.muted;
        nl.tailX = x;
        return x + getFontMetrics(th.smallFont).stringWidth(text) + gap;
    }

    private int layoutRoot(Node n, Theme th, NodeLayout nl, int x, int gap) {
        boolean focused = n.payload instanceof String s && !s.isEmpty();
        x = addLeadIcon(nl, focused ? Icons.focus(th.info.text(), 14) : Icons.folder(th.muted, 14), x, gap);
        Node.FolderMeta m = n.folderMeta;
        if (m != null) {
            x = addPrimary(nl, m.name(), th.boldFont, focused ? th.info.text() : th.fg, x, gap);
            if (m.countChip() != null && !m.countChip().isEmpty()) {
                x = addTail(nl, th, m.countChip(), x, gap);
            }
        } else {
            x = addPrimary(nl, Icons.stripLeadingSymbol(n.label), th.boldFont, th.fg, x, th.scaled(4));
        }
        return x;
    }

    /** "API của nhánh" bucket: flat row (no fill), API glyph, count + verdict + pending chips. */
    private int layoutApis(Node n, Theme th, NodeLayout nl, int x, int gap) {
        x = addLeadIcon(nl, Icons.api(th.muted, 12), x, gap);
        Node.FolderMeta m = n.folderMeta;
        if (m == null) {
            return addPrimary(nl, Icons.stripLeadingSymbol(n.label), th.boldFont, th.muted, x, th.scaled(4));
        }
        x = addPrimary(nl, m.name(), th.italicFont, th.muted, x, gap);
        if (m.countChip() != null && !m.countChip().isEmpty()) {
            x = addTail(nl, th, m.countChip(), x, gap);
        }
        if (m.verdictChip() != null && !m.verdictChip().isEmpty() && !"-".equals(m.verdictChip())) {
            Theme.Token at = th.audit(n.level);
            x = addChip(th, nl, compactVerdict(m.verdictChip()), th.smallBoldFont, at.fill(), at.border(),
                    at.text(), Icons.forAudit(n.level, at.text()), false, x, th.scaled(4));
        }
        if (m.pendingChip() != null && !m.pendingChip().isEmpty()) {
            x = addChip(th, nl, m.pendingChip(), th.smallBoldFont, th.pending.fill(), th.pending.border(),
                    th.pending.text(), Icons.hourglass(th.pending.text(), 11), false, x, th.scaled(4));
        }
        return x;
    }

    private int layoutLogSys(Node n, Theme th, NodeLayout nl, int x, int gap) {
        nl.fill = th.surfaceAlt;
        nl.edge = th.grid;
        x = addLeadIcon(nl, Icons.dot(th.dotNone, true), x, gap);
        x = addPrimary(nl, Icons.stripLeadingSymbol(n.label), th.boldFont, th.fg, x, th.scaled(4));
        return x;
    }

    /** Live Proxy-held request displayed directly inside its target Branch on the Tree (not yet sent to Server). */
    private int layoutHeldPending(Node n, Theme th, NodeLayout nl, int x, int gap) {
        if (!(n.payload instanceof themythofcrawl.core.ProxyInterceptQueue.PendingRequest pr)) {
            x = addLeadIcon(nl, Icons.strategyShield(th.danger.text(), 13), x, gap);
            return addPrimary(nl, n.label, th.boldFont, th.danger.text(), x, th.scaled(4));
        }
        nl.fill = th.danger.fill();
        nl.edge = th.danger.border();
        nl.dashedEdge = true;

        x = addLeadIcon(nl, Icons.strategyShield(th.danger.text(), 13), x, th.scaled(4));
        x = addChip(th, nl, "Chờ duyệt · Chưa gửi", th.smallBoldFont,
                th.danger.solidFill(), th.danger.border(), th.danger.onSolid(), null, true, x, gap);
        Theme.Token mt = th.method(pr.method());
        x = addChip(th, nl, pr.method(), th.smallBoldFont, mt.fill(), mt.border(), mt.text(), null, true, x, gap);

        String shortP = Text.shortPath(pr.path(), 48);
        if (!shortP.isEmpty()) {
            nl.monoPathX = x;
            nl.monoFont = th.monoBoldFont;
            nl.monoPath = shortP.toCharArray();
            nl.monoPathLen = nl.monoPath.length;
            x += getFontMetrics(th.monoBoldFont).charsWidth(nl.monoPath, 0, nl.monoPathLen) + gap;
        }
        String host = pr.service() != null ? pr.service().hostLabel() : "";
        String meta = (host.isEmpty() ? "" : host + " · ") + pr.timeHms();
        x = addTail(nl, th, meta, x, gap);
        return x;
    }

    // =================================================================== V_7.0 grid (tree-table) layouts
    /** Relative x of grid column {@code c} for this row; never moves left of {@code x}. */
    private static int col(Theme th, NodeLayout nl, TreeColumns.Col c, int x) {
        int rel = TreeColumns.start(th, c) - nl.estNodeX;
        if (nl.alignFrom < 0) {
            nl.alignFrom = Math.max(x, rel);
        }
        return Math.max(x, rel);
    }

    /** Right edge (relative) of the TREE column: primary text is clipped to stay inside it. */
    private static int treeColEnd(Theme th, NodeLayout nl) {
        return TreeColumns.start(th, TreeColumns.Col.METHOD) - nl.estNodeX - th.scaled(6);
    }

    private String clip(String text, Font f, int maxW) {
        if (text == null || maxW <= 0) {
            return "";
        }
        FontMetrics fm = getFontMetrics(f);
        if (fm.stringWidth(text) <= maxW) {
            return text;
        }
        String t = text;
        while (t.length() > 1 && fm.stringWidth(t + "...") > maxW) {
            t = t.substring(0, t.length() - 1);
        }
        return t.length() <= 1 ? "" : t + "...";
    }

    private void putPath(Theme th, NodeLayout nl, String path, Font f, int x, int colW) {
        if (path == null || path.isEmpty()) {
            return;
        }
        FontMetrics fm = getFontMetrics(f);
        int cw = Math.max(1, fm.charWidth('m'));
        int maxChars = Math.max(8, (colW - th.scaled(10)) / cw);
        String shortP = Text.shortPath(path, maxChars);
        nl.monoPathX = x;
        nl.monoFont = f;
        nl.monoPath = shortP.toCharArray();
        nl.monoPathLen = nl.monoPath.length;
    }

    private int layoutGroupGrid(Node n, Theme th, NodeLayout nl, int x, int gap) {
        if (!(n.payload instanceof ApiGroup g)) {
            return layoutGroup(n, th, nl, x, gap);
        }
        Color dotCol = n.level == AuditLevel.NONE ? th.dotNone : th.audit(n.level).base();
        x = addLeadIcon(nl, Icons.dot(dotCol, false), x, th.scaled(3));
        Icon cp = Icons.copy(th.muted);
        nl.copyIcon = cp;
        nl.copyX0 = x;
        nl.copyX1 = x + cp.getIconWidth();
        x = nl.copyX1 + gap;
        String label = clip(Text.apiLabel(g.gid()), th.boldFont, treeColEnd(th, nl) - x);
        x = addPrimary(nl, label, th.boldFont, th.fg, x, gap);

        x = col(th, nl, TreeColumns.Col.METHOD, x);
        Theme.Token mt = th.method(g.method());
        addChip(th, nl, g.method(), th.smallBoldFont, mt.fill(), mt.border(), mt.text(), null, true, x, gap);

        x = col(th, nl, TreeColumns.Col.PATH, x);
        putPath(th, nl, g.path(), th.monoFont, x, TreeColumns.scaledWidth(th, TreeColumns.Col.PATH));

        x = col(th, nl, TreeColumns.Col.STATUS, x);
        Integer st = g.origStatus();
        if (st != null) {
            nl.statusText = String.valueOf(st);
            nl.statusFont = th.boldFont;
            nl.statusColor = th.statusColor(st);
        } else {
            nl.statusText = g.heldAtProxy() ? "giữ" : "-";
            nl.statusFont = th.smallFont;
            nl.statusColor = th.muted;
        }
        nl.statusX = x;

        x = col(th, nl, TreeColumns.Col.LENGTH, x);
        nl.tailText = g.origLength() != null ? String.valueOf(g.origLength()) : "-";
        nl.tailFont = th.monoSmallFont;
        nl.tailColor = th.muted;
        nl.tailX = x;

        x = col(th, nl, TreeColumns.Col.ROLES, x);
        List<RoleResult> rs = g.results();
        int sq = Math.max(8, th.scaled(10));
        int sqGap = Math.max(2, th.scaled(3));
        int fit = Math.max(1, (TreeColumns.scaledWidth(th, TreeColumns.Col.ROLES) - th.scaled(10)) / (sq + sqGap));
        int count = Math.min(rs.size(), fit);
        if (nl.roleColors == null || nl.roleColors.length < count) {
            nl.roleColors = new Color[Math.max(count, 8)];
        }
        for (int i = 0; i < count; i++) {
            nl.roleColors[i] = RoleStripRenderer.colorOf(th, g, rs.get(i));
        }
        nl.roleCount = count;
        nl.roleX = x;

        x = col(th, nl, TreeColumns.Col.VERDICT, x);
        int vx = x;
        // Clickable Excel badge
        if (g.target() != null && !g.target().sttText().isBlank()) {
            nl.excelX0 = x;
            x = addChip(th, nl, "Excel " + g.target().sttText(), th.smallBoldFont, th.info.fill(), th.info.border(),
                    th.info.text(), Icons.excel(th.info.text(), 11), true, x, th.scaled(4));
            nl.excelX1 = x - th.scaled(4);
        }
        if (g.dupTotal() > 1) {
            nl.dupX0 = x;
            if (nl.originFlag) {
                x = addChip(th, nl, "Gốc · " + g.dupTotal() + " lần", th.smallBoldFont, th.orig.fillStrong(), th.orig.border(),
                        th.orig.text(), Icons.star(th.orig.text(), 11), true, x, th.scaled(4));
            } else {
                x = addChip(th, nl, "#" + g.dupNo() + "/" + g.dupTotal(), th.smallBoldFont, th.dup.fill(), th.dup.border(),
                        th.dup.text(), Icons.dup(th.dup.text(), 11), true, x, th.scaled(4));
            }
            nl.dupX1 = x - th.scaled(4);
        }
        if (g.pending()) {
            x = addChip(th, nl, compactPending(g), th.smallBoldFont, th.pending.fillStrong(), th.pending.border(),
                    th.pending.text(), Icons.hourglass(th.pending.text(), 11), true, x, th.scaled(4));
        }
        String summary = Audit.summary(g);
        if (summary != null && !summary.isEmpty()) {
            AuditLevel auditLvl = Audit.level(g);
            Theme.Token at = g.results().isEmpty() ? th.orig : th.audit(auditLvl);
            Icon ic = g.results().isEmpty() ? Icons.pin(at.text(), 11) : Icons.forAudit(auditLvl, at.text());
            x = addChip(th, nl, compactVerdict(summary), th.smallBoldFont, at.fill(), at.border(), at.text(), ic, true, x,
                    th.scaled(4));
        }
        if (g.activeIdNote() != null && !g.activeIdNote().isBlank()) {
            x = addChip(th, nl, Text.shortPath(g.activeIdNote(), 22), th.smallBoldFont,
                    th.accent.fill(), th.accent.border(), th.accent.text(), null, true, x, th.scaled(4));
        }
        int shownTags = 0;
        for (String tagId : g.tags()) {
            if (shownTags >= 2) {
                break;
            }
            ApiTagCatalog.TagDef def = engine != null ? engine.tagCatalog().byId(tagId) : null;
            Theme.Token ttok = Icons.tokenForKey(th, def != null ? def.colorKey() : "warn");
            x = addChip(th, nl, tagId, th.smallBoldFont, ttok.fill(), ttok.border(), ttok.text(),
                    Icons.tag(ttok.text(), 10), true, x, th.scaled(4));
            shownTags++;
        }
        return Math.max(x, vx + th.scaled(20));
    }

    /** V_8.2: which ID this row really used ("ID 91"), when it differs from the captured request. */
    private int addSentNoteChip(Theme th, NodeLayout nl, RoleResult r, int x) {
        String note = r.sentNote();
        if (note == null || note.isBlank() || r.status() == null) {
            return x;
        }
        int cut = note.indexOf(" · ");
        String shortNote = cut > 0 ? note.substring(0, cut) : note;
        Theme.Token tok = th.info;
        return addChip(th, nl, Text.shortPath(shortNote, 22), th.smallFont, tok.fill(), tok.border(), tok.text(),
                null, false, x, th.scaled(4));
    }

    private int layoutRoleGrid(Node n, Theme th, NodeLayout nl, int x, int gap) {
        boolean isOrig = n.kind == Node.Kind.ORIG;
        if (!(n.payload instanceof Object[] arr) || arr.length < 2 || !(arr[0] instanceof ApiGroup g)
                || !(arr[1] instanceof RoleResult r)) {
            return layoutRole(n, th, nl, x, gap);
        }
        Color dotCol = isOrig ? th.orig.base() : (n.level == AuditLevel.NONE ? th.dotNone : th.audit(n.level).base());
        x = addLeadIcon(nl, Icons.dot(dotCol, false), x, th.scaled(5));
        if (isOrig) {
            Icon pin = Icons.pin(th.orig.text(), 12);
            nl.pinIcon = pin;
            nl.pinX = x;
            x += pin.getIconWidth() + th.scaled(4);
        }
        Font pf = isOrig ? th.boldFont : th.baseFont;
        x = addPrimary(nl, clip(r.roleName(), pf, treeColEnd(th, nl) - x), pf, isOrig ? th.orig.text() : th.fg, x, gap);

        x = col(th, nl, TreeColumns.Col.STATUS, x);
        Integer stCode = r.status();
        String st = stCode != null ? String.valueOf(stCode) : Text.nz(r.state());
        nl.statusText = st.isEmpty() ? "-" : st;
        nl.statusFont = stCode != null ? th.boldFont : th.smallFont;
        nl.statusColor = stCode != null ? th.statusColor(stCode) : th.muted;
        nl.statusX = x;

        x = col(th, nl, TreeColumns.Col.LENGTH, x);
        nl.tailText = r.length() != null ? String.valueOf(r.length()) : "-";
        nl.tailFont = th.monoSmallFont;
        nl.tailColor = th.muted;
        nl.tailX = x;

        x = col(th, nl, TreeColumns.Col.ROLES, x);
        String badge = Audit.badge(g, r);
        if (badge != null && !badge.isEmpty()) {
            Theme.Token tok = isOrig ? th.orig : th.audit(n.level);
            Icon ic = isOrig ? Icons.pin(tok.text(), 11)
                    : (n.level != AuditLevel.NONE ? Icons.forAudit(n.level, tok.text()) : null);
            x = addChip(th, nl, Icons.stripLeadingSymbol(badge), th.smallBoldFont, tok.fill(), tok.border(), tok.text(),
                    ic, false, x, th.scaled(4));
        }
        return addSentNoteChip(th, nl, r, x);
    }

    private int layoutHeldGrid(Node n, Theme th, NodeLayout nl, int x, int gap) {
        if (!(n.payload instanceof themythofcrawl.core.ProxyInterceptQueue.PendingRequest pr)) {
            return layoutHeldPending(n, th, nl, x, gap);
        }
        nl.fill = th.danger.fill();
        nl.edge = th.danger.border();
        nl.dashedEdge = true;
        x = addLeadIcon(nl, Icons.strategyShield(th.danger.text(), 13), x, th.scaled(4));
        x = addPrimary(nl, clip("Chờ duyệt", th.boldFont, treeColEnd(th, nl) - x), th.boldFont, th.danger.text(), x, gap);
        x = col(th, nl, TreeColumns.Col.METHOD, x);
        Theme.Token mt = th.method(pr.method());
        addChip(th, nl, pr.method(), th.smallBoldFont, mt.fill(), mt.border(), mt.text(), null, true, x, gap);
        x = col(th, nl, TreeColumns.Col.PATH, x);
        putPath(th, nl, pr.path(), th.monoBoldFont, x, TreeColumns.scaledWidth(th, TreeColumns.Col.PATH));
        x = col(th, nl, TreeColumns.Col.STATUS, x);
        nl.statusText = "chưa gửi";
        nl.statusFont = th.smallBoldFont;
        nl.statusColor = th.danger.text();
        nl.statusX = x;
        x = col(th, nl, TreeColumns.Col.ROLES, x);
        String host = pr.service() != null ? pr.service().hostLabel() : "";
        return addTail(nl, th, (host.isEmpty() ? "" : host + " · ") + pr.timeHms(), x, gap);
    }

    private int layoutGroup(Node n, Theme th, NodeLayout nl, int x, int gap) {
        if (!(n.payload instanceof ApiGroup g)) {
            x = addLeadIcon(nl, Icons.dot(th.dotNone, false), x, gap);
            return addPrimary(nl, Icons.stripLeadingSymbol(n.label), th.boldFont, th.fg, x, th.scaled(4));
        }
        Color dotCol = n.level == AuditLevel.NONE ? th.dotNone : th.audit(n.level).base();
        x = addLeadIcon(nl, Icons.dot(dotCol, false), x, th.scaled(3));

        // Clickable Copy icon right next to the status dot
        Icon cp = Icons.copy(th.muted);
        nl.copyIcon = cp;
        nl.copyX0 = x;
        nl.copyX1 = x + cp.getIconWidth();
        x = nl.copyX1 + gap;

        // Primary bold "API <gid>"
        x = addPrimary(nl, Text.apiLabel(g.gid()), th.boldFont, th.fg, x, gap);

        // Method pill
        Theme.Token mt = th.method(g.method());
        x = addChip(th, nl, g.method(), th.smallBoldFont, mt.fill(), mt.border(), mt.text(), null, true, x, gap);

        // Monospace short path (rendered with dimmed {id} / numbers / query)
        String shortP = Text.shortPath(g.path(), 48);
        if (!shortP.isEmpty()) {
            nl.monoPathX = x;
            nl.monoFont = th.monoFont;
            nl.monoPath = shortP.toCharArray();
            nl.monoPathLen = nl.monoPath.length;
            x += getFontMetrics(th.monoFont).charsWidth(nl.monoPath, 0, nl.monoPathLen) + gap;
        }

        // Clickable Excel badge
        if (g.target() != null && !g.target().sttText().isBlank()) {
            nl.excelX0 = x;
            x = addChip(th, nl, "Excel " + g.target().sttText(), th.smallBoldFont, th.info.fill(), th.info.border(),
                    th.info.text(), Icons.excel(th.info.text(), 11), true, x, th.scaled(4));
            nl.excelX1 = x - th.scaled(4);
        }

        // Clickable Duplicate / Origin lineage badge
        if (g.dupTotal() > 1) {
            nl.dupX0 = x;
            boolean isOrig = nl.originFlag;
            if (isOrig) {
                x = addChip(th, nl, "Gốc · " + g.dupTotal(), th.smallBoldFont, th.orig.fillStrong(), th.orig.border(),
                        th.orig.text(), Icons.star(th.orig.text(), 11), true, x, th.scaled(4));
            } else {
                ApiGroup orig = engine != null ? engine.originOf(g.bookKey()) : null;
                String dupTxt = orig != null && orig.gid() != g.gid()
                        ? "Lần " + g.dupNo() + " · gốc " + Text.apiLabel(orig.gid())
                        : "Lần " + g.dupNo() + " / " + g.dupTotal();
                x = addChip(th, nl, dupTxt, th.smallBoldFont, th.dup.fill(), th.dup.border(),
                        th.dup.text(), Icons.dup(th.dup.text(), 11), true, x, th.scaled(4));
            }
            nl.dupX1 = x - th.scaled(4);
        }

        // Pending chip
        if (g.pending()) {
            x = addChip(th, nl, compactPending(g), th.smallBoldFont,
                    th.pending.fillStrong(), th.pending.border(), th.pending.text(),
                    Icons.hourglass(th.pending.text(), 11), true, x, th.scaled(4));
        }

        // Verdict pill (audit summary)
        String summary = Audit.summary(g);
        AuditLevel auditLvl = Audit.level(g);
        if (summary != null && !summary.isEmpty()) {
            Theme.Token at = g.results().isEmpty() ? th.orig : th.audit(auditLvl);
            Icon ic = g.results().isEmpty() ? Icons.pin(at.text(), 11) : Icons.forAudit(auditLvl, at.text());
            x = addChip(th, nl, compactVerdict(summary), th.smallBoldFont,
                    at.fill(), at.border(), at.text(), ic, true, x, th.scaled(4));
        }

        // Action / ID Protection status chip (when proxy-held or ID substituted/restored)
        if (g.activeIdNote() != null && !g.activeIdNote().isBlank()) {
            x = addChip(th, nl, Text.shortPath(g.activeIdNote(), 22), th.smallBoldFont,
                    th.accent.fill(), th.accent.border(), th.accent.text(), null, true, x, th.scaled(4));
        } else if (g.heldAtProxy()) {
            x = addChip(th, nl, "Chặn Proxy", th.smallBoldFont,
                    th.info.fill(), th.info.border(), th.info.text(), null, true, x, th.scaled(4));
        }

        // API Tag chips (REFRESH / INIT / POLLING / custom) + Suspected Refresh hint
        int shownTags = 0;
        for (String tagId : g.tags()) {
            if (shownTags >= 2) {
                break;
            }
            ApiTagCatalog.TagDef def = engine != null ? engine.tagCatalog().byId(tagId) : null;
            Theme.Token ttok = Icons.tokenForKey(th, def != null ? def.colorKey() : "warn");
            Icon tic = ApiTagCatalog.TAG_REFRESH.equals(tagId) ? Icons.refresh(ttok.text(), 10) : Icons.tag(ttok.text(), 10);
            x = addChip(th, nl, tagId, th.smallBoldFont, ttok.fill(), ttok.border(), ttok.text(), tic, true, x, th.scaled(4));
            shownTags++;
        }
        if (shownTags < 2 && g.suspectedRefresh() && !g.hasTag(ApiTagCatalog.TAG_REFRESH)) {
            x = addChip(th, nl, "Tải lại?", th.smallBoldFont, th.warning.fill(), th.warning.border(),
                    th.warning.text(), Icons.refresh(th.warning.text(), 10), true, x, th.scaled(4));
        }
        return x;
    }

    private int layoutLogApi(Node n, Theme th, NodeLayout nl, int x, int gap) {
        nl.fill = th.tinted(n.level) ? th.rowBackground(n.level, 0) : th.surfaceAlt;
        if (!(n.payload instanceof ApiGroup g)) {
            x = addLeadIcon(nl, Icons.dot(th.dotNone, false), x, gap);
            return addPrimary(nl, Icons.stripLeadingSymbol(n.label), th.boldFont, th.fg, x, th.scaled(4));
        }
        Color dotCol = n.level == AuditLevel.NONE ? th.dotNone : th.audit(n.level).base();
        x = addLeadIcon(nl, Icons.dot(dotCol, false), x, gap);
        x = addPrimary(nl, Text.apiLabel(g.gid()), th.boldFont, th.fg, x, gap);
        Theme.Token mt = th.method(g.method());
        x = addChip(th, nl, g.method(), th.smallBoldFont, mt.fill(), mt.border(), mt.text(), null, true, x, gap);
        String shortP = Text.shortPath(g.path(), 54);
        if (!shortP.isEmpty()) {
            nl.monoPathX = x;
            nl.monoFont = th.monoFont;
            nl.monoPath = shortP.toCharArray();
            nl.monoPathLen = nl.monoPath.length;
            x += getFontMetrics(th.monoFont).charsWidth(nl.monoPath, 0, nl.monoPathLen) + gap;
        }
        if (g.created() != null && !g.created().isEmpty()) {
            nl.statusText = g.created();
            nl.statusFont = th.monoSmallFont;
            nl.statusColor = th.muted;
            nl.statusX = x;
            x += getFontMetrics(th.monoSmallFont).stringWidth(nl.statusText) + gap;
        }
        String summary = Audit.summary(g);
        if (summary != null && !summary.isEmpty()) {
            AuditLevel al = Audit.level(g);
            Theme.Token at = th.audit(al);
            x = addChip(th, nl, compactVerdict(summary), th.smallBoldFont, at.fill(), at.border(), at.text(),
                    Icons.forAudit(al, at.text()), false, x, th.scaled(4));
        }
        return x;
    }

    private int layoutRole(Node n, Theme th, NodeLayout nl, int x, int gap) {
        boolean isOrig = n.kind == Node.Kind.ORIG;
        if (!(n.payload instanceof Object[] arr) || arr.length < 2 || !(arr[0] instanceof ApiGroup g)
                || !(arr[1] instanceof RoleResult r)) {
            Color dotCol = isOrig ? th.orig.base() : (n.level == AuditLevel.NONE ? th.dotNone : th.audit(n.level).base());
            x = addLeadIcon(nl, Icons.dot(dotCol, false), x, gap);
            return addPrimary(nl, Icons.stripLeadingSymbol(n.label), isOrig ? th.boldFont : th.baseFont,
                    isOrig ? th.orig.text() : th.fg, x, th.scaled(4));
        }

        Color dotCol = isOrig ? th.orig.base() : (n.level == AuditLevel.NONE ? th.dotNone : th.audit(n.level).base());
        x = addLeadIcon(nl, Icons.dot(dotCol, false), x, th.scaled(5));

        if (isOrig) {
            Icon pin = Icons.pin(th.orig.text(), 12);
            nl.pinIcon = pin;
            nl.pinX = x;
            x += pin.getIconWidth() + th.scaled(4);
        }

        x = addPrimary(nl, r.roleName(), isOrig ? th.boldFont : th.baseFont, isOrig ? th.orig.text() : th.fg, x, gap);

        Integer stCode = r.status();
        String st = stCode != null ? String.valueOf(stCode) : Text.nz(r.state());
        if (!st.isEmpty()) {
            nl.statusText = st;
            nl.statusFont = stCode != null ? th.boldFont : th.smallFont;
            nl.statusColor = stCode != null ? th.statusColor(stCode) : th.muted;
            nl.statusX = x;
            x += getFontMetrics(nl.statusFont).stringWidth(st) + gap;
        }

        String lenText = r.length() != null ? r.length() + " B" : "-";
        nl.tailText = lenText;
        nl.tailFont = th.monoSmallFont;
        nl.tailColor = th.muted;
        nl.tailX = x;
        x += getFontMetrics(th.monoSmallFont).stringWidth(lenText) + gap;

        String badge = Audit.badge(g, r);
        if (badge != null && !badge.isEmpty()) {
            Theme.Token tok = isOrig ? th.orig : th.audit(n.level);
            Icon ic = isOrig ? Icons.pin(tok.text(), 11)
                    : (n.level != AuditLevel.NONE ? Icons.forAudit(n.level, tok.text()) : null);
            x = addChip(th, nl, Icons.stripLeadingSymbol(badge), th.smallBoldFont, tok.fill(), tok.border(), tok.text(),
                    ic, false, x, th.scaled(4));
        }
        return addSentNoteChip(th, nl, r, x);
    }

    private int layoutGhost(Node n, Theme th, NodeLayout nl, int x, int gap) {
        x = addLeadIcon(nl, Icons.ghost(th.muted, 12), x, gap);
        return addPrimary(nl, Icons.stripLeadingSymbol(n.label), th.italicFont, th.muted, x, th.scaled(4));
    }

    private int layoutPlace(Node n, Theme th, NodeLayout nl, int x, int gap) {
        boolean isOld = n.payload instanceof Object[] arr && arr.length > 2 && "old".equals(arr[2]);
        Icon ic = isOld ? Icons.pin(th.orig.text(), 12) : Icons.plus(th.accent.text(), 12);
        x = addLeadIcon(nl, ic, x, gap);
        return addPrimary(nl, Icons.stripInlineSymbols(n.label), th.baseFont, th.muted, x, th.scaled(4));
    }

    private int layoutLogLine(Node n, Theme th, NodeLayout nl, int x, int gap) {
        if (n.payload instanceof Logbook.Line l) {
            LogTab.LogKind kind = LogTab.classify(l);
            nl.statusText = l.time();
            nl.statusFont = th.monoSmallFont;
            nl.statusColor = th.muted;
            nl.statusX = x;
            x += getFontMetrics(th.monoSmallFont).stringWidth(l.time()) + gap;

            Icon ic = switch (kind) {
                case ERROR -> Icons.error(th.danger.text(), 12);
                case CAPTURE -> Icons.bolt(th.info.text(), 12);
                case SEND -> Icons.send(th.success.text(), 12);
                case MOVE -> Icons.move(th.warning.text(), 12);
                default -> Icons.gear(th.muted, 12);
            };
            nl.midIcon = ic;
            nl.midX = x;
            x += ic.getIconWidth() + gap;

            String body = Icons.stripInlineSymbols(l.text());
            if (kind == LogTab.LogKind.ERROR) {
                nl.fill = th.danger.fill();
            }
            x = addPrimary(nl, body, th.baseFont, kind == LogTab.LogKind.ERROR ? th.danger.text() : th.fg, x, th.scaled(4));
            return x;
        }
        return addPrimary(nl, Icons.stripInlineSymbols(n.label), th.baseFont, th.fg, x, th.scaled(4));
    }

    private int layoutSimple(Node n, Theme th, NodeLayout nl, int x, int gap) {
        return addPrimary(nl, Icons.stripLeadingSymbol(n.label), th.baseFont, th.muted, x, gap);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Theme th = activeTheme != null ? activeTheme : Theme.current();
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            NodeLayout nl = active;
            if (nl == null) {
                if (activeSelected) {
                    g2.setColor(th.selectionBg);
                    g2.fillRect(0, 0, w - 1, h - 1);
                }
                g2.setColor(th.grid);
                g2.drawLine(0, h - 1, w - 1, h - 1);
                g2.setFont(th.baseFont);
                g2.setColor(activeSelected ? th.selectionFg : th.fg);
                FontMetrics fm = g2.getFontMetrics(th.baseFont);
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(currentText, th.scaled(6), ty);
                return;
            }

            Color fill = activeSelected ? th.selectionBg : nl.fill;
            Color edge = activeSelected ? Theme.mix(th.selectionBg, th.fg, 0.3) : nl.edge;
            if (fill != null) {
                g2.setColor(fill);
                g2.fillRect(0, 0, w - 1, h - 1);
            }
            if (edge != null) {
                Stroke prev = g2.getStroke();
                if (nl.dashedEdge) {
                    g2.setStroke(DASHED_STROKE);
                }
                g2.setColor(edge);
                g2.drawRect(0, 0, w - 1, h - 2);
                if (nl.dashedEdge) {
                    g2.setStroke(prev);
                }
            }

            // Tree-Table horizontal row border and vertical column cell separators
            g2.setColor(th.grid);
            g2.drawLine(0, h - 1, w - 1, h - 1);
            int divPad = th.scaled(3);
            // V_7.0 grid rows: correct the estimated node x with the real painted x so columns line up exactly
            int shift = nl.grid && nl.alignFrom >= 0 && getParent() instanceof javax.swing.CellRendererPane
                    ? nl.estNodeX - getX() : 0;
            int af = nl.grid ? nl.alignFrom : Integer.MAX_VALUE;
            if (nl.grid) {
                for (TreeColumns.Col c : TreeColumns.Col.values()) {
                    if (c == TreeColumns.Col.TREE) {
                        continue;
                    }
                    int cx = TreeColumns.start(th, c) - nl.estNodeX + shift - 1;
                    if (cx > 0 && cx < w) {
                        g2.drawLine(cx, 2, cx, h - 3);
                    }
                }
            } else if (nl.monoPathLen > 0 && nl.monoPathX > divPad) {
                g2.drawLine(nl.monoPathX - divPad, 2, nl.monoPathX - divPad, h - 3);
            }
            if (nl.statusText != null && !nl.statusText.isEmpty() && nl.statusX > divPad) {
                g2.drawLine(nl.statusX - divPad, 2, nl.statusX - divPad, h - 3);
            }
            if (nl.tailText != null && !nl.tailText.isEmpty() && nl.tailX > divPad) {
                g2.drawLine(nl.tailX - divPad, 2, nl.tailX - divPad, h - 3);
            }
            if (nl.chipCount > 0 && nl.chips[0].x > divPad) {
                g2.drawLine(nl.chips[0].x - divPad, 2, nl.chips[0].x - divPad, h - 3);
            }

            if (nl.leadIcon != null) {
                nl.leadIcon.paintIcon(this, g2, nl.leadX, (h - nl.leadIcon.getIconHeight()) / 2);
            }
            if (nl.copyIcon != null) {
                Icon cp = activeSelected ? Icons.copy(th.selectionFg) : nl.copyIcon;
                cp.paintIcon(this, g2, nl.copyX0, (h - cp.getIconHeight()) / 2);
            }
            if (nl.pinIcon != null) {
                Icon pin = activeSelected ? Icons.pin(th.selectionFg, 12) : nl.pinIcon;
                pin.paintIcon(this, g2, nl.pinX, (h - pin.getIconHeight()) / 2);
            }
            if (nl.midIcon != null) {
                nl.midIcon.paintIcon(this, g2, nl.midX, (h - nl.midIcon.getIconHeight()) / 2);
            }

            if (nl.primaryText != null && !nl.primaryText.isEmpty()) {
                g2.setFont(nl.primaryFont);
                FontMetrics fm = g2.getFontMetrics(nl.primaryFont);
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(activeSelected ? th.selectionFg : nl.primaryColor);
                drawStringZeroAlloc(g2, nl.primaryText, nl.primaryX, ty);
            }

            if (nl.monoPathLen > 0) {
                g2.setFont(nl.monoFont);
                FontMetrics fm = g2.getFontMetrics(nl.monoFont);
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                Color baseCol = activeSelected ? th.selectionFg : th.fg;
                Color mutedCol = activeSelected ? th.selectionFg : th.muted;
                AuditRenderer.drawPathBuffer(g2, fm, nl.monoPath, nl.monoPathLen, -1,
                        nl.monoPathX + (nl.monoPathX >= af ? shift : 0), ty, baseCol, mutedCol);
            }

            if (nl.statusText != null && !nl.statusText.isEmpty()) {
                g2.setFont(nl.statusFont);
                FontMetrics fm = g2.getFontMetrics(nl.statusFont);
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(activeSelected ? th.selectionFg : nl.statusColor);
                drawStringZeroAlloc(g2, nl.statusText, nl.statusX + (nl.statusX >= af ? shift : 0), ty);
            }

            if (nl.tailText != null && !nl.tailText.isEmpty()) {
                g2.setFont(nl.tailFont);
                FontMetrics fm = g2.getFontMetrics(nl.tailFont);
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(activeSelected ? th.selectionFg : nl.tailColor);
                drawStringZeroAlloc(g2, nl.tailText, nl.tailX + (nl.tailX >= af ? shift : 0), ty);
            }

            if (nl.roleCount > 0 && nl.roleX >= 0) {
                int sq = Math.max(8, th.scaled(10));
                int sqGap = Math.max(2, th.scaled(3));
                int rx = nl.roleX + (nl.roleX >= af ? shift : 0);
                int ry = (h - sq) / 2;
                for (int i = 0; i < nl.roleCount; i++) {
                    g2.setColor(nl.roleColors[i]);
                    g2.fillRect(rx, ry, sq, sq);
                    if (activeSelected) {
                        g2.setColor(th.selectionFg);
                        g2.drawRect(rx, ry, sq - 1, sq - 1);
                    }
                    rx += sq + sqGap;
                }
            }

            int pillH = Math.max(th.scaled(15), h - th.scaled(7));
            int pillY = (h - pillH) / 2;
            int ip = th.scaled(6);
            for (int i = 0; i < nl.chipCount; i++) {
                Chip ch = nl.chips[i];
                int chx = ch.x + (ch.x >= af ? shift : 0);
                g2.setColor(ch.fill);
                g2.fillRect(chx, pillY, ch.w - 1, pillH - 1);
                g2.setColor(ch.border);
                g2.drawRect(chx, pillY, ch.w - 1, pillH - 1);
                int cx = chx + ip;
                if (ch.icon != null) {
                    ch.icon.paintIcon(this, g2, cx, pillY + (pillH - ch.icon.getIconHeight()) / 2);
                    cx += ch.icon.getIconWidth() + (ch.text.isEmpty() ? 0 : th.scaled(3));
                }
                if (!ch.text.isEmpty()) {
                    g2.setFont(ch.font);
                    FontMetrics fm = g2.getFontMetrics(ch.font);
                    int ty = pillY + (pillH - fm.getHeight()) / 2 + fm.getAscent();
                    g2.setColor(ch.fg);
                    drawStringZeroAlloc(g2, ch.text, cx, ty);
                }
            }
        } finally {
            g2.dispose();
        }
    }

    private void drawStringZeroAlloc(Graphics2D g2, String s, int x, int y) {
        int n = s.length();
        if (n <= plainScratch.length) {
            s.getChars(0, n, plainScratch, 0);
            g2.drawChars(plainScratch, 0, n, x, y);
        } else {
            g2.drawString(s, x, y);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Theme th = activeTheme != null ? activeTheme : Theme.current();
        if (active == null) {
            FontMetrics fm = getFontMetrics(th.baseFont);
            int w = th.scaled(16) + fm.stringWidth(currentText);
            return new Dimension(w + 6 + w / 20, Math.max(Theme.ROW_TREE, th.scaled(Theme.ROW_TREE)));
        }
        // Preserve HiDPI scaling slack so rightmost chips never clip
        int w = active.totalW;
        return new Dimension(w + 8 + w / 20, active.totalH);
    }
}
