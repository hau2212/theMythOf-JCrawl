package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.QaItem;
import themythofcrawl.project.ClientRound;
import themythofcrawl.project.Columns;
import themythofcrawl.project.ProjectConfig;
import themythofcrawl.project.ProjectData;
import themythofcrawl.project.ProjectStore;
import themythofcrawl.project.ProjectSync;
import themythofcrawl.project.ProjectWorkbook;
import themythofcrawl.project.SheetDef;
import themythofcrawl.project.SyncRecord;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/** V_11.4 project tools: columns, workbook sheets, reports, client rounds, backups / conflicts, moving the project. */
final class ProjectDialogs {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private ProjectDialogs() {
    }

    static JDialog dialog(MainPanel ui, String title, String subtitle, JPanel body, int w, int h) {
        JDialog dlg = new JDialog(Ui.ownerWindow(ui.root()), title, java.awt.Dialog.ModalityType.MODELESS);
        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        root.add(Ui.dialogHeader(title, subtitle), BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        dlg.setContentPane(root);
        dlg.setSize(new Dimension(Theme.current().scaled(w), Theme.current().scaled(h)));
        dlg.setLocationRelativeTo(ui.root());
        return dlg;
    }

    // ================================================================== Cột...
    private static final class ColItem {
        String id;
        String header;
        boolean visible;

        ColItem(ProjectConfig.Col c) {
            id = c.id();
            header = Text.nz(c.header());
            visible = c.visible();
        }

        @Override
        public String toString() {
            String h = header.isBlank() ? Columns.defaultHeader(id) : header + "  (" + Columns.defaultHeader(id) + ")";
            String ex = Columns.explain(id);
            return (visible ? "[x] " : "[  ] ") + h + (ex.isEmpty() ? "" : "   ·   " + ex);
        }
    }

    static void columns(MainPanel ui, Runnable after) {
        ProjectService ps = ui.project;
        ProjectConfig cfg = ps.config();
        JComboBox<String> profiles = new JComboBox<>();
        for (ProjectConfig.Profile p : cfg.profiles) {
            profiles.addItem(p.name());
        }
        profiles.setSelectedItem(cfg.profile(cfg.activeProfile).name());
        DefaultListModel<ColItem> lm = new DefaultListModel<>();
        JList<ColItem> list = new JList<>(lm);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JTextField header = new JTextField(22);
        Runnable load = () -> {
            lm.clear();
            for (ProjectConfig.Col c : cfg.profile((String) profiles.getSelectedItem()).cols()) {
                lm.addElement(new ColItem(c));
            }
        };
        load.run();
        profiles.addActionListener(Ui.safe(e -> load.run()));
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && e.getX() < Theme.current().scaled(34)) {
                    lm.get(i).visible = !lm.get(i).visible;
                    list.repaint();
                }
            }
        });
        list.addListSelectionListener(e -> {
            ColItem c = list.getSelectedValue();
            header.setText(c == null ? "" : c.header);
        });
        Runnable move = null;
        JButton up = Ui.button("Lên", Icons::minus, "Đưa cột lên trước", () -> swap(list, lm, -1));
        JButton down = Ui.button("Xuống", Icons::plus, "Đưa cột xuống sau", () -> swap(list, lm, 1));
        JButton toggle = Ui.button("Hiện / Ẩn", Icons::show, "Bật / tắt cột đang chọn (hoặc bấm ô [x] đầu dòng)", () -> {
            ColItem c = list.getSelectedValue();
            if (c != null) {
                c.visible = !c.visible;
                list.repaint();
            }
        });
        JButton rename = Ui.button("Đổi tên hiển thị", t -> Icons.edit(t.fg, 13), "Tên cột trong báo cáo (để trống = tên gốc)", () -> {
            ColItem c = list.getSelectedValue();
            if (c != null) {
                c.header = header.getText().trim();
                list.repaint();
            }
        });
        JButton newProf = Ui.button("Mẫu mới...", Icons::plus, "Tạo mẫu cột mới từ mẫu đang xem", () -> {
            String n = ui.ask("Tên mẫu cột mới:", "Mẫu của tôi");
            if (n != null && !n.isBlank()) {
                List<ProjectConfig.Col> cols = new ArrayList<>();
                for (int i = 0; i < lm.size(); i++) {
                    cols.add(new ProjectConfig.Col(lm.get(i).id, lm.get(i).header, lm.get(i).visible));
                }
                cfg.putProfile(new ProjectConfig.Profile(n.trim(), cols));
                profiles.addItem(n.trim());
                profiles.setSelectedItem(n.trim());
            }
        });
        JPanel side = new JPanel(new GridLayout(0, 1, 0, 4));
        for (JButton b : List.of(up, down, toggle)) {
            side.add(b);
        }
        side.add(Ui.muted("Tên hiển thị:"));
        side.add(header);
        side.add(rename);
        side.add(newProf);
        JPanel body = new JPanel(new BorderLayout(Theme.SPACE_2, Theme.SPACE_2));
        body.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Mẫu cột:"), profiles,
                Ui.muted("Thứ tự trong danh sách = thứ tự cột. \"Các tài khoản\" tự mở thành 1 cột mỗi tài khoản.")), BorderLayout.NORTH);
        body.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel east = new JPanel(new BorderLayout());
        east.add(side, BorderLayout.NORTH);
        body.add(east, BorderLayout.EAST);
        JDialog[] ref = new JDialog[1];
        body.add(Ui.flow(FlowLayout.RIGHT, Ui.muted(ps.canManage() ? "Mẫu cột được gửi cho cả nhóm." : "Bạn không phải PIC: mẫu chỉ áp dụng trên máy này."),
                Ui.secondaryBtn("Đóng", null, () -> ref[0].dispose()),
                Ui.accent("Lưu & dùng mẫu này", t -> Icons.check(t.accent.onSolid()), t -> t.accent, () -> {
                    List<ProjectConfig.Col> cols = new ArrayList<>();
                    for (int i = 0; i < lm.size(); i++) {
                        cols.add(new ProjectConfig.Col(lm.get(i).id, lm.get(i).header, lm.get(i).visible));
                    }
                    String name = (String) profiles.getSelectedItem();
                    cfg.putProfile(new ProjectConfig.Profile(name, cols));
                    cfg.activeProfile = name;
                    ps.saveConfig("Mẫu cột: " + name);
                    ref[0].dispose();
                    if (after != null) {
                        after.run();
                    }
                })), BorderLayout.SOUTH);
        ref[0] = dialog(ui, "Cột", "Chọn cột, sắp xếp thứ tự (vd STT, Path, Method...), đổi tên hiển thị. Dùng cho bảng Dự án, "
                + "báo cáo Excel và Google Sheet.", body, 760, 560);
        ref[0].setVisible(true);
    }

    private static <T> void swap(JList<T> list, DefaultListModel<T> lm, int dir) {
        int i = list.getSelectedIndex();
        int j = i + dir;
        if (i < 0 || j < 0 || j >= lm.size()) {
            return;
        }
        T a = lm.get(i);
        lm.set(i, lm.get(j));
        lm.set(j, a);
        list.setSelectedIndex(j);
    }

    // ================================================================== Sổ dự án
    static void sheets(MainPanel ui) {
        ProjectService ps = ui.project;
        ProjectConfig cfg = ps.config();
        List<SheetDef> defs = new ArrayList<>(cfg.sheets);
        DefaultTableModel m = new DefaultTableModel(new Object[] {"Bật", "Tên sheet", "Loại", "Tài khoản", "Bộ lọc", "Mẫu cột", "Số dòng",
            "Thống kê"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        ThemedTable t = new ThemedTable(m);
        Ui.style(t);
        Ui.widths(t, 40, 170, 120, 80, 110, 110, 60, 260);
        Runnable fill = () -> {
            m.setRowCount(0);
            ProjectData d = ps.data();
            for (SheetDef s : defs) {
                String[] st = sheetStats(d, s);
                m.addRow(new Object[] {s.enabled() ? "Có" : "-", s.name(), s.type().label, s.account(), s.filter(),
                    s.profile().isEmpty() ? "(mẫu chung)" : s.profile(), st[0], st[1]});
            }
        };
        fill.run();
        Runnable edit = () -> {
            int i = t.getSelectedRow();
            if (i < 0) {
                Ui.hint(t, "Chọn 1 sheet trước");
                return;
            }
            SheetDef nd = editSheet(ui, defs.get(t.convertRowIndexToModel(i)));
            if (nd != null) {
                defs.set(t.convertRowIndexToModel(i), nd);
                fill.run();
            }
        };
        Ui.onDoubleClick(t, edit);
        JDialog[] ref = new JDialog[1];
        JPanel bar = Ui.flow(FlowLayout.LEFT,
                Ui.button("Thêm...", Icons::plus, "Thêm sheet (tài khoản, lọc, trống...)", () -> {
                    SheetDef nd = editSheet(ui, new SheetDef(null, "Còn thiếu của B", SheetDef.Type.FILTER, "", "missing:B", "", true));
                    if (nd != null) {
                        defs.add(nd);
                        fill.run();
                    }
                }),
                Ui.button("Sửa...", t2 -> Icons.edit(t2.fg, 13), "Sửa sheet đang chọn (nhấp đúp cũng được)", edit),
                Ui.button("Bật / Tắt", Icons::show, "Có / không xuất sheet này", () -> {
                    int i = t.getSelectedRow();
                    if (i >= 0) {
                        SheetDef s = defs.get(t.convertRowIndexToModel(i));
                        defs.set(t.convertRowIndexToModel(i), s.with(s.name(), s.type(), s.account(), s.filter(), s.profile(), !s.enabled()));
                        fill.run();
                    }
                }),
                Ui.button("Lên", Icons::minus, "Đưa sheet lên trước", () -> move(t, defs, -1, fill)),
                Ui.button("Xuống", Icons::plus, "Đưa sheet xuống sau", () -> move(t, defs, 1, fill)),
                Ui.button("Xóa", t2 -> Icons.trash(t2.danger.text()), "Xóa sheet đang chọn", () -> {
                    int i = t.getSelectedRow();
                    if (i >= 0 && ui.confirmDialog("Xóa sheet '" + defs.get(t.convertRowIndexToModel(i)).name() + "' khỏi sổ?")) {
                        defs.remove(t.convertRowIndexToModel(i));
                        fill.run();
                    }
                }),
                Ui.button("Mặc định", Icons::refresh, "Về bộ sheet mặc định", () -> {
                    defs.clear();
                    defs.addAll(SheetDef.defaults());
                    fill.run();
                }));
        JPanel body = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        body.add(bar, BorderLayout.NORTH);
        body.add(MainPanel.scroll(t), BorderLayout.CENTER);
        body.add(Ui.flow(FlowLayout.RIGHT, Ui.muted(ps.canManage() ? "Sổ dự án dùng chung cho cả nhóm (Google Sheet vẽ theo sổ này)."
                        : "Chỉ PIC / PIC phụ đổi được sổ của nhóm; thay đổi của bạn chỉ áp dụng trên máy này."),
                Ui.secondaryBtn("Đóng", null, () -> ref[0].dispose()),
                Ui.accent("Lưu sổ", t2 -> Icons.save(t2.accent.onSolid()), t2 -> t2.accent, () -> {
                    cfg.sheets.clear();
                    cfg.sheets.addAll(defs);
                    ps.saveConfig("Sổ dự án: " + defs.size() + " sheet");
                    ref[0].dispose();
                    ui.flash("Đã lưu sổ dự án");
                })), BorderLayout.SOUTH);
        ref[0] = dialog(ui, "Sổ dự án", "Các sheet của báo cáo Excel, Excel của bạn và Google Sheet của nhóm: thêm / xóa / đổi tên /"
                + " sắp xếp / lọc. Bộ lọc: missing:B, done:A, denied:B, diff, only, qa, info, bac, conflict, text:abc", body, 980, 560);
        ref[0].setVisible(true);
    }

    private static void move(ThemedTable t, List<SheetDef> defs, int dir, Runnable fill) {
        int i = t.getSelectedRow();
        if (i < 0) {
            return;
        }
        int mi = t.convertRowIndexToModel(i);
        int j = mi + dir;
        if (j < 0 || j >= defs.size()) {
            return;
        }
        SheetDef a = defs.get(mi);
        defs.set(mi, defs.get(j));
        defs.set(j, a);
        fill.run();
        t.setRowSelectionInterval(j, j);
    }

    static String[] sheetStats(ProjectData d, SheetDef s) {
        if (d == null) {
            return new String[] {"", ""};
        }
        List<ProjectData.Row> rows = switch (s.type()) {
            case SUMMARY -> d.visibleRows();
            case DIFF -> d.filter("diff");
            case ONLY -> d.filter("only");
            case BAC -> d.filter("bac");
            case FILTER -> d.filter(s.filter());
            case ACCOUNT -> d.listRows();
            default -> List.of();
        };
        int qa = (int) d.allQa().stream().filter(q -> q.item().kind() == QaItem.Kind.QA).count();
        int info = (int) d.allQa().stream().filter(q -> q.item().kind() == QaItem.Kind.INFO).count();
        return switch (s.type()) {
            case QA, QA_DETAIL -> new String[] {String.valueOf(qa), qa + " Q&A · chờ "
                    + d.allQa().stream().filter(q -> q.item().status().waiting() && q.item().kind() == QaItem.Kind.QA).count()};
            case INFO -> new String[] {String.valueOf(info), ""};
            case OVERVIEW -> new String[] {"", d.letters().size() + " tài khoản · " + d.listRows().size() + " API trong danh sách"};
            case ACCOUNT -> {
                StringBuilder sb = new StringBuilder();
                for (String l : d.letters()) {
                    if (s.account().isEmpty() || s.account().equals("*") || s.account().equalsIgnoreCase(l)) {
                        ProjectData.Stats x = d.stats(l);
                        sb.append(sb.length() == 0 ? "" : " · ").append(l).append(": Done ").append(x.done()).append(", Missing ").append(x.missing());
                    }
                }
                yield new String[] {String.valueOf(rows.size()), sb.toString()};
            }
            default -> {
                int done = 0;
                for (ProjectData.Row r : rows) {
                    done += r.reached() > 0 ? 1 : 0;
                }
                yield new String[] {String.valueOf(rows.size()), rows.isEmpty() ? "" : done + " dòng có ít nhất 1 tài khoản gọi được"};
            }
        };
    }

    private static SheetDef editSheet(MainPanel ui, SheetDef s) {
        JTextField name = new JTextField(s.name(), 24);
        JComboBox<SheetDef.Type> type = new JComboBox<>(SheetDef.Type.values());
        type.setSelectedItem(s.type());
        JTextField acc = new JTextField(s.account(), 6);
        JTextField flt = new JTextField(s.filter(), 16);
        JComboBox<String> prof = new JComboBox<>();
        prof.addItem("(mẫu chung)");
        for (ProjectConfig.Profile p : ui.project.config().profiles) {
            prof.addItem(p.name());
        }
        prof.setSelectedItem(s.profile().isEmpty() ? "(mẫu chung)" : s.profile());
        JLabel explain = Ui.muted(s.type().explain);
        type.addActionListener(e -> explain.setText(((SheetDef.Type) type.getSelectedItem()).explain));
        JPanel f = new JPanel(new GridLayout(0, 1, 0, 4));
        f.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Tên:"), name));
        f.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Loại:"), type));
        f.add(explain);
        f.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Tài khoản:"), acc, Ui.muted("(* = mỗi tài khoản 1 sheet; A, B... = 1 tài khoản)")));
        f.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Bộ lọc:"), flt, Ui.muted("missing:B · done:A · denied:B · diff · only · qa · info · bac · text:abc")));
        f.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Mẫu cột:"), prof));
        if (javax.swing.JOptionPane.showConfirmDialog(Ui.ownerWindow(ui.root()), f, "Sheet", javax.swing.JOptionPane.OK_CANCEL_OPTION)
                != javax.swing.JOptionPane.OK_OPTION) {
            return null;
        }
        String p = (String) prof.getSelectedItem();
        return s.with(name.getText(), (SheetDef.Type) type.getSelectedItem(), acc.getText().trim(), flt.getText().trim(),
                "(mẫu chung)".equals(p) ? "" : p, s.enabled());
    }

    // ================================================================== Xuất báo cáo
    record ExportForm(JPanel panel, List<JCheckBox> letters, JRadioButton all, JRadioButton mine, List<JCheckBox> sheets, JComboBox<String> profile,
                      JCheckBox images, JComboBox<String> size, JCheckBox perAccount, JTextField file, List<SheetDef> defs) {
        List<String> chosenLetters(ProjectService ps, ProjectData d) {
            if (all.isSelected()) {
                return d.letters();
            }
            if (mine.isSelected()) {
                return List.of(ps.myLetter());
            }
            List<String> out = new ArrayList<>();
            for (JCheckBox b : letters) {
                if (b.isSelected()) {
                    out.add(b.getActionCommand());
                }
            }
            return out;
        }

        List<SheetDef> chosenSheets() {
            List<SheetDef> out = new ArrayList<>();
            for (int i = 0; i < sheets.size(); i++) {
                if (sheets.get(i).isSelected()) {
                    SheetDef s = defs.get(i);
                    out.add(s.with(s.name(), s.type(), s.account(), s.filter(), s.profile(), true));
                }
            }
            return out;
        }

        int px() {
            return switch (size.getSelectedIndex()) {
                case 0 -> 220;
                case 2 -> 480;
                default -> 320;
            };
        }
    }

    static ExportForm exportForm(MainPanel ui, ProjectData d, boolean client, String fileName) {
        ProjectService ps = ui.project;
        ProjectConfig cfg = ps.config();
        boolean member = client && !ps.canManage();
        JRadioButton all = new JRadioButton("Cả dự án (mọi tài khoản)", !member);
        JRadioButton mine = new JRadioButton("Chỉ tài khoản đang mở (" + cfg.accountDisplay(ps.myLetter()) + ")", member);
        JRadioButton pick = new JRadioButton("Chọn tài khoản:");
        ButtonGroup bg = new ButtonGroup();
        bg.add(all);
        bg.add(mine);
        bg.add(pick);
        List<JCheckBox> letters = new ArrayList<>();
        JPanel lp = Ui.flow(FlowLayout.LEFT, pick);
        for (String l : d.letters()) {
            JCheckBox b = new JCheckBox(cfg.accountDisplay(l), true);
            b.setActionCommand(l);
            letters.add(b);
            lp.add(b);
        }
        List<SheetDef> defs = new ArrayList<>(cfg.sheets);
        List<JCheckBox> sheets = new ArrayList<>();
        JPanel sp = new JPanel(new GridLayout(0, 3, 6, 2));
        for (SheetDef s : defs) {
            boolean on = s.enabled() && (!client || s.type() != SheetDef.Type.LOG && s.type() != SheetDef.Type.BAC && s.type() != SheetDef.Type.ONLY);
            JCheckBox b = new JCheckBox(s.name(), on);
            b.setToolTipText(s.type().explain);
            sheets.add(b);
            sp.add(b);
        }
        JComboBox<String> profile = new JComboBox<>();
        for (ProjectConfig.Profile p : cfg.profiles) {
            profile.addItem(p.name());
        }
        profile.setSelectedItem(client ? "Nộp khách hàng" : cfg.activeProfile);
        JCheckBox images = new JCheckBox("Chèn ảnh Q&A vào file", true);
        JComboBox<String> size = new JComboBox<>(new String[] {"Ảnh nhỏ", "Ảnh vừa", "Ảnh lớn"});
        size.setSelectedIndex(1);
        JCheckBox per = new JCheckBox("Mỗi tài khoản 1 file", false);
        JTextField file = new JTextField(fileName, 34);
        JPanel f = new JPanel(new GridLayout(0, 1, 0, 4));
        f.add(Ui.bold("Phạm vi"));
        f.add(Ui.flow(FlowLayout.LEFT, all, mine));
        f.add(lp);
        f.add(Ui.bold("Sheet (thứ tự theo Sổ dự án)"));
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(f, BorderLayout.NORTH);
        panel.add(new JScrollPane(sp), BorderLayout.CENTER);
        JPanel south = new JPanel(new GridLayout(0, 1, 0, 4));
        south.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Mẫu cột:"), profile, Ui.button("Cột...", Icons::sliders, "Sửa mẫu cột",
                () -> columns(ui, null))));
        south.add(Ui.flow(FlowLayout.LEFT, images, size, per));
        south.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Tên file:"), file));
        panel.add(south, BorderLayout.SOUTH);
        return new ExportForm(panel, letters, all, mine, sheets, profile, images, size, per, file, defs);
    }

    static void export(MainPanel ui) {
        ProjectService ps = ui.project;
        ProjectData d = ps.buildNow();
        if (d == null) {
            ui.info("Workspace này chưa lưu trên đĩa.");
            return;
        }
        String name = "BaoCao_" + ProjectStore.safe(ps.config().name) + "_" + LocalDateTime.now().format(STAMP) + ".xlsx";
        ExportForm form = exportForm(ui, d, false, name);
        JDialog[] ref = new JDialog[1];
        JPanel body = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        body.add(form.panel(), BorderLayout.CENTER);
        body.add(Ui.flow(FlowLayout.RIGHT, Ui.secondaryBtn("Đóng", null, () -> ref[0].dispose()),
                Ui.accent("Xuất", t -> Icons.excel(t.accent.onSolid(), 13), t -> t.accent, () -> {
                    List<String> letters = form.chosenLetters(ps, d);
                    List<SheetDef> sheets = form.chosenSheets();
                    if (letters.isEmpty() || sheets.isEmpty()) {
                        Ui.hint(form.file(), "Chọn ít nhất 1 tài khoản và 1 sheet");
                        return;
                    }
                    ref[0].dispose();
                    runExport(ui, d, letters, sheets, (String) form.profile().getSelectedItem(), form.images().isSelected(), form.px(),
                            form.perAccount().isSelected(), form.file().getText().trim());
                })), BorderLayout.SOUTH);
        ref[0] = dialog(ui, "Xuất báo cáo", "Báo cáo theo tài khoản, cả dự án, chỉ tài khoản cha / con; lưu vào bao-cao/ trong thư mục dự án",
                body, 820, 600);
        ref[0].setVisible(true);
    }

    private static void runExport(MainPanel ui, ProjectData d, List<String> letters, List<SheetDef> sheets, String profile, boolean images,
                                  int px, boolean perAccount, String fileName) {
        ProjectService ps = ui.project;
        ProjectStore store = ps.store();
        ProjectSync sync = ps.sync();
        boolean qaOnly = sheets.stream().allMatch(s -> s.type() == SheetDef.Type.QA || s.type() == SheetDef.Type.QA_DETAIL
                || s.type() == SheetDef.Type.INFO);
        String folder = qaOnly ? "qa" : perAccount || letters.size() == 1 ? "tung-tai-khoan" : "noi-bo";
        Path dir = store.reportsDir(folder);
        String base = fileName.isEmpty() ? "BaoCao.xlsx" : fileName.endsWith(".xlsx") ? fileName : fileName + ".xlsx";
        new SwingWorker<List<Path>, Void>() {
            @Override
            protected List<Path> doInBackground() throws Exception {
                List<Path> out = new ArrayList<>();
                List<List<String>> groups = new ArrayList<>();
                if (perAccount) {
                    for (String l : letters) {
                        groups.add(List.of(l));
                    }
                } else {
                    groups.add(letters);
                }
                for (List<String> g : groups) {
                    ProjectWorkbook.Options o = new ProjectWorkbook.Options(g, profile, images, px, false, store.logTail(500),
                            sync == null ? null : sync::remoteImage, ps.config().name);
                    byte[] x = ProjectWorkbook.xlsx(d, sheets, o);
                    String fn = perAccount ? base.replace(".xlsx", "_" + ProjectStore.safe(ps.config().accountDisplay(g.get(0))) + ".xlsx") : base;
                    Path f = dir.resolve(fn);
                    ProjectStore.write(f, x);
                    out.add(f);
                }
                return out;
            }

            @Override
            protected void done() {
                try {
                    List<Path> files = get();
                    store.log(ps.me(), "export", folder, "Xuất " + files.size() + " file");
                    ui.flash("Đã xuất " + files.size() + " file vào bao-cao/" + folder);
                    ProjectTab.open(dir);
                } catch (Exception e) {
                    ui.info("Không xuất được: " + rootMessage(e));
                }
            }
        }.execute();
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    // ================================================================== Gửi khách hàng / Nhập phản hồi
    static void sendClient(MainPanel ui) {
        ProjectService ps = ui.project;
        ProjectData d = ps.buildNow();
        String name = "BaoCao_" + ProjectStore.safe(ps.config().name) + ".xlsx";
        ExportForm form = exportForm(ui, d, true, name);
        form.perAccount().setVisible(false);
        JDialog[] ref = new JDialog[1];
        JPanel body = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        body.add(form.panel(), BorderLayout.CENTER);
        body.add(Ui.flow(FlowLayout.RIGHT, Ui.secondaryBtn("Đóng", null, () -> ref[0].dispose()),
                Ui.accent("Tạo vòng gửi khách hàng", t -> Icons.send(t.accent.onSolid()), t -> t.accent, () -> {
                    List<String> letters = form.chosenLetters(ps, d);
                    List<SheetDef> sheets = form.chosenSheets();
                    ref[0].dispose();
                    try {
                        ProjectWorkbook.Options o = new ProjectWorkbook.Options(letters, (String) form.profile().getSelectedItem(),
                                form.images().isSelected(), form.px(), true, List.of(), null, ps.config().name);
                        byte[] x = ProjectWorkbook.xlsx(d, sheets, o);
                        String fn = form.file().getText().trim().isEmpty() ? name : form.file().getText().trim();
                        ClientRound.Sent s = ClientRound.send(ps.store(), x, fn.endsWith(".xlsx") ? fn : fn + ".xlsx", ps.me(),
                                Map.of("letters", letters, "profile", String.valueOf(form.profile().getSelectedItem())));
                        for (ProjectData.QaRef q : d.allQa()) {
                            if (q.local() && q.item().status() == QaItem.Status.OPEN && letters.contains(q.letter())) {
                                ApiGroup g = ui.engine.groups().stream().filter(gg -> gg.gid() == q.gid()).findFirst().orElse(null);
                                if (g != null) {
                                    ui.engine.putQa(g, q.item().withStatus(QaItem.Status.SENT));
                                }
                            }
                        }
                        ps.syncSoon();
                        roundDone(ui, s);
                    } catch (IOException | RuntimeException e) {
                        ui.info("Không tạo được: " + rootMessage(e));
                    }
                })), BorderLayout.SOUTH);
        ref[0] = dialog(ui, "Gửi khách hàng", "Tạo vòng mới: bao-cao/khach-hang/vong-N_ngày/ có goc/ (giữ lại, không share) và chia-se/"
                + " (share cho khách hàng). Q&A 'Mới' chuyển thành 'Đã gửi khách hàng'.", body, 820, 600);
        ref[0].setVisible(true);
    }

    private static void roundDone(MainPanel ui, ClientRound.Sent s) {
        JTextArea how = new JTextArea("""
                Đã tạo %s.

                Chia sẻ cho khách hàng (OneDrive):
                  1. Mở thư mục chia-se (nút bên dưới).
                  2. Chuột phải file -> Chia sẻ (Share) -> nhập email khách hàng -> "Có thể chỉnh sửa" (Can edit).
                  3. Nên đặt ngày hết hạn cho link. CHỈ share file / thư mục chia-se, không share cả thư mục dự án.
                  4. Khách hàng mở link bằng Excel trên web, xem ảnh, ghi vào cột "Khách hàng trả lời", có thể dán ảnh.

                Khi khách hàng trả lời xong: Báo cáo -> Nhập phản hồi của khách hàng (bản gốc trong goc/ không bao giờ bị sửa).
                """.formatted(s.dir().getFileName()));
        how.setEditable(false);
        how.setLineWrap(true);
        how.setWrapStyleWord(true);
        JDialog[] ref = new JDialog[1];
        JPanel body = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        body.add(new JScrollPane(how), BorderLayout.CENTER);
        body.add(Ui.flow(FlowLayout.RIGHT, Ui.button("Mở thư mục chia-se", Icons::folder, "", () -> ProjectTab.open(s.shared().getParent())),
                Ui.secondaryBtn("Đóng", null, () -> ref[0].dispose())), BorderLayout.SOUTH);
        ref[0] = dialog(ui, "Đã tạo vòng gửi khách hàng", s.shared().toString(), body, 720, 420);
        ref[0].setVisible(true);
    }

    static void importClient(MainPanel ui) {
        ProjectService ps = ui.project;
        ProjectStore store = ps.store();
        List<Path> rounds = store.rounds();
        JComboBox<String> pick = new JComboBox<>();
        for (int i = rounds.size() - 1; i >= 0; i--) {
            pick.addItem(rounds.get(i).getFileName().toString());
        }
        Path[] other = new Path[1];
        JLabel otherLbl = Ui.muted("(file khách hàng sửa trong chia-se/ của vòng)");
        JButton choose = Ui.button("Chọn file khách hàng gửi lại...", Icons::upload, "Khách hàng gửi file qua email: chọn file đó",
                () -> {
                    JFileChooser fc = new JFileChooser();
                    if (fc.showOpenDialog(Ui.ownerWindow(ui.root())) == JFileChooser.APPROVE_OPTION) {
                        other[0] = fc.getSelectedFile().toPath();
                        otherLbl.setText(other[0].getFileName().toString());
                    }
                });
        DefaultTableModel m = new DefaultTableModel(new Object[] {"Nhận", "Loại", "Sheet", "Mục", "Cột", "Trước", "Sau"}, 0) {
            @Override
            public Class<?> getColumnClass(int c) {
                return c == 0 ? Boolean.class : Object.class;
            }

            @Override
            public boolean isCellEditable(int r, int c) {
                return c == 0;
            }
        };
        javax.swing.JTable t = new javax.swing.JTable(m);
        Ui.widths(t, 40, 130, 110, 150, 130, 200, 260);
        List<ClientRound.Change> changes = new ArrayList<>();
        String[] roundName = new String[1];
        Runnable compare = () -> {
            if (pick.getSelectedIndex() < 0) {
                Ui.hint(pick, "Chưa có vòng nào (Báo cáo -> Gửi khách hàng)");
                return;
            }
            Path dir = rounds.get(rounds.size() - 1 - pick.getSelectedIndex());
            roundName[0] = dir.getFileName().toString();
            try {
                Path goc = ClientRound.originalFile(dir);
                Path back = other[0] != null ? other[0] : ClientRound.sharedFile(dir);
                if (goc == null || back == null) {
                    Ui.hint(pick, "Thiếu file trong goc/ hoặc chia-se/");
                    return;
                }
                changes.clear();
                changes.addAll(ClientRound.diff(goc, back, ps.config()));
                m.setRowCount(0);
                for (ClientRound.Change c : changes) {
                    boolean on = c.kind() == ClientRound.Kind.ANSWER || c.kind() == ClientRound.Kind.MARK || c.kind() == ClientRound.Kind.NOTE
                            || c.kind() == ClientRound.Kind.IMAGE;
                    m.addRow(new Object[] {on, c.kind().label, c.sheet(), c.target(), c.column(), Text.shortPath(c.before(), 80),
                        c.kind() == ClientRound.Kind.IMAGE ? "(ảnh " + (c.image() == null ? 0 : c.image().length / 1024) + " KB)"
                                : Text.shortPath(c.kind() == ClientRound.Kind.DELETED_ROW ? c.describe() : c.after(), 120)});
                }
                if (changes.isEmpty()) {
                    Ui.hint(t, "Khách hàng chưa sửa gì");
                }
            } catch (IOException | RuntimeException e) {
                ui.info("Không đọc được file: " + rootMessage(e) + "\n(Đóng file trong Excel rồi thử lại.)");
            }
        };
        JDialog[] ref = new JDialog[1];
        JPanel body = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        body.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Vòng:"), pick, choose, otherLbl,
                Ui.button("So sánh", Icons::compare, "So file khách hàng với bản gốc", compare)), BorderLayout.NORTH);
        body.add(MainPanel.scroll(t), BorderLayout.CENTER);
        body.add(Ui.flow(FlowLayout.RIGHT, Ui.muted("Nhận: câu trả lời vào Q&A, trạng thái thành 'đặt tay qua Khách hàng', ảnh vào Q&A."),
                Ui.secondaryBtn("Đóng", null, () -> ref[0].dispose()),
                Ui.accent("Nhận các thay đổi đã chọn", t2 -> Icons.check(t2.accent.onSolid()), t2 -> t2.accent, () -> {
                    List<ClientRound.Change> chosen = new ArrayList<>();
                    for (int i = 0; i < changes.size(); i++) {
                        if (Boolean.TRUE.equals(m.getValueAt(i, 0))) {
                            chosen.add(changes.get(i));
                        }
                    }
                    int n = ClientRound.apply(store, chosen, roundName[0], ps.me(), (qaId, img) -> attachClientImage(ui, qaId, img));
                    store.saveRecords();
                    ps.syncSoon();
                    ui.flash("Đã nhận " + n + " thay đổi của khách hàng");
                    ref[0].dispose();
                })), BorderLayout.SOUTH);
        ref[0] = dialog(ui, "Nhập phản hồi của khách hàng", "So file khách hàng đã sửa với bản gốc của vòng, chọn thay đổi nào được nhận.",
                body, 1060, 600);
        ref[0].setVisible(true);
        compare.run();
    }

    /** A screenshot the client pasted next to a Q&A of the open workspace: added to that Q&A as "Ảnh khách hàng". */
    static boolean attachClientImage(MainPanel ui, String qaId, byte[] img) {
        for (ApiGroup g : ui.engine.groups()) {
            for (QaItem q : g.qa()) {
                if (q.id().equals(qaId)) {
                    String name = ui.engine.addQaImage(g, img);
                    if (name == null) {
                        return false;
                    }
                    List<QaItem.QaImage> ims = new ArrayList<>(q.imgs());
                    ims.add(new QaItem.QaImage(name, QaItem.QaImage.Role.CUSTOMER, 0, "Ảnh khách hàng gửi", "", ""));
                    ui.engine.putQa(g, q.withImages(ims));
                    return true;
                }
            }
        }
        return false;
    }

    // ================================================================== Sao lưu & xung đột
    static void backups(MainPanel ui) {
        ProjectService ps = ui.project;
        ProjectStore store = ps.store();
        JTabbedPane tabs = new JTabbedPane();
        // bản lưu
        DefaultListModel<ProjectStore.Snapshot> lm = new DefaultListModel<>();
        Runnable reload = () -> {
            lm.clear();
            for (ProjectStore.Snapshot s : store.snapshots()) {
                lm.addElement(s);
            }
        };
        reload.run();
        JList<ProjectStore.Snapshot> list = new JList<>(lm);
        list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                if (v instanceof ProjectStore.Snapshot sn) {
                    setText(sn.label() + "   ·   " + sn.file().getFileName());
                }
                return this;
            }
        });
        JPanel snap = new JPanel(new BorderLayout(0, 4));
        snap.add(Ui.muted("Tự lưu mỗi giờ (giữ 7 ngày) và mỗi ngày (giữ 30 ngày) trong sao-luu/ của thư mục dự án (OneDrive của bạn)."),
                BorderLayout.NORTH);
        snap.add(new JScrollPane(list), BorderLayout.CENTER);
        snap.add(Ui.flow(FlowLayout.LEFT,
                Ui.button("Sao lưu ngay", Icons::save, "Lưu toàn bộ dữ liệu nhóm hiện có", () -> {
                    store.snapshot(true);
                    reload.run();
                    ui.flash("Đã sao lưu");
                }),
                Ui.button("So sánh & khôi phục...", Icons::compare, "Xem khác biệt với bản lưu, chọn mục để lấy lại", () -> {
                    ProjectStore.Snapshot s = list.getSelectedValue();
                    if (s == null) {
                        Ui.hint(list, "Chọn 1 bản lưu");
                        return;
                    }
                    restoreFrom(ui, s);
                }),
                Ui.button("Mở thư mục sao lưu", Icons::folder, "", () -> ProjectTab.open(store.folder("sao-luu")))), BorderLayout.SOUTH);
        tabs.addTab("Bản lưu", snap);

        // Google Sheet
        JTextArea info = new JTextArea("""
                Google Sheet là bảng làm việc chung; dữ liệu gốc luôn còn trên máy mỗi người (Excel của tôi + sao-luu/).

                Mất Sheet / ai đó xóa sheet, cột, dòng / Sheet hỏng:
                  - Tạo Google Sheet mới + dán lại Apps Script (Làm chung > PIC > Google Sheet), dán link mới,
                  - rồi bấm "Dựng lại Google Sheet từ máy này": mọi dữ liệu nhóm máy này có được gửi lên lại, các sheet được vẽ lại.
                  - Gửi mã mời mới cho thành viên (link đổi).
                Sửa sai hàng loạt trên Sheet: tab Bản lưu > So sánh & khôi phục.
                "Dọn dữ liệu" gộp các dòng cũ trong sheet ẩn _data (Sheet nhẹ hơn), không mất gì.
                """);
        info.setEditable(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        JPanel gs = new JPanel(new BorderLayout(0, 4));
        gs.add(new JScrollPane(info), BorderLayout.CENTER);
        gs.add(Ui.flow(FlowLayout.LEFT,
                Ui.button("Dựng lại Google Sheet từ máy này", Icons::refresh, "Gửi lại toàn bộ dữ liệu nhóm máy này đang giữ", () -> {
                    if (!ps.canManage()) {
                        ui.info("Chỉ PIC / PIC phụ.");
                        return;
                    }
                    background(ui, "Đang dựng lại Google Sheet...", () -> ps.sync().rebuildSheet() + " bản ghi đã gửi lại");
                }),
                Ui.button("Dọn dữ liệu Sheet", Icons::clean, "Gộp dòng cũ trong _data", () -> {
                    if (!ps.canManage()) {
                        ui.info("Chỉ PIC / PIC phụ.");
                        return;
                    }
                    background(ui, "Đang dọn...", () -> {
                        new themythofcrawl.project.SheetHub(ps.config().sheetUrl, ps.config().sheetPass).compact();
                        return "Đã dọn dữ liệu Sheet";
                    });
                })), BorderLayout.SOUTH);
        tabs.addTab("Google Sheet", gs);

        // Xung đột
        tabs.addTab("Xung đột", conflictsPanel(ui));
        JDialog[] ref = new JDialog[1];
        JPanel body = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        body.add(tabs, BorderLayout.CENTER);
        body.add(Ui.flow(FlowLayout.RIGHT, Ui.secondaryBtn("Đóng", null, () -> ref[0].dispose())), BorderLayout.SOUTH);
        ref[0] = dialog(ui, "Sao lưu & khôi phục", "Mất một bên thì lấy bên còn lại làm tiếp: Google Sheet <-> Excel / bản lưu trên OneDrive"
                + " của từng người.", body, 880, 560);
        ref[0].setVisible(true);
    }

    interface Job {
        String run() throws Exception;
    }

    static void background(MainPanel ui, String busy, Job job) {
        ui.flash(busy);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return job.run();
            }

            @Override
            protected void done() {
                try {
                    ui.flash(get());
                } catch (Exception e) {
                    ui.info("Không làm được: " + rootMessage(e));
                }
            }
        }.execute();
    }

    private static void restoreFrom(MainPanel ui, ProjectStore.Snapshot s) {
        ProjectService ps = ui.project;
        Map<String, SyncRecord> old;
        try {
            old = ProjectStore.readSnapshot(s.file());
        } catch (IOException e) {
            ui.info("Không đọc được bản lưu: " + e.getMessage());
            return;
        }
        Map<String, SyncRecord> cur = ps.store().records();
        List<SyncRecord> diffs = new ArrayList<>();
        for (SyncRecord r : old.values()) {
            String type = r.type();
            if (!(SyncRecord.MARK.equals(type) || SyncRecord.NOTE.equals(type) || SyncRecord.QANS.equals(type) || SyncRecord.PROJECT.equals(type))) {
                continue;
            }
            SyncRecord c = cur.get(r.id());
            if (c == null || !c.sameContent(r)) {
                diffs.add(r);
            }
        }
        if (diffs.isEmpty()) {
            ui.info("Không có khác biệt (đặt tay, ghi chú, câu trả lời, cài đặt dự án) so với bản lưu này.");
            return;
        }
        DefaultTableModel m = new DefaultTableModel(new Object[] {"Lấy lại", "Mục", "Trong bản lưu", "Hiện tại"}, 0) {
            @Override
            public Class<?> getColumnClass(int c) {
                return c == 0 ? Boolean.class : Object.class;
            }

            @Override
            public boolean isCellEditable(int r, int c) {
                return c == 0;
            }
        };
        for (SyncRecord r : diffs) {
            SyncRecord c = cur.get(r.id());
            m.addRow(new Object[] {true, r.id(), Text.shortPath(Json.write(new LinkedHashMap<>(r.data())), 120),
                c == null ? "(không còn)" : Text.shortPath(Json.write(new LinkedHashMap<>(c.data())), 120)});
        }
        javax.swing.JTable t = new javax.swing.JTable(m);
        Ui.widths(t, 60, 220, 300, 300);
        JDialog[] ref = new JDialog[1];
        JPanel body = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        body.add(MainPanel.scroll(t), BorderLayout.CENTER);
        body.add(Ui.flow(FlowLayout.RIGHT, Ui.secondaryBtn("Đóng", null, () -> ref[0].dispose()),
                Ui.accent("Khôi phục mục đã chọn", t2 -> Icons.undo(t2.accent.onSolid()), t2 -> t2.accent, () -> {
                    List<SyncRecord> chosen = new ArrayList<>();
                    for (int i = 0; i < diffs.size(); i++) {
                        if (Boolean.TRUE.equals(m.getValueAt(i, 0))) {
                            chosen.add(diffs.get(i));
                        }
                    }
                    int n = ps.sync().restore(chosen);
                    ps.store().saveRecords();
                    ps.syncSoon();
                    ui.flash("Đã khôi phục " + n + " mục (gửi lên Sheet ở lần đồng bộ tới)");
                    ref[0].dispose();
                })), BorderLayout.SOUTH);
        ref[0] = dialog(ui, "So sánh & khôi phục", s.label(), body, 980, 520);
        ref[0].setVisible(true);
    }

    private static JPanel conflictsPanel(MainPanel ui) {
        ProjectService ps = ui.project;
        DefaultTableModel m = new DefaultTableModel(new Object[] {"Mục", "Người A", "Giá trị A", "Người B", "Giá trị B"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        javax.swing.JTable t = new javax.swing.JTable(m);
        Ui.widths(t, 220, 100, 230, 100, 230);
        List<SyncRecord> list = new ArrayList<>();
        Runnable fill = () -> {
            list.clear();
            m.setRowCount(0);
            ProjectData d = ps.data();
            if (d == null) {
                return;
            }
            for (SyncRecord c : d.conflicts()) {
                list.add(c);
                m.addRow(new Object[] {c.str("target"), c.str("theirsBy"), c.str("theirs"), c.str("mineBy"), c.str("mine")});
            }
        };
        fill.run();
        java.util.function.Consumer<Boolean> pick = keepFirst -> {
            int i = t.getSelectedRow();
            if (i < 0) {
                Ui.hint(t, "Chọn 1 xung đột");
                return;
            }
            if (!ps.canManage()) {
                ui.info("Chỉ PIC / PIC phụ chọn bên giữ lại.");
                return;
            }
            SyncRecord c = list.get(i);
            String target = c.str("target");
            String json = keepFirst ? c.str("theirs") : c.str("mine");
            Map<String, Object> data;
            try {
                data = Json.parseObject(json);
            } catch (RuntimeException e) {
                data = Map.of();
            }
            SyncRecord cur = ps.store().record(target);
            ps.store().putLocal(SyncRecord.of(target, cur == null ? ps.me() : cur.owner(), cur == null ? "" : cur.account(), data, ps.me()));
            ps.store().putLocal(new SyncRecord(c.id(), SyncRecord.CONFLICT, c.owner(), c.account(), c.data(), System.currentTimeMillis(), ps.me(),
                    true, 0L));
            ps.store().saveRecords();
            ps.syncSoon();
            ui.flash("Đã chọn giá trị của " + (keepFirst ? c.str("theirsBy") : c.str("mineBy")));
            m.removeRow(i);
            list.remove(i);
        };
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.add(Ui.muted("Hai người ghi khác nhau cùng một ô (đặt tay, ghi chú, câu trả lời). PIC / PIC phụ chọn giá trị giữ lại."),
                BorderLayout.NORTH);
        p.add(MainPanel.scroll(t), BorderLayout.CENTER);
        p.add(Ui.flow(FlowLayout.LEFT, Ui.button("Giữ giá trị người A", Icons::check, "", () -> pick.accept(true)),
                Ui.button("Giữ giá trị người B", Icons::check, "", () -> pick.accept(false)),
                Ui.button("Làm mới", Icons::refresh, "", fill)), BorderLayout.SOUTH);
        return p;
    }

    // ================================================================== chuyển dự án
    static void moveProject(MainPanel ui) {
        ProjectService ps = ui.project;
        Path from = ps.store().dir();
        JFileChooser fc = new JFileChooser(suggestOneDrive());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Chọn thư mục OneDrive (hoặc thư mục khác) để chuyển dự án tới");
        if (fc.showOpenDialog(Ui.ownerWindow(ui.root())) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) {
            return;
        }
        Path root = fc.getSelectedFile().toPath();
        Path to = root.resolve(themythofcrawl.store.Workspaces.FOLDER).resolve(from.getFileName().toString());
        if (Files.exists(to)) {
            ui.info("Đã có thư mục " + to + ". Chọn chỗ khác hoặc xóa thư mục đó trước.");
            return;
        }
        if (!ui.confirmDialog("Chép dự án '" + from.getFileName() + "' (mọi workspace tài khoản, báo cáo, sao lưu) sang:\n" + to
                + "\n\nSau đó JCrawl dùng bản mới; bản cũ giữ nguyên (xóa tay khi chắc chắn).")) {
            return;
        }
        String wsName = Path.of(ui.engine.workspaceLocation()).getFileName().toString();
        ui.engine.flush();
        ps.close();
        ui.flash("Đang chép dự án...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                try (Stream<Path> s = Files.walk(from)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        Path dst = to.resolve(from.relativize(p).toString());
                        if (Files.isDirectory(p)) {
                            Files.createDirectories(dst);
                        } else if (!p.getFileName().toString().endsWith(".tmp")) {
                            Files.createDirectories(dst.getParent());
                            Files.copy(p, dst);
                        }
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    ui.workspaces.setRoot(root);
                    ui.openWorkspaceAt(to.resolve(wsName));
                    ui.info("Đã chuyển dự án sang " + to + ".\nTừ giờ mọi thứ (workspace, Excel của tôi, báo cáo, sao lưu, ảnh) nằm trong OneDrive."
                            + "\nBản cũ vẫn ở " + from + " (xóa khi chắc chắn).");
                } catch (Exception e) {
                    ps.workspaceChanged();
                    ui.info("Không chép được: " + rootMessage(e));
                }
            }
        }.execute();
    }

    static java.io.File suggestOneDrive() {
        for (String env : List.of("OneDriveCommercial", "OneDrive", "OneDriveConsumer")) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank() && Files.isDirectory(Path.of(v))) {
                return new java.io.File(v);
            }
        }
        return new java.io.File(System.getProperty("user.home"));
    }
}
