package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

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
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

import themythofcrawl.excel.TargetList;
import themythofcrawl.project.Invite;
import themythofcrawl.project.ProjectConfig;
import themythofcrawl.project.ProjectData;
import themythofcrawl.project.ProjectStore;
import themythofcrawl.project.SheetHub;
import themythofcrawl.store.FileStorage;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.theme.Theme;

/**
 * V_11.4 "Làm chung": step-by-step setup for the PIC (project, Google Sheet, project list, team & roles, invites) and for a
 * member (paste the invite, choose the OneDrive folder, join). Every step has a check button; the wizard remembers
 * nothing secret outside the person's own project settings.
 */
final class TeamWizard {
    private TeamWizard() {
    }

    static void open(MainPanel ui) {
        ProjectService ps = ui.project;
        if (!ps.ready()) {
            ps.workspaceChanged();
        }
        JDialog dlg = new JDialog(Ui.ownerWindow(ui.root()), "Làm chung", java.awt.Dialog.ModalityType.MODELESS);
        CardLayout cards = new CardLayout();
        JPanel deck = new JPanel(cards);
        deck.add(chooser(ui, cards, deck), "start");
        deck.add(picSteps(ui, dlg), "pic");
        deck.add(memberSteps(ui, dlg), "member");
        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        root.add(Ui.dialogHeader("Làm chung", "Mỗi người làm trong workspace riêng (trên OneDrive của mình); Google Sheet là nơi đồng bộ"
                + " gần như tức thì; mỗi người tự có Excel đầy đủ của cả nhóm."), BorderLayout.NORTH);
        root.add(deck, BorderLayout.CENTER);
        dlg.setContentPane(root);
        dlg.setSize(new Dimension(Theme.current().scaled(900), Theme.current().scaled(640)));
        dlg.setLocationRelativeTo(ui.root());
        if (ps.ready() && ps.config().syncOn && !ps.config().sheetUrl.isBlank()) {
            cards.show(deck, ps.canManage() ? "pic" : "member");
        }
        dlg.setVisible(true);
    }

    private static JPanel chooser(MainPanel ui, CardLayout cards, JPanel deck) {
        JPanel p = new JPanel(new GridLayout(1, 2, Theme.SPACE_3, 0));
        JButton pic = Ui.accent("Tôi là PIC (người quản lý)", t -> Icons.target(t.accent.onSolid()), t -> t.accent, () -> cards.show(deck, "pic"));
        JButton mem = Ui.accent("Tôi là Thành viên", t -> Icons.users(t.accent.onSolid(), 14), t -> t.accent, () -> cards.show(deck, "member"));
        JPanel a = new JPanel(new BorderLayout(0, 6));
        a.add(pic, BorderLayout.NORTH);
        a.add(text("""
                PIC: tạo dự án, tạo Google Sheet chung (dán Apps Script 1 lần), chọn danh sách chuẩn, thêm thành viên và
                phân quyền (PIC phụ / Thành viên), giao tài khoản, tạo mã mời, xử lý xung đột, gửi báo cáo cho khách hàng.

                Một người vừa là PIC vừa crawl: chọn PIC rồi tự giao tài khoản cho mình.
                """), BorderLayout.CENTER);
        JPanel b = new JPanel(new BorderLayout(0, 6));
        b.add(mem, BorderLayout.NORTH);
        b.add(text("""
                Thành viên: nhận mã mời từ PIC, chọn thư mục OneDrive của bạn, bấm Tham gia. JCrawl tạo workspace cho tài khoản
                được giao, nhận danh sách chuẩn và tự đồng bộ. Bạn chỉ việc đăng nhập trình duyệt bằng tài khoản đó và crawl.
                """), BorderLayout.CENTER);
        p.add(a);
        p.add(b);
        return p;
    }

