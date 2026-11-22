package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.table.DefaultTableModel;

import themythofcrawl.model.QaItem;
import themythofcrawl.store.FamilyReport;
import themythofcrawl.store.FileStorage;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.kit.ThemedTable;

/**
 * V_10.4 Excel > Tài khoản: one website crawled account by account, highest first. Each account gets its own
 * workspace ("workspace con") that starts with the same branch tree and Excel list as the account above it, so
 * the tree stays readable. This page shows which account reached which API, switches between the accounts and
 * exports the combined report.
 */
final class FamilyTab {
    final JPanel panel = new JPanel(new BorderLayout(0, 4));
    private final MainPanel ui;
    private final DefaultTableModel model = new DefaultTableModel(0, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    final ThemedTable table = new ThemedTable(model);
    private final JLabel info = Ui.muted(" ");
    private final JCheckBox onlyDiff = new JCheckBox("Chỉ API mà không phải tài khoản nào cũng gọi được", false);
    private FamilyReport report;

    FamilyTab(MainPanel ui) {
        this.ui = ui;
        Ui.style(table);
        onlyDiff.addActionListener(Ui.safe(e -> fill()));
        JButton[] switchRef = new JButton[1];
        JButton switchBtn = Ui.dropdown("Mở tài khoản", t -> Icons.withChevron(Icons.folder(t.fg), t.fg),
                "Chuyển sang workspace của tài khoản khác trong cùng họ", () -> switchMenu().show(switchRef[0], 0, switchRef[0].getHeight()));
        switchRef[0] = switchBtn;
        JPanel bar = new JPanel(new BorderLayout());
        bar.add(Ui.flow(FlowLayout.LEFT,
                Ui.accent("Tài khoản tiếp theo...", t -> Icons.plus(t.accent.onSolid()), t -> t.accent, this::createChild),
                switchBtn, onlyDiff), BorderLayout.WEST);
        bar.add(Ui.flow(FlowLayout.RIGHT, Ui.button("Làm mới", Icons::refresh, "Đọc lại mọi workspace của họ", this::reload),
                Ui.button("Xuất báo cáo tổng hợp...", t -> Icons.excel(t.fg, 13),
                        "Tổng quan, ma trận tài khoản, API từng tài khoản, Q&A có ảnh (.xlsx)", this::export)), BorderLayout.EAST);
        JPanel north = new JPanel(new BorderLayout(0, 2));
        north.add(bar, BorderLayout.NORTH);
        north.add(info, BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);
        panel.add(MainPanel.scroll(table), BorderLayout.CENTER);
        panel.add(Ui.muted("Cách dùng: crawl tài khoản cao nhất trước -> Tài khoản tiếp theo... -> workspace mới cùng cây nhánh và"
                + " file Excel, Cây chỉ chứa API của tài khoản đó; chip 'Có ở <tài khoản trên>' / 'Mới' cho biết so với tài khoản trên."),
                BorderLayout.SOUTH);
    }

    private Path current() {
        return Path.of(ui.engine.workspaceLocation());
    }

    void reload() {
        try {
            ui.engine.flush();
            report = FamilyReport.load(current());
        } catch (RuntimeException e) {
            report = null;
        }
        fill();
    }

    private void fill() {
        model.setRowCount(0);
        if (report == null || report.members().isEmpty()) {
            model.setColumnIdentifiers(new Object[] {"Workspace này chưa lưu trên đĩa"});
            info.setText(" ");
            return;
        }
        List<Object> cols = new ArrayList<>(List.of("STT Excel", "Method", "Path"));
        StringBuilder sb = new StringBuilder("Họ workspace: ");
        for (FamilyReport.Member m : report.members()) {
            cols.add(m.account());
            sb.append(m.account()).append(" (").append(m.apis().size()).append(" API) ");
            if (m.info().parent() != null) {
                sb.append("<- ");
            }
        }
        cols.add("Số tài khoản");
        model.setColumnIdentifiers(cols.toArray());
        int n = report.members().size();
        for (FamilyReport.Row r : report.rows()) {
            if (onlyDiff.isSelected() && r.reached() == n) {
                continue;
            }
            List<Object> row = new ArrayList<>(List.of(r.stt() == null ? "" : r.stt(), r.method(), r.path()));
            for (FamilyReport.Api a : r.cells()) {
                row.add(a == null ? "-" : "Có" + (a.status() != null ? " " + a.status() : ""));
            }
            row.add(r.reached() + "/" + n);
            model.addRow(row.toArray());
        }
        info.setText(sb.toString().trim() + " · " + report.rows().size() + " API khác nhau");
    }

    void createChild() {
        String name = ui.ask("Tên tài khoản tiếp theo (vd user, staff, guest):", "user");
        if (name == null || name.isBlank()) {
            return;
        }
        ui.engine.flush();
        FileStorage child = ui.workspaces.createChild(current(), name.trim(), ui.engine.templateStateJson());
        ui.openWorkspaceFromFamily(child);
        ui.flash("Đã tạo workspace cho tài khoản " + name.trim() + " (cùng cây nhánh và file Excel, chưa có API)");
    }

    private JPopupMenu switchMenu() {
        JPopupMenu m = new JPopupMenu();
        for (Workspaces.Info i : Workspaces.family(current())) {
            boolean me = i.dir().toAbsolutePath().normalize().equals(current().toAbsolutePath().normalize());
            m.add(Ui.item(FamilyReport.accountOf(i) + (me ? "  (đang mở)" : "") + "  ·  " + i.apis() + " API", Icons::folder, !me,
                    () -> ui.openWorkspaceFromFamily(ui.workspaces.open(i.dir()))));
        }
        return m;
    }

    private void export() {
        if (report == null) {
            reload();
        }
        if (report == null) {
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("jcrawl-bao-cao-tai-khoan.xlsx"));
        if (fc.showSaveDialog(Ui.ownerWindow(panel)) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) {
            return;
        }
        try {
            Files.write(fc.getSelectedFile().toPath(), report.workbook(true, List.of(QaItem.Kind.QA, QaItem.Kind.INFO), null));
            ui.flash("Đã xuất " + fc.getSelectedFile().getName());
        } catch (Exception ex) {
            ui.info("Không xuất được: " + ex.getMessage());
        }
    }

    /** Endpoint keys of the parent workspace (to mark APIs "Có ở <tài khoản trên>" / "Mới" in the Cây). */
    static void applyReference(MainPanel ui) {
        try {
            Path me = Path.of(ui.engine.workspaceLocation());
            Workspaces.Info info = Workspaces.info(me);
            if (info == null || info.parent() == null || Workspaces.info(info.parent()) == null) {
                ui.engine.setReference(null, Set.of());
                return;
            }
            Workspaces.Info parent = Workspaces.info(info.parent());
            FamilyReport rep = FamilyReport.load(info.parent());
            Set<String> keys = new HashSet<>();
            for (FamilyReport.Member m : rep.members()) {
                if (m.info().dir().toAbsolutePath().normalize().equals(parent.dir().toAbsolutePath().normalize())) {
                    keys.addAll(m.byKey().keySet());
                }
            }
            ui.engine.setReference(FamilyReport.accountOf(parent), keys);
        } catch (RuntimeException e) {
            ui.engine.setReference(null, Set.of());
        }
    }
}
