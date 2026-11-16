package themythofcrawl.ui.kit;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import themythofcrawl.ui.theme.Theme;

/**
 * Compact KPI card with a prominent metric value, muted label, optional subtitle and optional
 * thin progress bar tinted by a {@link Theme.Token}.
 */
public class StatCard extends Card {
    private final JLabel valueLabel = new JLabel("0");
    private final JLabel titleLabel = new JLabel(" ");
    private final JLabel noteLabel = new JLabel();
    private final ProgressBar bar = new ProgressBar();
    private Function<Theme, Theme.Token> metricToken;
    private double ratio = -1;

    public StatCard(String title, String value) {
        this(title, value, null, t -> t.neutral, -1);
    }

    public StatCard(String title, String value, String note, Function<Theme, Theme.Token> metricToken, double ratio) {
        super(metricToken, false);
        this.metricToken = metricToken != null ? metricToken : t -> t.neutral;
        this.ratio = ratio;
        setPadding(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3);

        JPanel stack = new JPanel(new BorderLayout(0, 2));
        stack.setOpaque(false);
        titleLabel.setText(title == null ? "" : title);
        valueLabel.setText(value == null ? "" : value);
        noteLabel.setText(note == null ? "" : note);
        noteLabel.setVisible(note != null && !note.isEmpty());
        stack.add(titleLabel, BorderLayout.NORTH);
        stack.add(valueLabel, BorderLayout.CENTER);
        stack.add(noteLabel, BorderLayout.SOUTH);

        JPanel wrap = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        wrap.setOpaque(false);
        wrap.add(stack, BorderLayout.CENTER);
        wrap.add(bar, BorderLayout.SOUTH);
        bar.setVisible(ratio >= 0);
        setBody(wrap);
        applyFontsAndColors();
    }

    public StatCard setMetric(String value, String note, double ratio) {
        valueLabel.setText(value == null ? "" : value);
        noteLabel.setText(note == null ? "" : note);
        noteLabel.setVisible(note != null && !note.isEmpty());
        this.ratio = ratio;
        bar.setVisible(ratio >= 0);
        bar.repaint();
        return this;
    }

    public StatCard setMetricToken(Function<Theme, Theme.Token> tokenFn) {
        this.metricToken = tokenFn != null ? tokenFn : t -> t.neutral;
        setAccentToken(this.metricToken, false);
        applyFontsAndColors();
        return this;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (valueLabel != null) {
            applyFontsAndColors();
        }
    }

    private void applyFontsAndColors() {
        Theme t = Theme.current();
        Theme.Token tok = metricToken != null ? metricToken.apply(t) : t.neutral;
        valueLabel.setFont(t.headingFont.deriveFont(Font.BOLD, t.headingFont.getSize2D() + 3f));
        valueLabel.setForeground(tok == t.neutral ? t.fg : tok.text());
        titleLabel.setFont(t.smallFont);
        titleLabel.setForeground(t.muted);
        noteLabel.setFont(t.smallFont);
        noteLabel.setForeground(t.muted);
        noteLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
    }

    private final class ProgressBar extends JComponent {
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(60, Math.max(4, Theme.current().scaled(4)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (ratio < 0) {
                return;
            }
            Theme t = Theme.current();
            Theme.Token tok = metricToken != null ? metricToken.apply(t) : t.accent;
            double clamped = Math.max(0.0, Math.min(1.0, ratio));
            int w = getWidth();
            int h = getHeight();
            int fillW = (int) Math.round(w * clamped);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(t.border);
                g2.fillRoundRect(0, 0, w, h, h, h);
                if (fillW > 0) {
                    g2.setColor(tok.solidFill());
                    g2.fillRoundRect(0, 0, Math.max(h, fillW), h, h, h);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
