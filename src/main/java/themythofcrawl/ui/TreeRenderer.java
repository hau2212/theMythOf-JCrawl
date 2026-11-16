package themythofcrawl.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.TreeCellRenderer;

import themythofcrawl.app.Logbook;
import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.RoleResult;
import themythofcrawl.place.Decisions;
import themythofcrawl.ui.kit.ThemedTree;
import themythofcrawl.ui.theme.Theme;

/**
 * Rich, HTML-free tree cell renderer drawing Java2D status markers, clickable chips (Copy, Excel,
 * Duplicate), semantic method &amp; verdict pills, and dimmed path parameters with node-level layout
 * caching so scrolling thousands of rows allocates nothing.
 */
public final class TreeRenderer extends JComponent implements TreeCellRenderer, ThemedTree.ExtensionRenderer {
    private static final int ARC = 8;

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
        String targetStt;

        int totalW;
        int totalH;
        Color fill;
        Color edge;

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

        Chip[] chips;
        int chipCount;
        int excelX0 = -1;
        int excelX1 = -1;
        int dupX0 = -1;
        int dupX1 = -1;

        void resetChips() {
            if (chips == null) {
                chips = new Chip[6];
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
        }
    }

    private NodeLayout active;
    private boolean activeSelected;
    private Theme activeTheme;
    private String currentText = "";
    private Icon currentIcon;
    private final char[] plainScratch = new char[256];

    public TreeRenderer() {
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
        if (n.renderCache instanceof NodeLayout cached && cached.theme == th && cached.gen == gen
                && n.renderStamp == gen && cached.nodeLevel == n.level && !groupChanged(n, cached)) {
            return cached;
        }
        NodeLayout nl = n.renderCache instanceof NodeLayout existing ? existing : new NodeLayout();
        buildLayout(tree, n, th, nl, gen);
        n.renderCache = nl;
        n.renderStamp = gen;
        return nl;
    }

