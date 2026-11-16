package themythofcrawl.ui.kit;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.beans.PropertyChangeListener;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableModel;

/**
 * {@link JTable} that retains extension cell renderers (both {@link #setDefaultRenderer} and
 * per-column {@link TableColumn#setCellRenderer}), row height, resize mode and grid styling across
 * {@link #updateUI()} and Burp theme passes.
 */
public class ThemedTable extends JTable {
    private boolean ready;
    private final Map<Class<?>, TableCellRenderer> extDefaultRenderers = new LinkedHashMap<>();
    private final Map<Integer, TableCellRenderer> extColumnRenderers = new HashMap<>();
    private final PropertyChangeListener columnRendererGuard;
    private final TableColumnModelListener columnModelWatcher;
    private boolean guardingRenderer;
    private boolean updatingUi;

    private int desiredRowHeight = -1;
    private boolean hasDesiredAutoResize;
    private int desiredAutoResizeMode = AUTO_RESIZE_SUBSEQUENT_COLUMNS;
    private boolean hasDesiredShowH;
    private boolean desiredShowH;
    private boolean hasDesiredShowV;
    private boolean desiredShowV;
    private Dimension desiredSpacing;
    private boolean hasDesiredFillsViewport;
    private boolean desiredFillsViewport;
    private boolean styled;

    public ThemedTable() {
        this(null, null, null);
    }

    public ThemedTable(TableModel model) {
        this(model, null, null);
    }

    public ThemedTable(TableModel model, TableColumnModel cm, ListSelectionModel sm) {
        super(model, cm, sm);
        this.columnRendererGuard = evt -> {
            if (!ready || guardingRenderer || !"cellRenderer".equals(evt.getPropertyName())) {
                return;
            }
            TableColumn col = (TableColumn) evt.getSource();
            Object nv = evt.getNewValue();
            int mi = col.getModelIndex();
            if (ThemedTree.isExtensionRenderer(nv)) {
                extColumnRenderers.put(mi, (TableCellRenderer) nv);
            } else {
                TableCellRenderer keep = extColumnRenderers.get(mi);
                guardingRenderer = true;
                try {
                    col.setCellRenderer(keep);
                } finally {
                    guardingRenderer = false;
                }
            }
        };
        this.columnModelWatcher = new TableColumnModelListener() {
            @Override
            public void columnAdded(TableColumnModelEvent e) {
                attachColumn(getColumnModel(), e.getToIndex());
            }

            @Override
            public void columnRemoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnMoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnMarginChanged(javax.swing.event.ChangeEvent e) {
            }

            @Override
            public void columnSelectionChanged(javax.swing.event.ListSelectionEvent e) {
            }
        };
        this.ready = true;
        attachAllColumns(getColumnModel());
    }

    /** Records that this table uses bold headers and theme hairline horizontal grid lines. */
    public void markStyled() {
        styled = true;
    }

