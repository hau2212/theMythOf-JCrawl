package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Jwt;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.Role;
import themythofcrawl.model.RoleScopes;
import themythofcrawl.model.Roles;
import themythofcrawl.model.RuleMode;

/** 'Roles' tab: the role list, the API gốc profile and an inline editor (rules, JWT inspector, bulk paste). */
final class RolesTab {
    final JPanel panel = new JPanel(new BorderLayout());
    private final MainPanel ui;
    private final Engine engine;
    private final RoleModel model = new RoleModel();
    private final JTable table = new JTable(model);
    private final JLabel profileState = new JLabel(" ");
    private final JLabel title = Ui.bold(" ");
    private final JTextField name = new JTextField(26);
    private final JCheckBox enabled = new JCheckBox("Enabled", true);
    private final RuleModel rules = new RuleModel();
    private final JTable rulesTable = new JTable(rules);
    private final JTextArea jwt = new JTextArea(5, 50);
    private final JLabel dirty = new JLabel(" ");
    private int editing = -1;          // role index, -2 = the API gốc profile

    RolesTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        Ui.style(table);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int r = table.getSelectedRow();
                if (r >= 0) {
                    load(r);
                }
            }
        });
        Ui.onDoubleClick(table, () -> { });
        Ui.onPopup(table, e -> roleMenu(true).show(e.getComponent(), e.getX(), e.getY()));
        Ui.widths(table, 35, 55, 160, 360);
        JButton more = new JButton("⚙ Thao tác Role ▾");
        more.addActionListener(e -> roleMenu(false).show(more, 0, more.getHeight()));
        JPanel top = Ui.flow(FlowLayout.LEFT, Ui.button("+ Thêm Role...", this::addRole), Ui.button("🗑 Xóa", this::deleteRoles), more,
                Ui.button("🎛 Bật/Tắt theo phạm vi...", "Tắt / bật role cho toàn bộ, cho từng nhánh hoặc từng API gốc",
                        () -> ui.roleScopeDialog(false, RoleScopes.Scope.GLOBAL, null, null, null)));
        JPanel profile = new JPanel(new BorderLayout(4, 2));
        profile.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, Palette.ui().origAccent),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        JLabel pt = Ui.bold("📌 " + Role.ORIGINAL_PROFILE_NAME);
        pt.setForeground(Palette.ui().origFg);
        profile.add(pt, BorderLayout.NORTH);
        profile.add(profileState, BorderLayout.CENTER);
        profile.add(Ui.flow(FlowLayout.LEFT, Ui.button("✏ Sửa profile API gốc", this::editOriginal),
                Ui.button("Bật / Tắt profile", () -> {
                    Role p = engine.originalProfile();
                    engine.saveOriginalProfile(p.withEnabled(!p.enabled()));
                    refreshProfile();
                })), BorderLayout.SOUTH);
        JPanel north = new JPanel(new BorderLayout(2, 4));
        north.add(top, BorderLayout.NORTH);
        north.add(profile, BorderLayout.SOUTH);
        JPanel left = new JPanel(new BorderLayout(2, 2));
        left.setBorder(BorderFactory.createTitledBorder("Danh sách Role / Profile (Click chọn để sửa bên phải | Chuột phải để Nhân bản / Xóa / Đổi thứ tự)"));
        left.add(north, BorderLayout.NORTH);
        left.add(MainPanel.scroll(table), BorderLayout.CENTER);
        left.add(modeLegend(), BorderLayout.SOUTH);

        Ui.style(rulesTable);
        rulesTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        rulesTable.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JComboBox<>(RuleMode.values())));
        rulesTable.getColumnModel().getColumn(2).setCellRenderer(new ModeRenderer());
        Ui.widths(rulesTable, 180, 360, 130);
        jwt.setEditable(false);
        jwt.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel head = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        head.add(new JLabel("Role Name:"));
        head.add(name);
        head.add(enabled);
        JButton save = Ui.accent("Save Profile Changes", Palette.GREEN, this::save);
        head.add(save);
        head.add(dirty);
        JPanel tools = new JPanel(new GridLayout(2, 5, 4, 4));
        tools.add(Ui.button("+ Header", () -> rules.add(new HeaderRule("X-Header", "value", RuleMode.REPLACE))));
        tools.add(Ui.button("+ Strip Header ([REMOVE])", () -> {
            String n = ui.ask("Header cần xóa khỏi request của role:", "Authorization");
            HeaderRule r = n == null ? null : HeaderRule.of(n, RuleMode.REMOVE_TOKEN, "REMOVE");
            if (r != null) {
                rules.add(r);
            }
        }));
        tools.add(Ui.button("+ Set JWT / Bearer...", this::setJwt));
        tools.add(Ui.button("+ Set Cookie/Session Key...", this::setCookie));
        tools.add(Ui.button("+ Strip Cookie Key...", () -> {
            String k = ui.ask("Cookie / Session key cần xóa:", "JSESSIONID");
            HeaderRule r = k == null ? null : HeaderRule.of(k, RuleMode.REMOVE_TOKEN, "COOKIE_REMOVE");
            if (r != null) {
                rules.add(r);
            }
        }));
        tools.add(Ui.button("Paste Headers...", () -> paste(false)));
        tools.add(Ui.button("Paste Cookies...", () -> paste(true)));
        tools.add(Ui.button("Rule Up", () -> moveRule(-1)));
        tools.add(Ui.button("Rule Down", () -> moveRule(1)));
        tools.add(Ui.button("Remove Rule(s)", this::removeRules));
        JPanel editorTop = new JPanel(new BorderLayout());
        editorTop.add(title, BorderLayout.NORTH);
        editorTop.add(head, BorderLayout.CENTER);
        editorTop.add(tools, BorderLayout.SOUTH);
        JPanel jwtPanel = new JPanel(new BorderLayout());
        jwtPanel.add(new JLabel(" JWT / Token Inspector (tự giải mã mọi token eyJ... trong profile này):"), BorderLayout.NORTH);
        jwtPanel.add(new JScrollPane(jwt), BorderLayout.CENTER);
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, MainPanel.scroll(rulesTable), jwtPanel);
        rightSplit.setResizeWeight(0.65);
        JPanel right = new JPanel(new BorderLayout(4, 4));
        right.setBorder(BorderFactory.createTitledBorder("Chỉnh sửa Profile đang chọn (Header / Cookie / Session / JWT - bấm 'Save Profile Changes' để lưu)"));
        right.add(editorTop, BorderLayout.NORTH);
        right.add(rightSplit, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.42);
        panel.add(split, BorderLayout.CENTER);
        rules.addTableModelListener(e -> {
            dirty.setText("● chưa lưu");
            jwt.setText(Jwt.preview(String.join("\n", rules.list.stream().map(HeaderRule::value).toList())));
            jwt.setCaretPosition(0);
        });
        reload();
        refreshProfile();
        if (model.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private JPanel modeLegend() {
        JPanel p = new JPanel(new GridLayout(3, 2, 6, 2));
        p.setBorder(BorderFactory.createTitledBorder("Các chế độ (cột Mode)"));
        Palette pal = Palette.ui();
        for (RuleMode m : RuleMode.values()) {
            Color[] c = pal.mode(m);
            JLabel chip = new JLabel(" " + m.name() + " ");
            chip.setOpaque(true);
            chip.setBackground(c[0]);
            chip.setForeground(c[1]);
            chip.setFont(chip.getFont().deriveFont(Font.BOLD));
            chip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(c[2]), BorderFactory.createEmptyBorder(1, 4, 1, 4)));
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            row.add(chip);
            row.add(new JLabel(m.hint));
            p.add(row);
        }
        p.add(new JLabel("  Value = [REMOVE] → xóa"));
        return p;
    }

    void reload() {
        model.fireTableDataChanged();
    }

    void refreshCounts() {
        model.fireTableRowsUpdated(0, Math.max(0, model.getRowCount() - 1));
    }

    private void refreshProfile() {
        Role p = engine.originalProfile();
        int n = p.rules().size();
        profileState.setText(!p.enabled() || n == 0 ? "Chưa có rule đang bật - API gốc được gửi đúng như lúc bắt"
                : "Đang áp dụng " + n + " rule cho API gốc (API bắt từ giờ; API cũ: 'Rebuild Roles')");
    }

    private void load(int idx) {
        editing = idx;
        Role r = engine.roles().get(idx);
        title.setText(" Đang sửa: " + r.name());
        name.setText(r.name());
        name.setEditable(true);
        enabled.setSelected(r.enabled());
        rules.set(r.rules());
        dirty.setText(" ");
    }

    private void editOriginal() {
        editing = -2;
        table.clearSelection();
        Role p = engine.originalProfile();
        title.setText(" Đang sửa: " + Role.ORIGINAL_PROFILE_NAME + " (áp lên API gốc - request của Acc Crawl)");
        name.setText(Role.ORIGINAL_PROFILE_NAME);
        name.setEditable(false);
        enabled.setSelected(p.enabled());
        rules.set(p.rules());
        dirty.setText(" ");
    }

    private void save() {
        if (rulesTable.isEditing()) {
            rulesTable.getCellEditor().stopCellEditing();
        }
        List<HeaderRule> clean = new ArrayList<>();
        for (HeaderRule r : rules.list) {
            HeaderRule c = HeaderRule.of(r.name(), r.value(), r.mode().name());
            if (c != null) {
                clean.add(c);
            }
        }
        if (editing == -2) {
            engine.saveOriginalProfile(new Role(Role.ORIGINAL_PROFILE_NAME, enabled.isSelected(), clean));
            refreshProfile();
            ui.flash("Đã lưu profile API gốc (" + clean.size() + " rule)");
        } else if (editing >= 0) {
            String n = name.getText().trim();
            if (n.isEmpty()) {
                ui.info("Tên role không được để trống.");
                return;
            }
            engine.roles().update(editing, new Role(n, enabled.isSelected(), clean));
            engine.saveRoles();
            reload();
            MainPanel.selectRow(table, editing);
            ui.flash("Đã lưu role " + n + " (" + clean.size() + " rule) - API bắt từ giờ dùng rule mới; API cũ: 'Rebuild Roles'");
        }
        dirty.setText("✔ đã lưu");
    }

    private void addRole() {
        String n = ui.ask("Tên role mới:", String.format("%02d_NewRole", engine.roles().count() + 1));
        if (n != null && !n.isBlank()) {
            int i = engine.roles().add(new Role(n.trim(), true, List.of()));
            engine.saveRoles();
            reload();
            MainPanel.selectRow(table, i);
        }
    }

    private void deleteRoles() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0 || !ui.confirmDialog("Xóa " + rows.length + " role?")) {
            return;
        }
        List<Integer> idx = new ArrayList<>();
        for (int r : rows) {
            idx.add(r);
        }
        engine.roles().remove(idx);
        engine.saveRoles();
        reload();
    }

    private JPopupMenu roleMenu(boolean onTable) {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("📋 Nhân bản Role đang chọn (Duplicate)", () -> {
            int r = table.getSelectedRow();
            if (r >= 0) {
                Role src = engine.roles().get(r);
                int i = engine.roles().add(src.withName(src.name() + "_copy"));
                engine.saveRoles();
                reload();
                MainPanel.selectRow(table, i);
            }
        }));
        if (onTable) {
            m.add(Ui.item("🗑 Xóa Role đang chọn", this::deleteRoles));
        }
        m.addSeparator();
        m.add(Ui.item("⬆ Di chuyển lên (Move Up)", () -> moveRole(-1)));
        m.add(Ui.item("⬇ Di chuyển xuống (Move Down)", () -> moveRole(1)));
        m.addSeparator();
        m.add(Ui.item("✅ Bật tất cả Role (Enable All)...", () -> ui.roleScopeDialog(true, RoleScopes.Scope.GLOBAL, null, null,
                engine.roles().all().stream().map(Role::name).toList())));
        m.add(Ui.item("⬜ Tắt tất cả Role (Disable All)...", () -> ui.roleScopeDialog(false, RoleScopes.Scope.GLOBAL, null, null,
                engine.roles().all().stream().map(Role::name).toList())));
        m.add(Ui.item("🎛 Bật / Tắt Role theo phạm vi (nhánh / API)...", () -> ui.roleScopeDialog(false, RoleScopes.Scope.GLOBAL, null, null, null)));
        if (!onTable) {
            m.addSeparator();
            m.add(Ui.item("📂 Import Roles từ JSON...", this::importRoles));
            m.add(Ui.item("💾 Export Roles ra JSON...", this::exportRoles));
            m.addSeparator();
            m.add(Ui.item("⚡ Nạp mẫu 10 Roles chuẩn (Load 10-Role Template)", () -> {
                if (ui.confirmDialog("Thay danh sách role bằng mẫu 10 role chuẩn?")) {
                    engine.roles().setAll(Roles.template());
                    engine.saveRoles();
                    reload();
                }
            }));
        }
        return m;
    }

    private void moveRole(int delta) {
        int r = table.getSelectedRow();
        if (r >= 0) {
            int j = engine.roles().move(r, delta);
            engine.saveRoles();
            reload();
            MainPanel.selectRow(table, j);
        }
    }

    private void importRoles() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("JSON files", "json"));
        if (fc.showOpenDialog(panel) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            List<Role> list = Roles.fromJson(Files.readString(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8));
            Object[] opts = {"Thay toàn bộ", "Thêm vào cuối", "Hủy"};
            int c = JOptionPane.showOptionDialog(panel, "Nạp " + list.size() + " role:", "Import", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
            if (c == 0) {
                engine.roles().setAll(list);
            } else if (c == 1) {
                for (Role r : list) {
                    engine.roles().add(r);
                }
            } else {
                return;
            }
            engine.saveRoles();
            reload();
        } catch (Exception e) {
            ui.error("Import failed: " + e.getMessage());
        }
    }

    private void exportRoles() {
        java.io.File f = ui.save("theMythOfCrawl_roles.json", "JSON", "json");
        if (f != null) {
            try {
                Files.writeString(f.toPath(), Roles.toJson(engine.roles().all()), StandardCharsets.UTF_8);
                ui.flash("Đã xuất " + f.getName());
            } catch (Exception e) {
                ui.error("Export failed: " + e.getMessage());
            }
        }
    }

    private void setJwt() {
        JTextField header = new JTextField("Authorization", 25);
        JTextField prefix = new JTextField("Bearer", 12);
        JTextArea token = new JTextArea(5, 45);
        token.setLineWrap(true);
        JPanel p = new JPanel(new BorderLayout(4, 4));
        JPanel f = new JPanel(new GridLayout(2, 2, 4, 4));
        f.add(new JLabel("Header Name:"));
        f.add(header);
        f.add(new JLabel("Prefix (để trống nếu dùng token thô):"));
        f.add(prefix);
        p.add(f, BorderLayout.NORTH);
        p.add(new JLabel("JWT / Token value (hoặc [REMOVE]):"), BorderLayout.CENTER);
        p.add(new JScrollPane(token), BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(panel, p, "Set JWT / Bearer", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        String t = token.getText().replace("\r", "").replace("\n", "").trim();
        if (t.isEmpty()) {
            return;
        }
        boolean remove = t.equalsIgnoreCase(RuleMode.REMOVE_TOKEN);
        String value = remove || prefix.getText().isBlank() ? t : prefix.getText().trim() + " " + t;
        HeaderRule r = HeaderRule.of(header.getText(), value, remove ? "REMOVE" : "REPLACE");
        if (r != null) {
            rules.add(r);
        }
    }

    private void setCookie() {
        JTextField k = new JTextField("JSESSIONID", 25);
        JTextField v = new JTextField("", 35);
        JPanel p = new JPanel(new GridLayout(4, 1, 4, 4));
        p.add(new JLabel("Cookie / Session Key (vd: JSESSIONID, session, token):"));
        p.add(k);
        p.add(new JLabel("Cookie Value:"));
        p.add(v);
        if (JOptionPane.showConfirmDialog(panel, p, "Set Cookie / Session Key", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            HeaderRule r = HeaderRule.of(k.getText(), v.getText(), "COOKIE_SET");
            if (r != null) {
                rules.add(r);
            }
        }
    }

    private void paste(boolean cookies) {
        JTextArea area = new JTextArea(cookies ? 8 : 12, 60);
        String q = cookies ? "Dán 'Cookie: a=1; b=2' hoặc các cặp a=1 (mỗi dòng / ngăn cách ;):" : "Dán các dòng 'Header: value':";
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(new JLabel(q), BorderLayout.NORTH);
        p.add(new JScrollPane(area), BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(panel, p, cookies ? "Paste Cookies" : "Paste Headers", JOptionPane.OK_CANCEL_OPTION)
                == JOptionPane.OK_OPTION) {
            for (HeaderRule r : cookies ? Roles.parseBulkCookies(area.getText()) : Roles.parseBulkHeaders(area.getText())) {
                rules.add(r);
            }
        }
    }

    private void moveRule(int delta) {
        int r = rulesTable.getSelectedRow();
        int j = r + delta;
        if (r >= 0 && j >= 0 && j < rules.list.size()) {
            HeaderRule t = rules.list.get(r);
            rules.list.set(r, rules.list.get(j));
            rules.list.set(j, t);
            rules.fireTableDataChanged();
            rulesTable.setRowSelectionInterval(j, j);
        }
    }

    private void removeRules() {
        int[] rows = rulesTable.getSelectedRows();
        for (int i = rows.length - 1; i >= 0; i--) {
            rules.list.remove(rows[i]);
        }
        rules.fireTableDataChanged();
    }

    private final class RoleModel extends AbstractTableModel {
        private final String[] cols = {"#", "Enabled", "Role Name", "Rules (Header / Cookie / JWT)"};

        @Override
        public int getRowCount() {
            return engine.roles().count();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 1 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 1;
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (c == 1) {
                engine.roles().setEnabled(r, Boolean.TRUE.equals(v));
                engine.saveRoles();
                fireTableRowsUpdated(r, r);
            }
        }

        @Override
        public Object getValueAt(int r, int c) {
            if (r >= engine.roles().count()) {
                return null;
            }
            Role role = engine.roles().get(r);
            return switch (c) {
                case 0 -> String.valueOf(r + 1);
                case 1 -> role.enabled();
                case 2 -> role.name();
                default -> role.rules().isEmpty() ? "(không có rule - gửi giống API gốc)" : role.rulesSummary();
            };
        }
    }

    private static final class RuleModel extends AbstractTableModel {
        final List<HeaderRule> list = new ArrayList<>();
        private final String[] cols = {"Header / Cookie Key", "Value ([REMOVE] = xóa)", "Mode"};

        void set(List<HeaderRule> r) {
            list.clear();
            list.addAll(r);
            fireTableDataChanged();
        }

        void add(HeaderRule r) {
            list.add(r);
            fireTableRowsInserted(list.size() - 1, list.size() - 1);
        }

        @Override
        public int getRowCount() {
            return list.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return true;
        }

        @Override
        public Object getValueAt(int r, int c) {
            HeaderRule h = list.get(r);
            return c == 0 ? h.name() : c == 1 ? h.value() : h.mode();
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            HeaderRule h = list.get(r);
            String s = v == null ? "" : v.toString();
            list.set(r, switch (c) {
                case 0 -> new HeaderRule(s.trim(), h.value(), h.mode());
                case 1 -> new HeaderRule(h.name(), s, h.mode());
                default -> new HeaderRule(h.name(), h.value(), v instanceof RuleMode m ? m : RuleMode.of(s, h.value()));
            });
            fireTableRowsUpdated(r, r);
        }
    }

    private static final class ModeRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            if (v instanceof RuleMode m && !sel) {
                Color[] c = Palette.of(t.getBackground(), t.getForeground()).mode(m);
                l.setBackground(c[0]);
                l.setForeground(c[1]);
            }
            l.setFont(l.getFont().deriveFont(Font.BOLD));
            return l;
        }
    }
}
