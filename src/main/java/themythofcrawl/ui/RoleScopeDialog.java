package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.BranchRule;
import themythofcrawl.model.Role;
import themythofcrawl.model.RoleScopes;

/**
 * 'Bật / Tắt Role theo phạm vi': Tắt / Bật, which roles, and where - every API (the roles' own Enabled
 * flag), chosen branches (sub-branches included, also APIs captured later) or chosen APIs. Lists the
 * exceptions already set (removable) and previews the change before applying.
 */
final class RoleScopeDialog {
    record Result(List<String> roles, boolean enable, RoleScopes.Scope scope, List<?> targets, boolean applyExisting) {
    }

    private static final String[] SCOPE_LABELS = {"🌐 Toàn bộ (cờ Enabled của role - mọi API)",
        "🌿 Chỉ các nhánh chọn bên dưới (gồm nhánh con, cả API bắt sau này)", "📌 Chỉ các API gốc chọn bên dưới"};

    private final Engine engine;
    private final List<ApiGroup> groups;
    private final JDialog dialog;
    private final JRadioButton on = new JRadioButton("✅ BẬT role");
    private final JRadioButton off = new JRadioButton("⬜ TẮT role");
    private final JRadioButton[] scopes = new JRadioButton[3];
    private final CheckList<String> roleList;
    private final CheckList<String> branchList;
    private final CheckList<Integer> apiList;
    private final JTabbedPane targets = new JTabbedPane();
    private final JPanel rulesBox = new JPanel();
    private final JCheckBox existing = new JCheckBox("Cập nhật luôn các API đã bắt (dòng role của role đang bật giữ nguyên kết quả "
            + "đã nhận). Bỏ chọn = chỉ áp dụng cho API bắt sau này.", true);
    private final JLabel summary = new JLabel(" ");
    private final JButton ok = new JButton("Áp dụng");
    private Result result;

    static Result show(Component parent, Engine engine, boolean enable, RoleScopes.Scope scope, List<String> branches,
                       List<ApiGroup> apis, List<String> roleNames) {
        return new RoleScopeDialog(parent, engine, enable, scope, branches, apis, roleNames).run();
    }

