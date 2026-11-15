package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
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

    public static JButton accent(String text, Color bg, Runnable action) {
        JButton b = button(text, action);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFont(b.getFont().deriveFont(Font.BOLD));
        return b;
    }

    public static JButton compact(JButton b) {
        b.setMargin(new Insets(1, 6, 1, 6));
        return b;
    }

    public static JMenuItem item(String text, Runnable action) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> action.run());
        return i;
    }

    public static JMenuItem item(String text, boolean enabled, Runnable action) {
        JMenuItem i = item(text, action);
        i.setEnabled(enabled);
        return i;
    }

    public static JLabel bold(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    public static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Palette.ui().muted);
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

    /** Taller rows, hairline row separators, no vertical grid, bold header. */
    public static void style(JTable t) {
        t.setRowHeight(24);
        t.setShowGrid(false);
        t.setShowHorizontalLines(true);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setFillsViewportHeight(true);
        t.setGridColor(Palette.of(t.getBackground(), t.getForeground()).grid);
        t.getTableHeader().setFont(t.getTableHeader().getFont().deriveFont(Font.BOLD));
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

    /** A popover with a title and hard-wrapped text (a wrapping text area breaks popup layouts). */
    public static JPopupMenu popover(Component anchor, String title, String text) {
        JTextArea area = new JTextArea(Text.wrap(text, 64));
        area.setEditable(false);
        area.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        JPanel p = new JPanel(new BorderLayout());
        if (title != null) {
            JLabel h = bold(" " + title);
            h.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));
            p.add(h, BorderLayout.NORTH);
        }
        p.add(area, BorderLayout.CENTER);
        JPopupMenu pop = new JPopupMenu();
        pop.add(p);
        pop.show(anchor, 0, anchor.getHeight());
        return pop;
    }

    /** 'ⓘ': hover = short help, click = the full explanation. */
    public static JLabel info(String text, String title) {
        Palette p = Palette.ui();
        JLabel l = new JLabel(Icons.info(Palette.mix(p.fg, new Color(37, 99, 235), 0.55)));
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
        for (Component c : cs) {
            p.add(c);
        }
        return p;
    }
}
