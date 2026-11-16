package themythofcrawl.ui;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

import themythofcrawl.app.Prefs;
import themythofcrawl.core.Json;

/**
 * Remembers column widths, display order, and hidden columns per table in {@link Prefs}
 * under {@code tmoc.ui.columns.<table>}, and adds a right-click column manager on headers.
 */
public final class ColumnMemory {
    private static final int SAVE_DEBOUNCE_MS = 220;

    private final JTable table;
    private final String prefKey;
    private final Prefs prefs;
    private final List<TableColumn> canonicalByModel = new ArrayList<>();
    private final List<Integer> defaultOrder = new ArrayList<>();
    private final Map<Integer, Integer> defaultWidths = new LinkedHashMap<>();
    private final Set<Integer> hiddenModelCols = new LinkedHashSet<>();
    private final Timer saveTimer;
    private boolean restoring;

    private ColumnMemory(JTable table, String tableId, Prefs prefs) {
        this.table = table;
        this.prefKey = UiPrefs.KEY_COLUMNS_PREFIX + tableId;
        this.prefs = prefs;
        this.saveTimer = new Timer(SAVE_DEBOUNCE_MS, e -> saveNow());
        this.saveTimer.setRepeats(false);

        captureDefaults();
        restore();
        installListeners();
    }

    /**
     * Installs column memory on {@code table} after initial widths and column order have been set.
     * Restores any previously saved state from {@code prefs} immediately.
     */
    public static ColumnMemory install(JTable table, String tableId, Prefs prefs) {
        if (table == null || tableId == null || tableId.isBlank()) {
            return null;
        }
        ColumnMemory cm = new ColumnMemory(table, tableId, prefs);
        table.putClientProperty(ColumnMemory.class, cm);
        return cm;
    }

    public static ColumnMemory of(JTable table) {
        if (table == null) {
            return null;
        }
        Object v = table.getClientProperty(ColumnMemory.class);
        return v instanceof ColumnMemory cm ? cm : null;
    }

    public String prefKey() {
        return prefKey;
    }

