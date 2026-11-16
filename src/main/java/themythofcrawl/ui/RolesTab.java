package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
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
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Jwt;
import themythofcrawl.core.Text;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.Role;
import themythofcrawl.model.RoleScopes;
import themythofcrawl.model.Roles;
import themythofcrawl.model.RuleMode;
import themythofcrawl.ui.kit.Card;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SectionHeader;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.kit.ThemedTree;
import themythofcrawl.ui.theme.Theme;

/** 'Roles' tab: pinned API gốc Card, avatar + chip role list, inline editor, and syntax-colored JWT inspector. */
final class RolesTab {
    private static final Pattern EXP_PATTERN = Pattern.compile("\"exp\"\\s*:\\s*(\\d{9,13})");

    final JPanel panel = new JPanel(new BorderLayout());
    private final MainPanel ui;
    private final Engine engine;
    private final RoleModel model = new RoleModel();
    private final ThemedTable table = new ThemedTable(model) {
        @Override
        public void updateUI() {
            super.updateUI();
            setRowHeight(Theme.current().scaled(36));
        }
    };
    private final Card profileCard = new Card(t -> t.orig, true);
    private final JLabel profileState = Ui.muted(" ");
    private final Pill profileBadge = new Pill("Bật", t -> t.orig, t -> Icons.pin(t.orig.text(), 11));
    private final JLabel title = Ui.bold(" ");
    private final JTextField name = new JTextField(26);
    private final JCheckBox enabled = new JCheckBox("Enabled", true);
    private final RuleModel rules = new RuleModel();
    private final ThemedTable rulesTable = new ThemedTable(rules);
    private final Pill expBadge = new Pill("", t -> t.danger, t -> Icons.warn(t.danger.text(), 11));
    private final JTextPane jwt = new JTextPane() {
        @Override
        public void updateUI() {
            super.updateUI();
            setFont(Theme.current().monoFont);
            if (lastJwtPreview != null) {
                renderJwt(lastJwtPreview);
            }
        }
    };
    private final JLabel dirty = new JLabel(" ");
    private String lastJwtPreview = "";
    private int editing = -1;          // role index, -2 = the API gốc profile

    RolesTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        Ui.style(table);
        table.setRowHeight(Theme.current().scaled(36));
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
        table.getColumnModel().getColumn(0).setCellRenderer(new RoleIndexRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new SwitchRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new AvatarNameRenderer());
        table.getColumnModel().getColumn(3).setCellRenderer(new RuleChipsRenderer());
        Ui.widths(table, 36, 64, 180, 380);

        JButton[] moreRef = new JButton[1];
        JButton more = Ui.dropdown("Thao tác Role", t -> Icons.withChevron(Icons.gear(t.fg), t.fg),
                "Các thao tác với Role", () -> roleMenu(false).show(moreRef[0], 0, moreRef[0].getHeight()));
        moreRef[0] = more;
        JPanel top = Ui.flow(FlowLayout.LEFT,
                Ui.button("Thêm Role...", t -> Icons.plus(t.fg), this::addRole),
                Ui.button("Xóa", t -> Icons.trash(t.danger.text()), this::deleteRoles),
                more,
                Ui.button("Bật/Tắt theo phạm vi...", t -> Icons.sliders(t.fg),
                        "Tắt / bật role cho toàn bộ, cho từng nhánh hoặc từng API gốc",
                        () -> ui.roleScopeDialog(false, RoleScopes.Scope.GLOBAL, null, null, null)));

        profileCard.setPadding(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3);
        profileCard.setHeader(Role.ORIGINAL_PROFILE_NAME, "Profile áp lên request API gốc (Acc Crawl)", profileBadge);
        JPanel profileBody = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        profileBody.setOpaque(false);
        profileBody.add(profileState, BorderLayout.CENTER);
        profileBody.add(Ui.flow(FlowLayout.LEFT,
                Ui.button("Sửa profile API gốc", t -> Icons.edit(t.fg), this::editOriginal),
                Ui.button("Bật / Tắt profile", () -> {
                    Role p = engine.originalProfile();
                    engine.saveOriginalProfile(p.withEnabled(!p.enabled()));
                    refreshProfile();
                })), BorderLayout.SOUTH);
        profileCard.setBody(profileBody);

        JPanel north = new JPanel(new BorderLayout(2, Theme.SPACE_2));
        north.setOpaque(false);
        north.add(top, BorderLayout.NORTH);
        north.add(profileCard, BorderLayout.SOUTH);

