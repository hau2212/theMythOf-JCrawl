package themythofcrawl.ui;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.plaf.basic.BasicTreeUI;

import themythofcrawl.app.Prefs;
import themythofcrawl.ui.theme.Theme;

/**
 * V_7.0 tree-table grid: API, role and held rows of a branch tree paint Method / Path / Status /
 * Length / Kết quả Role at FIXED absolute x positions whatever their depth, so every API row of the
 * Cây tab lines up like Burp's HTTP history while the branch hierarchy keeps its indentation in the
 * first column. A header strip ({@link Header}) shows the titles and lets the user drag column edges.
 */
public final class TreeColumns {
    public static final String CLIENT_KEY = "tmoc.tree.columns";
    public static final String PREF_KEY = "tmoc.ui.treeColumns.v7";

    public enum Col {
        TREE("Nhánh / API", 300, 140),
        METHOD("Method", 74, 50),
        PATH("Path", 380, 120),
        STATUS("Status", 60, 44),
        LENGTH("Length", 70, 44),
        ROLES("Kết quả vai trò", 150, 60),
        VERDICT("Đánh giá · Tag", 260, 80);

        public final String title;
        final int defaultWidth;
        final int minWidth;

        Col(String title, int defaultWidth, int minWidth) {
            this.title = title;
            this.defaultWidth = defaultWidth;
            this.minWidth = minWidth;
        }
    }

    private static final Col[] COLS = Col.values();
    private static final int[] WIDTHS = new int[COLS.length];
    private static final AtomicInteger GENERATION = new AtomicInteger(1);
    private static volatile Prefs prefs;

    static {
        reset();
    }

    private TreeColumns() {
    }

    /** Marks {@code tree} as a tree-table: its API / role rows are painted on the column grid. */
    public static void enable(JTree tree) {
        tree.putClientProperty(CLIENT_KEY, Boolean.TRUE);
    }

    public static boolean enabled(JTree tree) {
        return tree != null && Boolean.TRUE.equals(tree.getClientProperty(CLIENT_KEY));
    }

    /** Bumped whenever a width changes; renderers re-layout cached rows when it moves. */
    public static int generation() {
        return GENERATION.get();
    }

    public static synchronized void reset() {
        for (int i = 0; i < COLS.length; i++) {
            WIDTHS[i] = COLS[i].defaultWidth;
        }
        GENERATION.incrementAndGet();
    }

