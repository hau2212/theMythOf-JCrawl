package themythofcrawl.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/** The API list: one row per captured API, filtered by branch / audit chips. Rows are added incrementally. */
public final class GroupTableModel extends AbstractTableModel implements AuditRows {
    public static final String[] COLS = {"API #", "Group", "Group Path", "Đánh giá (Info)",
        "Method", "Host", "Path", "Orig", "Orig Len", "1 Gốc + Roles", "Đã gửi", "Source", "Time", "Đã copy", "Copy",
        "Excel", "Trùng"};
    public static final int PATH_COL = 2;
    public static final int COPY_COL = 14;
    public static final int EXCEL_COL = 15;
    public static final int DUP_COL = 16;

    /** Empty = every status; non-empty = match any toggled status (including PENDING). */
    private final EnumSet<AuditLevel> auditFilters = EnumSet.noneOf(AuditLevel.class);
    private String prefix;
    private String textFilter = "";
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
        if (!textFilter.isEmpty() && !matchesText(g, textFilter)) {
            return false;
        }
        if (auditFilters.isEmpty()) {
            return true;
        }
        for (AuditLevel lvl : auditFilters) {
            if (matchesLevel(g, lvl)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesText(ApiGroup g, String q) {
        String gidHash = "#" + g.gid();
        if (String.valueOf(g.gid()).contains(q)
                || gidHash.contains(q)
                || Text.groupId(g.gid()).toLowerCase(java.util.Locale.ROOT).contains(q)) {
            return true;
        }
        if (Text.nz(g.method()).toLowerCase(java.util.Locale.ROOT).contains(q)
                || Text.nz(g.path()).toLowerCase(java.util.Locale.ROOT).contains(q)
                || Text.nz(g.branch()).toLowerCase(java.util.Locale.ROOT).contains(q)
                || Text.nz(g.url()).toLowerCase(java.util.Locale.ROOT).contains(q)) {
            return true;
        }
        return g.target() != null && Text.nz(g.target().sttText()).toLowerCase(java.util.Locale.ROOT).contains(q);
    }

    private static boolean matchesLevel(ApiGroup g, AuditLevel lvl) {
        if (lvl == AuditLevel.PENDING) {
            return g.pending();
        }
        if (lvl == AuditLevel.ERROR) {
            return g.results().stream()
                    .anyMatch(r -> ResultState.ERROR.equals(r.state()) || (r.status() != null && r.status() >= 500));
        }
        if (lvl == AuditLevel.WARN) {
            return g.results().stream().anyMatch(r -> Audit.level(g, r) == AuditLevel.WARN);
        }
        return Audit.level(g) == lvl;
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

    public void setTextFilter(String query) {
        String clean = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (clean.equals(textFilter)) {
            return;
        }
        textFilter = clean;
        rebuildView();
        fireTableDataChanged();
    }

    public String textFilter() {
        return textFilter;
    }

    /** null = all colours. */
    public void setAuditFilter(AuditLevel level, boolean any) {
        if (any || level == null) {
            setAuditFilters(Set.of());
        } else {
            setAuditFilters(Set.of(level));
        }
    }

    /** Empty collection shows every API; non-empty shows APIs matching any selected level. */
    public void setAuditFilters(Collection<AuditLevel> levels) {
        EnumSet<AuditLevel> next = EnumSet.noneOf(AuditLevel.class);
        if (levels != null) {
            next.addAll(levels);
        }
        if (next.equals(auditFilters)) {
            return;
        }
        auditFilters.clear();
        auditFilters.addAll(next);
        rebuildView();
        fireTableDataChanged();
    }

    public Set<AuditLevel> auditFilters() {
        return Collections.unmodifiableSet(EnumSet.copyOf(auditFilters));
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