    private void captureDefaults() {
        TableColumnModel cm = table.getColumnModel();
        int count = cm.getColumnCount();
        List<TableColumn> viewCols = new ArrayList<>(count);
        for (int v = 0; v < count; v++) {
            TableColumn tc = cm.getColumn(v);
            viewCols.add(tc);
            defaultOrder.add(tc.getModelIndex());
            defaultWidths.put(tc.getModelIndex(), tc.getPreferredWidth());
        }
        int modelCols = table.getModel().getColumnCount();
        for (int mc = 0; mc < modelCols; mc++) {
            canonicalByModel.add(null);
        }
        for (TableColumn tc : viewCols) {
            int mi = tc.getModelIndex();
            if (mi >= 0 && mi < canonicalByModel.size()) {
                canonicalByModel.set(mi, tc);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void restore() {
        if (prefs == null) {
            return;
        }
        String raw = prefs.get(prefKey);
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            Map<String, Object> m = Json.parseObject(raw);
            List<Integer> order = new ArrayList<>();
            for (Object o : Json.list(m, "order")) {
                if (o instanceof Number n) {
                    int mi = n.intValue();
                    if (mi >= 0 && mi < canonicalByModel.size() && canonicalByModel.get(mi) != null && !order.contains(mi)) {
                        order.add(mi);
                    }
                }
            }
            Set<Integer> hidden = new LinkedHashSet<>();
            for (Object o : Json.list(m, "hidden")) {
                if (o instanceof Number n) {
                    int mi = n.intValue();
                    if (mi >= 0 && mi < canonicalByModel.size() && canonicalByModel.get(mi) != null) {
                        hidden.add(mi);
                    }
                }
            }
            Map<String, Object> widths = Json.obj(m, "widths");
            applyLayout(order, widths, hidden);
        } catch (RuntimeException ignored) {
            // Keep default columns if stored preference is corrupted
        }
    }

    private void applyLayout(List<Integer> savedOrder, Map<String, Object> widths, Set<Integer> hidden) {
        TableColumnModel cm = table.getColumnModel();
        restoring = true;
        try {
            // Reattach all known columns cleanly
            while (cm.getColumnCount() > 0) {
                cm.removeColumn(cm.getColumn(0));
            }
            List<Integer> finalOrder = new ArrayList<>();
            for (int mi : savedOrder) {
                if (!finalOrder.contains(mi) && mi >= 0 && mi < canonicalByModel.size() && canonicalByModel.get(mi) != null) {
                    finalOrder.add(mi);
                }
            }
            for (int mi : defaultOrder) {
                if (!finalOrder.contains(mi)) {
                    finalOrder.add(mi);
                }
            }
            hiddenModelCols.clear();
            // Never allow hiding every column
            int visibleCheck = 0;
            for (int mi : finalOrder) {
                if (!hidden.contains(mi)) {
                    visibleCheck++;
                }
            }
            Set<Integer> effectiveHidden = visibleCheck > 0 ? hidden : Set.of();

            for (int mi : finalOrder) {
                TableColumn tc = canonicalByModel.get(mi);
                if (tc == null) {
                    continue;
                }
                if (widths != null) {
                    Object wv = widths.get(String.valueOf(mi));
                    if (wv instanceof Number n && n.intValue() >= 20) {
                        int w = n.intValue();
                        tc.setPreferredWidth(w);
                        tc.setWidth(w);
                    }
                }
                if (effectiveHidden.contains(mi)) {
                    hiddenModelCols.add(mi);
                } else {
                    cm.addColumn(tc);
                }
            }
        } finally {
            restoring = false;
        }
        table.doLayout();
    }

    public boolean isColumnVisible(int modelIndex) {
        return !hiddenModelCols.contains(modelIndex);
    }

    public void setColumnVisible(int modelIndex, boolean visible) {
        if (modelIndex < 0 || modelIndex >= canonicalByModel.size() || canonicalByModel.get(modelIndex) == null) {
            return;
        }
        TableColumnModel cm = table.getColumnModel();
        if (!visible) {
            if (cm.getColumnCount() <= 1 || hiddenModelCols.contains(modelIndex)) {
                return;
            }
            TableColumn tc = canonicalByModel.get(modelIndex);
            restoring = true;
            try {
                hiddenModelCols.add(modelIndex);
                cm.removeColumn(tc);
            } finally {
                restoring = false;
            }
        } else {
            if (!hiddenModelCols.contains(modelIndex)) {
                return;
            }
            TableColumn tc = canonicalByModel.get(modelIndex);
            restoring = true;
            try {
                hiddenModelCols.remove(modelIndex);
                int targetPos = desiredInsertPos(modelIndex, cm);
                cm.addColumn(tc);
                int addedAt = cm.getColumnCount() - 1;
                if (targetPos >= 0 && targetPos < addedAt) {
                    cm.moveColumn(addedAt, targetPos);
                }
            } finally {
                restoring = false;
            }
        }
        saveNow();
    }

    private int desiredInsertPos(int modelIndex, TableColumnModel cm) {
        int defIdx = defaultOrder.indexOf(modelIndex);
        if (defIdx < 0) {
            return cm.getColumnCount();
        }
        int pos = 0;
        for (int v = 0; v < cm.getColumnCount(); v++) {
            int otherMi = cm.getColumn(v).getModelIndex();
            int otherDefIdx = defaultOrder.indexOf(otherMi);
            if (otherDefIdx >= 0 && otherDefIdx < defIdx) {
                pos = v + 1;
            }
        }
        return pos;
    }

    public void resetDefaults() {
        restoring = true;
        try {
            hiddenModelCols.clear();
            TableColumnModel cm = table.getColumnModel();
            while (cm.getColumnCount() > 0) {
                cm.removeColumn(cm.getColumn(0));
            }
            for (int mi : defaultOrder) {
                TableColumn tc = canonicalByModel.get(mi);
                if (tc != null) {
                    Integer dw = defaultWidths.get(mi);
                    if (dw != null) {
                        tc.setPreferredWidth(dw);
                        tc.setWidth(dw);
                    }
                    cm.addColumn(tc);
                }
            }
        } finally {
            restoring = false;
        }
        if (prefs != null) {
            prefs.set(prefKey, null);
        }
        table.doLayout();
    }

    public void saveNow() {
        saveTimer.stop();
        if (prefs == null) {
            return;
        }
        TableColumnModel cm = table.getColumnModel();
        List<Integer> order = new ArrayList<>();
        Map<String, Integer> widths = new LinkedHashMap<>();
        for (int v = 0; v < cm.getColumnCount(); v++) {
            TableColumn tc = cm.getColumn(v);
            int mi = tc.getModelIndex();
            order.add(mi);
            int w = (table.getWidth() > 0 && tc.getWidth() > 0) ? tc.getWidth() : tc.getPreferredWidth();
            widths.put(String.valueOf(mi), w > 0 ? w : tc.getPreferredWidth());
        }
        for (int mi : hiddenModelCols) {
            if (!order.contains(mi)) {
                order.add(mi);
            }
            TableColumn tc = canonicalByModel.get(mi);
            if (tc != null) {
                widths.put(String.valueOf(mi), tc.getPreferredWidth());
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("order", order);
        root.put("widths", widths);
        root.put("hidden", new ArrayList<>(hiddenModelCols));
        prefs.set(prefKey, Json.write(root));
    }

    private void scheduleSave() {
        if (!restoring && prefs != null) {
            saveTimer.restart();
        }
    }

    private void installListeners() {
        table.getColumnModel().addColumnModelListener(new TableColumnModelListener() {
            @Override
            public void columnAdded(TableColumnModelEvent e) {
            }

            @Override
            public void columnRemoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnMoved(TableColumnModelEvent e) {
                if (!restoring && e.getFromIndex() != e.getToIndex()) {
                    scheduleSave();
                }
            }

            @Override
            public void columnMarginChanged(ChangeEvent e) {
                if (!restoring) {
                    scheduleSave();
                }
            }

            @Override
            public void columnSelectionChanged(ListSelectionEvent e) {
            }
        });

        JTableHeader header = table.getTableHeader();
        if (header != null) {
            header.setToolTipText("Kéo cạnh để đổi rộng · Kéo tiêu đề để đổi thứ tự · Chuột phải để ẩn/hiện hoặc đặt lại cột");
            header.addMouseListener(new MouseAdapter() {
                private void maybeMenu(MouseEvent e) {
                    if (e.isPopupTrigger() || (SwingUtilities.isRightMouseButton(e) && e.getID() == MouseEvent.MOUSE_PRESSED)) {
                        showColumnMenu(e);
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    maybeMenu(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    maybeMenu(e);
                }
            });
        }
    }

    private void showColumnMenu(MouseEvent e) {
        JPopupMenu menu = buildColumnPopupMenu();
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    /** Builds the header column-visibility popup menu (also usable in tests). */
    public JPopupMenu buildColumnPopupMenu() {
        JPopupMenu menu = new JPopupMenu();
        int visibleCount = table.getColumnModel().getColumnCount();
        for (int mi : defaultOrder) {
            TableColumn tc = canonicalByModel.get(mi);
            if (tc == null) {
                continue;
            }
            String title = String.valueOf(tc.getHeaderValue());
            boolean vis = isColumnVisible(mi);
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(title, vis);
            if (vis && visibleCount <= 1) {
                item.setEnabled(false);
            }
            item.addActionListener(ev -> setColumnVisible(mi, item.isSelected()));
            menu.add(item);
        }
        menu.addSeparator();
        menu.add(Ui.item("Đặt lại cột mặc định", Icons::refresh, this::resetDefaults));
        return menu;
    }

    public void dispose() {
        saveTimer.stop();
    }

    /** Stops debounce save timers for all tables under {@code root}. */
    public static void disposeIn(java.awt.Component root) {
        if (root instanceof JTable t) {
            ColumnMemory cm = of(t);
            if (cm != null) {
                cm.dispose();
            }
        }
        if (root instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                disposeIn(child);
            }
        }
    }
}
