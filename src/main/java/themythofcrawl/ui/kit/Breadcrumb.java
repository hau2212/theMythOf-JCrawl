package themythofcrawl.ui.kit;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

import themythofcrawl.core.Hierarchy;
import themythofcrawl.ui.Icons;
import themythofcrawl.ui.theme.Theme;

/**
 * Sharp, uniform-grid hierarchy breadcrumb ({@code [<<] [icon Toppage] > [icon Orders] > [icon Detail] [v] [+]})
 * built from fixed-height (24 px) square/rectangular cells ({@code arc = 0}, 1 px hairline borders).
 */
public class Breadcrumb extends JPanel {
    public static final int DEFAULT_CELL_HEIGHT = 24;
    public static final int DEFAULT_CELL_WIDTH = 84;

    private String path = "";
    private int maxSegments = 2;
    private int maxLabelChars = 16;
    private int cellWidth = DEFAULT_CELL_WIDTH;
    private int cellHeight = DEFAULT_CELL_HEIGHT;
    private boolean showDropdownArrow;
    private Consumer<String> onSegmentClick;
    private Runnable onDropdown;
    private Runnable onQuickAdd;
    private Function<String, Icon> iconResolver;

    public Breadcrumb() {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setOpaque(false);
    }

    public Breadcrumb(String path) {
        this();
        setPath(path);
    }

    public Breadcrumb setPath(String path) {
        String norm = path == null ? "" : Hierarchy.normalize(path);
        if (!norm.equals(this.path)) {
            this.path = norm;
            rebuild();
        }
        return this;
    }

    public String path() {
        return path;
    }

    public Breadcrumb setMaxSegments(int maxSegments) {
        this.maxSegments = Math.max(1, maxSegments);
        rebuild();
        return this;
    }

    public Breadcrumb setMaxLabelChars(int maxLabelChars) {
        this.maxLabelChars = Math.max(8, maxLabelChars);
        rebuild();
        return this;
    }

    public Breadcrumb setCellWidth(int widthPx) {
        this.cellWidth = Math.max(64, widthPx);
        rebuild();
        return this;
    }

    public Breadcrumb setCellHeight(int heightPx) {
        this.cellHeight = Math.max(20, heightPx);
        rebuild();
        return this;
    }

    public Breadcrumb setIconResolver(Function<String, Icon> resolver) {
        this.iconResolver = resolver;
        rebuild();
        return this;
    }

    public Breadcrumb onSegmentClick(Consumer<String> handler) {
        this.onSegmentClick = handler;
        rebuild();
        return this;
    }

    public Breadcrumb onDropdown(Runnable handler) {
        this.onDropdown = handler;
        this.showDropdownArrow = handler != null;
        rebuild();
        return this;
    }

    public Breadcrumb onQuickAdd(Runnable handler) {
        this.onQuickAdd = handler;
        rebuild();
        return this;
    }

    /** Forces re-rendering (e.g. after a branch's NodeType or icon changes). */
    public void refresh() {
        rebuild();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (path != null) {
            rebuild();
        }
    }

    private void addInline(JComponent c) {
        c.setAlignmentY(Component.CENTER_ALIGNMENT);
        add(c);
    }

    private void rebuild() {
        removeAll();
        Theme t = Theme.current();
        List<String> parts = Hierarchy.split(path);
        int total = parts.size();
        int start = Math.max(0, total - maxSegments);
        int h = t.scaled(cellHeight);
        int w = t.scaled(cellWidth);

        if (start > 0) {
            List<String> hidden = parts.subList(0, start);
            SquareCell collapseBtn = new SquareCell(h, "\u00AB", null,
                    "Xem " + start + " cấp cha đang thu gọn (" + Hierarchy.join(hidden) + ")",
                    comp -> showAncestorsPopup(comp, parts, start));
            addInline(collapseBtn);
            add(Box.createHorizontalStrut(2));
            addInline(chevron(t));
            add(Box.createHorizontalStrut(2));
        }

        for (int i = start; i < total; i++) {
            int depth = i + 1;
            String name = parts.get(i);
            String segmentPath = Hierarchy.join(parts.subList(0, depth));
            boolean current = i == total - 1;
            Icon ic = iconResolver != null ? iconResolver.apply(segmentPath) : null;
            SegmentCell cell = new SegmentCell(w, h, name, ic, current,
                    "Cấp " + depth + ": " + segmentPath + (current ? " (Đích hiện tại)" : " (bấm để chọn làm đích)"),
                    () -> {
                        if (onSegmentClick != null) {
                            onSegmentClick.accept(segmentPath);
                        } else if (onDropdown != null) {
                            onDropdown.run();
                        }
                    });
            addInline(cell);
            if (i < total - 1) {
                add(Box.createHorizontalStrut(2));
                addInline(chevron(t));
                add(Box.createHorizontalStrut(2));
            }
        }

        if (showDropdownArrow) {
            add(Box.createHorizontalStrut(3));
            SquareCell drop = new SquareCell(h, null, th -> Icons.chevronDown(th.fg, 11),
                    "Request mới sẽ vào: " + path + " (bấm để mở menu Đích)",
                    comp -> {
                        if (onDropdown != null) {
                            onDropdown.run();
                        }
                    });
            addInline(drop);
        }

        if (onQuickAdd != null) {
            add(Box.createHorizontalStrut(2));
            SquareCell addBtn = new SquareCell(h, null, th -> Icons.plus(th.success.text(), 12),
                    "Thêm mục con mới (Folder / Link / Button / Icon...)",
                    comp -> onQuickAdd.run());
            addInline(addBtn);
        }

        revalidate();
        repaint();
    }

