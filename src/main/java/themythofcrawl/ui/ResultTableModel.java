package themythofcrawl.ui;

import java.util.List;

import javax.swing.table.AbstractTableModel;

import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.RoleResult;

/** Row 0 = the API gốc, rows 1..N = the roles of the selected API. */
public final class ResultTableModel extends AbstractTableModel implements AuditRows {
    static final String[] COLS = {"#", "Loại / Role", "Đánh giá Role", "State", "Status", "Body Len", "Delta",
        "Time (ms)", "Sends", "Ghi chú (ID dùng / lỗi / tab)"};
    private ApiGroup group;
    private List<RoleResult> entries = List.of();

    public void setGroup(ApiGroup g) {
        group = g;
        entries = GroupTableModel.entries(g);
        fireTableDataChanged();
    }

    /** Repaint without dropping the selection (full refresh when roles were added / removed). */
    public void refresh() {
        List<RoleResult> now = GroupTableModel.entries(group);
        if (now.size() != entries.size() || !now.equals(entries)) {
            entries = now;
            fireTableDataChanged();
        } else if (!entries.isEmpty()) {
            fireTableRowsUpdated(0, entries.size() - 1);
        }
    }

    public ApiGroup group() {
        return group;
    }

    public RoleResult at(int r) {
        return r >= 0 && r < entries.size() ? entries.get(r) : null;
    }

    public int rowOf(RoleResult res) {
        return entries.indexOf(res);
    }

    @Override
    public int getRowCount() {
        return entries.size();
    }

    @Override
    public int getColumnCount() {
        return COLS.length;
    }

    @Override
    public String getColumnName(int c) {
        return COLS[c];
    }

    @Override
    public Class<?> getColumnClass(int c) {
        return c >= 4 && c <= 8 ? Integer.class : String.class;
    }

    @Override
    public AuditLevel levelAt(int r) {
        RoleResult res = at(r);
        return res == null ? AuditLevel.NONE : Audit.level(group, res);
    }

    @Override
    public Object accentAt(int r) {
        RoleResult res = at(r);
        return res != null && res.original() ? "ORIG" : null;
    }

    @Override
    public Object getValueAt(int r, int c) {
        RoleResult res = entries.get(r);
        return switch (c) {
            case 0 -> res.original() ? "GỐC" : "Role " + r;
            case 1 -> res.roleName();
            case 2 -> Audit.badge(group, res);
            case 3 -> res.state();
            case 4 -> res.status();
            case 5 -> res.length();
            case 6 -> res.original() ? (res.length() != null ? (Object) 0 : null)
                    : res.length() == null || group.origLength() == null ? null : res.length() - group.origLength();
            case 7 -> res.elapsedMs() == null ? null : res.elapsedMs().intValue();
            case 8 -> res.sentCount();
            case 9 -> !Text.blank(res.error()) ? res.error() : !Text.blank(res.sentNote()) ? res.sentNote() : res.tabName();
            default -> "";
        };
    }
}
