package themythofcrawl.ui.kit;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagLayout;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

import themythofcrawl.ui.Icons;
import themythofcrawl.ui.theme.Theme;

/**
 * Centered empty-state placeholder with faded Java2D icon, bold heading, muted guidance line and
 * optional action button. Adapts dynamically to theme changes.
 */
public class EmptyState extends JPanel {
    private final JLabel iconLabel = new JLabel();
    private final JLabel titleLabel = new JLabel();
    private final JLabel hintLabel = new JLabel();
    private Function<Theme, Icon> iconFn;

    public EmptyState(Function<Theme, Icon> iconFn, String title, String guidance) {
        this(iconFn, title, guidance, null);
    }

    public EmptyState(Function<Theme, Icon> iconFn, String title, String guidance, JButton cta) {
        super(new GridBagLayout());
        this.iconFn = iconFn != null ? iconFn : t -> Icons.folder(t.muted, 32);
        setOpaque(false);

        JPanel stack = new JPanel();
        stack.setOpaque(false);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_4, Theme.SPACE_4, Theme.SPACE_4, Theme.SPACE_4));

        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        titleLabel.setText(title == null ? "" : title);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        hintLabel.setText(guidance == null ? "" : guidance);
        hintLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        hintLabel.setVisible(guidance != null && !guidance.isEmpty());

        stack.add(iconLabel);
        stack.add(Box.createVerticalStrut(Theme.SPACE_2));
        stack.add(titleLabel);
        if (guidance != null && !guidance.isEmpty()) {
            stack.add(Box.createVerticalStrut(Theme.SPACE_1));
            stack.add(hintLabel);
        }
        if (cta != null) {
            cta.setAlignmentX(Component.CENTER_ALIGNMENT);
            stack.add(Box.createVerticalStrut(Theme.SPACE_3));
            stack.add(cta);
        }
        add(stack);
        applyThemeStyles();
    }

    public EmptyState setMessage(String title, String guidance) {
        titleLabel.setText(title == null ? "" : title);
        hintLabel.setText(guidance == null ? "" : guidance);
        hintLabel.setVisible(guidance != null && !guidance.isEmpty());
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
        if (iconFn != null) {
            iconLabel.setIcon(iconFn.apply(t));
        }
        titleLabel.setFont(t.boldFont);
        titleLabel.setForeground(t.fg);
        hintLabel.setFont(t.smallFont);
        hintLabel.setForeground(t.muted);
    }
}
