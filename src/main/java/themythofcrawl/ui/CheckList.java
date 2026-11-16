package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

import themythofcrawl.ui.kit.ThemedTable;

/** A filterable list of check boxes (key + label), with 'all / none' for the rows shown. */
final class CheckList<K> extends JPanel {
    record Item<K>(K key, String label) {
    }

    private final List<Item<K>> items;
    private final boolean[] on;
    private final Model model = new Model();
    private final JTable table;
    private final TableRowSorter<Model> sorter;
    private final JTextField filter = new JTextField(18);
    private final JLabel count = new JLabel();
    private final Runnable changed;

    CheckList(List<Item<K>> items, Collection<K> checked, Runnable changed, String filterHint) {
        super(new BorderLayout(2, 2));
        this.items = items;
        this.on = new boolean[items.size()];
        this.changed = changed;
        table = new ThemedTable(model);           // after `items`: the sorter reads the row count at once
        sorter = new TableRowSorter<>(model);
        Set<K> c = checked == null ? Set.of() : new HashSet<>(checked);
        for (int i = 0; i < items.size(); i++) {
            on[i] = c.contains(items.get(i).key());
        }
        table.setRowSorter(sorter);
        table.setTableHeader(null);
        table.setRowHeight(22);
        table.setShowGrid(false);
        table.getColumnModel().getColumn(0).setMaxWidth(28);
        filter.setToolTipText(filterHint);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refilter();
            }
        });
        JPanel top = Ui.flow(FlowLayout.LEFT, new JLabel("🔎"), filter, Ui.compact(Ui.button("Chọn hết", "Chọn mọi dòng đang hiện",
                () -> setShown(true))), Ui.compact(Ui.button("Bỏ hết", "Bỏ chọn mọi dòng đang hiện", () -> setShown(false))), count);
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
