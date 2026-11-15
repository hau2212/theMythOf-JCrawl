package themythofcrawl.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JTable;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Colours derived from the component's own background / foreground, so one scheme reads well in
 * Burp's light and dark themes: zebra rows, coloured methods / status codes, a colour per branch level.
 */
public final class Palette {
    private static final int[][] LEVELS = {
        {37, 99, 235}, {13, 148, 136}, {147, 51, 234}, {217, 119, 6}, {219, 39, 119}, {22, 163, 74}, {71, 85, 105}};
    private static final Map<String, int[]> METHODS = Map.of(
            "GET", new int[] {25, 135, 70}, "POST", new int[] {20, 100, 200}, "PUT", new int[] {185, 105, 0},
            "PATCH", new int[] {125, 75, 190}, "DELETE", new int[] {200, 40, 40});
    private static final Map<Long, Palette> CACHE = new HashMap<>();

    public final boolean dark;
    public final Color fg;
    public final Color muted;
    public final Color zebra0;
    public final Color zebra1;
    public final Color grid;
    public final Color ok;
    public final Color info;
    public final Color error;
    public final Color redirect;
    public final Color okRow;
    public final Color infoRow;
    public final Color redirectRow;

    public static Color mix(Color a, Color b, double t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    public static synchronized Palette of(Color bg, Color fg) {
        long key = ((long) bg.getRGB() << 32) ^ (fg.getRGB() & 0xffffffffL);
        return CACHE.computeIfAbsent(key, k -> new Palette(bg, fg));
    }

    private Palette(Color bg, Color fg) {
        this.dark = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) < 128000;
        this.fg = fg;
        this.muted = mix(fg, bg, 0.42);
        this.zebra0 = bg;
        this.zebra1 = mix(bg, fg, 0.06);
        this.grid = mix(bg, fg, 0.14);
        this.ok = tone(new Color(55, 175, 90));
        this.info = tone(new Color(240, 160, 20));
        this.error = tone(new Color(225, 60, 60));
        this.redirect = tone(new Color(65, 135, 235));
        this.okRow = mix(bg, new Color(55, 175, 90), dark ? 0.20 : 0.12);
        this.infoRow = mix(bg, new Color(240, 185, 30), dark ? 0.20 : 0.14);
        this.redirectRow = mix(bg, new Color(65, 135, 235), dark ? 0.20 : 0.11);
    }

    private Color tone(Color hue) {
        return dark ? mix(hue, Color.WHITE, 0.35) : mix(hue, Color.BLACK, 0.40);
    }

    public Color method(String m) {
        int[] rgb = METHODS.get(m == null ? "" : m.toUpperCase(Locale.ROOT));
        if (rgb == null) {
            return fg;
        }
        Color c = new Color(rgb[0], rgb[1], rgb[2]);
        return dark ? mix(c, Color.WHITE, 0.40) : c;
    }

    public Color status(int code) {
        if (code >= 200 && code < 300) {
            return ok;
        }
        if (code >= 300 && code < 400) {
            return redirect;
        }
        if (code >= 400 && code < 500) {
            return info;
        }
        if (code >= 500) {
            return error;
        }
        return muted;
    }

    /** Hue of a branch / folder level (1 = top); same level, same colour everywhere. */
    public static Color levelHue(int level) {
        int[] rgb = LEVELS[(Math.max(level, 1) - 1) % LEVELS.length];
        return new Color(rgb[0], rgb[1], rgb[2]);
    }

    public Color levelText(int level) {
        Color hue = levelHue(level);
        return dark ? mix(hue, Color.WHITE, 0.55) : mix(hue, Color.BLACK, 0.30);
    }

    public Color levelFill(int level, Color bg) {
        return mix(bg, levelHue(level), dark ? 0.20 : 0.10);
    }

    /**
     * Table renderer: zebra rows (tinted by {@link #rowTint}), padded cells, bold + coloured method,
     * status and chosen columns. Columns are model indexes.
     */
    public abstract static class Renderer extends DefaultTableCellRenderer {
        private final int methodCol;
        private final int statusCol;
        private final java.util.Set<Integer> boldCols;
        private final Border pad = BorderFactory.createEmptyBorder(0, 6, 0, 6);
        private Font plain;
        private Font bold;

        protected Renderer(int methodCol, int statusCol, Integer... boldCols) {
            this.methodCol = methodCol;
            this.statusCol = statusCol;
            this.boldCols = java.util.Set.of(boldCols);
        }

        /** Row background override (null = zebra). */
        protected Color rowTint(Palette p, int modelRow) {
            return null;
        }

        /** Foreground override for a cell (null = default rules). */
        protected Color cellColor(Palette p, int modelRow, int modelCol, Object value) {
            return null;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, selected, false, row, column);
            int mr = table.convertRowIndexToModel(row);
            int mc = table.convertColumnIndexToModel(column);
            Palette p = Palette.of(table.getBackground(), table.getForeground());
            Font f = table.getFont();
            if (plain != f) {
                plain = f;
                bold = f.deriveFont(Font.BOLD);
            }
            c.setFont(mc == methodCol || mc == statusCol || boldCols.contains(mc) ? bold : plain);
            setBorder(pad);
            if (selected) {
                return c;
            }
            Color tint = rowTint(p, mr);
            c.setBackground(tint != null ? tint : (row % 2 == 0 ? p.zebra0 : p.zebra1));
            Color fg = cellColor(p, mr, mc, value);
            if (fg == null && mc == methodCol) {
                fg = p.method(String.valueOf(value));
            }
            if (fg == null && mc == statusCol && value instanceof Integer code) {
                fg = p.status(code);
            }
            c.setForeground(fg != null ? fg : p.fg);
            return c;
        }
    }

    public static void styleTable(JTable table) {
        table.setRowHeight(24);
        table.setShowGrid(false);
        table.setShowHorizontalLines(true);
        table.setIntercellSpacing(new java.awt.Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setGridColor(Palette.of(table.getBackground(), table.getForeground()).grid);
        table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD));
    }
}
