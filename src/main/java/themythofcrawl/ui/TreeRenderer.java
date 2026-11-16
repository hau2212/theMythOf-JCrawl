package themythofcrawl.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
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
        setText(n.label);
        Color bg = tree.getBackground() != null ? tree.getBackground() : UIManager.getColor("Tree.background");
        Color fgc = UIManager.getColor("Tree.textForeground") != null ? UIManager.getColor("Tree.textForeground") : tree.getForeground();
        Palette pal = Palette.of(bg, fgc);
        Font f = tree.getFont();
        boolean strong = switch (n.kind) {
            case ROOT, FOLDER, GROUP, LOGBRANCH, LOGAPI, LOGSYS -> true;
            default -> false;
        };
        if (f != null) {
            setFont(strong ? f.deriveFont(Font.BOLD) : f.deriveFont(Font.PLAIN));
        }
        Color text = pal.fg;
        Icon icon = null;
        switch (n.kind) {
            case FOLDER, LOGBRANCH -> {
                Color[] lv = pal.level(n.depth);
                fill = lv[0];
                edge = lv[1];
                text = lv[2];
                icon = Icons.dot(lv[3], true);
                if (pal.tinted(n.level) && n.level != AuditLevel.PENDING) {
                    dot = pal.dot(n.level);
                }
            }
            case ROOT, LOGSYS -> {
                fill = pal.zebra[1];
                edge = pal.grid;
                icon = Icons.dot(pal.dotNone, true);
            }
            case GROUP, LOGAPI -> {
                fill = pal.tinted(n.level) ? pal.background(n.level, 0) : pal.zebra[1];
                icon = n.kind == Node.Kind.GROUP ? Icons.group(pal.dot(n.level), pal.muted) : Icons.dot(pal.dot(n.level), false);
                if (n.payload instanceof ApiGroup g && g.dupTotal() > 1) {
                    edge = Palette.DUP_PURPLE;
                }
            }
            case ORIG, ROLE, LOGLINE -> {
                if (pal.tinted(n.level)) {
                    fill = pal.background(n.level, 0);
                }
                if (n.kind == Node.Kind.ORIG) {
                    text = pal.origFg;
                }
                icon = Icons.dot(n.kind == Node.Kind.ORIG ? pal.origAccent : pal.dot(n.level), false);
            }
            default -> text = pal.muted;
        }
        if (selected) {
            Color sb = UIManager.getColor("Tree.selectionBackground") != null ? UIManager.getColor("Tree.selectionBackground")
                    : new Color(51, 120, 220);
            Color sf = UIManager.getColor("Tree.selectionForeground") != null ? UIManager.getColor("Tree.selectionForeground")
                    : Color.WHITE;
            fill = sb;
            edge = Palette.mix(sb, pal.fg, 0.3);
            text = sf;
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
