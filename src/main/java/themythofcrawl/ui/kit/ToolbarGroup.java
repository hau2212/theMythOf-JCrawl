package themythofcrawl.ui.kit;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.swing.AbstractButton;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;

import themythofcrawl.ui.WrapLayout;
import themythofcrawl.ui.theme.Theme;

/**
 * Horizontal button group using FlatLaf string properties ({@code JButton.buttonType=toolBarButton})
 * and semantic primary accent styling via {@link Theme} tokens (zero FlatLaf compile dependency).
 */
public class ToolbarGroup extends JPanel {
    private final List<AccentBinding> accentButtons = new ArrayList<>();

    private record AccentBinding(AbstractButton button, Function<Theme, Theme.Token> supplier) {
    }

    public ToolbarGroup(Component... components) {
        super(new WrapLayout(FlowLayout.LEFT, Theme.SPACE_1, 2));
        setOpaque(false);
        for (Component c : components) {
            if (c != null) {
                addItem(c);
            }
        }
    }

    public ToolbarGroup addItem(Component c) {
        if (c instanceof AbstractButton b && b.getClientProperty("JButton.buttonType") == null) {
            b.putClientProperty("JButton.buttonType", "toolBarButton");
        }
        super.add(c);
        return this;
    }

    public ToolbarGroup addPrimary(JButton button, Function<Theme, Theme.Token> tokenSupplier) {
        Function<Theme, Theme.Token> fn = tokenSupplier != null ? tokenSupplier : t -> t.accent;
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.setFont(Theme.current().boldFont);
        accentButtons.add(new AccentBinding(button, fn));
        applyAccent(button, fn.apply(Theme.current()));
        super.add(button);
        return this;
    }

    public ToolbarGroup addDivider() {
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(2, 18));
        super.add(Box.createHorizontalStrut(2));
        super.add(sep);
        super.add(Box.createHorizontalStrut(2));
        return this;
    }

    private static void applyAccent(AbstractButton button, Theme.Token tok) {
        Color bg = tok.solidFill();
        Color fg = tok.onSolid();
        button.setBackground(bg);
        button.setForeground(fg);
        button.setOpaque(true);
        button.putClientProperty("FlatLaf.style", String.format("background: #%02X%02X%02X; foreground: #%02X%02X%02X; font: bold",
                bg.getRed(), bg.getGreen(), bg.getBlue(), fg.getRed(), fg.getGreen(), fg.getBlue()));
    }

    /** Styles a standalone button as a prominent semantic CTA using string properties only. */
    public static JButton stylePrimary(JButton button, Theme.Token tok) {
        Theme t = Theme.current();
        Theme.Token resolved = tok != null ? tok : t.accent;
        button.setFont(t.boldFont);
        applyAccent(button, resolved);
        return button;
    }

    /** Formats a button with FlatLaf's subtle toolbar style when available. */
    public static <B extends AbstractButton> B styleToolButton(B button) {
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        return button;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (accentButtons != null) {
            Theme t = Theme.current();
            for (AccentBinding b : accentButtons) {
                b.button().setFont(t.boldFont.deriveFont(Font.BOLD));
                applyAccent(b.button(), b.supplier().apply(t));
            }
        }
    }
}
