package themythofcrawl.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import themythofcrawl.core.Text;
import themythofcrawl.ui.kit.Card;
import themythofcrawl.ui.kit.ToolbarGroup;
import themythofcrawl.ui.theme.Theme;

/** Small Swing helpers shared by every tab. */
public final class Ui {
    private Ui() {
    }

    public static JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    public static JButton button(String text, String tip, Runnable action) {
        JButton b = button(text, action);
        b.setToolTipText(tip);
        return b;
    }

    public static JButton button(String text, Function<Theme, Icon> iconFn, Runnable action) {
        JButton b = button(text, action);
        if (iconFn != null) {
            b.setIcon(Icons.themed(iconFn));
        }
        return b;
    }

    public static JButton button(String text, Function<Theme, Icon> iconFn, String tip, Runnable action) {
        JButton b = button(text, iconFn, action);
        b.setToolTipText(tip);
        return b;
    }

    public static JButton dropdown(String text, Function<Theme, Icon> iconFn, String tip, Runnable action) {
        JButton b = new JButton(text);
        if (iconFn != null) {
            b.setIcon(Icons.themed(iconFn));
        } else {
            b.setIcon(Icons.themed(t -> Icons.chevronDown(t.fg, 10)));
            b.setHorizontalTextPosition(javax.swing.SwingConstants.LEADING);
        }
        if (tip != null) {
            b.setToolTipText(tip);
        }
        b.addActionListener(e -> action.run());
        return b;
    }

    public static JButton accent(String text, Function<Theme, Theme.Token> tokenSupplier, Runnable action) {
        return accent(text, null, tokenSupplier, action);
    }