    /** Applies an extension renderer across all current columns as well as {@code Object/Integer/String} defaults. */
    public void setAllRenderers(TableCellRenderer renderer) {
        if (renderer == null) {
            return;
        }
        setDefaultRenderer(Object.class, renderer);
        setDefaultRenderer(String.class, renderer);
        setDefaultRenderer(Integer.class, renderer);
        TableColumnModel cm = getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) {
            cm.getColumn(i).setCellRenderer(renderer);
        }
    }

    private void attachAllColumns(TableColumnModel cm) {
        if (cm == null) {
            return;
        }
        cm.removeColumnModelListener(columnModelWatcher);
        cm.addColumnModelListener(columnModelWatcher);
        for (int i = 0; i < cm.getColumnCount(); i++) {
            attachColumn(cm, i);
        }
    }

    private void attachColumn(TableColumnModel cm, int viewIndex) {
        if (!ready || cm == null || viewIndex < 0 || viewIndex >= cm.getColumnCount()) {
            return;
        }
        TableColumn col = cm.getColumn(viewIndex);
        boolean found = false;
        for (PropertyChangeListener l : col.getPropertyChangeListeners()) {
            if (l == columnRendererGuard) {
                found = true;
                break;
            }
        }
        if (!found) {
            col.addPropertyChangeListener(columnRendererGuard);
        }
        TableCellRenderer cur = col.getCellRenderer();
        if (ThemedTree.isExtensionRenderer(cur)) {
            extColumnRenderers.put(col.getModelIndex(), cur);
        } else if (extColumnRenderers.containsKey(col.getModelIndex())) {
            guardingRenderer = true;
            try {
                col.setCellRenderer(extColumnRenderers.get(col.getModelIndex()));
            } finally {
                guardingRenderer = false;
            }
        }
    }

    @Override
    public void setColumnModel(TableColumnModel columnModel) {
        TableColumnModel prev = ready ? getColumnModel() : null;
        if (prev != null && prev != columnModel) {
            prev.removeColumnModelListener(columnModelWatcher);
        }
        super.setColumnModel(columnModel);
        if (ready) {
            attachAllColumns(columnModel);
        }
    }

    @Override
    public void setDefaultRenderer(Class<?> columnClass, TableCellRenderer renderer) {
        if (!ready) {
            super.setDefaultRenderer(columnClass, renderer);
            return;
        }
        if (ThemedTree.isExtensionRenderer(renderer)) {
            extDefaultRenderers.put(columnClass, renderer);
            super.setDefaultRenderer(columnClass, renderer);
        } else if (extDefaultRenderers.containsKey(columnClass)) {
            super.setDefaultRenderer(columnClass, extDefaultRenderers.get(columnClass));
        } else {
            super.setDefaultRenderer(columnClass, renderer);
        }
    }

    @Override
    public void setRowHeight(int rowHeight) {
        if (ready) {
            this.desiredRowHeight = rowHeight;
        }
        super.setRowHeight(rowHeight);
    }

    @Override
    public void setAutoResizeMode(int mode) {
        if (ready) {
            this.hasDesiredAutoResize = true;
            this.desiredAutoResizeMode = mode;
        }
        super.setAutoResizeMode(mode);
    }

    @Override
    public void setShowHorizontalLines(boolean showHorizontalLines) {
        if (ready) {
            this.hasDesiredShowH = true;
            this.desiredShowH = showHorizontalLines;
        }
        super.setShowHorizontalLines(showHorizontalLines);
    }

    @Override
    public void setShowVerticalLines(boolean showVerticalLines) {
        if (ready) {
            this.hasDesiredShowV = true;
            this.desiredShowV = showVerticalLines;
        }
        super.setShowVerticalLines(showVerticalLines);
    }

    @Override
    public void setIntercellSpacing(Dimension intercellSpacing) {
        if (ready && intercellSpacing != null) {
            this.desiredSpacing = new Dimension(intercellSpacing);
        }
        super.setIntercellSpacing(intercellSpacing);
    }

    @Override
    public void setFillsViewportHeight(boolean fillsViewportHeight) {
        if (ready) {
            this.hasDesiredFillsViewport = true;
            this.desiredFillsViewport = fillsViewportHeight;
        }
        super.setFillsViewportHeight(fillsViewportHeight);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (!ready || updatingUi) {
            return;
        }
        updatingUi = true;
        try {
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Map.Entry<Class<?>, TableCellRenderer> en : extDefaultRenderers.entrySet()) {
                updateRendererUi(en.getValue(), seen);
                super.setDefaultRenderer(en.getKey(), en.getValue());
            }
            guardingRenderer = true;
            try {
                TableColumnModel cm = getColumnModel();
                for (int i = 0; i < cm.getColumnCount(); i++) {
                    TableColumn col = cm.getColumn(i);
                    attachColumn(cm, i);
                    TableCellRenderer ext = extColumnRenderers.get(col.getModelIndex());
                    if (ext != null) {
                        updateRendererUi(ext, seen);
                        col.setCellRenderer(ext);
                    }
                }
            } finally {
                guardingRenderer = false;
            }
            if (desiredRowHeight > 0) {
                super.setRowHeight(desiredRowHeight);
            }
            if (hasDesiredAutoResize) {
                super.setAutoResizeMode(desiredAutoResizeMode);
            }
            if (hasDesiredShowH) {
                super.setShowHorizontalLines(desiredShowH);
            }
            if (hasDesiredShowV) {
                super.setShowVerticalLines(desiredShowV);
            }
            if (desiredSpacing != null) {
                super.setIntercellSpacing(desiredSpacing);
            }
            if (hasDesiredFillsViewport) {
                super.setFillsViewportHeight(desiredFillsViewport);
            }
            if (styled) {
                Color bg = getBackground();
                Color fg = getForeground();
                if (bg != null && fg != null) {
                    super.setGridColor(themythofcrawl.ui.theme.Theme.of(bg, fg).grid);
                }
                if (getTableHeader() != null && getTableHeader().getFont() != null) {
                    getTableHeader().setFont(getTableHeader().getFont().deriveFont(Font.BOLD));
                }
            }
        } finally {
            updatingUi = false;
        }
    }

    private static void updateRendererUi(TableCellRenderer r, Set<Object> seen) {
        if (r instanceof JComponent jc && seen.add(r)) {
            jc.updateUI();
        }
    }
}