    static JTextArea text(String s) {
        JTextArea t = new JTextArea(s);
        t.setEditable(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setOpaque(false);
        return t;
    }

    /** Left: clickable step list; right: the step. */
    private static JPanel stepper(List<String> names, List<JPanel> steps) {
        CardLayout cl = new CardLayout();
        JPanel right = new JPanel(cl);
        for (int i = 0; i < steps.size(); i++) {
            right.add(steps.get(i), String.valueOf(i));
        }
        DefaultListModel<String> lm = new DefaultListModel<>();
        for (int i = 0; i < names.size(); i++) {
            lm.addElement(names.get(i));
        }
        JList<String> list = new JList<>(lm);
        list.setSelectedIndex(0);
        list.addListSelectionListener(e -> {
            if (list.getSelectedIndex() >= 0) {
                cl.show(right, String.valueOf(list.getSelectedIndex()));
            }
        });
        JPanel nav = Ui.flow(FlowLayout.RIGHT,
                Ui.secondaryBtn("Quay lại", null, () -> list.setSelectedIndex(Math.max(0, list.getSelectedIndex() - 1))),
                Ui.button("Tiếp", Icons::plus, "", () -> list.setSelectedIndex(Math.min(lm.size() - 1, list.getSelectedIndex() + 1))));
        JPanel main = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        JScrollPane ls = new JScrollPane(list);
        ls.setPreferredSize(new Dimension(Theme.current().scaled(190), 10));
        main.add(ls, BorderLayout.WEST);
        JPanel r = new JPanel(new BorderLayout(0, 4));
        r.add(right, BorderLayout.CENTER);
        r.add(nav, BorderLayout.SOUTH);
        main.add(r, BorderLayout.CENTER);
        return main;
    }

    // ================================================================== PIC
    private static JPanel picSteps(MainPanel ui, JDialog dlg) {
        ProjectService ps = ui.project;
        ProjectConfig cfg = ps.config();
        List<String> names = List.of("A. Dự án", "B. Lưu trên OneDrive", "C. Google Sheet", "D. Danh sách chuẩn", "E. Thành viên & quyền",
                "F. Mã mời", "G. Theo dõi");
        List<JPanel> steps = new ArrayList<>();

        // A
        JTextField pname = new JTextField(cfg.name, 26);
        JTextField me = new JTextField(ps.me(), 18);
        DefaultTableModel acc = new DefaultTableModel(new Object[] {"Ký hiệu", "Tên tài khoản (vd admin, staff)"}, 0);
        ProjectData d0 = ps.data();
        if (d0 != null) {
            for (String l : d0.letters()) {
                ProjectConfig.Account a = cfg.account(l);
                acc.addRow(new Object[] {l, a == null ? "" : a.name()});
            }
        }
        javax.swing.JTable accT = new javax.swing.JTable(acc);
        JPanel a = page("Đặt tên dự án và tên của bạn. Mỗi tài khoản có ký hiệu: A = tài khoản cao nhất (cha), B, C... Tài khoản tiếp"
                        + " theo: Excel > Dự án > Tài khoản > Tài khoản tiếp theo.",
                Ui.flow(FlowLayout.LEFT, Ui.bold("Tên dự án:"), pname, Ui.bold("Tên bạn:"), me), new JScrollPane(accT),
                Ui.flow(FlowLayout.RIGHT, Ui.accent("Lưu", t -> Icons.save(t.accent.onSolid()), t -> t.accent, () -> {
                    cfg.name = pname.getText().trim().isEmpty() ? cfg.name : pname.getText().trim();
                    cfg.me = me.getText().trim().isEmpty() ? cfg.me : me.getText().trim();
                    for (int i = 0; i < acc.getRowCount(); i++) {
                        String l = String.valueOf(acc.getValueAt(i, 0)).trim();
                        String n = String.valueOf(acc.getValueAt(i, 1)).trim();
                        ProjectConfig.Account old = cfg.ensureAccount(l, n);
                        cfg.accounts.set(cfg.accounts.indexOf(old), new ProjectConfig.Account(l, n, old.owner()));
                    }
                    if (cfg.member(cfg.me) == null) {
                        cfg.putMember(new ProjectConfig.Member(cfg.me, ProjectConfig.Role.PIC, List.of(ps.myLetter()), ""));
                    }
                    ps.saveConfig("Cài đặt dự án");
                    Ui.hint(pname, "Đã lưu");
                })));
        steps.add(a);

        // B
        JLabel where = Ui.muted(ps.store().dir().toString());
        steps.add(page("""
                        Dữ liệu của bạn (workspace, Excel của tôi, báo cáo, ảnh, sao lưu) nên nằm trong OneDrive của chính bạn: mất máy vẫn còn,
                        có lịch sử phiên bản. Mỗi người tự lưu trên OneDrive của mình, không share workspace cho ai.
                        Nếu thư mục dưới đây chưa nằm trong OneDrive: bấm "Chuyển dự án sang OneDrive...".""",
                Ui.flow(FlowLayout.LEFT, Ui.bold("Thư mục dự án:"), where),
                Ui.flow(FlowLayout.LEFT, Ui.button("Chuyển dự án sang OneDrive...", Icons::move, "", () -> ProjectDialogs.moveProject(ui)),
                        Ui.button("Mở thư mục", Icons::folder, "", () -> ProjectTab.open(ps.store().dir()))),
                null));

        // C
        JTextField url = new JTextField(cfg.sheetUrl, 46);
        JTextField pass = new JTextField(cfg.sheetPass.isEmpty() ? randomPass() : cfg.sheetPass, 18);
        JCheckBox on = new JCheckBox("Bật đồng bộ (khoảng " + cfg.syncSeconds + " giây / lần)", cfg.syncOn || cfg.sheetUrl.isEmpty());
        JLabel check = Ui.muted(" ");
        steps.add(page("""
                        1. Bấm "Mở Google Sheets" -> tạo bảng tính trống (đặt tên dự án).
                        2. Trong Sheet: Tiện ích mở rộng (Extensions) -> Apps Script -> xóa mã mẫu -> bấm "Copy mã Apps Script" ở đây -> dán -> Lưu.
                        3. Triển khai (Deploy) -> Triển khai mới -> Loại: Ứng dụng web. Thực thi với tư cách: Tôi. Ai có quyền: Bất kỳ ai.
                        4. Cho phép quyền khi Google hỏi -> copy URL ứng dụng web -> dán vào ô dưới -> "Kiểm tra kết nối".
                        5. Chia sẻ Sheet cho nhóm (Người chỉnh sửa). Khách hàng: chỉ chia sẻ nếu muốn họ trả lời trên Sheet.
                        Mã dự án là mật khẩu của Sheet (lần kết nối đầu tiên sẽ khóa Sheet bằng mã này).""",
                Ui.flow(FlowLayout.LEFT, Ui.button("Mở Google Sheets", t -> Icons.globe(t.fg), "", () -> browse("https://sheets.new")),
                        Ui.button("Copy mã Apps Script", t -> Icons.copy(t.fg), "", () -> {
                            Ui.copyToClipboard(SheetHub.script());
                            ui.flash("Đã copy mã Apps Script (" + SheetHub.script().length() + " ký tự)");
                        })),
                fields(Ui.flow(FlowLayout.LEFT, Ui.bold("URL ứng dụng web:"), url), Ui.flow(FlowLayout.LEFT, Ui.bold("Mã dự án:"), pass, on), check),
                Ui.flow(FlowLayout.RIGHT, Ui.button("Kiểm tra kết nối", Icons::refresh, "", () -> {
                    check.setText("Đang kiểm tra...");
                    new SwingWorker<String, Void>() {
                        @Override
                        protected String doInBackground() throws Exception {
                            SheetHub.Hello h = new SheetHub(url.getText().trim(), pass.getText().trim()).hello(cfg.id, cfg.name);
                            if (!h.project().isEmpty() && !h.project().equals(cfg.id)) {
                                return "Sheet này đã thuộc dự án khác (" + h.name() + "): tạo Sheet mới";
                            }
                            return h.created() ? "Đã khóa Sheet bằng mã dự án. Kết nối được." : "Kết nối được Sheet của dự án " + h.name() + ".";
                        }

                        @Override
                        protected void done() {
                            try {
                                check.setText(get());
                            } catch (Exception e) {
                                check.setText("Lỗi: " + ProjectDialogs.rootMessage(e));
                            }
                        }
                    }.execute();
                }), Ui.accent("Lưu & bật", t -> Icons.check(t.accent.onSolid()), t -> t.accent, () -> {
                    boolean changed = !url.getText().trim().equals(cfg.sheetUrl);
                    cfg.sheetUrl = url.getText().trim();
                    cfg.sheetPass = pass.getText().trim();
                    cfg.syncOn = on.isSelected() && !cfg.sheetUrl.isEmpty();
                    if (cfg.member(ps.me()) == null) {
                        cfg.putMember(new ProjectConfig.Member(ps.me(), ProjectConfig.Role.PIC, List.of(ps.myLetter()), ""));
                    }
                    ps.saveConfig("Google Sheet của nhóm");
                    if (changed) {
                        ps.sync().resetSent();
                    }
                    ps.sync().start();
                    ps.syncSoon();
                    check.setText(cfg.syncOn ? "Đã bật đồng bộ." : "Đã lưu (đồng bộ đang tắt).");
                }))));

        // D
        JRadioButton m1 = new JRadioButton(ProjectConfig.BaselineMode.STANDARD.label, cfg.baseline == ProjectConfig.BaselineMode.STANDARD);
        JRadioButton m2 = new JRadioButton(ProjectConfig.BaselineMode.FROM_A.label, cfg.baseline == ProjectConfig.BaselineMode.FROM_A);
        JRadioButton m3 = new JRadioButton(ProjectConfig.BaselineMode.BOTH.label, cfg.baseline == ProjectConfig.BaselineMode.BOTH);
        ButtonGroup bg = new ButtonGroup();
        bg.add(m1);
        bg.add(m2);
        bg.add(m3);
        JCheckBox share = new JCheckBox("Chia sẻ chi tiết cho nhóm: request / response (đã che token, cookie, mật khẩu) + Cây của từng người",
                cfg.shareDetails);
        share.setToolTipText("Tắt: chỉ đồng bộ trạng thái, lỗi, tag, kết quả vai trò, Q&A. Bật: mọi người mở được workspace của nhau ở chế độ chỉ xem.");
        TargetList tl = ui.engine.targets();
        steps.add(page("""
                        Danh sách chuẩn = danh sách API mọi tài khoản đối chiếu. Có file Excel chuẩn thì dùng file đó; không có thì dựng từ API
                        tài khoản A đã crawl. Tài khoản con thấy đủ mọi dòng (dòng chưa gọi = Missing). API tài khoản khác tìm thêm hiện
                        "Chỉ B có" để PIC duyệt (chuột phải dòng > Thêm vào danh sách chuẩn).""",
                fields(m1, m2, m3, Ui.muted(tl == null ? "Chưa nạp file Excel chuẩn trong workspace này." : "File chuẩn: " + tl.size() + " dòng."),
                        share, Ui.muted("Bật chia sẻ chi tiết: body vẫn có thể chứa dữ liệu thật của khách hàng (email, tên...). Chỉ bật khi được phép;"
                                + " không bao giờ có trong file gửi khách hàng.")),
                Ui.flow(FlowLayout.LEFT, Ui.button("Nạp file Excel chuẩn...", t -> Icons.excel(t.fg, 13), "", () -> ui.excel.load()),
                        Ui.button("Cột...", Icons::sliders, "", () -> ProjectDialogs.columns(ui, null)),
                        Ui.button("Sổ dự án...", t -> Icons.excel(t.fg, 13), "", () -> ProjectDialogs.sheets(ui))),
                Ui.flow(FlowLayout.RIGHT, Ui.accent("Lưu", t -> Icons.save(t.accent.onSolid()), t -> t.accent, () -> {
                    if (share.isSelected() != cfg.shareDetails) {
                        if (!ps.canManage()) {
                            ui.info("Chỉ PIC / PIC phụ bật / tắt chia sẻ chi tiết.");
                            share.setSelected(cfg.shareDetails);
                        } else if (!share.isSelected() || ui.confirmDialog("Bật chia sẻ request / response (đã che token, cookie, mật khẩu) và Cây"
                                + " cho cả nhóm?\nNội dung lưu trong thư mục Drive của dự án và máy từng thành viên.")) {
                            cfg.shareDetails = share.isSelected();
                        } else {
                            share.setSelected(false);
                        }
                    }
                    cfg.baseline = m1.isSelected() ? ProjectConfig.BaselineMode.STANDARD
                            : m2.isSelected() ? ProjectConfig.BaselineMode.FROM_A : ProjectConfig.BaselineMode.BOTH;
                    TargetList cur = ui.engine.targets();
                    if (cur != null) {
                        cfg.targetsJson = cur.toJson();
                    }
                    ps.saveConfig("Danh sách chuẩn: " + cfg.baseline.label);
                    ui.flash("Đã lưu danh sách chuẩn");
                }))));

        // E
        DefaultTableModel mem = new DefaultTableModel(new Object[] {"Tên", "Vai trò (PIC / PIC phụ / Thành viên)", "Tài khoản (vd B, C)", "Ghi chú"}, 0);
        for (ProjectConfig.Member x : cfg.members) {
            mem.addRow(new Object[] {x.name(), x.role().label, String.join(", ", x.accounts()), x.note()});
        }
        javax.swing.JTable memT = new javax.swing.JTable(mem);
        JComboBox<String> roleBox = new JComboBox<>(new String[] {"PIC", "PIC phụ", "Thành viên"});
        memT.getColumnModel().getColumn(1).setCellEditor(new javax.swing.DefaultCellEditor(roleBox));
        steps.add(page("Thêm từng người, vai trò và tài khoản được giao. PIC: toàn quyền. PIC phụ: như PIC trừ xóa dự án / đổi PIC / đổi PIC phụ"
                        + " (khi PIC vắng: PIC phụ bấm Nhận quyền PIC). Thành viên: crawl tài khoản được giao, tạo Q&A.",
                new JScrollPane(memT),
                Ui.flow(FlowLayout.LEFT, Ui.button("Thêm người", Icons::plus, "", () -> mem.addRow(new Object[] {"", "Thành viên", "", ""})),
                        Ui.button("Xóa người", t -> Icons.trash(t.danger.text()), "", () -> {
                            int i = memT.getSelectedRow();
                            if (i >= 0) {
                                mem.removeRow(i);
                            }
                        }),
                        Ui.button("Nhận quyền PIC (PIC phụ)", t -> Icons.shield(t.fg, 13), "PIC vắng / rời dự án: PIC phụ nhận quyền", () -> {
                            if (cfg.takeOverPic(ps.me())) {
                                ps.saveConfig(ps.me() + " nhận quyền PIC");
                                ui.flash("Bạn là PIC của dự án");
                            } else {
                                ui.info("Chỉ PIC phụ nhận được quyền PIC.");
                            }
                        })),
                Ui.flow(FlowLayout.RIGHT, Ui.accent("Lưu thành viên", t -> Icons.save(t.accent.onSolid()), t -> t.accent, () -> {
                    if (memT.isEditing()) {
                        memT.getCellEditor().stopCellEditing();
                    }
                    boolean pic = cfg.isPic(ps.me());
                    List<ProjectConfig.Member> list = new ArrayList<>();
                    for (int i = 0; i < mem.getRowCount(); i++) {
                        String n = String.valueOf(mem.getValueAt(i, 0)).trim();
                        if (n.isEmpty()) {
                            continue;
                        }
                        String r = String.valueOf(mem.getValueAt(i, 1));
                        ProjectConfig.Role role = r.startsWith("PIC phụ") ? ProjectConfig.Role.DEPUTY : r.startsWith("PIC") ? ProjectConfig.Role.PIC
                                : ProjectConfig.Role.MEMBER;
                        ProjectConfig.Member old = cfg.member(n);
                        if (!pic && (role != ProjectConfig.Role.MEMBER || old != null && old.role() != ProjectConfig.Role.MEMBER)
                                && (old == null || old.role() != role)) {
                            ui.info("Chỉ PIC đổi PIC / PIC phụ.");
                            return;
                        }
                        List<String> letters = new ArrayList<>();
                        for (String x : String.valueOf(mem.getValueAt(i, 2)).split("[,; ]+")) {
                            if (!x.isBlank()) {
                                letters.add(x.trim().toUpperCase(java.util.Locale.ROOT));
                            }
                        }
                        list.add(new ProjectConfig.Member(n, role, letters, String.valueOf(mem.getValueAt(i, 3) == null ? "" : mem.getValueAt(i, 3))));
                    }
                    cfg.members.clear();
                    for (ProjectConfig.Member x : list) {
                        cfg.putMember(x);
                    }
                    ps.saveConfig("Thành viên & quyền: " + list.size() + " người");
                    ui.flash("Đã lưu " + list.size() + " người");
                }))));

        // F
        JComboBox<String> who = new JComboBox<>();
        Runnable reloadWho = () -> {
            who.removeAllItems();
            for (ProjectConfig.Member x : cfg.members) {
                who.addItem(x.name());
            }
        };
        reloadWho.run();
        JTextArea code = new JTextArea(6, 60);
        code.setLineWrap(true);
        steps.add(page("""
                        Mã mời chứa link Google Sheet + mã dự án + tài khoản được giao + danh sách chuẩn. Gửi RIÊNG cho từng người (chat / mail),
                        không đăng nơi công khai. Muốn thu hồi: tạo Google Sheet mới (bước C) rồi gửi mã mời mới.""",
                Ui.flow(FlowLayout.LEFT, Ui.bold("Người nhận:"), who, Ui.button("Làm mới danh sách", Icons::refresh, "", reloadWho),
                        Ui.accent("Tạo mã mời", t -> Icons.key(t.accent.onSolid(), 14), t -> t.accent, () -> {
                            String n = (String) who.getSelectedItem();
                            ProjectConfig.Member x = n == null ? null : cfg.member(n);
                            if (x == null || cfg.sheetUrl.isBlank()) {
                                Ui.hint(code, cfg.sheetUrl.isBlank() ? "Làm bước C (Google Sheet) trước" : "Thêm người ở bước E trước");
                                return;
                            }
                            TargetList cur = ui.engine.targets();
                            String tj = cur != null ? cur.toJson() : cfg.targetsJson;
                            code.setText(new Invite(cfg.id, cfg.name, cfg.sheetUrl, cfg.sheetPass, x.name(), x.role(), x.accounts(), tj,
                                    System.currentTimeMillis()).encode());
                            code.selectAll();
                        }),
                        Ui.button("Copy", t -> Icons.copy(t.fg), "", () -> {
                            Ui.copyToClipboard(code.getText());
                            ui.flash("Đã copy mã mời");
                        })),
                new JScrollPane(code), null));

        // G
        JLabel st = Ui.muted(" ");
        steps.add(page("""
                        Hằng ngày: Excel > Dự án cho thấy tiến độ từng tài khoản, ai đang làm gì (Doing), Q&A chờ trả lời, xung đột (Sao lưu... >
                        Xung đột). Mỗi người có Excel của tôi trong OneDrive của mình (Báo cáo > Mở Excel của tôi). Gửi khách hàng: Báo cáo >
                        Gửi khách hàng; khi họ trả lời: Báo cáo > Nhập phản hồi.""",
                st, Ui.flow(FlowLayout.LEFT, Ui.button("Đồng bộ ngay", Icons::refresh, "", () -> {
                    ps.syncSoon();
                    st.setText(ps.sync().status().state().label + " · " + ps.sync().status().text());
                }), Ui.button("Mở Excel của tôi", t -> Icons.excel(t.fg, 13), "", () -> ProjectTab.open(ps.store().mirrorFile())),
                        Ui.button("Sao lưu & xung đột...", Icons::save, "", () -> ProjectDialogs.backups(ui))), null));
        return stepper(names, steps);
    }

    // ================================================================== member
    private static JPanel memberSteps(MainPanel ui, JDialog dlg) {
        List<String> names = List.of("1. Dán mã mời", "2. Thư mục OneDrive", "3. Tham gia", "4. Crawl");
        List<JPanel> steps = new ArrayList<>();
        JTextArea code = new JTextArea(6, 60);
        code.setLineWrap(true);
        JLabel read = Ui.muted(" ");
        Invite[] inv = new Invite[1];
        steps.add(page("PIC gửi bạn một mã mời (bắt đầu bằng " + Invite.PREFIX + "). Dán toàn bộ vào ô dưới rồi bấm Đọc mã.",
                new JScrollPane(code), read, Ui.flow(FlowLayout.RIGHT, Ui.accent("Đọc mã", t -> Icons.key(t.accent.onSolid(), 14), t -> t.accent, () -> {
                    try {
                        inv[0] = Invite.decode(code.getText());
                        read.setText("Dự án: " + inv[0].projectName() + " · bạn: " + inv[0].member() + " (" + inv[0].role().label
                                + ") · tài khoản: " + String.join(", ", inv[0].accounts()) + (inv[0].targetsJson() == null ? "" : " · có danh sách chuẩn"));
                    } catch (IllegalArgumentException e) {
                        read.setText(e.getMessage());
                    }
                }))));
        JTextField folder = new JTextField(ProjectDialogs.suggestOneDrive().toPath().resolve("JCrawl").toString(), 46);
        steps.add(page("""
                        Chọn thư mục trong OneDrive CỦA BẠN (không phải thư mục share của PIC). JCrawl tạo workspace riêng của bạn ở đó:
                        mất máy vẫn còn, và có Excel đầy đủ của cả nhóm tự cập nhật.""",
                Ui.flow(FlowLayout.LEFT, Ui.bold("Thư mục:"), folder, Ui.button("Chọn...", Icons::folder, "", () -> {
                    JFileChooser fc = new JFileChooser(ProjectDialogs.suggestOneDrive());
                    fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    if (fc.showOpenDialog(dlg) == JFileChooser.APPROVE_OPTION) {
                        folder.setText(fc.getSelectedFile().getAbsolutePath());
                    }
                })), null, null));
        JLabel joined = Ui.muted(" ");
        steps.add(page("Bấm Tham gia: tạo workspace cho tài khoản được giao (có sẵn danh sách chuẩn), lưu link Sheet và bật đồng bộ.",
                joined, null, Ui.flow(FlowLayout.RIGHT, Ui.accent("Tham gia", t -> Icons.check(t.accent.onSolid()), t -> t.accent, () -> {
                    if (inv[0] == null) {
                        Ui.hint(joined, "Làm bước 1 (Đọc mã) trước");
                        return;
                    }
                    try {
                        join(ui, inv[0], Path.of(folder.getText().trim()));
                        joined.setText("Đã tham gia dự án " + inv[0].projectName() + ". Workspace: " + ui.engine.workspaceLocation());
                    } catch (RuntimeException e) {
                        joined.setText("Không tham gia được: " + e.getMessage());
                    }
                }))));
        steps.add(page("""
                        Đăng nhập trình duyệt (qua Burp) bằng tài khoản được giao rồi crawl như bình thường.
                        - Excel > Dự án: mọi API của dự án, dòng bạn chưa gọi = Missing; bật Cây > ... > "Hiện API tài khoản trên chưa gọi".
                        - Q&A: chuột phải API > Hỏi đáp; ảnh và câu trả lời tự đồng bộ.
                        - Đèn "Đồng bộ" ở Excel > Dự án: xanh = ổn, vàng = đang chờ / mất mạng (tự gửi lại), đỏ = lỗi (bấm để xem).
                        - Trước khi tắt Burp: bấm Đồng bộ ngay để gửi nốt.""",
                Ui.flow(FlowLayout.LEFT, Ui.button("Đồng bộ ngay", Icons::refresh, "", () -> ui.project.syncSoon()),
                        Ui.button("Mở Excel của tôi", t -> Icons.excel(t.fg, 13), "", () -> ProjectTab.open(ui.project.store().mirrorFile()))),
                null, null));
        return stepper(names, steps);
    }

    /** Creates this member's workspace(s) for the invite, stores the project settings there, opens it and starts syncing. */
    static void join(MainPanel ui, Invite inv, Path folder) {
        String letter = inv.accounts().isEmpty() ? "B" : inv.accounts().get(0);
        ui.workspaces.setRoot(folder);
        FileStorage st = ui.workspaces.create(inv.projectName(), letter + " - " + inv.member());
        Workspaces.setMeta(st.dir(), "letter", letter);
        Workspaces.setMeta(st.dir(), "project_id", inv.projectId());
        Workspaces.setMeta(st.dir(), "account", letter);
        ProjectStore store = ProjectStore.open(ProjectStore.projectDirOf(st.dir()));
        ProjectConfig c = store.config();
        c.id = inv.projectId();
        c.name = inv.projectName();
        c.sheetUrl = inv.sheetUrl();
        c.sheetPass = inv.sheetPass();
        c.me = inv.member();
        c.syncOn = true;
        c.targetsJson = inv.targetsJson();
        c.putMember(new ProjectConfig.Member(inv.member(), inv.role(), inv.accounts(), ""));
        store.ensureLayout();
        store.save();
        ui.project.close();
        ui.openWorkspaceAt(st.dir());
        if (inv.targetsJson() != null) {
            try {
                ui.engine.setTargets(TargetList.fromJson(inv.targetsJson()));
            } catch (RuntimeException ignored) {
                // the list also arrives with the project settings
            }
        }
        ui.project.workspaceChanged();
        ui.project.syncSoon();
        ui.flash("Đã tham gia " + inv.projectName() + " · tài khoản " + letter);
    }

    // ================================================================== helpers
    private static JPanel page(String help, java.awt.Component a, java.awt.Component b, java.awt.Component c) {
        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        p.add(text(help), BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        if (a != null) {
            mid.add(a, BorderLayout.NORTH);
        }
        if (b != null) {
            mid.add(b, BorderLayout.CENTER);
        }
        p.add(mid, BorderLayout.CENTER);
        if (c != null) {
            p.add(c, BorderLayout.SOUTH);
        }
        return p;
    }

    private static JPanel fields(java.awt.Component... cs) {
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 4));
        for (java.awt.Component c : cs) {
            p.add(c);
        }
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(p, BorderLayout.NORTH);
        return wrap;
    }

    static String randomPass() {
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(abc.charAt(r.nextInt(abc.length())));
            if (i % 4 == 3 && i < 15) {
                sb.append('-');
            }
        }
        return sb.toString();
    }

    private static void browse(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            Ui.copyToClipboard(url);
        }
    }
}
