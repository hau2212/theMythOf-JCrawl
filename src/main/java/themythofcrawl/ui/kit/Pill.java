package themythofcrawl.ui.kit;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Function;

import javax.swing.Icon;
import javax.swing.JComponent;

import themythofcrawl.ui.theme.Theme;

/**
 * Rounded semantic pill / badge component backed by a {@link Theme.Token}. Caches dimensions and
 * updates colours automatically on Look &amp; Feel switches. Supports optional left icon and click
 * handler with hover highlight.
 */
public class Pill extends JComponent {
    private String text;
    private Function<Theme, Theme.Token> tokenSupplier;
    private Function<Theme, Icon> iconSupplier;
    private Function<Theme, Color> trailingDotSupplier;
    private Icon cachedIcon;
    private Color cachedTrailingDot;
    private Theme.Token resolved;
    private Theme resolvedTheme;
    private int radius = Theme.RADIUS_SM;
    private Insets padding = new Insets(2, 8, 2, 8);
    private double progress = -1;
    private boolean hovered;
    private boolean active;
    private boolean solidWhenActive;
    private Runnable onClick;
    private Dimension cachedPref;

    public Pill(String text) {
        this(text, t -> t.neutral, null);
    }

    public Pill(String text, Function<Theme, Theme.Token> tokenSupplier) {
        this(text, tokenSupplier, null);
    }