    /** Loads saved widths (unscaled px) from {@code p} and remembers it for saving. */
    public static synchronized void load(Prefs p) {
        prefs = p;
        if (p == null) {
            return;
        }
        String raw = p.get(PREF_KEY);
        if (raw == null || raw.isBlank()) {
            return;
        }
        String[] parts = raw.split(",");
        for (int i = 0; i < COLS.length && i < parts.length; i++) {
            try {
                WIDTHS[i] = Math.max(COLS[i].minWidth, Integer.parseInt(parts[i].trim()));
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        GENERATION.incrementAndGet();
    }

    private static synchronized void save() {
        Prefs p = prefs;
        if (p == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < WIDTHS.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(WIDTHS[i]);
        }
        p.set(PREF_KEY, sb.toString());
    }

    /** Unscaled width of a column. */
    public static synchronized int width(Col c) {
        return WIDTHS[c.ordinal()];
    }

    public static synchronized void setWidth(Col c, int unscaled) {
        int w = Math.max(c.minWidth, unscaled);
        if (WIDTHS[c.ordinal()] != w) {
            WIDTHS[c.ordinal()] = w;
            GENERATION.incrementAndGet();
            save();
        }
    }

    /** Absolute x (tree coordinates, scaled) where column {@code c} starts. */
    public static int start(Theme th, Col c) {
        int x = 0;
        for (int i = 0; i < c.ordinal(); i++) {
            x += th.scaled(width(COLS[i]));
        }
        return x;
    }

    public static int scaledWidth(Theme th, Col c) {
        return th.scaled(width(c));
    }

    /**
     * Absolute x of the renderer of a node at {@code level} (= TreeNode#getLevel), mirroring
     * {@code BasicTreeUI.getRowX}: insets + totalChildIndent * (depth + depthOffset).
     */
    public static int nodeX(JTree tree, int level) {
        if (tree == null) {
            return 0;
        }
        int total = 20;
        if (tree.getUI() instanceof BasicTreeUI ui) {
            total = ui.getLeftChildIndent() + ui.getRightChildIndent();
        }
        int offset;
        if (tree.isRootVisible()) {
            offset = tree.getShowsRootHandles() ? 1 : 0;
        } else {
            offset = tree.getShowsRootHandles() ? 0 : -1;
        }
        Insets in = tree.getInsets();
        return (in != null ? in.left : 0) + total * (level + offset);
    }

    /** Forces the tree to recompute row widths after a column change. */
    public static void refresh(JTree tree) {
        if (tree == null) {
            return;
        }
        int h = tree.getRowHeight();
        if (h > 0) {
            tree.setRowHeight(h + 1);
            tree.setRowHeight(h);
        }
        tree.revalidate();
        tree.repaint();
    }

    /** Installs the column header strip above {@code tree} inside {@code scroll}. */
    public static Header installHeader(JTree tree, JScrollPane scroll) {
        Header h = new Header(tree);
        scroll.setColumnHeaderView(h);
        return h;
    }

    /** Column titles above the tree; drag a column's right edge to resize it. */
    public static final class Header extends JComponent {
        private final JTree tree;
        private Col dragging;
        private int dragStartX;
        private int dragStartW;

        Header(JTree tree) {
            this.tree = tree;
            setOpaque(true);
            setToolTipText("Kéo mép phải tiêu đề cột để đổi độ rộng · Nhấp đúp để về mặc định");
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    setCursor(Cursor.getPredefinedCursor(edgeAt(e.getX()) != null ? Cursor.E_RESIZE_CURSOR
                            : Cursor.DEFAULT_CURSOR));
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    dragging = edgeAt(e.getX());
                    if (dragging != null) {
                        dragStartX = e.getX();
                        dragStartW = width(dragging);
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragging == null) {
                        return;
                    }
                    Theme th = Theme.current();
                    int deltaUnscaled = (int) Math.round((e.getX() - dragStartX) / Math.max(0.5, th.scaled(100) / 100.0));
                    setWidth(dragging, dragStartW + deltaUnscaled);
                    repaint();
                    tree.repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (dragging != null) {
                        dragging = null;
                        refresh(tree);
                    }
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && edgeAt(e.getX()) != null) {
                        reset();
                        save();
                        refresh(tree);
                        repaint();
                    }
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private Col edgeAt(int x) {
            Theme th = Theme.current();
            int grab = th.scaled(4);
            for (Col c : COLS) {
                int edge = start(th, c) + scaledWidth(th, c);
                if (Math.abs(x - edge) <= grab) {
                    return c;
                }
            }
            return null;
        }

        @Override
        public Dimension getPreferredSize() {
            Theme th = Theme.current();
            Col last = COLS[COLS.length - 1];
            int w = start(th, last) + scaledWidth(th, last);
            return new Dimension(Math.max(w, tree.getPreferredSize().width), th.scaled(24));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme th = Theme.current();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                themythofcrawl.ui.theme.FontManager.applyTextHints(g2);
                int w = getWidth();
                int h = getHeight();
                g2.setColor(th.surfaceAlt);
                g2.fillRect(0, 0, w, h);
                g2.setColor(th.border);
                g2.drawLine(0, h - 1, w, h - 1);
                g2.setFont(th.smallBoldFont);
                FontMetrics fm = g2.getFontMetrics();
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                for (Col c : COLS) {
                    int x = start(th, c);
                    int cw = scaledWidth(th, c);
                    g2.setColor(th.fg);
                    String t = c.title;
                    int pad = th.scaled(7);
                    if (fm.stringWidth(t) > cw - pad * 2) {
                        while (t.length() > 1 && fm.stringWidth(t + "…") > cw - pad * 2) {
                            t = t.substring(0, t.length() - 1);
                        }
                        t = t + "…";
                    }
                    g2.drawString(t, x + pad, ty);
                    g2.setColor(th.border);
                    g2.drawLine(x + cw - 1, th.scaled(4), x + cw - 1, h - th.scaled(4));
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
