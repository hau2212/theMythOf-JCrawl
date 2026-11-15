package themythofcrawl.ui;

import java.awt.Color;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.border.Border;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.model.RuleMode;

/**
 * Colours derived from the component's own background / foreground so the same scheme reads well in
 * Burp's light and dark themes: zebra rows tinted by audit level, method / status colours, one accent
 * per branch, one colour per folder level, duplicate purple.
 */
public final class Palette {
    static final Map<AuditLevel, int[]> HUES = new EnumMap<>(Map.of(
            AuditLevel.INFO, new int[] {240, 185, 30}, AuditLevel.OK, new int[] {55, 175, 90},
            AuditLevel.WARN, new int[] {245, 125, 25}, AuditLevel.ERROR, new int[] {225, 60, 60},
            AuditLevel.REDIRECT, new int[] {65, 135, 235}, AuditLevel.PENDING, new int[] {128, 128, 140}));
    static final Map<String, int[]> METHOD_HUES = Map.of("GET", new int[] {25, 135, 70}, "POST", new int[] {20, 100, 200},
            "PUT", new int[] {185, 105, 0}, "PATCH", new int[] {125, 75, 190}, "DELETE", new int[] {200, 40, 40});
    static final int[][] ACCENTS = {{66, 133, 244}, {234, 88, 12}, {22, 163, 74}, {168, 85, 247}, {219, 39, 119},
        {13, 148, 136}, {202, 138, 4}, {100, 116, 139}};
    static final int[][] LEVEL_HUES = {{37, 99, 235}, {13, 148, 136}, {147, 51, 234}, {217, 119, 6}, {219, 39, 119},
        {22, 163, 74}, {71, 85, 105}};
    static final Map<RuleMode, int[]> MODE_HUES = new EnumMap<>(Map.of(RuleMode.REPLACE, new int[] {37, 99, 235},
            RuleMode.ADD, new int[] {22, 163, 74}, RuleMode.REMOVE, new int[] {220, 38, 38},
            RuleMode.COOKIE_SET, new int[] {147, 51, 234}, RuleMode.COOKIE_REMOVE, new int[] {234, 88, 12}));
    public static final Color DUP_PURPLE = new Color(147, 51, 234);
    public static final Color ORANGE = new Color(234, 88, 12);
    public static final Color SEND_ORANGE = new Color(235, 95, 40);
    public static final Color PINK = new Color(219, 39, 119);
    public static final Color GREEN = new Color(22, 163, 74);

    private static final Map<Long, Palette> CACHE = new HashMap<>();

    public final boolean dark;
    public final Color fg;
    public final Color bg;
    public final Color[] zebra;
    public final Color grid;
    public final Color muted;
    public final Color origFg;
    public final Color origAccent;
    public final Color dotNone;
    private final Map<AuditLevel, Color[]> rowBg = new EnumMap<>(AuditLevel.class);
    private final Map<AuditLevel, Color> auditFg = new EnumMap<>(AuditLevel.class);
    private final Map<AuditLevel, Color> dots = new EnumMap<>(AuditLevel.class);
    private final Map<String, Color> methodFg = new HashMap<>();
    private final Color[] accents = new Color[ACCENTS.length];
    private final Color[][] levels = new Color[LEVEL_HUES.length][];
    private final Map<RuleMode, Color[]> modes = new EnumMap<>(RuleMode.class);
    private final Map<Object, Border> stripes = new HashMap<>();
    public final Border pad = BorderFactory.createEmptyBorder(0, 6, 0, 6);

