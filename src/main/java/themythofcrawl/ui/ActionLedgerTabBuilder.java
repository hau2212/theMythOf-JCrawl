package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.ActionEngine.ActionMode;
import themythofcrawl.core.ActionPolicyLedger;
import themythofcrawl.core.ActionPolicyLedger.DeletedEntry;
import themythofcrawl.core.ActionPolicyLedger.EvaluationResult;
import themythofcrawl.core.ActionPolicyLedger.Preset;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.model.ActionPolicy;
import themythofcrawl.model.ActionPolicy.PathMatchType;
import themythofcrawl.model.ActionPolicy.ProxyEffect;
import themythofcrawl.model.ActionPolicy.ScopeType;
import themythofcrawl.model.ActionPolicy.Stage;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Branch;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * Builds the Unified Action Policy Ledger tab (Full + Extended CRUD, Multi-Scope Filter,
 * Audit Timestamps, Per-API Effective Policy Explainer, Presets, JSON Import/Export,
 * Emergency Reset All, and Soft-Delete Undo Trash) for {@link ActionWorkbenchDialog}.
 */
final class ActionLedgerTabBuilder {

    private ActionLedgerTabBuilder() {
    }

    static JPanel build(
            Component dialogParent,
            Engine engine,
            ApiGroup focusGroup,
            String prefillBranch,
            Runnable refreshBanner,
            Runnable onAfterAction) {
        Theme th = Theme.current();
        ActionPolicyLedger ledger = engine.actionPolicyLedger();

        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setOpaque(false);
        root.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        // ---- Top Filter & Global Control Strip
        JPanel topStrip = new JPanel(new BorderLayout(8, 4));
        topStrip.setOpaque(true);
        topStrip.setBackground(th.surfaceAlt);
        topStrip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.border),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        filterRow.setOpaque(false);

        JComboBox<String> scopeFilterCombo = new JComboBox<>(new String[] {
                "Mọi phạm vi",
                ScopeType.GLOBAL.label,
                ScopeType.METHOD_MATRIX.label,
                ScopeType.BRANCH.label,
                ScopeType.PATH_PATTERN.label,
                ScopeType.API_EXACT.label
        });
        JComboBox<String> stateFilterCombo = new JComboBox<>(new String[] {
                "Mọi trạng thái", "Đang BẬT", "Đang TẮT"
        });
        JTextField searchField = new JTextField(16);
        searchField.setToolTipText("Lọc nhanh theo tên chính sách, path, nhánh, method, nguồn tạo...");
        Pill summaryPill = new Pill(ledger.activeSummaryText(), t -> t.accent, t -> Icons.strategyShield(t.accent.text(), 12));

        filterRow.add(Ui.bold("Lọc:"));
        filterRow.add(scopeFilterCombo);
        filterRow.add(stateFilterCombo);
        filterRow.add(searchField);
        filterRow.add(summaryPill);

        JPanel topRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        topRight.setOpaque(false);
        JButton trashBtn = Ui.secondaryBtn("Thùng rác / Hoàn tác (0)", t -> Icons.undo(t.fg));
        JButton resetAllBtn = Ui.secondaryBtn("Nhả toàn bộ -> Nguyên bản", t -> Icons.trash(t.danger.text()));
        resetAllBtn.setToolTipText("Đưa toàn bộ chính sách vào Thùng rác Undo, tắt mọi chặn Proxy và chuyển về chế độ Nguyên bản");
        topRight.add(trashBtn);
        topRight.add(resetAllBtn);

        topStrip.add(filterRow, BorderLayout.CENTER);
        topStrip.add(topRight, BorderLayout.EAST);
        root.add(topStrip, BorderLayout.NORTH);

