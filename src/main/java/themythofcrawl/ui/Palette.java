package themythofcrawl.ui;

import java.awt.Color;

import javax.swing.border.Border;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.model.RuleMode;
import themythofcrawl.ui.theme.Theme;

/**
 * Legacy adapter delegating all colour / contrast calculations to {@link Theme}.
 */
public final class Palette {
    public final Theme theme;
    public final boolean dark;
    public final Color fg;
    public final Color bg;
    public final Color[] zebra;
    public final Color grid;
    public final Color muted;
    public final Color origFg;
    public final Color origAccent;
    public final Color dotNone;
    public final Border pad;

    public static Color mix(Color a, Color b, double t) {
        return Theme.mix(a, b, t);
    }

    public static Palette of(Color bg, Color fg) {
        return new Palette(Theme.of(bg, fg));
    }

    /** From the active Look &amp; Feel ({@link Theme#current()}). */
    public static Palette ui() {
        return new Palette(Theme.current());
    }

    private Palette(Theme theme) {
        this.theme = theme;
        this.dark = theme.dark;
        this.fg = theme.fg;
        this.bg = theme.bg;
        this.zebra = theme.zebra;
        this.grid = theme.grid;
        this.muted = theme.muted;
        this.origAccent = theme.orig.base();
        this.origFg = theme.orig.text();
        this.dotNone = theme.dotNone;
        this.pad = theme.cellPad;
    }

    /** [fill, edge, text, hue] of a folder at 1-based depth. */
    public Color[] level(int depth) {
        Theme.Token t = theme.level(depth);
        return new Color[] {t.fill(), t.border(), t.text(), t.base()};
    }

    public Color dot(AuditLevel l) {
        return l == null || l == AuditLevel.NONE ? dotNone : theme.audit(l).base();
    }

    public boolean tinted(AuditLevel l) {
        return theme.tinted(l);
    }

    public Color background(AuditLevel l, int viewRow) {
        return theme.rowBackground(l, viewRow);
    }

    public Color auditFg(AuditLevel l) {
        return l == null || l == AuditLevel.NONE ? fg : theme.audit(l).text();
    }

    public Color methodFg(String method) {
        Theme.Token t = theme.method(method);
        return t == theme.pending ? fg : t.text();
    }

    public Color statusFg(int code) {
        return theme.statusColor(code);
    }

    /** [fill, text, edge] chip colours of a rule mode. */
    public Color[] mode(RuleMode m) {
        Theme.Token t = theme.mode(m);
        return new Color[] {t.fill(), t.text(), t.border()};
    }

    public Color accent(Object key) {
        return theme.branchAccent(key);
    }

    /** Left colour bar marking which branch (or the original request) a row belongs to. */
    public Border stripe(Object key) {
        return theme.stripe(key);
    }
}
