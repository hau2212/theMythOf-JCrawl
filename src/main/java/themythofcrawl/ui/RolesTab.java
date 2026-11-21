package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
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
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import themythofcrawl.app.Engine;
import themythofcrawl.core.HttpBytes;
import themythofcrawl.core.Jwt;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.Role;
import themythofcrawl.model.RoleScopes;
import themythofcrawl.model.Roles;
import themythofcrawl.model.RuleMode;
import themythofcrawl.ui.kit.Card;
import themythofcrawl.ui.kit.ModernToggleSwitch;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.kit.ThemedTree;
import themythofcrawl.ui.theme.Theme;

/**
 * 'Roles' tab — Modern minimalist design synchronized across Light & Dark themes:
 * - Clean vector ModernToggleSwitch in both table status column and right-hand Profile Editor
 * - 3 balanced summary cards ([Tổng] [Đang bật] [Hết hạn / Lỗi])
 * - Tier badges ([ADM]/[MGR]/[STF]/[USR]/[GST]/[CUS]) & live JWT TTL / Session Ping chips
 * - Unified Burp-LAF-proof buttons (Solid Primary / Danger / Secondary)
 */
final class RolesTab {
    private static final Pattern EXP_PATTERN = Pattern.compile("\"exp\"\\s*:\\s*(\\d{9,13})");
    private static final Pattern JSON_TOKEN_PATTERN = Pattern.compile(
            "\"(access_token|accessToken|id_token|idToken|refresh_token|refreshToken|token|jwt|session_id|sessionId)\"\\s*:\\s*\"([^\"]{8,2048})\"");

    final JPanel panel = new JPanel(new BorderLayout());
    private final MainPanel ui;
    private final Engine engine;
    private final RoleModel model = new RoleModel();
    private final ThemedTable table = new ThemedTable(model) {
        @Override
        public void updateUI() {
            super.updateUI();
            super.setRowHeight(Theme.current().scaled(36));
        }

        @Override
        public void setRowHeight(int rowHeight) {
            super.setRowHeight(Math.max(rowHeight, Theme.current().scaled(36)));
        }
    };
    private final Card profileCard = new Card(t -> t.orig, true);
    private final JLabel profileState = Ui.muted(" ");
    private final Pill profileBadge = new Pill("00_API GỐC", t -> t.orig, t -> Icons.pin(t.orig.text(), 11));
    private final JLabel summaryTotal = makeSummaryCell("Tổng: 0", false);
    private final JLabel summaryActive = makeSummaryCell("Đang bật: 0", false);
    private final JLabel summaryWarn = makeSummaryCell("Hết hạn / Lỗi: 0", false);

    private final JLabel title = Ui.bold("Profile:");
    private final JTextField name = new JTextField(16);
    private final JComboBox<Role.Tier> tierBox = new JComboBox<>(Role.Tier.values());
    private boolean enabledState = true;
    private final ModernToggleSwitch enabledToggle = new ModernToggleSwitch(true, false)
            .labels("Bật", "Tắt");
    private final RuleModel rules = new RuleModel();
    private final ThemedTable rulesTable = new ThemedTable(rules) {
        @Override
        public void updateUI() {
            super.updateUI();
            super.setRowHeight(Theme.current().scaled(32));
        }

        @Override
        public void setRowHeight(int rowHeight) {
            super.setRowHeight(Math.max(rowHeight, Theme.current().scaled(32)));
        }
    };
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
    private final JButton jwtToggleBtn = new JButton();
    private JScrollPane jwtScroll;
    private boolean jwtExpanded = false;
    private final JLabel dirty = new JLabel(" ");
    private final Map<String, Engine.ProbeResult> probeResults = new ConcurrentHashMap<>();
    private String lastJwtPreview = "";
    private int editing = -1;          // role index, -2 = the API gốc profile

    RolesTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        Ui.style(table);
        table.setRowHeight(Theme.current().scaled(36));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
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
        table.getColumnModel().getColumn(1).setCellRenderer(new SquareStatusRenderer());
        table.getColumnModel().getColumn(1).setCellEditor(new SquareStatusEditor());
        table.getColumnModel().getColumn(2).setCellRenderer(new AvatarNameRenderer());
        table.getColumnModel().getColumn(3).setCellRenderer(new RuleChipsRenderer());
        Ui.widths(table, 38, 96, 205, 320);
        table.getColumnModel().getColumn(0).setMinWidth(34);
        table.getColumnModel().getColumn(1).setMinWidth(90);
        table.getColumnModel().getColumn(2).setMinWidth(150);
        table.getColumnModel().getColumn(3).setMinWidth(200);

        profileBadge.setFixedHeight(24).onClick(this::editOriginal);
        profileCard.setPadding(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3);
        profileCard.setHeader(Role.ORIGINAL_PROFILE_NAME, "Profile áp lên request API gốc (Acc Crawl)", profileBadge);
        profileCard.setBody(profileState);

        JButton[] moreRef = new JButton[1];
        JButton more = Ui.dropdown("Thao tác", t -> Icons.withChevron(Icons.gear(t.fg), t.fg),
                "Quản lý Role, Ping Session, So sánh 2 Role, Bật/Tắt phạm vi, Import/Export",
                () -> roleMenu(false).show(moreRef[0], 0, moreRef[0].getHeight()));
        moreRef[0] = more;
        JButton pingBtn = Ui.secondaryBtn("Ping", t -> Icons.bolt(t.info.text()), this::pingRolesDialog);
        pingBtn.setToolTipText("Ping kiểm tra Session còn sống (200 LIVE) hay hết hạn (401 DEAD) trên tất cả Role đang bật");
        JButton diffBtn = Ui.secondaryBtn("So sánh", Icons::compare, this::compareRolesDialog);
        diffBtn.setToolTipText("So sánh trực tiếp bảng Header/Cookie giữa 2 Role bất kỳ");

        JPanel topLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        topLeft.setOpaque(false);
        topLeft.add(Ui.solidPrimary("Thêm Role", t -> Icons.plus(t.accent.onSolid()), this::addRole));
        topLeft.add(Ui.dangerBtn("Xóa", t -> Icons.trash(t.danger.text()), this::deleteRoles));
        topLeft.add(profileBadge);

        JPanel topRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        topRight.setOpaque(false);
        topRight.add(pingBtn);
        topRight.add(diffBtn);
        topRight.add(more);

        JPanel top = new JPanel(new BorderLayout(4, 0));
        top.setOpaque(false);
        top.add(topLeft, BorderLayout.WEST);
        top.add(topRight, BorderLayout.EAST);

        JPanel summaryStrip = new JPanel(new GridLayout(1, 3, 6, 0));
        summaryStrip.setOpaque(false);
        summaryStrip.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        summaryStrip.add(summaryTotal);
        summaryStrip.add(summaryActive);
        summaryStrip.add(summaryWarn);

        JPanel leftNorth = new JPanel(new BorderLayout(0, 2));
        leftNorth.setOpaque(false);
        leftNorth.add(top, BorderLayout.NORTH);
        leftNorth.add(summaryStrip, BorderLayout.SOUTH);