        // ---- Center: Top = Ledger Table + Bulk Toolbar, Bottom = CRUD Editor Form + Effective Explainer
        String[] cols = {
                "#", "Bật/Tắt", "Phạm vi", "Đối tượng áp dụng", "Method",
                "Giai đoạn & Hành vi", "Tạo lúc", "Sửa lúc", "Khớp cuối", "Hits", "Nguồn"
        };
        DefaultTableModel tableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        JTable ledgerTable = new ThemedTable(tableModel);
        ledgerTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        ledgerTable.setRowHeight(26);
        int[] widths = {34, 62, 108, 190, 92, 175, 86, 86, 86, 46, 92};
        for (int i = 0; i < widths.length; i++) {
            ledgerTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        JScrollPane tableScroll = new JScrollPane(ledgerTable);
        tableScroll.setColumnHeaderView(ledgerTable.getTableHeader());
        tableScroll.setBorder(BorderFactory.createLineBorder(th.border));

        final List<ActionPolicy> displayedRows = new ArrayList<>();
        final String[] editingPolicyId = {null};

        // Bulk & Table Toolbar
        JPanel tableBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        tableBtns.setOpaque(false);
        JButton toggleSelBtn = Ui.secondaryBtn("Bật / Tắt", t -> Icons.sliders(t.fg));
        JButton dupBtn = Ui.secondaryBtn("Nhân bản", t -> Icons.dup(t.fg));
        JButton upBtn = Ui.secondaryBtn("Lên", null);
        JButton downBtn = Ui.secondaryBtn("Xuống", null);
        JButton topBtn = Ui.secondaryBtn("Lên đầu", null);
        JButton deleteBtn = Ui.secondaryBtn("Xóa chọn", t -> Icons.trash(t.danger.text()));
        JButton presetBtn = Ui.secondaryBtn("Bộ mẫu nhanh", t -> Icons.plus(t.accent.text()));
        JButton importBtn = Ui.secondaryBtn("Import JSON", null);
        JButton exportBtn = Ui.secondaryBtn("Export JSON", null);
        JButton clearHitsBtn = Ui.secondaryBtn("Reset Hits", null);

        tableBtns.add(toggleSelBtn);
        tableBtns.add(dupBtn);
        tableBtns.add(upBtn);
        tableBtns.add(downBtn);
        tableBtns.add(topBtn);
        tableBtns.add(deleteBtn);
        tableBtns.add(Box.createHorizontalStrut(6));
        tableBtns.add(presetBtn);
        tableBtns.add(importBtn);
        tableBtns.add(exportBtn);
        tableBtns.add(clearHitsBtn);

        JPanel tableWrap = new JPanel(new BorderLayout(0, 2));
        tableWrap.setOpaque(false);
        tableWrap.add(tableScroll, BorderLayout.CENTER);
        tableWrap.add(tableBtns, BorderLayout.SOUTH);

        // ---- Bottom Form: Full CRUD Editor
        JPanel formCard = new JPanel(new BorderLayout(0, 4));
        formCard.setOpaque(true);
        formCard.setBackground(th.surfaceAlt);
        formCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.border),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        JLabel formHeader = Ui.bold("Tạo mới / Chỉnh sửa Chính sách Action (CRUD)");
        formCard.add(formHeader, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 4, 3, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;

        JTextField nameField = new JTextField(22);
        JCheckBox enabledChk = new JCheckBox("Đang BẬT", true);
        enabledChk.setOpaque(false);

        JComboBox<ScopeType> scopeCombo = new JComboBox<>(ScopeType.values());
        scopeCombo.setRenderer((list, val, idx, sel, foc) -> {
            JLabel l = new JLabel(val != null ? val.label : "");
            l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return l;
        });

        JComboBox<Stage> stageCombo = new JComboBox<>(Stage.values());
        stageCombo.setRenderer((list, val, idx, sel, foc) -> {
            JLabel l = new JLabel(val != null ? val.label : "");
            l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return l;
        });

        JComboBox<ProxyEffect> proxyEffectCombo = new JComboBox<>(ProxyEffect.values());
        proxyEffectCombo.setRenderer((list, val, idx, sel, foc) -> {
            JLabel l = new JLabel(val != null ? val.label : "");
            l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return l;
        });
        proxyEffectCombo.setSelectedItem(ProxyEffect.INTERCEPT_WAIT);

        JComboBox<ActionMode> replayModeCombo = new JComboBox<>(ActionMode.values());
        replayModeCombo.setRenderer((list, val, idx, sel, foc) -> {
            JLabel l = new JLabel(val != null ? val.shortTitle() : "");
            l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return l;
        });
        replayModeCombo.setSelectedItem(ActionMode.NONE);

        JTextField branchesField = new JTextField(22);
        branchesField.setToolTipText("Nhiều nhánh cách nhau bằng dấu chấm phẩy ';'");
        JComboBox<String> branchPicker = new JComboBox<>();
        branchPicker.addItem("(Chọn nhanh nhánh để thêm...)");
        for (Branch b : engine.branches()) {
            branchPicker.addItem(b.name());
        }
        JCheckBox subBranchChk = new JCheckBox("Gồm nhánh con", true);
        subBranchChk.setOpaque(false);

        branchPicker.addActionListener(e -> {
            if (branchPicker.getSelectedIndex() <= 0) return;
            String picked = String.valueOf(branchPicker.getSelectedItem());
            String cur = branchesField.getText().trim();
            if (cur.isEmpty()) {
                branchesField.setText(picked);
            } else if (!cur.toLowerCase().contains(picked.toLowerCase())) {
                branchesField.setText(cur + "; " + picked);
            }
            branchPicker.setSelectedIndex(0);
        });

        JComboBox<PathMatchType> pathTypeCombo = new JComboBox<>(PathMatchType.values());
        pathTypeCombo.setRenderer((list, val, idx, sel, foc) -> {
            JLabel l = new JLabel(val != null ? val.label : "");
            l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return l;
        });
        JTextField pathField = new JTextField(20);
        JButton insertIdBtn = Ui.secondaryBtn("+ {id}", null);
        insertIdBtn.setToolTipText("Chèn placeholder {id} vào mẫu đường dẫn");
        insertIdBtn.addActionListener(e -> {
            String t = pathField.getText();
            pathField.setText(t.endsWith("/") ? t + "{id}" : t + "/{id}");
            pathTypeCombo.setSelectedItem(PathMatchType.TEMPLATE);
        });
        JTextField hostField = new JTextField(12);
        hostField.setToolTipText("Để trống = mọi Host (hỗ trợ wildcard *)");

        // Method Matrix Toggle Chips
        JPanel methodChipsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        methodChipsPanel.setOpaque(false);
        Map<String, JToggleButton> methodBtns = new LinkedHashMap<>();
        for (String m : ActionPolicy.ALL_METHODS) {
            boolean defOn = ActionEngine.isActionMethod(m);
            JToggleButton tb = new JToggleButton(m, defOn);
            tb.setFocusable(false);
            tb.setMargin(new Insets(2, 7, 2, 7));
            methodBtns.put(m, tb);
            methodChipsPanel.add(tb);
        }
        JButton onlyActionBtn = Ui.secondaryBtn("Chỉ Action", null);
        onlyActionBtn.addActionListener(e -> methodBtns.forEach((m, b) -> b.setSelected(ActionEngine.isActionMethod(m))));
        JButton allMethodBtn = Ui.secondaryBtn("Tất cả", null);
        allMethodBtn.addActionListener(e -> methodBtns.forEach((m, b) -> b.setSelected(true)));
        methodChipsPanel.add(onlyActionBtn);
        methodChipsPanel.add(allMethodBtn);

        // Row 0: Name + Scope + Enabled
        gc.gridy = 0;
        gc.gridx = 0; gc.weightx = 0; grid.add(Ui.muted("Tên chính sách:"), gc);
        gc.gridx = 1; gc.weightx = 0.45; grid.add(nameField, gc);
        gc.gridx = 2; gc.weightx = 0; grid.add(Ui.muted("Phạm vi:"), gc);
        gc.gridx = 3; gc.weightx = 0.35; grid.add(scopeCombo, gc);
        gc.gridx = 4; gc.weightx = 0; grid.add(enabledChk, gc);

        // Row 1: Branches + Branch Picker + Sub-branch
        gc.gridy = 1;
        gc.gridx = 0; gc.weightx = 0; grid.add(Ui.muted("Nhánh cây:"), gc);
        gc.gridx = 1; gc.weightx = 0.45; grid.add(branchesField, gc);
        gc.gridx = 2; gc.weightx = 0; grid.add(Ui.muted("Chọn nhanh:"), gc);
        gc.gridx = 3; gc.weightx = 0.35; grid.add(branchPicker, gc);
        gc.gridx = 4; gc.weightx = 0; grid.add(subBranchChk, gc);

        // Row 2: Path pattern + Type + {id} + Host
        JPanel pathWrap = new JPanel(new BorderLayout(4, 0));
        pathWrap.setOpaque(false);
        pathWrap.add(pathTypeCombo, BorderLayout.WEST);
        pathWrap.add(pathField, BorderLayout.CENTER);
        pathWrap.add(insertIdBtn, BorderLayout.EAST);

        gc.gridy = 2;
        gc.gridx = 0; gc.weightx = 0; grid.add(Ui.muted("Đường dẫn:"), gc);
        gc.gridx = 1; gc.gridwidth = 2; gc.weightx = 0.6; grid.add(pathWrap, gc);
        gc.gridwidth = 1;
        gc.gridx = 3; gc.weightx = 0.2;
        JPanel hostWrap = new JPanel(new BorderLayout(4, 0));
        hostWrap.setOpaque(false);
        hostWrap.add(Ui.muted("Host:"), BorderLayout.WEST);
        hostWrap.add(hostField, BorderLayout.CENTER);
        grid.add(hostWrap, gc);
        gc.gridx = 4; gc.weightx = 0; grid.add(Box.createHorizontalStrut(4), gc);

        // Row 3: Stage + ProxyEffect + ReplayMode
        gc.gridy = 3;
        gc.gridx = 0; gc.weightx = 0; grid.add(Ui.muted("Giai đoạn:"), gc);
        gc.gridx = 1; gc.weightx = 0.3; grid.add(stageCombo, gc);
        gc.gridx = 2; gc.weightx = 0; grid.add(Ui.muted("Hành động Proxy:"), gc);
        gc.gridx = 3; gc.weightx = 0.4; grid.add(proxyEffectCombo, gc);
        gc.gridx = 4; gc.weightx = 0.2; grid.add(replayModeCombo, gc);

        // Row 4: Method Matrix
        gc.gridy = 4;
        gc.gridx = 0; gc.weightx = 0; grid.add(Ui.muted("HTTP Method:"), gc);
        gc.gridx = 1; gc.gridwidth = 4; gc.weightx = 1.0; grid.add(methodChipsPanel, gc);
        gc.gridwidth = 1;

        formCard.add(grid, BorderLayout.CENTER);

        // Form Footer Buttons
        JPanel formBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        formBtns.setOpaque(false);
        JButton createBtn = Ui.primaryBtn("Thêm chính sách mới (Create)", t -> Icons.plus(t.accent.text()));
        JButton updateBtn = Ui.secondaryBtn("Cập nhật dòng đang chọn (Update)", t -> Icons.check(t.success.text()));
        updateBtn.setEnabled(false);
        JButton clearFormBtn = Ui.secondaryBtn("Làm mới Form", null);
        formBtns.add(clearFormBtn);
        formBtns.add(updateBtn);
        formBtns.add(createBtn);
        formCard.add(formBtns, BorderLayout.SOUTH);

        // Prefill context if opened for a specific branch or API
        if (prefillBranch != null && !prefillBranch.isBlank()) {
            scopeCombo.setSelectedItem(ScopeType.BRANCH);
            branchesField.setText(Hierarchy.normalize(prefillBranch));
            nameField.setText("Chặn nhánh " + Hierarchy.leaf(prefillBranch));
        } else if (focusGroup != null) {
            scopeCombo.setSelectedItem(ScopeType.API_EXACT);
            pathTypeCombo.setSelectedItem(PathMatchType.TEMPLATE);
            pathField.setText(ActionEngine.normalizePathTemplate(focusGroup.path()));
            hostField.setText(focusGroup.host());
            methodBtns.forEach((m, b) -> b.setSelected(m.equalsIgnoreCase(focusGroup.method())));
        }

        JSplitPane centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableWrap, formCard);
        centerSplit.setResizeWeight(0.56);
        centerSplit.setDividerSize(6);
        centerSplit.setBorder(null);
        root.add(centerSplit, BorderLayout.CENTER);

