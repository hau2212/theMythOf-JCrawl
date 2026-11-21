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
                "Nhật ký",
                "Thử luật");
        modeToggle.setOptionTooltip(0, "Request thật vừa đi qua Burp: bị chặn hay cho qua, vì luật nào");
        modeToggle.setOptionTooltip(1, "Chạy thử luật trên các API đã bắt, không gửi gì lên mạng");
        JLabel hintLbl = Ui.muted("Xem request nào bị chặn / cho qua và sửa luật bằng 1 bấm.");
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

        JPanel liveLeft = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        liveLeft.setOpaque(false);
        JCheckBox actionOnlyChk = new JCheckBox("Chỉ API thao tác", true);
        actionOnlyChk.setOpaque(false);
        actionOnlyChk.setToolTipText("Chỉ POST / PUT / PATCH / DELETE");
        JComboBox<String> verdictCombo = new JComboBox<>(new String[] {"Mọi kết quả", "Bị chặn", "Cho qua"});
        JComboBox<String> methodCombo = new JComboBox<>(new String[] {"Mọi Method", "POST", "PUT", "PATCH", "DELETE", "GET"});
        JTextField liveSearch = new JTextField(12);
        liveSearch.setToolTipText("Tìm theo path, host, nhánh hoặc tên luật");
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

        liveLeft.add(liveSearch);
        liveFilterRow.add(liveLeft, BorderLayout.CENTER);
        liveFilterRow.add(liveStats, BorderLayout.EAST);
        livePanel.add(liveFilterRow, BorderLayout.NORTH);

        DefaultTableModel liveModel = new DefaultTableModel(
                new String[] {"Lúc", "Method", "Host", "Path", "Nhánh", "Kết quả", "Luật khớp", "Cuối cùng"}, 0) {
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

        JPanel liveCalibrateBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        liveCalibrateBar.setOpaque(true);
        liveCalibrateBar.setBackground(th.surfaceAlt);
        liveCalibrateBar.setBorder(BorderFactory.createLineBorder(th.border));
        JButton calWaitBtn = Ui.solidPrimary("+ Chờ duyệt", t -> Icons.strategyShield(t.accent.onSolid(), 13), null);
        JButton calHoldBtn = Ui.secondaryBtn("+ Giữ lại", t -> Icons.strategyShield(t.warning.text(), 13));
        JButton calAllowBtn = Ui.secondaryBtn("+ Cho qua", t -> Icons.check(t.success.text()));
        JButton openQueueBtn = Ui.secondaryBtn("Hàng chặn", t -> Icons.strategyShield(t.danger.text(), 13));
        liveCalibrateBar.add(Ui.bold("Tạo luật cho dòng chọn:"));
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
                    "Tạo từ Nhật ký",
                    true, 0,
                    ScopeType.PATH_PATTERN, List.of(), true, en.host(),
                    PathMatchType.TEMPLATE, tpl, null,
                    List.of(en.method()),
                    Stage.PROXY_GATE, effect, ActionEngine.ActionMode.NONE,
                    200, null, null, "", now, now, "Nhật ký", 0L, "", 0);
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

        JPanel simTop = new JPanel(new BorderLayout(0, 2));
        simTop.setOpaque(false);

        JPanel simControls = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        simControls.setOpaque(false);
        JComboBox<String> simBranchCombo = new JComboBox<>();
        simBranchCombo.addItem("Mọi API (" + engine.groups().size() + ")");
        for (Branch b : engine.branches()) {
            simBranchCombo.addItem(b.name());
        }
        JCheckBox simActionOnlyChk = new JCheckBox("Chỉ API thao tác", true);
        simActionOnlyChk.setOpaque(false);
        simActionOnlyChk.setToolTipText("Chỉ POST / PUT / PATCH / DELETE");
        JButton runSimBtn = Ui.solidPrimary("Chạy thử", t -> Icons.playTest(t.accent.onSolid(), 14), null);
        runSimBtn.setToolTipText("Chạy luật trên các API đã bắt. Không gửi gì lên mạng.");
        simControls.add(Ui.bold("Phạm vi:"));
        simControls.add(simBranchCombo);
        simControls.add(simActionOnlyChk);
        simControls.add(runSimBtn);

        JPanel simSummary = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        simSummary.setOpaque(false);
        Pill simWaitPill = new Pill("Chờ duyệt: 0", t -> t.danger, t -> Icons.strategyShield(t.danger.text(), 12));
        Pill simHoldPill = new Pill("Giữ lại: 0", t -> t.warning, t -> Icons.strategyShield(t.warning.text(), 12));
        Pill simAllowPill = new Pill("Cho qua: 0", t -> t.info, t -> Icons.check(t.info.text(), 12));
        Pill simPassPill = new Pill("Không luật: 0", t -> t.success, t -> Icons.check(t.success.text(), 12));
        simSummary.add(simWaitPill);
        simSummary.add(simHoldPill);
        simSummary.add(simAllowPill);
        simSummary.add(simPassPill);

        // V_8.2 fix: controls and result pills on their OWN rows (they used to overlap on narrow dialogs).
        simTop.add(simControls, BorderLayout.NORTH);
        simTop.add(simSummary, BorderLayout.SOUTH);
        simPanel.add(simTop, BorderLayout.NORTH);

        DefaultTableModel simModel = new DefaultTableModel(
                new String[] {"API #", "Method", "Path", "Nhánh", "Loại", "Khi crawl", "Khi gửi lại", "Luật khớp"}, 0) {
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

        JPanel simFoot = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        simFoot.setOpaque(true);
        simFoot.setBackground(th.surfaceAlt);
        simFoot.setBorder(BorderFactory.createLineBorder(th.border));
        JButton simSetWaitBtn = Ui.solidPrimary("Chờ duyệt", t -> Icons.strategyShield(t.accent.onSolid(), 13), null);
        JButton simSetHoldBtn = Ui.secondaryBtn("Giữ lại", t -> Icons.strategyShield(t.warning.text(), 13));
        JButton simSetAllowBtn = Ui.secondaryBtn("Cho qua", t -> Icons.check(t.success.text()));
        JButton simReleaseBtn = Ui.secondaryBtn("Bỏ luật", t -> Icons.undo(t.fg));
        JButton simComposeBtn = Ui.secondaryBtn("Sửa request", t -> Icons.edit(t.accent.text()));
        simFoot.add(Ui.bold("Đặt luật cho API chọn:"));
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
                        : "(không luật)";
                simModel.addRow(new Object[] {
                        Text.apiLabel(row.gid()),
                        row.method(),
                        row.path(),
                        row.branch(),
                        row.actionProtocol() ? "Thao tác" : "Đọc",
                        row.proxyEval().effectiveProxyEffect().label,
                        row.replayEval().effectiveReplayMode().shortTitle(),
                        winName
                });
            }
            simWaitPill.setText("Chờ duyệt: " + rep.willWait());
            simHoldPill.setText("Giữ lại: " + (rep.willHoldSpoof() + rep.willDrop()));
            simAllowPill.setText("Cho qua: " + rep.willAllowExplicit());
            simPassPill.setText("Không luật: " + rep.willPassDefault());
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
                            (eff == ProxyEffect.INTERCEPT_WAIT ? "Chờ duyệt " : "Giữ lại ")
                                    + Text.apiLabel(g.gid()) + " " + g.method() + " " + Text.shortPath(tpl, 30),
                            "Tạo từ Thử luật",
                            true, 0,
                            ScopeType.API_EXACT, List.of(), true, g.host(),
                            PathMatchType.TEMPLATE, tpl, g.gid(),
                            List.of(g.method()),
                            Stage.PROXY_GATE, eff, ActionEngine.ActionMode.NONE,
                            200, null, null, "", now, now, "Thử luật", 0L, "", 0));
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
