package themythofcrawl.ui;

import java.awt.Color;
import java.awt.Component;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntFunction;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.ui.kit.ThemedTree;
import themythofcrawl.ui.theme.Theme;

/**
 * Cell renderer of every data table: zebra rows tinted by audit level, a colour bar per branch in the
 * first column, coloured method / status / verdict; 'copy' cells draw the copy glyph, 'link' / 'dup'
 * cells look clickable; emoji markers are replaced by drawn Java2D icons.
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
        Theme th = Theme.of(table.getBackground(), table.getForeground());
        Object accent = rows.accentAt(mr);
        boolean isBold = bold.contains(mc) || ("ORIG".equals(accent) && mc <= 1) || mode != Mode.TEXT;
        c.setFont(isBold ? th.boldFont : th.baseFont);
        c.setBorder(column == 0 ? th.stripe(accent) : th.cellPad);
        c.setIcon(null);
        c.setIconTextGap(5);
        c.setHorizontalAlignment(JLabel.LEFT);

        String raw = value == null ? "" : String.valueOf(value);
        AuditLevel lvl = rows.levelAt(mr);
        if (!selected) {
            c.setBackground(th.rowBackground(lvl, row));
            Color fg = th.fg;
            if (auditCol != null && mc == auditCol) {
                fg = lvl == AuditLevel.NONE ? th.fg : th.audit(lvl).text();
            } else if (methodCol != null && mc == methodCol) {
                Theme.Token mt = th.method(raw);
                fg = mt == th.pending ? th.fg : mt.text();
            } else if (statusCol != null && mc == statusCol && value != null) {
                try {
                    fg = th.statusColor(Integer.parseInt(raw));
                } catch (NumberFormatException ignored) {
                    // not a status code
                }
            } else if ("ORIG".equals(accent) && mc <= 1) {
                fg = th.orig.text();
            }
            if (mode == Mode.LINK && !raw.isEmpty()) {
                fg = th.orig.text();
            } else if (mode == Mode.DUP && !raw.isEmpty()) {
                fg = th.dup.text();
            }
            c.setForeground(fg);
        }

        if (mode == Mode.COPY) {
            c.setIcon(Icons.copy(selected ? c.getForeground() : th.muted));
            c.setText("");
            c.setHorizontalAlignment(JLabel.CENTER);
        } else if (mode == Mode.DUP && !raw.isEmpty()) {
            c.setIcon(Icons.dup(selected ? c.getForeground() : th.dup.text(), 12));
            c.setText(Icons.stripLeadingSymbol(raw));
        } else if (!raw.isEmpty()) {
            javax.swing.Icon symIcon = Icons.forLeadingSymbol(raw, th, selected ? c.getForeground() : null);
            if (symIcon != null) {
                c.setIcon(symIcon);
                c.setText(Icons.stripLeadingSymbol(raw));
            } else if (auditCol != null && mc == auditCol && lvl != AuditLevel.NONE) {
                c.setIcon(Icons.forAudit(lvl, selected ? c.getForeground() : th.audit(lvl).text()));
            }
        }
        c.setToolTipText(tooltip == null ? null : tooltip.apply(mr));
        return c;
    }
}