    public static JButton accent(String text, Function<Theme, Icon> iconFn, Function<Theme, Theme.Token> tokenSupplier,
                                 Runnable action) {
        Function<Theme, Theme.Token> fn = tokenSupplier != null ? tokenSupplier : t -> t.accent;
        JButton b = new JButton(text) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                Theme.Token tok = fn.apply(t);
                Color bg = tok.solidFill();
                Color fg = tok.onSolid();
                setBackground(bg);
                setForeground(fg);
                setFont(t.boldFont);
                putClientProperty("FlatLaf.style", String.format("background: #%02X%02X%02X; foreground: #%02X%02X%02X; font: bold",
                        bg.getRed(), bg.getGreen(), bg.getBlue(), fg.getRed(), fg.getGreen(), fg.getBlue()));
            }
        };
        if (iconFn != null) {
            b.setIcon(Icons.themed(iconFn));
        }
        b.addActionListener(e -> action.run());
        return b;
    }

    public static JButton compact(JButton b) {
        b.setMargin(new Insets(1, 6, 1, 6));
        return ToolbarGroup.styleToolButton(b);
    }

    public static JMenuItem item(String text, Runnable action) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> action.run());
        return i;
    }

    public static JMenuItem item(String text, Function<Theme, Icon> iconFn, Runnable action) {
        JMenuItem i = item(text, action);
        if (iconFn != null) {
            i.setIcon(Icons.themed(iconFn));
        }
        return i;
    }

    public static JMenuItem item(String text, boolean enabled, Runnable action) {
        JMenuItem i = item(text, action);
        i.setEnabled(enabled);
        return i;
    }

    public static JMenuItem item(String text, Function<Theme, Icon> iconFn, boolean enabled, Runnable action) {
        JMenuItem i = item(text, iconFn, action);
        i.setEnabled(enabled);
        return i;
    }

    public static JLabel bold(String text) {
        JLabel l = new JLabel(text) {
            @Override
            public void updateUI() {
                super.updateUI();
                setFont(Theme.current().boldFont);
            }
        };
        l.setFont(Theme.current().boldFont);
        return l;
    }

    public static JLabel muted(String text) {
        JLabel l = new JLabel(text) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setForeground(t.muted);
                setFont(t.smallFont);
            }
        };
        Theme t = Theme.current();
        l.setForeground(t.muted);
        l.setFont(t.smallFont);
        return l;
    }

    public static void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(Text.nz(text)), null);
        } catch (RuntimeException ignored) {
            // headless or clipboard busy
        }
    }

    public static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** Taller rows, hairline row separators, no vertical grid, bold left header with sort glyph, row hover. */
    public static void style(JTable t) {
        Theme th = Theme.current();
        t.setRowHeight(Theme.ROW_TABLE);
        t.setShowGrid(false);
        t.setShowHorizontalLines(true);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setFillsViewportHeight(true);
        t.setGridColor(Theme.of(t.getBackground(), t.getForeground()).grid);
        AuditRenderer.installHover(t);
        AuditRenderer.HeaderRenderer hdr = new AuditRenderer.HeaderRenderer();
        if (t.getTableHeader() != null) {
            t.getTableHeader().setFont(th.boldFont);
            t.getTableHeader().setDefaultRenderer(hdr);
        }
        if (t instanceof themythofcrawl.ui.kit.ThemedTable tt) {
            tt.setExtensionHeaderRenderer(hdr);
            tt.markStyled();
        }
    }

    public static void widths(JTable t, int... w) {
        for (int i = 0; i < w.length && i < t.getColumnCount(); i++) {
            t.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        }
    }

    public static void key(JComponent c, String stroke, Runnable action) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke), stroke);
        c.getActionMap().put(stroke, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    public static void onDoubleClick(Component c, Runnable r) {
        c.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    r.run();
                }
            }
        });
    }

    /** Right click: select the row under the mouse when it is not part of the selection, then show. */
    public static void onPopup(JTable t, Consumer<MouseEvent> show) {
        t.addMouseListener(new MouseAdapter() {
            private void trigger(MouseEvent e) {
                if (e.isPopupTrigger() || (SwingUtilities.isRightMouseButton(e) && e.getID() == MouseEvent.MOUSE_PRESSED)) {
                    int r = t.rowAtPoint(e.getPoint());
                    if (r >= 0 && !t.isRowSelected(r)) {
                        t.setRowSelectionInterval(r, r);
                    }
                    show.accept(e);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                trigger(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    trigger(e);
                }
            }
        });
    }

    /** A popover with a title and hard-wrapped text rendered inside a themed {@link Card}. */
    public static JPopupMenu popover(Component anchor, String title, String text) {
        Theme t = Theme.current();
        Card card = new Card(title, null, new JLabel(Icons.themed(th -> Icons.info(th.info.text()))),
                th -> th.info, false);
        card.setPadding(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3);
        JTextArea area = new JTextArea(Text.wrap(text, 64));
        area.setEditable(false);
        area.setOpaque(false);
        area.setForeground(t.fg);
        area.setFont(t.baseFont);
        area.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, 0, 0, 0));
        card.setBody(area);
        JPopupMenu pop = new JPopupMenu();
        pop.setBackground(t.surface);
        pop.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        pop.add(card);
        if (anchor != null && anchor.isShowing()) {
            pop.show(anchor, 0, anchor.getHeight());
        }
        return pop;
    }

    /** Drawn info glyph: hover = short help, click = the full explanation. */
    public static JLabel info(String text, String title) {
        JLabel l = new JLabel(Icons.themed(t -> Icons.info(t.info.text())));
        l.setToolTipText(text.split("\n")[0] + "  (bấm để xem đầy đủ)");
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.setBorder(BorderFactory.createEmptyBorder(0, 1, 0, 3));
        l.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                popover(l, title, text);
            }
        });
        return l;
    }

    public static JPanel flow(int align, Component... cs) {
        JPanel p = new JPanel(new WrapLayout(align, 4, 2));
        p.setOpaque(false);
        for (Component c : cs) {
            p.add(c);
        }
        return p;
    }
}

