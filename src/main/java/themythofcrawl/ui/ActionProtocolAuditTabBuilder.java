package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.ActionGateAuditLog;
import themythofcrawl.core.ActionGateAuditLog.Entry;
import themythofcrawl.core.ActionPolicyLedger;
import themythofcrawl.core.ActionPolicyLedger.SimulationReport;
import themythofcrawl.core.ActionPolicyLedger.SimulationRow;
import themythofcrawl.core.Text;
import themythofcrawl.model.ActionPolicy;
import themythofcrawl.model.ActionPolicy.PathMatchType;
import themythofcrawl.model.ActionPolicy.ProxyEffect;
import themythofcrawl.model.ActionPolicy.ScopeType;
import themythofcrawl.model.ActionPolicy.Stage;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Branch;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SegmentedToggle;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * Builds the Action Protocol Gate Auditor &amp; Batch Pre-flight Simulator tab (V_3.4_beta).
 * <ul>
 *   <li><b>Live Traffic Probe</b>: records every Action protocol request seen at Proxy (both BLOCKED and PASSED)
 *       with 1-click calibration buttons (Chặn chờ duyệt / Giữ Proxy / Cho qua luôn ALLOW).</li>
 *   <li><b>Batch Pre-flight Simulator</b>: dry-runs all captured APIs (or a chosen Branch) against the live
 *       {@link ActionPolicyLedger} with zero network calls so users can fine-tune rules before clicking target UI.</li>
 * </ul>
 */
final class ActionProtocolAuditTabBuilder {

    private ActionProtocolAuditTabBuilder() {
    }

    static JPanel build(
            Component dialogParent,
            Engine engine,
            Runnable refreshBanner,
            Runnable onAfterAction) {
        Theme th = Theme.current();
        ActionGateAuditLog auditLog = engine.actionGateAuditLog();
        ActionPolicyLedger ledger = engine.actionPolicyLedger();

        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setOpaque(false);
        root.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        // ---- Top Mode Switcher: Live Traffic Probe vs Batch Pre-flight Simulator
        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setOpaque(true);
        topBar.setBackground(th.surfaceAlt);
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.border),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        SegmentedToggle modeToggle = new SegmentedToggle(
                "Nhật ký Cổng Action trực tiếp (Live Proxy)",
                "Bộ Dò Hàng loạt trước khi chạy (Batch Simulator)");
        JLabel hintLbl = Ui.muted("Dò chính xác request Action nào đang bị Chặn hay Cho qua và căn chỉnh chỉ bằng 1 bấm.");
        topBar.add(modeToggle, BorderLayout.WEST);
        topBar.add(hintLbl, BorderLayout.CENTER);
        root.add(topBar, BorderLayout.NORTH);

        CardLayout cards = new CardLayout();
        JPanel cardHost = new JPanel(cards);
        cardHost.setOpaque(false);

        // =====================================================================
        // CARD 1: LIVE ACTION GATE AUDIT LOG
        // =====================================================================
        JPanel livePanel = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        livePanel.setOpaque(false);

        JPanel liveFilterRow = new JPanel(new BorderLayout(8, 0));
        liveFilterRow.setOpaque(false);

        JPanel liveLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        liveLeft.setOpaque(false);
        JCheckBox actionOnlyChk = new JCheckBox("Chỉ Action (POST/PUT/PATCH/DELETE)", true);
        actionOnlyChk.setOpaque(false);
        JComboBox<String> verdictCombo = new JComboBox<>(new String[] {"Tất cả phán quyết", "Chỉ Đã Chặn", "Chỉ Cho Qua"});
        JComboBox<String> methodCombo = new JComboBox<>(new String[] {"Mọi Method", "POST", "PUT", "PATCH", "DELETE", "GET"});
        JTextField liveSearch = new JTextField(12);
        liveSearch.setToolTipText("Lọc theo path, host, nhánh hoặc tên chính sách...");
        liveLeft.add(actionOnlyChk);
        liveLeft.add(verdictCombo);
        liveLeft.add(methodCombo);

