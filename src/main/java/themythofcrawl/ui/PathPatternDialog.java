package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import themythofcrawl.core.Text;
import themythofcrawl.excel.PathMatch;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.ui.theme.Theme;

/**
 * V_10.4 "Cùng 1 API": IDs can be words (/api/a/b/new, /api/a/b/old). When the Excel list has no {ID} row for such
 * a path, the user ticks the segment(s) that hold the ID; every request matching the pattern is then ONE API: the
 * earliest capture is the bản gốc, the others are duplicates, and the next request is recognised as a duplicate.
 */
final class PathPatternDialog {
    private PathPatternDialog() {
    }

    static void open(MainPanel ui, List<ApiGroup> gs) {
        JDialog dlg = new JDialog(Ui.ownerWindow(ui.root()), "Cùng 1 API (ID dạng chữ)", java.awt.Dialog.ModalityType.MODELESS);
        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        root.add(Ui.dialogHeader("Cùng 1 API", "Đánh dấu đoạn path là ID (số hoặc chữ). Mọi request cùng mẫu là 1 API:"
                + " lần bắt đầu là bản gốc, các lần sau là bản trùng. Dòng Excel có {ID} cũng làm việc này tự động."), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        DefaultListModel<String> existing = new DefaultListModel<>();
        Runnable reloadList = () -> {
            existing.clear();
            for (String p : ui.engine.pathPatterns()) {
                existing.addElement(p);
            }
        };
        reloadList.run();

        ApiGroup first = gs == null || gs.isEmpty() ? null : gs.get(0);
        List<JCheckBox> boxes = new ArrayList<>();
        JLabel preview = Ui.bold(" ");
        JTextArea matches = new JTextArea(6, 60);
        matches.setEditable(false);
        matches.setLineWrap(true);
        String method = first == null ? "GET" : first.method().toUpperCase(Locale.ROOT);
        List<String> segs = first == null ? List.of() : PathMatch.segments(first.path());
        Runnable[] refresh = new Runnable[1];
        if (first != null) {
            boolean[] differ = differing(gs, segs.size());
            JPanel segRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
            segRow.add(Ui.bold(method));
            for (int i = 0; i < segs.size(); i++) {
                segRow.add(new JLabel("/"));
                JCheckBox b = new JCheckBox(segs.get(i), differ[i] || Text.isIdSegment(segs.get(i)));
                b.setToolTipText("Tích nếu đoạn này là ID");
                b.addActionListener(Ui.safe(e -> refresh[0].run()));
                boxes.add(b);
                segRow.add(b);
            }
            JPanel top = new JPanel(new BorderLayout(0, 4));
            top.add(Ui.muted("Tích các đoạn là ID" + (gs.size() > 1 ? " (đã tích sẵn đoạn khác nhau giữa " + gs.size() + " API đang chọn)" : "") + ":"),
                    BorderLayout.NORTH);
            top.add(segRow, BorderLayout.CENTER);
            top.add(preview, BorderLayout.SOUTH);
            JPanel mid = new JPanel(new BorderLayout(0, 2));
            mid.add(top, BorderLayout.NORTH);
            mid.add(new JScrollPane(matches), BorderLayout.CENTER);
            center.add(mid, BorderLayout.NORTH);
        }
        refresh[0] = () -> {
            String tpl = template(segs, boxes);
            if (tpl == null) {
                preview.setText("Chưa tích đoạn nào");
                matches.setText("");
                return;
            }
            preview.setText("Mẫu: " + method + " " + tpl);
            StringBuilder sb = new StringBuilder();
            int n = 0;
            var rx = PathMatch.template(tpl, false).exact();
            for (ApiGroup g : ui.engine.groups()) {
                if (g.method().equalsIgnoreCase(method) && rx.matcher(PathMatch.normalize(g.path())).matches()) {
                    n++;
                    if (n <= 30) {
                        sb.append(Text.apiLabel(g.gid())).append("  ").append(Text.shortPath(g.path(), 90)).append('\n');
                    }
                }
            }
            matches.setText((n > 30 ? "... và " + (n - 30) + " API khác\n" : "") + sb);
            matches.setCaretPosition(0);
            preview.setText("Mẫu: " + method + " " + tpl + "   ·   gộp " + n + " API đã bắt thành 1 API");
        };
        refresh[0].run();

        JList<String> list = new JList<>(existing);
        JPanel listPanel = new JPanel(new BorderLayout(0, 2));
        listPanel.add(Ui.bold("Mẫu đã có"), BorderLayout.NORTH);
        JScrollPane ls = new JScrollPane(list);
        ls.setPreferredSize(new Dimension(600, 120));
        listPanel.add(ls, BorderLayout.CENTER);
        listPanel.add(Ui.flow(FlowLayout.LEFT, Ui.button("Xóa mẫu", t -> Icons.trash(t.danger.text()), "Bỏ mẫu đang chọn (các API tách lại)", () -> {
            String sel = list.getSelectedValue();
            if (sel == null) {
                Ui.hint(list, "Chọn 1 mẫu trước");
                return;
            }
            ui.engine.removePathPattern(sel);
            reloadList.run();
            ui.tick(true);
            ui.flash("Đã bỏ mẫu " + sel);
        })), BorderLayout.SOUTH);
        center.add(listPanel, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        List<java.awt.Component> buttons = new ArrayList<>();
        buttons.add(Ui.secondaryBtn("Đóng", null, dlg::dispose));
        if (first != null) {
            buttons.add(Ui.accent("Gộp thành 1 API", t -> Icons.check(t.accent.onSolid()), t -> t.accent, () -> {
                String tpl = template(segs, boxes);
                if (tpl == null) {
                    Ui.hint(preview, "Tích ít nhất 1 đoạn là ID");
                    return;
                }
                int n = ui.engine.addPathPattern(method, tpl);
                reloadList.run();
                ui.tick(true);
                ui.flash("Mẫu " + method + " " + tpl + ": " + n + " API thành lần bắt trùng");
                dlg.dispose();
            }));
        }
        root.add(Ui.flow(FlowLayout.RIGHT, buttons.toArray(new java.awt.Component[0])), BorderLayout.SOUTH);
        dlg.setContentPane(root);
        dlg.setSize(new Dimension(Theme.current().scaled(760), Theme.current().scaled(first == null ? 360 : 560)));
        dlg.setLocationRelativeTo(ui.root());
        dlg.setVisible(true);
    }

    /** Positions where the selected APIs (same number of segments) have different values. */
    static boolean[] differing(List<ApiGroup> gs, int n) {
        boolean[] out = new boolean[n];
        if (gs.size() < 2) {
            return out;
        }
        List<String> a = PathMatch.segments(gs.get(0).path());
        for (ApiGroup g : gs.subList(1, gs.size())) {
            List<String> b = PathMatch.segments(g.path());
            if (b.size() != a.size()) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                if (!a.get(i).equalsIgnoreCase(b.get(i))) {
                    out[i] = true;
                }
            }
        }
        return out;
    }

    static String template(List<String> segs, List<JCheckBox> boxes) {
        if (segs.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int ids = 0;
        for (int i = 0; i < segs.size(); i++) {
            boolean id = i < boxes.size() && boxes.get(i).isSelected();
            sb.append('/').append(id ? (ids == 0 ? "{id}" : "{id" + (ids + 1) + "}") : segs.get(i));
            ids += id ? 1 : 0;
        }
        return ids == 0 ? null : sb.toString();
    }
}
