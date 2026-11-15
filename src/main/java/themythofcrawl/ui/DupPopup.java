package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.place.Decisions;

/** Quick look at every copy (#1 ... #n) of a duplicated API with the common actions. */
final class DupPopup {
    private DupPopup() {
    }

    static JPopupMenu show(MainPanel ui, ApiGroup g, Component anchor, int x, int y) {
        List<ApiGroup> copies = new ArrayList<>(ui.engine.copiesOf(g.bookKey()));
        copies.sort(Comparator.comparingInt(ApiGroup::dupNo));
        RowsModel<ApiGroup> model = new RowsModel<>(List.of("Bản", "API", "Đang ở", "Trạng thái", "Excel"));
        List<Decisions.Row<ApiGroup>> rows = new ArrayList<>();
        for (ApiGroup c : copies) {
            String state = c.hidden() ? "🙈 ẩn" : c.dupPending() ? "⏳ chờ quyết định" : "đang hiện";
            rows.add(new Decisions.Row<>(c, c.hidden() ? AuditLevel.PENDING : AuditLevel.NONE, List.of("#" + c.dupNo(),
                    Text.apiLabel(c.gid()), c.branch(), state, c.target() != null ? "#" + c.target().sttText() : "")));
        }
        model.set(rows);
        JTable t = new JTable(model);
        Ui.style(t);
        Ui.widths(t, 45, 70, 300, 120, 60);
        AuditRenderer r = new AuditRenderer(model, null, null, null);
        for (int i = 0; i < t.getColumnCount(); i++) {
            t.getColumnModel().getColumn(i).setCellRenderer(r);
        }
        int mine = copies.indexOf(g);
        if (mine >= 0) {
            t.setRowSelectionInterval(mine, mine);
        }
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(620, Math.min(60 + 24 * rows.size(), 260)));
        JPopupMenu pop = new JPopupMenu();
        java.util.function.Consumer<String> act = kind -> {
            int v = t.getSelectedRow();
            if (v < 0) {
                return;
            }
            ApiGroup target = model.payload(v);
            pop.setVisible(false);
            switch (kind) {
                case "open" -> ui.jumpToGroup(target);
                case "keep" -> ui.engine.keepDuplicates(List.of(target));
                case "hide" -> ui.engine.hide(List.of(target), "");
                case "show" -> ui.engine.show(List.of(target));
                case "delete" -> {
                    if (ui.confirmDialog("Xóa bản #" + target.dupNo() + "?")) {
                        ui.engine.remove(List.of(target), "xóa bản trùng");
                    }
                }
                default -> ui.showAudit(2);
            }
        };
        JLabel head = Ui.bold("  ⧉ " + g.endpoint() + "  ·  " + copies.size() + " bản (số #1, #2 ... theo thứ tự gặp)");
        head.setForeground(Palette.DUP_PURPLE);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        String[][] actions = {{"🔎 Mở", "open"}, {"✔ Giữ", "keep"}, {"🙈 Ẩn", "hide"}, {"👁 Hiện", "show"}, {"🗑 Xóa", "delete"},
            {"Chi tiết ở Audit", "audit"}};
        for (String[] a : actions) {
            btns.add(Ui.button(a[0], () -> act.accept(a[1])));
        }
        JPanel p = new JPanel(new BorderLayout(2, 2));
        p.add(head, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.add(btns, BorderLayout.SOUTH);
        pop.add(p);
        pop.show(anchor, x, y);
        return pop;
    }
}
