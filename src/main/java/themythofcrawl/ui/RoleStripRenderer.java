package themythofcrawl.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;

import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.RoleResult;
import themythofcrawl.ui.kit.ThemedTree;
import themythofcrawl.ui.theme.Theme;

/**
 * V_7.0 "Kết quả Role" cell of the flat API table: one small square per role, coloured by that role's
 * verdict (OK / bị chặn / bypass / lỗi / chưa gửi), so a whole access-control picture fits on one row,
 * like a mini heatmap. Tooltip lists every role with its status.
 */
public final class RoleStripRenderer extends JComponent implements TableCellRenderer, ThemedTree.ExtensionRenderer {
    private final GroupTableModel model;
    private List<RoleResult> results = List.of();
    private ApiGroup group;
    private Color bg;
    private Theme theme;

    public RoleStripRenderer(GroupTableModel model) {
        this.model = model;
        setOpaque(true);
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                   int row, int column) {
        Theme th = Theme.current();
        theme = th;
        int mr = table == null ? row : table.convertRowIndexToModel(row);
        group = model.at(mr);
        results = group == null ? List.of() : group.results();
        AuditLevel lvl = group == null ? AuditLevel.NONE : Audit.display(group);
        bg = selected ? th.selectionBg : th.rowBackground(lvl, Math.max(0, row));
        setToolTipText(tooltip());
        return this;
    }

    private String tooltip() {
        if (group == null || results.isEmpty()) {
            return "Không có role nào bật cho API này";
        }
        StringBuilder sb = new StringBuilder();
        for (RoleResult r : results) {
            if (sb.length() > 0) {
                sb.append("  |  ");
            }
            sb.append(Text.nz(r.roleName())).append(": ")
                    .append(r.status() != null ? String.valueOf(r.status()) : Text.nz(r.state()));
        }
        return sb.toString();
    }

    static Color colorOf(Theme th, ApiGroup g, RoleResult r) {
        AuditLevel l = Audit.level(g, r);
        return l == AuditLevel.NONE ? th.dotNone : th.audit(l).base();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Theme th = theme != null ? theme : Theme.current();
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            int w = getWidth();
            int h = getHeight();
            g2.setColor(bg != null ? bg : th.surface);
            g2.fillRect(0, 0, w, h);
            if (group == null || results.isEmpty()) {
                return;
            }
            int sq = Math.max(8, th.scaled(10));
            int gap = Math.max(2, th.scaled(3));
            int x = th.scaled(7);
            int y = (h - sq) / 2;
            int maxRight = w - th.scaled(4);
            for (int i = 0; i < results.size(); i++) {
                if (x + sq > maxRight) {
                    g2.setColor(th.muted);
                    g2.setFont(th.smallFont);
                    g2.drawString("+" + (results.size() - i), x, y + sq);
                    break;
                }
                g2.setColor(colorOf(th, group, results.get(i)));
                g2.fillRect(x, y, sq, sq);
                x += sq + gap;
            }
        } finally {
            g2.dispose();
        }
    }
}
