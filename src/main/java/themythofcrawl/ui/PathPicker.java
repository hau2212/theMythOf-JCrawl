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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import themythofcrawl.core.Hierarchy;

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
        JTextField filter = new JTextField();
        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JTextField field = new JTextField(initial == null ? "" : initial);
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
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refilter.run();
            }
        });
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
        JPanel head = new JPanel(new BorderLayout(4, 4));
        head.setBorder(BorderFactory.createEmptyBorder(8, 10, 0, 10));
        if (hint != null) {
            head.add(new JLabel(hint), BorderLayout.NORTH);
        }
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.add(new JLabel("Lọc:"), BorderLayout.WEST);
        row.add(filter, BorderLayout.CENTER);
        head.add(row, BorderLayout.SOUTH);
        d.add(head, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createTitledBorder("Các đường dẫn đã có (click đúp để chọn)"));
        d.add(sp, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        JPanel nf = new JPanel(new BorderLayout(4, 0));
        nf.add(new JLabel("Đường dẫn (sửa hoặc nhập mới, ngăn cách bởi '>'):"), BorderLayout.NORTH);
        nf.add(field, BorderLayout.CENTER);
        bottom.add(nf, BorderLayout.NORTH);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton choose = Ui.button("Chọn", ok);
        choose.setFont(choose.getFont().deriveFont(java.awt.Font.BOLD));
        btns.add(choose);
        btns.add(Ui.button("Hủy", d::dispose));
        bottom.add(btns, BorderLayout.SOUTH);
        d.add(bottom, BorderLayout.SOUTH);
        refilter.run();
        d.setSize(new Dimension(640, 520));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return result[0];
    }
}