        // ---- Bottom Per-API Effective Policy Explainer (when focusGroup != null)
        JPanel explainerPanel = new JPanel(new BorderLayout(8, 4));
        explainerPanel.setOpaque(true);
        explainerPanel.setBackground(th.surfaceAlt);
        explainerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.border),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        JLabel explainerText = Ui.muted("");
        JPanel explainerBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        explainerBtns.setOpaque(false);

        if (focusGroup != null) {
            JButton releaseApiBtn = Ui.secondaryBtn("Nhả API này về Nguyên bản", t -> Icons.undo(t.fg));
            JButton allowApiBtn = Ui.secondaryBtn("+ Cho qua luôn (ALLOW)", t -> Icons.check(t.success.text()));
            JButton composerBtn = Ui.secondaryBtn("Sửa & Gửi lại Request", t -> Icons.edit(t.accent.text()));
            explainerBtns.add(releaseApiBtn);
            explainerBtns.add(allowApiBtn);
            explainerBtns.add(composerBtn);
            explainerPanel.add(explainerText, BorderLayout.CENTER);
            explainerPanel.add(explainerBtns, BorderLayout.EAST);
            root.add(explainerPanel, BorderLayout.SOUTH);

            releaseApiBtn.addActionListener(e -> {
                engine.releaseApiToNone(focusGroup, false);
                if (refreshBanner != null) refreshBanner.run();
                if (onAfterAction != null) onAfterAction.run();
            });
            allowApiBtn.addActionListener(e -> {
                engine.releaseApiToNone(focusGroup, true);
                if (refreshBanner != null) refreshBanner.run();
                if (onAfterAction != null) onAfterAction.run();
            });
            composerBtn.addActionListener(e -> RequestComposerDialog.open(dialogParent, engine, focusGroup, null, null));
        }

        // ---- Refresh Logic
        Runnable refreshAll = () -> {
            ScopeType sf = switch (scopeFilterCombo.getSelectedIndex()) {
                case 1 -> ScopeType.GLOBAL;
                case 2 -> ScopeType.METHOD_MATRIX;
                case 3 -> ScopeType.BRANCH;
                case 4 -> ScopeType.PATH_PATTERN;
                case 5 -> ScopeType.API_EXACT;
                default -> null;
            };
            Boolean ef = switch (stateFilterCombo.getSelectedIndex()) {
                case 1 -> Boolean.TRUE;
                case 2 -> Boolean.FALSE;
                default -> null;
            };
            List<ActionPolicy> allPolicies = ledger.all();
            java.util.Map<String, Integer> rankById = new java.util.HashMap<>(allPolicies.size() * 2);
            for (int i = 0; i < allPolicies.size(); i++) {
                rankById.put(allPolicies.get(i).id(), i + 1);
            }
            List<ActionPolicy> list = ledger.filter(sf, ef, null, null, searchField.getText());
            displayedRows.clear();
            displayedRows.addAll(list);
            tableModel.setRowCount(0);
            for (ActionPolicy p : list) {
                int rank = rankById.getOrDefault(p.id(), tableModel.getRowCount() + 1);
                tableModel.addRow(new Object[] {
                        "#" + rank,
                        p.enabled() ? "BẬT" : "TẮT",
                        p.scopeType().label,
                        p.targetSummary(),
                        p.methodsLabel(),
                        p.behaviorSummary(),
                        ActionPolicy.formatTimestamp(p.createdAt()),
                        ActionPolicy.formatTimestamp(p.updatedAt()),
                        ActionPolicy.formatTimestamp(p.lastHitAt()),
                        p.hitCount(),
                        p.createdFrom()
                });
            }
            summaryPill.setText(ledger.activeSummaryText());
            summaryPill.setToken(ledger.activeCount() > 0 ? t -> t.warning : t -> t.success);
            trashBtn.setText("Thùng rác / Hoàn tác (" + ledger.trashSize() + ")");
            trashBtn.setEnabled(ledger.trashSize() > 0);

            if (focusGroup != null) {
                EvaluationResult pEv = ledger.evaluateProxy(
                        focusGroup.branch(), focusGroup.host(), focusGroup.method(), focusGroup.path(), focusGroup.gid());
                ActionMode effReplay = engine.effectiveActionMode(focusGroup);
                String chain = pEv.allMatched().isEmpty()
                        ? "Không có luật Proxy nào chạm vào " + Text.apiLabel(focusGroup.gid()) + " -> Đi thẳng Nguyên bản."
                        : "Hiệu lực cho " + Text.apiLabel(focusGroup.gid()) + ": THẮNG = [" + pEv.winningPolicy().name()
                                + " (" + pEv.effectiveProxyEffect().label + ")]"
                                + (pEv.allMatched().size() > 1
                                        ? " · Ghi đè " + (pEv.allMatched().size() - 1) + " luật ưu tiên thấp hơn." : ".");
                explainerText.setText(chain + "  |  Replay hiện tại: " + effReplay.shortTitle());
            }
            if (refreshBanner != null) refreshBanner.run();
        };

        // Wire filters
        scopeFilterCombo.addActionListener(e -> refreshAll.run());
        stateFilterCombo.addActionListener(e -> refreshAll.run());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshAll.run(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshAll.run(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshAll.run(); }
        });

        // Populate form on row selection
        ledgerTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = ledgerTable.getSelectedRow();
            if (row < 0 || row >= displayedRows.size()) {
                editingPolicyId[0] = null;
                updateBtn.setEnabled(false);
                return;
            }
            ActionPolicy p = displayedRows.get(row);
            editingPolicyId[0] = p.id();
            updateBtn.setEnabled(true);
            nameField.setText(p.rawName());
            enabledChk.setSelected(p.enabled());
            scopeCombo.setSelectedItem(p.scopeType());
            stageCombo.setSelectedItem(p.stage());
            proxyEffectCombo.setSelectedItem(p.proxyEffect());
            replayModeCombo.setSelectedItem(p.replayMode());
            branchesField.setText(String.join("; ", p.targetBranches()));
            subBranchChk.setSelected(p.includeSubBranches());
            pathTypeCombo.setSelectedItem(p.pathMatchType());
            pathField.setText(p.pathPattern());
            hostField.setText(p.hostPattern());
            Set<String> ms = p.methods();
            methodBtns.forEach((m, b) -> b.setSelected(ms.isEmpty() || ms.contains(m)));
        });

        // Build policy from form helper
        java.util.function.Function<String, ActionPolicy> buildFromForm = existingId -> {
            ScopeType sc = (ScopeType) scopeCombo.getSelectedItem();
            Stage st = (Stage) stageCombo.getSelectedItem();
            ProxyEffect pe = (ProxyEffect) proxyEffectCombo.getSelectedItem();
            ActionMode rm = (ActionMode) replayModeCombo.getSelectedItem();
            PathMatchType pt = (PathMatchType) pathTypeCombo.getSelectedItem();
            List<String> branches = new ArrayList<>();
            for (String seg : branchesField.getText().split("[;,]")) {
                if (!seg.trim().isEmpty()) {
                    branches.add(Hierarchy.normalize(seg.trim()));
                }
            }
            Set<String> selectedMethods = new LinkedHashSet<>();
            methodBtns.forEach((m, b) -> {
                if (b.isSelected()) selectedMethods.add(m);
            });
            if (selectedMethods.size() == ActionPolicy.ALL_METHODS.size()) {
                selectedMethods.clear(); // empty = all methods
            }
            Integer apiGid = (sc == ScopeType.API_EXACT && focusGroup != null) ? focusGroup.gid() : null;
            int prio = existingId != null && ledger.findById(existingId) != null
                    ? ledger.findById(existingId).priority() : -1;
            long now = System.currentTimeMillis();
            return new ActionPolicy(
                    existingId, nameField.getText().trim(), "", enabledChk.isSelected(), prio,
                    sc, branches, subBranchChk.isSelected(), hostField.getText().trim(),
                    pt, pathField.getText().trim(), apiGid, selectedMethods,
                    st, pe, rm, 200, null, null, "",
                    now, now, existingId == null ? "Sổ cái CRUD" : "Chỉnh sửa CRUD", 0L, "", 0);
        };

        createBtn.addActionListener(e -> {
            ActionPolicy created = buildFromForm.apply(null);
            if (created.proxyEffect() == ProxyEffect.ALLOW_EXPLICIT) {
                ledger.createAtTop(created);
            } else {
                ledger.upsert(created);
            }
            engine.saveActionPolicies();
            refreshAll.run();
            if (onAfterAction != null) onAfterAction.run();
        });

        updateBtn.addActionListener(e -> {
            if (editingPolicyId[0] == null) return;
            ActionPolicy updated = buildFromForm.apply(editingPolicyId[0]);
            ledger.upsert(updated);
            engine.saveActionPolicies();
            refreshAll.run();
            if (onAfterAction != null) onAfterAction.run();
        });

        clearFormBtn.addActionListener(e -> {
            editingPolicyId[0] = null;
            ledgerTable.clearSelection();
            updateBtn.setEnabled(false);
            nameField.setText("");
            enabledChk.setSelected(true);
            scopeCombo.setSelectedItem(ScopeType.PATH_PATTERN);
            stageCombo.setSelectedItem(Stage.PROXY_GATE);
            proxyEffectCombo.setSelectedItem(ProxyEffect.INTERCEPT_WAIT);
            replayModeCombo.setSelectedItem(ActionMode.NONE);
            branchesField.setText("");
            pathField.setText("");
            hostField.setText("");
            methodBtns.forEach((m, b) -> b.setSelected(ActionEngine.isActionMethod(m)));
        });

        // Bulk & Table button actions
        java.util.function.Supplier<List<String>> selectedIds = () -> {
            List<String> ids = new ArrayList<>();
            for (int r : ledgerTable.getSelectedRows()) {
                if (r >= 0 && r < displayedRows.size()) {
                    ids.add(displayedRows.get(r).id());
                }
            }
            return ids;
        };

        toggleSelBtn.addActionListener(e -> {
            List<String> ids = selectedIds.get();
            if (ids.isEmpty()) return;
            boolean anyDisabled = false;
            for (String id : ids) {
                ActionPolicy p = ledger.findById(id);
                if (p != null && !p.enabled()) {
                    anyDisabled = true;
                    break;
                }
            }
            ledger.bulkSetEnabled(ids, anyDisabled);
            engine.saveActionPolicies();
            refreshAll.run();
            if (onAfterAction != null) onAfterAction.run();
        });

        dupBtn.addActionListener(e -> {
            List<String> ids = selectedIds.get();
            if (ids.isEmpty()) return;
            for (String id : ids) {
                ledger.duplicate(id);
            }
            engine.saveActionPolicies();
            refreshAll.run();
            if (onAfterAction != null) onAfterAction.run();
        });

        upBtn.addActionListener(e -> {
            List<String> ids = selectedIds.get();
            if (ids.size() == 1 && ledger.movePriority(ids.get(0), -1)) {
                engine.saveActionPolicies();
                refreshAll.run();
            }
        });

        downBtn.addActionListener(e -> {
            List<String> ids = selectedIds.get();
            if (ids.size() == 1 && ledger.movePriority(ids.get(0), 1)) {
                engine.saveActionPolicies();
                refreshAll.run();
            }
        });

        topBtn.addActionListener(e -> {
            List<String> ids = selectedIds.get();
            if (ids.size() == 1 && ledger.moveToTop(ids.get(0))) {
                engine.saveActionPolicies();
                refreshAll.run();
            }
        });

        deleteBtn.addActionListener(e -> {
            List<String> ids = selectedIds.get();
            if (ids.isEmpty()) return;
            ledger.bulkDelete(ids, "Xóa từ Sổ cái");
            engine.saveActionPolicies();
            refreshAll.run();
            if (onAfterAction != null) onAfterAction.run();
        });

        presetBtn.addActionListener(e -> {
            JPopupMenu pm = new JPopupMenu();
            for (Preset pr : Preset.values()) {
                pm.add(Ui.item(pr.label, null, () -> {
                    ledger.applyPreset(pr);
                    engine.saveActionPolicies();
                    refreshAll.run();
                    if (onAfterAction != null) onAfterAction.run();
                }));
            }
            pm.show(presetBtn, 0, presetBtn.getHeight());
        });

        importBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("JSON Policy (*.json)", "json"));
            if (fc.showOpenDialog(dialogParent) == JFileChooser.APPROVE_OPTION) {
                try {
                    String json = Files.readString(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8);
                    int choice = JOptionPane.showOptionDialog(dialogParent,
                            "Chọn cách nhập bộ Chính sách Action từ file JSON:",
                            "Import JSON Chính sách",
                            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                            new String[] {"Gộp thêm vào Sổ cái", "Thay thế toàn bộ (cũ vào Thùng rác)", "Hủy"},
                            "Gộp thêm vào Sổ cái");
                    if (choice == 0 || choice == 1) {
                        int n = ledger.importJson(json, choice == 1);
                        engine.saveActionPolicies();
                        refreshAll.run();
                        if (onAfterAction != null) onAfterAction.run();
                        JOptionPane.showMessageDialog(dialogParent, "Đã nhập " + n + " chính sách thành công.");
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialogParent, "Lỗi đọc file JSON: " + ex.getMessage());
                }
            }
        });

        exportBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("jcrawl_action_policies.json"));
            fc.setFileFilter(new FileNameExtensionFilter("JSON Policy (*.json)", "json"));
            if (fc.showSaveDialog(dialogParent) == JFileChooser.APPROVE_OPTION) {
                try {
                    List<String> ids = selectedIds.get();
                    String json = ledger.exportJson(ids.isEmpty() ? null : ids);
                    Files.writeString(fc.getSelectedFile().toPath(), json, StandardCharsets.UTF_8);
                    JOptionPane.showMessageDialog(dialogParent,
                            "Đã xuất " + (ids.isEmpty() ? ledger.size() : ids.size()) + " chính sách ra file JSON.");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialogParent, "Lỗi ghi file JSON: " + ex.getMessage());
                }
            }
        });

        clearHitsBtn.addActionListener(e -> {
            ledger.resetAllHitCounts();
            engine.saveActionPolicies();
            refreshAll.run();
        });

        resetAllBtn.addActionListener(e -> {
            int ok = JOptionPane.showConfirmDialog(dialogParent,
                    "Nhả toàn bộ cấu hình Action về Nguyên bản (NONE)?\n"
                            + "Mọi chính sách hiện có sẽ được chuyển an toàn vào Thùng rác Hoàn tác để khôi phục khi cần.",
                    "Xác nhận Nhả toàn bộ (Reset All)", JOptionPane.YES_NO_OPTION);
            if (ok == JOptionPane.YES_OPTION) {
                engine.resetAllActionControls();
                refreshAll.run();
                if (onAfterAction != null) onAfterAction.run();
            }
        });

        trashBtn.addActionListener(e -> showTrashDialog(dialogParent, engine, refreshAll, onAfterAction));

        // Live sync when ledger changes from other tabs (e.g. Gate Auditor or Quick Strategy)
        ledger.addListener(() -> SwingUtilities.invokeLater(refreshAll));
        refreshAll.run();
        return root;
    }

    private static void showTrashDialog(Component parent, Engine engine, Runnable refreshAll, Runnable onAfterAction) {
        ActionPolicyLedger ledger = engine.actionPolicyLedger();
        List<DeletedEntry> items = ledger.trash();
        if (items.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "Thùng rác Hoàn tác đang trống.");
            return;
        }
        JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(parent),
                "Thùng rác Hoàn tác Chính sách (" + items.size() + " mục)", JDialog.ModalityType.APPLICATION_MODAL);
        Theme th = Theme.current();
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBackground(th.surface);
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        DefaultTableModel tm = new DefaultTableModel(
                new String[] {"Xóa lúc", "Tên chính sách", "Phạm vi", "Hành vi", "Lý do xóa"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        for (DeletedEntry d : items) {
            tm.addRow(new Object[] {
                    ActionPolicy.formatTimestamp(d.deletedAt()),
                    d.policy().name(),
                    d.policy().scopeType().label,
                    d.policy().behaviorSummary(),
                    d.reason()
            });
        }
        JTable tbl = new ThemedTable(tm);
        tbl.setRowHeight(25);
        tbl.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (tm.getRowCount() > 0) tbl.setRowSelectionInterval(0, 0);
        JScrollPane sc = new JScrollPane(tbl);
        sc.setColumnHeaderView(tbl.getTableHeader());
        p.add(sc, BorderLayout.CENTER);

        JPanel foot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        foot.setOpaque(false);
        JButton restoreBtn = Ui.primaryBtn("Khôi phục mục đang chọn", t -> Icons.undo(t.accent.text()));
        JButton restoreAllBtn = Ui.secondaryBtn("Khôi phục tất cả", null);
        JButton closeBtn = Ui.secondaryBtn("Đóng", null);

        restoreBtn.addActionListener(e -> {
            int row = tbl.getSelectedRow();
            if (row >= 0 && row < items.size()) {
                ledger.restoreFromTrash(items.get(row).policy().id());
                engine.saveActionPolicies();
                refreshAll.run();
                if (onAfterAction != null) onAfterAction.run();
                dlg.dispose();
            }
        });
        restoreAllBtn.addActionListener(e -> {
            for (DeletedEntry d : items) {
                ledger.restoreFromTrash(d.policy().id());
            }
            engine.saveActionPolicies();
            refreshAll.run();
            if (onAfterAction != null) onAfterAction.run();
            dlg.dispose();
        });
        closeBtn.addActionListener(e -> dlg.dispose());

        foot.add(restoreAllBtn);
        foot.add(restoreBtn);
        foot.add(closeBtn);
        p.add(foot, BorderLayout.SOUTH);

        dlg.setContentPane(p);
        Theme.track(dlg);
        dlg.setSize(new Dimension(780, 420));
        dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true);
    }
}