        JPanel left = new JPanel(new BorderLayout(4, Theme.SPACE_1));
        left.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_1));
        left.add(leftNorth, BorderLayout.NORTH);
        left.add(MainPanel.scroll(table), BorderLayout.CENTER);

        Ui.style(rulesTable);
        rulesTable.setRowHeight(Theme.current().scaled(32));
        rulesTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        rulesTable.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JComboBox<>(RuleMode.values())));
        rulesTable.getColumnModel().getColumn(2).setCellRenderer(new ModeRenderer());
        Ui.widths(rulesTable, 175, 290, 128);
        Ui.onPopup(rulesTable, e -> ruleEditorMenu().show(e.getComponent(), e.getX(), e.getY()));

        name.putClientProperty("JTextField.placeholderText", "VD: 01_Admin, 02_Staff...");
        name.setPreferredSize(new Dimension(168, 26));
        name.setMinimumSize(new Dimension(120, 26));
        tierBox.setPreferredSize(new Dimension(132, 26));
        tierBox.setToolTipText("Phân tầng cấp quyền dự kiến của Role (ADM / MGR / STF / USR / GST / CUS)");
        tierBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof Role.Tier tr) {
                    setText("[" + tr.badge + "] " + tr.name());
                }
                return this;
            }
        });
        tierBox.addActionListener(Ui.safe(e -> {
            if (editing >= 0) {
                markDirty();
            }
        }));

        enabledToggle.setOn(true, false);
        enabledToggle.setToolTipText("Bật hoặc tắt Role này khi chạy kiểm thử phân quyền");
        enabledToggle.onChange(on -> {
            enabledState = on;
            markDirty();
        });

        jwt.setEditable(false);
        jwt.setFont(Theme.current().monoFont);
        jwt.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        expBadge.setVisible(false);

        // Minimalist bordered Profile Header Card (flexible GridBagLayout prevents overlap at any width)
        JPanel headCard = new JPanel(new java.awt.GridBagLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setBackground(t.surface);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(t.border),
                        BorderFactory.createEmptyBorder(6, 10, 6, 10)));
            }
        };
        headCard.setOpaque(true);
        headCard.setBackground(Theme.current().surface);
        headCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.current().border),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        name.setPreferredSize(new Dimension(132, 26));
        name.setMinimumSize(new Dimension(96, 26));
        tierBox.setPreferredSize(new Dimension(118, 26));
        tierBox.setMinimumSize(new Dimension(104, 26));

        java.awt.GridBagConstraints hc = new java.awt.GridBagConstraints();
        hc.gridy = 0;
        hc.anchor = java.awt.GridBagConstraints.WEST;
        hc.insets = new java.awt.Insets(0, 0, 0, 6);

        hc.gridx = 0;
        hc.weightx = 0;
        hc.fill = java.awt.GridBagConstraints.NONE;
        headCard.add(title, hc);

        hc.gridx = 1;
        hc.weightx = 1.0;
        hc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        headCard.add(name, hc);

        hc.gridx = 2;
        hc.weightx = 0;
        hc.fill = java.awt.GridBagConstraints.NONE;
        headCard.add(tierBox, hc);

        hc.gridx = 3;
        headCard.add(enabledToggle, hc);

        hc.gridx = 4;
        headCard.add(dirty, hc);

        hc.gridx = 5;
        hc.insets = new java.awt.Insets(0, 0, 0, 0);
        headCard.add(Ui.solidPrimary("Lưu Profile", t -> Icons.save(t.accent.onSolid()), this::save), hc);

        // Streamlined 3-group Rules Action Bar (no clutter)
        JButton[] quickRef = new JButton[1];
        JButton quickTemplatesBtn = Ui.dropdown("Mẫu nhanh", t -> Icons.withChevron(Icons.key(t.accent.text(), 13), t.fg),
                "Thêm nhanh JWT/Bearer, Cookie, Trích Token từ API đã bắt, Xóa Header hoặc Dán hàng loạt", () -> {
                    JPopupMenu qm = new JPopupMenu();
                    qm.add(Ui.item("Thêm JWT / Bearer Authorization...", Icons::codeBraces, this::setJwt));
                    qm.add(Ui.item("Thêm Cookie (COOKIE_SET)...", Icons::plus, this::setCookie));
                    qm.add(Ui.item("Trích xuất Token / Cookie từ API đã bắt...", t -> Icons.key(t.accent.text(), 14), this::extractTokenDialog));
                    qm.addSeparator();
                    for (Component c : ruleEditorMenu().getComponents()) {
                        qm.add(c);
                    }
                    qm.show(quickRef[0], 0, quickRef[0].getHeight());
                });
        quickRef[0] = quickTemplatesBtn;

        JPanel toolsLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        toolsLeft.setOpaque(false);
        toolsLeft.add(Ui.secondaryBtn("Thêm Rule", t -> Icons.plus(t.fg),
                () -> rules.add(new HeaderRule("X-Header", "value", RuleMode.REPLACE))));
        toolsLeft.add(quickTemplatesBtn);

        JPanel toolsRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        toolsRight.setOpaque(false);
        toolsRight.add(Ui.secondaryBtn("Lên", Icons::arrowUp, () -> moveRule(-1)));
        toolsRight.add(Ui.secondaryBtn("Xuống", Icons::arrowDown, () -> moveRule(1)));
        toolsRight.add(Ui.dangerBtn("Xóa Rule", t -> Icons.trash(t.danger.text()), this::removeRules));

        JPanel tools = new JPanel(new BorderLayout(4, 0));
        tools.setOpaque(false);
        tools.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        tools.add(toolsLeft, BorderLayout.WEST);
        tools.add(toolsRight, BorderLayout.EAST);

        JPanel editorTop = new JPanel(new BorderLayout(0, 4));
        editorTop.setOpaque(false);
        editorTop.add(headCard, BorderLayout.NORTH);
        editorTop.add(tools, BorderLayout.SOUTH);

        // Collapsible JWT / Token Inspector Panel
        jwtToggleBtn.setMargin(new java.awt.Insets(0, 0, 0, 0));
        jwtToggleBtn.setPreferredSize(new Dimension(20, 20));
        jwtToggleBtn.setToolTipText("Mở rộng / Thu gọn bảng giải mã JWT");
        jwtToggleBtn.addActionListener(Ui.safe(e -> setJwtExpanded(!jwtExpanded)));

        JPanel jwtHeader = new JPanel(new BorderLayout(Theme.SPACE_2, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setBackground(t.surfaceAlt);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(t.border),
                        BorderFactory.createEmptyBorder(3, 6, 3, 6)));
            }
        };
        jwtHeader.setOpaque(true);
        jwtHeader.setBackground(Theme.current().surfaceAlt);
        jwtHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.current().border),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));

        JPanel jwtHeadLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        jwtHeadLeft.setOpaque(false);
        jwtHeadLeft.add(jwtToggleBtn);
        jwtHeadLeft.add(Ui.bold("JWT / Token Inspector"));
        jwtHeadLeft.add(Ui.muted("(Tự giải mã eyJ... & đếm ngược TTL)"));
        jwtHeader.add(jwtHeadLeft, BorderLayout.WEST);
        jwtHeader.add(expBadge, BorderLayout.EAST);

        jwtScroll = new JScrollPane(jwt);
        jwtScroll.setPreferredSize(new Dimension(0, 145));
        JPanel jwtPanel = new JPanel(new BorderLayout(0, 0));
        jwtPanel.setOpaque(false);
        jwtPanel.add(jwtHeader, BorderLayout.NORTH);
        jwtPanel.add(jwtScroll, BorderLayout.CENTER);
        setJwtExpanded(false);

        JPanel right = new JPanel(new BorderLayout(4, Theme.SPACE_1));
        right.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_1, Theme.SPACE_2, Theme.SPACE_2));
        right.add(editorTop, BorderLayout.NORTH);
        right.add(MainPanel.scroll(rulesTable), BorderLayout.CENTER);
        right.add(jwtPanel, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.46);
        panel.add(split, BorderLayout.CENTER);

        rules.addTableModelListener(e -> {
            markDirty();
            String preview = Jwt.preview(String.join("\n", rules.list.stream().map(HeaderRule::value).toList()));
            renderJwt(preview);
        });
        reload();
        refreshProfile();
        if (model.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private void setJwtExpanded(boolean expanded) {
        this.jwtExpanded = expanded;
        if (jwtScroll != null) {
            jwtScroll.setVisible(expanded);
        }
        jwtToggleBtn.setIcon(Icons.themed(t -> expanded ? Icons.chevronDown(t.fg, 12) : Icons.chevronRight(t.fg, 12)));
        panel.revalidate();
        panel.repaint();
    }

    private static JLabel makeSummaryCell(String text, boolean warn) {
        JLabel l = new JLabel(text, SwingConstants.CENTER) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setFont(t.smallBoldFont);
                setOpaque(true);
                setBackground(t.surfaceAlt);
                setForeground(t.fg);
                setBorder(BorderFactory.createLineBorder(t.border, 1));
            }
        };
        l.setPreferredSize(new Dimension(0, 24));
        l.setMinimumSize(new Dimension(60, 24));
        return l;
    }

    private void updateSummaryStrip() {
        List<Role> all = engine.roles().all();
        int total = all.size();
        int active = 0;
        int warnCount = 0;
        for (Role r : all) {
            if (r.enabled()) {
                active++;
            }
            JwtTtl ttl = jwtTtlOf(r);
            Engine.ProbeResult pr = probeResults.get(r.name());
            if ((ttl != null && ttl.expired) || (pr != null && pr.dead())) {
                warnCount++;
            }
        }
        Theme t = Theme.current();
        summaryTotal.setText("Tổng: " + total);
        summaryTotal.setBackground(t.surfaceAlt);
        summaryTotal.setForeground(t.fg);
        summaryTotal.setBorder(BorderFactory.createLineBorder(t.border, 1));

        summaryActive.setText("Đang bật: " + active + "/" + total);
        summaryActive.setBackground(active > 0 ? t.success.fill() : t.surfaceAlt);
        summaryActive.setForeground(active > 0 ? t.success.text() : t.muted);
        summaryActive.setBorder(BorderFactory.createLineBorder(active > 0 ? t.success.border() : t.border, 1));

        summaryWarn.setText("Hết hạn / Lỗi: " + warnCount);
        summaryWarn.setBackground(warnCount > 0 ? t.danger.fill() : t.surfaceAlt);
        summaryWarn.setForeground(warnCount > 0 ? t.danger.text() : t.muted);
        summaryWarn.setBorder(BorderFactory.createLineBorder(warnCount > 0 ? t.danger.border() : t.border, 1));
    }

    private void setEnabledState(boolean on) {
        this.enabledState = on;
        if (enabledToggle != null) {
            enabledToggle.setOn(on, false);
        }
    }

    private void markDirty() {
        dirty.setIcon(Icons.themed(t -> Icons.statusDot(t.warning.text(), 8)));
        dirty.setText("chưa lưu");
    }

    private record JwtTtl(boolean expired, long remainingSec) {
        String compactLabel() {
            if (expired) {
                return "JWT HẾT HẠN";
            }
            if (remainingSec < 60) {
                return "TTL " + remainingSec + "s";
            }
            if (remainingSec < 3600) {
                return "TTL " + (remainingSec / 60) + "m";
            }
            long h = remainingSec / 3600;
            long m = (remainingSec % 3600) / 60;
            if (h >= 24) {
                return "TTL " + (h / 24) + "d";
            }
            return m > 0 ? "TTL " + h + "h" + m + "m" : "TTL " + h + "h";
        }
    }

    private static JwtTtl jwtTtlOf(Role role) {
        if (role == null || role.rules().isEmpty()) {
            return null;
        }
        String joined = String.join("\n", role.rules().stream().map(HeaderRule::value).toList());
        String preview = Jwt.preview(joined);
        if (preview == null || preview.isEmpty() || preview.startsWith("No JWT ")) {
            return null;
        }
        return parseExpFromPreview(preview);
    }

    private static JwtTtl parseExpFromPreview(String preview) {
        long nowSec = Instant.now().getEpochSecond();
        boolean hasExp = false;
        long minRemaining = Long.MAX_VALUE;
        Matcher m = EXP_PATTERN.matcher(preview);
        while (m.find()) {
            try {
                long exp = Long.parseLong(m.group(1));
                if (exp > 1_000_000_000_000L) {
                    exp /= 1000L;
                }
                hasExp = true;
                long rem = exp - nowSec;
                if (rem < minRemaining) {
                    minRemaining = rem;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (!hasExp) {
            return null;
        }
        return new JwtTtl(minRemaining < 0, Math.max(0, minRemaining));
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

            JwtTtl ttl = parseExpFromPreview(lastJwtPreview);
            if (ttl != null && ttl.expired) {
                expBadge.setToken(t -> t.danger)
                        .setIcon(t -> Icons.warn(t.danger.text(), 11))
                        .setText("JWT hết hạn (exp)");
                expBadge.setVisible(true);
            } else if (ttl != null) {
                expBadge.setToken(t -> ttl.remainingSec < 300 ? t.warning : t.success)
                        .setIcon(t -> ttl.remainingSec < 300 ? Icons.clock(t.warning.text()) : Icons.check(t.success.text(), 11))
                        .setText("Token còn hạn · " + ttl.compactLabel());
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

    private void showModeLegend() {
        StringBuilder sb = new StringBuilder();
        for (RuleMode m : RuleMode.values()) {
            sb.append("- ").append(m.name()).append(": ").append(m.hint).append('\n');
        }
        sb.append("- Value = ").append(RuleMode.REMOVE_TOKEN).append(": xóa Header / Cookie tương ứng khỏi request");
        Ui.popover(panel, "Các chế độ (cột Mode)", sb.toString());
    }

    void reload() {
        model.fireTableDataChanged();
        updateSummaryStrip();
    }

    void refreshCounts() {
        model.fireTableRowsUpdated(0, Math.max(0, model.getRowCount() - 1));
        updateSummaryStrip();
    }

    private void refreshProfile() {
        Role p = engine.originalProfile();
        int n = p.rules().size();
        boolean active = p.enabled() && n > 0;
        String badgeText = p.enabled() ? (n > 0 ? "API GỐC: " + n + " rule" : "API GỐC: Nguyên bản") : "API GỐC: Tắt";
        String stateText = !p.enabled() || n == 0 ? "Chưa có rule đang bật - API gốc được gửi đúng như lúc bắt"
                : "Đang áp dụng " + n + " rule cho API gốc (API bắt từ giờ; API cũ: 'Rebuild Roles')";
        profileBadge.setToken(active ? t -> t.orig : t -> t.neutral)
                .setIcon(t -> Icons.pin(active ? t.orig.text() : t.muted, 11))
                .setText(badgeText);
        profileBadge.setToolTipText(stateText + " (Bấm để sửa profile 00_API GỐC)");
        profileState.setText(stateText);
    }

    private void load(int idx) {
        editing = idx;
        Role r = engine.roles().get(idx);
        title.setText("Role:");
        name.setText(r.name());
        name.setEditable(true);
        tierBox.setEnabled(true);
        tierBox.setSelectedItem(r.tier());
        setEnabledState(r.enabled());
        rules.set(r.rules());
        dirty.setIcon(null);
        dirty.setText(" ");
    }

    private void editOriginal() {
        editing = -2;
        table.clearSelection();
        Role p = engine.originalProfile();
        title.setText("API Gốc:");
        name.setText(Role.ORIGINAL_PROFILE_NAME);
        name.setEditable(false);
        tierBox.setEnabled(false);
        tierBox.setSelectedItem(Role.Tier.CUSTOM);
        setEnabledState(p.enabled());
        rules.set(p.rules());
        dirty.setIcon(null);
        dirty.setText(" ");
    }

    void selectOriginal() {
        editOriginal();
    }

    void selectRole(int idx) {
        if (idx >= 0 && idx < model.getRowCount()) {
            MainPanel.selectRow(table, idx);
            load(idx);
        }
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
            engine.saveOriginalProfile(new Role(Role.ORIGINAL_PROFILE_NAME, enabledState, clean, Role.Tier.CUSTOM));
            refreshProfile();
            ui.flash("Đã lưu profile API gốc (" + clean.size() + " rule)");
        } else if (editing >= 0) {
            String n = name.getText().trim();
            if (n.isEmpty()) {
                ui.info("Tên role không được để trống.");
                return;
            }
            Role.Tier chosenTier = tierBox.getSelectedItem() instanceof Role.Tier tr ? tr : Role.Tier.infer(n, clean);
            engine.roles().update(editing, new Role(n, enabledState, clean, chosenTier));
            engine.saveRoles();
            reload();
            MainPanel.selectRow(table, editing);
            ui.flash("Đã lưu role " + n + " [" + chosenTier.badge + "] (" + clean.size() + " rule)");
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

    // =================================================================== Advanced Features
    private record ExtractedCred(int gid, String apiLabel, String source, String key, String value, RuleMode mode) {
    }

    private void extractTokenDialog() {
        List<ExtractedCred> found = scanCapturedCredentials();
        if (found.isEmpty()) {
            ui.info("Chưa tìm thấy Authorization / Cookie / JWT nào trong các API đã bắt.\nHãy crawl ít nhất 1 request có đăng nhập trước.");
            return;
        }
        AbstractTableModel tm = new AbstractTableModel() {
            private final String[] cols = {"API #", "Endpoint", "Nguồn", "Key / Header", "Giá trị (rút gọn)", "Mode"};
            @Override public int getRowCount() { return found.size(); }
            @Override public int getColumnCount() { return cols.length; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override
            public Object getValueAt(int r, int c) {
                ExtractedCred e = found.get(r);
                return switch (c) {
                    case 0 -> Text.groupId(e.gid);
                    case 1 -> e.apiLabel;
                    case 2 -> e.source;
                    case 3 -> e.key;
                    case 4 -> Text.shortPath("/" + e.value, 48).substring(1);
                    default -> e.mode.name();
                };
            }
        };
        ThemedTable t = new ThemedTable(tm);
        Ui.style(t);
        t.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        Ui.widths(t, 56, 200, 96, 130, 260, 92);
        if (!found.isEmpty()) {
            t.setRowSelectionInterval(0, 0);
        }

        JDialog dlg = new JDialog(Ui.ownerWindow(SwingUtilities.getWindowAncestor(panel)), "Trích xuất Token / Cookie từ API đã bắt",
                JDialog.ModalityType.APPLICATION_MODAL);
        JPanel body = new JPanel(new BorderLayout(6, 6));
        body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        body.add(Ui.muted("Tìm thấy " + found.size() + " credential trong request/response đã bắt. Chọn 1 hoặc nhiều dòng để thêm vào Role đang sửa:"),
                BorderLayout.NORTH);
        body.add(MainPanel.scroll(t), BorderLayout.CENTER);
        JButton applyBtn = Ui.accent("Thêm vào Role đang sửa", t0 -> Icons.check(t0.accent.onSolid()), t0 -> t0.accent, () -> {
            int[] rows = t.getSelectedRows();
            if (rows.length == 0) {
                return;
            }
            int added = 0;
            for (int r : rows) {
                ExtractedCred c = found.get(r);
                HeaderRule hr = HeaderRule.of(c.key, c.value, c.mode.name());
                if (hr != null) {
                    replaceOrAddRule(hr);
                    added++;
                }
            }
            dlg.dispose();
            ui.flash("Đã nạp " + added + " rule credential vào editor (bấm 'Lưu Profile' để áp dụng)");
        });
        JButton closeBtn = Ui.button("Đóng", (String) null, dlg::dispose);
        body.add(Ui.flow(FlowLayout.RIGHT, closeBtn, applyBtn), BorderLayout.SOUTH);
        dlg.setContentPane(body);
        dlg.setSize(860, 440);
        dlg.setLocationRelativeTo(panel);
        dlg.setVisible(true);
    }

    private void replaceOrAddRule(HeaderRule incoming) {
        for (int i = 0; i < rules.list.size(); i++) {
            HeaderRule cur = rules.list.get(i);
            if (cur.name().equalsIgnoreCase(incoming.name()) && cur.mode() == incoming.mode()) {
                rules.list.set(i, incoming);
                rules.fireTableRowsUpdated(i, i);
                return;
            }
        }
        rules.add(incoming);
    }

    private List<ExtractedCred> scanCapturedCredentials() {
        List<ExtractedCred> out = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();
        for (ApiGroup g : engine.groups()) {
            String apiLabel = g.method() + " " + Text.shortPath(g.path(), 32);
            byte[] req = engine.requestOf(g, g.origResult());
            if (req != null && req.length > 0) {
                String head = headerText(req);
                for (String line : head.split("\r?\n")) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String hk = line.substring(0, colon).trim();
                    String hv = line.substring(colon + 1).trim();
                    String low = hk.toLowerCase(Locale.ROOT);
                    if (low.equals("authorization") || low.equals("x-auth-token") || low.equals("x-api-key")
                            || low.equals("x-access-token") || low.equals("x-csrf-token") || low.equals("x-xsrf-token")) {
                        if (!hv.isEmpty() && dedup.add("REQ|" + low + "|" + hv)) {
                            out.add(new ExtractedCred(g.gid(), apiLabel, "Request Header", hk, hv, RuleMode.REPLACE));
                        }
                    } else if (low.equals("cookie")) {
                        for (String part : hv.split(";")) {
                            int eq = part.indexOf('=');
                            if (eq > 0) {
                                String ck = part.substring(0, eq).trim();
                                String cv = part.substring(eq + 1).trim();
                                if (!ck.isEmpty() && !cv.isEmpty() && dedup.add("CK|" + ck + "|" + cv)) {
                                    out.add(new ExtractedCred(g.gid(), apiLabel, "Request Cookie", ck, cv, RuleMode.COOKIE_SET));
                                }
                            }
                        }
                    }
                }
            }
            byte[] resp = engine.responseOf(g, g.origResult());
            if (resp != null && resp.length > 0) {
                String head = headerText(resp);
                for (String line : head.split("\r?\n")) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String hk = line.substring(0, colon).trim();
                    String hv = line.substring(colon + 1).trim();
                    if (hk.equalsIgnoreCase("set-cookie")) {
                        String pair = hv.split(";", 2)[0];
                        int eq = pair.indexOf('=');
                        if (eq > 0) {
                            String ck = pair.substring(0, eq).trim();
                            String cv = pair.substring(eq + 1).trim();
                            if (!ck.isEmpty() && !cv.isEmpty() && dedup.add("SCK|" + ck + "|" + cv)) {
                                out.add(new ExtractedCred(g.gid(), apiLabel, "Set-Cookie", ck, cv, RuleMode.COOKIE_SET));
                            }
                        }
                    }
                }
                int bodyOff = HttpBytes.bodyOffset(resp);
                if (bodyOff > 0 && bodyOff < resp.length) {
                    String body = new String(resp, bodyOff, Math.min(resp.length - bodyOff, 65536), StandardCharsets.UTF_8);
                    Matcher jm = JSON_TOKEN_PATTERN.matcher(body);
                    while (jm.find()) {
                        String tk = jm.group(1);
                        String tv = jm.group(2);
                        String bearer = tv.startsWith("Bearer ") ? tv : "Bearer " + tv;
                        if (dedup.add("JSON|" + bearer)) {
                            out.add(new ExtractedCred(g.gid(), apiLabel, "Resp JSON (" + tk + ")", "Authorization", bearer, RuleMode.REPLACE));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static String headerText(byte[] raw) {
        int off = HttpBytes.bodyOffset(raw);
        int len = off > 0 ? Math.min(off, raw.length) : Math.min(raw.length, 8192);
        return new String(raw, 0, len, StandardCharsets.ISO_8859_1);
    }

    private void pingRolesDialog() {
        List<ApiGroup> all = engine.groups();
        if (all.isEmpty()) {
            ui.info("Cần bắt ít nhất 1 API (khuyên dùng API GET kiểu /me hoặc /profile) để Ping kiểm tra Session.");
            return;
        }
        List<ApiGroup> ordered = new ArrayList<>();
        for (ApiGroup g : all) {
            if ("GET".equalsIgnoreCase(g.method())) {
                ordered.add(g);
            }
        }
        for (ApiGroup g : all) {
            if (!"GET".equalsIgnoreCase(g.method())) {
                ordered.add(g);
            }
        }
        JComboBox<ApiGroup> apiPicker = new JComboBox<>(ordered.toArray(new ApiGroup[0]));
        apiPicker.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof ApiGroup g) {
                    setText(Text.groupId(g.gid()) + "  " + g.method() + " " + Text.shortPath(g.path(), 56) + "  [" + g.branch() + "]");
                }
                return this;
            }
        });

        List<Role> enabledRoles = engine.roles().all().stream().filter(Role::enabled).toList();
        if (enabledRoles.isEmpty()) {
            ui.info("Chưa có Role nào đang bật để Ping.");
            return;
        }

        List<String[]> rows = new ArrayList<>();
        for (Role r : enabledRoles) {
            Engine.ProbeResult prev = probeResults.get(r.name());
            rows.add(new String[] {
                    r.name(),
                    "[" + r.tier().badge + "]",
                    prev == null ? "Chưa ping" : prev.badge(),
                    prev == null ? "-" : prev.elapsedMs() + " ms",
                    prev == null || prev.error() == null ? "" : prev.error()
            });
        }
        AbstractTableModel tm = new AbstractTableModel() {
            private final String[] cols = {"Role", "Cấp", "Trạng thái Session", "Thời gian", "Ghi chú / Lỗi"};
            @Override public int getRowCount() { return rows.size(); }
            @Override public int getColumnCount() { return cols.length; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Object getValueAt(int r, int c) { return rows.get(r)[c]; }
        };
        ThemedTable resTable = new ThemedTable(tm);
        Ui.style(resTable);
        Ui.widths(resTable, 165, 64, 115, 76, 260);
        resTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                Theme th = Theme.current();
                String s = v == null ? "" : v.toString();
                setFont(th.smallBoldFont);
                setHorizontalAlignment(CENTER);
                if (!sel) {
                    if (s.contains("LIVE")) {
                        setBackground(th.success.fill());
                        setForeground(th.success.text());
                    } else if (s.contains("DEAD") || s.contains("ERR")) {
                        setBackground(th.danger.fill());
                        setForeground(th.danger.text());
                    } else if (s.contains("DENY")) {
                        setBackground(th.warning.fill());
                        setForeground(th.warning.text());
                    } else {
                        setBackground(th.zebra[row & 1]);
                        setForeground(th.muted);
                    }
                }
                return this;
            }
        });

        JLabel statusLbl = Ui.muted("Chọn API GET mẫu (VD: /profile, /me) và bấm 'Chạy Ping' để kiểm tra toàn bộ " + enabledRoles.size() + " Role đang bật.");
        JDialog dlg = new JDialog(Ui.ownerWindow(SwingUtilities.getWindowAncestor(panel)), "Ping / Kiểm tra Session của các Role",
                JDialog.ModalityType.APPLICATION_MODAL);
        JButton runBtn = Ui.accent("Chạy Ping (" + enabledRoles.size() + " Role)",
                t -> Icons.bolt(t.accent.onSolid()), t -> t.accent, null);
        runBtn.addActionListener(Ui.safe(e -> {
            ApiGroup sample = (ApiGroup) apiPicker.getSelectedItem();
            if (sample == null) {
                return;
            }
            runBtn.setEnabled(false);
            AtomicInteger done = new AtomicInteger();
            statusLbl.setText("Đang Ping " + enabledRoles.size() + " Role với " + sample.method() + " " + sample.path() + "...");
            for (int i = 0; i < enabledRoles.size(); i++) {
                int idx = i;
                Role role = enabledRoles.get(i);
                rows.get(idx)[2] = "Đang gửi...";
                tm.fireTableRowsUpdated(idx, idx);
                engine.probeRoleAsync(sample, role, pr -> SwingUtilities.invokeLater(() -> {
                    probeResults.put(role.name(), pr);
                    rows.get(idx)[2] = pr.badge();
                    rows.get(idx)[3] = pr.elapsedMs() + " ms";
                    rows.get(idx)[4] = pr.error() != null ? pr.error() : (pr.alive() ? "Session còn sống" : "Kiểm tra lại token/cookie");
                    tm.fireTableRowsUpdated(idx, idx);
                    refreshCounts();
                    if (done.incrementAndGet() == enabledRoles.size()) {
                        runBtn.setEnabled(true);
                        long alive = enabledRoles.stream().filter(r -> {
                            Engine.ProbeResult p = probeResults.get(r.name());
                            return p != null && p.alive();
                        }).count();
                        statusLbl.setText("Hoàn tất Ping: " + alive + "/" + enabledRoles.size() + " Role LIVE (2xx/3xx).");
                    }
                }));
            }
        }));

        JPanel top = new JPanel(new BorderLayout(6, 4));
        top.add(Ui.bold("API mẫu để Ping:"), BorderLayout.WEST);
        top.add(apiPicker, BorderLayout.CENTER);
        top.add(runBtn, BorderLayout.EAST);

        JPanel body = new JPanel(new BorderLayout(6, 6));
        body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        body.add(top, BorderLayout.NORTH);
        body.add(MainPanel.scroll(resTable), BorderLayout.CENTER);
        JPanel foot = new JPanel(new BorderLayout(6, 0));
        foot.add(statusLbl, BorderLayout.CENTER);
        foot.add(Ui.button("Đóng", (String) null, dlg::dispose), BorderLayout.EAST);
        body.add(foot, BorderLayout.SOUTH);
        dlg.setContentPane(body);
        dlg.setSize(760, 400);
        dlg.setLocationRelativeTo(panel);
        dlg.setVisible(true);
    }

    private void compareRolesDialog() {
        List<Role> choices = new ArrayList<>();
        choices.add(engine.originalProfile());
        choices.addAll(engine.roles().all());
        if (choices.size() < 2) {
            ui.info("Cần ít nhất 2 Role để so sánh.");
            return;
        }
        JComboBox<Role> pickA = new JComboBox<>(choices.toArray(new Role[0]));
        JComboBox<Role> pickB = new JComboBox<>(choices.toArray(new Role[0]));
        DefaultListCellRenderer rdr = new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof Role r) {
                    setText("[" + r.tier().badge + "] " + r.name() + " (" + r.rules().size() + " rule)");
                }
                return this;
            }
        };
        pickA.setRenderer(rdr);
        pickB.setRenderer(rdr);
        pickA.setSelectedIndex(choices.size() > 1 ? 1 : 0);
        pickB.setSelectedIndex(choices.size() > 2 ? 2 : 0);

        List<String[]> diffRows = new ArrayList<>();
        AbstractTableModel tm = new AbstractTableModel() {
            private final String[] cols = {"Header / Cookie Key", "Role A", "Role B", "Trạng thái"};
            @Override public int getRowCount() { return diffRows.size(); }
            @Override public int getColumnCount() { return cols.length; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Object getValueAt(int r, int c) { return diffRows.get(r)[c]; }
        };
        Runnable rebuildDiff = () -> {
            diffRows.clear();
            Role a = (Role) pickA.getSelectedItem();
            Role b = (Role) pickB.getSelectedItem();
            if (a == null || b == null) {
                tm.fireTableDataChanged();
                return;
            }
            Map<String, HeaderRule> mapA = indexRules(a);
            Map<String, HeaderRule> mapB = indexRules(b);
            Set<String> keys = new LinkedHashSet<>();
            keys.addAll(mapA.keySet());
            keys.addAll(mapB.keySet());
            for (String k : keys) {
                HeaderRule ra = mapA.get(k);
                HeaderRule rb = mapB.get(k);
                String sa = ra == null ? "—" : ra.mode().name() + ": " + ra.value();
                String sb = rb == null ? "—" : rb.mode().name() + ": " + rb.value();
                String st;
                if (ra != null && rb != null) {
                    st = (ra.mode() == rb.mode() && ra.value().equals(rb.value())) ? "KHỚP" : "KHÁC GIÁ TRỊ";
                } else if (ra != null) {
                    st = "CHỈ Ở A";
                } else {
                    st = "CHỈ Ở B";
                }
                diffRows.add(new String[] {k, sa, sb, st});
            }
            tm.fireTableDataChanged();
        };
        pickA.addActionListener(Ui.safe(e -> rebuildDiff.run()));
        pickB.addActionListener(Ui.safe(e -> rebuildDiff.run()));
        rebuildDiff.run();

        ThemedTable diffTable = new ThemedTable(tm);
        Ui.style(diffTable);
        Ui.widths(diffTable, 160, 240, 240, 110);
        diffTable.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                Theme th = Theme.current();
                String s = v == null ? "" : v.toString();
                setFont(th.smallBoldFont);
                setHorizontalAlignment(CENTER);
                if (!sel) {
                    switch (s) {
                        case "KHỚP" -> { setBackground(th.success.fill()); setForeground(th.success.text()); }
                        case "KHÁC GIÁ TRỊ" -> { setBackground(th.warning.fill()); setForeground(th.warning.text()); }
                        default -> { setBackground(th.info.fill()); setForeground(th.info.text()); }
                    }
                }
                return this;
            }
        });

        JDialog dlg = new JDialog(Ui.ownerWindow(SwingUtilities.getWindowAncestor(panel)), "So sánh 2 Role (Diff Rules)",
                JDialog.ModalityType.APPLICATION_MODAL);
        JPanel pickBar = new JPanel(new GridLayout(1, 2, 8, 0));
        JPanel pA = new JPanel(new BorderLayout(4, 0));
        pA.add(Ui.bold("Role A:"), BorderLayout.WEST);
        pA.add(pickA, BorderLayout.CENTER);
        JPanel pB = new JPanel(new BorderLayout(4, 0));
        pB.add(Ui.bold("Role B:"), BorderLayout.WEST);
        pB.add(pickB, BorderLayout.CENTER);
        pickBar.add(pA);
        pickBar.add(pB);

        JPanel body = new JPanel(new BorderLayout(6, 6));
        body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        body.add(pickBar, BorderLayout.NORTH);
        body.add(MainPanel.scroll(diffTable), BorderLayout.CENTER);
        body.add(Ui.flow(FlowLayout.RIGHT, Ui.button("Đóng", (String) null, dlg::dispose)), BorderLayout.SOUTH);
        dlg.setContentPane(body);
        dlg.setSize(800, 420);
        dlg.setLocationRelativeTo(panel);
        dlg.setVisible(true);
    }

    private static Map<String, HeaderRule> indexRules(Role r) {
        Map<String, HeaderRule> m = new LinkedHashMap<>();
        for (HeaderRule hr : r.rules()) {
            String key = (hr.mode().cookie() ? "Cookie:" : "") + hr.name();
            m.put(key, hr);
        }
        return m;
    }

    private JPopupMenu ruleEditorMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("+ Header mới (REPLACE)", Icons::plus,
                () -> rules.add(new HeaderRule("X-Header", "value", RuleMode.REPLACE))));
        m.add(Ui.item("+ Set JWT / Bearer...", Icons::codeBraces, this::setJwt));
        m.add(Ui.item("+ Set Cookie / Session Key...", Icons::plus, this::setCookie));
        m.add(Ui.item("+ Trích xuất Token / Cookie từ API đã bắt...", t -> Icons.key(t.accent.text(), 13), this::extractTokenDialog));
        m.addSeparator();
        m.add(Ui.item("+ Strip Header ([REMOVE])...", Icons::minus, () -> {
            String n = ui.ask("Header cần xóa khỏi request của role:", "Authorization");
            HeaderRule r = n == null ? null : HeaderRule.of(n, RuleMode.REMOVE_TOKEN, "REMOVE");
            if (r != null) {
                rules.add(r);
            }
        }));
        m.add(Ui.item("+ Strip Cookie Key...", Icons::minus, () -> {
            String k = ui.ask("Cookie / Session key cần xóa:", "JSESSIONID");
            HeaderRule r = k == null ? null : HeaderRule.of(k, RuleMode.REMOVE_TOKEN, "COOKIE_REMOVE");
            if (r != null) {
                rules.add(r);
            }
        }));
        m.addSeparator();
        m.add(Ui.item("Paste Headers (dán nhiều dòng)...", Icons::copy, () -> paste(false)));
        m.add(Ui.item("Paste Cookies...", Icons::copy, () -> paste(true)));
        m.addSeparator();
        m.add(Ui.item("Di chuyển Rule lên (Rule Up)", Icons::arrowUp, () -> moveRule(-1)));
        m.add(Ui.item("Di chuyển Rule xuống (Rule Down)", Icons::arrowDown, () -> moveRule(1)));
        m.add(Ui.item("Xóa Rule đang chọn", t -> Icons.trash(t.danger.text()), this::removeRules));
        m.addSeparator();
        m.add(Ui.item("Chú giải các chế độ (cột Mode)...", Icons::info, this::showModeLegend));
        return m;
    }

    private JPopupMenu roleMenu(boolean onTable) {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Ping / Kiểm tra Session của các Role...", t -> Icons.bolt(t.info.text()), this::pingRolesDialog));
        m.add(Ui.item("So sánh 2 Role (Diff Rules)...", Icons::compare, this::compareRolesDialog));
        m.add(Ui.item("Trích xuất Token / Cookie từ API đã bắt...", t -> Icons.key(t.accent.text(), 13), this::extractTokenDialog));
        m.addSeparator();
        m.add(Ui.item("Sửa profile 00_API GỐC (Acc Crawl)...", t -> Icons.pin(t.orig.text()), this::editOriginal));
        m.add(Ui.item("Bật / Tắt profile 00_API GỐC", Icons::sliders, () -> {
            Role p = engine.originalProfile();
            engine.saveOriginalProfile(p.withEnabled(!p.enabled()));
            refreshProfile();
        }));
        m.addSeparator();
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
        m.add(Ui.item("Bật / Tắt Role theo phạm vi (nhánh / API)...", Icons::sliders,
                () -> ui.roleScopeDialog(false, RoleScopes.Scope.GLOBAL, null, null, null)));
        m.add(Ui.item("Bật tất cả Role (Enable All)...", t -> Icons.check(t.success.text()),
                () -> ui.roleScopeDialog(true, RoleScopes.Scope.GLOBAL, null, null,
                        engine.roles().all().stream().map(Role::name).toList())));
        m.add(Ui.item("Tắt tất cả Role (Disable All)...", Icons::minus,
                () -> ui.roleScopeDialog(false, RoleScopes.Scope.GLOBAL, null, null,
                        engine.roles().all().stream().map(Role::name).toList())));
        if (!onTable) {
            m.addSeparator();
            m.add(Ui.item("Import Roles từ JSON...", Icons::folder, this::importRoles));
            m.add(Ui.item("Export Roles ra JSON...", Icons::export, this::exportRoles));
            m.add(Ui.item("Nạp mẫu 10 Roles chuẩn (Load 10-Role Template)", t -> Icons.bolt(t.warning.text()), () -> {
                if (ui.confirmDialog("Thay danh sách role bằng mẫu 10 role chuẩn?")) {
                    engine.roles().setAll(Roles.template());
                    engine.saveRoles();
                    reload();
                }
            }));
        }
        m.addSeparator();
        m.add(Ui.item("Chú giải các chế độ (cột Mode)...", Icons::info, this::showModeLegend));
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
            int c = JOptionPane.showOptionDialog(Ui.ownerWindow(panel), "Nạp " + list.size() + " role:", "Import", JOptionPane.DEFAULT_OPTION,
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
        if (JOptionPane.showConfirmDialog(Ui.ownerWindow(panel), p, "Set JWT / Bearer", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
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
        if (JOptionPane.showConfirmDialog(Ui.ownerWindow(panel), p, "Set Cookie / Session Key", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
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
        if (JOptionPane.showConfirmDialog(Ui.ownerWindow(panel), p, cookies ? "Paste Cookies" : "Paste Headers", JOptionPane.OK_CANCEL_OPTION)
                == JOptionPane.OK_OPTION) {
            for (HeaderRule r : cookies ? Roles.parseBulkCookies(area.getText()) : Roles.parseBulkHeaders(area.getText())) {
                rules.add(r);
            }
        }
    }

    private void moveRule(int delta) {
        int r = rulesTable.getSelectedRow();
        if (r < 0) {
            Ui.hint(rulesTable, "Chọn 1 rule trong bảng trước rồi bấm Lên / Xuống");
            return;
        }
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
        if (rows.length == 0) {
            Ui.hint(rulesTable, "Chọn rule cần xóa trong bảng trước");
            return;
        }
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
        private final String[] cols = {"#", "Trạng thái", "Role & Cấp quyền", "Rules · JWT TTL · Session"};

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
                updateSummaryStrip();
                if (editing == r) {
                    setEnabledState(Boolean.TRUE.equals(v));
                }
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

        @Override public int getRowCount() { return list.size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public boolean isCellEditable(int r, int c) { return true; }

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

    /**
     * Sleek vector pill toggle switch renderer for the Roles status column:
     * paints a crisp {@link ModernToggleSwitch} track + thumb + subtle "Bật" / "Tắt" label,
     * harmonizing cleanly with both Light/Dark themes and row selection highlights.
     */
    static final class SquareStatusRenderer extends JComponent implements TableCellRenderer, ThemedTree.ExtensionRenderer {
        private Theme theme;
        private boolean on;
        private boolean selected;
        private int row;

        SquareStatusRenderer() {
            setOpaque(true);
        }

        void configure(Theme th, boolean on, boolean selected, int row) {
            this.theme = th != null ? th : Theme.current();
            this.on = on;
            this.selected = selected;
            this.row = row;
            setToolTipText(on ? "Role đang BẬT — bấm để tắt" : "Role đang TẮT — bấm để bật");
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            Theme th = t != null ? Theme.of(t.getBackground(), t.getForeground()) : Theme.current();
            configure(th, Boolean.TRUE.equals(v), sel, row);
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
                g2.setColor(selected ? th.selectionBg : th.zebra[row & 1]);
                g2.fillRect(0, 0, w, h);

                int sw = th.scaled(32);
                int sh = th.scaled(18);
                int gap = th.scaled(6);
                String lbl = on ? "Bật" : "Tắt";
                g2.setFont(th.smallBoldFont);
                FontMetrics fm = g2.getFontMetrics();
                int lw = fm.stringWidth(lbl);
                int totalW = sw + gap + lw;
                int sx = Math.max(th.scaled(6), (w - totalW) / 2);
                int sy = (h - sh) / 2;

                ModernToggleSwitch.paintSwitch(g2, th, sx, sy, sw, sh, on, false, false, true);

                g2.setColor(selected ? th.selectionFg : (on ? th.success.text() : th.muted));
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(lbl, sx + sw + gap, ty);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * Matching table cell editor for {@link SquareStatusRenderer}: renders the exact same
     * vector pill switch and toggles immediately on single click.
     */
    private static final class SquareStatusEditor extends AbstractCellEditor implements TableCellEditor {
        private final SquareStatusRenderer view = new SquareStatusRenderer();
        private boolean value;

        SquareStatusEditor() {
            view.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            view.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) {
                        cancelCellEditing();
                        return;
                    }
                    value = !value;
                    view.configure(Theme.current(), value, true, view.row);
                    view.repaint();
                    stopCellEditing();
                }
            });
        }

        @Override
        public Object getCellEditorValue() {
            return value;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object val, boolean isSelected, int row, int column) {
            this.value = Boolean.TRUE.equals(val);
            Theme th = table != null ? Theme.of(table.getBackground(), table.getForeground()) : Theme.current();
            view.configure(th, this.value, true, row);
            return view;
        }
    }

    private final class AvatarNameRenderer extends JComponent implements TableCellRenderer, ThemedTree.ExtensionRenderer {
        private Theme theme;
        private boolean selected;
        private boolean roleEnabled;
        private int modelRow;
        private String roleName = "";
        private Role.Tier tier = Role.Tier.CUSTOM;

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
            this.tier = r != null ? r.tier() : Role.Tier.CUSTOM;
            setToolTipText("[" + tier.badge + "] " + roleName + " — " + tier.label);
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
                Theme.Token tierTok = tierToken(th, tier);
                int bw = th.scaled(34);
                int bh = th.scaled(19);
                int by = (h - bh) / 2;
                int arc = th.scaled(4);
                g2.setColor(roleEnabled ? tierTok.fill() : th.surfaceAlt);
                g2.fillRoundRect(pad, by, bw - 1, bh - 1, arc, arc);
                g2.setColor(roleEnabled ? tierTok.border() : th.border);
                g2.drawRoundRect(pad, by, bw - 1, bh - 1, arc, arc);

                g2.setFont(th.smallBoldFont);
                FontMetrics bfm = g2.getFontMetrics();
                int btw = bfm.stringWidth(tier.badge);
                g2.setColor(roleEnabled ? tierTok.text() : th.muted);
                g2.drawString(tier.badge, pad + (bw - btw) / 2, by + (bh - bfm.getHeight()) / 2 + bfm.getAscent());

                int tx = pad + bw + th.scaled(7);
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

    private static Theme.Token tierToken(Theme th, Role.Tier tier) {
        if (tier == null) {
            return th.neutral;
        }
        return switch (tier) {
            case ADMIN -> th.critical;
            case MANAGER -> th.accent;
            case STAFF -> th.info;
            case USER -> th.success;
            case GUEST -> th.neutral;
            case CUSTOM -> th.dup;
        };
    }

    private final class RuleChipsRenderer extends JComponent implements TableCellRenderer, ThemedTree.ExtensionRenderer {
        private Theme theme;
        private boolean selected;
        private int modelRow;
        private Role role;
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
            this.role = modelRow >= 0 && modelRow < engine.roles().count() ? engine.roles().get(modelRow) : null;
            this.ruleList = role != null ? role.rules() : List.of();
            setToolTipText(role != null && !role.rules().isEmpty() ? role.rulesSummary() : "(không có rule - gửi giống API gốc)");
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
                g2.setFont(th.smallBoldFont);
                FontMetrics fm = g2.getFontMetrics();
                int ip = th.scaled(6);
                int gap = th.scaled(4);
                int pillH = Math.min(h - th.scaled(10), th.scaled(20));
                int pillY = (h - pillH) / 2;
                int maxRight = w - th.scaled(6);

                // 1) Live Session Ping badge (if probed)
                Engine.ProbeResult pr = role == null ? null : probeResults.get(role.name());
                if (pr != null) {
                    String badge = pr.badge();
                    int pw = ip * 2 + fm.stringWidth(badge);
                    if (x + pw <= maxRight) {
                        Theme.Token tok = pr.alive() ? th.success : (pr.dead() ? th.danger : th.warning);
                        drawSharpTextChip(g2, tok, x, pillY, pw, pillH, fm, badge);
                        x += pw + gap;
                    }
                }

                // 2) Live JWT TTL badge (if any JWT with exp exists)
                JwtTtl ttl = jwtTtlOf(role);
                if (ttl != null) {
                    String badge = ttl.compactLabel();
                    int pw = ip * 2 + fm.stringWidth(badge);
                    if (x + pw <= maxRight) {
                        Theme.Token tok = ttl.expired ? th.danger : (ttl.remainingSec < 300 ? th.warning : th.teal);
                        drawSharpTextChip(g2, tok, x, pillY, pw, pillH, fm, badge);
                        x += pw + gap;
                    }
                }

                // 3) Rule chips (sleek 4px rounded hairline badges)
                if (ruleList.isEmpty()) {
                    g2.setFont(th.italicFont);
                    FontMetrics ifm = g2.getFontMetrics();
                    g2.setColor(selected ? th.selectionFg : th.muted);
                    int ty = (h - ifm.getHeight()) / 2 + ifm.getAscent();
                    g2.drawString("(chưa có rule - gửi giống API gốc)", x, ty);
                    return;
                }

                g2.setFont(th.smallBoldFont);
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

        private void drawSharpTextChip(Graphics2D g2, Theme.Token tok, int x, int y, int w, int h, FontMetrics fm, String text) {
            int arc = 6;
            g2.setColor(tok.fill());
            g2.fillRoundRect(x, y, w - 1, h - 1, arc, arc);
            g2.setColor(tok.border());
            g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
            g2.setColor(tok.text());
            int ty = y + (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(text, x + (w - fm.stringWidth(text)) / 2, ty);
        }

        private void drawChip(Graphics2D g2, Theme.Token tok, int x, int y, int w, int h, FontMetrics fm, int len) {
            int arc = 6;
            g2.setColor(tok.fill());
            g2.fillRoundRect(x, y, w - 1, h - 1, arc, arc);
            g2.setColor(tok.border());
            g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
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
            l.setHorizontalAlignment(CENTER);
            l.setFont(th.smallBoldFont);
            if (!sel) {
                if (v instanceof RuleMode m) {
                    Theme.Token tok = th.mode(m);
                    l.setBackground(tok.fill());
                    l.setForeground(tok.text());
                    l.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(4, 6, 4, 6, th.zebra[row & 1]),
                            BorderFactory.createLineBorder(tok.border())));
                } else {
                    l.setBackground(th.zebra[row & 1]);
                    l.setForeground(t.getForeground());
                    l.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                }
            } else {
                l.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            }
            return l;
        }
    }
}
