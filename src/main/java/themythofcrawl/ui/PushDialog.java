package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import themythofcrawl.app.Engine;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.ui.theme.Theme;

/**
 * 'Đẩy / Gửi có chọn Role': for one API, several APIs or a whole branch choose push to Repeater or send,
 * the API gốc and any subset of roles, an 'only failing / diverging' filter and the tab naming. Shows how
 * many tabs / requests that makes; remembers the last choice.
 */
public final class PushDialog {
    /** The user's choice. */
    public record Choice(boolean send, boolean original, Set<String> roles, Set<String> unchecked, boolean onlyFlagged,
                         boolean compact) {
    }

    private PushDialog() {
    }

    public static Choice show(Component parent, List<ApiGroup> groups, String label, Choice memory, boolean send) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, "Đẩy / Gửi có chọn Role - " + label, JDialog.ModalityType.APPLICATION_MODAL);
        d.setLayout(new BorderLayout(6, 6));
        Theme th = Theme.current();
        Choice mem = memory != null ? memory : new Choice(false, false, Set.of(), Set.of(), false, true);
        JPanel head = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        long branches = groups.stream().map(ApiGroup::branch).distinct().count();
        head.add(Ui.dialogHeader("Đẩy / Gửi có chọn Role - " + label,
                groups.size() + " API  ·  " + branches + " nhánh"), BorderLayout.NORTH);
        JRadioButton push = new JRadioButton("Đẩy sang Burp Repeater", !send);
        JRadioButton sendRb = new JRadioButton("Gửi request (hàng đợi chống 429)", send);
        ButtonGroup bg = new ButtonGroup();
        bg.add(push);
        bg.add(sendRb);
        JPanel modeRow = Ui.flow(FlowLayout.LEFT, push, sendRb);
        modeRow.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
        head.add(modeRow, BorderLayout.SOUTH);
        d.add(head, BorderLayout.NORTH);

        JPanel listing = new JPanel();
        listing.setLayout(new BoxLayout(listing, BoxLayout.Y_AXIS));
        listing.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        JCheckBox orig = new JCheckBox("API GỐC (Acc Crawl) - request đã bắt, áp profile API gốc nếu bật", mem.original());
        orig.setFont(th.boldFont);
        orig.setForeground(th.orig.text());
        listing.add(orig);
        listing.add(Box.createVerticalStrut(4));
        List<Map.Entry<String, Integer>> names = Engine.roleNamesIn(groups);
        List<Object[]> boxes = new ArrayList<>();
        if (names.isEmpty()) {
            listing.add(Ui.muted("   (Các API này không có role nào - chỉ có thể đẩy / gửi API gốc)"));
        }
        for (Map.Entry<String, Integer> n : names) {
            JCheckBox b = new JCheckBox(n.getKey() + "      (có ở " + n.getValue() + "/" + groups.size() + " API)",
                    !mem.unchecked().contains(n.getKey()));
            boxes.add(new Object[] {n.getKey(), b});
            listing.add(b);
        }
        JScrollPane scroll = new JScrollPane(listing);
        scroll.setBorder(themythofcrawl.ui.kit.SectionBorder.of("Đối tượng (API gốc và / hoặc các Role)"));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        JCheckBox flagged = new JCheckBox("Chỉ lấy role đang Lỗi / Lệch / Nghi bypass (Vàng / Cam / Đỏ) ở từng API", mem.onlyFlagged());
        JRadioButton compact = new JRadioButton("Tên tab gọn: G01•01_Admin (+ copy tên Group vào clipboard)", mem.compact());
        JRadioButton full = new JRadioButton("Tên tab đầy đủ: [G01:METHOD /path] 01_Admin", !mem.compact());
        ButtonGroup naming = new ButtonGroup();
        naming.add(compact);
        naming.add(full);
        JLabel summary = Ui.bold(" ");
        Choice[] result = {null};
        JButton ok = Ui.accent("Thực hiện", t -> Icons.check(t.accent.onSolid()), t -> t.accent, () -> {
            Set<String> chosen = new HashSet<>();
            Set<String> un = new HashSet<>();
            for (Object[] b : boxes) {
                (((JCheckBox) b[1]).isSelected() ? chosen : un).add((String) b[0]);
            }
            result[0] = new Choice(sendRb.isSelected(), orig.isSelected(), chosen, un, flagged.isSelected(), compact.isSelected());
            d.dispose();
        });
        Runnable update = () -> {
            Set<String> chosen = new HashSet<>();
            for (Object[] b : boxes) {
                if (((JCheckBox) b[1]).isSelected()) {
                    chosen.add((String) b[0]);
                }
            }
            int total = 0;
            int apis = 0;
            for (ApiGroup g : groups) {
                int n = Engine.selectTargets(g, orig.isSelected(), chosen, flagged.isSelected()).size();
                total += n;
                apis += n > 0 ? 1 : 0;
            }
            summary.setText((sendRb.isSelected() ? "Sẽ gửi " + total + " request" : "Sẽ tạo " + total + " tab Repeater")
                    + " cho " + apis + " / " + groups.size() + " API");
            ok.setEnabled(total > 0);
            compact.setEnabled(push.isSelected());
            full.setEnabled(push.isSelected());
        };
        for (Object[] b : boxes) {
            ((JCheckBox) b[1]).addActionListener(e -> update.run());
        }
        for (javax.swing.AbstractButton b : new javax.swing.AbstractButton[] {orig, flagged, push, sendRb}) {
            b.addActionListener(e -> update.run());
        }
        java.util.function.BiConsumer<Boolean, Boolean> setAll = (o, r) -> {
            orig.setSelected(o);
            for (Object[] b : boxes) {
                ((JCheckBox) b[1]).setSelected(r);
            }
            update.run();
        };
        JPanel quick = Ui.flow(FlowLayout.LEFT, Ui.button("Tất cả", () -> setAll.accept(true, true)),
                Ui.button("Chỉ API gốc", () -> setAll.accept(true, false)),
                Ui.button("Chỉ các Role", () -> setAll.accept(false, true)),
                Ui.button("Bỏ chọn hết", () -> setAll.accept(false, false)),
                Ui.button("Đảo chọn", () -> {
                    orig.setSelected(!orig.isSelected());
                    for (Object[] b : boxes) {
                        ((JCheckBox) b[1]).setSelected(!((JCheckBox) b[1]).isSelected());
                    }
                    update.run();
                }));
        JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        for (javax.swing.JComponent c : new javax.swing.JComponent[] {flagged, compact, full}) {
            c.setAlignmentX(0f);
            options.add(c);
        }
        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(quick, BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);
        center.add(options, BorderLayout.SOUTH);
        d.add(center, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));
        bottom.add(summary, BorderLayout.CENTER);
        bottom.add(Ui.flow(FlowLayout.RIGHT, Ui.button("Hủy", d::dispose), ok), BorderLayout.EAST);
        d.add(bottom, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(ok);
        Theme.track(d);
        update.run();
        d.setSize(new Dimension(720, 560));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return result[0];
    }
}
