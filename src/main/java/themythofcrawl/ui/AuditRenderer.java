package themythofcrawl.ui;

import java.awt.Component;
import java.awt.Font;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntFunction;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.ui.kit.ThemedTree;

/**
 * Cell renderer of every data table: zebra rows tinted by audit level, a colour bar per branch in the
 * first column, coloured method / status / verdict; 'copy' cells draw the copy glyph, 'link' / 'dup'
 * cells look clickable.
 */
public class AuditRenderer extends DefaultTableCellRenderer implements ThemedTree.ExtensionRenderer {
    public enum Mode { TEXT, COPY, LINK, DUP }

    private final AuditRows rows;
    private final Integer statusCol;
    private final Integer auditCol;
    private final Integer methodCol;
    private final Set<Integer> bold = new HashSet<>();
    private final Mode mode;
    private IntFunction<String> tooltip;

    public AuditRenderer(AuditRows rows, Integer statusCol, Integer auditCol, Integer methodCol, int... boldCols) {
        this(rows, statusCol, auditCol, methodCol, Mode.TEXT, boldCols);
    }

    public AuditRenderer(AuditRows rows, Integer statusCol, Integer auditCol, Integer methodCol, Mode mode, int... boldCols) {
        this.rows = rows;
        this.statusCol = statusCol;
        this.auditCol = auditCol;
        this.methodCol = methodCol;
        this.mode = mode;
        for (int b : boldCols) {
            bold.add(b);
        }
        if (auditCol != null) {
            bold.add(auditCol);
        }
        if (methodCol != null) {
            bold.add(methodCol);
        }
    }

    public AuditRenderer tooltip(IntFunction<String> f) {
        this.tooltip = f;
        return this;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row,
                                                   int column) {
        JLabel c = (JLabel) super.getTableCellRendererComponent(table, value, selected, false, row, column);
        int mr = table.convertRowIndexToModel(row);
        int mc = table.convertColumnIndexToModel(column);
        Palette pal = Palette.of(table.getBackground(), table.getForeground());
        Object accent = rows.accentAt(mr);
        boolean isBold = bold.contains(mc) || ("ORIG".equals(accent) && mc <= 1) || mode != Mode.TEXT;
        c.setFont(isBold ? table.getFont().deriveFont(Font.BOLD) : table.getFont());
        c.setBorder(column == 0 ? pal.stripe(accent) : pal.pad);
        c.setIcon(null);
        c.setHorizontalAlignment(JLabel.LEFT);
        if (!selected) {
            AuditLevel lvl = rows.levelAt(mr);
            c.setBackground(pal.background(lvl, row));
            java.awt.Color fg = pal.fg;
            if (auditCol != null && mc == auditCol) {
                fg = pal.auditFg(lvl);
            } else if (methodCol != null && mc == methodCol) {
                fg = pal.methodFg(String.valueOf(value));
            } else if (statusCol != null && mc == statusCol && value != null) {
                try {
                    fg = pal.statusFg(Integer.parseInt(String.valueOf(value)));
                } catch (NumberFormatException ignored) {
                    // not a status code
                }
            } else if ("ORIG".equals(accent) && mc <= 1) {
                fg = pal.origFg;
            }
            if (mode == Mode.LINK && value != null && !String.valueOf(value).isEmpty()) {
                fg = pal.origFg;
            } else if (mode == Mode.DUP && value != null && !String.valueOf(value).isEmpty()) {
                fg = Palette.DUP_PURPLE;
            }
            c.setForeground(fg);
        }
        if (mode == Mode.COPY) {
            c.setIcon(Icons.copy(selected ? c.getForeground() : pal.muted));
            c.setText("");
            c.setHorizontalAlignment(JLabel.CENTER);
        }
        c.setToolTipText(tooltip == null ? null : tooltip.apply(mr));
        return c;
    }
}
