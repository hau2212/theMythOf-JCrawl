package themythofcrawl.ui.kit;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JLabel;
import javax.swing.JPanel;

import themythofcrawl.core.Hierarchy;
import themythofcrawl.ui.Icons;
import themythofcrawl.ui.theme.Theme;

/**
 * Interactive hierarchy breadcrumb ({@code Toppage > contact > button "send"}) rendering each
 * segment as a level-coloured chip. Supports segment-click navigation and optional trailing dropdown.
 */
public class Breadcrumb extends JPanel {
    private String path = "";
    private int maxSegments = 4;
    private boolean showDropdownArrow;
    private Consumer<String> onSegmentClick;
    private Runnable onDropdown;

    public Breadcrumb() {
        super(new FlowLayout(FlowLayout.LEFT, 3, 1));
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

    @Override
    public void updateUI() {
        super.updateUI();
        if (path != null) {
            rebuild();
        }
    }

    private void rebuild() {
        removeAll();
        Theme t = Theme.current();
        List<String> parts = Hierarchy.split(path);
        int total = parts.size();
        int start = 0;
        if (total > maxSegments) {
            JLabel ellipsis = new JLabel("...");
            ellipsis.setFont(t.smallBoldFont);
            ellipsis.setForeground(t.muted);
            ellipsis.setToolTipText(path);
            if (onDropdown != null) {
                ellipsis.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                ellipsis.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (e.getButton() == MouseEvent.BUTTON1) {
                            onDropdown.run();
                        }
                    }
                });
            }
            add(ellipsis);
            add(new JLabel(Icons.chevronRight(t.muted, 10)));
            start = total - maxSegments;
        }
        for (int i = start; i < total; i++) {
            int depth = i + 1;
            String name = parts.get(i);
            String segmentPath = Hierarchy.join(parts.subList(0, depth));
            String shortName = name.length() <= 22 ? name : name.substring(0, 20) + "...";
            Pill chip = new Pill(shortName, th -> th.level(depth));
            chip.setRadius(Theme.RADIUS_SM);
            chip.setPadding(1, 7, 1, 7);
            chip.setToolTipText("Cấp " + depth + ": " + segmentPath);
            if (onSegmentClick != null) {
                chip.onClick(() -> onSegmentClick.accept(segmentPath));
            } else if (onDropdown != null) {
                chip.onClick(onDropdown);
            }
            add(chip);
            if (i < total - 1) {
                JLabel sep = new JLabel(Icons.chevronRight(t.muted, 10));
                add(sep);
            }
        }
        if (showDropdownArrow) {
            DropdownTrigger drop = new DropdownTrigger();
            add(drop);
        }
        revalidate();
        repaint();
    }

    private final class DropdownTrigger extends JLabel {
        DropdownTrigger() {
            super(Icons.themed(th -> Icons.chevronDown(th.fg, 12)));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Request mới sẽ vào: " + path + " (bấm để chọn đích)");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (onDropdown != null && e.getButton() == MouseEvent.BUTTON1) {
                        onDropdown.run();
                    }
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(20, 20);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.current();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(t.surfaceAlt);
                g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 6, 6);
                g2.setColor(t.border);
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 6, 6);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }
}
