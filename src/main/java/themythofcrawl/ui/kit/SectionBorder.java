package themythofcrawl.ui.kit;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;

import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;

import themythofcrawl.ui.theme.Theme;

/**
 * Burp Suite style section header: bold title on a single hairline at the top, no box around the
 * content. Colours / fonts are read from {@link Theme#current()} at paint time, so Light / Dark
 * switches need no rebuild. Drop-in replacement for {@code BorderFactory.createTitledBorder(title)}.
 */
public final class SectionBorder extends TitledBorder {
    private static final Border TOP_RULE = new AbstractBorder() {
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            g.setColor(Theme.current().border);
            g.drawLine(x, y, x + w - 1, y);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(1, 0, 0, 0);
            return insets;
        }
    };

    public SectionBorder(String title) {
        super(TOP_RULE, title, TitledBorder.LEADING, TitledBorder.TOP);
    }

    public static SectionBorder of(String title) {
        return new SectionBorder(title);
    }

    @Override
    public Font getTitleFont() {
        return Theme.current().smallBoldFont;
    }

    @Override
    public Color getTitleColor() {
        return Theme.current().muted;
    }

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        Insets in = super.getBorderInsets(c, insets);
        // keep content tight to the edges like Burp panels (TitledBorder adds 2 px EDGE_SPACING all round)
        in.left = Math.max(0, in.left - 2);
        in.right = Math.max(0, in.right - 2);
        in.bottom = Math.max(0, in.bottom - 2);
        return in;
    }
}