    private RoleScopeDialog(Component parent, Engine engine, boolean enable, RoleScopes.Scope scope, List<String> branches,
                            List<ApiGroup> apis, List<String> roleNames) {
        this.engine = engine;
        this.groups = new ArrayList<>(engine.groups());
        groups.sort(Comparator.comparingInt(ApiGroup::gid));
        java.awt.Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        dialog = new JDialog(owner, "Bật / Tắt Role theo phạm vi", JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(6, 6));

        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setBorder(BorderFactory.createEmptyBorder(8, 10, 2, 10));
        ButtonGroup g1 = new ButtonGroup();
        g1.add(off);
        g1.add(on);
        (enable ? on : off).setSelected(true);
        on.addActionListener(e -> update());
        off.addActionListener(e -> update());
        JPanel acts = Ui.flow(FlowLayout.LEFT, new JLabel("Thao tác:"), off, on);
        acts.setAlignmentX(0f);
        head.add(acts);
        ButtonGroup g2 = new ButtonGroup();
        JPanel sc = Ui.flow(FlowLayout.LEFT, new JLabel("Phạm vi:"));
        for (int i = 0; i < 3; i++) {
            scopes[i] = new JRadioButton(SCOPE_LABELS[i], RoleScopes.Scope.values()[i] == (scope == null ? RoleScopes.Scope.GLOBAL : scope));
            g2.add(scopes[i]);
            scopes[i].addActionListener(e -> onScope());
            sc.add(scopes[i]);
        }
        sc.setAlignmentX(0f);
        head.add(sc);
        dialog.add(head, BorderLayout.NORTH);

        List<CheckList.Item<String>> roleItems = new ArrayList<>();
        List<String> allNames = new ArrayList<>();
        for (Role r : engine.roles().all()) {
            roleItems.add(new CheckList.Item<>(r.name(), r.name() + "   " + (r.enabled() ? "(đang bật)" : "(đang tắt)")));
            allNames.add(r.name());
        }
        roleList = new CheckList<>(roleItems, roleNames != null ? roleNames : allNames, this::update, "Lọc role theo tên");
        roleList.setBorder(BorderFactory.createTitledBorder("1. Role áp dụng"));
        roleList.setPreferredSize(new Dimension(250, 300));

        List<CheckList.Item<String>> branchItems = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (Map.Entry<String, Integer> e : RoleScopes.branchCounts(groups)) {
            branchItems.add(new CheckList.Item<>(e.getKey(), e.getKey() + "   (" + e.getValue() + " API)"));
            known.add(e.getKey().toLowerCase(java.util.Locale.ROOT));
        }
        for (BranchRule r : engine.branchRules()) {
            if (!known.contains(r.prefix().toLowerCase(java.util.Locale.ROOT))) {
                branchItems.add(new CheckList.Item<>(r.prefix(), r.prefix() + "   (chưa có API)"));
            }
        }
        branchList = new CheckList<>(branchItems, branches == null ? List.of() : branches, this::update, "Lọc nhánh, vd: contact");
        List<CheckList.Item<Integer>> apiItems = new ArrayList<>();
        for (ApiGroup g : groups) {
            apiItems.add(new CheckList.Item<>(g.gid(), "#" + g.gid() + "  " + g.method() + " " + Text.stripQuery(g.path())
                    + "   ·   " + g.branch()));
        }
        apiList = new CheckList<>(apiItems, apis == null ? List.of() : apis.stream().map(ApiGroup::gid).toList(), this::update,
                "Lọc API theo #, method, path, nhánh");
        targets.addTab("Nhánh", branchList);
        targets.addTab("API gốc", apiList);
        targets.addTab("Ngoại lệ đã đặt", rulesPanel());
        targets.setBorder(BorderFactory.createTitledBorder("2. Nhánh / API (khi phạm vi không phải Toàn bộ)"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, roleList, targets);
        split.setResizeWeight(0.3);
        dialog.add(split, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 10, 8, 10));
        existing.addActionListener(e -> update());
        bottom.add(existing, BorderLayout.NORTH);
        summary.setFont(summary.getFont().deriveFont(Font.BOLD));
        bottom.add(summary, BorderLayout.CENTER);
        ok.setFont(ok.getFont().deriveFont(Font.BOLD));
        ok.addActionListener(e -> {
            result = choice();
            dialog.dispose();
        });
        bottom.add(Ui.flow(FlowLayout.RIGHT, ok, Ui.button("Đóng", dialog::dispose)), BorderLayout.EAST);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setSize(new Dimension(1040, 620));
        dialog.setLocationRelativeTo(owner);
        dialog.getRootPane().setDefaultButton(ok);
        onScope();
    }

    private Result run() {
        dialog.setVisible(true);
        return result;
    }

