package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.model.Branch;

/** Edit the levels of the destination branch (add / rename / delete / reorder). Applied as a rename. */
final class LevelsDialog {
    private LevelsDialog() {
    }

    static void show(Component parent, Engine engine) {
        Branch b = engine.activeBranch();
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, "Các cấp của đích: " + b.name(), JDialog.ModalityType.APPLICATION_MODAL);
        DefaultListModel<String> model = new DefaultListModel<>();
        for (String p : Hierarchy.split(b.name())) {
            model.addElement(p);
        }
        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Runnable add = () -> {
            Object v = JOptionPane.showInputDialog(d, "Tên cấp mới (thêm sau cấp đang chọn):", "Subfolder_mới");
            if (v != null && !v.toString().isBlank()) {
                int i = list.getSelectedIndex();
                model.add(i < 0 ? model.size() : i + 1, v.toString().trim());
            }
        };
        Runnable edit = () -> {
            int i = list.getSelectedIndex();
            if (i >= 0) {
                Object v = JOptionPane.showInputDialog(d, "Sửa tên cấp " + (i + 1) + ":", model.get(i));
                if (v != null && !v.toString().isBlank()) {
                    model.set(i, v.toString().trim());
                }
            }
        };
        Runnable del = () -> {
            int i = list.getSelectedIndex();
            if (i >= 0 && model.size() > 1) {
                model.remove(i);
            }
        };
        java.util.function.IntConsumer move = delta -> {
            int i = list.getSelectedIndex();
            int j = i + delta;
            if (i >= 0 && j >= 0 && j < model.size()) {
                String t = model.get(i);
                model.set(i, model.get(j));
                model.set(j, t);
                list.setSelectedIndex(j);
            }
        };
        Runnable apply = () -> {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < model.size(); i++) {
                parts.add(model.get(i));
            }
            String path = Hierarchy.join(parts);
            if (!Hierarchy.same(path, b.name())) {
                engine.renameBranch(b.id(), path);
            }
            d.dispose();
        };
        d.setLayout(new BorderLayout(6, 6));
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createTitledBorder("Cấp 1 ở trên cùng"));
        d.add(sp, BorderLayout.CENTER);
        d.add(Ui.flow(FlowLayout.LEFT, Ui.button("➕ Thêm", add), Ui.button("✏ Sửa", edit), Ui.button("🗑 Xóa", del),
                Ui.button("⬆", () -> move.accept(-1)), Ui.button("⬇", () -> move.accept(1))), BorderLayout.NORTH);
        d.add(Ui.flow(FlowLayout.RIGHT, Ui.button("Áp dụng (đổi tên đích, API trong đích đổi theo)", apply),
                Ui.button("Hủy", d::dispose)), BorderLayout.SOUTH);
        d.setSize(new Dimension(520, 420));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }
}