        JPanel left = new JPanel(new BorderLayout(4, Theme.SPACE_2));
        left.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        left.add(north, BorderLayout.NORTH);
        left.add(MainPanel.scroll(table), BorderLayout.CENTER);
        left.add(modeLegend(), BorderLayout.SOUTH);

        Ui.style(rulesTable);
        rulesTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        rulesTable.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JComboBox<>(RuleMode.values())));
        rulesTable.getColumnModel().getColumn(2).setCellRenderer(new ModeRenderer());
        Ui.widths(rulesTable, 180, 360, 130);

        name.putClientProperty("JTextField.placeholderText", "VD: 01_Admin, 02_Staff, 03_Guest...");
        jwt.setEditable(false);
        jwt.setFont(Theme.current().monoFont);
        jwt.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_2, Theme.SPACE_1, Theme.SPACE_2));
        expBadge.setVisible(false);

        JPanel head = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        head.setOpaque(false);
        head.add(new JLabel("Role Name:"));
        head.add(name);
        head.add(enabled);
        JButton save = Ui.accent("Save Profile Changes", t -> Icons.save(t.success.onSolid()), t -> t.success, this::save);
        head.add(save);
        head.add(dirty);

        JPanel tools = new JPanel(new GridLayout(2, 5, 4, 4));
        tools.setOpaque(false);
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

        JPanel editorTop = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        editorTop.setOpaque(false);
        editorTop.add(title, BorderLayout.NORTH);
        editorTop.add(head, BorderLayout.CENTER);
        editorTop.add(tools, BorderLayout.SOUTH);

        JPanel jwtPanel = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        jwtPanel.setOpaque(false);
        JPanel jwtHeader = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        jwtHeader.setOpaque(false);
        jwtHeader.add(Ui.bold("JWT / Token Inspector (tự giải mã mọi token eyJ... trong profile này)"), BorderLayout.WEST);
        jwtHeader.add(expBadge, BorderLayout.EAST);
        jwtPanel.add(jwtHeader, BorderLayout.NORTH);
        jwtPanel.add(new JScrollPane(jwt), BorderLayout.CENTER);

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, MainPanel.scroll(rulesTable), jwtPanel);
        rightSplit.setResizeWeight(0.62);
        JPanel right = new JPanel(new BorderLayout(4, Theme.SPACE_2));
        right.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        JPanel rightNorth = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        rightNorth.setOpaque(false);
        rightNorth.add(new SectionHeader("Chỉnh sửa Profile đang chọn",
                "Sửa Header / Cookie / Session / JWT rồi bấm 'Save Profile Changes' để lưu"), BorderLayout.NORTH);
        rightNorth.add(editorTop, BorderLayout.CENTER);
        right.add(rightNorth, BorderLayout.NORTH);
        right.add(rightSplit, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.42);
        panel.add(split, BorderLayout.CENTER);

        rules.addTableModelListener(e -> {
            dirty.setIcon(Icons.themed(t -> Icons.statusDot(t.warning.text(), 8)));
            dirty.setText("chưa lưu");
            String preview = Jwt.preview(String.join("\n", rules.list.stream().map(HeaderRule::value).toList()));
            renderJwt(preview);
        });
        reload();
        refreshProfile();
        if (model.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private void renderJwt(String preview) {
        lastJwtPreview = preview == null ? "" : preview;
        Theme th = Theme.current();
        StyledDocument doc = jwt.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            if (lastJwtPreview.isEmpty()) {
                expBadge.setVisible(false);
                return;
            }

            SimpleAttributeSet plain = attr(th.fg, false);
            SimpleAttributeSet muted = attr(th.muted, false);
            SimpleAttributeSet head = attr(th.info.text(), true);
            SimpleAttributeSet label = attr(th.accent.text(), true);
            SimpleAttributeSet keyAttr = attr(th.info.text(), true);
            SimpleAttributeSet strAttr = attr(th.success.text(), false);
            SimpleAttributeSet numAttr = attr(th.warning.text(), true);
            SimpleAttributeSet errAttr = attr(th.danger.text(), true);

            String[] lines = lastJwtPreview.split("\n", -1);
            for (int li = 0; li < lines.length; li++) {
                String line = lines[li];
                if (line.startsWith("JWT #") && line.contains("decode error")) {
                    doc.insertString(doc.getLength(), line, errAttr);
                } else if (line.startsWith("JWT #")) {
                    doc.insertString(doc.getLength(), line, head);
                } else if ("Header:".equals(line) || "Payload:".equals(line)) {
                    doc.insertString(doc.getLength(), line, label);
                } else if (line.startsWith("No JWT ")) {
                    doc.insertString(doc.getLength(), line, muted);
                } else {
                    appendJsonLine(doc, line, plain, keyAttr, strAttr, numAttr);
                }
                if (li + 1 < lines.length) {
                    doc.insertString(doc.getLength(), "\n", plain);
                }
            }

            long nowSec = Instant.now().getEpochSecond();
            boolean hasExp = false;
            boolean anyExpired = false;
            Matcher m = EXP_PATTERN.matcher(lastJwtPreview);
            while (m.find()) {
                try {
                    long exp = Long.parseLong(m.group(1));
                    if (exp > 1_000_000_000_000L) {
                        exp /= 1000L;
                    }
                    hasExp = true;
                    if (exp < nowSec) {
                        anyExpired = true;
                        break;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            if (anyExpired) {
                expBadge.setToken(t -> t.danger)
                        .setIcon(t -> Icons.warn(t.danger.text(), 11))
                        .setText("JWT hết hạn (exp)");
                expBadge.setVisible(true);
            } else if (hasExp) {
                expBadge.setToken(t -> t.success)
                        .setIcon(t -> Icons.check(t.success.text(), 11))
                        .setText("Token còn hạn (exp)");
                expBadge.setVisible(true);
            } else {
                expBadge.setVisible(false);
            }
        } catch (BadLocationException ignored) {
        }
        jwt.setCaretPosition(0);
    }

    private static SimpleAttributeSet attr(Color fg, boolean bold) {
        SimpleAttributeSet s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, fg);
        StyleConstants.setBold(s, bold);
        return s;
    }

    private static void appendJsonLine(StyledDocument doc, String line, SimpleAttributeSet plain,
                                       SimpleAttributeSet keyAttr, SimpleAttributeSet strAttr,
                                       SimpleAttributeSet numAttr) throws BadLocationException {
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '"') {
                int end = i + 1;
                while (end < n) {
                    char ec = line.charAt(end);
                    if (ec == '\\' && end + 1 < n) {
                        end += 2;
                    } else if (ec == '"') {
                        end++;
                        break;
                    } else {
                        end++;
                    }
                }
                int probe = end;
                while (probe < n && line.charAt(probe) == ' ') {
                    probe++;
                }
                boolean isKey = probe < n && line.charAt(probe) == ':';
                doc.insertString(doc.getLength(), line.substring(i, end), isKey ? keyAttr : strAttr);
                i = end;
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                int end = i + 1;
                while (end < n) {
                    char nc = line.charAt(end);
                    if ((nc >= '0' && nc <= '9') || nc == '.' || nc == 'e' || nc == 'E' || nc == '+' || nc == '-') {
                        end++;
                    } else {
                        break;
                    }
                }
                doc.insertString(doc.getLength(), line.substring(i, end), numAttr);
                i = end;
            } else if (line.startsWith("true", i) && isBoundary(line, i + 4)) {
                doc.insertString(doc.getLength(), "true", numAttr);
                i += 4;
            } else if (line.startsWith("false", i) && isBoundary(line, i + 5)) {
                doc.insertString(doc.getLength(), "false", numAttr);
                i += 5;
            } else if (line.startsWith("null", i) && isBoundary(line, i + 4)) {
                doc.insertString(doc.getLength(), "null", numAttr);
                i += 4;
            } else {
                int end = i + 1;
                while (end < n) {
                    char next = line.charAt(end);
                    if (next == '"' || next == '-' || (next >= '0' && next <= '9')
                            || line.startsWith("true", end) || line.startsWith("false", end) || line.startsWith("null", end)) {
                        break;
                    }
                    end++;
                }
                doc.insertString(doc.getLength(), line.substring(i, end), plain);
                i = end;
            }
        }
    }

    private static boolean isBoundary(String s, int idx) {
        if (idx >= s.length()) {
            return true;
        }
        char c = s.charAt(idx);
        return !Character.isLetterOrDigit(c) && c != '_';
    }

    private JPanel modeLegend() {
        JPanel p = new JPanel(new GridLayout(3, 2, 6, 4));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createTitledBorder("Các chế độ (cột Mode)"));
        for (RuleMode m : RuleMode.values()) {
            Pill chip = Pill.badge(m.name(), t -> t.mode(m));
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            row.setOpaque(false);
            row.add(chip);
            row.add(Ui.muted(m.hint));
            p.add(row);
        }
        p.add(Ui.muted("Value = [REMOVE]: xóa"));
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
        boolean active = p.enabled() && n > 0;
        profileBadge.setToken(active ? t -> t.orig : t -> t.neutral)
                .setIcon(t -> Icons.pin(active ? t.orig.text() : t.muted, 11))
                .setText(p.enabled() ? (n > 0 ? n + " rule" : "Nguyên bản") : "Đang tắt");
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
        dirty.setIcon(null);
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
        dirty.setIcon(null);
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
        dirty.setIcon(Icons.themed(t -> Icons.check(t.success.text(), 12)));
        dirty.setText("đã lưu");
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
        m.add(Ui.item("Nhân bản Role đang chọn (Duplicate)", t -> Icons.copy(t.muted), () -> {
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
            m.add(Ui.item("Xóa Role đang chọn", t -> Icons.trash(t.danger.text()), this::deleteRoles));
        }
        m.addSeparator();
        m.add(Ui.item("Di chuyển lên (Move Up)", Icons::arrowUp, () -> moveRole(-1)));
        m.add(Ui.item("Di chuyển xuống (Move Down)", Icons::arrowDown, () -> moveRole(1)));
        m.addSeparator();
        m.add(Ui.item("Bật tất cả Role (Enable All)...", t -> Icons.check(t.success.text()),
                () -> ui.roleScopeDialog(true, RoleScopes.Scope.GLOBAL, null, null,
                        engine.roles().all().stream().map(Role::name).toList())));
        m.add(Ui.item("Tắt tất cả Role (Disable All)...", Icons::minus,
                () -> ui.roleScopeDialog(false, RoleScopes.Scope.GLOBAL, null, null,
                        engine.roles().all().stream().map(Role::name).toList())));
        m.add(Ui.item("Bật / Tắt Role theo phạm vi (nhánh / API)...", Icons::sliders,
                () -> ui.roleScopeDialog(false, RoleScopes.Scope.GLOBAL, null, null, null)));
        if (!onTable) {
            m.addSeparator();
            m.add(Ui.item("Import Roles từ JSON...", Icons::folder, this::importRoles));
            m.add(Ui.item("Export Roles ra JSON...", Icons::export, this::exportRoles));
            m.addSeparator();
            m.add(Ui.item("Nạp mẫu 10 Roles chuẩn (Load 10-Role Template)", t -> Icons.bolt(t.warning.text()), () -> {
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
        java.io.File f = ui.save(Text.EXT_NAME + "_roles.json", "JSON", "json");
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
        header.putClientProperty("JTextField.placeholderText", "Header Name (vd: Authorization)");
        JTextField prefix = new JTextField("Bearer", 12);
        prefix.putClientProperty("JTextField.placeholderText", "Prefix (Bearer)");
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
        k.putClientProperty("JTextField.placeholderText", "Cookie key");
        JTextField v = new JTextField("", 35);
        v.putClientProperty("JTextField.placeholderText", "Cookie value");
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

    // ---- Test / inspection helpers ----
    ThemedTable roleTable() {
        return table;
    }

    JTextPane jwtInspector() {
        return jwt;
    }

    Pill expBadge() {
        return expBadge;
    }

    Card originalProfileCard() {
        return profileCard;
    }

    static String initialsOf(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return "RL";
        }
        String s = roleName.trim();
        int underscore = s.indexOf('_');
        String target = underscore >= 0 && underscore + 1 < s.length() ? s.substring(underscore + 1) : s;
        StringBuilder out = new StringBuilder(2);
        for (int i = 0; i < target.length() && out.length() < 2; i++) {
            char c = target.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toUpperCase(c));
            }
        }
        if (out.isEmpty()) {
            return s.substring(0, Math.min(2, s.length())).toUpperCase(Locale.ROOT);
        }
        if (out.length() == 1 && s.length() > 1) {
            char c = s.charAt(0);
            if (Character.isLetterOrDigit(c)) {
                return ("" + Character.toUpperCase(c) + out.charAt(0));
            }
        }
        return out.toString();
    }

    private final class RoleModel extends AbstractTableModel {
        private final String[] cols = {"#", "Bật", "Role Name", "Rules (Header / Cookie / JWT)"};

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

    private static final class RoleIndexRenderer extends DefaultTableCellRenderer implements ThemedTree.ExtensionRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            Theme th = Theme.of(t.getBackground(), t.getForeground());
            setHorizontalAlignment(CENTER);
            setFont(th.monoSmallFont);
            setForeground(sel ? th.selectionFg : th.muted);
            setBackground(sel ? th.selectionBg : th.zebra[row & 1]);
            return this;
        }
    }

    private static final class SwitchRenderer extends JComponent implements TableCellRenderer, ThemedTree.ExtensionRenderer {
        private Theme theme;
        private boolean on;
        private boolean selected;
        private int row;

        SwitchRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            this.theme = t != null ? Theme.of(t.getBackground(), t.getForeground()) : Theme.current();
            this.on = Boolean.TRUE.equals(v);
            this.selected = sel;
            this.row = row;
            setToolTipText(on ? "Role đang bật (bấm để tắt)" : "Role đang tắt (bấm để bật)");
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme th = theme != null ? theme : Theme.current();
            int w = getWidth();
            int h = getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(selected ? th.selectionBg : th.zebra[row & 1]);
                g2.fillRect(0, 0, w, h);

                int sw = Math.min(w - 8, th.scaled(32));
                int sh = Math.min(h - 8, th.scaled(18));
                int sx = (w - sw) / 2;
                int sy = (h - sh) / 2;
                g2.setColor(on ? th.success.solidFill() : th.neutral.fillStrong());
                g2.fillRoundRect(sx, sy, sw, sh, sh, sh);
                g2.setColor(on ? th.success.border() : th.border);
                g2.drawRoundRect(sx, sy, sw - 1, sh - 1, sh, sh);

                int knob = Math.max(8, sh - 4);
                int kx = on ? (sx + sw - knob - 2) : (sx + 2);
                int ky = sy + (sh - knob) / 2;
                g2.setColor(on ? th.success.onSolid() : th.surface);
                g2.fillOval(kx, ky, knob, knob);
            } finally {
                g2.dispose();
            }
        }
    }

    private final class AvatarNameRenderer extends JComponent implements TableCellRenderer, ThemedTree.ExtensionRenderer {
        private Theme theme;
        private boolean selected;
        private boolean roleEnabled;
        private int modelRow;
        private String roleName = "";
        private String initials = "RL";

        AvatarNameRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            this.theme = t != null ? Theme.of(t.getBackground(), t.getForeground()) : Theme.current();
            this.selected = sel;
            this.modelRow = t != null && row >= 0 ? t.convertRowIndexToModel(row) : row;
            Role r = modelRow >= 0 && modelRow < engine.roles().count() ? engine.roles().get(modelRow) : null;
            this.roleEnabled = r == null || r.enabled();
            this.roleName = r != null ? r.name() : (v == null ? "" : v.toString());
            this.initials = initialsOf(this.roleName);
            setToolTipText(roleName);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme th = theme != null ? theme : Theme.current();
            int w = getWidth();
            int h = getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(selected ? th.selectionBg : th.zebra[Math.max(0, modelRow) & 1]);
                g2.fillRect(0, 0, w, h);

                int pad = th.scaled(6);
                int av = Math.min(h - th.scaled(8), th.scaled(24));
                int ay = (h - av) / 2;
                Theme.Token tok = roleEnabled ? th.accent(modelRow) : th.neutral;
                g2.setColor(tok.solidFill());
                g2.fillOval(pad, ay, av, av);

                g2.setFont(th.smallBoldFont);
                FontMetrics sfm = g2.getFontMetrics();
                int itw = sfm.stringWidth(initials);
                int ity = ay + (av - sfm.getHeight()) / 2 + sfm.getAscent();
                g2.setColor(tok.onSolid());
                g2.drawString(initials, pad + Math.max(1, (av - itw) / 2), ity);

                int tx = pad + av + th.scaled(8);
                g2.setFont(roleEnabled ? th.boldFont : th.baseFont);
                FontMetrics fm = g2.getFontMetrics();
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(selected ? th.selectionFg : (roleEnabled ? th.fg : th.muted));
                g2.drawString(roleName, tx, ty);
            } finally {
                g2.dispose();
            }
        }
    }

    private final class RuleChipsRenderer extends JComponent implements TableCellRenderer, ThemedTree.ExtensionRenderer {
        private Theme theme;
        private boolean selected;
        private int modelRow;
        private List<HeaderRule> ruleList = List.of();
        private final char[] buf = new char[128];

        RuleChipsRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            this.theme = t != null ? Theme.of(t.getBackground(), t.getForeground()) : Theme.current();
            this.selected = sel;
            this.modelRow = t != null && row >= 0 ? t.convertRowIndexToModel(row) : row;
            Role r = modelRow >= 0 && modelRow < engine.roles().count() ? engine.roles().get(modelRow) : null;
            this.ruleList = r != null ? r.rules() : List.of();
            setToolTipText(r != null && !r.rules().isEmpty() ? r.rulesSummary() : "(không có rule - gửi giống API gốc)");
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme th = theme != null ? theme : Theme.current();
            int w = getWidth();
            int h = getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(selected ? th.selectionBg : th.zebra[Math.max(0, modelRow) & 1]);
                g2.fillRect(0, 0, w, h);

                int x = th.scaled(6);
                if (ruleList.isEmpty()) {
                    g2.setFont(th.italicFont);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.setColor(selected ? th.selectionFg : th.muted);
                    int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawString("(chưa có rule - gửi giống API gốc)", x, ty);
                    return;
                }

                g2.setFont(th.smallBoldFont);
                FontMetrics fm = g2.getFontMetrics();
                int ip = th.scaled(6);
                int gap = th.scaled(4);
                int pillH = Math.min(h - th.scaled(8), th.scaled(20));
                int pillY = (h - pillH) / 2;
                int maxRight = w - th.scaled(6);

                for (int i = 0; i < ruleList.size(); i++) {
                    HeaderRule hr = ruleList.get(i);
                    String modeLabel = compactModeName(hr.mode());
                    int n = writeChipText(modeLabel, hr.name());
                    int tw = fm.charsWidth(buf, 0, n);
                    int pw = ip * 2 + tw;
                    if (x + pw > maxRight - th.scaled(28) && i < ruleList.size() - 1) {
                        int rem = ruleList.size() - i;
                        int rn = writeOverflow(rem);
                        int rpw = ip * 2 + fm.charsWidth(buf, 0, rn);
                        if (x + rpw <= maxRight) {
                            drawChip(g2, th.neutral, x, pillY, rpw, pillH, fm, rn);
                        }
                        break;
                    }
                    if (x + pw > maxRight) {
                        break;
                    }
                    Theme.Token tok = th.mode(hr.mode());
                    drawChip(g2, tok, x, pillY, pw, pillH, fm, n);
                    x += pw + gap;
                }
            } finally {
                g2.dispose();
            }
        }

        private void drawChip(Graphics2D g2, Theme.Token tok, int x, int y, int w, int h, FontMetrics fm, int len) {
            g2.setColor(tok.fill());
            g2.fillRoundRect(x, y, w - 1, h - 1, h, h);
            g2.setColor(tok.border());
            g2.drawRoundRect(x, y, w - 1, h - 1, h, h);
            g2.setColor(tok.text());
            int ty = y + (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawChars(buf, 0, len, x + (w - fm.charsWidth(buf, 0, len)) / 2, ty);
        }

        private static String compactModeName(RuleMode m) {
            if (m == null) {
                return "RULE";
            }
            return switch (m) {
                case REPLACE -> "REPLACE";
                case ADD -> "ADD";
                case REMOVE -> "REMOVE";
                case COOKIE_SET -> "COOKIE";
                case COOKIE_REMOVE -> "DEL_CK";
            };
        }

        private int writeChipText(String prefix, String key) {
            int pos = 0;
            int plen = Math.min(prefix.length(), buf.length - 4);
            prefix.getChars(0, plen, buf, pos);
            pos += plen;
            if (key != null && !key.isEmpty() && pos + 3 < buf.length) {
                buf[pos++] = ':';
                buf[pos++] = ' ';
                int klen = Math.min(key.length(), Math.min(24, buf.length - pos));
                key.getChars(0, klen, buf, pos);
                pos += klen;
            }
            return pos;
        }

        private int writeOverflow(int rem) {
            buf[0] = '+';
            String s = Integer.toString(rem);
            int n = Math.min(s.length(), buf.length - 1);
            s.getChars(0, n, buf, 1);
            return 1 + n;
        }
    }

    private static final class ModeRenderer extends DefaultTableCellRenderer implements ThemedTree.ExtensionRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            Theme th = Theme.of(t.getBackground(), t.getForeground());
            if (!sel) {
                if (v instanceof RuleMode m) {
                    Theme.Token tok = th.mode(m);
                    l.setBackground(tok.fill());
                    l.setForeground(tok.text());
                } else {
                    l.setBackground(t.getBackground());
                    l.setForeground(t.getForeground());
                }
            }
            l.setFont(th.boldFont);
            return l;
        }
    }
}