    private static boolean groupChanged(Node n, NodeLayout nl) {
        if (n.payload instanceof ApiGroup g) {
            String stt = g.target() != null ? g.target().sttText() : null;
            return g.auditSummary() != nl.summaryRef || g.results().size() != nl.resCount
                    || g.pending() != nl.pendingFlag || g.dupTotal() != nl.dupTotal
                    || !java.util.Objects.equals(stt, nl.targetStt);
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
            nl.targetStt = g.target() != null ? g.target().sttText() : null;
        }

        int rowH = Math.max(Theme.ROW_TREE, tree != null && tree.getRowHeight() > 0 ? tree.getRowHeight() : th.scaled(Theme.ROW_TREE));
        int padL = th.scaled(6);
        int padR = th.scaled(8);
        int gap = th.scaled(6);
        int x = padL;

        switch (n.kind) {
            case FOLDER, LOGBRANCH -> x = layoutFolder(n, th, nl, x, gap);
            case ROOT -> x = layoutRoot(n, th, nl, x, gap);
            case LOGSYS -> x = layoutLogSys(n, th, nl, x, gap);
            case GROUP -> x = layoutGroup(n, th, nl, x, gap);
            case LOGAPI -> x = layoutLogApi(n, th, nl, x, gap);
            case ORIG, ROLE -> x = layoutRole(n, th, nl, x, gap);
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
        Theme.Token lv = th.level(n.depth);
        nl.fill = lv.fill();
        nl.edge = lv.border();
        x = addLeadIcon(nl, Icons.dot(lv.base(), true), x, gap);

        Node.FolderMeta meta = n.folderMeta;
        if (meta == null && n.kind == Node.Kind.LOGBRANCH && n.payload instanceof List<?> gs) {
            @SuppressWarnings("unchecked")
            List<ApiGroup> groups = (List<ApiGroup>) gs;
            Audit.Verdict v = Audit.branch(groups);
            String path = groups.isEmpty() ? Icons.stripLeadingSymbol(n.label) : Hierarchy.leaf(groups.get(0).branch());
            meta = new Node.FolderMeta(path, "C" + Math.max(1, n.depth), groups.size() + " API", v.summary(), null, null);
        }

        if (meta != null) {
            x = addPrimary(nl, meta.name(), th.boldFont, lv.text(), x, gap);
            if (meta.levelChip() != null && !meta.levelChip().isEmpty()) {
                x = addChip(th, nl, meta.levelChip(), th.smallBoldFont, lv.fillStrong(), lv.border(), lv.text(), null,
                        true, x, th.scaled(4));
            }
            if (meta.countChip() != null && !meta.countChip().isEmpty()) {
                x = addChip(th, nl, meta.countChip(), th.smallBoldFont, th.neutral.fill(), th.border, th.fg, null,
                        false, x, th.scaled(4));
            }
            if (meta.verdictChip() != null && !meta.verdictChip().isEmpty() && !"-".equals(meta.verdictChip())) {
                Theme.Token at = th.audit(n.level);
                Icon ic = Icons.forAudit(n.level, at.text());
                x = addChip(th, nl, Icons.stripLeadingSymbol(meta.verdictChip()), th.smallBoldFont,
                        at.fillStrong(), at.border(), at.text(), ic, false, x, th.scaled(4));
            }
            if (meta.pendingChip() != null && !meta.pendingChip().isEmpty()) {
                x = addChip(th, nl, meta.pendingChip(), th.smallBoldFont, th.pending.fillStrong(), th.pending.border(),
                        th.pending.text(), Icons.hourglass(th.pending.text(), 11), false, x, th.scaled(4));
            }
            if (meta.ghostChip() != null && !meta.ghostChip().isEmpty()) {
                x = addChip(th, nl, meta.ghostChip(), th.smallBoldFont, th.neutral.fill(), th.border, th.muted,
                        Icons.ghost(th.muted, 11), false, x, th.scaled(4));
            }
        } else {
            x = addPrimary(nl, Icons.stripLeadingSymbol(n.label), th.boldFont, lv.text(), x, th.scaled(4));
        }
        return x;
    }

    private int layoutRoot(Node n, Theme th, NodeLayout nl, int x, int gap) {
        nl.fill = th.surfaceAlt;
        nl.edge = th.grid;
        x = addLeadIcon(nl, Icons.dot(th.dotNone, true), x, gap);
        Node.FolderMeta m = n.folderMeta;
        if (m != null) {
            x = addPrimary(nl, m.name(), th.boldFont, th.fg, x, gap);
            if (m.countChip() != null && !m.countChip().isEmpty()) {
                x = addChip(th, nl, m.countChip(), th.smallBoldFont, th.neutral.fill(), th.border, th.fg, null,
                        false, x, th.scaled(4));
            }
        } else {
            x = addPrimary(nl, Icons.stripLeadingSymbol(n.label), th.boldFont, th.fg, x, th.scaled(4));
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

    private int layoutGroup(Node n, Theme th, NodeLayout nl, int x, int gap) {
        nl.fill = th.tinted(n.level) ? th.rowBackground(n.level, 0) : th.surfaceAlt;
        if (!(n.payload instanceof ApiGroup g)) {
            x = addLeadIcon(nl, Icons.dot(th.dotNone, false), x, gap);
            return addPrimary(nl, Icons.stripLeadingSymbol(n.label), th.boldFont, th.fg, x, th.scaled(4));
        }
        if (g.dupTotal() > 1) {
            nl.edge = th.dup.base();
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
            x = addChip(th, nl, "Excel #" + g.target().sttText(), th.smallBoldFont, th.info.fill(), th.info.border(),
                    th.info.text(), Icons.excel(th.info.text(), 11), false, x, th.scaled(4));
            nl.excelX1 = x - th.scaled(4);
        }

        // Clickable Duplicate badge
        if (g.dupTotal() > 1) {
            nl.dupX0 = x;
            x = addChip(th, nl, "#" + g.dupNo() + "/" + g.dupTotal(), th.smallBoldFont, th.dup.fill(), th.dup.border(),
                    th.dup.text(), Icons.dup(th.dup.text(), 11), false, x, th.scaled(4));
            nl.dupX1 = x - th.scaled(4);
        }

        // Pending chip
        if (g.pending()) {
            x = addChip(th, nl, Icons.stripLeadingSymbol(Decisions.pendingLabel(g)), th.smallBoldFont,
                    th.pending.fillStrong(), th.pending.border(), th.pending.text(),
                    Icons.hourglass(th.pending.text(), 11), false, x, th.scaled(4));
        }

        // Verdict pill (audit summary)
        String summary = Audit.summary(g);
        AuditLevel auditLvl = Audit.level(g);
        if (summary != null && !summary.isEmpty()) {
            Theme.Token at = g.results().isEmpty() ? th.orig : th.audit(auditLvl);
            Icon ic = g.results().isEmpty() ? Icons.pin(at.text(), 11) : Icons.forAudit(auditLvl, at.text());
            x = addChip(th, nl, Icons.stripLeadingSymbol(summary), th.smallBoldFont,
                    at.fill(), at.border(), at.text(), ic, false, x, th.scaled(4));
        }

        // Roles count chip
        x = addChip(th, nl, BranchTree.rolesLabel(g), th.smallFont, th.neutral.fill(), th.border, th.muted, null,
                false, x, th.scaled(2));
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
            x = addChip(th, nl, Icons.stripLeadingSymbol(summary), th.smallBoldFont, at.fill(), at.border(), at.text(),
                    Icons.forAudit(al, at.text()), false, x, th.scaled(4));
        }
        return x;
    }

    private int layoutRole(Node n, Theme th, NodeLayout nl, int x, int gap) {
        boolean isOrig = n.kind == Node.Kind.ORIG;
        if (isOrig) {
            nl.fill = th.orig.fill();
        } else if (th.tinted(n.level)) {
            nl.fill = th.rowBackground(n.level, 0);
        }
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
        return x;
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
                    g2.fillRoundRect(0, 1, w - 1, h - 2, ARC, ARC);
                }
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
                g2.fillRoundRect(0, 1, w - 1, h - 2, ARC, ARC);
            }
            if (edge != null) {
                g2.setColor(edge);
                g2.drawRoundRect(0, 1, w - 1, h - 3, ARC, ARC);
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
                AuditRenderer.drawPathBuffer(g2, fm, nl.monoPath, nl.monoPathLen, -1, nl.monoPathX, ty, baseCol, mutedCol);
            }

            if (nl.statusText != null && !nl.statusText.isEmpty()) {
                g2.setFont(nl.statusFont);
                FontMetrics fm = g2.getFontMetrics(nl.statusFont);
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(activeSelected ? th.selectionFg : nl.statusColor);
                drawStringZeroAlloc(g2, nl.statusText, nl.statusX, ty);
            }

            if (nl.tailText != null && !nl.tailText.isEmpty()) {
                g2.setFont(nl.tailFont);
                FontMetrics fm = g2.getFontMetrics(nl.tailFont);
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(activeSelected ? th.selectionFg : nl.tailColor);
                drawStringZeroAlloc(g2, nl.tailText, nl.tailX, ty);
            }

            int pillH = Math.max(th.scaled(15), h - th.scaled(7));
            int pillY = (h - pillH) / 2;
            int ip = th.scaled(6);
            for (int i = 0; i < nl.chipCount; i++) {
                Chip ch = nl.chips[i];
                int arc = ch.squareRadius ? Math.min(pillH, th.scaled(5)) : pillH;
                g2.setColor(ch.fill);
                g2.fillRoundRect(ch.x, pillY, ch.w - 1, pillH - 1, arc, arc);
                g2.setColor(ch.border);
                g2.drawRoundRect(ch.x, pillY, ch.w - 1, pillH - 1, arc, arc);
                int cx = ch.x + ip;
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