        JPanel liveStats = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        liveStats.setOpaque(false);
        Pill liveBlockedPill = new Pill("Chặn: 0", t -> t.danger, t -> Icons.strategyShield(t.danger.text(), 12));
        Pill livePassedPill = new Pill("Cho qua: 0", t -> t.success, t -> Icons.check(t.success.text(), 12));
        JButton clearLogBtn = Ui.secondaryBtn("Xóa nhật ký", t -> Icons.trash(t.muted));
        liveStats.add(liveBlockedPill);
        liveStats.add(livePassedPill);
        liveStats.add(clearLogBtn);

        liveFilterRow.add(liveLeft, BorderLayout.WEST);
        liveFilterRow.add(liveSearch, BorderLayout.CENTER);
        liveFilterRow.add(liveStats, BorderLayout.EAST);
        livePanel.add(liveFilterRow, BorderLayout.NORTH);

        DefaultTableModel liveModel = new DefaultTableModel(
                new String[] {"Lúc", "Method", "Host", "Path", "Nhánh", "Phán quyết", "Chính sách thắng", "Kết quả cuối"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable liveTable = new ThemedTable(liveModel);
        liveTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        liveTable.setRowHeight(25);
        int[] liveW = {76, 66, 130, 210, 120, 136, 150, 125};
        for (int i = 0; i < liveW.length; i++) {
            liveTable.getColumnModel().getColumn(i).setPreferredWidth(liveW[i]);
        }
        JScrollPane liveScroll = new JScrollPane(liveTable);
        liveScroll.setColumnHeaderView(liveTable.getTableHeader());
        liveScroll.setBorder(BorderFactory.createLineBorder(th.border));
        livePanel.add(liveScroll, BorderLayout.CENTER);

        final List<Entry> displayedAudit = new ArrayList<>();

        JPanel liveCalibrateBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        liveCalibrateBar.setOpaque(true);
        liveCalibrateBar.setBackground(th.surfaceAlt);
        liveCalibrateBar.setBorder(BorderFactory.createLineBorder(th.border));
        JButton calWaitBtn = Ui.solidPrimary("+ Chặn chờ duyệt (Đợi tôi bấm Gửi)", t -> Icons.strategyShield(t.accent.onSolid(), 13), null);
        JButton calHoldBtn = Ui.secondaryBtn("+ Giữ ở Proxy (200 ảo)", t -> Icons.strategyShield(t.warning.text(), 13));
        JButton calAllowBtn = Ui.secondaryBtn("+ Cho qua luôn (ALLOW ưu tiên đầu)", t -> Icons.check(t.success.text()));
        JButton openQueueBtn = Ui.secondaryBtn("Mở Hàng chờ Chặn", t -> Icons.strategyShield(t.danger.text(), 13));
        liveCalibrateBar.add(Ui.bold("Căn chỉnh dòng đang chọn:"));
        liveCalibrateBar.add(calWaitBtn);
        liveCalibrateBar.add(calHoldBtn);
        liveCalibrateBar.add(calAllowBtn);
        liveCalibrateBar.add(Box.createHorizontalStrut(8));
        liveCalibrateBar.add(openQueueBtn);
        livePanel.add(liveCalibrateBar, BorderLayout.SOUTH);

        Runnable refreshLive = () -> {
            String vm = switch (verdictCombo.getSelectedIndex()) {
                case 1 -> "BLOCKED";
                case 2 -> "PASSED";
                default -> "ALL";
            };
            String mf = methodCombo.getSelectedIndex() == 0 ? "ALL" : String.valueOf(methodCombo.getSelectedItem());
            List<Entry> rows = auditLog.filter(actionOnlyChk.isSelected(), vm, mf, liveSearch.getText());
            displayedAudit.clear();
            displayedAudit.addAll(rows);
            liveModel.setRowCount(0);
            for (Entry en : rows) {
                liveModel.addRow(new Object[] {
                        ActionPolicy.formatTimestamp(en.timestampMs()),
                        en.method(),
                        en.host(),
                        en.path(),
                        en.branch(),
                        en.verdict(),
                        en.policyName(),
                        en.finalOutcome()
                });
            }
            ActionGateAuditLog.Stats st = auditLog.stats();
            liveBlockedPill.setText("Chặn: " + st.blocked());
            livePassedPill.setText("Cho qua: " + (st.passedExplicit() + st.passedDefault()));
        };

        actionOnlyChk.addActionListener(Ui.safe(e -> refreshLive.run()));
        verdictCombo.addActionListener(Ui.safe(e -> refreshLive.run()));
        methodCombo.addActionListener(Ui.safe(e -> refreshLive.run()));
        liveSearch.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshLive.run(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshLive.run(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshLive.run(); }
        });
        clearLogBtn.addActionListener(Ui.safe(e -> {
            auditLog.clear();
            refreshLive.run();
        }));
        openQueueBtn.addActionListener(Ui.safe(e -> MainPanel.openInterceptQueue(engine)));

        java.util.function.Consumer<ProxyEffect> calibrateSelectedAudit = effect -> {
            int r = liveTable.getSelectedRow();
            if (r < 0 || r >= displayedAudit.size()) return;
            Entry en = displayedAudit.get(r);
            String tpl = ActionEngine.normalizePathTemplate(en.path());
            long now = System.currentTimeMillis();
            ActionPolicy pol = new ActionPolicy(
                    null,
                    (effect == ProxyEffect.ALLOW_EXPLICIT ? "Cho qua " : "Chặn ") + en.method() + " " + Text.shortPath(tpl, 36),
                    "Tạo từ Dò Giao thức trực tiếp",
                    true, 0,
                    ScopeType.PATH_PATTERN, List.of(), true, en.host(),
                    PathMatchType.TEMPLATE, tpl, null,
                    List.of(en.method()),
                    Stage.PROXY_GATE, effect, ActionEngine.ActionMode.NONE,
                    200, null, null, "", now, now, "Dò Giao thức", 0L, "", 0);
            if (effect == ProxyEffect.ALLOW_EXPLICIT) {
                ledger.createAtTop(pol);
            } else {
                ledger.upsert(pol);
            }
            engine.saveActionPolicies();
            if (refreshBanner != null) refreshBanner.run();
            if (onAfterAction != null) onAfterAction.run();
        };
        calWaitBtn.addActionListener(Ui.safe(e -> calibrateSelectedAudit.accept(ProxyEffect.INTERCEPT_WAIT)));
        calHoldBtn.addActionListener(Ui.safe(e -> calibrateSelectedAudit.accept(ProxyEffect.HOLD_SPOOF)));
        calAllowBtn.addActionListener(Ui.safe(e -> calibrateSelectedAudit.accept(ProxyEffect.ALLOW_EXPLICIT)));

        auditLog.addListener(() -> SwingUtilities.invokeLater(refreshLive));

        // =====================================================================
        // CARD 2: BATCH PRE-FLIGHT SIMULATOR
        // =====================================================================
        JPanel simPanel = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        simPanel.setOpaque(false);

        JPanel simTop = new JPanel(new BorderLayout(6, 4));
        simTop.setOpaque(false);

        JPanel simControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        simControls.setOpaque(false);
        JComboBox<String> simBranchCombo = new JComboBox<>();
        simBranchCombo.addItem("Toàn bộ API đã bắt (" + engine.groups().size() + ")");
        for (Branch b : engine.branches()) {
            simBranchCombo.addItem(b.name());
        }
        JCheckBox simActionOnlyChk = new JCheckBox("Chỉ lọc Action API (POST/PUT/PATCH/DELETE)", true);
        simActionOnlyChk.setOpaque(false);
        JButton runSimBtn = Ui.solidPrimary("Chạy Dò ngay (Không gửi mạng)", t -> Icons.playTest(t.accent.onSolid(), 14), null);
        simControls.add(Ui.bold("Phạm vi dò:"));
        simControls.add(simBranchCombo);
        simControls.add(simActionOnlyChk);
        simControls.add(runSimBtn);

        JPanel simSummary = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        simSummary.setOpaque(false);
        Pill simWaitPill = new Pill("Chờ duyệt: 0", t -> t.danger, t -> Icons.strategyShield(t.danger.text(), 12));
        Pill simHoldPill = new Pill("Giữ Proxy: 0", t -> t.warning, t -> Icons.strategyShield(t.warning.text(), 12));
        Pill simAllowPill = new Pill("Cho qua (ALLOW): 0", t -> t.info, t -> Icons.check(t.info.text(), 12));
        Pill simPassPill = new Pill("Nguyên bản: 0", t -> t.success, t -> Icons.check(t.success.text(), 12));
        simSummary.add(simWaitPill);
        simSummary.add(simHoldPill);
        simSummary.add(simAllowPill);
        simSummary.add(simPassPill);

        simTop.add(simControls, BorderLayout.WEST);
        simTop.add(simSummary, BorderLayout.EAST);
        simPanel.add(simTop, BorderLayout.NORTH);

        DefaultTableModel simModel = new DefaultTableModel(
                new String[] {"API #", "Method", "Path", "Nhánh", "Loại", "Dự báo Cổng Proxy", "Dự báo Replay", "Chính sách thắng"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable simTable = new ThemedTable(simModel);
        simTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        simTable.setRowHeight(25);
        int[] simW = {64, 66, 220, 140, 74, 150, 118, 170};
        for (int i = 0; i < simW.length; i++) {
            simTable.getColumnModel().getColumn(i).setPreferredWidth(simW[i]);
        }
        JScrollPane simScroll = new JScrollPane(simTable);
        simScroll.setColumnHeaderView(simTable.getTableHeader());
        simScroll.setBorder(BorderFactory.createLineBorder(th.border));
        simPanel.add(simScroll, BorderLayout.CENTER);

        final List<SimulationRow> displayedSim = new ArrayList<>();

        JPanel simFoot = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        simFoot.setOpaque(true);
        simFoot.setBackground(th.surfaceAlt);
        simFoot.setBorder(BorderFactory.createLineBorder(th.border));
        JButton simSetWaitBtn = Ui.solidPrimary("Chuyển -> Chặn chờ duyệt", t -> Icons.strategyShield(t.accent.onSolid(), 13), null);
        JButton simSetHoldBtn = Ui.secondaryBtn("Chuyển -> Giữ Proxy", t -> Icons.strategyShield(t.warning.text(), 13));
        JButton simSetAllowBtn = Ui.secondaryBtn("Chuyển -> Cho qua (ALLOW)", t -> Icons.check(t.success.text()));
        JButton simReleaseBtn = Ui.secondaryBtn("Nhả về Nguyên bản", t -> Icons.undo(t.fg));
        JButton simComposeBtn = Ui.secondaryBtn("Sửa & Gửi lại (Composer)", t -> Icons.edit(t.accent.text()));
        simFoot.add(Ui.bold("Căn chỉnh API đã chọn:"));
        simFoot.add(simSetWaitBtn);
        simFoot.add(simSetHoldBtn);
        simFoot.add(simSetAllowBtn);
        simFoot.add(simReleaseBtn);
        simFoot.add(Box.createHorizontalStrut(6));
        simFoot.add(simComposeBtn);
        simPanel.add(simFoot, BorderLayout.SOUTH);

        Runnable runSim = () -> {
            List<ApiGroup> targetApis = simBranchCombo.getSelectedIndex() <= 0
                    ? engine.groups()
                    : engine.groupsUnder(String.valueOf(simBranchCombo.getSelectedItem()));
            if (simActionOnlyChk.isSelected()) {
                targetApis = targetApis.stream()
                        .filter(g -> ActionEngine.isDestructiveOrMutating(g.method(), g.path()))
                        .toList();
            }
            SimulationReport rep = ledger.simulateBatch(targetApis, engine.config().actionMode);
            displayedSim.clear();
            displayedSim.addAll(rep.rows());
            simModel.setRowCount(0);
            for (SimulationRow row : rep.rows()) {
                String winName = row.proxyEval().winningPolicy() != null
                        ? row.proxyEval().winningPolicy().name()
                        : "(Mặc định — Nguyên bản)";
                simModel.addRow(new Object[] {
                        Text.apiLabel(row.gid()),
                        row.method(),
                        row.path(),
                        row.branch(),
                        row.actionProtocol() ? "Action" : "Read",
                        row.proxyEval().effectiveProxyEffect().label,
                        row.replayEval().effectiveReplayMode().shortTitle(),
                        winName
                });
            }
            simWaitPill.setText("Chờ duyệt: " + rep.willWait());
            simHoldPill.setText("Giữ Proxy: " + (rep.willHoldSpoof() + rep.willDrop()));
            simAllowPill.setText("Cho qua (ALLOW): " + rep.willAllowExplicit());
            simPassPill.setText("Nguyên bản: " + rep.willPassDefault());
        };

        runSimBtn.addActionListener(Ui.safe(e -> runSim.run()));
        simBranchCombo.addActionListener(Ui.safe(e -> runSim.run()));
        simActionOnlyChk.addActionListener(Ui.safe(e -> runSim.run()));

        java.util.function.Consumer<ProxyEffect> applySimCalibration = eff -> {
            int[] rows = simTable.getSelectedRows();
            if (rows.length == 0) return;
            for (int r : rows) {
                if (r < 0 || r >= displayedSim.size()) continue;
                SimulationRow sr = displayedSim.get(r);
                ApiGroup g = engine.group(sr.gid());
                if (g == null) continue;
                if (eff == ProxyEffect.PASS_NONE) {
                    engine.releaseApiToNone(g, false);
                } else if (eff == ProxyEffect.ALLOW_EXPLICIT) {
                    engine.releaseApiToNone(g, true);
                } else {
                    String tpl = ActionEngine.normalizePathTemplate(g.path());
                    long now = System.currentTimeMillis();
                    ledger.upsert(new ActionPolicy(
                            null,
                            (eff == ProxyEffect.INTERCEPT_WAIT ? "Chặn chờ duyệt " : "Giữ Proxy ")
                                    + Text.apiLabel(g.gid()) + " " + g.method() + " " + Text.shortPath(tpl, 30),
                            "Căn chỉnh từ Bộ Dò hàng loạt",
                            true, 0,
                            ScopeType.API_EXACT, List.of(), true, g.host(),
                            PathMatchType.TEMPLATE, tpl, g.gid(),
                            List.of(g.method()),
                            Stage.PROXY_GATE, eff, ActionEngine.ActionMode.NONE,
                            200, null, null, "", now, now, "Bộ Dò hàng loạt", 0L, "", 0));
                    engine.saveActionPolicies();
                }
            }
            runSim.run();
            if (refreshBanner != null) refreshBanner.run();
            if (onAfterAction != null) onAfterAction.run();
        };

        simSetWaitBtn.addActionListener(Ui.safe(e -> applySimCalibration.accept(ProxyEffect.INTERCEPT_WAIT)));
        simSetHoldBtn.addActionListener(Ui.safe(e -> applySimCalibration.accept(ProxyEffect.HOLD_SPOOF)));
        simSetAllowBtn.addActionListener(Ui.safe(e -> applySimCalibration.accept(ProxyEffect.ALLOW_EXPLICIT)));
        simReleaseBtn.addActionListener(Ui.safe(e -> applySimCalibration.accept(ProxyEffect.PASS_NONE)));
        simComposeBtn.addActionListener(Ui.safe(e -> {
            int r = simTable.getSelectedRow();
            if (r >= 0 && r < displayedSim.size()) {
                ApiGroup g = engine.group(displayedSim.get(r).gid());
                if (g != null) {
                    RequestComposerDialog.open(dialogParent, engine, g, null, null);
                }
            }
        }));

        cardHost.add(livePanel, "LIVE");
        cardHost.add(simPanel, "SIM");
        modeToggle.onChange(idx -> {
            cards.show(cardHost, idx == 0 ? "LIVE" : "SIM");
            if (idx == 0) {
                refreshLive.run();
            } else {
                runSim.run();
            }
        });
        root.add(cardHost, BorderLayout.CENTER);

        ledger.addListener(() -> SwingUtilities.invokeLater(runSim));
        refreshLive.run();
        runSim.run();
        return root;
    }
}
