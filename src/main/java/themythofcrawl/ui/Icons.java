package themythofcrawl.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.swing.Icon;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.ui.theme.Theme;

/**
 * Resolution-independent Java2D icons parameterized by colour (zero dependency on OS emoji fonts).
 */
public final class Icons {
    private Icons() {
    }

    private static final int MAX_CACHE = 1024;
    private static final Map<String, Icon> CACHE = new HashMap<>();
    private static final Stroke STROKE_12 = new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke STROKE_16 = new BasicStroke(1.65f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    static {
        Theme.addListener(Icons::clearCache);
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

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
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                draw(c, g2, x, y);
            } finally {
                g2.dispose();
            }
        }

        abstract void draw(Component c, Graphics2D g, int x, int y);
    }

    @FunctionalInterface
    private interface Painter {
        void paint(Component c, Graphics2D g, int x, int y, int size, Color color);
    }

    private static synchronized Icon cached(String name, Color color, int size, Painter p) {
        Color col = color != null ? color : Theme.current().fg;
        String key = name + "|" + size + "|" + col.getRGB();
        Icon have = CACHE.get(key);
        if (have != null) {
            return have;
        }
        if (CACHE.size() > MAX_CACHE) {
            CACHE.clear();
        }
        Icon made = new Drawn(size, size) {
            @Override
            void draw(Component c, Graphics2D g, int x, int y) {
                g.setColor(col);
                g.setStroke(STROKE_12);
                p.paint(c, g, x, y, size, col);
            }
        };
        CACHE.put(key, made);
        return made;
    }

    /** Dynamic icon whose colour / appearance resolves from {@link Theme#current()} on every repaint. */
    public static Icon themed(Function<Theme, Icon> resolver) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                resolver.apply(Theme.current()).paintIcon(c, g, x, y);
            }

            @Override
            public int getIconWidth() {
                return resolver.apply(Theme.current()).getIconWidth();
            }

            @Override
            public int getIconHeight() {
                return resolver.apply(Theme.current()).getIconHeight();
            }
        };
    }

    // ------------------------------------------------------------------ core & tree icons
    /** Filled circle (API / role marker) or rounded square (folder marker). */
    public static synchronized Icon dot(Color color, boolean square) {
        Color col = color != null ? color : Theme.current().dotNone;
        String key = "dot|" + col.getRGB() + "|" + square;
        Icon have = CACHE.get(key);
        if (have != null) {
            return have;
        }
        if (CACHE.size() > MAX_CACHE) {
            CACHE.clear();
        }
        Icon made = new Drawn(12, 10) {
            @Override
            void draw(Component c, Graphics2D g, int x, int y) {
                g.setColor(col);
                if (square) {
                    g.fillRoundRect(x + 1, y, 10, 10, 4, 4);
                } else {
                    g.fillOval(x + 1, y, 10, 10);
                }
            }
        };
        CACHE.put(key, made);
        return made;
    }

    /** Crisp status dot of given diameter (e.g. 8 px). */
    public static Icon statusDot(Color color, int size) {
        return cached("sdot", color, size, (c, g, x, y, s, col) -> g.fillOval(x + 1, y + 1, Math.max(4, s - 2), Math.max(4, s - 2)));
    }

    /** Open / filled radio circle (for menu options). */
    public static Icon circle(Color color, boolean filled) {
        return cached(filled ? "circF" : "circO", color, 12, (c, g, x, y, s, col) -> {
            if (filled) {
                g.fillOval(x + 2, y + 2, 8, 8);
            } else {
                g.drawOval(x + 2, y + 2, 7, 7);
            }
        });
    }

    /** 'Two sheets' copy glyph. */
    public static synchronized Icon copy(Color color) {
        Color col = color != null ? color : Theme.current().muted;
        String key = "copy|" + col.getRGB();
        Icon have = CACHE.get(key);
        if (have != null) {
            return have;
        }
        Icon made = new Drawn(14, 14) {
            @Override
            void draw(Component c, Graphics2D g, int x, int y) {
                g.setColor(col);
                g.drawRoundRect(x + 1, y + 1, 8, 9, 3, 3);
                Color bg = c != null ? c.getBackground() : null;
                g.setColor(bg != null ? bg : Theme.current().surface);
                g.fillRoundRect(x + 4, y + 4, 8, 9, 3, 3);
                g.setColor(col);
                g.drawRoundRect(x + 4, y + 4, 8, 9, 3, 3);
                g.drawLine(x + 6, y + 7, x + 10, y + 7);
                g.drawLine(x + 6, y + 10, x + 10, y + 10);
            }
        };
        CACHE.put(key, made);
        return made;
    }

    /** API row marker in the trees: audit dot + copy glyph (the glyph copies 'STT. path'). */
    public static final int GROUP_COPY_X = 14;

    public static synchronized Icon group(Color dotColor, Color copyColor) {
        Color dc = dotColor != null ? dotColor : Theme.current().dotNone;
        Color cc = copyColor != null ? copyColor : Theme.current().muted;
        String key = "grp" + dc.getRGB() + "/" + cc.getRGB();
        Icon have = CACHE.get(key);
        if (have != null) {
            return have;
        }
        Icon dot = dot(dc, false);
        Icon cp = copy(cc);
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

    // ------------------------------------------------------------------ full 14/16 px set
    public static Icon send(Color color) {
        return send(color, 14);
    }

    public static Icon send(Color color, int size) {
        return cached("send", color, size, (c, g, x, y, s, col) -> {
            int p = s / 4;
            int[] xs = {x + p + 1, x + s - p, x + p + 1};
            int[] ys = {y + p - 1, y + s / 2, y + s - p + 1};
            g.fillPolygon(xs, ys, 3);
        });
    }

    public static Icon sendAll(Color color) {
        return cached("sendAll", color, 14, (c, g, x, y, s, col) -> {
            g.fillPolygon(new int[] {x + 2, x + 7, x + 2}, new int[] {y + 3, y + 7, y + 11}, 3);
            g.fillPolygon(new int[] {x + 7, x + 12, x + 7}, new int[] {y + 3, y + 7, y + 11}, 3);
        });
    }

    public static Icon stop(Color color) {
        return cached("stop", color, 14, (c, g, x, y, s, col) -> g.fillRoundRect(x + 3, y + 3, 8, 8, 2, 2));
    }

    public static Icon repeater(Color color) {
        return repeater(color, 14);
    }

    public static Icon repeater(Color color, int size) {
        return cached("repeater", color, size, (c, g, x, y, s, col) -> {
            g.drawRoundRect(x + 2, y + 4, s - 6, s - 6, 3, 3);
            g.drawLine(x + s / 2 - 1, y + s / 2 + 1, x + s - 2, y + 2);
            g.drawLine(x + s - 6, y + 2, x + s - 2, y + 2);
            g.drawLine(x + s - 2, y + 2, x + s - 2, y + 6);
        });
    }

    public static Icon folder(Color color) {
        return folder(color, 14);
    }

    public static Icon folder(Color color, int size) {
        return cached("folder", color, size, (c, g, x, y, s, col) -> {
            Path2D p = new Path2D.Float();
            p.moveTo(x + 1.5, y + 3.5);
            p.lineTo(x + 5.5, y + 3.5);
            p.lineTo(x + 7.0, y + 5.0);
            p.lineTo(x + s - 1.5, y + 5.0);
            p.lineTo(x + s - 1.5, y + s - 2.5);
            p.lineTo(x + 1.5, y + s - 2.5);
            p.closePath();
            g.draw(p);
        });
    }

    public static Icon api(Color color) {
        return api(color, 14);
    }

    public static Icon api(Color color, int size) {
        return cached("api", color, size, (c, g, x, y, s, col) -> {
            int m = s / 2;
            g.drawOval(x + 2, y + 2, s - 4, s - 4);
            g.fillOval(x + m - 2, y + m - 2, 4, 4);
        });
    }

    public static Icon role(Color color) {
        return role(color, 14);
    }

    public static Icon role(Color color, int size) {
        return cached("role", color, size, (c, g, x, y, s, col) -> {
            g.drawOval(x + s / 2 - 2, y + 2, 5, 5);
            g.draw(new Arc2D.Float(x + 2.5f, y + 8.5f, s - 5f, s - 5f, 0, 180, Arc2D.OPEN));
        });
    }

    public static Icon check(Color color) {
        return check(color, 14);
    }

    public static Icon check(Color color, int size) {
        return cached("check", color, size, (c, g, x, y, s, col) -> {
            g.setStroke(STROKE_16);
            Path2D p = new Path2D.Float();
            p.moveTo(x + s * 0.20, y + s * 0.54);
            p.lineTo(x + s * 0.43, y + s * 0.76);
            p.lineTo(x + s * 0.82, y + s * 0.28);
            g.draw(p);
        });
    }

    public static Icon warn(Color color) {
        return warn(color, 14);
    }

    public static Icon warn(Color color, int size) {
        return cached("warn", color, size, (c, g, x, y, s, col) -> {
            Path2D p = new Path2D.Float();
            p.moveTo(x + s / 2.0, y + 1.5);
            p.lineTo(x + s - 1.5, y + s - 2.0);
            p.lineTo(x + 1.5, y + s - 2.0);
            p.closePath();
            g.draw(p);
            int mx = x + s / 2;
            g.drawLine(mx, y + 5, mx, y + s - 6);
            g.fillRect(mx - 1, y + s - 4, 2, 2);
        });
    }

    /** Crisp '!' alert mark inside rounded diamond/badge for INFO divergence status. */
    public static Icon alertMark(Color color, int size) {
        return cached("alert", color, size, (c, g, x, y, s, col) -> {
            g.drawOval(x + 1, y + 1, s - 3, s - 3);
            int mx = x + s / 2;
            g.drawLine(mx, y + 4, mx, y + s - 6);
            g.fillRect(mx - 1, y + s - 4, 2, 2);
        });
    }

    public static Icon error(Color color) {
        return error(color, 14);
    }

    public static Icon error(Color color, int size) {
        return cached("error", color, size, (c, g, x, y, s, col) -> {
            g.setStroke(STROKE_16);
            int m = Math.max(3, s / 4);
            g.drawLine(x + m, y + m, x + s - m, y + s - m);
            g.drawLine(x + s - m, y + m, x + m, y + s - m);
        });
    }

    public static Icon info(Color color) {
        return info(color, 14);
    }

    public static Icon info(Color color, int size) {
        return cached("info", color, size, (c, g, x, y, s, col) -> {
            g.drawOval(x + 1, y + 1, s - 3, s - 3);
            int mx = x + s / 2;
            g.fillRect(mx - 1, y + 6, 2, Math.max(3, s - 10));
            g.fillRect(mx - 1, y + 3, 2, 2);
        });
    }

    public static Icon redirect(Color color) {
        return redirect(color, 14);
    }

    public static Icon redirect(Color color, int size) {
        return cached("redir", color, size, (c, g, x, y, s, col) -> {
            g.setStroke(STROKE_16);
            g.drawLine(x + 3, y + s - 3, x + s - 3, y + 3);
            g.drawLine(x + s - 8, y + 3, x + s - 3, y + 3);
            g.drawLine(x + s - 3, y + 3, x + s - 3, y + 8);
        });
    }

    public static Icon hourglass(Color color) {
        return hourglass(color, 14);
    }

    public static Icon hourglass(Color color, int size) {
        return cached("hourglass", color, size, (c, g, x, y, s, col) -> {
            g.drawLine(x + 3, y + 2, x + s - 3, y + 2);
            g.drawLine(x + 3, y + s - 2, x + s - 3, y + s - 2);
            Path2D p = new Path2D.Float();
            p.moveTo(x + 3.5, y + 2);
            p.lineTo(x + s - 3.5, y + 2);
            p.lineTo(x + s / 2.0, y + s / 2.0);
            p.lineTo(x + 3.5, y + s - 2);
            p.lineTo(x + s - 3.5, y + s - 2);
            p.lineTo(x + s / 2.0, y + s / 2.0);
            p.closePath();
            g.draw(p);
        });
    }

    public static Icon ghost(Color color) {
        return ghost(color, 14);
    }

    public static Icon ghost(Color color, int size) {
        return cached("ghost", color, size, (c, g, x, y, s, col) -> {
            Path2D p = new Path2D.Float();
            p.moveTo(x + 3, y + s - 2);
            p.lineTo(x + 3, y + 6);
            p.quadTo(x + s / 2.0, y + 1, x + s - 3, y + 6);
            p.lineTo(x + s - 3, y + s - 2);
            p.lineTo(x + s - 5, y + s - 4);
            p.lineTo(x + s / 2.0, y + s - 2);
            p.lineTo(x + 5, y + s - 4);
            p.closePath();
            g.draw(p);
            g.fillOval(x + 5, y + 5, 2, 2);
            g.fillOval(x + s - 7, y + 5, 2, 2);
        });
    }

    public static Icon dup(Color color) {
        return dup(color, 14);
    }

    public static Icon dup(Color color, int size) {
        return cached("dup", color, size, (c, g, x, y, s, col) -> {
            g.drawRoundRect(x + 1, y + 1, s - 6, s - 6, 2, 2);
            g.drawRoundRect(x + 5, y + 5, s - 6, s - 6, 2, 2);
        });
    }

    public static Icon hide(Color color) {
        return hide(color, 14);
    }

    public static Icon hide(Color color, int size) {
        return cached("hide", color, size, (c, g, x, y, s, col) -> {
            g.draw(new Arc2D.Float(x + 1.5f, y + 3.5f, s - 3f, s - 7f, 0, 180, Arc2D.OPEN));
            g.draw(new Arc2D.Float(x + 1.5f, y + 3.5f, s - 3f, s - 7f, 180, 180, Arc2D.OPEN));
            g.drawLine(x + 2, y + s - 2, x + s - 2, y + 2);
        });
    }

    public static Icon show(Color color) {
        return show(color, 14);
    }

    public static Icon show(Color color, int size) {
        return cached("show", color, size, (c, g, x, y, s, col) -> {
            g.draw(new Arc2D.Float(x + 1.5f, y + 3.5f, s - 3f, s - 7f, 0, 180, Arc2D.OPEN));
            g.draw(new Arc2D.Float(x + 1.5f, y + 3.5f, s - 3f, s - 7f, 180, 180, Arc2D.OPEN));
            g.fillOval(x + s / 2 - 2, y + s / 2 - 2, 4, 4);
        });
    }

    public static Icon trash(Color color) {
        return trash(color, 14);
    }

    public static Icon trash(Color color, int size) {
        return cached("trash", color, size, (c, g, x, y, s, col) -> {
            g.drawLine(x + 2, y + 3, x + s - 2, y + 3);
            g.drawRect(x + 5, y + 1, s - 10, 2);
            g.drawRoundRect(x + 3, y + 4, s - 6, s - 6, 2, 2);
            g.drawLine(x + 6, y + 6, x + 6, y + s - 4);
            g.drawLine(x + s - 6, y + 6, x + s - 6, y + s - 4);
        });
    }

    public static Icon move(Color color) {
        return move(color, 14);
    }

    public static Icon move(Color color, int size) {
        return cached("move", color, size, (c, g, x, y, s, col) -> {
            g.draw(new Arc2D.Float(x + 2f, y + 5f, s - 6f, s - 6f, 90, 120, Arc2D.OPEN));
            g.drawLine(x + 4, y + 5, x + s - 3, y + 5);
            g.drawLine(x + s - 6, y + 2, x + s - 3, y + 5);
            g.drawLine(x + s - 6, y + 8, x + s - 3, y + 5);
        });
    }

    public static Icon undo(Color color) {
        return undo(color, 14);
    }

    public static Icon undo(Color color, int size) {
        return cached("undo", color, size, (c, g, x, y, s, col) -> {
            g.draw(new Arc2D.Float(x + 4f, y + 5f, s - 6f, s - 6f, 90, -120, Arc2D.OPEN));
            g.drawLine(x + 3, y + 5, x + s - 4, y + 5);
            g.drawLine(x + 6, y + 2, x + 3, y + 5);
            g.drawLine(x + 6, y + 8, x + 3, y + 5);
        });
    }

    public static Icon filter(Color color) {
        return filter(color, 14);
    }

    public static Icon filter(Color color, int size) {
        return cached("filter", color, size, (c, g, x, y, s, col) -> {
            Path2D p = new Path2D.Float();
            p.moveTo(x + 2, y + 3);
            p.lineTo(x + s - 2, y + 3);
            p.lineTo(x + s / 2.0 + 1.5, y + 7.5);
            p.lineTo(x + s / 2.0 + 1.5, y + s - 2);
            p.lineTo(x + s / 2.0 - 1.5, y + s - 3);
            p.lineTo(x + s / 2.0 - 1.5, y + 7.5);
            p.closePath();
            g.draw(p);
        });
    }

    public static Icon export(Color color) {
        return export(color, 14);
    }

    public static Icon export(Color color, int size) {
        return cached("export", color, size, (c, g, x, y, s, col) -> {
            g.drawLine(x + s / 2, y + 2, x + s / 2, y + 9);
            g.drawLine(x + s / 2 - 3, y + 5, x + s / 2, y + 2);
            g.drawLine(x + s / 2 + 3, y + 5, x + s / 2, y + 2);
            g.drawLine(x + 2, y + 9, x + 2, y + s - 2);
            g.drawLine(x + 2, y + s - 2, x + s - 2, y + s - 2);
            g.drawLine(x + s - 2, y + s - 2, x + s - 2, y + 9);
        });
    }

    public static Icon excel(Color color) {
        return excel(color, 14);
    }

    public static Icon excel(Color color, int size) {
        return cached("excel", color, size, (c, g, x, y, s, col) -> {
            g.drawRoundRect(x + 2, y + 2, s - 4, s - 4, 2, 2);
            g.drawLine(x + 2, y + 6, x + s - 2, y + 6);
            g.drawLine(x + 6, y + 2, x + 6, y + s - 2);
        });
    }

    public static Icon document(Color color) {
        return cached("doc", color, 14, (c, g, x, y, s, col) -> {
            g.drawRoundRect(x + 3, y + 1, s - 6, s - 2, 2, 2);
            g.drawLine(x + 5, y + 5, x + s - 5, y + 5);
            g.drawLine(x + 5, y + 8, x + s - 5, y + 8);
            g.drawLine(x + 5, y + 11, x + s - 6, y + 11);
        });
    }

    public static Icon save(Color color) {
        return cached("save", color, 14, (c, g, x, y, s, col) -> {
            g.drawRoundRect(x + 2, y + 2, s - 4, s - 4, 2, 2);
            g.drawRect(x + 4, y + 2, s - 8, 4);
            g.drawRect(x + 4, y + 8, s - 8, 4);
        });
    }

    public static Icon window(Color color) {
        return window(color, 14);
    }

    public static Icon window(Color color, int size) {
        return cached("window", color, size, (c, g, x, y, s, col) -> {
            g.drawRoundRect(x + 2, y + 4, s - 6, s - 6, 2, 2);
            g.drawLine(x + 5, y + 2, x + s - 2, y + 2);
            g.drawLine(x + s - 2, y + 2, x + s - 2, y + s - 5);
        });
    }

    public static Icon pin(Color color) {
        return pin(color, 14);
    }

    public static Icon pin(Color color, int size) {
        return cached("pin", color, size, (c, g, x, y, s, col) -> {
            g.drawLine(x + 4, y + 2, x + s - 4, y + 2);
            g.drawLine(x + 5, y + 2, x + 4, y + 7);
            g.drawLine(x + s - 5, y + 2, x + s - 4, y + 7);
            g.drawLine(x + 3, y + 7, x + s - 3, y + 7);
            g.drawLine(x + s / 2, y + 7, x + s / 2, y + s - 1);
        });
    }

    public static Icon gear(Color color) {
        return gear(color, 14);
    }

    public static Icon gear(Color color, int size) {
        return cached("gear", color, size, (c, g, x, y, s, col) -> {
            int cx = x + s / 2;
            int cy = y + s / 2;
            g.drawOval(cx - 3, cy - 3, 6, 6);
            g.drawOval(cx - 1, cy - 1, 2, 2);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4.0;
                int x1 = cx + (int) Math.round(Math.cos(a) * 3.8);
                int y1 = cy + (int) Math.round(Math.sin(a) * 3.8);
                int x2 = cx + (int) Math.round(Math.cos(a) * 5.6);
                int y2 = cy + (int) Math.round(Math.sin(a) * 5.6);
                g.drawLine(x1, y1, x2, y2);
            }
        });
    }

    public static Icon refresh(Color color) {
        return refresh(color, 14);
    }

    public static Icon refresh(Color color, int size) {
        return cached("refresh", color, size, (c, g, x, y, s, col) -> {
            g.draw(new Arc2D.Float(x + 2, y + 2, s - 4, s - 4, 30, 290, Arc2D.OPEN));
            g.fillPolygon(new int[] {x + s - 4, x + s - 1, x + s - 5}, new int[] {y + 2, y + 5, y + 6}, 3);
        });
    }

    public static Icon plus(Color color) {
        return plus(color, 14);
    }

    public static Icon plus(Color color, int size) {
        return cached("plus", color, size, (c, g, x, y, s, col) -> {
            g.setStroke(STROKE_16);
            int m = s / 2;
            g.drawLine(x + m, y + 3, x + m, y + s - 3);
            g.drawLine(x + 3, y + m, x + s - 3, y + m);
        });
    }

    public static Icon minus(Color color) {
        return minus(color, 14);
    }

    public static Icon minus(Color color, int size) {
        return cached("minus", color, size, (c, g, x, y, s, col) -> {
            g.setStroke(STROKE_16);
            int m = s / 2;
            g.drawLine(x + 3, y + m, x + s - 3, y + m);
        });
    }

    public static Icon search(Color color) {
        return search(color, 14);
    }

    public static Icon search(Color color, int size) {
        return cached("search", color, size, (c, g, x, y, s, col) -> {
            int r = s - 6;
            g.drawOval(x + 2, y + 2, r, r);
            g.setStroke(STROKE_16);
            g.drawLine(x + r, y + r, x + s - 2, y + s - 2);
        });
    }

    public static Icon target(Color color) {
        return cached("target", color, 14, (c, g, x, y, s, col) -> {
            g.drawOval(x + 2, y + 2, s - 4, s - 4);
            g.drawOval(x + 5, y + 5, s - 10, s - 10);
            g.fillOval(x + 6, y + 6, 2, 2);
        });
    }

    public static Icon sliders(Color color) {
        return cached("sliders", color, 14, (c, g, x, y, s, col) -> {
            g.drawLine(x + 2, y + 4, x + s - 2, y + 4);
            g.drawLine(x + 2, y + 10, x + s - 2, y + 10);
            g.fillOval(x + 4, y + 2, 4, 4);
            g.fillOval(x + 7, y + 8, 4, 4);
        });
    }

    public static Icon edit(Color color) {
        return edit(color, 14);
    }

    public static Icon edit(Color color, int size) {
        return cached("edit", color, size, (c, g, x, y, s, col) -> {
            g.drawLine(x + 3, y + s - 3, x + s - 3, y + 3);
            g.drawLine(x + 2, y + s - 2, x + 5, y + s - 3);
            g.drawLine(x + s - 4, y + 2, x + s - 2, y + 4);
        });
    }

    public static Icon branch(Color color) {
        return cached("branch", color, 14, (c, g, x, y, s, col) -> {
            g.drawLine(x + 4, y + 3, x + 4, y + s - 3);
            g.drawLine(x + 4, y + 7, x + 10, y + 7);
            g.drawLine(x + 10, y + 7, x + 10, y + 4);
            g.fillOval(x + 2, y + 2, 4, 4);
            g.fillOval(x + 2, y + s - 5, 4, 4);
            g.fillOval(x + 8, y + 2, 4, 4);
        });
    }

    public static Icon arrowUp(Color color) {
        return cached("up", color, 14, (c, g, x, y, s, col) -> {
            g.drawLine(x + s / 2, y + 3, x + s / 2, y + s - 3);
            g.drawLine(x + s / 2 - 3, y + 6, x + s / 2, y + 3);
            g.drawLine(x + s / 2 + 3, y + 6, x + s / 2, y + 3);
        });
    }

    public static Icon arrowDown(Color color) {
        return cached("down", color, 14, (c, g, x, y, s, col) -> {
            g.drawLine(x + s / 2, y + 3, x + s / 2, y + s - 3);
            g.drawLine(x + s / 2 - 3, y + s - 6, x + s / 2, y + s - 3);
            g.drawLine(x + s / 2 + 3, y + s - 6, x + s / 2, y + s - 3);
        });
    }

    public static Icon arrowRight(Color color) {
        return cached("right", color, 14, (c, g, x, y, s, col) -> {
            g.drawLine(x + 3, y + s / 2, x + s - 3, y + s / 2);
            g.drawLine(x + s - 6, y + s / 2 - 3, x + s - 3, y + s / 2);
            g.drawLine(x + s - 6, y + s / 2 + 3, x + s - 3, y + s / 2);
        });
    }

    public static Icon compass(Color color) {
        return cached("compass", color, 14, (c, g, x, y, s, col) -> {
            g.drawOval(x + 2, y + 2, s - 4, s - 4);
            g.drawLine(x + 5, y + s - 5, x + s - 5, y + 5);
            g.fillOval(x + 6, y + 6, 2, 2);
        });
    }

    public static Icon clock(Color color) {
        return cached("clock", color, 14, (c, g, x, y, s, col) -> {
            g.drawOval(x + 2, y + 2, s - 4, s - 4);
            g.drawLine(x + s / 2, y + 4, x + s / 2, y + s / 2);
            g.drawLine(x + s / 2, y + s / 2, x + s - 4, y + s / 2);
        });
    }

    public static Icon bolt(Color color) {
        return cached("bolt", color, 14, (c, g, x, y, s, col) -> {
            int[] xs = {x + 8, x + 3, x + 7, x + 6, x + 11, x + 7};
            int[] ys = {y + 1, y + 8, y + 8, y + 13, y + 6, y + 6};
            g.drawPolygon(xs, ys, 6);
        });
    }

    public static Icon globe(Color color) {
        return cached("globe", color, 14, (c, g, x, y, s, col) -> {
            g.drawOval(x + 2, y + 2, s - 4, s - 4);
            g.drawOval(x + 5, y + 2, s - 10, s - 4);
            g.drawLine(x + 2, y + s / 2, x + s - 2, y + s / 2);
        });
    }

    public static Icon clean(Color color) {
        return cached("clean", color, 14, (c, g, x, y, s, col) -> {
            g.drawLine(x + 3, y + 4, x + s - 3, y + 4);
            g.drawLine(x + 2, y + 7, x + s - 2, y + 7);
            g.drawLine(x + 4, y + 10, x + s - 4, y + 10);
        });
    }

    public static Icon chevronDown(Color color) {
        return chevronDown(color, 12);
    }

    public static Icon chevronDown(Color color, int size) {
        return cached("chevD", color, size, (c, g, x, y, s, col) -> {
            g.setStroke(STROKE_16);
            int midX = x + s / 2;
            int topY = y + s / 3;
            int botY = y + (s * 2) / 3;
            g.drawLine(x + 2, topY, midX, botY);
            g.drawLine(midX, botY, x + s - 2, topY);
        });
    }

    public static Icon chevronRight(Color color) {
        return chevronRight(color, 12);
    }

    public static Icon chevronRight(Color color, int size) {
        return cached("chevR", color, size, (c, g, x, y, s, col) -> {
            g.setStroke(STROKE_16);
            int midY = y + s / 2;
            int leftX = x + s / 3;
            int rightX = x + (s * 2) / 3;
            g.drawLine(leftX, y + 2, rightX, midY);
            g.drawLine(rightX, midY, leftX, y + s - 2);
        });
    }

    public static Icon chevronLeft(Color color, int size) {
        return cached("chevL", color, size, (c, g, x, y, s, col) -> {
            g.setStroke(STROKE_16);
            int midY = y + s / 2;
            int rightX = x + (s * 2) / 3;
            int leftX = x + s / 3;
            g.drawLine(rightX, y + 2, leftX, midY);
            g.drawLine(leftX, midY, rightX, y + s - 2);
        });
    }

    public static Icon codeBraces(Color color) {
        return cached("braces", color, 14, (c, g, x, y, s, col) -> {
            g.drawLine(x + 5, y + 3, x + 3, y + 4);
            g.drawLine(x + 3, y + 4, x + 3, y + s / 2 - 1);
            g.drawLine(x + 3, y + s / 2 - 1, x + 2, y + s / 2);
            g.drawLine(x + 2, y + s / 2, x + 3, y + s / 2 + 1);
            g.drawLine(x + 3, y + s / 2 + 1, x + 3, y + s - 4);
            g.drawLine(x + 3, y + s - 4, x + 5, y + s - 3);
            g.drawLine(x + s - 5, y + 3, x + s - 3, y + 4);
            g.drawLine(x + s - 3, y + 4, x + s - 3, y + s / 2 - 1);
            g.drawLine(x + s - 3, y + s / 2 - 1, x + s - 2, y + s / 2);
            g.drawLine(x + s - 2, y + s / 2, x + s - 3, y + s / 2 + 1);
            g.drawLine(x + s - 3, y + s / 2 + 1, x + s - 3, y + s - 4);
            g.drawLine(x + s - 3, y + s - 4, x + s - 5, y + s - 3);
        });
    }

    // ------------------------------------------------------------------ Theme-bound convenience overloads
    public static Icon copy(Theme t) { return copy(t.fg); }
    public static Icon send(Theme t) { return send(t.fg); }
    public static Icon sendAll(Theme t) { return sendAll(t.fg); }
    public static Icon stop(Theme t) { return stop(t.danger.text()); }
    public static Icon repeater(Theme t) { return repeater(t.fg); }
    public static Icon folder(Theme t) { return folder(t.fg); }
    public static Icon api(Theme t) { return api(t.fg); }
    public static Icon role(Theme t) { return role(t.fg); }
    public static Icon check(Theme t) { return check(t.success.text()); }
    public static Icon warn(Theme t) { return warn(t.critical.text()); }
    public static Icon error(Theme t) { return error(t.danger.text()); }
    public static Icon info(Theme t) { return info(t.info.text()); }
    public static Icon redirect(Theme t) { return redirect(t.info.text()); }
    public static Icon hourglass(Theme t) { return hourglass(t.pending.text()); }
    public static Icon ghost(Theme t) { return ghost(t.muted); }
    public static Icon dup(Theme t) { return dup(t.dup.text()); }
    public static Icon hide(Theme t) { return hide(t.muted); }
    public static Icon show(Theme t) { return show(t.fg); }
    public static Icon trash(Theme t) { return trash(t.danger.text()); }
    public static Icon move(Theme t) { return move(t.info.text()); }
    public static Icon undo(Theme t) { return undo(t.fg); }
    public static Icon filter(Theme t) { return filter(t.fg); }
    public static Icon export(Theme t) { return export(t.fg); }
    public static Icon excel(Theme t) { return excel(t.success.text()); }
    public static Icon document(Theme t) { return document(t.fg); }
    public static Icon save(Theme t) { return save(t.fg); }
    public static Icon window(Theme t) { return window(t.fg); }
    public static Icon pin(Theme t) { return pin(t.orig.text()); }
    public static Icon gear(Theme t) { return gear(t.fg); }
    public static Icon refresh(Theme t) { return refresh(t.fg); }
    public static Icon plus(Theme t) { return plus(t.success.text()); }
    public static Icon minus(Theme t) { return minus(t.fg); }
    public static Icon search(Theme t) { return search(t.muted); }
    public static Icon target(Theme t) { return target(t.fg); }
    public static Icon sliders(Theme t) { return sliders(t.fg); }
    public static Icon edit(Theme t) { return edit(t.fg); }
    public static Icon branch(Theme t) { return branch(t.fg); }
    public static Icon arrowUp(Theme t) { return arrowUp(t.fg); }
    public static Icon arrowDown(Theme t) { return arrowDown(t.fg); }
    public static Icon arrowRight(Theme t) { return arrowRight(t.fg); }
    public static Icon compass(Theme t) { return compass(t.info.text()); }
    public static Icon clock(Theme t) { return clock(t.muted); }
    public static Icon bolt(Theme t) { return bolt(t.warning.text()); }
    public static Icon globe(Theme t) { return globe(t.info.text()); }
    public static Icon clean(Theme t) { return clean(t.danger.text()); }
    public static Icon chevronDown(Theme t) { return chevronDown(t.fg); }
    public static Icon chevronRight(Theme t) { return chevronRight(t.fg); }
    public static Icon codeBraces(Theme t) { return codeBraces(t.fg); }

    /** Paints two icons side-by-side (e.g. gear + dropdown chevron or folder + toggle arrow). */
    public static Icon pair(Icon left, Icon right, int gap) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        int h = Math.max(left.getIconHeight(), right.getIconHeight());
        int w = left.getIconWidth() + gap + right.getIconWidth();
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                left.paintIcon(c, g, x, y + (h - left.getIconHeight()) / 2);
                right.paintIcon(c, g, x + left.getIconWidth() + gap, y + (h - right.getIconHeight()) / 2);
            }

            @Override
            public int getIconWidth() {
                return w;
            }

            @Override
            public int getIconHeight() {
                return h;
            }
        };
    }

    public static Icon withChevron(Icon left, Color chevronColor) {
        return pair(left, chevronDown(chevronColor, 10), 2);
    }

    /** Semantic state glyph paired with {@link AuditLevel} so status is distinguishable without colour alone. */
    public static Icon forAudit(AuditLevel lvl, Color color) {
        if (lvl == null) {
            return dot(color, false);
        }
        return switch (lvl) {
            case OK -> check(color, 12);
            case INFO -> alertMark(color, 12);
            case WARN -> warn(color, 12);
            case ERROR -> error(color, 12);
            case REDIRECT -> redirect(color, 12);
            case PENDING -> hourglass(color, 12);
            default -> dot(color, false);
        };
    }

    /** Picks a crisp Java2D icon for legacy emoji prefixes produced by placement / model strings. */
    public static Icon forLeadingSymbol(String text, Theme theme) {
        return forLeadingSymbol(text, theme, null);
    }

    /** Picks a crisp Java2D icon for legacy emoji prefixes, optionally forcing {@code overrideColor} (e.g. on selection). */
    public static Icon forLeadingSymbol(String text, Theme theme, Color overrideColor) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Color ov = overrideColor;
        if (text.startsWith("\u2714") || text.startsWith("\u2705")) {
            return check(ov != null ? ov : theme.success.text(), 12);
        }
        if (text.startsWith("\u23F3")) {
            return hourglass(ov != null ? ov : theme.pending.text(), 12);
        }
        if (text.startsWith("\uD83D\uDC7B")) {
            return ghost(ov != null ? ov : theme.muted, 12);
        }
        if (text.startsWith("\u29C9") || text.startsWith("[\u29C9")) {
            return dup(ov != null ? ov : theme.dup.text(), 12);
        }
        if (text.startsWith("\uD83D\uDE48")) {
            return hide(ov != null ? ov : theme.muted, 12);
        }
        if (text.startsWith("\uD83D\uDC41")) {
            return show(ov != null ? ov : theme.info.text(), 12);
        }
        if (text.startsWith("\u21AA")) {
            return move(ov != null ? ov : theme.info.text(), 12);
        }
        if (text.startsWith("\u21A9") || text.startsWith("\u21B6")) {
            return undo(ov != null ? ov : theme.warning.text(), 12);
        }
        if (text.startsWith("\uD83D\uDDD1") || text.startsWith("\u2716")) {
            return trash(ov != null ? ov : theme.danger.text(), 12);
        }
        if (text.startsWith("\u270F")) {
            return edit(ov != null ? ov : theme.info.text(), 12);
        }
        if (text.startsWith("\u2795") || text.startsWith("\uFF0B")) {
            return plus(ov != null ? ov : theme.success.text(), 12);
        }
        if (text.startsWith("\u267B")) {
            return refresh(ov != null ? ov : theme.teal.text(), 12);
        }
        if (text.startsWith("\uD83D\uDCCD")) {
            return pin(ov != null ? ov : theme.orig.text(), 12);
        }
        if (text.startsWith("\uD83C\uDD95")) {
            return plus(ov != null ? ov : theme.accent.text(), 12);
        }
        if (text.startsWith("\uD83D\uDCCB")) {
            return copy(ov != null ? ov : theme.muted);
        }
        return null;
    }

    /** Strips leading emoji/symbol prefix so cells show our drawn Java2D icon cleanly without OS box glyphs. */
    public static String stripLeadingSymbol(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = s.trim();
        String[] prefixes = {"\u2714 ", "\u2705 ", "\u23F3 ", "\uD83D\uDC7B ", "\u29C9 ", "\uD83D\uDE48 ", "\uD83D\uDC41 ",
            "\u21AA ", "\u21A9 ", "\u21B6 ", "\uD83D\uDDD1 ", "\u2716 ", "\u270F ", "\u2795 ", "\u267B ",
            "\uD83D\uDCCD ", "\uD83C\uDD95 ", "\u2B1C ", "\uD83D\uDCC1 ", "\uD83D\uDCC2 ", "\uD83E\uDDED ",
            "\uD83D\uDD0E ", "\uD83D\uDD0D ", "\u25B6\u25B6 ", "\u25B6 ", "\uD83D\uDCE4 ", "\uD83C\uDFAF ",
            "\uD83D\uDCCB ", "\uD83C\uDF9B ", "\u2699 ", "\uD83D\uDDD7 ", "\uD83D\uDCCC ", "\uD83D\uDCCA ",
            "\uD83D\uDCC4 ", "\uD83D\uDCBE ", "\uD83D\uDD04 ", "\uD83E\uDDF9 ", "\uD83C\uDF3F ", "\uD83C\uDF33 ",
            "\uD83C\uDF10 ", "\u26A1 ", "\u279C ", "\u2B06 ", "\u2B07 ", "\uD83D\uDD58 ", "\uFF0B ",
            "\uD83D\uDEE0 ", "\u21BA ", "\u22EF ", "\u25A0 ", "\u21BB ", "\uD83C\uDF89 "};
        for (String p : prefixes) {
            if (t.startsWith(p)) {
                return stripInlineSymbols(t.substring(p.length()).trim());
            }
        }
        return stripInlineSymbols(t);
    }

    /** Strips any inline emoji glyphs (e.g. from placement text) before plain-text rendering. */
    public static String stripInlineSymbols(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String out = s.replace("[\u29C9", "[#")
                .replace("\u21AA ", "-> ")
                .replace("\u21AA", "-> ")
                .replace("\u2192", "->");
        String[] remove = {"\u29C9 ", "\u29C9", "\u23F3 ", "\u23F3", "\uD83D\uDC7B ", "\uD83D\uDC7B", "\uD83D\uDE48 ",
            "\uD83D\uDE48", "\u2714 ", "\u2714", "\u2705 ", "\u2705", "\u21A9 ", "\u21A9", "\u21B6 ", "\u21B6",
            "\uD83D\uDCC1 ", "\uD83D\uDCC1", "\uD83D\uDCC2 ", "\uD83D\uDCC2", "\uD83D\uDCCD ", "\uD83D\uDCCD",
            "\uD83C\uDD95 ", "\uD83C\uDD95", "\uD83C\uDF89 ", "\uD83C\uDF89", "\uD83D\uDD0E ", "\uD83D\uDD0E",
            "\uD83D\uDD0D ", "\uD83D\uDD0D", "\uD83D\uDC41 ", "\uD83D\uDC41", "\uD83D\uDDD1 ", "\uD83D\uDDD1",
            "\u2716 ", "\u2716", "\uD83D\uDCCB ", "\uD83D\uDCCB", "\uD83E\uDDED ", "\uD83E\uDDED", "\uD83C\uDF3F ",
            "\uD83C\uDF3F", "\uD83D\uDCCC ", "\uD83D\uDCCC", "\uD83C\uDF10 ", "\uD83C\uDF10", "\u2B1C ", "\u2B1C",
            "\u267B ", "\u267B", "\u270F ", "\u270F", "\u2795 ", "\u2795"};
        for (String tok : remove) {
            if (out.contains(tok)) {
                out = out.replace(tok, "");
            }
        }
        return out;
    }
}
