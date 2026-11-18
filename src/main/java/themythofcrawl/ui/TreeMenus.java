package themythofcrawl.ui;

import java.awt.Component;
import java.util.List;

import javax.swing.JPopupMenu;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.RoleScopes;
import themythofcrawl.place.PlacementBook;

/** Right-click menu of a branch tree (the API tab's, the Cây tab's and the pop-out window's). */
final class TreeMenus {
    private TreeMenus() {
    }

    static void show(MainPanel ui, BranchTree tree, Component c, int x, int y) {
        Engine engine = ui.engine;
        Node n = tree.selectedNode();
        if (n == null) {
            return;
        }
        List<ApiGroup> many = tree.selectedGroups();
        JPopupMenu m = new JPopupMenu();
        switch (n.kind) {
            case ROOT, FOLDER -> {
                String prefix = n.kind == Node.Kind.FOLDER ? (String) n.payload : "";
                String title = prefix.isEmpty() ? "Tất cả" : prefix;
                m.add(Ui.item("Gửi (Replay) toàn bộ Nhánh \"" + title + "\"", t -> Icons.send(t.critical.text()), () -> ui.send(many)));
                m.add(Ui.item("Thêm Sub-folder con vào \"" + title + "\"...", Icons::plus,
                        () -> ui.addSubfolder(prefix.isEmpty() ? null : prefix)));
                if (n.kind == Node.Kind.FOLDER) {
                    m.add(Ui.item("Đặt \"" + title + "\" làm ĐÍCH cho request mới", Icons::arrowRight,
                            () -> ui.setDestination(prefix)));
                    m.addSeparator();
                    ui.placeItems(m, many);
                    m.addSeparator();
                    m.add(Ui.item("Đổi tên Nhánh \"" + title + "\" (Cập nhật cả cấp con)...", Icons::edit,
                            () -> ui.renameBranchDialog(prefix)));
                    m.add(Ui.item("Đẩy Nhánh \"" + title + "\" sang Burp Repeater", Icons::repeater, () -> ui.push(many)));
                    m.add(Ui.item("Đẩy / Gửi Nhánh \"" + title + "\" có chọn Role...", Icons::target,
                            () -> ui.pushDialog(false, many, "Nhánh \"" + title + "\"")));
                    m.add(Ui.item("Copy path nhánh \"" + title + "\"", t -> Icons.copy(t.muted), () -> {
                        Ui.copyToClipboard(prefix);
                        ui.flash("Đã copy path nhánh: " + prefix);
                    }));
                    m.add(Ui.item("Copy STT + Path của các API trong nhánh", t -> Icons.copy(t.muted), () -> ui.copy(many)));
                    m.add(Ui.item("Bật / Tắt Role cho nhánh \"" + title + "\"...", Icons::sliders,
                            () -> ui.roleScopeDialog(false, RoleScopes.Scope.BRANCH, List.of(prefix), null, null)));
                    m.addSeparator();
                    m.add(Ui.item("Xóa Nhánh \"" + title + "\"...", t -> Icons.trash(t.danger.text()),
                            () -> ui.deleteBranchDialog(prefix)));
                }
            }
            case APIS -> {
                String branch = (String) n.payload;
                String title = Hierarchy.leaf(branch);
                m.add(Ui.item("Gửi " + many.size() + " API trực tiếp của \"" + title + "\"", t -> Icons.send(t.critical.text()),
                        () -> ui.send(many)));
                m.add(Ui.item("Đẩy " + many.size() + " API sang Burp Repeater", Icons::repeater, () -> ui.push(many)));
                m.add(Ui.item("Đẩy / Gửi có chọn Role...", Icons::target,
                        () -> ui.pushDialog(false, many, "API của nhánh \"" + title + "\"")));
                m.add(Ui.item("Copy STT + Path của " + many.size() + " API", t -> Icons.copy(t.muted), () -> ui.copy(many)));
                m.add(Ui.item("Bật / Tắt Role cho các API này...", Icons::sliders,
                        () -> ui.roleScopeDialog(false, RoleScopes.Scope.API, null, many, null)));
                m.addSeparator();
                ui.placeItems(m, many);
                m.addSeparator();
                m.add(Ui.item("Thêm Sub-folder con vào \"" + title + "\"...", Icons::plus, () -> ui.addSubfolder(branch)));
            }
            case GROUP, ORIG, ROLE -> {
                ApiGroup g = BranchTree.groupOf(n);
                m.add(Ui.item(g.results().isEmpty() ? "Gửi API #" + g.gid() + " (API gốc - 0 role)"
                        : "Gửi API #" + g.gid() + " (" + g.results().size() + " Roles)", t -> Icons.send(t.critical.text()),
                        () -> ui.send(many.isEmpty() ? List.of(g) : many)));
                m.add(Ui.item("Đẩy API #" + g.gid() + " sang Burp Repeater (Gọn)", Icons::repeater, () -> ui.push(g)));
                String label = engine.copyLabel(g, false);
                m.add(Ui.item("Copy STT + Path   (" + (label.length() > 60 ? label.substring(0, 60) : label) + ")",
                        t -> Icons.copy(t.muted), () -> ui.copy(many.size() > 1 ? many : List.of(g))));
                m.add(Ui.item("Đẩy / Gửi API #" + g.gid() + " có chọn Role...", Icons::target,
                        () -> ui.pushDialog(false, List.of(g), Text.apiLabel(g.gid()))));
                m.add(Ui.item("Bật / Tắt Role cho API #" + g.gid() + "...", Icons::sliders,
                        () -> ui.roleScopeDialog(false, RoleScopes.Scope.API, null, many.size() > 1 ? many : List.of(g), null)));
                m.addSeparator();
                ui.placeItems(m, many.isEmpty() ? List.of(g) : many);
            }
            case PLACE -> {
                Object[] p = (Object[]) n.payload;
                ApiGroup g = (ApiGroup) p[0];
                String place = (String) p[1];
                if ("old".equals(p[2])) {
                    m.add(Ui.item("Giữ API #" + g.gid() + " ở vị trí cũ: " + place,
                            t -> Icons.check(t.success.text()), () -> engine.confirm(List.of(g), "giữ ở vị trí cũ")));
                } else {
                    m.add(Ui.item("Chuyển API #" + g.gid() + " sang nơi vừa gặp: " + place, Icons::move,
                            () -> engine.moveToPath(List.of(g), place, "chọn nơi vừa gặp")));
                }
                m.add(Ui.item("Chuyển sang đường dẫn khác...", Icons::move, () -> ui.moveDialog(List.of(g))));
                m.add(Ui.item("Lịch sử vị trí của API này", Icons::compass, () -> ui.showPlaceOf(g.bookKey())));
            }
            case GHOSTS -> m.add(Ui.item("Xem các API đã xóa trong tab Audit > Giữ chỗ", Icons::compass, () -> {
                ui.showPlaceOf(null);
                ui.audit.placeView(2);
            }));
            case GHOST -> {
                PlacementBook.Record r = (PlacementBook.Record) n.payload;
                m.add(Ui.item("Sửa vị trí giữ chỗ (" + (r.place == null ? "-" : r.place) + ")...", Icons::edit,
                        () -> ui.audit.editPlace(r)));
                m.add(Ui.item("Cho phép bắt lại ngay (xóa khóa chống trùng)", Icons::refresh,
                        () -> ui.flash("Đã xóa " + engine.allowRecapture(r.key) + " khóa chống trùng")));
                m.add(Ui.item("Quên giữ chỗ (lần sau bắt lại sẽ vào đích hiện tại)", Icons::error, () -> engine.forgetPlace(r.key)));
                m.add(Ui.item("Lịch sử trong tab Audit", Icons::compass, () -> ui.showPlaceOf(r.key)));
            }
            default -> {
                return;
            }
        }
        m.show(c, x, y);
    }
}
