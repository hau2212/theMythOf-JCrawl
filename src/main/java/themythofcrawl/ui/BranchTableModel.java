package themythofcrawl.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.table.AbstractTableModel;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Branch;

/** Row 0 = [all branches], then one row per branch (name editable). */
public final class BranchTableModel extends AbstractTableModel implements AuditRows {
    static final String[] COLS = {"#", "Tên chức năng", "Số API", "Trạng thái vai trò (Màu)", "Danh sách API"};

    private record Row(List<Object> cells, AuditLevel level, Branch branch) {
    }

    private final Engine engine;
    private List<Row> rows = new ArrayList<>();

    public BranchTableModel(Engine engine) {
        this.engine = engine;
    }

    /** Returns true when the row count is unchanged (selection kept). */
    public boolean reload() {
        List<ApiGroup> groups = engine.groups();
        List<Branch> branches = engine.branches();
        int active = engine.activeBranch().id();
        Map<Integer, List<ApiGroup>> by = new HashMap<>();
        for (ApiGroup g : groups) {
            by.computeIfAbsent(g.branchId(), k -> new ArrayList<>()).add(g);
        }
        List<Row> next = new ArrayList<>();
        Audit.Verdict all = Audit.branch(groups);
        next.add(new Row(List.of("ALL", "[Tất cả: " + branches.size() + " chức năng]", groups.size(), all.summary(),
                groups.isEmpty() ? "-" : Text.apiLabel(groups.get(0).gid()) + " .. " + Text.apiLabel(groups.get(groups.size() - 1).gid())),
                all.level(), null));
        for (Branch b : branches) {
            List<ApiGroup> bg = by.getOrDefault(b.id(), List.of());
            Audit.Verdict v = Audit.branch(bg);
            String apis;
            if (bg.isEmpty()) {
                apis = "(chưa có API)";
            } else if (bg.size() <= 3) {
                apis = String.join(", ", bg.stream().map(g -> Text.apiLabel(g.gid())).toList());
            } else {
                apis = Text.apiLabel(bg.get(0).gid()) + " .. " + Text.apiLabel(bg.get(bg.size() - 1).gid()) + " (" + bg.size() + ")";
            }
            next.add(new Row(List.of(b.id() == active ? "* #" + b.id() : "#" + b.id(), b.label(), bg.size(), v.summary(), apis),
                    v.level(), b));
        }
        boolean same = next.size() == rows.size();
        rows = next;
        if (same && !rows.isEmpty()) {
            fireTableRowsUpdated(0, rows.size() - 1);
        } else {
            fireTableDataChanged();
        }
        return same;
    }

    public Branch at(int r) {
        return r > 0 && r < rows.size() ? rows.get(r).branch() : null;
    }

    @Override
    public AuditLevel levelAt(int r) {
        return r >= 0 && r < rows.size() ? rows.get(r).level() : AuditLevel.NONE;
    }

    @Override
    public Object accentAt(int r) {
        Branch b = at(r);
        return b == null ? null : b.id();
    }

    @Override
    public int getRowCount() {
        return rows.size();
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
        return c == 2 ? Integer.class : String.class;
    }

    @Override
    public boolean isCellEditable(int r, int c) {
        return r > 0 && c == 1;
    }

    @Override
    public void setValueAt(Object v, int r, int c) {
        Branch b = at(r);
        if (b == null || c != 1) {
            return;
        }
        String raw = Text.nz(v).trim().replaceFirst("(?i)^T[êe]n\\s*\\d+\\s*[:\\-]\\s*", "").trim();
        engine.renameBranch(b.id(), raw.isEmpty() ? "Chức năng " + b.id() : raw);
    }

    @Override
    public Object getValueAt(int r, int c) {
        return rows.get(r).cells().get(c);
    }
}
