package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import themythofcrawl.core.Text;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.project.AccStatus;
import themythofcrawl.project.Columns;
import themythofcrawl.project.ProjectConfig;
import themythofcrawl.project.ProjectData;
import themythofcrawl.project.ProjectStore;
import themythofcrawl.project.ProjectSync;
import themythofcrawl.project.ProjectWorkbook;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * V_11.4 Excel > Dự án: the project list x every account (this person's and the team's), filters, hand marks, the
 * team sync light and every project tool (Làm chung, Cột, Xuất báo cáo, Gửi khách hàng, Nhập phản hồi, Sổ dự án,
 * Sao lưu). Replaces V_10.4 "Tài khoản".
 */
final class ProjectTab {
    final JPanel panel = new JPanel(new BorderLayout(0, 4));
    private final MainPanel ui;
    private final DefaultTableModel model = new DefaultTableModel(0, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    final ThemedTable table = new ThemedTable(model);
    private final JComboBox<String> filter = new JComboBox<>();
    private final JComboBox<String> person = new JComboBox<>(new String[] {"Mọi người làm"});
    private final JTextField search = new JTextField(16);
    private final JLabel stats = Ui.muted(" ");
    private final JLabel me = Ui.bold(" ");
    private final JButton syncPill;
    private final JLabel conflicts = new JLabel(" ");
    private final List<ProjectData.Row> shown = new ArrayList<>();
    private List<String> cols = List.of();
    private ProjectData data;
    private static final String[] FILTERS = {"Mọi API", "Trong danh sách", "Còn thiếu của tôi", "Chênh lệch giữa tài khoản",
        "Ngoài danh sách / Chỉ 1 số tài khoản có", "Có Q&A", "Có Info", "Nghi BAC", "Xung đột", "Đã loại khỏi danh sách",
        "Có lỗi đã đánh dấu"};

    ProjectTab(MainPanel ui) {
        this.ui = ui;
        Ui.style(table);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        for (String f : FILTERS) {
            filter.addItem(f);
        }
        filter.addActionListener(Ui.safe(e -> fill()));
        person.addActionListener(Ui.safe(e -> {
            if (!fillingPeople) {
                fill();
            }
        }));
        person.setToolTipText("Chỉ API người này đã làm (crawl, đặt tay, hỏi Q&A)");
        search.putClientProperty("JTextField.placeholderText", "Tìm path, nhánh, STT, ghi chú...");
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { fill(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { fill(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { fill(); }
        });
        syncPill = Ui.button("Làm chung: tắt", t -> Icons.refresh(t.fg), "Trạng thái đồng bộ với nhóm. Bấm để mở Làm chung",
                () -> TeamWizard.open(ui));
        JButton[] accRef = new JButton[1];
        JButton accounts = Ui.dropdown("Tài khoản web", t -> Icons.withChevron(Icons.folder(t.fg), t.fg),
                "Danh sách tài khoản web dùng chung, tài khoản web tiếp theo, mở workspace khác",
                () -> accountMenu().show(accRef[0], 0, accRef[0].getHeight()));
        accRef[0] = accounts;
        JButton[] repRef = new JButton[1];
        JButton reports = Ui.dropdown("Báo cáo", t -> Icons.withChevron(Icons.excel(t.fg, 13), t.fg),
                "Xuất báo cáo, gửi khách hàng, nhập phản hồi, mở Excel của tôi", () -> reportMenu().show(repRef[0], 0, repRef[0].getHeight()));
        repRef[0] = reports;

        JPanel row1 = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new BorderLayout(0, 2));
        left.add(Ui.flow(FlowLayout.LEFT, me), BorderLayout.NORTH);
        left.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Lọc:"), filter, person, search), BorderLayout.SOUTH);
        row1.add(left, BorderLayout.WEST);
        row1.add(Ui.flow(FlowLayout.RIGHT, syncPill, Ui.button("Làm mới", Icons::refresh, "Đọc lại workspace và dữ liệu nhóm", this::refreshNow)),
                BorderLayout.EAST);
        JPanel row2 = Ui.flow(FlowLayout.LEFT,
                Ui.accent("Làm chung...", t -> Icons.target(t.accent.onSolid()), t -> t.accent, () -> TeamWizard.open(ui)),
                accounts, reports,
                Ui.button("Cột...", Icons::sliders, "Chọn, sắp xếp, đổi tên cột (mẫu cột)", () -> ProjectDialogs.columns(ui, this::refreshNow)),
                Ui.button("Sổ dự án...", t -> Icons.excel(t.fg, 13), "Các sheet của báo cáo / Google Sheet: thêm, xóa, lọc, thống kê",
                        () -> ProjectDialogs.sheets(ui)),
                Ui.button("Sao lưu...", Icons::save, "Bản lưu theo giờ / ngày, khôi phục, dựng lại Google Sheet", () -> ProjectDialogs.backups(ui)));
        conflicts.setForeground(Theme.current().warning.text());
        JPanel north = new JPanel();
        north.setLayout(new javax.swing.BoxLayout(north, javax.swing.BoxLayout.Y_AXIS));
        for (javax.swing.JComponent c : List.<javax.swing.JComponent>of(row1, row2, stats)) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            north.add(c);
        }
        stats.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 6));
        JPanel top = new JPanel(new BorderLayout());
        top.add(north, BorderLayout.CENTER);
        top.add(conflicts, BorderLayout.SOUTH);
        panel.add(top, BorderLayout.NORTH);
        panel.add(MainPanel.scroll(table), BorderLayout.CENTER);
        panel.add(Ui.muted("Mỗi dòng: 1 API của danh sách chuẩn. Mỗi cột tài khoản: Done (gọi được) · Denied (bị chặn) · Error · Missing (chưa gọi)"
                + " · Doing · OOS. Chuột phải ô tài khoản để đặt tay (đặt tay luôn thắng). Nhấp đúp: mở API trên Cây."), BorderLayout.SOUTH);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });
        Ui.onDoubleClick(table, this::openSelected);
        ui.project.addListener(this::fillFromSync);
    }

    // ------------------------------------------------------------------ data
    void reload() {
        refreshNow();
    }

    void refreshNow() {
        ProjectService ps = ui.project;
        if (!ps.ready()) {
            ps.workspaceChanged();
        }
        data = ps.buildNow();
        fill();
        ps.syncSoon();
    }

    private void fillFromSync() {
        ProjectSync s = ui.project.sync();
        if (s != null && s.data() != null) {
            data = s.data();
            if (panel.isShowing()) {
                fill();
            } else {
                status();
            }
        }
    }

    private String spec() {
        String my = ui.project.myColumn();
        return switch (filter.getSelectedIndex()) {
            case 1 -> "list";
            case 2 -> "missing:" + my;
            case 3 -> "diff";
            case 4 -> "only";
            case 5 -> "qa";
            case 6 -> "info";
            case 7 -> "bac";
            case 8 -> "conflict";
            case 9 -> "excluded";
            case 10 -> "bug";
            default -> "all";
        };
    }

    private void fill() {
        status();
        if (data == null) {
            model.setRowCount(0);
            return;
        }
        ProjectConfig cfg = data.config();
        ProjectConfig.Profile prof = cfg.profile(cfg.activeProfile);
        List<String> letters = data.letters();
        List<String> newCols = ProjectWorkbook.columns(prof, letters);
        boolean structure = !newCols.equals(cols);
        cols = newCols;
        if (structure) {
            List<Object> heads = new ArrayList<>();
            for (String c : cols) {
                heads.add(ProjectWorkbook.header(data, prof, c));
            }
            model.setColumnIdentifiers(heads.toArray());
            for (int i = 0; i < cols.size(); i++) {
                String c = cols.get(i);
                int w = c.startsWith(Columns.ACC_PREFIX) ? 150 : switch (c) {
                    case Columns.STT -> 46;
                    case Columns.PATH -> 320;
                    case Columns.METHOD -> 64;
                    case Columns.BRANCH -> 170;
                    case Columns.NOTE, Columns.BAC -> 220;
                    case Columns.QA, Columns.INFO, Columns.BUGS, Columns.ROLES -> 190;
                    default -> 110;
                };
                table.getColumnModel().getColumn(i).setPreferredWidth(Theme.current().scaled(w));
            }
        }
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        String sp = spec();
        refreshPeople();
        String who = person.getSelectedIndex() > 0 ? (String) person.getSelectedItem() : null;
        shown.clear();
        model.setRowCount(0);
        for (ProjectData.Row r : data.rows()) {
            if (!data.matches(r, sp) || who != null && !data.matches(r, "person:" + who)) {
                continue;
            }
            if (!q.isEmpty() && !(r.path + " " + r.branch + " " + r.stt + " " + r.note + " " + r.method).toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            shown.add(r);
            List<Object> row = new ArrayList<>();
            for (String c : cols) {
                row.add(ProjectWorkbook.value(data, r, c, letters));
            }
            model.addRow(row.toArray());
        }
    }

    private boolean fillingPeople;

    private void refreshPeople() {
        if (data == null || fillingPeople) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (ProjectData.PersonStats p : data.people()) {
            names.add(p.person());
        }
        List<String> cur = new ArrayList<>();
        for (int i = 1; i < person.getItemCount(); i++) {
            cur.add(person.getItemAt(i));
        }
        if (cur.equals(names)) {
            return;
        }
        Object sel = person.getSelectedItem();
        fillingPeople = true;
        try {
            person.removeAllItems();
            person.addItem("Mọi người làm");
            for (String n : names) {
                person.addItem(n);
            }
            person.setSelectedItem(sel != null && names.contains(sel) ? sel : "Mọi người làm");
        } finally {
            fillingPeople = false;
        }
    }

    private void status() {
        ProjectService ps = ui.project;
        ProjectSync s = ps.sync();
        ProjectConfig cfg = ps.ready() ? ps.config() : null;
        if (cfg == null) {
            return;
        }
        String my = ps.myLetter();
        me.setText(cfg.name + " · bạn: " + ps.me() + " (" + cfg.roleOf(ps.me()).label + ") · tài khoản web đang mở: "
                + cfg.accountDisplay(my));
        if (s != null) {
            ProjectSync.Status st = s.status();
            syncPill.setText(st.state().label + (st.pending() > 0 ? " · chờ gửi " + st.pending() : ""));
            syncPill.setToolTipText(st.text() + (st.mirrorNote().isEmpty() ? "" : " · " + st.mirrorNote())
                    + (st.renderer() ? " · máy này đang vẽ Google Sheet" : ""));
            Theme th = Theme.current();
            syncPill.setForeground(switch (st.state()) {
                case OK -> th.success.text();
                case WAIT -> th.warning.text();
                case ERROR -> th.danger.text();
                default -> th.muted;
            });
        }
        if (data != null) {
            StringBuilder sb = new StringBuilder();
            for (String l : data.letters()) {
                ProjectData.Stats x = data.stats(l);
                sb.append(sb.length() == 0 ? "" : "   |   ").append(cfg.accountDisplay(l)).append(": Done ").append(x.done()).append('/')
                        .append(x.list()).append(" (").append(Math.round(x.coverage() * 100)).append("%) · Missing ").append(x.missing())
                        .append(x.qaOpen() > 0 ? " · Q&A chờ " + x.qaOpen() : "");
            }
            stats.setText(sb.length() == 0 ? "Chưa có tài khoản nào" : sb.toString());
            int c = data.conflicts().size();
            List<String> notes = new ArrayList<>();
            if (c > 0) {
                notes.add(c + " xung đột chờ PIC chọn (Sao lưu... > Xung đột)");
            }
            for (String[] p : cfg.similarPairs()) {
                notes.add("Có thể cùng 1 tài khoản web: " + cfg.accountDisplay(p[0]) + " và " + cfg.accountDisplay(p[1])
                        + " (Tài khoản web > Danh sách... > Gộp)");
            }
            String n = s == null ? "" : s.accountNotice();
            if (!n.isEmpty()) {
                notes.add(n);
            }
            conflicts.setText(notes.isEmpty() ? " " : String.join("   |   ", notes));
        }
    }

    // ------------------------------------------------------------------ menus
    private ProjectData.Row rowAt(int viewRow) {
        if (viewRow < 0) {
            return null;
        }
        int m = table.convertRowIndexToModel(viewRow);
        return m >= 0 && m < shown.size() ? shown.get(m) : null;
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int vr = table.rowAtPoint(e.getPoint());
        int vc = table.columnAtPoint(e.getPoint());
        if (vr >= 0 && !table.isRowSelected(vr)) {
            table.setRowSelectionInterval(vr, vr);
        }
        ProjectData.Row r = rowAt(vr);
        if (r == null) {
            return;
        }
        String col = vc >= 0 && table.convertColumnIndexToModel(vc) < cols.size() ? cols.get(table.convertColumnIndexToModel(vc)) : "";
        rowMenu(r, col).show(table, e.getX(), e.getY());
    }

    private JPopupMenu rowMenu(ProjectData.Row r, String col) {
        JPopupMenu m = new JPopupMenu();
        ProjectService ps = ui.project;
        String letter = col.startsWith(Columns.ACC_PREFIX) ? col.substring(Columns.ACC_PREFIX.length()) : ps.myColumn();
        JMenu set = new JMenu("Đặt trạng thái tài khoản " + letter);
        set.setIcon(Icons.themed(t -> Icons.edit(t.fg, 13)));
        for (AccStatus st : AccStatus.values()) {
            set.add(Ui.item(st.word + " - " + st.explain, () -> {
                ps.mark(r.id, letter, st, "");
                ui.flash("Đã đặt " + st.label(letter) + " cho " + Text.shortPath(r.path, 60));
            }));
        }
        m.add(set);
        ProjectData.Cell c = r.cells.get(letter);
        if (c != null && (c.manual || !c.note.isEmpty())) {
            m.add(Ui.item("Bỏ đặt tay (dùng lại kết quả crawl) cho " + letter, Icons::refresh, () -> ps.clearMark(r.id, letter)));
        }
        m.add(Ui.item("Ghi chú dòng...", t -> Icons.edit(t.fg, 13), () -> {
            String n = ui.ask("Ghi chú cho " + r.method + " " + Text.shortPath(r.path, 60) + ":", r.note);
            if (n != null) {
                ps.note(r.id, n.trim());
            }
        }));
        ApiGroup g = localGroup(r);
        m.add(Ui.item("Hỏi đáp (Q&A / Info)...", t -> Icons.info(t.info.text()), g != null, () -> QaDialog.open(ui, g, null)));
        m.add(Ui.item("Xem ở workspace đồng đội (chỉ xem)", t -> Icons.users(t.fg, 13), ps.config().shareDetails,
                () -> TeammateDialog.open(ui, r.id)));
        m.add(Ui.item("Mở API trên Cây", Icons::api, g != null, () -> {
            ui.selectTab(ui.treeTab.panel);
            ui.treeTab.tree.expandApi(g.gid());
        }));
        if (!r.qa.isEmpty()) {
            JMenu qm = new JMenu("Q&A của dòng này (" + r.qa.size() + ")");
            for (ProjectData.QaRef q : r.qa) {
                qm.add(Ui.item(q.item().code() + " (" + q.letter() + ") " + Text.shortPath(q.item().question(), 50) + " · "
                        + q.item().status().label, () -> {
                            ApiGroup og = q.local() ? groupByGid(q.gid()) : null;
                            if (og != null) {
                                QaDialog.open(ui, og, q.item());
                            } else {
                                ui.info(q.item().code() + " · " + q.member() + "\n\n" + q.item().question() + "\n\nTrả lời: " + q.item().answer());
                            }
                        }));
            }
            m.add(qm);
        }
        m.addSeparator();
        boolean manage = ps.canManage();
        if (!r.source.inList()) {
            m.add(Ui.item("Thêm vào danh sách chuẩn (PIC)", t -> Icons.plus(t.success.text()), manage, () -> {
                ProjectConfig cfg = ps.config();
                cfg.added.add(new ProjectConfig.Added(r.id, r.method, r.path, r.branch, r.host));
                ps.saveConfig("Thêm " + r.method + " " + r.path + " vào danh sách chuẩn");
                refreshNow();
            }));
        }
        if (!r.excluded) {
            m.add(Ui.item("Loại khỏi danh sách (PIC)", t -> Icons.trash(t.danger.text()), manage, () -> {
                ps.config().excluded.add(r.id);
                ps.saveConfig("Loại " + r.method + " " + r.path + " khỏi danh sách");
                refreshNow();
            }));
        } else {
            m.add(Ui.item("Đưa lại vào danh sách (PIC)", Icons::refresh, manage, () -> {
                ps.config().excluded.remove(r.id);
                ps.saveConfig("Đưa lại " + r.path + " vào danh sách");
                refreshNow();
            }));
        }
        if (!manage) {
            m.add(Ui.item("(Thêm / loại khỏi danh sách: chỉ PIC / PIC phụ)", false, () -> { }));
        }
        m.add(Ui.item("Copy path", t -> Icons.copy(t.muted), () -> Ui.copyToClipboard(r.path)));
        return m;
    }

    ApiGroup localGroup(ProjectData.Row r) {
        ProjectData.Cell c = r.cells.get(ui.project.myColumn());
        if (c != null && !c.gids.isEmpty()) {
            ApiGroup g = groupByGid(c.gids.get(0));
            if (g != null) {
                return g;
            }
        }
        for (ApiGroup g : ui.engine.groups()) {
            if (r.id.equals(ProjectService.rowIdOf(g, data))) {
                return g;
            }
        }
        return null;
    }

    private ApiGroup groupByGid(int gid) {
        for (ApiGroup g : ui.engine.groups()) {
            if (g.gid() == gid) {
                return g;
            }
        }
        return null;
    }

    private void openSelected() {
        ProjectData.Row r = rowAt(table.getSelectedRow());
        if (r == null) {
            return;
        }
        ApiGroup g = localGroup(r);
        if (g == null) {
            if (ui.project.config().shareDetails && !TeammateDialog.apis(ui.project).isEmpty()) {
                TeammateDialog.open(ui, r.id);
            } else {
                Ui.hint(table, "Tài khoản đang mở chưa gọi API này");
            }
            return;
        }
        ui.selectTab(ui.treeTab.panel);
        ui.treeTab.tree.expandApi(g.gid());
    }

    private JPopupMenu accountMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Danh sách tài khoản web (dùng chung, ai cũng sửa được)...", t -> Icons.users(t.fg, 13),
                () -> ProjectDialogs.accounts(ui)));
        m.add(Ui.item("Tài khoản web tiếp theo (workspace mới: giữ cây nhánh + Excel)...", Icons::plus, () -> ui.excel.familyTab.createChild()));
        m.add(ui.excel.familyTab.switchMenuAsMenu());
        m.add(Ui.item("Xem workspace đồng đội (chỉ xem)...", t -> Icons.users(t.fg, 13), () -> TeammateDialog.open(ui, null)));
        m.addSeparator();
        m.add(Ui.item("Chuyển dự án sang OneDrive / thư mục khác...", Icons::move, () -> ProjectDialogs.moveProject(ui)));
        m.add(Ui.item("Mở thư mục dự án", Icons::folder, () -> open(ui.project.store().dir())));
        return m;
    }

    private JPopupMenu reportMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Xuất báo cáo...", t -> Icons.excel(t.fg, 13), () -> ProjectDialogs.export(ui)));
        m.add(Ui.item("Gửi khách hàng (tạo vòng mới)...", Icons::send, () -> ProjectDialogs.sendClient(ui)));
        m.add(Ui.item("Nhập phản hồi của khách hàng...", Icons::upload, () -> ProjectDialogs.importClient(ui)));
        m.addSeparator();
        m.add(Ui.item("Mở Excel của tôi (cả nhóm, tự cập nhật)", t -> Icons.excel(t.fg, 13), () -> {
            Path f = ui.project.store().mirrorFile();
            if (!Files.isRegularFile(f)) {
                ui.project.syncSoon();
                ui.flash("Đang tạo " + f.getFileName() + ", thử lại sau vài giây");
                return;
            }
            open(f);
        }));
        m.add(Ui.item("Mở thư mục báo cáo", Icons::folder, () -> open(ui.project.store().folder("bao-cao"))));
        return m;
    }

    static void open(Path p) {
        try {
            Desktop.getDesktop().open(p.toFile());
        } catch (Exception e) {
            Ui.copyToClipboard(p.toAbsolutePath().toString());
        }
    }

    // ------------------------------------------------------------------ renderer
    private final class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            Object val = v instanceof Xlsx.StyledCell sc ? sc.value() : v;
            super.getTableCellRendererComponent(t, val, sel, focus, row, col);
            Theme th = Theme.current();
            setToolTipText(null);
            if (!sel) {
                setBackground(t.getBackground());
                setForeground(t.getForeground());
                if (v instanceof Xlsx.StyledCell sc) {
                    Theme.Token tok = switch (sc.tone()) {
                        case DONE_GREEN -> th.success;
                        case DENIED_SLATE -> th.slate;
                        case ERROR_RED -> th.danger;
                        case MISSING_RED -> th.pink;
                        case DOING_BLUE -> th.info;
                        case OOS_GRAY -> th.neutral;
                        case CONFLICT_ORANGE -> th.orange;
                        case INFO_YELLOW -> th.amber;
                        case QA_PURPLE -> th.indigo;
                        default -> null;
                    };
                    if (tok != null) {
                        setBackground(tok.fill());
                        setForeground(tok.text());
                    }
                }
            }
            int mc = t.convertColumnIndexToModel(col);
            if (mc < cols.size() && cols.get(mc).startsWith(Columns.ACC_PREFIX)) {
                ProjectData.Row r = rowAt(row);
                String l = cols.get(mc).substring(Columns.ACC_PREFIX.length());
                ProjectData.Cell c = r == null ? null : r.cells.get(l);
                if (c != null) {
                    setToolTipText((c.manual ? "Đặt tay" + (c.manualVia.isEmpty() ? "" : " qua " + c.manualVia) + " bởi " + c.by
                            : c.apis > 0 ? c.apis + " lần bắt" + (c.http != null ? " · HTTP " + c.http : "") : "Chưa gọi")
                            + (c.note.isEmpty() ? "" : " · " + c.note) + (c.conflict ? " · thành viên ghi khác nhau" : "")
                            + (c.byMember.size() > 1 ? " · " + c.byMember : ""));
                    if (c.manual) {
                        setFont(th.boldFont);
                    } else {
                        setFont(th.baseFont);
                    }
                }
            } else {
                setFont(th.baseFont);
            }
            return this;
        }
    }
}
