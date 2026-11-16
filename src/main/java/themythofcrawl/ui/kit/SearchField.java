package themythofcrawl.ui.kit;

import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.function.Consumer;

import javax.swing.Icon;
import javax.swing.JTextField;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import themythofcrawl.ui.Icons;
import themythofcrawl.ui.theme.Theme;

/**
 * Filter / search input styled with FlatLaf string properties ({@code JTextField.placeholderText},
 * {@code JTextField.showClearButton}, {@code JTextField.leadingIcon}) and clean Java2D fallback
 * rendering on non-FlatLaf Look &amp; Feels.
 */
public class SearchField extends JTextField {
    private String placeholder = "";
    private boolean flatLaf;
    private boolean customMarginApplied;

    public SearchField(String placeholder) {
        this(placeholder, 18);
    }

    public SearchField(String placeholder, int columns) {
        super(columns);
        setPlaceholder(placeholder);
        putClientProperty("JTextField.showClearButton", Boolean.TRUE);
        putClientProperty("JTextField.leadingIcon", Icons.themed(t -> Icons.search(t.muted)));
        syncLafKind();
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
        putClientProperty("JTextField.placeholderText", this.placeholder);
        repaint();
    }

    public SearchField onChange(Consumer<String> listener) {
        getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                listener.accept(getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                listener.accept(getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                listener.accept(getText());
            }
        });
        return this;
    }

    /** Marks or clears an error outline via FlatLaf string property + tooltip fallback. */
    public void setErrorOutline(boolean error) {
        putClientProperty("JComponent.outline", error ? "error" : null);
    }

    /** Focuses the search field and selects any existing query text. */
    public void focusField() {
        requestFocusInWindow();
        selectAll();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        syncLafKind();
    }

    private void syncLafKind() {
        LookAndFeel laf = UIManager.getLookAndFeel();
        flatLaf = laf != null && laf.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("flatlaf");
        if (!flatLaf) {
            Insets m = super.getMargin();
            int baseLeft = m != null ? Math.max(4, m.left) : 4;
            super.setMargin(new Insets(m != null ? m.top : 2, Math.max(22, baseLeft),
                    m != null ? m.bottom : 2, m != null ? m.right : 4));
            customMarginApplied = true;
        } else if (customMarginApplied || super.getMargin() == null) {
            Insets uiMargin = UIManager.getInsets("TextField.margin");
            super.setMargin(uiMargin != null ? uiMargin : new Insets(2, 6, 2, 6));
            customMarginApplied = false;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (flatLaf) {
            return;
        }
        Theme t = Theme.current();
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Icon ic = Icons.search(t.muted);
            int iy = (getHeight() - ic.getIconHeight()) / 2;
            ic.paintIcon(this, g2, 6, iy);
            if (getText().isEmpty() && !placeholder.isEmpty() && !isFocusOwner()) {
                g2.setFont(getFont());
                g2.setColor(t.muted);
                FontMetrics fm = g2.getFontMetrics();
                int ty = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(placeholder, 24, ty);
            }
        } finally {
            g2.dispose();
        }
    }
}
