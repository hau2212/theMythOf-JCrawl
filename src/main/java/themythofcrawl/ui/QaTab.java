package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Text;
import themythofcrawl.model.QaItem;
import themythofcrawl.store.FamilyReport;
import themythofcrawl.ui.kit.ThemedTable;

/** V_10.4 Excel > Hỏi đáp: every Q&A / Info item, filters, edit, export (this account or every account). */
final class QaTab {
    final JPanel panel = new JPanel(new BorderLayout(0, 4));
    private final MainPanel ui;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[] {"Loại", "API", "Method", "Path", "Nhánh", "Câu hỏi / ghi chú", "Ảnh", "Trạng thái", "Trả lời", "Người hỏi"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    final ThemedTable table = new ThemedTable(model);
    private final JComboBox<String> kind = new JComboBox<>(new String[] {"Mọi loại", "Q&A", "Info"});
    private final JComboBox<String> status = new JComboBox<>(new String[] {"Mọi trạng thái", "Chờ trả lời", "Đã trả lời", "Đóng"});
    private final JTextField search = new JTextField(18);
    private final List<Engine.QaRow> shown = new ArrayList<>();

    QaTab(MainPanel ui) {
        this.ui = ui;
        Ui.style(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Ui.widths(table, 60, 60, 60, 260, 160, 320, 40, 90, 220, 70);
        Ui.onDoubleClick(table, this::editSelected);
        search.putClientProperty("JTextField.placeholderText", "Tìm path, câu hỏi, trả lời...");
        kind.addActionListener(Ui.safe(e -> reload()));
        status.addActionListener(Ui.safe(e -> reload()));
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { reload(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { reload(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { reload(); }
        });
        JPanel bar = new JPanel(new BorderLayout());
        bar.add(Ui.flow(FlowLayout.LEFT, kind, status, search), BorderLayout.WEST);
        bar.add(Ui.flow(FlowLayout.RIGHT,
                Ui.button("Sửa / trả lời", t -> Icons.edit(t.fg, 13), "Mở hỏi đáp đang chọn (nhấp đúp cũng được)", this::editSelected),
                Ui.button("Xóa", t -> Icons.trash(t.danger.text()), "Xóa hỏi đáp đang chọn", this::deleteSelected),
                Ui.button("Mở API", t -> Icons.api(t.fg), "Đi tới API trên Cây", this::openSelected),
                Ui.accent("Xuất Excel Q&A...", t -> Icons.excel(t.accent.onSolid(), 13), t -> t.accent, this::export)), BorderLayout.EAST);
        panel.add(bar, BorderLayout.NORTH);
        panel.add(MainPanel.scroll(table), BorderLayout.CENTER);
        panel.add(Ui.muted("Thêm hỏi đáp: chuột phải 1 API ở Cây / API -> Hỏi đáp (Q&A / Info)... · Ảnh: thêm từ file hoặc dán Ctrl+V."),
                BorderLayout.SOUTH);
    }

    void reload() {
        shown.clear();
        model.setRowCount(0);
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        for (Engine.QaRow r : ui.engine.qaRows()) {
            QaItem it = r.item();
            if (kind.getSelectedIndex() == 1 && it.kind() != QaItem.Kind.QA || kind.getSelectedIndex() == 2 && it.kind() != QaItem.Kind.INFO) {
                continue;
            }
            if (status.getSelectedIndex() > 0 && it.status().ordinal() != status.getSelectedIndex() - 1) {
                continue;
            }
            String hay = (r.group().path() + " " + it.question() + " " + it.answer()).toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !hay.contains(q)) {
                continue;
            }
            shown.add(r);
            model.addRow(new Object[] {it.kind().label, Text.apiLabel(r.group().gid()), r.group().method(), r.group().path(),
                r.group().branch(), it.question(), it.images().size(), it.status().label, it.answer(), it.by()});
        }
    }

    private Engine.QaRow selected() {
        int v = table.getSelectedRow();
        return v < 0 ? null : shown.get(table.convertRowIndexToModel(v));
    }

    private void editSelected() {
        Engine.QaRow r = selected();
        if (r == null) {
            Ui.hint(table, "Chọn 1 dòng trước");
            return;
        }
        QaDialog.open(ui, r.group(), r.item());
    }

    private void deleteSelected() {
        Engine.QaRow r = selected();
        if (r == null) {
            Ui.hint(table, "Chọn 1 dòng trước");
            return;
        }
        if (ui.confirmDialog("Xóa " + r.item().kind().label + " của " + Text.apiLabel(r.group().gid()) + "?")) {
            ui.engine.removeQa(r.group(), r.item().id());
            reload();
        }
    }

    private void openSelected() {
        Engine.QaRow r = selected();
        if (r == null) {
            Ui.hint(table, "Chọn 1 dòng trước");
            return;
        }
        ui.selectTab(ui.treeTab.panel);
        ui.treeTab.tree.expandApi(r.group().gid());
    }

    /** Export: only this account or every account of the workspace family; Q&A and / or Info. */
    private void export() {
        JCheckBox qa = new JCheckBox("Q&A", true);
        JCheckBox info = new JCheckBox("Info", true);
        JComboBox<String> scope = new JComboBox<>(new String[] {"Chỉ tài khoản này (" + ui.engine.workspaceName() + ")",
            "Mọi tài khoản (cả họ workspace, có cột tài khoản)"});
        JCheckBox withReport = new JCheckBox("Kèm báo cáo tổng hợp (Tổng quan, Ma trận tài khoản, API từng tài khoản)", false);
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        form.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Xuất:"), qa, info));
        form.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Phạm vi:"), scope));
        form.add(withReport);
        if (javax.swing.JOptionPane.showConfirmDialog(Ui.ownerWindow(panel), form, "Xuất Excel Q&A",
                javax.swing.JOptionPane.OK_CANCEL_OPTION) != javax.swing.JOptionPane.OK_OPTION) {
            return;
        }
        List<QaItem.Kind> kinds = new ArrayList<>();
        if (qa.isSelected()) {
            kinds.add(QaItem.Kind.QA);
        }
        if (info.isSelected()) {
            kinds.add(QaItem.Kind.INFO);
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("jcrawl-hoi-dap.xlsx"));
        if (fc.showSaveDialog(Ui.ownerWindow(panel)) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) {
            return;
        }
        ui.engine.flush();
        try {
            Path ws = Path.of(ui.engine.workspaceLocation());
            FamilyReport rep = FamilyReport.load(ws);
            String only = scope.getSelectedIndex() == 0 ? FamilyReport.accountOf(themythofcrawl.store.Workspaces.info(ws)) : null;
            byte[] x = rep.workbook(withReport.isSelected(), kinds, only);
            Files.write(fc.getSelectedFile().toPath(), x);
            ui.flash("Đã xuất " + fc.getSelectedFile().getName());
        } catch (Exception ex) {
            ui.info("Không xuất được: " + ex.getMessage());
        }
    }
}