    private void showAncestorsPopup(Component anchor, List<String> parts, int hiddenCount) {
        JPopupMenu popup = new JPopupMenu();
        for (int i = 0; i < hiddenCount; i++) {
            String segPath = Hierarchy.join(parts.subList(0, i + 1));
            String label = "Cấp " + (i + 1) + ": " + parts.get(i);
            Icon ic = iconResolver != null ? iconResolver.apply(segPath) : null;
            JMenuItem item = new JMenuItem(label, ic);
            item.addActionListener(e -> {
                if (onSegmentClick != null) {
                    onSegmentClick.accept(segPath);
                } else if (onDropdown != null) {
                    onDropdown.run();
                }
            });
            popup.add(item);
        }
        popup.show(anchor, 0, anchor.getHeight());
    }

    private static JLabel chevron(Theme t) {
        JLabel l = new JLabel(Icons.chevronRight(t.muted, 9));
        l.setOpaque(false);
        return l;
    }

    /** Uniform-size sharp rectangular breadcrumb segment cell. */
    private static final class SegmentCell extends JComponent {
        private final int widthPx;
        private final int heightPx;
        private final String label;
        private final Icon icon;
        private final boolean active;
        private final Runnable onClick;
        private boolean hovered;

        SegmentCell(int widthPx, int heightPx, String label, Icon icon, boolean active, String tooltip, Runnable onClick) {
            this.widthPx = widthPx;
            this.heightPx = heightPx;
            this.label = label == null ? "" : label;
            this.icon = icon;
            this.active = active;
            this.onClick = onClick;
            setOpaque(false);
            setToolTipText(tooltip);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && onClick != null) {
                        onClick.run();
                    }
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(widthPx, heightPx);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.current();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                Color bg = active ? (hovered ? t.info.fillStrong() : t.info.fill())
                        : (hovered ? t.hover : t.surface);
                Color border = active ? t.info.border() : (hovered ? t.info.border() : t.border);
                Color fg = active ? t.info.text() : t.fg;

                g2.setColor(bg);
                g2.fillRect(0, 0, w - 1, h - 1);
                g2.setColor(border);
                g2.drawRect(0, 0, w - 1, h - 1);
                if (active) {
                    g2.setColor(t.accent.solidFill());
                    g2.fillRect(1, h - 3, Math.max(1, w - 2), 2);
                }

                int cx = 6;
                if (icon != null) {
                    int iy = (h - icon.getIconHeight()) / 2;
                    icon.paintIcon(this, g2, cx, iy);
                    cx += icon.getIconWidth() + 4;
                }
                Font f = active ? t.smallBoldFont : t.smallFont;
                g2.setFont(f);
                g2.setColor(fg);
                FontMetrics fm = g2.getFontMetrics();
                int avail = Math.max(8, w - cx - 6);
                String text = clipText(label, fm, avail);
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent() - (active ? 1 : 0);
                g2.drawString(text, cx, ty);
            } finally {
                g2.dispose();
            }
        }

        private static String clipText(String s, FontMetrics fm, int maxWidth) {
            if (fm.stringWidth(s) <= maxWidth) {
                return s;
            }
            String ell = "...";
            int ew = fm.stringWidth(ell);
            if (maxWidth <= ew) {
                return ell;
            }
            int len = s.length();
            while (len > 1 && fm.stringWidth(s.substring(0, len)) + ew > maxWidth) {
                len--;
            }
            return s.substring(0, len) + ell;
        }
    }

    /** Uniform square (HxH) sharp icon/glyph trigger cell ([<<], [v], [+]). */
    private static final class SquareCell extends JComponent {
        private final int sizePx;
        private final String glyph;
        private final Function<Theme, Icon> iconFn;
        private final Consumer<Component> onClick;
        private boolean hovered;

        SquareCell(int sizePx, String glyph, Function<Theme, Icon> iconFn, String tooltip, Consumer<Component> onClick) {
            this.sizePx = sizePx;
            this.glyph = glyph;
            this.iconFn = iconFn;
            this.onClick = onClick;
            setOpaque(false);
            setToolTipText(tooltip);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && onClick != null) {
                        onClick.accept(SquareCell.this);
                    }
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(sizePx, sizePx);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.current();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                g2.setColor(hovered ? t.hover : t.surfaceAlt);
                g2.fillRect(0, 0, w - 1, h - 1);
                g2.setColor(hovered ? t.info.border() : t.border);
                g2.drawRect(0, 0, w - 1, h - 1);

                if (iconFn != null) {
                    Icon ic = iconFn.apply(t);
                    if (ic != null) {
                        ic.paintIcon(this, g2, (w - ic.getIconWidth()) / 2, (h - ic.getIconHeight()) / 2);
                    }
                } else if (glyph != null && !glyph.isEmpty()) {
                    g2.setFont(t.smallBoldFont);
                    g2.setColor(hovered ? t.fg : t.muted);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(glyph, (w - fm.stringWidth(glyph)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
