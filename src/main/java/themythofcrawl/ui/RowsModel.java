package themythofcrawl.ui;

import java.util.ArrayList;
import java.util.List;

import javax.swing.table.AbstractTableModel;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.place.Decisions;

/** Read-only table over prepared rows (payload, colour, cells). */
public final class RowsModel<T> extends AbstractTableModel implements AuditRows {
    private final List<String> cols;
    private List<Decisions.Row<T>> rows = new ArrayList<>();

    public RowsModel(List<String> cols) {
        this.cols = cols;
    }

    public void set(List<Decisions.Row<T>> r) {
        rows = r;
        fireTableDataChanged();
    }

    public T payload(int r) {
        return r >= 0 && r < rows.size() ? rows.get(r).payload() : null;
    }

    public int indexOf(T p) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).payload() == p || rows.get(i).payload().equals(p)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public AuditLevel levelAt(int r) {
        return r >= 0 && r < rows.size() ? rows.get(r).level() : AuditLevel.NONE;
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return cols.size();
    }

    @Override
    public String getColumnName(int c) {
        return cols.get(c);
    }

    @Override
    public Object getValueAt(int r, int c) {
        List<Object> cells = rows.get(r).cells();
        return c < cells.size() ? cells.get(c) : null;
    }
}