    public static Color mix(Color a, Color b, double t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    static Color rgb(int[] c) {
        return new Color(c[0], c[1], c[2]);
    }

    public static synchronized Palette of(Color bg, Color fg) {
        Color b = bg != null ? bg : new Color(255, 255, 255);
        Color f = fg != null ? fg : new Color(20, 20, 20);
        long key = ((long) b.getRGB() << 32) ^ (f.getRGB() & 0xffffffffL);
        Palette p = CACHE.get(key);
        if (p == null) {
            if (CACHE.size() > 8) {
                CACHE.clear();
            }
            p = new Palette(b, f);
            CACHE.put(key, p);
        }
        return p;
    }

    /** From the look-and-feel (Panel.background / Label.foreground). */
    public static Palette ui() {
        return of(UIManager.getColor("Panel.background"), UIManager.getColor("Label.foreground"));
    }

    private Palette(Color bg, Color fg) {
        this.bg = bg;
        this.fg = fg;
        dark = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) < 128000;
        zebra = new Color[] {bg, mix(bg, fg, 0.07)};
        grid = mix(bg, fg, 0.14);
        Color white = Color.WHITE;
        Color black = Color.BLACK;
        HUES.forEach((lvl, c) -> {
            Color hue = rgb(c);
            rowBg.put(lvl, new Color[] {mix(bg, hue, 0.17), mix(bg, hue, 0.27)});
            auditFg.put(lvl, dark ? mix(hue, white, 0.35) : mix(hue, black, 0.45));
            dots.put(lvl, hue);
        });
        METHOD_HUES.forEach((m, c) -> methodFg.put(m, dark ? mix(rgb(c), white, 0.40) : rgb(c)));
        for (int i = 0; i < ACCENTS.length; i++) {
            accents[i] = rgb(ACCENTS[i]);
        }
        origAccent = new Color(37, 99, 235);
        origFg = dark ? mix(origAccent, white, 0.45) : origAccent;
        muted = mix(fg, bg, 0.42);
        dotNone = mix(bg, fg, 0.35);
        for (int i = 0; i < LEVEL_HUES.length; i++) {
            Color hue = rgb(LEVEL_HUES[i]);
            levels[i] = new Color[] {mix(bg, hue, dark ? 0.20 : 0.10), mix(bg, hue, 0.75),
                dark ? mix(hue, white, 0.55) : mix(hue, black, 0.30), hue};
        }
        MODE_HUES.forEach((m, c) -> {
            Color hue = rgb(c);
            modes.put(m, new Color[] {mix(bg, hue, dark ? 0.22 : 0.12), dark ? mix(hue, white, 0.45) : mix(hue, black, 0.15),
                mix(bg, hue, 0.6)});
        });
    }

    /** [fill, edge, text, hue] of a folder at 1-based depth. */
    public Color[] level(int depth) {
        return levels[(Math.max(depth, 1) - 1) % levels.length];
    }

    public Color dot(AuditLevel l) {
        return dots.getOrDefault(l, dotNone);
    }

    public boolean tinted(AuditLevel l) {
        return rowBg.containsKey(l);
    }

    public Color background(AuditLevel l, int viewRow) {
        Color[] pair = rowBg.getOrDefault(l, zebra);
        return pair[viewRow & 1];
    }

    public Color auditFg(AuditLevel l) {
        return auditFg.getOrDefault(l, fg);
    }

    public Color methodFg(String method) {
        return methodFg.getOrDefault(method == null ? "" : method.toUpperCase(java.util.Locale.ROOT), fg);
    }

    public Color statusFg(int code) {
        if (code >= 200 && code < 300) {
            return auditFg(AuditLevel.OK);
        }
        if (code >= 300 && code < 400) {
            return auditFg(AuditLevel.REDIRECT);
        }
        if (code >= 400 && code < 500) {
            return auditFg(AuditLevel.INFO);
        }
        return code >= 500 ? auditFg(AuditLevel.ERROR) : fg;
    }

    /** [fill, text, edge] chip colours of a rule mode. */
    public Color[] mode(RuleMode m) {
        return modes.get(m);
    }

    public Color accent(Object key) {
        if (key == null) {
            return null;
        }
        if ("ORIG".equals(key)) {
            return origAccent;
        }
        int k = key instanceof Number n ? n.intValue() : Math.abs(key.hashCode());
        return accents[Math.floorMod(k - 1, accents.length)];
    }

    /** Left colour bar marking which branch (or the original request) a row belongs to. */
    public Border stripe(Object key) {
        return stripes.computeIfAbsent(key == null ? "none" : key, k -> {
            Color c = accent(key);
            return c == null ? BorderFactory.createEmptyBorder(0, 10, 0, 6) : BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 4, 0, 0, c), BorderFactory.createEmptyBorder(0, 6, 0, 6));
        });
    }
}
