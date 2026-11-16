package themythofcrawl.ui.kit;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntConsumer;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;

import themythofcrawl.ui.theme.Theme;

/**
 * Compact segmented switch for 2–4 mutually exclusive options (e.g. CHỈ CRAWL | GỬI, Thẻ | Ma trận | Văn bản).
 * Each segment can specify an active semantic {@link Theme.Token} and dynamic Java2D icon.
 */
public class SegmentedToggle extends JPanel {
    public static final class Option {
        final String label;
        final String tooltip;
        final Function<Theme, Theme.Token> activeToken;
        final BiFunction<Theme, Boolean, Icon> stateIconFn;
        final boolean softFill;

        public Option(String label, String tooltip, Function<Theme, Theme.Token> activeToken, Function<Theme, Icon> iconFn) {
            this(label, tooltip, activeToken, iconFn == null ? null : (t, sel) -> iconFn.apply(t), false);
        }

        public Option(String label, String tooltip, Function<Theme, Theme.Token> activeToken,
                      BiFunction<Theme, Boolean, Icon> stateIconFn, boolean softFill) {
            this.label = label;
            this.tooltip = tooltip;
            this.activeToken = activeToken != null ? activeToken : t -> t.accent;
            this.stateIconFn = stateIconFn;
            this.softFill = softFill;
        }
    }

    private final List<SegmentButton> buttons = new ArrayList<>();
    private final List<Option> options = new ArrayList<>();
    private int selectedIndex;
    private IntConsumer onChange;
    private boolean suppressEvents;

    public SegmentedToggle(Option... opts) {
        super(new FlowLayout(FlowLayout.LEFT, 2, 2));
        setOpaque(false);
        for (Option opt : opts) {
            addOption(opt);
        }
        applyThemeBorder();
    }

    public SegmentedToggle(String... labels) {
        this(toOptions(labels));
    }

    private static Option[] toOptions(String... labels) {
        Option[] arr = new Option[labels.length];
        for (int i = 0; i < labels.length; i++) {
            arr[i] = new Option(labels[i], null, null, (Function<Theme, Icon>) null);
        }
        return arr;
    }

    public SegmentedToggle addOption(String label, Function<Theme, Theme.Token> activeToken, Function<Theme, Icon> iconFn) {
        return addOption(new Option(label, null, activeToken, iconFn));
    }

    public SegmentedToggle addOption(Option opt) {
        int idx = options.size();
        options.add(opt);
        SegmentButton b = new SegmentButton(idx, opt);
        buttons.add(b);
        add(b);
        if (idx == 0) {
            b.setSelected(true);
        }
        return this;
    }

    public SegmentedToggle onChange(IntConsumer listener) {
        this.onChange = listener;
        return this;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public void setSelectedIndex(int idx) {
        setSelectedIndex(idx, false);
    }

    public void setSelectedIndex(int idx, boolean fireEvent) {
        if (idx < 0 || idx >= buttons.size()) {
            return;
        }
        boolean changed = selectedIndex != idx;
        selectedIndex = idx;
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).setSelected(i == idx);
        }
        repaint();
        if (fireEvent && changed && !suppressEvents && onChange != null) {
            onChange.accept(idx);
        }
    }

    public void setOptionText(int idx, String text) {
        if (idx >= 0 && idx < buttons.size()) {
            buttons.get(idx).setLabelText(text);
        }
    }

    public void setOptionTooltip(int idx, String tip) {
        if (idx >= 0 && idx < buttons.size()) {
            buttons.get(idx).setToolTipText(tip);
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (buttons != null) {
            applyThemeBorder();
            for (SegmentButton b : buttons) {
                b.refreshMetrics();
            }
        }
    }

    private void applyThemeBorder() {
        setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Theme t = Theme.current();
        int arc = Math.round((Theme.RADIUS_SM + 2) * 2 * t.scale);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(t.surfaceAlt);
            g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
            g2.setColor(t.border);
            g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }

    private final class SegmentButton extends JComponent {
        private final int idx;
        private final Option opt;
        private String label;
        private boolean selected;
        private boolean hovered;
        private Dimension pref;

        SegmentButton(int idx, Option opt) {
            this.idx = idx;
            this.opt = opt;
            this.label = opt.label == null ? "" : opt.label;
            setToolTipText(opt.tooltip);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    if (isEnabled() && SegmentedToggle.this.isEnabled()) {
                        hovered = true;
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && isEnabled() && SegmentedToggle.this.isEnabled()) {
                        setSelectedIndex(idx, true);
                    }
                }
            });
            refreshMetrics();
        }

        void setSelected(boolean s) {
            if (this.selected != s) {
                this.selected = s;
                pref = null;
                repaint();
            }
        }

        void setLabelText(String text) {
            String next = text == null ? "" : text;
            if (!next.equals(label)) {
                label = next;
                pref = null;
                revalidate();
                repaint();
            }
        }

        void refreshMetrics() {
            pref = null;
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            if (pref != null) {
                return new Dimension(pref);
            }
            Theme t = Theme.current();
            Font f = selected ? t.smallBoldFont : t.smallFont;
            FontMetrics fm = getFontMetrics(f);
            Icon ic = opt.stateIconFn != null ? opt.stateIconFn.apply(t, selected) : null;
            int iw = ic != null ? ic.getIconWidth() + (label.isEmpty() ? 0 : Theme.SPACE_1) : 0;
            int ih = ic != null ? ic.getIconHeight() : 0;
            Insets pad = new Insets(Math.round(2 * t.scale), Math.round(8 * t.scale),
                    Math.round(2 * t.scale), Math.round(8 * t.scale));
            int w = iw + (label.isEmpty() ? 0 : fm.stringWidth(label)) + pad.left + pad.right;
            int h = Math.max(ih, fm.getHeight()) + pad.top + pad.bottom;
            pref = new Dimension(Math.max(24, w), Math.max(20, h));
            return new Dimension(pref);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.current();
            Theme.Token tok = opt.activeToken.apply(t);
            Color fill = selected ? (opt.softFill ? tok.fillStrong() : tok.solidFill()) : (hovered ? t.hover : null);
            Color fg = selected ? (opt.softFill ? tok.text() : tok.onSolid()) : (hovered ? t.fg : t.muted);
            int arc = Math.round(Theme.RADIUS_SM * 2 * t.scale);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                if (fill != null) {
                    g2.setColor(fill);
                    g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                    if (selected && opt.softFill) {
                        g2.setColor(tok.border());
                        g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
                    }
                }
                int x = Math.round(8 * t.scale);
                Icon ic = opt.stateIconFn != null ? opt.stateIconFn.apply(t, selected) : null;
                if (ic != null) {
                    ic.paintIcon(this, g2, x, (h - ic.getIconHeight()) / 2);
                    x += ic.getIconWidth() + Theme.SPACE_1;
                }
                if (!label.isEmpty()) {
                    Font f = selected ? t.smallBoldFont : t.smallFont;
                    g2.setFont(f);
                    g2.setColor(fg);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(label, x, (h - fm.getHeight()) / 2 + fm.getAscent());
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
