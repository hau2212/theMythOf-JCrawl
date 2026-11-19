package themythofcrawl.ui.kit;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import themythofcrawl.core.ActionEngine;
import themythofcrawl.model.ActionPolicy;
import themythofcrawl.ui.theme.Theme;

/**
 * Burp-LAF-proof HTTP Method chip selector (V_3.5_beta).
 * <p>
 * Replaces raw {@code JToggleButton} instances that rendered unreadable dark-text-on-dark-blue
 * under Burp Suite's Windows FlatLaf LAF. Every chip paints cleanly using {@link Theme} semantic
 * tokens with guaranteed AA contrast on both Light and Dark themes.
 */
public final class MethodChipBar extends JPanel {

    private final Map<String, MethodChip> chips = new LinkedHashMap<>();
    private final List<Consumer<Set<String>>> listeners = new ArrayList<>();
    private boolean suppressEvents;

    public MethodChipBar(Collection<String> initialSelected, boolean showPresets) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 2));
        setOpaque(false);
        Set<String> init = new LinkedHashSet<>();
        if (initialSelected != null) {
            for (String m : initialSelected) {
                if (m != null && !m.isBlank()) {
                    init.add(m.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        for (String method : ActionPolicy.ALL_METHODS) {
            MethodChip chip = new MethodChip(method, init.contains(method));
            chip.onToggle = () -> fireChanged();
            chips.put(method, chip);
            add(chip);
        }
        if (showPresets) {
            PresetChip onlyAction = new PresetChip("Chỉ Action",
                    "Chọn nhanh 4 phương thức thay đổi dữ liệu: POST, PUT, PATCH, DELETE",
                    () -> {
                        suppressEvents = true;
                        chips.forEach((m, c) -> c.setSelected(ActionEngine.isActionMethod(m)));
                        suppressEvents = false;
                        fireChanged();
                    });
            PresetChip allBtn = new PresetChip("Tất cả",
                    "Chọn toàn bộ HTTP Method",
                    () -> {
                        suppressEvents = true;
                        chips.values().forEach(c -> c.setSelected(true));
                        suppressEvents = false;
                        fireChanged();
                    });
            add(onlyAction);
            add(allBtn);
        }
    }

    public Set<String> selectedMethods() {
        Set<String> out = new LinkedHashSet<>();
        chips.forEach((m, c) -> {
            if (c.selected) {
                out.add(m);
            }
        });
        return out;
    }

    public void setSelectedMethods(Collection<String> methods) {
        suppressEvents = true;
        Set<String> set = new LinkedHashSet<>();
        if (methods != null) {
            for (String m : methods) {
                if (m != null && !m.isBlank()) {
                    set.add(m.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        boolean selectAll = set.isEmpty();
        chips.forEach((m, c) -> c.setSelected(selectAll || set.contains(m)));
        suppressEvents = false;
        repaint();
    }

    public void setSingleMethod(String method) {
        suppressEvents = true;
        String up = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        chips.forEach((m, c) -> c.setSelected(m.equals(up)));
        suppressEvents = false;
        repaint();
    }

    public void onChange(Consumer<Set<String>> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void fireChanged() {
        if (suppressEvents) {
            return;
        }
        Set<String> snap = selectedMethods();
        for (Consumer<Set<String>> l : listeners) {
            l.accept(snap);
        }
    }

    private static final class MethodChip extends JComponent {
        private final String method;
        private boolean selected;
        private boolean hovered;
        private Runnable onToggle;

        MethodChip(String method, boolean selected) {
            this.method = method;
            this.selected = selected;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Bấm để bật/tắt lọc HTTP " + method);
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
                public void mousePressed(MouseEvent e) {
                    if (isEnabled() && SwingUtilities.isLeftMouseButton(e)) {
                        MethodChip.this.selected = !MethodChip.this.selected;
                        repaint();
                        if (onToggle != null) {
                            onToggle.run();
                        }
                    }
                }
            });
        }

        void setSelected(boolean s) {
            if (this.selected != s) {
                this.selected = s;
                repaint();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            Theme th = Theme.current();
            FontMetrics fm = getFontMetrics(th.smallBoldFont);
            int w = fm.stringWidth(method) + th.scaled(16);
            int h = th.scaled(24);
            return new Dimension(w, h);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme th = Theme.current();
            Theme.Token tok = th.method(method);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                Color bg;
                Color bd;
                Color fg;
                if (selected) {
                    bg = tok.solidFill();
                    bd = tok.border();
                    fg = tok.onSolid();
                } else {
                    bg = hovered ? th.hover : th.surface;
                    bd = hovered ? tok.border() : th.border;
                    fg = hovered ? th.fg : th.muted;
                }
                g2.setColor(bg);
                g2.fillRect(0, 0, w, h);
                g2.setColor(bd);
                g2.drawRect(0, 0, w - 1, h - 1);

                g2.setFont(selected ? th.smallBoldFont : th.smallFont);
                FontMetrics fm = g2.getFontMetrics();
                int tx = (w - fm.stringWidth(method)) / 2;
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(fg);
                g2.drawString(method, tx, ty);
            } finally {
                g2.dispose();
            }
        }
    }

    private static final class PresetChip extends JComponent {
        private final String label;
        private boolean hovered;

        PresetChip(String label, String tip, Runnable onClick) {
            this.label = label;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(tip);
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
                public void mousePressed(MouseEvent e) {
                    if (isEnabled() && SwingUtilities.isLeftMouseButton(e) && onClick != null) {
                        onClick.run();
                    }
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            Theme th = Theme.current();
            FontMetrics fm = getFontMetrics(th.smallFont);
            return new Dimension(fm.stringWidth(label) + th.scaled(14), th.scaled(24));
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme th = Theme.current();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                g2.setColor(hovered ? th.accent.fill() : th.surfaceAlt);
                g2.fillRect(0, 0, w, h);
                g2.setColor(hovered ? th.accent.border() : th.border);
                g2.drawRect(0, 0, w - 1, h - 1);

                g2.setFont(th.smallFont);
                FontMetrics fm = g2.getFontMetrics();
                int tx = (w - fm.stringWidth(label)) / 2;
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(hovered ? th.accent.text() : th.fg);
                g2.drawString(label, tx, ty);
            } finally {
                g2.dispose();
            }
        }
    }
}
