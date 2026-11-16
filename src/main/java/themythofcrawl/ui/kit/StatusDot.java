package themythofcrawl.ui.kit;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Function;

import javax.swing.JComponent;

import themythofcrawl.ui.theme.Theme;

/**
 * Crisp 8 px (HiDPI-scaled) status dot painted with Java2D and themed dynamically via {@link Theme}.
 */
public class StatusDot extends JComponent {
    private Function<Theme, Color> colorFn;
    private int diameter;
    private boolean pulseDim;

    public StatusDot(Function<Theme, Color> colorFn) {
        this(colorFn, 8);
    }

    public StatusDot(Function<Theme, Color> colorFn, int diameterPx) {
        this.colorFn = colorFn != null ? colorFn : t -> t.dotNone;
        this.diameter = Math.max(4, diameterPx);
        setOpaque(false);
    }

    public void setColor(Function<Theme, Color> colorFn) {
        this.colorFn = colorFn != null ? colorFn : t -> t.dotNone;
        repaint();
    }

    public void setPulseDim(boolean dim) {
        if (this.pulseDim != dim) {
            this.pulseDim = dim;
            repaint();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        int s = Theme.current().scaled(diameter) + 4;
        return new Dimension(s, s);
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
    public void updateUI() {
        super.updateUI();
        revalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Theme t = Theme.current();
        Color col = colorFn.apply(t);
        if (col == null) {
            col = t.dotNone;
        }
        if (pulseDim) {
            col = Theme.mix(col, t.surface, 0.55);
        }
        int d = t.scaled(diameter);
        int x = Math.max(0, (getWidth() - d) / 2);
        int y = Math.max(0, (getHeight() - d) / 2);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(col);
            g2.fillOval(x, y, d, d);
        } finally {
            g2.dispose();
        }
    }
}
