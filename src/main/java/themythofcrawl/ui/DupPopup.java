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
import themythofcrawl.model.ApiTagCatalog;
import themythofcrawl.place.Decisions;
import themythofcrawl.ui.kit.Card;
import themythofcrawl.ui.kit.StructuredAdviceCard;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * Quick-view popup v2 for every copy (#1 Bản Gốc ... #N) of a duplicated API:
 * zero text overlap, separated Status &amp; Tag columns, StructuredAdviceCard, and one-click actions.
 */
final class DupPopup {
    private DupPopup() {
    }

    static JPopupMenu show(MainPanel ui, ApiGroup g, Component anchor, int x, int y) {
        List<ApiGroup> copies = new ArrayList<>(ui.engine.copiesOf(g.bookKey()));
        copies.sort(Comparator.comparingInt(ApiGroup::dupNo));
        ApiGroup origin = ui.engine.originOf(g.bookKey());

        RowsModel<ApiGroup> model = new RowsModel<>(List.of("Bản", "Vai trò", "Mã API", "Vị trí trong cây", "Trạng thái", "Nhãn", "STT"));
        List<Decisions.Row<ApiGroup>> rows = new ArrayList<>();
        for (ApiGroup c : copies) {
            boolean isOrig = origin != null && c.gid() == origin.gid();
            String role = isOrig ? "Bản Gốc (#1)" : "Bản trùng";
            String state = Decisions.copyStateShort(c);
            String tag = Decisions.copyTagLabel(c);
            String stt = c.target() != null ? "Excel #" + c.target().sttText()
                    : (c.copyStt() != null ? "STT " + c.copyStt() : "—");
            AuditLevel lvl = isOrig ? AuditLevel.OK : Decisions.copyStateLevel(c);
            rows.add(new Decisions.Row<>(c, lvl, List.of("#" + c.dupNo(), role,
                    Text.apiLabel(c.gid()), c.branch(), state, tag, stt)));
        }
        model.set(rows);
        JTable t = new ThemedTable(model);
        Ui.style(t);
        t.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        Ui.widths(t, 56, 112, 74, 220, 112, 98, 76);
        AuditRenderer r = new AuditRenderer(model, null, 4, null, 0, 1).numberCols(0);
        for (int i = 0; i < t.getColumnCount(); i++) {
            t.getColumnModel().getColumn(i).setCellRenderer(r);
        }
        int mine = copies.indexOf(g);
        if (mine >= 0) {
            t.setRowSelectionInterval(mine, mine);
        }
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(760, Math.min(54 + Theme.ROW_TABLE * Math.max(1, rows.size()), 210)));

        StructuredAdviceCard adviceCard = new StructuredAdviceCard();
        adviceCard.setAdvice(Decisions.advise(ui.engine, g));
        t.getSelectionModel().addListSelectionListener(ev -> {
            if (!ev.getValueIsAdjusting()) {
                int sel = t.getSelectedRow();
                ApiGroup target = sel >= 0 ? model.payload(sel) : g;
                adviceCard.setAdvice(Decisions.advise(ui.engine, target));
            }
        });

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
                    ui.flash("Đã giữ Bản Gốc (#1) và ẩn " + n + " bản trùng");
                    ui.tick(true);
                }
                case "promote" -> {
                    if (ui.engine.promoteToOrigin(target)) {
                        ui.flash("Đã đặt " + Text.apiLabel(target.gid()) + " làm Bản Gốc (#1)");
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
                case "refresh" -> {
                    boolean next = !target.hasTag(ApiTagCatalog.TAG_REFRESH);
                    ui.engine.toggleEndpointTag(g.bookKey(), ApiTagCatalog.TAG_REFRESH, next);
                    ui.flash(next ? "Đã gắn nhãn Tự tải lại" : "Đã gỡ nhãn Tự tải lại");
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

        String sub = copies.size() + " lần xuất hiện · Bản Gốc (#1) tại " + (origin != null ? origin.branch() : "đã xóa");
        Card card = new Card(g.endpoint(), sub,
                new JLabel(Icons.themed(theme -> Icons.lineageGraph(theme.dup.text(), 14))), theme -> theme.dup, true);
        card.setPadding(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        btns.setOpaque(false);
        btns.add(Ui.accent("Chỉ giữ Bản Gốc (#1) & Ẩn lặp",
                theme -> Icons.originCrown(theme.accent.onSolid(), 14), theme -> theme.accent,
                () -> act.accept("keepOriginOnly")));
        btns.add(Ui.button("Mở bảng đầy đủ...", Icons::lineageGraph, () -> act.accept("lineage")));
        btns.add(Ui.button("Đặt làm Bản Gốc", Icons::star, () -> act.accept("promote")));
        btns.add(Ui.button("Nhãn Tự tải lại", Icons::refresh, () -> act.accept("refresh")));
        btns.add(Ui.button("Mở API", theme -> Icons.search(theme.info.text()), () -> act.accept("open")));
        btns.add(Ui.button("Ẩn", theme -> Icons.hide(theme.muted), () -> act.accept("hide")));
        btns.add(Ui.button("Xóa", theme -> Icons.trash(theme.danger.text()), () -> act.accept("delete")));

        JPanel inner = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        inner.setOpaque(false);
        inner.add(adviceCard, BorderLayout.NORTH);
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