    private JPanel rulesPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        rulesBox.setLayout(new BoxLayout(rulesBox, BoxLayout.Y_AXIS));
        p.add(MainPanel.scroll(rulesBox), BorderLayout.CENTER);
        JLabel hint = new JLabel(" Thứ tự ưu tiên: cờ Enabled toàn bộ  →  nhánh (nhánh sâu hơn thắng)  →  từng API.");
        hint.setForeground(Palette.ui().muted);
        p.add(hint, BorderLayout.SOUTH);
        fillRules();
        return p;
    }

    private static String onOff(Map<String, Boolean> m) {
        List<String> offs = m.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).sorted().toList();
        List<String> ons = m.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).sorted().toList();
        return (offs.isEmpty() ? "" : "TẮT: " + String.join(", ", offs)) + (ons.isEmpty() ? "" : "   BẬT: " + String.join(", ", ons));
    }

    private void fillRules() {
        rulesBox.removeAll();
        List<BranchRule> rules = new ArrayList<>(engine.branchRules());
        rules.sort(Comparator.comparing(r -> r.prefix().toLowerCase(java.util.Locale.ROOT)));
        List<ApiGroup> over = groups.stream().filter(g -> !g.roleOverrides().isEmpty()).toList();
        if (rules.isEmpty() && over.isEmpty()) {
            rulesBox.add(new JLabel("   Chưa có ngoại lệ - mọi API dùng cờ Enabled của từng role."));
        }
        for (BranchRule r : rules) {
            JPanel row = Ui.flow(FlowLayout.LEFT, Ui.compact(Ui.button("✖", "Xóa ngoại lệ của nhánh này", () -> {
                engine.removeBranchRule(r.prefix(), existing.isSelected());
                fillRules();
                update();
            })), new JLabel("🌿 " + r.prefix() + "   " + onOff(r.roles())));
            row.setAlignmentX(0f);
            rulesBox.add(row);
        }
        for (ApiGroup g : over) {
            JPanel row = Ui.flow(FlowLayout.LEFT, Ui.compact(Ui.button("✖", "Xóa ngoại lệ của API này", () -> {
                if (existing.isSelected()) {
                    engine.clearOverrides(g);
                } else {
                    g.clearRoleOverrides();
                }
                fillRules();
                update();
            })), new JLabel("📌 #" + g.gid() + " " + g.method() + " " + Text.stripQuery(g.path()) + "   " + onOff(g.roleOverrides())));
            row.setAlignmentX(0f);
            rulesBox.add(row);
        }
        rulesBox.revalidate();
        rulesBox.repaint();
    }

    private RoleScopes.Scope scope() {
        for (int i = 0; i < 3; i++) {
            if (scopes[i].isSelected()) {
                return RoleScopes.Scope.values()[i];
            }
        }
        return RoleScopes.Scope.GLOBAL;
    }

    private void onScope() {
        RoleScopes.Scope s = scope();
        if (s == RoleScopes.Scope.BRANCH) {
            targets.setSelectedIndex(0);
        } else if (s == RoleScopes.Scope.API) {
            targets.setSelectedIndex(1);
        }
        update();
    }

    private Result choice() {
        RoleScopes.Scope s = scope();
        List<?> t;
        if (s == RoleScopes.Scope.BRANCH) {
            t = branchList.checked();
        } else if (s == RoleScopes.Scope.API) {
            Set<Integer> want = new LinkedHashSet<>(apiList.checked());
            t = groups.stream().filter(g -> want.contains(g.gid())).toList();
        } else {
            t = List.of();
        }
        return new Result(roleList.checked(), on.isSelected(), s, t, existing.isSelected() || s == RoleScopes.Scope.API);
    }

    private void update() {
        Result c = choice();
        String verb = c.enable() ? "BẬT" : "TẮT";
        boolean good;
        String msg;
        if (c.roles().isEmpty()) {
            msg = "Chọn ít nhất 1 role.";
            good = false;
        } else if (c.scope() != RoleScopes.Scope.GLOBAL && c.targets().isEmpty()) {
            msg = "Chọn ít nhất 1 " + (c.scope() == RoleScopes.Scope.BRANCH ? "nhánh" : "API") + " ở khung bên phải.";
            good = false;
        } else {
            good = true;
            String where = switch (c.scope()) {
                case GLOBAL -> "mọi API";
                case BRANCH -> c.targets().size() + " nhánh";
                case API -> c.targets().size() + " API";
            };
            msg = verb + " " + c.roles().size() + " role cho " + where;
            if (c.applyExisting()) {
                RoleScopes.Preview p = RoleScopes.preview(engine.roles().all(), groups, engine.branchRules(), c.roles(), c.enable(),
                        c.scope(), (Collection<?>) c.targets());
                msg += "  ·  " + p.changed() + " API đã bắt thay đổi (+" + p.added() + " / -" + p.removed() + " dòng role)";
            } else {
                msg += "  ·  chỉ áp dụng cho API bắt sau này";
            }
        }
        summary.setText(msg);
        ok.setEnabled(good);
        existing.setEnabled(c.scope() != RoleScopes.Scope.API);
    }
}
