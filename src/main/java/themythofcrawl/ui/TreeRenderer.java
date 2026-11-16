package themythofcrawl.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.tree.TreeCellRenderer;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.ui.kit.ThemedTree;
import themythofcrawl.ui.theme.Theme;

/**
 * Tree rows drawn as rounded chips: folders in the colour of their level (siblings share it, parent and
 * child never do) with a dot for the branch verdict; API / role rows tinted by audit; the original in
 * blue; duplicated APIs with a purple edge; API rows carry a copy glyph.
 */
public final class TreeRenderer extends JLabel implements TreeCellRenderer, ThemedTree.ExtensionRenderer {
    private static final int ARC = 10;
    private Color fill;
    private Color edge;
    private Color dot;
    private final Border pad = BorderFactory.createEmptyBorder(2, 6, 2, 9);
    private final Border padDot = BorderFactory.createEmptyBorder(2, 6, 2, 22);

    public TreeRenderer() {
        setOpaque(false);
        setIconTextGap(5);
        setBorder(pad);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setOpaque(false);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf,
                                                  int row, boolean hasFocus) {
        fill = null;
        edge = null;
        dot = null;
        if (!(value instanceof Node n)) {
            setText(String.valueOf(value));
            setIcon(null);
            return this;
        }
        String cleanLabel = Icons.stripLeadingSymbol(n.label);
        setText(cleanLabel);
        Color bg = tree.getBackground() != null ? tree.getBackground() : UIManager.getColor("Tree.background");
        Color fgc = UIManager.getColor("Tree.textForeground") != null ? UIManager.getColor("Tree.textForeground") : tree.getForeground();
        Theme th = Theme.of(bg, fgc);
        boolean strong = switch (n.kind) {
            case ROOT, FOLDER, GROUP, LOGBRANCH, LOGAPI, LOGSYS -> true;
            default -> false;
        };
        setFont(n.kind == Node.Kind.GHOST || n.kind == Node.Kind.GHOSTS ? th.italicFont
                : strong ? th.boldFont : th.baseFont);

        Color text = th.fg;
        Icon icon = null;
        switch (n.kind) {
            case FOLDER, LOGBRANCH -> {
                Theme.Token lv = th.level(n.depth);
                fill = lv.fill();
                edge = lv.border();
                text = lv.text();
                icon = Icons.dot(lv.base(), true);
                if (th.tinted(n.level) && n.level != AuditLevel.PENDING) {
                    dot = th.audit(n.level).base();
                }
            }
            case ROOT, LOGSYS -> {
                fill = th.surfaceAlt;
                edge = th.grid;
                icon = Icons.dot(th.dotNone, true);
            }
            case GROUP, LOGAPI -> {
                fill = th.tinted(n.level) ? th.rowBackground(n.level, 0) : th.surfaceAlt;
                Color dotCol = n.level == AuditLevel.NONE ? th.dotNone : th.audit(n.level).base();
                icon = n.kind == Node.Kind.GROUP ? Icons.group(dotCol, th.muted) : Icons.dot(dotCol, false);
                if (n.payload instanceof ApiGroup g && g.dupTotal() > 1) {
                    edge = th.dup.base();
                }
            }
            case ORIG, ROLE, LOGLINE -> {
                if (th.tinted(n.level)) {
                    fill = th.rowBackground(n.level, 0);
                }
                if (n.kind == Node.Kind.ORIG) {
                    text = th.orig.text();
                }
                Color dotCol = n.kind == Node.Kind.ORIG ? th.orig.base()
                        : n.level == AuditLevel.NONE ? th.dotNone : th.audit(n.level).base();
                icon = Icons.dot(dotCol, false);
            }
            case GHOSTS, GHOST -> {
                text = th.muted;
                icon = Icons.ghost(th.muted, 12);
            }
            case PLACE -> {
                text = th.muted;
                icon = n.payload instanceof Object[] arr && arr.length > 2 && "old".equals(arr[2])
                        ? Icons.pin(th.orig.text(), 12) : Icons.plus(th.accent.text(), 12);
            }
            default -> text = th.muted;
        }
        if (selected) {
            fill = th.selectionBg;
            edge = Theme.mix(th.selectionBg, th.fg, 0.3);
            text = th.selectionFg;
        }
        setForeground(text);
        setIcon(icon);
        setBorder(dot != null ? padDot : pad);
        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (fill != null || edge != null || dot != null) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                if (fill != null) {
                    g2.setColor(fill);
                    g2.fillRoundRect(0, 1, w - 1, h - 2, ARC, ARC);
                }
                if (edge != null) {
                    g2.setColor(edge);
                    g2.drawRoundRect(0, 1, w - 1, h - 3, ARC, ARC);
                }
                if (dot != null) {
                    g2.setColor(dot);
                    g2.fillOval(w - 16, (h - 9) / 2, 9, 9);
                }
            } finally {
                g2.dispose();
            }
        }
        super.paintComponent(g);
    }

    @Override
    public Dimension getPreferredSize() {
        // text measured at 1x but drawn scaled (HiDPI) can be a few % wider: keep slack so labels never clip
        Dimension d = super.getPreferredSize();
        String t = getText();
        if (t != null && !t.isEmpty()) {
            d.width += 4 + getFontMetrics(getFont()).stringWidth(t) / 20;
        }
        return d;
    }
}
