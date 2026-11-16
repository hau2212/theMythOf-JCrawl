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
import themythofcrawl.ui.theme.Theme;

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
        list.setFixedCellHeight(Theme.current().scaled(Theme.ROW_TABLE));
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
        JPanel north = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        north.add(Ui.dialogHeader("Các cấp của đích: " + b.name(),
                "Cấp 1 ở trên cùng — thêm, sửa, xóa hoặc đổi thứ tự"), BorderLayout.NORTH);
        JPanel tools = Ui.flow(FlowLayout.LEFT,
                Ui.button("Thêm", t -> Icons.plus(t.success.text()), add),
                Ui.button("Sửa", t -> Icons.edit(t.info.text()), edit),
                Ui.button("Xóa", t -> Icons.trash(t.danger.text()), del),
                Ui.button("", t -> Icons.arrowUp(t.fg), "Đưa lên", () -> move.accept(-1)),
                Ui.button("", t -> Icons.arrowDown(t.fg), "Đưa xuống", () -> move.accept(1)));
        tools.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
        north.add(tools, BorderLayout.SOUTH);
        d.add(north, BorderLayout.NORTH);

        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_3, 0, Theme.SPACE_3));
        d.add(sp, BorderLayout.CENTER);

        javax.swing.JButton applyBtn = Ui.accent("Áp dụng (đổi tên đích, API trong đích đổi theo)",
                t -> Icons.check(t.accent.onSolid()), t -> t.accent, apply);
        JPanel bottom = Ui.flow(FlowLayout.RIGHT, Ui.button("Hủy", d::dispose), applyBtn);
        bottom.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));
        d.add(bottom, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(applyBtn);
        Theme.track(d);
        d.setSize(new Dimension(560, 460));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }
}
