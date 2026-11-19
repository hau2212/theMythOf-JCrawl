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
import javax.swing.JTextArea;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ApiTagCatalog;
import themythofcrawl.place.Decisions;
import themythofcrawl.ui.kit.Card;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * Quick-view popup for every copy (#1 GỐC ... #N) of a duplicated API, displaying SmartAdvice
 * and one-click actions plus full {@link LineageDialog} trigger.
 */
final class DupPopup {
    private DupPopup() {
    }

    static JPopupMenu show(MainPanel ui, ApiGroup g, Component anchor, int x, int y) {
        List<ApiGroup> copies = new ArrayList<>(ui.engine.copiesOf(g.bookKey()));
        copies.sort(Comparator.comparingInt(ApiGroup::dupNo));
        ApiGroup origin = ui.engine.originOf(g.bookKey());

        RowsModel<ApiGroup> model = new RowsModel<>(List.of("Bản", "Vai trò", "API", "Vị trí (Đường dẫn)", "Trạng thái", "STT / Excel"));
        List<Decisions.Row<ApiGroup>> rows = new ArrayList<>();
        for (ApiGroup c : copies) {
            boolean isOrig = origin != null && c.gid() == origin.gid();
            String role = isOrig ? "GỐC ĐỊNH HÌNH" : "Trùng #" + c.dupNo();
            String state = c.hidden() ? "Đang ẩn" : c.dupPending() ? "Chờ quyết định" : "Đang hiện";
            if (c.hasTag(ApiTagCatalog.TAG_REFRESH)) {
                state += " [REFRESH]";
            } else if (c.suspectedRefresh()) {
                state += " [? Refresh]";
            }
            String stt = c.target() != null ? "Excel #" + c.target().sttText()
                    : (c.copyStt() != null ? "STT " + c.copyStt() : "");
            AuditLevel lvl = isOrig ? AuditLevel.OK : (c.hidden() ? AuditLevel.PENDING : AuditLevel.INFO);
            rows.add(new Decisions.Row<>(c, lvl, List.of("#" + c.dupNo(), role,
                    Text.groupId(c.gid()), c.branch(), state, stt)));
        }
        model.set(rows);
        JTable t = new ThemedTable(model);
        Ui.style(t);
        Ui.widths(t, 44, 118, 56, 250, 128, 76);
        AuditRenderer r = new AuditRenderer(model, null, 4, null, 0, 1);
        for (int i = 0; i < t.getColumnCount(); i++) {
            t.getColumnModel().getColumn(i).setCellRenderer(r);
        }
        int mine = copies.indexOf(g);
        if (mine >= 0) {
            t.setRowSelectionInterval(mine, mine);
        }
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(690, Math.min(72 + 26 * Math.max(1, rows.size()), 220)));

        Decisions.SmartAdvice adv = Decisions.advise(ui.engine, g);
        JTextArea adviceBox = new JTextArea(adv.headline() + "\n" + adv.explanation());
        adviceBox.setEditable(false);
        adviceBox.setLineWrap(true);
        adviceBox.setWrapStyleWord(true);
        adviceBox.setOpaque(true);
        adviceBox.setBackground(Theme.current().surfaceAlt);
        adviceBox.setForeground(Theme.current().fg);
        adviceBox.setFont(Theme.current().smallFont);
        adviceBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.current().border, 1),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));

        JPopupMenu pop = new JPopupMenu();
        java.util.function.Consumer<String> act = kind -> {
            int v = t.getSelectedRow();
            ApiGroup target = v >= 0 ? model.payload(v) : g;
            pop.setVisible(false);
            switch (kind) {
                case "lineage" -> LineageDialog.show(ui, target);
                case "open" -> ui.jumpToGroup(target);
                case "keepOriginOnly" -> {
                    int n = ui.engine.keepOnlyOriginAndHideOthers(g.bookKey());
                    ui.flash("Đã giữ API GỐC #1 và ẩn " + n + " bản trùng");
                    ui.tick(true);
                }
                case "promote" -> {
                    if (ui.engine.promoteToOrigin(target)) {
                        ui.flash("Đã đặt " + Text.groupId(target.gid()) + " làm API GỐC #1");
                        ui.tick(true);
                    }
                }
                case "keep" -> {
                    ui.engine.keepDuplicates(List.of(target));
                    ui.tick(true);
                }
                case "hide" -> {
                    ui.engine.hide(List.of(target), "");
                    ui.tick(true);
                }
                case "show" -> {
                    ui.engine.show(List.of(target));
                    ui.tick(true);
                }
                case "refresh" -> {
                    boolean next = !target.hasTag(ApiTagCatalog.TAG_REFRESH);
                    ui.engine.toggleEndpointTag(g.bookKey(), ApiTagCatalog.TAG_REFRESH, next);
                    ui.flash(next ? "Đã gắn tag [REFRESH]" : "Đã gỡ tag [REFRESH]");
                    ui.tick(true);
                }
                case "delete" -> {
                    if (ui.confirmDialog("Xóa bản #" + target.dupNo() + "?")) {
                        ui.engine.remove(List.of(target), "xóa bản trùng");
                        ui.tick(true);
                    }
                }
                default -> ui.showAudit(2);
            }
        };

        String sub = copies.size() + " bản · Gốc #1 tại " + (origin != null ? origin.branch() : "(đã xóa)");
        Card card = new Card(g.endpoint(), sub,
                new JLabel(Icons.themed(theme -> Icons.dup(theme.dup.text()))), theme -> theme.dup, true);
        card.setPadding(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        btns.setOpaque(false);
        btns.add(Ui.accent("Lineage đầy đủ...", theme -> Icons.sliders(theme.accent.onSolid()), theme -> theme.accent,
                () -> act.accept("lineage")));
        btns.add(Ui.button("Chỉ giữ GỐC & Ẩn #2+", theme -> Icons.check(theme.success.text()), () -> act.accept("keepOriginOnly")));
        btns.add(Ui.button("Đặt làm GỐC", Icons::star, () -> act.accept("promote")));
        btns.add(Ui.button("Tag REFRESH", Icons::tag, () -> act.accept("refresh")));
        btns.add(Ui.button("Mở", theme -> Icons.search(theme.info.text()), () -> act.accept("open")));
        btns.add(Ui.button("Ẩn", theme -> Icons.hide(theme.muted), () -> act.accept("hide")));
        btns.add(Ui.button("Xóa", theme -> Icons.trash(theme.danger.text()), () -> act.accept("delete")));

        JPanel inner = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        inner.setOpaque(false);
        inner.add(sp, BorderLayout.NORTH);
        inner.add(adviceBox, BorderLayout.CENTER);
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
