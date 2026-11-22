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
import themythofcrawl.ui.kit.ThemedTable;

/** V_10.4 Excel > Hỏi đáp: every Q&A / Info item, filters, edit, export (this account or every account). */
final class QaTab {
    final JPanel panel = new JPanel(new BorderLayout(0, 4));
    private final MainPanel ui;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[] {"Mã", "Loại", "API", "Method", "Path", "Nhánh", "Câu hỏi / ghi chú", "Ảnh", "Mức độ", "Trạng thái", "Trả lời", "Người hỏi"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    final ThemedTable table = new ThemedTable(model);
    private final JComboBox<String> kind = new JComboBox<>(new String[] {"Mọi loại", "Q&A", "Info"});
    private final JComboBox<String> status = new JComboBox<>(new String[] {"Mọi trạng thái", "Mới", "Đã gửi khách hàng", "Đã trả lời", "Đóng"});
    private final JTextField search = new JTextField(18);
    private final List<Engine.QaRow> shown = new ArrayList<>();

    QaTab(MainPanel ui) {
        this.ui = ui;
        Ui.style(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Ui.widths(table, 80, 50, 60, 60, 240, 150, 300, 40, 80, 110, 220, 80);
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
            model.addRow(new Object[] {it.code(), it.kind().label, Text.apiLabel(r.group().gid()), r.group().method(), r.group().path(),
                r.group().branch(), it.question(), it.imgs().size(), it.severity().label, it.status().label, it.answer(), it.by()});
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

    /** Export Q&A / Info (this account or every account of the project) with screenshots by step. */
    private void export() {
        JCheckBox qa = new JCheckBox("Q&A", true);
        JCheckBox info = new JCheckBox("Info", true);
        JCheckBox detail = new JCheckBox("Kèm sheet Chi tiết Q&A (mỗi Q&A 1 khối: bước + ảnh + ô trả lời)", true);
        JComboBox<String> scope = new JComboBox<>(new String[] {"Chỉ tài khoản này (" + ui.engine.workspaceName() + ")",
            "Mọi tài khoản của dự án (cả của đồng đội)"});
        JCheckBox withReport = new JCheckBox("Kèm Tổng quan + Tổng hợp", false);
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        form.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Xuất:"), qa, info));
        form.add(detail);
        form.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Phạm vi:"), scope));
        form.add(withReport);
        if (javax.swing.JOptionPane.showConfirmDialog(Ui.ownerWindow(panel), form, "Xuất Excel Q&A",
                javax.swing.JOptionPane.OK_CANCEL_OPTION) != javax.swing.JOptionPane.OK_OPTION) {
            return;
        }
        themythofcrawl.project.ProjectData d = ui.project.buildNow();
        if (d == null) {
            ui.info("Workspace này chưa lưu trên đĩa.");
            return;
        }
        List<themythofcrawl.project.SheetDef> sheets = new ArrayList<>();
        if (withReport.isSelected()) {
            sheets.add(themythofcrawl.project.SheetDef.of(themythofcrawl.project.SheetDef.Type.OVERVIEW));
            sheets.add(themythofcrawl.project.SheetDef.of(themythofcrawl.project.SheetDef.Type.SUMMARY));
        }
        if (qa.isSelected()) {
            sheets.add(themythofcrawl.project.SheetDef.of(themythofcrawl.project.SheetDef.Type.QA));
        }
        if (info.isSelected()) {
            sheets.add(themythofcrawl.project.SheetDef.of(themythofcrawl.project.SheetDef.Type.INFO));
        }
        if (detail.isSelected()) {
            sheets.add(themythofcrawl.project.SheetDef.of(themythofcrawl.project.SheetDef.Type.QA_DETAIL));
        }
        List<String> letters = scope.getSelectedIndex() == 0 ? List.of(ui.project.myLetter()) : d.letters();
        Path dir = ui.project.store().reportsDir("qa");
        JFileChooser fc = new JFileChooser(dir.toFile());
        fc.setSelectedFile(dir.resolve("HoiDap_" + themythofcrawl.project.ProjectStore.safe(ui.project.config().name) + ".xlsx").toFile());
        if (fc.showSaveDialog(Ui.ownerWindow(panel)) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) {
            return;
        }
        try {
            themythofcrawl.project.ProjectSync sync = ui.project.sync();
            themythofcrawl.project.ProjectWorkbook.Options o = new themythofcrawl.project.ProjectWorkbook.Options(letters, "Q&A", true, 320, false,
                    List.of(), sync == null ? null : sync::remoteImage, ui.project.config().name);
            Files.write(fc.getSelectedFile().toPath(), themythofcrawl.project.ProjectWorkbook.xlsx(d, sheets, o));
            ui.flash("Đã xuất " + fc.getSelectedFile().getName());
        } catch (Exception ex) {
            ui.info("Không xuất được: " + ex.getMessage());
        }
    }
}
