package themythofcrawl.ui.kit;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.JTextArea;

import themythofcrawl.ui.theme.Theme;

/**
 * Pure-Swing word-wrapping multi-line text component that NEVER uses HTML labels.
 * Burp Suite sets {@code UIManager.put("html.disable", Boolean.TRUE)} which causes
 * {@code JLabel("<html>...")} to display raw HTML tags and escaped entities.
 * {@code WrappedText} wraps cleanly using native {@code JTextArea} line-wrapping and
 * computes accurate preferred height for any container width without clipping or blank voids.
 */
public final class WrappedText extends JTextArea {
    private final Function<Theme, Font> fontFn;
    private final Function<Theme, Color> colorFn;
    private int fallbackWidthPx;

    public WrappedText(String text, Function<Theme, Font> fontFn, Function<Theme, Color> colorFn, int fallbackWidthPx) {
        super(text == null ? "" : text);
        this.fontFn = fontFn != null ? fontFn : t -> t.baseFont;
        this.colorFn = colorFn != null ? colorFn : t -> t.fg;
        this.fallbackWidthPx = Math.max(120, fallbackWidthPx);
        configure();
    }

    public static WrappedText body(String text, int fallbackWidthPx) {
        return new WrappedText(text, t -> t.baseFont, t -> t.fg, fallbackWidthPx);
    }

    public static WrappedText small(String text, int fallbackWidthPx) {
        return new WrappedText(text, t -> t.smallFont, t -> t.fg, fallbackWidthPx);
    }

    public static WrappedText muted(String text, int fallbackWidthPx) {
        return new WrappedText(text, t -> t.smallFont, t -> t.muted, fallbackWidthPx);
    }

    private void configure() {
        setLineWrap(true);
        setWrapStyleWord(true);
        setEditable(false);
        setFocusable(false);
        setOpaque(false);
        setDragEnabled(false);
        setHighlighter(null);
        setMargin(new Insets(0, 0, 0, 0));
        setBorder(BorderFactory.createEmptyBorder());
        setAlignmentX(LEFT_ALIGNMENT);
        applyTheme();
    }

    private void applyTheme() {
        Theme th = Theme.current();
        if (fontFn != null) {
            setFont(fontFn.apply(th));
        }
        if (colorFn != null) {
            setForeground(colorFn.apply(th));
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setLineWrap(true);
        setWrapStyleWord(true);
        setEditable(false);
        setFocusable(false);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        applyTheme();
    }

    @Override
    public Dimension getPreferredSize() {
        Font f = getFont();
        if (f == null) {
            return super.getPreferredSize();
        }
        FontMetrics fm = getFontMetrics(f);
        Insets ins = getInsets();
        int avail = getWidth() - ins.left - ins.right;
        if (avail <= 24 && getParent() != null && getParent().getWidth() > 48) {
            Insets pins = getParent().getInsets();
            avail = getParent().getWidth() - pins.left - pins.right - ins.left - ins.right - 8;
        }
        int wrapW = avail > 24 ? avail : fallbackWidthPx;
        int lines = countWrappedLines(getText(), fm, wrapW);
        int h = Math.max(fm.getHeight(), lines * fm.getHeight()) + ins.top + ins.bottom + 2;
        return new Dimension(Math.min(wrapW + ins.left + ins.right, fallbackWidthPx + ins.left + ins.right), h);
    }

    @Override
    public Dimension getMinimumSize() {
        Dimension pref = getPreferredSize();
        return new Dimension(Math.min(100, pref.width), pref.height);
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension pref = getPreferredSize();
        return new Dimension(Integer.MAX_VALUE, pref.height + 4);
    }

    private static int countWrappedLines(String text, FontMetrics fm, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        int width = Math.max(40, maxWidth);
        int totalLines = 0;
        String[] paragraphs = text.split("\n", -1);
        for (String para : paragraphs) {
            if (para.isEmpty()) {
                totalLines++;
                continue;
            }
            int lineWidth = 0;
            int paraLines = 1;
            String[] words = para.split("(?<=\\s)");
            for (String word : words) {
                int ww = fm.stringWidth(word);
                if (lineWidth > 0 && lineWidth + ww > width) {
                    paraLines++;
                    lineWidth = ww;
                } else {
                    lineWidth += ww;
                }
            }
            totalLines += paraLines;
        }
        return Math.max(1, totalLines);
    }
}