    public Pill(String text, Function<Theme, Theme.Token> tokenSupplier, Function<Theme, Icon> iconSupplier) {
        this.text = text == null ? "" : text;
        this.tokenSupplier = tokenSupplier != null ? tokenSupplier : t -> t.neutral;
        this.iconSupplier = iconSupplier;
        setOpaque(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (onClick != null && isEnabled()) {
                    hovered = true;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hovered) {
                    hovered = false;
                    repaint();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (onClick != null && isEnabled() && e.getButton() == MouseEvent.BUTTON1) {
                    onClick.run();
                }
            }
        });
        syncToken();
    }

    /** Convenience factory matching badge naming in UI specs. */
    public static Pill badge(String text, Function<Theme, Theme.Token> tokenSupplier) {
        Pill p = new Pill(text, tokenSupplier, null);
        p.setRadius(Theme.RADIUS_SM);
        return p;
    }

    public Pill setText(String text) {
        String next = text == null ? "" : text;
        if (!next.equals(this.text)) {
            this.text = next;
            cachedPref = null;
            revalidate();
            repaint();
        }
        return this;
    }

    public String getText() {
        return text;
    }

    public Pill setToken(Function<Theme, Theme.Token> supplier) {
        this.tokenSupplier = supplier != null ? supplier : t -> t.neutral;
        syncToken();
        repaint();
        return this;
    }

    public Pill setIcon(Function<Theme, Icon> supplier) {
        this.iconSupplier = supplier;
        syncToken();
        revalidate();
        repaint();
        return this;
    }

    /** Optional right-side status dot (e.g. dup indicator); pass {@code null} to hide. */
    public Pill setTrailingDot(Function<Theme, Color> dotColorSupplier) {
        boolean hadDot = this.trailingDotSupplier != null;
        this.trailingDotSupplier = dotColorSupplier;
        syncToken();
        if (hadDot != (dotColorSupplier != null)) {
            revalidate();
        }
        repaint();
        return this;
    }

    /** Sets optional thin progress bar along the bottom edge ({@code -1} hides, {@code 0..1} fills). */
    public Pill setProgress(double ratio) {
        double next = ratio < 0 ? -1 : Math.max(0.0, Math.min(1.0, ratio));
        if (Math.abs(this.progress - next) > 0.005 || (this.progress < 0) != (next < 0)) {
            this.progress = next;
            repaint();
        }
        return this;
    }

    public double progress() {
        return progress;
    }

    public Pill setRadius(int radius) {
        this.radius = radius;
        repaint();
        return this;
    }

    public Pill setPadding(int top, int left, int bottom, int right) {
        this.padding = new Insets(top, left, bottom, right);
        cachedPref = null;
        revalidate();
        repaint();
        return this;
    }

    public Pill setActive(boolean active) {
        if (this.active != active) {
            this.active = active;
            repaint();
        }
        return this;
    }

    public boolean isActive() {
        return active;
    }

    public Pill setSolidWhenActive(boolean solid) {
        this.solidWhenActive = solid;
        repaint();
        return this;
    }

    public Pill onClick(Runnable handler) {
        this.onClick = handler;
        setCursor(Cursor.getPredefinedCursor(handler != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        return this;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        syncToken();
    }

    private void syncToken() {
        Theme t = Theme.current();
        resolvedTheme = t;
        resolved = tokenSupplier != null ? tokenSupplier.apply(t) : t.neutral;
        if (resolved == null) {
            resolved = t.neutral;
        }
        cachedIcon = iconSupplier != null ? iconSupplier.apply(t) : null;
        cachedTrailingDot = trailingDotSupplier != null ? trailingDotSupplier.apply(t) : null;
        setFont(t.smallBoldFont);
        cachedPref = null;
    }

    private void ensureTheme() {
        Theme t = Theme.current();
        if (resolvedTheme != t) {
            syncToken();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        ensureTheme();
        if (cachedPref != null) {
            return new Dimension(cachedPref);
        }
        Font f = getFont() != null ? getFont() : resolvedTheme.smallBoldFont;
        FontMetrics fm = getFontMetrics(f);
        int iw = cachedIcon != null ? cachedIcon.getIconWidth() + (text.isEmpty() ? 0 : Theme.SPACE_1) : 0;
        int ih = cachedIcon != null ? cachedIcon.getIconHeight() : 0;
        int tw = text.isEmpty() ? 0 : fm.stringWidth(text);
        int dw = cachedTrailingDot != null ? Theme.SPACE_1 + resolvedTheme.scaled(7) : 0;
        int th = fm.getHeight();
        int hPad = Math.round((padding.left + padding.right) * resolvedTheme.scale);
        int vPad = Math.round((padding.top + padding.bottom) * resolvedTheme.scale);
        cachedPref = new Dimension(Math.max(16, iw + tw + dw + hPad), Math.max(18, Math.max(ih, th) + vPad));
        return new Dimension(cachedPref);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        ensureTheme();
        Theme.Token tok = resolved;
        Color fill;
        Color fg;
        Color border = tok.border();
        if (active && solidWhenActive) {
            fill = tok.solidFill();
            fg = tok.onSolid();
            border = tok.solidFill();
        } else if (active) {
            fill = tok.fillStrong();
            fg = tok.text();
            border = tok.solidFill();
        } else if (hovered) {
            fill = tok.fillStrong();
            fg = tok.text();
        } else {
            fill = tok.fill();
            fg = tok.text();
        }
        if (!isEnabled()) {
            fg = resolvedTheme.muted;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int arc = radius >= Theme.RADIUS_PILL ? h : Math.min(h, Math.round(radius * 2 * resolvedTheme.scale));
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);

            if (progress >= 0) {
                int margin = Math.max(4, Math.min(8, h / 3));
                int barW = Math.max(4, w - margin * 2);
                int barH = Math.max(2, resolvedTheme.scaled(2));
                int by = h - barH - 2;
                g2.setColor(resolvedTheme.border);
                g2.fillRoundRect(margin, by, barW, barH, barH, barH);
                int fillW = (int) Math.round(barW * progress);
                if (fillW > 0) {
                    g2.setColor(active && solidWhenActive ? tok.onSolid() : tok.solidFill());
                    g2.fillRoundRect(margin, by, Math.max(barH, fillW), barH, barH, barH);
                }
            }

            g2.setColor(border);
            g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);

            int cx = Math.round(padding.left * resolvedTheme.scale);
            if (cachedIcon != null) {
                int iy = (h - cachedIcon.getIconHeight()) / 2;
                cachedIcon.paintIcon(this, g2, cx, iy);
                cx += cachedIcon.getIconWidth() + Theme.SPACE_1;
            }
            if (!text.isEmpty()) {
                Font f = getFont() != null ? getFont() : resolvedTheme.smallBoldFont;
                g2.setFont(f);
                g2.setColor(fg);
                FontMetrics fm = g2.getFontMetrics();
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(text, cx, ty);
                cx += fm.stringWidth(text);
            }
            if (cachedTrailingDot != null) {
                int d = resolvedTheme.scaled(7);
                int dx = cx + Theme.SPACE_1;
                int dy = (h - d) / 2;
                g2.setColor(cachedTrailingDot);
                g2.fillOval(dx, dy, d, d);
            }
        } finally {
            g2.dispose();
        }
    }
}
