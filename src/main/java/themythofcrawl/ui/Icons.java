package themythofcrawl.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.Map;

import javax.swing.Icon;

/** Small icons drawn in code (no emoji font needed). */
public final class Icons {
    private Icons() {
    }

    private static final Map<String, Icon> CACHE = new HashMap<>();

    private abstract static class Drawn implements Icon {
        final int w;
        final int h;

        Drawn(int w, int h) {
            this.w = w;
            this.h = h;
        }

        @Override
        public int getIconWidth() {
            return w;
        }

        @Override
        public int getIconHeight() {
            return h;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                draw(c, g2, x, y);
            } finally {
                g2.dispose();
            }
        }

        abstract void draw(Component c, Graphics2D g, int x, int y);
    }

    /** Filled circle (API / role marker) or rounded square (folder marker). */
    public static synchronized Icon dot(Color color, boolean square) {
        return CACHE.computeIfAbsent("dot" + color.getRGB() + square, k -> new Drawn(12, 10) {
            @Override
            void draw(Component c, Graphics2D g, int x, int y) {
                g.setColor(color);
                if (square) {
                    g.fillRoundRect(x + 1, y, 10, 10, 4, 4);
                } else {
                    g.fillOval(x + 1, y, 10, 10);
                }
            }
        });
    }

    /** 'Two sheets' copy glyph. */
    public static synchronized Icon copy(Color color) {
        return CACHE.computeIfAbsent("copy" + color.getRGB(), k -> new Drawn(14, 14) {
            @Override
            void draw(Component c, Graphics2D g, int x, int y) {
                g.setColor(color);
                g.drawRoundRect(x + 1, y + 1, 8, 9, 3, 3);
                Color bg = c != null ? c.getBackground() : null;
                g.setColor(bg != null ? bg : Color.WHITE);
                g.fillRoundRect(x + 4, y + 4, 8, 9, 3, 3);
                g.setColor(color);
                g.drawRoundRect(x + 4, y + 4, 8, 9, 3, 3);
                g.drawLine(x + 6, y + 7, x + 10, y + 7);
                g.drawLine(x + 6, y + 10, x + 10, y + 10);
            }
        });
    }

    /** Circled 'i'. */
    public static synchronized Icon info(Color color) {
        return CACHE.computeIfAbsent("info" + color.getRGB(), k -> new Drawn(14, 14) {
            @Override
            void draw(Component c, Graphics2D g, int x, int y) {
                g.setColor(color);
                g.drawOval(x + 1, y + 1, 11, 11);
                g.fillRect(x + 6, y + 6, 2, 4);
                g.fillRect(x + 6, y + 3, 2, 2);
            }
        });
    }

    /** API row marker in the trees: audit dot + copy glyph (the glyph copies 'STT. path'). */
    public static final int GROUP_COPY_X = 14;

    public static synchronized Icon group(Color dotColor, Color copyColor) {
        String key = "grp" + dotColor.getRGB() + "/" + copyColor.getRGB();
        Icon have = CACHE.get(key);
        if (have != null) {
            return have;
        }
        Icon dot = dot(dotColor, false);   // filled first: no nested computeIfAbsent on the same map
        Icon cp = copy(copyColor);
        Icon made = new Drawn(GROUP_COPY_X + 16, 14) {
            @Override
            void draw(Component c, Graphics2D g, int x, int y) {
                dot.paintIcon(c, g, x, y + 2);
                cp.paintIcon(c, g, x + GROUP_COPY_X + 1, y);
            }
        };
        CACHE.put(key, made);
        return made;
    }
}
