package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.place.Decisions;
import themythofcrawl.ui.kit.Card;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

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
            String state = c.hidden() ? "ẩn" : c.dupPending() ? "chờ quyết định" : "đang hiện";
            rows.add(new Decisions.Row<>(c, c.hidden() ? AuditLevel.PENDING : AuditLevel.NONE, List.of("#" + c.dupNo(),
                    Text.apiLabel(c.gid()), c.branch(), state, c.target() != null ? "#" + c.target().sttText() : "")));
        }
        model.set(rows);
        JTable t = new ThemedTable(model);
        Ui.style(t);
        Ui.widths(t, 52, 70, 290, 130, 60);
        AuditRenderer r = new AuditRenderer(model, null, 3, null, 0);
        for (int i = 0; i < t.getColumnCount(); i++) {
            t.getColumnModel().getColumn(i).setCellRenderer(r);
        }
        int mine = copies.indexOf(g);
        if (mine >= 0) {
            t.setRowSelectionInterval(mine, mine);
        }
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(620, Math.min(72 + 28 * rows.size(), 280)));
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
        Card card = new Card(g.endpoint(), copies.size() + " bản (số #1, #2 ... theo thứ tự gặp)",
                new JLabel(Icons.themed(theme -> Icons.dup(theme.dup.text()))), theme -> theme.dup, true);
        card.setPadding(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        btns.setOpaque(false);
        btns.add(Ui.button("Mở", theme -> Icons.search(theme.info.text()), () -> act.accept("open")));
        btns.add(Ui.button("Giữ", theme -> Icons.check(theme.success.text()), () -> act.accept("keep")));
        btns.add(Ui.button("Ẩn", theme -> Icons.hide(theme.muted), () -> act.accept("hide")));
        btns.add(Ui.button("Hiện", theme -> Icons.show(theme.info.text()), () -> act.accept("show")));
        btns.add(Ui.button("Xóa", theme -> Icons.trash(theme.danger.text()), () -> act.accept("delete")));
        btns.add(Ui.button("Chi tiết ở Audit", theme -> Icons.compass(theme.accent.text()), () -> act.accept("audit")));

        JPanel inner = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        inner.setOpaque(false);
        inner.add(sp, BorderLayout.CENTER);
        inner.add(btns, BorderLayout.SOUTH);
        card.setBody(inner);

        pop.setBackground(Theme.current().surface);
        pop.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        pop.add(card);
        Theme.track(pop);
        if (anchor != null && anchor.isShowing()) {
            pop.show(anchor, x, y);
        }
        return pop;
    }
}
