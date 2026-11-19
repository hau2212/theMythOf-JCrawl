package themythofcrawl.ui.kit;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import themythofcrawl.ui.theme.Theme;

/**
 * Crisp, minimalist, Burp-LAF-independent vector toggle switch (V_3.5_beta).
 * <p>
 * Renders an anti-aliased pill track with a clean sliding thumb and optional status label
 * ({@code Bật} / {@code Tắt}), guaranteed AA contrast in both Light and Dark themes without
 * ever exhibiting Windows/FlatLaf neon-block or underline artifacts.
 */
public final class ModernToggleSwitch extends JComponent {
    private boolean selected;
    private boolean hovered;
    private boolean compact;
    private String onLabel = "Bật";
    private String offLabel = "Tắt";
    private Function<Theme, Theme.Token> onTokenFn = t -> t.success;
    private final List<Consumer<Boolean>> listeners = new ArrayList<>();

    public ModernToggleSwitch(boolean initial) {
        this(initial, false);
    }

    public ModernToggleSwitch(boolean initial, boolean compact) {
        this.selected = initial;
        this.compact = compact;
        setOpaque(false);
        setFocusable(true);
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
            public void mousePressed(MouseEvent e) {
                if (!isEnabled() || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                toggleFromUser();
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!isEnabled()) {
                    return;
                }
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_SPACE || code == KeyEvent.VK_ENTER) {
                    toggleFromUser();
                }
            }
        });
    }

    public ModernToggleSwitch labels(String onText, String offText) {
        this.onLabel = onText == null ? "" : onText;
        this.offLabel = offText == null ? "" : offText;
        revalidate();
        repaint();
        return this;
    }

    public ModernToggleSwitch onToken(Function<Theme, Theme.Token> tokenFn) {
        if (tokenFn != null) {
            this.onTokenFn = tokenFn;
            repaint();
        }
        return this;
    }

    public ModernToggleSwitch compact(boolean compact) {
        this.compact = compact;
        revalidate();
        repaint();
        return this;
    }

    public boolean isSelected() {
        return selected;
    }

    public boolean isOn() {
        return selected;
    }

    public void setSelected(boolean value) {
        if (this.selected != value) {
            this.selected = value;
            repaint();
        }
    }

    public void setOn(boolean value, boolean notifyListeners) {
        boolean changed = this.selected != value;
        this.selected = value;
        repaint();
        if (changed && notifyListeners) {
            for (Consumer<Boolean> l : listeners) {
                l.accept(selected);
            }
        }
    }

    public void onChange(Consumer<Boolean> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void toggleFromUser() {
        selected = !selected;
        repaint();
        for (Consumer<Boolean> l : listeners) {
            l.accept(selected);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Theme th = Theme.current();
        int trackW = th.scaled(compact ? 32 : 36);
        int h = th.scaled(compact ? 20 : 24);
        if (compact || (onLabel.isEmpty() && offLabel.isEmpty())) {
            return new Dimension(trackW + th.scaled(4), h);
        }
        FontMetrics fm = getFontMetrics(th.smallBoldFont);
        int labelW = Math.max(fm.stringWidth(onLabel), fm.stringWidth(offLabel));
        return new Dimension(trackW + th.scaled(10) + labelW + th.scaled(4), h);
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
        Theme th = Theme.current();
        Theme.Token onTok = onTokenFn.apply(th);
        paintSwitch(g, th, getWidth(), getHeight(), selected, hovered, isEnabled(),
                compact, onLabel, offLabel, onTok, false);
    }

    /** Convenience overload that paints a compact switch directly at {@code (x, y, w, h)}. */
    public static void paintSwitch(
            Graphics2D g,
            Theme th,
            int x,
            int y,
            int width,
            int height,
            boolean on,
            boolean hovered,
            boolean inSelectedTableRow,
            boolean enabled) {
        Graphics2D sub = (Graphics2D) g.create();
        try {
            sub.translate(x, y);
            paintSwitch(sub, th, width, height, on, hovered, enabled, true, "", "", th.success, inSelectedTableRow);
        } finally {
            sub.dispose();
        }
    }

    /**
     * Reusable pure-Java2D switch painter (also used directly inside table cell renderers/editors
     * such as {@code RolesTab} for pixel-identical visuals across the entire Extension).
     */
    public static void paintSwitch(
            Graphics g,
            Theme th,
            int width,
            int height,
            boolean on,
            boolean hovered,
            boolean enabled,
            boolean compact,
            String onLabel,
            String offLabel,
            Theme.Token onTok,
            boolean inSelectedTableRow) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Theme.Token tok = onTok != null ? onTok : th.success;
            int trackW = th.scaled(compact ? 30 : 34);
            int trackH = th.scaled(compact ? 16 : 18);
            boolean showLabel = !compact && ((on && onLabel != null && !onLabel.isEmpty())
                    || (!on && offLabel != null && !offLabel.isEmpty()));

            FontMetrics fm = g2.getFontMetrics(th.smallBoldFont);
            int maxLblW = showLabel ? Math.max(
                    onLabel == null ? 0 : fm.stringWidth(onLabel),
                    offLabel == null ? 0 : fm.stringWidth(offLabel)) : 0;
            int gap = showLabel ? th.scaled(6) : 0;
            int totalW = trackW + gap + maxLblW;

            int x0 = Math.max(2, (width - totalW) / 2);
            int y0 = (height - trackH) / 2;

            // Track background & subtle outline
            Color trackFill;
            Color trackBorder;
            if (!enabled) {
                trackFill = th.surfaceAlt;
                trackBorder = th.border;
            } else if (on) {
                trackFill = tok.solidFill();
                trackBorder = tok.border();
            } else {
                trackFill = hovered ? th.slate.fillStrong() : (inSelectedTableRow ? th.surface : th.surfaceAlt);
                trackBorder = hovered ? th.slate.border() : th.border;
            }

            g2.setColor(trackFill);
            g2.fillRoundRect(x0, y0, trackW, trackH, trackH, trackH);
            g2.setColor(trackBorder);
            g2.drawRoundRect(x0, y0, trackW - 1, trackH - 1, trackH, trackH);

            // Clean circular thumb
            int pad = Math.max(2, th.scaled(2));
            int knobD = trackH - pad * 2;
            int knobX = on ? (x0 + trackW - pad - knobD) : (x0 + pad);
            int knobY = y0 + pad;
            Color knobFill = on ? tok.onSolid() : (enabled ? th.muted : th.border);
            g2.setColor(knobFill);
            g2.fillOval(knobX, knobY, knobD, knobD);

            // Optional crisp status text beside track
            if (showLabel) {
                String txt = on ? onLabel : offLabel;
                g2.setFont(th.smallBoldFont);
                Color txtColor;
                if (!enabled) {
                    txtColor = th.muted;
                } else if (inSelectedTableRow) {
                    txtColor = on ? tok.text() : th.selectionFg;
                } else {
                    txtColor = on ? tok.text() : th.muted;
                }
                g2.setColor(txtColor);
                int tx = x0 + trackW + gap;
                int ty = (height - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(txt, tx, ty);
            }
        } finally {
            g2.dispose();
        }
    }
}
