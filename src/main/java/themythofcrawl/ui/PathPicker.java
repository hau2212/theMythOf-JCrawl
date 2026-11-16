package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

import themythofcrawl.core.Hierarchy;
import themythofcrawl.ui.kit.SearchField;
import themythofcrawl.ui.theme.Theme;

/** Pick a branch: filterable list of every known path, or type a new one ('/ > a > b'). */
public final class PathPicker {
    private PathPicker() {
    }

    public static String pick(Component parent, String title, Collection<String> paths, String initial, String hint) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);
        d.setLayout(new BorderLayout(6, 6));
        List<String> all = new ArrayList<>(paths);
        all.sort((a, b) -> Hierarchy.key(a).compareTo(Hierarchy.key(b)));
        SearchField filter = new SearchField("Lọc đường dẫn...", 24);
        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(Theme.current().scaled(Theme.ROW_TABLE));
        JTextField field = new JTextField(initial == null ? "" : initial);
        field.putClientProperty("JTextField.placeholderText", "/ > nhóm > đích");
        String[] result = {null};
        Runnable refilter = () -> {
            String t = filter.getText().trim().toLowerCase(java.util.Locale.ROOT);
            model.clear();
            for (String p : all) {
                if (t.isEmpty() || p.toLowerCase(java.util.Locale.ROOT).contains(t)) {
                    model.addElement(p);
                }
            }
        };
        filter.onChange(q -> refilter.run());
        Runnable ok = () -> {
            String t = field.getText().trim();
            if (!t.isEmpty()) {
                result[0] = t.contains(">") ? Hierarchy.normalize(t) : t;
                d.dispose();
            }
        };
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) {
                field.setText(list.getSelectedValue());
            }
        });
        Ui.onDoubleClick(list, ok);
        field.addActionListener(e -> ok.run());
        JPanel head = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        String subtitle = hint != null && !hint.isBlank() ? hint : "Chọn đường dẫn đích có sẵn hoặc nhập đường dẫn mới ngăn cách bởi '>'";
        head.add(Ui.dialogHeader(title, subtitle), BorderLayout.NORTH);
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_3, Theme.SPACE_1, Theme.SPACE_3));
        row.add(filter, BorderLayout.CENTER);
        head.add(row, BorderLayout.SOUTH);
        d.add(head, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createTitledBorder("Các đường dẫn đã có (click đúp để chọn)"));
        d.add(sp, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(4, Theme.SPACE_2));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));
        JPanel nf = new JPanel(new BorderLayout(4, 2));
        nf.add(new JLabel("Đường dẫn (sửa hoặc nhập mới, ngăn cách bởi '>'):"), BorderLayout.NORTH);
        nf.add(field, BorderLayout.CENTER);
        bottom.add(nf, BorderLayout.NORTH);
        JButton choose = Ui.accent("Chọn", t -> Icons.check(t.accent.onSolid()), t -> t.accent, ok);
        JPanel btns = Ui.flow(FlowLayout.RIGHT, Ui.button("Hủy", d::dispose), choose);
        bottom.add(btns, BorderLayout.SOUTH);
        d.add(bottom, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(choose);
        Theme.track(d);
        refilter.run();
        d.setSize(new Dimension(640, 520));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return result[0];
    }
}
