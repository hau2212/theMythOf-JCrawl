package themythofcrawl.ui.kit;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

import themythofcrawl.ui.theme.Theme;

/**
 * Elevated rounded surface card ({@link Theme#RADIUS_CARD}, {@code surfaceAlt} or tinted fill,
 * 1 px {@code border}, {@link Theme#SPACE_3} inner padding) with optional header title/subtitle and
 * right-aligned controls. Automatically adapts when the theme changes.
 */
public class Card extends JPanel {
    private Function<Theme, Theme.Token> tokenSupplier;
    private boolean subtleTint;
    private Insets innerPadding = new Insets(Theme.SPACE_3, Theme.SPACE_3, Theme.SPACE_3, Theme.SPACE_3);
    private JLabel titleLabel;
    private JLabel subtitleLabel;
    private JPanel headerPanel;
    private JPanel bodyPanel;

    public Card() {
        this((Function<Theme, Theme.Token>) null, false);
    }

    public Card(Function<Theme, Theme.Token> accentToken, boolean subtleTint) {
        super(new BorderLayout(0, Theme.SPACE_2));
        this.tokenSupplier = accentToken;
        this.subtleTint = subtleTint;
        setOpaque(false);
        bodyPanel = new JPanel(new BorderLayout());
        bodyPanel.setOpaque(false);
        super.add(bodyPanel, BorderLayout.CENTER);
        applyThemeStyles();
    }

    public Card(String title, String subtitle, Component rightAction) {
        this(title, subtitle, rightAction, null, false);
    }

    public Card(String title, String subtitle, Component rightAction, Function<Theme, Theme.Token> accentToken,
                boolean subtleTint) {
        this(accentToken, subtleTint);
        setHeader(title, subtitle, rightAction);
    }

    public Card setHeader(String title, String subtitle, Component rightAction) {
        if (headerPanel == null) {
            headerPanel = new JPanel(new BorderLayout(Theme.SPACE_2, 2));
            headerPanel.setOpaque(false);
            JPanel textStack = new JPanel(new BorderLayout(0, 2));
            textStack.setOpaque(false);
            titleLabel = new JLabel();
            subtitleLabel = new JLabel();
            textStack.add(titleLabel, BorderLayout.NORTH);
            textStack.add(subtitleLabel, BorderLayout.CENTER);
            headerPanel.add(textStack, BorderLayout.CENTER);
            super.add(headerPanel, BorderLayout.NORTH);
        }
        titleLabel.setText(title == null ? "" : title);
        titleLabel.setVisible(title != null && !title.isEmpty());
        subtitleLabel.setText(subtitle == null ? "" : subtitle);
        subtitleLabel.setVisible(subtitle != null && !subtitle.isEmpty());
        Component east = ((BorderLayout) headerPanel.getLayout()).getLayoutComponent(BorderLayout.EAST);
        if (east != null) {
            headerPanel.remove(east);
        }
        if (rightAction != null) {
            headerPanel.add(rightAction, BorderLayout.EAST);
        }
        applyThemeStyles();
        return this;
    }

    public Card setAccentToken(Function<Theme, Theme.Token> tokenSupplier, boolean subtleTint) {
        this.tokenSupplier = tokenSupplier;
        this.subtleTint = subtleTint;
        applyThemeStyles();
        repaint();
        return this;
    }

    public Card setPadding(int top, int left, int bottom, int right) {
        this.innerPadding = new Insets(top, left, bottom, right);
        applyThemeStyles();
        return this;
    }

    public JPanel body() {
        return bodyPanel;
    }

    public void setBody(Component content) {
        bodyPanel.removeAll();
        if (content != null) {
            bodyPanel.add(content, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (bodyPanel != null) {
            applyThemeStyles();
        }
    }

    private void applyThemeStyles() {
        Theme t = Theme.current();
        setBorder(BorderFactory.createEmptyBorder(innerPadding.top, innerPadding.left, innerPadding.bottom, innerPadding.right));
        if (titleLabel != null) {
            Theme.Token tok = tokenSupplier != null ? tokenSupplier.apply(t) : null;
            titleLabel.setFont(t.boldFont);
            titleLabel.setForeground(tok != null ? tok.text() : t.fg);
        }
        if (subtitleLabel != null) {
            subtitleLabel.setFont(t.smallFont);
            subtitleLabel.setForeground(t.muted);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Theme t = Theme.current();
        Theme.Token tok = tokenSupplier != null ? tokenSupplier.apply(t) : null;
        Color fill = subtleTint && tok != null ? tok.fill() : t.surfaceAlt;
        int arc = Math.round(Theme.RADIUS_CARD * 2 * t.scale);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
            g2.setColor(t.border);
            g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
            if (tok != null) {
                g2.setColor(tok.base());
                g2.fillRect(0, Math.min(2, h), Math.max(2, t.scaled(3)), Math.max(0, h - 4));
            }
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
