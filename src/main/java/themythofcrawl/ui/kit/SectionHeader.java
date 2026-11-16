package themythofcrawl.ui.kit;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

import themythofcrawl.ui.theme.Theme;

/**
 * Section heading with bold +2 pt title, -1 pt muted description and a 1 px hairline divider.
 */
public class SectionHeader extends JPanel {
    private final JLabel titleLabel = new JLabel();
    private final JLabel subtitleLabel = new JLabel();
    private final JPanel textWrap = new JPanel(new BorderLayout(0, 2));
    private final Function<Theme, Theme.Token> accentToken;

    public SectionHeader(String title, String subtitle) {
        this(title, subtitle, null, null);
    }

    public SectionHeader(String title, String subtitle, Component rightSlot, Function<Theme, Theme.Token> accentToken) {
        super(new BorderLayout(Theme.SPACE_2, 2));
        this.accentToken = accentToken;
        setOpaque(false);
        textWrap.setOpaque(false);
        titleLabel.setText(title == null ? "" : title);
        subtitleLabel.setText(subtitle == null ? "" : subtitle);
        subtitleLabel.setVisible(subtitle != null && !subtitle.isEmpty());
        textWrap.add(titleLabel, BorderLayout.NORTH);
        textWrap.add(subtitleLabel, BorderLayout.CENTER);
        add(textWrap, BorderLayout.CENTER);
        if (rightSlot != null) {
            add(rightSlot, BorderLayout.EAST);
        }
        applyThemeStyles();
    }

    public SectionHeader setTitle(String title) {
        titleLabel.setText(title == null ? "" : title);
        return this;
    }

    public SectionHeader setSubtitle(String subtitle) {
        subtitleLabel.setText(subtitle == null ? "" : subtitle);
        subtitleLabel.setVisible(subtitle != null && !subtitle.isEmpty());
        return this;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (titleLabel != null) {
            applyThemeStyles();
        }
    }

    private void applyThemeStyles() {
        Theme t = Theme.current();
        Theme.Token tok = accentToken != null ? accentToken.apply(t) : null;
        titleLabel.setFont(t.headingFont);
        titleLabel.setForeground(tok != null ? tok.text() : t.fg);
        subtitleLabel.setFont(t.smallFont);
        subtitleLabel.setForeground(t.muted);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, t.border),
                BorderFactory.createEmptyBorder(0, 0, Theme.SPACE_1, 0)));
    }
}
