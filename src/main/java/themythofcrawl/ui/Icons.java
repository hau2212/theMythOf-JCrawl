package themythofcrawl.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.model.NodeTypeCatalog;
import themythofcrawl.ui.theme.FontManager;
import themythofcrawl.ui.theme.Theme;

/**
 * Icons v2 — Sharp geometric 16x16 optical-grid Java2D vector icon pack inspired by JetBrains
 * New UI (expui) and Lucide/Tabler, tailored to the sharp rectangular (arc = 0) aesthetic.
 * Zero dependency on OS emoji fonts, zero raw Color construction, 100% theme-aware.
 */
public final class Icons {
    private Icons() {
    }

    private static final int MAX_CACHE = 2048;
    private static final Map<String, Icon> CACHE = new HashMap<>();

    private static final Stroke STROKE_SHARP = new BasicStroke(1.38f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10f);
    private static final Stroke STROKE_BOLD = new BasicStroke(1.65f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10f);
    private static final Stroke STROKE_THIN = new BasicStroke(1.12f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10f);
    private static final Stroke STROKE_DASH = new BasicStroke(1.25f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            10f, new float[] {2.4f, 2.0f}, 0f);
    private static final AlphaComposite WASH_ALPHA = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.14f);

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
    private interface Grid16Painter {
        void paint(Graphics2D g, Color col);
    }

    /** Caches a vector icon defined cleanly on a 16x16 coordinate canvas and scaled to {@code size}. */
    private static synchronized Icon grid16(String name, Color color, int size, Grid16Painter p) {
        Color col = color != null ? color : Theme.current().fg;
        int sz = Math.max(8, size);
        String key = "g16|" + name + "|" + sz + "|" + col.getRGB();
        Icon have = CACHE.get(key);
        if (have != null) {
            return have;
        }
        if (CACHE.size() > MAX_CACHE) {
            CACHE.clear();
        }
        Icon made = new Drawn(sz, sz) {
            @Override
            void draw(Component c, Graphics2D g, int x, int y) {
                AffineTransform saved = g.getTransform();
                g.translate(x, y);
                double k = sz / 16.0;
                g.scale(k, k);
                g.setColor(col);
                g.setStroke(STROKE_SHARP);
                p.paint(g, col);
                g.setTransform(saved);
            }
        };
        CACHE.put(key, made);
        return made;
    }

    /** Paints a subtle 14% duotone tint inside a closed geometric shape using only the icon color. */
    private static void wash(Graphics2D g, Shape s) {
        Composite prev = g.getComposite();
        g.setComposite(WASH_ALPHA);
        g.fill(s);
        g.setComposite(prev);
    }

    private static void line(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new Line2D.Double(x1, y1, x2, y2));
    }

    private static void rect(Graphics2D g, double x, double y, double w, double h) {
        g.draw(new Rectangle2D.Double(x, y, w, h));
    }

    private static void fillRect(Graphics2D g, double x, double y, double w, double h) {
        g.fill(new Rectangle2D.Double(x, y, w, h));
    }

    private static void oval(Graphics2D g, double x, double y, double w, double h) {
        g.draw(new Ellipse2D.Double(x, y, w, h));
    }

    private static void fillOval(Graphics2D g, double x, double y, double w, double h) {
        g.fill(new Ellipse2D.Double(x, y, w, h));
    }

    private static Path2D poly(boolean close, double... pts) {
        Path2D p = new Path2D.Double();
        p.moveTo(pts[0], pts[1]);
        for (int i = 2; i + 1 < pts.length; i += 2) {
            p.lineTo(pts[i], pts[i + 1]);
        }
        if (close) {
            p.closePath();
        }
        return p;
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

    // ------------------------------------------------------------------ core markers & status
    public static synchronized Icon dot(Color color, boolean square) {
        Color col = color != null ? color : Theme.current().dotNone;
        String key = "dot2|" + col.getRGB() + "|" + square;
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
                    g.fillRect(x + 2, y + 1, 8, 8);
                } else {
                    g.fillOval(x + 2, y + 1, 8, 8);
                }
            }
        };
        CACHE.put(key, made);
        return made;
    }

    public static Icon statusDot(Color color, int size) {
        return grid16("sdot", color, size, (g, col) -> fillOval(g, 2.5, 2.5, 11, 11));
    }

    public static Icon circle(Color color, boolean filled) {
        return grid16(filled ? "circF" : "circO", color, 12, (g, col) -> {
            if (filled) {
                fillOval(g, 3, 3, 10, 10);
            } else {
                oval(g, 3, 3, 10, 10);
            }
        });
    }

    public static Icon copy(Color color) {
        return grid16("copy", color != null ? color : Theme.current().muted, 14, (g, col) -> {
            Rectangle2D back = new Rectangle2D.Double(2.5, 2, 7.5, 9.5);
            Rectangle2D front = new Rectangle2D.Double(5.5, 4.5, 8, 9.5);
            g.draw(back);
            wash(g, front);
            g.draw(front);
        });
    }

    public static synchronized Icon group(Color dotColor, Color sheetColor) {
        Color dc = dotColor != null ? dotColor : Theme.current().dotNone;
        Color sc = sheetColor != null ? sheetColor : Theme.current().muted;
        String key = "grp2|" + dc.getRGB() + "|" + sc.getRGB();
        Icon have = CACHE.get(key);
        if (have != null) {
            return have;
        }
        if (CACHE.size() > MAX_CACHE) {
            CACHE.clear();
        }
        Icon made = new Drawn(28, 14) {
            @Override
            void draw(Component c, Graphics2D g, int x, int y) {
                g.setColor(dc);
                g.fillRect(x + 2, y + 3, 8, 8);
                copy(sc).paintIcon(c, g, x + 13, y);
            }
        };
        CACHE.put(key, made);
        return made;
    }

    // ------------------------------------------------------------------ primary toolbar & crawl actions
    public static Icon send(Color color) { return send(color, 14); }

    public static Icon send(Color color, int size) {
        return grid16("send", color, size, (g, col) -> {
            Path2D p = poly(true, 2.5, 2.5, 14, 8, 2.5, 13.5, 5, 8);
            wash(g, p);
            g.draw(p);
            line(g, 5, 8, 10.5, 8);
        });
    }

    public static Icon sendAll(Color color) { return sendAll(color, 14); }

    public static Icon sendAll(Color color, int size) {
        return grid16("sendAll", color, size, (g, col) -> {
            Path2D p1 = poly(true, 1.8, 3, 8.2, 8, 1.8, 13);
            Path2D p2 = poly(true, 7.8, 3, 14.2, 8, 7.8, 13);
            wash(g, p1);
            wash(g, p2);
            g.draw(p1);
            g.draw(p2);
        });
    }

    public static Icon stop(Color color) { return stop(color, 14); }

    public static Icon stop(Color color, int size) {
        return grid16("stop", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(3, 3, 10, 10);
            wash(g, r);
            g.draw(r);
            fillRect(g, 5.5, 5.5, 5, 5);
        });
    }

    public static Icon repeater(Color color) { return repeater(color, 14); }

    public static Icon repeater(Color color, int size) {
        return grid16("repeater", color, size, (g, col) -> {
            Path2D box = poly(false, 8.5, 3, 2.5, 3, 2.5, 13.5, 13, 13.5, 13, 7.5);
            g.draw(box);
            line(g, 7.5, 8.5, 13.5, 2.5);
            g.draw(poly(false, 9.5, 2.5, 13.5, 2.5, 13.5, 6.5));
        });
    }

    public static Icon folder(Color color) { return folder(color, 14); }

    public static Icon folder(Color color, int size) {
        return grid16("folder", color, size, (g, col) -> {
            Path2D p = poly(true, 1.8, 3, 6.4, 3, 8, 5, 14.2, 5, 14.2, 13.2, 1.8, 13.2);
            wash(g, p);
            g.draw(p);
        });
    }

    public static Icon folderOpen(Color color, int size) {
        return grid16("folderOpen", color, size, (g, col) -> {
            Path2D back = poly(false, 2, 12.5, 2, 3, 6.4, 3, 8, 5, 13.5, 5, 13.5, 7);
            Path2D front = poly(true, 2, 13, 3.6, 7, 14.6, 7, 13, 13);
            g.draw(back);
            wash(g, front);
            g.draw(front);
        });
    }

    public static Icon api(Color color) { return api(color, 14); }

    public static Icon api(Color color, int size) {
        return grid16("api", color, size, (g, col) -> {
            Rectangle2D outer = new Rectangle2D.Double(2, 2.5, 12, 11);
            wash(g, outer);
            g.draw(outer);
            line(g, 2, 5.5, 14, 5.5);
            g.draw(poly(false, 6.2, 7.5, 4.3, 9.5, 6.2, 11.5));
            g.draw(poly(false, 9.8, 7.5, 11.7, 9.5, 9.8, 11.5));
            line(g, 8.5, 7.3, 7.5, 11.7);
        });
    }

    public static Icon role(Color color) { return role(color, 14); }

    public static Icon role(Color color, int size) {
        return grid16("role", color, size, (g, col) -> {
            Ellipse2D head = new Ellipse2D.Double(5.2, 2.2, 5.6, 5.6);
            wash(g, head);
            g.draw(head);
            Path2D body = poly(true, 2.5, 13.8, 2.5, 11, 5, 9.4, 11, 9.4, 13.5, 11, 13.5, 13.8);
            wash(g, body);
            g.draw(body);
        });
    }

    public static Icon users(Color color, int size) {
        return grid16("users", color, size, (g, col) -> {
            oval(g, 3, 2.5, 4.6, 4.6);
            g.draw(poly(false, 1.5, 13.5, 1.5, 10.6, 3.5, 9.2, 7.2, 9.2, 9.2, 10.6, 9.2, 13.5));
            oval(g, 9.2, 3.2, 3.8, 3.8);
            g.draw(poly(false, 10.5, 9.4, 12.8, 9.4, 14.5, 10.8, 14.5, 13.5));
        });
    }

    // ------------------------------------------------------------------ semantic & audit icons
    public static Icon check(Color color) { return check(color, 14); }

    public static Icon check(Color color, int size) {
        return grid16("check", color, size, (g, col) -> {
            g.setStroke(STROKE_BOLD);
            g.draw(poly(false, 2.5, 8.5, 6.2, 12.2, 13.5, 4.2));
        });
    }

    public static Icon warn(Color color) { return warn(color, 14); }

    public static Icon warn(Color color, int size) {
        return grid16("warn", color, size, (g, col) -> {
            Path2D tri = poly(true, 8, 2, 14.5, 13.5, 1.5, 13.5);
            wash(g, tri);
            g.draw(tri);
            line(g, 8, 6, 8, 9.5);
            fillRect(g, 7.3, 11, 1.4, 1.4);
        });
    }

    public static Icon alertMark(Color color, int size) {
        return grid16("alertMark", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(2, 2, 12, 12);
            wash(g, r);
            g.draw(r);
            line(g, 8, 4.8, 8, 9.2);
            fillRect(g, 7.2, 10.8, 1.6, 1.6);
        });
    }

    public static Icon error(Color color) { return error(color, 14); }

    public static Icon error(Color color, int size) {
        return grid16("error", color, size, (g, col) -> {
            g.setStroke(STROKE_BOLD);
            line(g, 3.2, 3.2, 12.8, 12.8);
            line(g, 12.8, 3.2, 3.2, 12.8);
        });
    }

    public static Icon info(Color color) { return info(color, 14); }

    public static Icon info(Color color, int size) {
        return grid16("info", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(2, 2, 12, 12);
            wash(g, r);
            g.draw(r);
            fillRect(g, 7.2, 4.5, 1.6, 1.6);
            line(g, 8, 7.4, 8, 11.6);
        });
    }

    public static Icon redirect(Color color) { return redirect(color, 14); }

    public static Icon redirect(Color color, int size) {
        return grid16("redirect", color, size, (g, col) -> {
            g.draw(poly(false, 2.5, 11.5, 2.5, 6.5, 13, 6.5));
            g.draw(poly(false, 9.5, 3.2, 13.2, 6.5, 9.5, 9.8));
        });
    }

    public static Icon hourglass(Color color) { return hourglass(color, 14); }

    public static Icon hourglass(Color color, int size) {
        return grid16("hourglass", color, size, (g, col) -> {
            line(g, 3, 2, 13, 2);
            line(g, 3, 14, 13, 14);
            Path2D p = poly(true, 4, 2, 12, 2, 8, 8, 12, 14, 4, 14, 8, 8);
            wash(g, p);
            g.draw(p);
        });
    }

    /** Modern dashed slot icon replacing the cartoon ghost for remembered/deleted slots. */
    public static Icon ghost(Color color) { return ghost(color, 14); }

    public static Icon ghost(Color color, int size) {
        return grid16("ghostSlot", color, size, (g, col) -> {
            Stroke prev = g.getStroke();
            g.setStroke(STROKE_DASH);
            rect(g, 2.2, 2.2, 11.6, 11.6);
            g.setStroke(prev);
            fillRect(g, 6.5, 6.5, 3, 3);
        });
    }

    public static Icon dup(Color color) { return dup(color, 14); }

    public static Icon dup(Color color, int size) {
        return grid16("dup", color, size, (g, col) -> {
            rect(g, 2, 2, 8.2, 8.2);
            Rectangle2D front = new Rectangle2D.Double(5.8, 5.8, 8.2, 8.2);
            wash(g, front);
            g.draw(front);
        });
    }

    /** Crown / anchor star for API Gốc (#1). */
    public static Icon originCrown(Color color, int size) {
        return grid16("originCrown", color, size, (g, col) -> {
            Path2D p = poly(true, 2, 12.5, 2, 5, 5.5, 8, 8, 3.5, 10.5, 8, 14, 5, 14, 12.5);
            wash(g, p);
            g.draw(p);
            line(g, 2, 14, 14, 14);
        });
    }

    /** Lineage tree / origin-to-copies graph icon. */
    public static Icon lineageGraph(Color color, int size) {
        return grid16("lineageGraph", color, size, (g, col) -> {
            Rectangle2D root = new Rectangle2D.Double(2, 2, 4.5, 4.5);
            wash(g, root);
            g.draw(root);
            rect(g, 9.8, 2.5, 4, 4);
            rect(g, 9.8, 9.8, 4, 4);
            line(g, 4.2, 6.5, 4.2, 11.8);
            line(g, 4.2, 11.8, 9.8, 11.8);
            line(g, 6.5, 4.2, 9.8, 4.2);
        });
    }

    public static Icon hide(Color color) { return hide(color, 14); }

    public static Icon hide(Color color, int size) {
        return grid16("hide", color, size, (g, col) -> {
            g.draw(new Arc2D.Double(1.8, 4, 12.4, 8, 0, 180, Arc2D.OPEN));
            g.draw(new Arc2D.Double(1.8, 4, 12.4, 8, 180, 180, Arc2D.OPEN));
            line(g, 2.4, 13.6, 13.6, 2.4);
        });
    }

    public static Icon show(Color color) { return show(color, 14); }

    public static Icon show(Color color, int size) {
        return grid16("show", color, size, (g, col) -> {
            g.draw(new Arc2D.Double(1.8, 4, 12.4, 8, 0, 180, Arc2D.OPEN));
            g.draw(new Arc2D.Double(1.8, 4, 12.4, 8, 180, 180, Arc2D.OPEN));
            fillOval(g, 6, 6, 4, 4);
        });
    }

    public static Icon trash(Color color) { return trash(color, 14); }

    public static Icon trash(Color color, int size) {
        return grid16("trash", color, size, (g, col) -> {
            line(g, 2.2, 4, 13.8, 4);
            rect(g, 5.5, 1.8, 5, 2.2);
            Rectangle2D body = new Rectangle2D.Double(3.5, 4, 9, 10.2);
            wash(g, body);
            g.draw(body);
            line(g, 6.5, 6.8, 6.5, 11.8);
            line(g, 9.5, 6.8, 9.5, 11.8);
        });
    }

    public static Icon move(Color color) { return move(color, 14); }

    public static Icon move(Color color, int size) {
        return grid16("move", color, size, (g, col) -> {
            g.draw(poly(false, 2.5, 12.5, 2.5, 6.5, 13.2, 6.5));
            g.draw(poly(false, 9.5, 3, 13.2, 6.5, 9.5, 10));
        });
    }

    public static Icon undo(Color color) { return undo(color, 14); }

    public static Icon undo(Color color, int size) {
        return grid16("undo", color, size, (g, col) -> {
            g.draw(poly(false, 13.5, 12.5, 13.5, 6.5, 2.8, 6.5));
            g.draw(poly(false, 6.5, 3, 2.8, 6.5, 6.5, 10));
        });
    }

    public static Icon filter(Color color) { return filter(color, 14); }

    public static Icon filter(Color color, int size) {
        return grid16("filter", color, size, (g, col) -> {
            Path2D p = poly(true, 2, 2.5, 14, 2.5, 9.5, 8.2, 9.5, 13.5, 6.5, 12.2, 6.5, 8.2);
            wash(g, p);
            g.draw(p);
        });
    }

    public static Icon export(Color color) { return export(color, 14); }

    public static Icon export(Color color, int size) {
        return grid16("export", color, size, (g, col) -> {
            line(g, 8, 2.5, 8, 10.2);
            g.draw(poly(false, 4.8, 5.7, 8, 2.5, 11.2, 5.7));
            g.draw(poly(false, 2.2, 10, 2.2, 13.8, 13.8, 13.8, 13.8, 10));
        });
    }

    public static Icon download(Color color, int size) {
        return grid16("download", color, size, (g, col) -> {
            line(g, 8, 2.2, 8, 10);
            g.draw(poly(false, 4.8, 6.8, 8, 10, 11.2, 6.8));
            g.draw(poly(false, 2.2, 10.5, 2.2, 13.8, 13.8, 13.8, 13.8, 10.5));
        });
    }

    public static Icon excel(Color color) { return excel(color, 14); }

    public static Icon excel(Color color, int size) {
        return grid16("excel", color, size, (g, col) -> {
            Rectangle2D box = new Rectangle2D.Double(2, 2, 12, 12);
            wash(g, box);
            g.draw(box);
            line(g, 2, 6, 14, 6);
            line(g, 2, 10, 14, 10);
            line(g, 6.5, 2, 6.5, 14);
        });
    }

    public static Icon document(Color color) { return document(color, 14); }

    public static Icon document(Color color, int size) {
        return grid16("doc", color, size, (g, col) -> {
            Path2D p = poly(true, 3, 1.8, 10, 1.8, 13, 4.8, 13, 14.2, 3, 14.2);
            wash(g, p);
            g.draw(p);
            g.draw(poly(false, 10, 1.8, 10, 4.8, 13, 4.8));
            line(g, 5.2, 8, 10.8, 8);
            line(g, 5.2, 11, 9.5, 11);
        });
    }

    public static Icon save(Color color) { return save(color, 14); }

    public static Icon save(Color color, int size) {
        return grid16("save", color, size, (g, col) -> {
            Path2D p = poly(true, 2.2, 2.2, 11.2, 2.2, 13.8, 4.8, 13.8, 13.8, 2.2, 13.8);
            wash(g, p);
            g.draw(p);
            rect(g, 4.8, 2.2, 5.2, 3.8);
            rect(g, 4.5, 9, 7, 4.8);
        });
    }

    public static Icon window(Color color) { return window(color, 14); }

    public static Icon window(Color color, int size) {
        return grid16("window", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(2, 4.2, 9.8, 9.6);
            wash(g, r);
            g.draw(r);
            line(g, 2, 6.8, 11.8, 6.8);
            g.draw(poly(false, 5, 2.2, 14, 2.2, 14, 10.5));
        });
    }

    public static Icon pin(Color color) { return pin(color, 14); }

    public static Icon pin(Color color, int size) {
        return grid16("pin", color, size, (g, col) -> {
            Path2D head = poly(true, 4.5, 2.2, 11.5, 2.2, 10.5, 7, 12.5, 9, 3.5, 9, 5.5, 7);
            wash(g, head);
            g.draw(head);
            line(g, 8, 9, 8, 14.2);
        });
    }

    public static Icon gear(Color color) { return gear(color, 14); }

    public static Icon gear(Color color, int size) {
        return grid16("gear", color, size, (g, col) -> {
            Ellipse2D ring = new Ellipse2D.Double(4.2, 4.2, 7.6, 7.6);
            wash(g, ring);
            g.draw(ring);
            oval(g, 6.5, 6.5, 3, 3);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4.0;
                line(g, 8 + Math.cos(a) * 4.2, 8 + Math.sin(a) * 4.2,
                        8 + Math.cos(a) * 6.2, 8 + Math.sin(a) * 6.2);
            }
        });
    }

    public static Icon refresh(Color color) { return refresh(color, 14); }

    public static Icon refresh(Color color, int size) {
        return grid16("refresh", color, size, (g, col) -> {
            g.draw(new Arc2D.Double(2.5, 2.5, 11, 11, 35, 285, Arc2D.OPEN));
            g.draw(poly(false, 10.5, 2.2, 13.5, 4.2, 11, 6.6));
        });
    }

    public static Icon plus(Color color) { return plus(color, 14); }

    public static Icon plus(Color color, int size) {
        return grid16("plus", color, size, (g, col) -> {
            g.setStroke(STROKE_BOLD);
            line(g, 8, 2.8, 8, 13.2);
            line(g, 2.8, 8, 13.2, 8);
        });
    }

    public static Icon minus(Color color) { return minus(color, 14); }

    public static Icon minus(Color color, int size) {
        return grid16("minus", color, size, (g, col) -> {
            g.setStroke(STROKE_BOLD);
            line(g, 2.8, 8, 13.2, 8);
        });
    }

    public static Icon search(Color color) { return search(color, 14); }

    public static Icon search(Color color, int size) {
        return grid16("search", color, size, (g, col) -> {
            Ellipse2D lens = new Ellipse2D.Double(2.3, 2.3, 8.6, 8.6);
            wash(g, lens);
            g.draw(lens);
            g.setStroke(STROKE_BOLD);
            line(g, 9.8, 9.8, 13.7, 13.7);
        });
    }

    public static Icon target(Color color) { return target(color, 14); }

    public static Icon target(Color color, int size) {
        return grid16("target", color, size, (g, col) -> {
            oval(g, 2.2, 2.2, 11.6, 11.6);
            oval(g, 5.2, 5.2, 5.6, 5.6);
            fillRect(g, 7.1, 7.1, 1.8, 1.8);
        });
    }

    public static Icon sliders(Color color) { return sliders(color, 14); }

    public static Icon sliders(Color color, int size) {
        return grid16("sliders", color, size, (g, col) -> {
            line(g, 2, 4.5, 14, 4.5);
            line(g, 2, 11.5, 14, 11.5);
            fillRect(g, 4.5, 2.5, 3, 4);
            fillRect(g, 8.8, 9.5, 3, 4);
        });
    }

    public static Icon edit(Color color) { return edit(color, 14); }

    public static Icon edit(Color color, int size) {
        return grid16("edit", color, size, (g, col) -> {
            Path2D p = poly(true, 2.5, 13.5, 3.2, 9.8, 10.6, 2.4, 13.6, 5.4, 6.2, 12.8);
            wash(g, p);
            g.draw(p);
            line(g, 8.8, 4.2, 11.8, 7.2);
        });
    }

    public static Icon branch(Color color) { return branch(color, 14); }

    public static Icon branch(Color color, int size) {
        return grid16("branch", color, size, (g, col) -> {
            rect(g, 2.5, 2, 3.4, 3.4);
            rect(g, 2.5, 10.6, 3.4, 3.4);
            rect(g, 10.1, 2, 3.4, 3.4);
            line(g, 4.2, 5.4, 4.2, 10.6);
            g.draw(poly(false, 4.2, 8.2, 11.8, 8.2, 11.8, 5.4));
        });
    }

    public static Icon arrowUp(Color color) { return arrowUp(color, 14); }

    public static Icon arrowUp(Color color, int size) {
        return grid16("arrowUp", color, size, (g, col) -> {
            line(g, 8, 13.2, 8, 2.8);
            g.draw(poly(false, 4.2, 6.5, 8, 2.8, 11.8, 6.5));
        });
    }

    public static Icon arrowDown(Color color) { return arrowDown(color, 14); }

    public static Icon arrowDown(Color color, int size) {
        return grid16("arrowDown", color, size, (g, col) -> {
            line(g, 8, 2.8, 8, 13.2);
            g.draw(poly(false, 4.2, 9.5, 8, 13.2, 11.8, 9.5));
        });
    }

    public static Icon arrowRight(Color color) { return arrowRight(color, 14); }

    public static Icon arrowRight(Color color, int size) {
        return grid16("arrowRight", color, size, (g, col) -> {
            line(g, 2.5, 8, 13.2, 8);
            g.draw(poly(false, 9.5, 4.2, 13.2, 8, 9.5, 11.8));
        });
    }

    public static Icon compass(Color color) { return compass(color, 14); }

    public static Icon compass(Color color, int size) {
        return grid16("compass", color, size, (g, col) -> {
            oval(g, 2, 2, 12, 12);
            Path2D needle = poly(true, 10.8, 5.2, 9.2, 9.2, 5.2, 10.8, 6.8, 6.8);
            wash(g, needle);
            g.draw(needle);
        });
    }

    public static Icon clock(Color color) { return clock(color, 14); }

    public static Icon clock(Color color, int size) {
        return grid16("clock", color, size, (g, col) -> {
            Ellipse2D face = new Ellipse2D.Double(2, 2, 12, 12);
            wash(g, face);
            g.draw(face);
            g.draw(poly(false, 8, 4.6, 8, 8.2, 10.8, 9.8));
        });
    }

    public static Icon bolt(Color color) { return bolt(color, 14); }

    public static Icon bolt(Color color, int size) {
        return grid16("bolt", color, size, (g, col) -> {
            Path2D p = poly(true, 9, 1.8, 3.2, 8.8, 7.6, 8.8, 6.8, 14.2, 12.8, 7.2, 8.4, 7.2);
            wash(g, p);
            g.draw(p);
        });
    }

    public static Icon globe(Color color) { return globe(color, 14); }

    public static Icon globe(Color color, int size) {
        return grid16("globe", color, size, (g, col) -> {
            Ellipse2D c = new Ellipse2D.Double(2, 2, 12, 12);
            wash(g, c);
            g.draw(c);
            oval(g, 5.2, 2, 5.6, 12);
            line(g, 2.2, 8, 13.8, 8);
        });
    }

    public static Icon clean(Color color) { return clean(color, 14); }

    public static Icon clean(Color color, int size) {
        return grid16("clean", color, size, (g, col) -> {
            line(g, 9.5, 2.2, 6.5, 7);
            Path2D broom = poly(true, 3.5, 7, 10.5, 7, 13, 13.5, 2, 13.5);
            wash(g, broom);
            g.draw(broom);
            line(g, 6, 10.5, 5.5, 13.5);
            line(g, 9, 10.5, 9.5, 13.5);
        });
    }

    public static Icon chevronDown(Color color) { return chevronDown(color, 12); }

    public static Icon chevronDown(Color color, int size) {
        return grid16("chevDown", color, size, (g, col) -> {
            g.setStroke(STROKE_BOLD);
            g.draw(poly(false, 3.5, 6, 8, 10.5, 12.5, 6));
        });
    }

    public static Icon chevronUp(Color color) { return chevronUp(color, 12); }

    public static Icon chevronUp(Color color, int size) {
        return grid16("chevUp", color, size, (g, col) -> {
            g.setStroke(STROKE_BOLD);
            g.draw(poly(false, 3.5, 10.5, 8, 6, 12.5, 10.5));
        });
    }

    public static Icon chevronRight(Color color) { return chevronRight(color, 12); }

    public static Icon chevronRight(Color color, int size) {
        return grid16("chevRight", color, size, (g, col) -> {
            g.setStroke(STROKE_BOLD);
            g.draw(poly(false, 6, 3.5, 10.5, 8, 6, 12.5));
        });
    }

    public static Icon chevronLeft(Color color, int size) {
        return grid16("chevLeft", color, size, (g, col) -> {
            g.setStroke(STROKE_BOLD);
            g.draw(poly(false, 10, 3.5, 5.5, 8, 10, 12.5));
        });
    }

    public static Icon codeBraces(Color color) { return codeBraces(color, 14); }

    public static Icon codeBraces(Color color, int size) {
        return grid16("codeBraces", color, size, (g, col) -> {
            g.draw(poly(false, 6, 2.5, 4.2, 2.5, 4.2, 6.8, 2.5, 8, 4.2, 9.2, 4.2, 13.5, 6, 13.5));
            g.draw(poly(false, 10, 2.5, 11.8, 2.5, 11.8, 6.8, 13.5, 8, 11.8, 9.2, 11.8, 13.5, 10, 13.5));
        });
    }

    public static Icon cluster(Color color, int size) {
        return grid16("cluster", color, size, (g, col) -> {
            Stroke prev = g.getStroke();
            g.setStroke(STROKE_DASH);
            rect(g, 1.8, 1.8, 12.4, 12.4);
            g.setStroke(prev);
            Rectangle2D a = new Rectangle2D.Double(4, 4, 3.3, 3.3);
            Rectangle2D b = new Rectangle2D.Double(8.7, 4, 3.3, 3.3);
            Rectangle2D c = new Rectangle2D.Double(4, 8.7, 8, 3.3);
            wash(g, a);
            wash(g, b);
            wash(g, c);
            g.draw(a);
            g.draw(b);
            g.draw(c);
        });
    }

    public static Icon focus(Color color, int size) {
        return grid16("focus", color, size, (g, col) -> {
            g.draw(poly(false, 2.2, 5.5, 2.2, 2.2, 5.5, 2.2));
            g.draw(poly(false, 10.5, 2.2, 13.8, 2.2, 13.8, 5.5));
            g.draw(poly(false, 13.8, 10.5, 13.8, 13.8, 10.5, 13.8));
            g.draw(poly(false, 5.5, 13.8, 2.2, 13.8, 2.2, 10.5));
            fillRect(g, 6.5, 6.5, 3, 3);
        });
    }

    public static Icon tag(Color color, int size) {
        return grid16("tag", color, size, (g, col) -> {
            Path2D p = poly(true, 2.2, 2.2, 8.2, 2.2, 13.8, 7.8, 7.8, 13.8, 2.2, 8.2);
            wash(g, p);
            g.draw(p);
            fillRect(g, 4.6, 4.6, 1.8, 1.8);
        });
    }

    public static Icon star(Color color, int size) {
        return grid16("star", color, size, (g, col) -> {
            Path2D p = new Path2D.Double();
            for (int i = 0; i < 10; i++) {
                double a = -Math.PI / 2.0 + i * Math.PI / 5.0;
                double r = (i % 2 == 0) ? 6.2 : 2.8;
                double px = 8 + Math.cos(a) * r;
                double py = 8.2 + Math.sin(a) * r;
                if (i == 0) {
                    p.moveTo(px, py);
                } else {
                    p.lineTo(px, py);
                }
            }
            p.closePath();
            wash(g, p);
            g.draw(p);
        });
    }

    public static Icon compare(Color color, int size) {
        return grid16("cmp", color, size, (g, col) -> {
            line(g, 2.5, 5.2, 13.5, 5.2);
            g.draw(poly(false, 10.2, 2.6, 13.5, 5.2, 10.2, 7.8));
            line(g, 2.5, 10.8, 13.5, 10.8);
            g.draw(poly(false, 5.8, 8.2, 2.5, 10.8, 5.8, 13.4));
        });
    }

    // ------------------------------------------------------------------ V2.1 Action Workbench & Display Icons
    public static Icon workbench(Color color, int size) {
        return grid16("workbench", color, size, (g, col) -> {
            Rectangle2D box = new Rectangle2D.Double(1.8, 2.2, 12.4, 11.6);
            wash(g, box);
            g.draw(box);
            line(g, 1.8, 5.4, 14.2, 5.4);
            line(g, 6.2, 5.4, 6.2, 13.8);
            Path2D bolt = poly(false, 10.8, 7, 8.6, 9.8, 10.6, 9.8, 9.2, 12.4);
            g.draw(bolt);
        });
    }

    public static Icon strategyShield(Color color, int size) {
        return grid16("stratShield", color, size, (g, col) -> {
            Path2D p = poly(true, 8, 1.8, 13.8, 4.2, 13, 10.4, 8, 14.2, 3, 10.4, 2.2, 4.2);
            wash(g, p);
            g.draw(p);
            line(g, 6, 8, 10, 8);
        });
    }

    public static Icon strategySnapshot(Color color, int size) {
        return grid16("stratSnap", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(2, 3, 12, 10);
            wash(g, r);
            g.draw(r);
            g.draw(poly(false, 5, 8.2, 7.2, 10.4, 11.2, 5.8));
        });
    }

    public static Icon strategySwap(Color color, int size) {
        return grid16("stratSwap", color, size, (g, col) -> {
            Rectangle2D a = new Rectangle2D.Double(1.8, 2.2, 5.2, 5.2);
            Rectangle2D b = new Rectangle2D.Double(9, 8.6, 5.2, 5.2);
            wash(g, a);
            wash(g, b);
            g.draw(a);
            g.draw(b);
            g.draw(poly(false, 8.8, 4.2, 12.2, 4.2, 12.2, 7));
            g.draw(poly(false, 7.2, 11.8, 3.8, 11.8, 3.8, 9));
        });
    }

    public static Icon strategyChain(Color color, int size) {
        return grid16("stratChain", color, size, (g, col) -> {
            Rectangle2D left = new Rectangle2D.Double(1.6, 5, 4.4, 6);
            Rectangle2D right = new Rectangle2D.Double(10, 5, 4.4, 6);
            wash(g, left);
            wash(g, right);
            g.draw(left);
            g.draw(right);
            line(g, 6, 8, 10, 8);
            g.draw(poly(false, 8.2, 6, 10, 8, 8.2, 10));
        });
    }

    public static Icon idPool(Color color, int size) {
        return grid16("idPool", color, size, (g, col) -> {
            Rectangle2D top = new Rectangle2D.Double(2, 2.2, 12, 3.4);
            Rectangle2D mid = new Rectangle2D.Double(2, 6.3, 12, 3.4);
            Rectangle2D bot = new Rectangle2D.Double(2, 10.4, 12, 3.4);
            wash(g, top);
            g.draw(top);
            g.draw(mid);
            g.draw(bot);
            fillRect(g, 11, 3.3, 1.4, 1.4);
            fillRect(g, 11, 7.4, 1.4, 1.4);
            fillRect(g, 11, 11.5, 1.4, 1.4);
        });
    }

    public static Icon displayMode(Color color, int size) {
        return grid16("displayMode", color, size, (g, col) -> {
            Rectangle2D box = new Rectangle2D.Double(1.8, 2.5, 12.4, 11);
            wash(g, box);
            g.draw(box);
            line(g, 5.8, 2.5, 5.8, 13.5);
            line(g, 7.8, 6, 12.2, 6);
            line(g, 7.8, 9.8, 11.2, 9.8);
            fillRect(g, 3.2, 5.2, 1.4, 1.4);
            fillRect(g, 3.2, 9, 1.4, 1.4);
        });
    }

    public static Icon fontGlyph(Color color, int size) {
        return grid16("fontGlyph", color, size, (g, col) -> {
            g.draw(poly(false, 2.5, 13, 6.2, 3, 9.8, 13));
            line(g, 3.8, 9.8, 8.6, 9.8);
            line(g, 11.5, 6.5, 14, 6.5);
            line(g, 12.7, 6.5, 12.7, 13);
        });
    }

    public static Icon palette(Color color, int size) {
        return grid16("palette", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(2, 2, 12, 12);
            wash(g, r);
            g.draw(r);
            fillRect(g, 4.2, 4.2, 2.6, 2.6);
            fillRect(g, 9.2, 4.2, 2.6, 2.6);
            fillRect(g, 4.2, 9.2, 2.6, 2.6);
            rect(g, 9.2, 9.2, 2.6, 2.6);
        });
    }

    public static Icon playTest(Color color, int size) {
        return grid16("playTest", color, size, (g, col) -> {
            rect(g, 2, 2, 12, 12);
            Path2D tri = poly(true, 6.2, 4.8, 11.4, 8, 6.2, 11.2);
            wash(g, tri);
            g.draw(tri);
        });
    }

    // ------------------------------------------------------------------ 42 Node Vector Icons
    public static Icon link(Color color, int size) {
        return grid16("link", color, size, (g, col) -> {
            rect(g, 1.8, 5.5, 5.4, 5);
            rect(g, 8.8, 5.5, 5.4, 5);
            line(g, 5.5, 8, 10.5, 8);
        });
    }

    public static Icon buttonNode(Color color, int size) {
        return grid16("btnNode", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(1.8, 4.2, 12.4, 7.6);
            wash(g, r);
            g.draw(r);
            line(g, 4.8, 8, 11.2, 8);
        });
    }

    public static Icon iconNode(Color color, int size) {
        return grid16("icNode", color, size, (g, col) -> {
            Path2D d = poly(true, 8, 2, 14, 8, 8, 14, 2, 8);
            wash(g, d);
            g.draw(d);
            fillRect(g, 7, 7, 2, 2);
        });
    }

    public static Icon tabNode(Color color, int size) {
        return grid16("tabNode", color, size, (g, col) -> {
            Path2D tab = poly(true, 2, 3, 7.2, 3, 8.4, 5.5, 14, 5.5, 14, 13, 2, 13);
            wash(g, tab);
            g.draw(tab);
            line(g, 2, 5.8, 14, 5.8);
        });
    }

    public static Icon modalNode(Color color, int size) {
        return grid16("modalNode", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(2, 2.4, 12, 11.2);
            wash(g, r);
            g.draw(r);
            line(g, 2, 5.6, 14, 5.6);
            fillRect(g, 11.2, 3.6, 1.4, 1.2);
            line(g, 4.2, 8.8, 11.8, 8.8);
            line(g, 4.2, 11.2, 9.2, 11.2);
        });
    }

    public static Icon formNode(Color color, int size) {
        return grid16("formNode", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(2, 2, 12, 12);
            wash(g, r);
            g.draw(r);
            rect(g, 4.2, 4.2, 7.6, 2.4);
            rect(g, 4.2, 8.2, 7.6, 2.4);
            line(g, 7.5, 12.2, 11.8, 12.2);
        });
    }

    public static Icon bell(Color color, int size) {
        return grid16("bell", color, size, (g, col) -> {
            Path2D b = poly(true, 3, 11.2, 4.2, 5.5, 6.2, 3, 9.8, 3, 11.8, 5.5, 13, 11.2);
            wash(g, b);
            g.draw(b);
            line(g, 2, 11.2, 14, 11.2);
            line(g, 6.8, 13.4, 9.2, 13.4);
        });
    }

    public static Icon cart(Color color, int size) {
        return grid16("cart", color, size, (g, col) -> {
            g.draw(poly(false, 1.8, 3, 4, 3, 5.4, 10.5, 12.6, 10.5, 14, 5, 4.6, 5));
            fillRect(g, 5.6, 12.4, 2, 2);
            fillRect(g, 10.8, 12.4, 2, 2);
        });
    }

    public static Icon lock(Color color, int size) {
        return grid16("lock", color, size, (g, col) -> {
            Rectangle2D body = new Rectangle2D.Double(3, 7, 10, 7);
            wash(g, body);
            g.draw(body);
            g.draw(poly(false, 5, 7, 5, 3.8, 6.5, 2.2, 9.5, 2.2, 11, 3.8, 11, 7));
            fillRect(g, 7.2, 9.5, 1.6, 2.4);
        });
    }

    public static Icon key(Color color, int size) {
        return grid16("key", color, size, (g, col) -> {
            Rectangle2D head = new Rectangle2D.Double(2, 5.5, 5, 5);
            wash(g, head);
            g.draw(head);
            line(g, 7, 8, 14, 8);
            line(g, 11.2, 8, 11.2, 10.8);
            line(g, 13.5, 8, 13.5, 10.2);
        });
    }

    public static Icon database(Color color, int size) {
        return grid16("db", color, size, (g, col) -> {
            Rectangle2D r1 = new Rectangle2D.Double(2.5, 2.2, 11, 3.6);
            Rectangle2D r2 = new Rectangle2D.Double(2.5, 6.2, 11, 3.6);
            Rectangle2D r3 = new Rectangle2D.Double(2.5, 10.2, 11, 3.6);
            wash(g, r1);
            g.draw(r1);
            g.draw(r2);
            g.draw(r3);
        });
    }

    public static Icon shield(Color color, int size) {
        return grid16("shield", color, size, (g, col) -> {
            Path2D p = poly(true, 8, 1.8, 13.8, 4, 13, 10.2, 8, 14.2, 3, 10.2, 2.2, 4);
            wash(g, p);
            g.draw(p);
            g.draw(poly(false, 5.6, 8.2, 7.4, 10, 10.8, 6.2));
        });
    }

    public static Icon terminal(Color color, int size) {
        return grid16("term", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(1.8, 2.5, 12.4, 11);
            wash(g, r);
            g.draw(r);
            g.draw(poly(false, 4.2, 6, 6.8, 8.2, 4.2, 10.4));
            line(g, 8.2, 10.4, 11.8, 10.4);
        });
    }

    public static Icon sidebar(Color color, int size) {
        return grid16("sidebar", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(2, 2.2, 12, 11.6);
            wash(g, r);
            g.draw(r);
            line(g, 6.2, 2.2, 6.2, 13.8);
            line(g, 3.6, 5, 4.8, 5);
            line(g, 3.6, 7.8, 4.8, 7.8);
        });
    }

    public static Icon menu(Color color, int size) {
        return grid16("menu", color, size, (g, col) -> {
            g.setStroke(STROKE_BOLD);
            line(g, 2.5, 4, 13.5, 4);
            line(g, 2.5, 8, 13.5, 8);
            line(g, 2.5, 12, 10.5, 12);
        });
    }

    public static Icon home(Color color, int size) {
        return grid16("home", color, size, (g, col) -> {
            Path2D p = poly(true, 2, 7.6, 8, 2.2, 14, 7.6, 12.5, 7.6, 12.5, 13.8, 3.5, 13.8, 3.5, 7.6);
            wash(g, p);
            g.draw(p);
            rect(g, 6.6, 9.6, 2.8, 4.2);
        });
    }

    public static Icon layers(Color color, int size) {
        return grid16("layers", color, size, (g, col) -> {
            Path2D top = poly(true, 8, 2.2, 14, 5.6, 8, 9, 2, 5.6);
            wash(g, top);
            g.draw(top);
            g.draw(poly(false, 2, 8.8, 8, 12.2, 14, 8.8));
            g.draw(poly(false, 2, 11.6, 8, 14.4, 14, 11.6));
        });
    }

    public static Icon sitemap(Color color, int size) {
        return grid16("sitemap", color, size, (g, col) -> {
            Rectangle2D top = new Rectangle2D.Double(6, 2, 4, 3.2);
            wash(g, top);
            g.draw(top);
            rect(g, 2, 10.8, 3.4, 3);
            rect(g, 6.3, 10.8, 3.4, 3);
            rect(g, 10.6, 10.8, 3.4, 3);
            line(g, 8, 5.2, 8, 10.8);
            line(g, 3.7, 8, 12.3, 8);
            line(g, 3.7, 8, 3.7, 10.8);
            line(g, 12.3, 8, 12.3, 10.8);
        });
    }

    public static Icon inputNode(Color color, int size) {
        return grid16("inputNode", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(1.8, 4.2, 12.4, 7.6);
            wash(g, r);
            g.draw(r);
            line(g, 5, 6, 5, 10);
        });
    }

    public static Icon dropdownNode(Color color, int size) {
        return grid16("dropNode", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(1.8, 3.5, 12.4, 9);
            wash(g, r);
            g.draw(r);
            line(g, 9.6, 3.5, 9.6, 12.5);
            g.draw(poly(false, 10.8, 7.2, 12, 8.8, 13.2, 7.2));
            line(g, 3.8, 8, 7.8, 8);
        });
    }

    public static Icon toggleNode(Color color, int size) {
        return grid16("toggleNode", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(1.8, 4.8, 12.4, 6.4);
            wash(g, r);
            g.draw(r);
            fillRect(g, 9.2, 6.2, 3.6, 3.6);
        });
    }

    public static Icon creditCard(Color color, int size) {
        return grid16("card", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(1.8, 3.4, 12.4, 9.2);
            wash(g, r);
            g.draw(r);
            line(g, 1.8, 6.5, 14.2, 6.5);
            line(g, 4, 10, 7.4, 10);
        });
    }

    public static Icon receipt(Color color, int size) {
        return grid16("receipt", color, size, (g, col) -> {
            Path2D p = poly(true, 3, 2, 13, 2, 13, 14, 10.8, 12.4, 8, 14, 5.2, 12.4, 3, 14);
            wash(g, p);
            g.draw(p);
            line(g, 5.2, 5.5, 10.8, 5.5);
            line(g, 5.2, 8.5, 9.2, 8.5);
        });
    }

    public static Icon mail(Color color, int size) {
        return grid16("mail", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(1.8, 3.2, 12.4, 9.6);
            wash(g, r);
            g.draw(r);
            g.draw(poly(false, 2.2, 3.8, 8, 8.6, 13.8, 3.8));
        });
    }

    public static Icon chat(Color color, int size) {
        return grid16("chat", color, size, (g, col) -> {
            Path2D p = poly(true, 2, 2.5, 14, 2.5, 14, 11.2, 6.8, 11.2, 3.2, 14, 3.2, 11.2, 2, 11.2);
            wash(g, p);
            g.draw(p);
            line(g, 4.6, 5.8, 11.4, 5.8);
            line(g, 4.6, 8.4, 9.2, 8.4);
        });
    }

    public static Icon calendar(Color color, int size) {
        return grid16("calendar", color, size, (g, col) -> {
            Rectangle2D r = new Rectangle2D.Double(2, 3.5, 12, 10.5);
            wash(g, r);
            g.draw(r);
            line(g, 2, 6.8, 14, 6.8);
            line(g, 5.2, 1.8, 5.2, 4.5);
            line(g, 10.8, 1.8, 10.8, 4.5);
            fillRect(g, 4.5, 9, 2, 2);
            fillRect(g, 8.5, 9, 2, 2);
        });
    }

    public static Icon chart(Color color, int size) {
        return grid16("chart", color, size, (g, col) -> {
            line(g, 2, 13.8, 14, 13.8);
            Rectangle2D b1 = new Rectangle2D.Double(3, 8.5, 2.4, 5.3);
            Rectangle2D b2 = new Rectangle2D.Double(6.8, 5, 2.4, 8.8);
            Rectangle2D b3 = new Rectangle2D.Double(10.6, 2.5, 2.4, 11.3);
            wash(g, b1);
            wash(g, b2);
            wash(g, b3);
            g.draw(b1);
            g.draw(b2);
            g.draw(b3);
        });
    }

    public static Icon server(Color color, int size) {
        return grid16("server", color, size, (g, col) -> {
            Rectangle2D top = new Rectangle2D.Double(2, 2.4, 12, 4.8);
            Rectangle2D bot = new Rectangle2D.Double(2, 8.8, 12, 4.8);
            wash(g, top);
            wash(g, bot);
            g.draw(top);
            g.draw(bot);
            fillRect(g, 4, 4.2, 1.5, 1.4);
            fillRect(g, 4, 10.6, 1.5, 1.4);
            line(g, 7.5, 4.8, 12, 4.8);
            line(g, 7.5, 11.2, 12, 11.2);
        });
    }

    public static Icon webhook(Color color, int size) {
        return grid16("webhook", color, size, (g, col) -> {
            oval(g, 6, 2, 4, 4);
            oval(g, 2, 10, 4, 4);
            oval(g, 10, 10, 4, 4);
            line(g, 7.2, 5.8, 4.6, 10.2);
            line(g, 8.8, 5.8, 11.4, 10.2);
            line(g, 6, 12, 10, 12);
        });
    }

    public static Icon flag(Color color, int size) {
        return grid16("flag", color, size, (g, col) -> {
            line(g, 3.2, 2, 3.2, 14.2);
            Path2D f = poly(true, 3.2, 2.4, 13.4, 2.4, 11.2, 5.8, 13.4, 9.2, 3.2, 9.2);
            wash(g, f);
            g.draw(f);
        });
    }

    // ------------------------------------------------------------------ NodeType resolution & custom icons
    public static Theme.Token tokenForKey(Theme t, String colorKey) {
        return t.tokenForColorKey(colorKey, null);
    }

    public static Theme.Token tokenForNodeType(Theme t, NodeTypeCatalog.NodeType nt) {
        if (nt == null) {
            return t.info;
        }
        return t.tokenForColorKey(nt.colorKey(), nt.customHex());
    }

    /** Resolves a NodeType's icon (embedded data-URI or file image, monogram 3 styles, or 42 vector icons). */
    public static Icon nodeIcon(NodeTypeCatalog.NodeType nt, Theme t, int size) {
        if (nt == null) {
            return folder(t.info.text(), size);
        }
        Theme.Token tok = tokenForNodeType(t, nt);
        if ("image".equals(nt.iconKind()) && nt.imagePath() != null && !nt.imagePath().isBlank()) {
            Icon img = imageIcon(nt.imagePath(), size);
            if (img != null) {
                return img;
            }
        }
        if ("monogram".equals(nt.iconKind()) && nt.monogram() != null && !nt.monogram().isBlank()) {
            return monogramIcon(nt.monogram(), tok, nt.monogramStyle(), size);
        }
        Icon vec = vectorIcon(nt.iconKind(), tok.text(), nt.boxed() ? Math.max(10, size - 4) : size);
        if (nt.boxed()) {
            return boxedIcon(vec, tok, size);
        }
        return vec;
    }

    public static Icon vectorIcon(String kind, Color color, int size) {
        if (kind == null) {
            return folder(color, size);
        }
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "folder-open" -> folderOpen(color, size);
            case "page" -> document(color, size);
            case "tab" -> tabNode(color, size);
            case "sidebar" -> sidebar(color, size);
            case "menu" -> menu(color, size);
            case "link" -> link(color, size);
            case "home" -> home(color, size);
            case "layers" -> layers(color, size);
            case "sitemap" -> sitemap(color, size);
            case "button" -> buttonNode(color, size);
            case "icon" -> iconNode(color, size);
            case "modal" -> modalNode(color, size);
            case "form" -> formNode(color, size);
            case "input" -> inputNode(color, size);
            case "search" -> search(color, size);
            case "dropdown" -> dropdownNode(color, size);
            case "toggle" -> toggleNode(color, size);
            case "upload" -> export(color, size);
            case "download" -> download(color, size);
            case "user" -> role(color, size);
            case "users" -> users(color, size);
            case "cart" -> cart(color, size);
            case "credit-card" -> creditCard(color, size);
            case "receipt" -> receipt(color, size);
            case "bell" -> bell(color, size);
            case "mail" -> mail(color, size);
            case "chat" -> chat(color, size);
            case "calendar" -> calendar(color, size);
            case "chart" -> chart(color, size);
            case "api" -> api(color, size);
            case "database" -> database(color, size);
            case "server" -> server(color, size);
            case "terminal" -> terminal(color, size);
            case "code" -> codeBraces(color, size);
            case "webhook" -> webhook(color, size);
            case "lock" -> lock(color, size);
            case "key" -> key(color, size);
            case "shield" -> shield(color, size);
            case "star" -> star(color, size);
            case "flag" -> flag(color, size);
            case "tag" -> tag(color, size);
            case "globe" -> globe(color, size);
            case "cluster" -> cluster(color, size);
            case "focus" -> focus(color, size);
            default -> folder(color, size);
        };
    }

    private static Icon boxedIcon(Icon inner, Theme.Token tok, int size) {
        return new Drawn(size, size) {
            @Override
            void draw(Component c, Graphics2D g, int x, int y) {
                g.setColor(tok.fill());
                g.fillRect(x, y, size - 1, size - 1);
                g.setColor(tok.border());
                g.drawRect(x, y, size - 1, size - 1);
                int ix = x + (size - inner.getIconWidth()) / 2;
                int iy = y + (size - inner.getIconHeight()) / 2;
                inner.paintIcon(c, g, ix, iy);
            }
        };
    }

    public static synchronized Icon monogramIcon(String letters, Theme.Token tok, String style, int size) {
        String st = style == null ? "soft" : style.trim().toLowerCase(Locale.ROOT);
        String key = "mono2|" + letters + "|" + st + "|" + size + "|" + tok.text().getRGB() + "|" + tok.solidFill().getRGB();
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
                FontManager.applyTextHints(g);
                if ("solid".equals(st)) {
                    g.setColor(tok.solidFill());
                    g.fillRect(x, y, size, size);
                    g.setColor(tok.onSolid());
                } else if ("outline".equals(st)) {
                    g.setColor(tok.border());
                    g.drawRect(x, y, size - 1, size - 1);
                    g.setColor(tok.text());
                } else {
                    g.setColor(tok.fillStrong());
                    g.fillRect(x, y, size - 1, size - 1);
                    g.setColor(tok.border());
                    g.drawRect(x, y, size - 1, size - 1);
                    g.setColor(tok.text());
                }
                float fsz = letters.length() >= 3 ? Math.max(7.5f, size * 0.42f)
                        : letters.length() == 2 ? Math.max(8f, size * 0.50f) : Math.max(9f, size * 0.62f);
                Font f = Theme.current().boldFont.deriveFont(fsz);
                g.setFont(f);
                FontMetrics fm = g.getFontMetrics();
                int tw = fm.stringWidth(letters);
                g.drawString(letters, x + Math.max(1, (size - tw) / 2), y + (size - fm.getHeight()) / 2 + fm.getAscent());
            }
        };
        CACHE.put(key, made);
        return made;
    }

    /** Encodes a user-selected image file into a self-contained 48x48 PNG data URI so workspace icons never break. */
    public static String encodeImageDataUri(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try {
            BufferedImage raw = ImageIO.read(file);
            if (raw == null) {
                return "";
            }
            int target = 48;
            BufferedImage scaled = new BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaled.createGraphics();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.drawImage(raw, 0, 0, target, target, null);
            } finally {
                g2.dispose();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", baos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception ignored) {
            return "";
        }
    }

    public static synchronized Icon imageIcon(String pathOrDataUri, int size) {
        if (pathOrDataUri == null || pathOrDataUri.isBlank()) {
            return null;
        }
        String key = "img2|" + pathOrDataUri.hashCode() + "|" + pathOrDataUri.length() + "|" + size;
        Icon have = CACHE.get(key);
        if (have != null) {
            return have;
        }
        try {
            BufferedImage raw;
            if (pathOrDataUri.startsWith("data:image/")) {
                int comma = pathOrDataUri.indexOf(',');
                if (comma < 0) {
                    return null;
                }
                byte[] bytes = Base64.getDecoder().decode(pathOrDataUri.substring(comma + 1));
                raw = ImageIO.read(new ByteArrayInputStream(bytes));
            } else {
                File f = new File(pathOrDataUri);
                if (!f.isFile()) {
                    return null;
                }
                raw = ImageIO.read(f);
            }
            if (raw == null) {
                return null;
            }
            Image scaled = raw.getScaledInstance(size, size, Image.SCALE_SMOOTH);
            Icon made = new ImageIcon(scaled);
            if (CACHE.size() > MAX_CACHE) {
                CACHE.clear();
            }
            CACHE.put(key, made);
            return made;
        } catch (Exception ignored) {
            return null;
        }
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
    public static Icon chevronUp(Theme t) { return chevronUp(t.fg); }
    public static Icon chevronRight(Theme t) { return chevronRight(t.fg); }
    public static Icon codeBraces(Theme t) { return codeBraces(t.fg); }
    public static Icon cluster(Theme t) { return cluster(t.accent.text(), 14); }
    public static Icon focus(Theme t) { return focus(t.info.text(), 14); }
    public static Icon tag(Theme t) { return tag(t.warning.text(), 14); }
    public static Icon star(Theme t) { return star(t.accent.text(), 14); }
    public static Icon compare(Theme t) { return compare(t.info.text(), 14); }
    public static Icon originCrown(Theme t) { return originCrown(t.info.text(), 14); }
    public static Icon lineageGraph(Theme t) { return lineageGraph(t.dup.text(), 14); }
    public static Icon workbench(Theme t) { return workbench(t.accent.text(), 14); }
    public static Icon displayMode(Theme t) { return displayMode(t.fg, 14); }
    public static Icon idPool(Theme t) { return idPool(t.success.text(), 14); }
    public static Icon palette(Theme t) { return palette(t.accent.text(), 14); }
    public static Icon upload(Color c) { return upload(c, 14); }
    public static Icon upload(Color c, int size) { return vectorIcon("upload", c, size); }
    public static Icon upload(Theme t) { return upload(t.fg, 14); }
    public static Icon download(Theme t) { return download(t.fg, 14); }
    public static Icon strategyShield(Theme t) { return strategyShield(t.warning.text(), 14); }
    public static Icon strategySnapshot(Theme t) { return strategySnapshot(t.info.text(), 14); }
    public static Icon strategySwap(Theme t) { return strategySwap(t.success.text(), 14); }
    public static Icon strategyChain(Theme t) { return strategyChain(t.accent.text(), 14); }
    public static Icon playTest(Theme t) { return playTest(t.accent.text(), 14); }

    public static Icon boxedVectorIcon(String kind, Theme.Token tok, int size) {
        Icon inner = vectorIcon(kind, tok.text(), Math.max(10, size - 4));
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setColor(tok.fill());
                    g2.fillRect(x, y, size - 1, size - 1);
                    g2.setColor(tok.border());
                    g2.drawRect(x, y, size - 1, size - 1);
                    inner.paintIcon(c, g2, x + (size - inner.getIconWidth()) / 2, y + (size - inner.getIconHeight()) / 2);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

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

    public static Icon forLeadingSymbol(String text, Theme theme) {
        return forLeadingSymbol(text, theme, null);
    }

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
        if (text.startsWith("\u2605")) {
            return originCrown(ov != null ? ov : theme.info.text(), 12);
        }
        return null;
    }

    public static String stripLeadingSymbol(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (!containsSymbol(s)) {
            return s;
        }
        String t = s.trim();
        for (String p : LEADING_PREFIXES) {
            if (t.startsWith(p)) {
                return stripInlineSymbols(t.substring(p.length()).trim());
            }
        }
        return stripInlineSymbols(t);
    }

    public static String stripInlineSymbols(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (!containsSymbol(s)) {
            return s;
        }
        String out = s.replace("[\u29C9", "[#")
                .replace("\u21AA ", "-> ")
                .replace("\u21AA", "-> ")
                .replace("\u2192", "->");
        for (String tok : INLINE_REMOVE) {
            if (out.contains(tok)) {
                out = out.replace(tok, "");
            }
        }
        return out;
    }

    private static boolean containsSymbol(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x2190) {
                return true;
            }
        }
        return false;
    }

    private static final String[] LEADING_PREFIXES = {"\u2714 ", "\u2705 ", "\u23F3 ", "\uD83D\uDC7B ", "\u29C9 ", "\uD83D\uDE48 ", "\uD83D\uDC41 ",
        "\u21AA ", "\u21A9 ", "\u21B6 ", "\uD83D\uDDD1 ", "\u2716 ", "\u270F ", "\u2795 ", "\u267B ",
        "\uD83D\uDCCD ", "\uD83C\uDD95 ", "\u2B1C ", "\uD83D\uDCC1 ", "\uD83D\uDCC2 ", "\uD83E\uDDED ",
        "\uD83D\uDD0E ", "\uD83D\uDD0D ", "\u25B6\u25B6 ", "\u25B6 ", "\uD83D\uDCE4 ", "\uD83C\uDFAF ",
        "\uD83D\uDCCB ", "\uD83C\uDF9B ", "\u2699 ", "\uD83D\uDDD7 ", "\uD83D\uDCCC ", "\uD83D\uDCCA ",
        "\uD83D\uDCC4 ", "\uD83D\uDCBE ", "\uD83D\uDD04 ", "\uD83E\uDDF9 ", "\uD83C\uDF3F ", "\uD83C\uDF33 ",
        "\uD83C\uDF10 ", "\u26A1 ", "\u279C ", "\u2B06 ", "\u2B07 ", "\uD83D\uDD58 ", "\uFF0B ",
        "\uD83D\uDEE0 ", "\u21BA ", "\u22EF ", "\u25A0 ", "\u21BB ", "\uD83C\uDF89 ", "\u2605 "};

    private static final String[] INLINE_REMOVE = {"\u29C9 ", "\u29C9", "\u23F3 ", "\u23F3", "\uD83D\uDC7B ", "\uD83D\uDC7B", "\uD83D\uDE48 ",
        "\uD83D\uDE48", "\u2714 ", "\u2714", "\u2705 ", "\u2705", "\u21A9 ", "\u21A9", "\u21B6 ", "\u21B6",
        "\uD83D\uDCC1 ", "\uD83D\uDCC1", "\uD83D\uDCC2 ", "\uD83D\uDCC2", "\uD83D\uDCCD ", "\uD83D\uDCCD",
        "\uD83C\uDD95 ", "\uD83C\uDD95", "\uD83C\uDF89 ", "\uD83C\uDF89", "\uD83D\uDD0E ", "\uD83D\uDD0E",
        "\uD83D\uDD0D ", "\uD83D\uDD0D", "\uD83D\uDC41 ", "\uD83D\uDC41", "\uD83D\uDDD1 ", "\uD83D\uDDD1",
        "\u2716 ", "\u2716", "\uD83D\uDCCB ", "\uD83D\uDCCB", "\uD83E\uDDED ", "\uD83E\uDDED", "\uD83C\uDF3F ",
        "\uD83C\uDF3F", "\uD83D\uDCCC ", "\uD83D\uDCCC", "\uD83C\uDF10 ", "\uD83C\uDF10", "\u2B1C ", "\u2B1C",
        "\u267B ", "\u267B", "\u270F ", "\u270F", "\u2795 ", "\u2795", "\u2605 ", "\u2605"};
}
