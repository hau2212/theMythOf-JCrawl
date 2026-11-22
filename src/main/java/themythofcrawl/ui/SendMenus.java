package themythofcrawl.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.Audit;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.RoleResult;

/**
 * V_8.2: one "Gửi lại" submenu used by the Cây and API tabs, so every send scope is one click away
 * without making the main menu longer: everything / only the crawl account / only roles / one role /
 * only flagged rows / choose roles, plus "Mỗi vai trò 1 bản ghi" for DELETE / PUT sent to many roles.
 */
final class SendMenus {
    private SendMenus() {
    }

    static JMenu build(MainPanel ui, List<ApiGroup> gs, RoleResult nodeRole) {
        Engine engine = ui.engine;
        JMenu m = new JMenu("Gửi lại");
        m.setIcon(Icons.themed(t -> Icons.send(t.critical.text(), 13)));
        if (gs == null || gs.isEmpty()) {
            m.add(Ui.item("(Chưa chọn API)", false, () -> { }));
            return m;
        }
        int roles = 0;
        int flagged = 0;
        boolean anyAction = false;
        for (ApiGroup g : gs) {
            roles += g.results().size();
            for (RoleResult r : g.results()) {
                if (Audit.level(g, r).flagged()) {
                    flagged++;
                }
            }
            anyAction |= ActionEngine.isActionMethod(g.method());
        }
        String apis = gs.size() == 1 ? Text.apiLabel(gs.get(0).gid()) : gs.size() + " API";

        JMenuItem all = Ui.item("Tất cả: tài khoản crawl + " + roles + " vai trò", t -> Icons.sendAll(t.critical.text()),
                () -> send(ui, gs, g -> engine.defaultTargets(g, true), "tài khoản crawl + vai trò"));
        all.setToolTipText("Gửi " + apis + " bằng tài khoản crawl rồi tất cả vai trò đang bật");
        m.add(all);
        m.add(Ui.item("Chỉ vai trò (" + roles + ")", t -> Icons.send(t.fg, 13),
                () -> send(ui, gs, g -> engine.defaultTargets(g, false), "vai trò")));
        m.add(Ui.item("Chỉ tài khoản crawl", t -> Icons.pin(t.orig.text()),
                () -> send(ui, gs, g -> List.of(g.origResult()), "tài khoản crawl")));
        if (nodeRole != null && !nodeRole.original() && gs.size() == 1) {
            m.add(Ui.item("Chỉ " + nodeRole.roleName(), t -> Icons.send(t.accent.text(), 13),
                    () -> send(ui, gs, g -> List.of(nodeRole), nodeRole.roleName())));
        }
        m.add(Ui.item("Chỉ dòng bị cờ (" + flagged + ")", t -> Icons.alertMark(t.danger.text(), 13), flagged > 0,
                () -> send(ui, gs, g -> g.results().stream().filter(r -> Audit.level(g, r).flagged()).toList(), "dòng bị cờ")));
        m.add(Ui.item("Chọn vai trò...", Icons::target, () -> ui.pushDialog(true, new ArrayList<>(gs), apis)));

        if (anyAction) {
            m.addSeparator();
            ApiGroup first = gs.get(0);
            Engine.PerRolePlan plan = engine.perRolePlan(first, first.results().size() + 1);
            JMenuItem each = Ui.item("Mỗi vai trò 1 bản ghi riêng" + (plan.hasId() && !plan.enough() ? "  (thiếu ID)" : ""),
                    t -> Icons.strategyChain(t.accent.text(), 14), () -> {
                        int n = 0;
                        for (ApiGroup g : gs) {
                            if (ActionEngine.isActionMethod(g.method())) {
                                engine.sendEachRoleOwnObject(g, engine.defaultTargets(g, true));
                                n++;
                            }
                        }
                        ui.flash("Mỗi vai trò 1 bản ghi: đang chuẩn bị ID cho " + n + " API thao tác");
                    });
            each.setToolTipText("DELETE / PUT gửi cho nhiều vai trò: mỗi vai trò nhận 1 bản ghi khác nhau (tạo trước hoặc lấy"
                    + " từ Kho ID), tài khoản crawl gửi cuối để đối chứng. " + (gs.size() == 1 ? plan.sentence() : ""));
            m.add(each);
        }
        return m;
    }

    interface Targets {
        Collection<RoleResult> of(ApiGroup g);
    }

    private static void send(MainPanel ui, List<ApiGroup> gs, Targets targets, String what) {
        int rows = 0;
        for (ApiGroup g : gs) {
            Collection<RoleResult> t = targets.of(g);
            if (!t.isEmpty()) {
                ui.engine.replay(g, t);
                rows += t.size();
            }
        }
        ui.flash(rows == 0 ? "Không có dòng nào để gửi (" + what + ")"
                : "Xếp hàng gửi " + rows + " request (" + what + ")");
    }
}
