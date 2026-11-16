package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;

import themythofcrawl.ui.kit.SearchField;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.kit.ThemedTree;
import themythofcrawl.ui.theme.Theme;

/** A filterable list of check boxes (key + label), with 28 px hover rows and 'all / none' toggles. */
final class CheckList<K> extends JPanel {
    record Item<K>(K key, String label) {
    }

    private final List<Item<K>> items;
    private final boolean[] on;
    private final Model model = new Model();
    private final ThemedTable table;
    private final TableRowSorter<Model> sorter;
    private final SearchField filter;
    private final JLabel count = new JLabel();
    private final Runnable changed;

    CheckList(List<Item<K>> items, Collection<K> checked, Runnable changed, String filterHint) {
        super(new BorderLayout(2, 2));
        this.items = items;
        this.on = new boolean[items.size()];
        this.changed = changed;
        this.filter = new SearchField(filterHint, 18);
        table = new ThemedTable(model);           // after `items`: the sorter reads the row count at once
        sorter = new TableRowSorter<>(model);
        Set<K> c = checked == null ? Set.of() : new HashSet<>(checked);
        for (int i = 0; i < items.size(); i++) {
            on[i] = c.contains(items.get(i).key());
        }
        table.setRowSorter(sorter);
        table.setTableHeader(null);
        table.setRowHeight(Theme.ROW_TABLE);
        table.setShowGrid(false);
        table.setShowHorizontalLines(true);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setGridColor(Theme.current().grid);
        table.markStyled();
        AuditRenderer.installHover(table);
        table.getColumnModel().getColumn(0).setMaxWidth(Theme.current().scaled(32));
        table.getColumnModel().getColumn(0).setCellRenderer(new CheckBoxHoverRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new AuditRenderer(null, null, null, null));
        filter.setToolTipText(filterHint);
        filter.onChange(q -> refilter());
        JPanel top = Ui.flow(FlowLayout.LEFT,
                filter,
                Ui.compact(Ui.button("Chọn hết", "Chọn mọi dòng đang hiện", () -> setShown(true))),
                Ui.compact(Ui.button("Bỏ hết", "Bỏ chọn mọi dòng đang hiện", () -> setShown(false))), count);
        add(top, BorderLayout.NORTH);
        add(MainPanel.scroll(table), BorderLayout.CENTER);
        counter();
    }

    private void refilter() {
        String f = filter.getText().trim().toLowerCase(Locale.ROOT);
        sorter.setRowFilter(f.isEmpty() ? null : new RowFilter<Model, Integer>() {
            @Override
            public boolean include(Entry<? extends Model, ? extends Integer> e) {
                return items.get(e.getIdentifier()).label().toLowerCase(Locale.ROOT).contains(f);
            }
        });
    }

    private void setShown(boolean v) {
        for (int r = 0; r < table.getRowCount(); r++) {
            on[table.convertRowIndexToModel(r)] = v;
        }
        model.fireTableDataChanged();
        counter();
        changed.run();
    }

    private void counter() {
        int n = 0;
        for (boolean b : on) {
            n += b ? 1 : 0;
        }
        count.setText(n + "/" + items.size() + " đã chọn");
    }

    List<K> checked() {
        List<K> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (on[i]) {
                out.add(items.get(i).key());
            }
        }
        return out;
    }

    SearchField searchField() {
        return filter;
    }

    ThemedTable listTable() {
        return table;
    }

    private static final class CheckBoxHoverRenderer extends JCheckBox implements TableCellRenderer, ThemedTree.ExtensionRenderer {
        CheckBoxHoverRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setOpaque(true);
            setBorderPainted(false);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Theme th = table != null ? Theme.of(table.getBackground(), table.getForeground()) : Theme.current();
            setSelected(Boolean.TRUE.equals(value));
            boolean hovered = AuditRenderer.isHovered(table, row);
            setBackground(isSelected ? th.selectionBg : (hovered ? th.hover : th.zebra[Math.max(0, row) & 1]));
            setForeground(isSelected ? th.selectionFg : th.fg);
            return this;
        }
    }

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return items.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 0;
        }

        @Override
        public Object getValueAt(int r, int c) {
            return c == 0 ? on[r] : items.get(r).label();
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            on[r] = Boolean.TRUE.equals(v);
            counter();
            changed.run();
        }
    }
}
