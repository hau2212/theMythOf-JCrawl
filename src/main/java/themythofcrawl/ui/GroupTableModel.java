package themythofcrawl.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.swing.table.AbstractTableModel;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ResultState;
import themythofcrawl.model.RoleResult;
import themythofcrawl.place.Decisions;

/** The API list: one row per captured API, filtered by branch / colour. Rows are added incrementally. */
public final class GroupTableModel extends AbstractTableModel implements AuditRows {
    public static final String[] COLS = {"API #", "Group", "Group Path (Click đúp để chọn/đổi)", "Đánh giá / Màu (Info)",
        "Method", "Host", "Path", "Orig", "Orig Len", "1 Gốc + Roles", "Đã gửi", "Source", "Time", "Đã copy", "Copy",
        "Excel", "Trùng"};
    public static final int PATH_COL = 2;
    public static final int COPY_COL = 14;
    public static final int EXCEL_COL = 15;
    public static final int DUP_COL = 16;

    /** null = every colour; PENDING = waiting for confirmation. */
    private AuditLevel audit;
    private boolean auditAll = true;
    private String prefix;
    private final Engine engine;
    private final Function<ApiGroup, Integer> hits;
    private List<ApiGroup> all = new ArrayList<>();
    private List<ApiGroup> rows = new ArrayList<>();
    private Map<ApiGroup, Integer> rowOf = new HashMap<>();

    public GroupTableModel(Engine engine, Function<ApiGroup, Integer> hits) {
        this.engine = engine;
        this.hits = hits;
    }

    private boolean matches(ApiGroup g) {
        if (prefix != null && !Hierarchy.matchesPrefix(g.branch(), prefix)) {
            return false;
        }
        if (auditAll) {
            return true;
        }
        if (audit == AuditLevel.PENDING) {
            return g.pending();
        }
        if (audit == AuditLevel.ERROR) {
            return g.results().stream().anyMatch(r -> ResultState.ERROR.equals(r.state()) || (r.status() != null && r.status() >= 500));
        }
        if (audit == AuditLevel.WARN) {
            return g.results().stream().anyMatch(r -> Audit.level(g, r) == AuditLevel.WARN);
        }
        return Audit.level(g) == audit;
    }

    private void rebuildView() {
        rows = new ArrayList<>();
        rowOf = new HashMap<>();
        for (ApiGroup g : all) {
            if (matches(g)) {
                rowOf.put(g, rows.size());
                rows.add(g);
            }
        }
    }

    /** Full reload (structure changed: deletes, moves, filters). */
    public void setAll(List<ApiGroup> groups) {
        all = new ArrayList<>(groups);
        rebuildView();
        fireTableDataChanged();
    }

    /** Append newly captured APIs with ONE table event. */
    public void addAll(Collection<ApiGroup> added) {
        int first = rows.size();
        for (ApiGroup g : added) {
            if (all.contains(g)) {
                continue;
            }
            all.add(g);
            if (matches(g)) {
                rowOf.put(g, rows.size());
                rows.add(g);
            }
        }
        if (rows.size() > first) {
            fireTableRowsInserted(first, rows.size() - 1);
        }
    }

    /** Repaint the rows of APIs that changed (keeps the selection). */
    public void updated(Collection<ApiGroup> changed) {
        int lo = Integer.MAX_VALUE;
        int hi = -1;
        for (ApiGroup g : changed) {
            Integer r = rowOf.get(g);
            if (r != null) {
                lo = Math.min(lo, r);
                hi = Math.max(hi, r);
            }
        }
        if (hi >= 0) {
            fireTableRowsUpdated(lo, hi);
        }
    }

    public void setBranchFilter(String p) {
        String clean = p == null || p.isBlank() ? null : p;
        if (java.util.Objects.equals(clean, prefix)) {
            return;
        }
        prefix = clean;
        rebuildView();
        fireTableDataChanged();
    }

    public String branchFilter() {
        return prefix;
    }

    /** null = all colours. */
    public void setAuditFilter(AuditLevel level, boolean any) {
        if (any == auditAll && level == audit) {
            return;
        }
        auditAll = any;
        audit = level;
        rebuildView();
        fireTableDataChanged();
    }

    public ApiGroup at(int r) {
        return r >= 0 && r < rows.size() ? rows.get(r) : null;
    }

    public int rowOf(ApiGroup g) {
        Integer r = rowOf.get(g);
        return r == null ? -1 : r;
    }

    public int total() {
        return all.size();
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
        return c == 0 || c == 7 || c == 8 ? Integer.class : String.class;
    }

    @Override
    public boolean isCellEditable(int r, int c) {
        return c == PATH_COL;
    }

    @Override
    public void setValueAt(Object v, int r, int c) {
        ApiGroup g = at(r);
        if (g != null && c == PATH_COL && v != null && !String.valueOf(v).isBlank()
                && !Hierarchy.same(String.valueOf(v), g.branch())) {
            engine.moveToPath(List.of(g), String.valueOf(v), "sửa ô Group Path");
        }
    }

    @Override
    public AuditLevel levelAt(int r) {
        ApiGroup g = at(r);
        return g == null ? AuditLevel.NONE : Audit.display(g);
    }

    @Override
    public Object accentAt(int r) {
        ApiGroup g = at(r);
        return g == null ? null : g.branchId();
    }

    @Override
    public Object getValueAt(int r, int c) {
        ApiGroup g = rows.get(r);
        return switch (c) {
            case 0 -> g.gid();
            case 1 -> Text.groupId(g.gid());
            case 2 -> g.branch();
            case 3 -> g.pending() ? Decisions.pendingLabel(g) + " · " + Audit.summary(g) : Audit.summary(g);
            case 4 -> g.method();
            case 5 -> g.service().hostLabel();
            case 6 -> g.path();
            case 7 -> g.origStatus();
            case 8 -> g.origLength();
            case 9 -> BranchTree.rolesLabel(g);
            case 10 -> {
                long done = g.results().stream().filter(x -> ResultState.finished(x.state())).count();
                yield done + "/" + g.results().size();
            }
            case 11 -> g.source();
            case 12 -> g.created();
            case 13 -> Text.nz(g.copiedLabel());
            case 14 -> "";
            case 15 -> {
                if (g.target() == null) {
                    yield "";
                }
                int n = hits.apply(g);
                yield n > 0 ? "#" + g.target().sttText() + " · gặp " + n : "#" + g.target().sttText();
            }
            case 16 -> {
                String b = BranchTree.dupBadge(g);
                yield b.isEmpty() ? "" : b.substring(1, b.length() - 1);
            }
            default -> "";
        };
    }

    public static String apiTitle(ApiGroup g) {
        return Text.apiLabel(g.gid()) + " " + g.endpoint();
    }

    /** Rows of the API: [API gốc] + roles. */
    public static List<RoleResult> entries(ApiGroup g) {
        return g == null ? List.of() : g.entries();
    }
}
