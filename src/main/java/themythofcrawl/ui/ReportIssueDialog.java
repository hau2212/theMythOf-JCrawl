package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import themythofcrawl.core.ActivityLog;
import themythofcrawl.ui.theme.Theme;

/**
 * V_10.4 "Báo lỗi": from any window (Ctrl+Alt+E, the Menu, or the small button in a dialog header) the user writes
 * what went wrong in their own words; it is stored in the Nhật ký with the place it came from and the last actions,
 * so the cause can be found later.
 */
final class ReportIssueDialog {
    private static final String[] KINDS = {"Lỗi / sai", "Chậm", "Khó hiểu", "Thiếu tính năng", "Đề xuất"};

    private ReportIssueDialog() {
    }

    static void open(java.awt.Component owner, String where) {
        JDialog dlg = new JDialog(Ui.ownerWindow(owner), "Báo lỗi", java.awt.Dialog.ModalityType.MODELESS);
        JComboBox<String> kind = new JComboBox<>(KINDS);
        JTextArea text = new JTextArea(7, 60);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.putClientProperty("JTextField.placeholderText", "Bạn bấm gì, mong đợi gì, thấy gì?");
        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        root.add(Ui.dialogHeader("Báo lỗi", "Ghi lại vấn đề bằng lời của bạn. Nơi xảy ra (" + where
                + ") và các thao tác vừa làm được lưu kèm trong Nhật ký."), BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(0, 4));
        center.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Loại:"), kind), BorderLayout.NORTH);
        center.add(new JScrollPane(text), BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);
        root.add(Ui.flow(FlowLayout.RIGHT, Ui.secondaryBtn("Đóng", null, dlg::dispose),
                Ui.accent("Lưu vào Nhật ký", t -> Icons.save(t.accent.onSolid()), t -> t.accent, () -> {
                    if (text.getText().isBlank()) {
                        Ui.hint(text, "Viết vài chữ mô tả vấn đề");
                        return;
                    }
                    StringBuilder ctx = new StringBuilder("Nơi: ").append(where).append("\nThao tác vừa làm:\n");
                    List<ActivityLog.Event> last = ActivityLog.recent(15);
                    for (ActivityLog.Event e : last) {
                        ctx.append(e.time()).append(' ').append(e.kind()).append(' ').append(e.text())
                                .append(e.ms() > 0 ? " (" + e.ms() + " ms)" : "").append('\n');
                    }
                    ActivityLog.add(ActivityLog.Kind.REPORT, "[" + kind.getSelectedItem() + "] " + text.getText().trim(), ctx.toString());
                    Ui.hint(text, "Đã lưu vào Nhật ký");
                    dlg.dispose();
                })), BorderLayout.SOUTH);
        dlg.setContentPane(root);
        dlg.setSize(new Dimension(Theme.current().scaled(620), Theme.current().scaled(380)));
        dlg.setLocationRelativeTo(Ui.ownerWindow(owner));
        dlg.setVisible(true);
    }
}
