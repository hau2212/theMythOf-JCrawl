package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.excel.TargetEntry;
import themythofcrawl.excel.TargetList;
import themythofcrawl.export.Reports;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Branch;
import themythofcrawl.model.ResultState;
import themythofcrawl.model.RoleResult;
import themythofcrawl.ui.kit.Breadcrumb;
import themythofcrawl.ui.kit.Card;
import themythofcrawl.ui.kit.EmptyState;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SegmentedToggle;
import themythofcrawl.ui.kit.StatCard;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.kit.ThemedTree;
import themythofcrawl.ui.theme.Theme;

/**
 * Visual Audit History view (replacing the plain-text dump in {@link HistoryTab}):
 * <ul>
 *   <li>Responsive 8-KPI strip ({@link StatCard}) with mutually exclusive audit counts &amp; Excel coverage.</li>
 *   <li>View switcher ({@code Thẻ} | {@code Ma trận} | {@code Văn bản}).</li>
 *   <li>Virtualized branch card list with collapse/expand, empty-state cards, inline role heatmap squares
 *       with exact tooltips, and click-to-jump into {@link ApiTab}.</li>
 *   <li>Large-scale Role x API matrix table with abbreviated column headers and zero-allocation painting.</li>
 *   <li>Lazy plain-text view preserved for copy / inspection.</li>
 * </ul>
 */
public final class HistoryReportView extends JPanel {
    public static final int MODE_CARDS = 0;
    public static final int MODE_MATRIX = 1;
    public static final int MODE_TEXT = 2;

    private static final String CARD_CARDS = "cards";
    private static final String CARD_MATRIX = "matrix";
    private static final String CARD_TEXT = "text";

    private final BiConsumer<ApiGroup, RoleResult> onJump;
    private final Consumer<String> onFlash;

    private final SegmentedToggle modeToggle = new SegmentedToggle(
            new SegmentedToggle.Option("Thẻ", "Xem báo cáo theo từng nhánh chức năng", t -> t.accent,
                    t -> Icons.branch(t.fg, 12)),
            new SegmentedToggle.Option("Ma trận", "Bảng ma trận Role x API", t -> t.accent,
                    t -> Icons.excel(t.fg, 12)),
            new SegmentedToggle.Option("Văn bản", "Bản văn bản thuần để đọc / copy", t -> t.accent,
                    t -> Icons.document(t.fg, 12)));

    private final JButton expandAllBtn;
    private final JButton collapseAllBtn;
    private final JButton copyTextBtn;
    private final JButton toggleKpiBtn;

    private final JPanel kpiPanel = new JPanel(new GridLayout(1, 8, Theme.SPACE_1, Theme.SPACE_1));
    private final StatCard kpiBranches = new StatCard("Tổng nhánh", "0");
    private final StatCard kpiApis = new StatCard("Tổng API", "0");
    private final StatCard kpiOk = new StatCard("OK", "0", null, t -> t.success, -1);
    private final StatCard kpiDiff = new StatCard("Lệch", "0", null, t -> t.warning, -1);
    private final StatCard kpiBypass = new StatCard("Bypass", "0", null, t -> t.critical, -1);
    private final StatCard kpiError = new StatCard("Lỗi", "0", null, t -> t.danger, -1);
    private final StatCard kpiUnsent = new StatCard("Chưa gửi", "0", null, t -> t.pending, -1);
    private final StatCard kpiExcel = new StatCard("Độ phủ Excel", "-", "Chưa nạp", t -> t.info, -1);
    private final StatCard[] kpis = {kpiBranches, kpiApis, kpiOk, kpiDiff, kpiBypass, kpiError, kpiUnsent, kpiExcel};

    private final CardLayout contentLayout = new CardLayout();
    private final JPanel contentDeck = new JPanel(contentLayout);

    private final BranchCardList cardList;
    private final JScrollPane cardScroll;

    private final MatrixModel matrixModel = new MatrixModel();
    private final ThemedTable matrixTable;
    private final JScrollPane matrixScroll;

    private final JTextArea textArea = new JTextArea() {
        @Override
        public void updateUI() {
            super.updateUI();
            setFont(Theme.current().monoFont);
        }
    };
    private final JScrollPane textScroll;

    private List<Branch> lastBranches = List.of();
    private List<ApiGroup> lastGroups = List.of();
    private TargetList lastTargets;
    private List<ApiGroup> sortedGroups = List.of();
    private boolean matrixDirty;
    private boolean textDirty;
    private boolean wideKpiGrid = true;
    private final Map<Integer, Boolean> collapsedBranches = new HashMap<>();

    public HistoryReportView(BiConsumer<ApiGroup, RoleResult> onJump, Consumer<String> onFlash) {
        super(new BorderLayout(0, Theme.SPACE_1));
        this.onJump = onJump != null ? onJump : (g, r) -> { };
        this.onFlash = onFlash != null ? onFlash : s -> { };
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_2, Theme.SPACE_1, Theme.SPACE_2));

        expandAllBtn = Ui.compact(Ui.button("Mở hết", t -> Icons.plus(t.fg, 12), "Mở rộng tất cả thẻ nhánh",
                () -> expandAll(true)));
        collapseAllBtn = Ui.compact(Ui.button("Thu gọn hết", t -> Icons.minus(t.fg, 12), "Thu gọn tất cả thẻ nhánh",
                () -> expandAll(false)));
        copyTextBtn = Ui.compact(Ui.button("Copy văn bản", t -> Icons.copy(t.fg), "Copy toàn bộ báo cáo Lịch sử dạng văn bản",
                this::copyTextReport));
        toggleKpiBtn = Ui.compact(Ui.button("Ẩn/Hiện KPI", t -> Icons.chevronDown(t.fg, 11), "Thu gọn hoặc mở rộng dải 8 thẻ KPI tổng quan", () -> {
            kpiPanel.setVisible(!kpiPanel.isVisible());
            revalidate();
            repaint();
        }));

        JPanel topBar = new JPanel(new BorderLayout(Theme.SPACE_2, Theme.SPACE_1));
        topBar.setOpaque(false);
        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightControls.setOpaque(false);
        rightControls.add(toggleKpiBtn);
        rightControls.add(expandAllBtn);
        rightControls.add(collapseAllBtn);
        rightControls.add(copyTextBtn);
        topBar.add(modeToggle, BorderLayout.WEST);
        topBar.add(rightControls, BorderLayout.EAST);

        kpiPanel.setOpaque(false);
        for (StatCard card : kpis) {
            card.setPadding(Theme.SPACE_1, Theme.SPACE_2, Theme.SPACE_1, Theme.SPACE_2);
            kpiPanel.add(card);
        }

        JPanel northStack = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        northStack.setOpaque(false);
        northStack.add(topBar, BorderLayout.NORTH);
        northStack.add(kpiPanel, BorderLayout.CENTER);
        add(northStack, BorderLayout.NORTH);

        cardList = new BranchCardList();
        cardScroll = new JScrollPane(cardList);
        cardScroll.setBorder(BorderFactory.createEmptyBorder());
        cardScroll.getVerticalScrollBar().setUnitIncrement(24);
        cardScroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        cardScroll.getViewport().addChangeListener(e -> cardList.onScrollOrResize());

        matrixTable = new ThemedTable(matrixModel);
        Ui.style(matrixTable);
        matrixTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        matrixTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        matrixTable.setAutoCreateRowSorter(false);
        MatrixHeaderRenderer mhdr = new MatrixHeaderRenderer();
        matrixTable.getTableHeader().setDefaultRenderer(mhdr);
        matrixTable.setExtensionHeaderRenderer(mhdr);
        MatrixCellRenderer mcell = new MatrixCellRenderer(matrixModel);
        matrixTable.setDefaultRenderer(Object.class, mcell);
        installMatrixMouse(mcell);
        matrixScroll = MainPanel.scroll(matrixTable);

        textArea.setEditable(false);
        textArea.setFont(Theme.current().monoFont);
        textScroll = MainPanel.scroll(textArea);

        contentDeck.setOpaque(false);
        contentDeck.add(cardScroll, CARD_CARDS);
        contentDeck.add(matrixScroll, CARD_MATRIX);
        contentDeck.add(textScroll, CARD_TEXT);
        add(contentDeck, BorderLayout.CENTER);

        modeToggle.onChange(idx -> applyMode(idx, false));
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateKpiColumns();
                cardList.onScrollOrResize();
            }
        });
        applyMode(MODE_CARDS, true);
    }

    private void installMatrixMouse(MatrixCellRenderer cellRenderer) {
        matrixTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int vr = matrixTable.rowAtPoint(e.getPoint());
                    int vc = matrixTable.columnAtPoint(e.getPoint());
                    if (vr < 0) {
                        return;
                    }
                    int mr = matrixTable.convertRowIndexToModel(vr);
                    ApiGroup g = matrixModel.groupAt(mr);
                    if (g == null) {
                        return;
                    }
                    RoleResult rr = null;
                    if (vc >= 0) {
                        int mc = matrixTable.convertColumnIndexToModel(vc);
                        rr = matrixModel.roleResultAt(mr, mc);
                    }
                    onJump.accept(g, rr);
                }
            }
        });
    }

    private void updateKpiColumns() {
        int w = getWidth();
        boolean wide = w >= 860;
        if (wide != wideKpiGrid) {
            wideKpiGrid = wide;
            kpiPanel.setLayout(new GridLayout(wide ? 1 : 2, wide ? 8 : 4, Theme.SPACE_2, Theme.SPACE_2));
            kpiPanel.revalidate();
            kpiPanel.repaint();
        }
    }

    private void copyTextReport() {
        String txt = Reports.history(lastBranches, lastGroups);
        Ui.copyToClipboard(txt);
        onFlash.accept("Đã copy báo cáo Lịch sử dạng văn bản");
    }

    public int viewMode() {
        return modeToggle.selectedIndex();
    }

    public void setViewMode(int mode) {
        int clamped = Math.max(MODE_CARDS, Math.min(MODE_TEXT, mode));
        modeToggle.setSelectedIndex(clamped, true);
    }

    private void applyMode(int mode, boolean force) {
        boolean isCards = mode == MODE_CARDS;
        expandAllBtn.setVisible(isCards);
        collapseAllBtn.setVisible(isCards);
        switch (mode) {
            case MODE_MATRIX -> {
                if (matrixDirty || force) {
                    rebuildMatrixNow();
                }
                contentLayout.show(contentDeck, CARD_MATRIX);
            }
            case MODE_TEXT -> {
                if (textDirty || force) {
                    rebuildTextNow();
                }
                contentLayout.show(contentDeck, CARD_TEXT);
            }
            default -> {
                contentLayout.show(contentDeck, CARD_CARDS);
                cardList.onScrollOrResize();
            }
        }
        revalidate();
        repaint();
    }

    public void expandAll(boolean expand) {
        collapsedBranches.clear();
        if (!expand) {
            for (BranchEntry be : cardList.entries) {
                collapsedBranches.put(be.branchId, Boolean.TRUE);
                be.collapsed = true;
            }
        } else {
            for (BranchEntry be : cardList.entries) {
                be.collapsed = false;
            }
        }
        cardList.recomputeLayout();
        cardList.ensureVisibleCards(true);
        cardList.repaint();
    }

    /** Replaces the report model in O(G + B) and updates only what the active view needs. */
    public void setData(List<Branch> branches, Collection<ApiGroup> groups, TargetList targets) {
        this.lastBranches = branches == null ? List.of() : new ArrayList<>(branches);
        this.lastGroups = groups == null ? List.of() : new ArrayList<>(groups);
        this.lastTargets = targets;

        List<ApiGroup> gs = new ArrayList<>(this.lastGroups);
        gs.sort(Comparator.comparingInt(ApiGroup::gid));
        this.sortedGroups = gs;

        int okCount = 0;
        int diffCount = 0;
        int bypassCount = 0;
        int errCount = 0;
        int unsentCount = 0;
        Set<TargetEntry> matchedTargets = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Integer, List<ApiGroup>> byBranch = new LinkedHashMap<>();

        for (ApiGroup g : gs) {
            if (g.target() != null) {
                matchedTargets.add(g.target());
            }
            byBranch.computeIfAbsent(g.branchId(), k -> new ArrayList<>()).add(g);

            boolean hasBypass = false;
            boolean hasErr = false;
            for (RoleResult r : g.results()) {
                if (Audit.level(g, r) == AuditLevel.WARN) {
                    hasBypass = true;
                }
                if (isErrorResult(r)) {
                    hasErr = true;
                }
            }
            if (hasBypass) {
                bypassCount++;
            } else if (hasErr) {
                errCount++;
            } else {
                AuditLevel gl = Audit.level(g);
                if (gl == AuditLevel.OK) {
                    okCount++;
                } else if (gl == AuditLevel.INFO || gl == AuditLevel.REDIRECT) {
                    diffCount++;
                } else {
                    unsentCount++;
                }
            }
        }

        List<BranchEntry> entries = new ArrayList<>();
        Map<Integer, Branch> branchMap = new LinkedHashMap<>();
        for (Branch b : this.lastBranches) {
            branchMap.put(b.id(), b);
        }
        for (Branch b : this.lastBranches) {
            List<ApiGroup> rows = byBranch.getOrDefault(b.id(), List.of());
            boolean col = Boolean.TRUE.equals(collapsedBranches.get(b.id()));
            entries.add(buildEntry(b.id(), b.name(), b.created(), rows, col));
        }
        List<Integer> orphanIds = new ArrayList<>();
        for (Integer bid : byBranch.keySet()) {
            if (!branchMap.containsKey(bid)) {
                orphanIds.add(bid);
            }
        }
        orphanIds.sort(null);
        for (Integer bid : orphanIds) {
            List<ApiGroup> rows = byBranch.get(bid);
            boolean col = Boolean.TRUE.equals(collapsedBranches.get(bid));
            String name = rows.isEmpty() ? Text.featureLabel(bid, "") : rows.get(0).branch();
            entries.add(buildEntry(bid, name, "", rows, col));
        }

        updateKpis(entries.size(), gs.size(), okCount, diffCount, bypassCount, errCount, unsentCount, matchedTargets.size(),
                targets);

        cardList.setEntries(entries);
        matrixDirty = true;
        textDirty = true;
        int m = viewMode();
        if (m == MODE_MATRIX) {
            rebuildMatrixNow();
        } else if (m == MODE_TEXT) {
            rebuildTextNow();
        }
    }

    private void updateKpis(int branchCount, int totalApis, int ok, int diff, int bypass, int err, int unsent,
                            int excelMatched, TargetList targets) {
        kpiBranches.setMetric(String.valueOf(branchCount), totalApis == 0 ? "Chưa có" : "Nhánh", -1);
        kpiApis.setMetric(String.valueOf(totalApis), branchCount == 0 ? null : branchCount + " nhánh", -1);
        kpiOk.setMetric(String.valueOf(ok), pctNote(ok, totalApis), -1);
        kpiDiff.setMetric(String.valueOf(diff), pctNote(diff, totalApis), -1);
        kpiBypass.setMetric(String.valueOf(bypass), bypass > 0 ? "Nghi vấn 2xx" : null, -1);
        kpiError.setMetric(String.valueOf(err), err > 0 ? "5xx / lỗi" : null, -1);
        kpiUnsent.setMetric(String.valueOf(unsent), pctNote(unsent, totalApis), -1);
        if (targets == null || targets.size() <= 0) {
            kpiExcel.setMetricToken(t -> t.neutral);
            kpiExcel.setMetric("-", "Chưa nạp Excel", -1);
        } else {
            int tot = targets.size();
            int hit = Math.min(tot, excelMatched);
            double ratio = (double) hit / tot;
            int pct = (int) Math.round(ratio * 100.0);
            Function<Theme, Theme.Token> tok = pct >= 80 ? t -> t.success : pct >= 40 ? t -> t.info : t -> t.warning;
            kpiExcel.setMetricToken(tok);
            kpiExcel.setMetric(pct + "%", hit + "/" + tot + " Excel", ratio);
        }
    }

    private static String pctNote(int part, int total) {
        if (total <= 0 || part <= 0) {
            return null;
        }
        return Math.round((part * 100.0) / total) + "%";
    }

    private static BranchEntry buildEntry(int branchId, String path, String created, List<ApiGroup> rows, boolean collapsed) {
        int bypass = 0;
        int err = 0;
        for (ApiGroup g : rows) {
            boolean gb = false;
            boolean ge = false;
            for (RoleResult r : g.results()) {
                if (Audit.level(g, r) == AuditLevel.WARN) {
                    gb = true;
                }
                if (isErrorResult(r)) {
                    ge = true;
                }
            }
            if (gb) {
                bypass++;
            } else if (ge) {
                err++;
            }
        }
        Audit.Verdict verdict = Audit.branch(rows);
        return new BranchEntry(branchId, path, created, rows, verdict, bypass, err, collapsed);
    }

    private void rebuildMatrixNow() {
        matrixDirty = false;
        matrixModel.setGroups(sortedGroups);
        applyMatrixColumnSetup();
    }

    private void applyMatrixColumnSetup() {
        MatrixCellRenderer mcell = (MatrixCellRenderer) matrixTable.getDefaultRenderer(Object.class);
        TableColumnModel cm = matrixTable.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) {
            TableColumn c = cm.getColumn(i);
            c.setCellRenderer(mcell);
            switch (i) {
                case 0 -> c.setPreferredWidth(64);
                case 1 -> c.setPreferredWidth(140);
                case 2 -> c.setPreferredWidth(74);
                case 3 -> c.setPreferredWidth(240);
                case 4 -> c.setPreferredWidth(54);
                default -> c.setPreferredWidth(88);
            }
        }
    }

    private void rebuildTextNow() {
        textDirty = false;
        int caret = textArea.getCaretPosition();
        String text = Reports.history(lastBranches, sortedGroups);
        if (!text.equals(textArea.getText())) {
            textArea.setText(text);
            textArea.setCaretPosition(Math.min(caret, text.length()));
        }
    }

    public boolean scrollToBranchPath(String prefixPath) {
        if (prefixPath == null || prefixPath.isBlank()) {
            return false;
        }
        String norm = Hierarchy.key(prefixPath);
        int exactIdx = -1;
        int prefixIdx = -1;
        for (int i = 0; i < cardList.entries.size(); i++) {
            BranchEntry e = cardList.entries.get(i);
            String ek = Hierarchy.key(e.branchPath);
            if (ek.equals(norm)) {
                exactIdx = i;
                break;
            }
            if (prefixIdx < 0 && Hierarchy.matchesPrefix(e.branchPath, prefixPath)) {
                prefixIdx = i;
            }
        }
        int target = exactIdx >= 0 ? exactIdx : prefixIdx;
        if (target < 0) {
            return false;
        }
        return scrollToEntryIndex(target);
    }

    public boolean scrollToBranchId(int branchId) {
        for (int i = 0; i < cardList.entries.size(); i++) {
            if (cardList.entries.get(i).branchId == branchId) {
                return scrollToEntryIndex(i);
            }
        }
        return false;
    }

    public boolean scrollToGroup(ApiGroup g) {
        if (g == null) {
            return false;
        }
        if (viewMode() == MODE_MATRIX) {
            if (matrixDirty) {
                rebuildMatrixNow();
            }
            int row = matrixModel.rowOf(g);
            if (row >= 0) {
                MainPanel.selectRow(matrixTable, row);
                return true;
            }
        }
        return scrollToBranchId(g.branchId());
    }

    private boolean scrollToEntryIndex(int idx) {
        if (idx < 0 || idx >= cardList.entries.size()) {
            return false;
        }
        BranchEntry entry = cardList.entries.get(idx);
        if (entry.collapsed) {
            entry.collapsed = false;
            collapsedBranches.remove(entry.branchId);
            cardList.recomputeLayout();
        }
        JViewport vp = cardScroll.getViewport();
        int viewH = vp.getExtentSize().height;
        int maxY = Math.max(0, cardList.totalHeight - Math.max(1, viewH));
        int targetY = Math.max(0, Math.min(entry.yOffset, maxY));
        if (cardList.getParent() == vp) {
            vp.setViewPosition(new Point(0, targetY));
        }
        Rectangle r = new Rectangle(0, entry.yOffset, Math.max(1, cardList.getWidth()), entry.height);
        cardList.scrollRectToVisible(r);
        cardList.ensureVisibleCards(true);
        cardList.repaint();
        return true;
    }

    // ---- Test / inspection helpers ----
    String kpiValue(int index) {
        return kpis[index].valueText();
    }

    int branchCardCount() {
        return cardList.entries.size();
    }

    int realizedCardCount() {
        return cardList.realized.size();
    }

    ThemedTable matrixTable() {
        return matrixTable;
    }

    JTextArea summaryTextArea() {
        return textArea;
    }

    BranchRowsPanel rowsPanelForBranch(int branchId) {
        BranchCardWidget w = cardList.realized.get(branchId);
        if (w == null || w.entry == null || w.entry.collapsed
                || w.rowsPanel == null || w.rowsPanel.getParent() == null) {
            return null;
        }
        return w.rowsPanel;
    }

    // ------------------------------------------------------------------ Shared audit helpers
    static AuditLevel effectiveRoleLevel(ApiGroup g, RoleResult r) {
        if (r == null) {
            return AuditLevel.NONE;
        }
        if (isErrorResult(r)) {
            return AuditLevel.ERROR;
        }
        AuditLevel lvl = Audit.level(g, r);
        if (lvl == AuditLevel.NONE && !ResultState.finished(r.state())) {
            return AuditLevel.NONE;
        }
        return lvl;
    }

    static boolean isErrorResult(RoleResult r) {
        return r != null && (ResultState.ERROR.equals(r.state()) || (r.status() != null && r.status() >= 500));
    }

    static String roleTooltip(ApiGroup g, RoleResult r) {
        if (r == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append(Text.nz(r.roleName())).append(": ");
        if (ResultState.ERROR.equals(r.state())) {
            sb.append("ERR");
            if (!Text.blank(r.error())) {
                sb.append(" (").append(r.error()).append(')');
            }
        } else if (r.status() != null) {
            sb.append(r.status());
            if (r.length() != null) {
                sb.append(" (").append(r.length()).append(" B)");
            }
        } else {
            sb.append(Text.blank(r.state()) ? "Chưa gửi" : r.state());
        }
        String badge = Audit.badge(g, r);
        if (!Text.blank(badge) && !badge.equals(r.state())) {
            sb.append(" - ").append(badge);
        }
        return sb.toString();
    }

    static String compactRoleCode(RoleResult r) {
        if (r == null) {
            return "";
        }
        if (ResultState.ERROR.equals(r.state())) {
            return "ERR";
        }
        if (r.status() != null) {
            return String.valueOf(r.status());
        }
        return "-";
    }

    // ------------------------------------------------------------------ Card virtualization
    private static final class BranchEntry {
        final int branchId;
        final String branchPath;
        final String created;
        final List<ApiGroup> rows;
        final Audit.Verdict verdict;
        final int bypassCount;
        final int errorCount;
        boolean collapsed;
        int yOffset;
        int height;

        BranchEntry(int branchId, String branchPath, String created, List<ApiGroup> rows, Audit.Verdict verdict,
                    int bypassCount, int errorCount, boolean collapsed) {
            this.branchId = branchId;
            this.branchPath = branchPath == null ? "" : branchPath;
            this.created = created == null ? "" : created;
            this.rows = rows;
            this.verdict = verdict;
            this.bypassCount = bypassCount;
            this.errorCount = errorCount;
            this.collapsed = collapsed;
        }
    }

    private final class BranchCardList extends JPanel implements Scrollable {
        private List<BranchEntry> entries = List.of();
        private final Map<Integer, BranchCardWidget> realized = new LinkedHashMap<>();
        private final EmptyState emptyAll = new EmptyState(t -> Icons.branch(t.muted, 30),
                "Chưa có nhánh / API nào trong Lịch sử",
                "Bật Capture ở thanh trên cùng hoặc mở workspace có dữ liệu để xem báo cáo trực quan.");
        private int totalHeight = 120;

        BranchCardList() {
            super(null);
            setOpaque(false);
        }

        void setEntries(List<BranchEntry> next) {
            this.entries = next;
            realized.clear();
            removeAll();
            recomputeLayout();
            ensureVisibleCards(true);
            revalidate();
            repaint();
        }

        void recomputeLayout() {
            Theme t = Theme.current();
            int gap = t.scaled(Theme.SPACE_2);
            if (entries.isEmpty()) {
                totalHeight = t.scaled(180);
                setPreferredSize(new Dimension(Math.max(480, getWidth()), totalHeight));
                return;
            }
            int headerH = t.scaled(62);
            int rowH = t.scaled(Theme.ROW_TABLE);
            int emptyH = t.scaled(86);
            int padVert = t.scaled(Theme.SPACE_3 * 2 + Theme.SPACE_2);
            int y = 0;
            for (BranchEntry be : entries) {
                int bodyH;
                if (be.collapsed) {
                    bodyH = 0;
                } else if (be.rows.isEmpty()) {
                    bodyH = emptyH;
                } else {
                    bodyH = be.rows.size() * rowH + t.scaled(4);
                }
                be.yOffset = y;
                be.height = be.collapsed ? headerH : (headerH + padVert / 3 + bodyH);
                y += be.height + gap;
            }
            totalHeight = Math.max(t.scaled(120), y);
            setPreferredSize(new Dimension(Math.max(480, getWidth()), totalHeight));
            revalidate();
        }

        void repositionFromEntry(BranchEntry changed) {
            Theme t = Theme.current();
            int gap = t.scaled(Theme.SPACE_2);
            int y = 0;
            for (BranchEntry be : entries) {
                be.yOffset = y;
                y += be.height + gap;
            }
            totalHeight = Math.max(t.scaled(120), y);
            setPreferredSize(new Dimension(Math.max(480, getWidth()), totalHeight));
            revalidate();
            doLayout();
        }

        void onScrollOrResize() {
            ensureVisibleCards(false);
            doLayout();
        }

        void ensureVisibleCards(boolean force) {
            if (entries.isEmpty()) {
                if (emptyAll.getParent() != this) {
                    removeAll();
                    add(emptyAll);
                }
                return;
            }
            if (emptyAll.getParent() == this) {
                remove(emptyAll);
            }
            Rectangle vr = getVisibleRect();
            int viewTop = vr.y;
            int viewBottom = vr.height > 0 ? (vr.y + vr.height) : (vr.y + 920);
            int buf = Theme.current().scaled(320);
            int loY = Math.max(0, viewTop - buf);
            int hiY = viewBottom + buf;

            // Binary search first entry whose bottom >= loY
            int lo = 0;
            int hi = entries.size() - 1;
            int start = entries.size();
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                BranchEntry be = entries.get(mid);
                if (be.yOffset + be.height >= loY) {
                    start = mid;
                    hi = mid - 1;
                } else {
                    lo = mid + 1;
                }
            }

            Map<Integer, BranchEntry> needed = new LinkedHashMap<>();
            for (int i = start; i < entries.size(); i++) {
                BranchEntry be = entries.get(i);
                if (be.yOffset > hiY) {
                    break;
                }
                needed.put(be.branchId, be);
            }

            List<Integer> removeIds = new ArrayList<>();
            for (Integer id : realized.keySet()) {
                if (!needed.containsKey(id)) {
                    removeIds.add(id);
                }
            }
            boolean changed = force;
            for (Integer id : removeIds) {
                BranchCardWidget w = realized.remove(id);
                if (w != null) {
                    remove(w);
                    changed = true;
                }
            }
            int availW = Math.max(360, getWidth());
            for (BranchEntry be : needed.values()) {
                BranchCardWidget w = realized.get(be.branchId);
                if (w == null) {
                    w = new BranchCardWidget(be);
                    realized.put(be.branchId, w);
                    add(w);
                    changed = true;
                } else {
                    w.sync(be);
                }
                w.setBounds(0, be.yOffset, availW, be.height);
                w.validate();
            }
            if (changed) {
                revalidate();
                repaint();
            }
        }

        @Override
        public void doLayout() {
            if (entries.isEmpty()) {
                int w = Math.max(320, getWidth());
                int h = Math.max(160, getHeight());
                emptyAll.setBounds(0, 0, w, h);
                return;
            }
            int availW = Math.max(360, getWidth());
            for (BranchCardWidget w : realized.values()) {
                BranchEntry be = w.entry;
                w.setBounds(0, be.yOffset, availW, be.height);
            }
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (entries != null && !entries.isEmpty()) {
                recomputeLayout();
                ensureVisibleCards(true);
            }
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(680, 460);
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Theme.current().scaled(Theme.ROW_TABLE);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(64, visibleRect.height - Theme.current().scaled(Theme.ROW_TABLE));
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private final class BranchCardWidget extends Card {
        private BranchEntry entry;
        private final Breadcrumb breadcrumb = new Breadcrumb().setMaxSegments(4).setMaxLabelChars(24);
        private final JLabel subtitle = Ui.muted("");
        private final Pill verdictPill = new Pill("", t -> t.neutral);
        private final Pill issuePill = new Pill("", t -> t.critical);
        private final JButton toggleBtn;
        private BranchRowsPanel rowsPanel;
        private EmptyState emptyBranch;

        BranchCardWidget(BranchEntry entry) {
            super(accentFor(entry), false);
            setPadding(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3);

            toggleBtn = Ui.compact(Ui.button("", t -> entryIcon(this.entry, t), () -> toggleCollapse()));
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            right.setOpaque(false);
            right.add(issuePill);
            right.add(verdictPill);
            right.add(toggleBtn);

            JPanel hdrTop = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
            hdrTop.setOpaque(false);
            hdrTop.add(breadcrumb, BorderLayout.CENTER);
            hdrTop.add(right, BorderLayout.EAST);

            JPanel hdr = new JPanel(new BorderLayout(0, 2));
            hdr.setOpaque(false);
            hdr.add(hdrTop, BorderLayout.NORTH);
            hdr.add(subtitle, BorderLayout.SOUTH);

            // Replace default Card header area via BorderLayout.NORTH
            add(hdr, BorderLayout.NORTH);
            sync(entry);
        }

        private Icon entryIcon(BranchEntry e, Theme t) {
            return e != null && e.collapsed ? Icons.chevronRight(t.fg, 12) : Icons.chevronDown(t.fg, 12);
        }

        private void toggleCollapse() {
            if (entry == null) {
                return;
            }
            boolean targetCollapsed = !entry.collapsed;
            if (!themythofcrawl.ui.kit.ToastPane.animationsEnabled() || !cardList.isShowing() || entry.rows.isEmpty()) {
                entry.collapsed = targetCollapsed;
                if (targetCollapsed) {
                    collapsedBranches.put(entry.branchId, Boolean.TRUE);
                } else {
                    collapsedBranches.remove(entry.branchId);
                }
                cardList.recomputeLayout();
                sync(entry);
                cardList.ensureVisibleCards(true);
                cardList.repaint();
                return;
            }

            Theme t = Theme.current();
            int startH = entry.height;
            int headerH = t.scaled(62);
            int rowH = t.scaled(Theme.ROW_TABLE);
            int padVert = t.scaled(Theme.SPACE_3 * 2 + Theme.SPACE_2);
            int expandedH = headerH + padVert / 3 + entry.rows.size() * rowH + t.scaled(4);
            int targetH = targetCollapsed ? headerH : expandedH;

            entry.collapsed = targetCollapsed;
            if (targetCollapsed) {
                collapsedBranches.put(entry.branchId, Boolean.TRUE);
            } else {
                collapsedBranches.remove(entry.branchId);
                sync(entry);
            }

            int[] step = {0};
            final int totalSteps = 8; // 8 * 15 ms = 120 ms <= 150 ms
            javax.swing.Timer anim = new javax.swing.Timer(15, null);
            anim.addActionListener(Ui.safe(e -> {
                step[0]++;
                double p = Math.min(1.0, (double) step[0] / totalSteps);
                double eased = 1.0 - (1.0 - p) * (1.0 - p);
                int curH = (int) Math.round(startH + (targetH - startH) * eased);
                entry.height = curH;
                cardList.repositionFromEntry(entry);
                if (step[0] >= totalSteps) {
                    anim.stop();
                    cardList.recomputeLayout();
                    if (targetCollapsed) {
                        sync(entry);
                    }
                    cardList.ensureVisibleCards(true);
                }
                cardList.repaint();
            }));
            anim.start();
        }

        void sync(BranchEntry next) {
            this.entry = next;
            setAccentToken(accentFor(next), false);
            breadcrumb.setPath(next.branchPath);

            String range;
            if (next.rows.isEmpty()) {
                range = "Chưa có API";
            } else {
                int first = next.rows.get(0).gid();
                int last = next.rows.get(next.rows.size() - 1).gid();
                range = first == last ? Text.apiLabel(first) : (Text.apiLabel(first) + " ... " + Text.apiLabel(last));
            }
            String timePart = next.created.isEmpty() ? "" : ("Lúc " + next.created + " \u00B7 ");
            subtitle.setText("Nhánh #" + next.branchId + " \u00B7 " + timePart + next.rows.size() + " API \u00B7 " + range);

            Audit.Verdict v = next.verdict;
            Function<Theme, Theme.Token> vTok = tokenForAudit(v.level());
            verdictPill.setToken(vTok).setIcon(t -> Icons.forAudit(v.level(), vTok.apply(t).text()))
                    .setText(v.summary());

            if (next.bypassCount > 0) {
                issuePill.setVisible(true);
                issuePill.setToken(t -> t.critical)
                        .setIcon(t -> Icons.warn(t.critical.text(), 11))
                        .setText(next.bypassCount + " Bypass");
            } else if (next.errorCount > 0) {
                issuePill.setVisible(true);
                issuePill.setToken(t -> t.danger)
                        .setIcon(t -> Icons.error(t.danger.text(), 11))
                        .setText(next.errorCount + " Lỗi");
            } else {
                issuePill.setVisible(false);
            }
            toggleBtn.setIcon(Icons.themed(t -> entryIcon(entry, t)));
            toggleBtn.setToolTipText(next.collapsed ? "Mở rộng thẻ nhánh" : "Thu gọn thẻ nhánh");

            if (next.collapsed) {
                body().setVisible(false);
                if (body().getComponentCount() > 0) {
                    setBody(null);
                }
            } else {
                body().setVisible(true);
                if (next.rows.isEmpty()) {
                    if (emptyBranch == null) {
                        emptyBranch = new EmptyState(t -> Icons.folder(t.muted, 24),
                                "Chưa có API nào trong nhánh này",
                                "Đặt làm đích và thực hiện request để ghi nhận API.");
                    }
                    setBody(emptyBranch);
                } else {
                    if (rowsPanel == null) {
                        rowsPanel = new BranchRowsPanel(next.rows);
                    } else {
                        rowsPanel.setRows(next.rows);
                    }
                    setBody(rowsPanel);
                }
            }
        }

        private static Function<Theme, Theme.Token> accentFor(BranchEntry e) {
            if (e.bypassCount > 0) {
                return t -> t.critical;
            }
            if (e.errorCount > 0) {
                return t -> t.danger;
            }
            return e.verdict != null && e.verdict.level() != AuditLevel.NONE ? tokenForAudit(e.verdict.level()) : null;
        }
    }

    private static Function<Theme, Theme.Token> tokenForAudit(AuditLevel lvl) {
        if (lvl == null) {
            return t -> t.neutral;
        }
        return switch (lvl) {
            case OK -> t -> t.success;
            case WARN -> t -> t.critical;
            case ERROR -> t -> t.danger;
            case REDIRECT -> t -> t.info;
            case INFO -> t -> t.warning;
            case PENDING -> t -> t.pending;
            default -> t -> t.neutral;
        };
    }

    // ------------------------------------------------------------------ Zero-alloc borderless card rows + heatmap
    final class BranchRowsPanel extends JComponent {
        private List<ApiGroup> rows;
        private int hoverRow = -1;
        private int hoverRole = -1;
        private char[] pathBuf = new char[512];
        private char[] tmpBuf = new char[32];
        private int pathTruncFrom = -1;

        // Reusable layout coordinates (EDT-only, avoids per-row allocations in paintComponent)
        private int gPad;
        private int gMethodX;
        private int gMethodW;
        private int gPathX;
        private int gPathW;
        private int gOrigX;
        private int gOrigW;
        private int gHeatStart;
        private int gSq;
        private int gSqGap;

        BranchRowsPanel(List<ApiGroup> rows) {
            this.rows = rows;
            setOpaque(false);
            ToolTipManager.sharedInstance().registerComponent(this);
            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    updateHover(e.getX(), e.getY());
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (hoverRow != -1 || hoverRole != -1) {
                        hoverRow = -1;
                        hoverRole = -1;
                        setCursor(Cursor.getDefaultCursor());
                        repaint();
                    }
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) {
                        return;
                    }
                    int row = rowAtY(e.getY());
                    if (row < 0 || row >= BranchRowsPanel.this.rows.size()) {
                        return;
                    }
                    ApiGroup g = BranchRowsPanel.this.rows.get(row);
                    int rIdx = roleAtPoint(row, e.getX());
                    RoleResult rr = rIdx >= 0 && rIdx < g.results().size() ? g.results().get(rIdx) : null;
                    onJump.accept(g, rr);
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        void setRows(List<ApiGroup> next) {
            this.rows = next;
            this.hoverRow = -1;
            this.hoverRole = -1;
            revalidate();
            repaint();
        }

        private int rowH() {
            return Theme.current().scaled(Theme.ROW_TABLE);
        }

        private int rowAtY(int y) {
            int rh = rowH();
            if (rh <= 0 || y < 0) {
                return -1;
            }
            int r = y / rh;
            return r < rows.size() ? r : -1;
        }

        private void computeGeom(Theme t, int w, int roleCount) {
            gPad = t.scaled(6);
            int gidW = t.scaled(46);
            gMethodW = t.scaled(56);
            gOrigW = t.scaled(40);
            gSq = t.scaled(14);
            gSqGap = t.scaled(3);
            int maxHeatW = Math.max(gSq, (int) (w * 0.44));
            int needHeatW = roleCount <= 0 ? 0 : roleCount * gSq + Math.max(0, roleCount - 1) * gSqGap;
            int heatW = Math.min(maxHeatW, needHeatW);
            gHeatStart = w - gPad - heatW;
            gOrigX = gHeatStart - gOrigW - t.scaled(6);
            gMethodX = gPad + gidW + t.scaled(4);
            gPathX = gMethodX + gMethodW + t.scaled(8);
            gPathW = Math.max(t.scaled(80), gOrigX - gPathX - t.scaled(6));
        }

        int roleAtPoint(int row, int x) {
            if (row < 0 || row >= rows.size()) {
                return -1;
            }
            ApiGroup g = rows.get(row);
            int n = g.results().size();
            if (n <= 0) {
                return -1;
            }
            computeGeom(Theme.current(), Math.max(getWidth(), 480), n);
            int step = gSq + gSqGap;
            if (x < gHeatStart || step <= 0) {
                return -1;
            }
            int rel = x - gHeatStart;
            int idx = rel / step;
            int inside = rel % step;
            if (idx >= 0 && idx < n && inside <= gSq) {
                return idx;
            }
            return -1;
        }

        Point roleSquareCenter(int row, int roleIdx) {
            if (row < 0 || row >= rows.size()) {
                return null;
            }
            ApiGroup g = rows.get(row);
            int n = g.results().size();
            if (roleIdx < 0 || roleIdx >= n) {
                return null;
            }
            computeGeom(Theme.current(), Math.max(getWidth(), 480), n);
            int rh = rowH();
            int x = gHeatStart + roleIdx * (gSq + gSqGap) + gSq / 2;
            int y = row * rh + rh / 2;
            return new Point(x, y);
        }

        private void updateHover(int x, int y) {
            int r = rowAtY(y);
            int role = r >= 0 ? roleAtPoint(r, x) : -1;
            if (r != hoverRow || role != hoverRole) {
                hoverRow = r;
                hoverRole = role;
                setCursor(Cursor.getPredefinedCursor(r >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                repaint();
            }
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int r = rowAtY(e.getY());
            if (r < 0 || r >= rows.size()) {
                return null;
            }
            ApiGroup g = rows.get(r);
            int role = roleAtPoint(r, e.getX());
            if (role >= 0 && role < g.results().size()) {
                return roleTooltip(g, g.results().get(role));
            }
            return Text.apiLabel(g.gid()) + " " + g.method() + " " + g.url() + "  |  " + Audit.summary(g)
                    + "  (Bấm để xem ở tab API)";
        }

        @Override
        public Dimension getPreferredSize() {
            int rh = rowH();
            return new Dimension(480, Math.max(rh, rows.size() * rh + Theme.current().scaled(2)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0 || rows.isEmpty()) {
                return;
            }
            Theme th = Theme.current();
            int rh = rowH();
            Rectangle clip = g.getClipBounds();
            int first = clip == null ? 0 : Math.max(0, clip.y / rh);
            int last = clip == null ? rows.size() - 1 : Math.min(rows.size() - 1, (clip.y + clip.height) / rh);
            if (first > last) {
                return;
            }

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                FontMetrics smallFm = g2.getFontMetrics(th.smallBoldFont);
                FontMetrics monoFm = g2.getFontMetrics(th.monoFont);
                FontMetrics boldFm = g2.getFontMetrics(th.boldFont);

                for (int r = first; r <= last; r++) {
                    ApiGroup grp = rows.get(r);
                    int ry = r * rh;
                    if (r == hoverRow) {
                        g2.setColor(th.hover);
                        g2.fillRect(0, ry + 1, w, rh - 2);
                    } else if ((r & 1) == 1) {
                        g2.setColor(th.zebra[1]);
                        g2.fillRect(0, ry, w, rh);
                    }
                    if (r > 0) {
                        g2.setColor(th.border);
                        g2.drawLine(th.scaled(6), ry, w - th.scaled(6), ry);
                    }

                    List<RoleResult> results = grp.results();
                    computeGeom(th, w, results.size());

                    // 1. #gid
                    int gidLen = writeGid(grp.gid());
                    g2.setFont(th.monoBoldFont);
                    g2.setColor(th.muted);
                    int monoTy = ry + (rh - monoFm.getHeight()) / 2 + monoFm.getAscent();
                    g2.drawChars(tmpBuf, 0, gidLen, gPad, monoTy);

                    // 2. Method pill (sharp rectangle)
                    String meth = Text.nz(grp.method());
                    Theme.Token mTok = th.method(meth);
                    int mLen = loadTmp(meth);
                    int mw = Math.min(gMethodW, smallFm.charsWidth(tmpBuf, 0, mLen) + th.scaled(12));
                    int mh = th.scaled(19);
                    int my = ry + (rh - mh) / 2;
                    g2.setColor(mTok.fill());
                    g2.fillRect(gMethodX, my, mw - 1, mh - 1);
                    g2.setColor(mTok.border());
                    g2.drawRect(gMethodX, my, mw - 1, mh - 1);
                    g2.setFont(th.smallBoldFont);
                    g2.setColor(mTok.text());
                    int stTy = my + (mh - smallFm.getHeight()) / 2 + smallFm.getAscent();
                    int mTextX = gMethodX + Math.max(2, (mw - smallFm.charsWidth(tmpBuf, 0, mLen)) / 2);
                    g2.drawChars(tmpBuf, 0, mLen, mTextX, stTy);

                    // 3. Monospace Path with dimmed param segments
                    String path = grp.path();
                    int pLen = loadPath(path);
                    int drawLen = fitPathChars(monoFm, pathBuf, pLen, gPathW);
                    if (drawLen > 0) {
                        g2.setFont(th.monoFont);
                        AuditRenderer.drawPathBuffer(g2, monoFm, pathBuf, drawLen, pathTruncFrom,
                                gPathX, monoTy, th.fg, th.muted);
                    }

                    // 4. Orig status
                    Integer ost = grp.origStatus();
                    int oLen = ost != null ? writeInt(ost) : loadTmp("-");
                    g2.setFont(th.boldFont);
                    g2.setColor(ost != null ? th.statusColor(ost) : th.muted);
                    int oW = boldFm.charsWidth(tmpBuf, 0, oLen);
                    int bTy = ry + (rh - boldFm.getHeight()) / 2 + boldFm.getAscent();
                    g2.drawChars(tmpBuf, 0, oLen, gOrigX + Math.max(0, (gOrigW - oW) / 2), bTy);

                    // 5. Heatmap 14px sharp squares for each RoleResult
                    int sq = gSq;
                    int sqY = ry + (rh - sq) / 2;
                    int maxDrawX = w - gPad - sq;
                    for (int i = 0; i < results.size(); i++) {
                        int sx = gHeatStart + i * (sq + gSqGap);
                        if (sx > maxDrawX) {
                            break;
                        }
                        RoleResult rr = results.get(i);
                        AuditLevel lvl = effectiveRoleLevel(grp, rr);
                        if (lvl == AuditLevel.NONE) {
                            g2.setColor(th.surface);
                            g2.fillRect(sx, sqY, sq - 1, sq - 1);
                            g2.setColor(th.border);
                            g2.drawRect(sx, sqY, sq - 1, sq - 1);
                        } else {
                            Theme.Token tok = tokenForAudit(lvl).apply(th);
                            g2.setColor(tok.solidFill());
                            g2.fillRect(sx, sqY, sq - 1, sq - 1);
                            g2.setColor(tok.border());
                            g2.drawRect(sx, sqY, sq - 1, sq - 1);
                            if (lvl == AuditLevel.WARN || lvl == AuditLevel.ERROR) {
                                g2.setColor(tok.onSolid());
                                int cx = sx + sq / 2;
                                if (lvl == AuditLevel.WARN) {
                                    g2.fillRect(cx - 1, sqY + 3, 2, Math.max(3, sq - 8));
                                    g2.fillRect(cx - 1, sqY + sq - 4, 2, 2);
                                } else {
                                    g2.drawLine(sx + 4, sqY + 4, sx + sq - 5, sqY + sq - 5);
                                    g2.drawLine(sx + sq - 5, sqY + 4, sx + 4, sqY + sq - 5);
                                }
                            }
                        }
                        if (r == hoverRow && i == hoverRole) {
                            g2.setColor(th.fg);
                            g2.drawRect(sx - 1, sqY - 1, sq + 1, sq + 1);
                        }
                    }
                }
            } finally {
                g2.dispose();
            }
        }

        private int writeGid(int gid) {
            tmpBuf[0] = '#';
            return writeIntAt(gid, 1);
        }

        private int writeInt(int v) {
            return writeIntAt(v, 0);
        }

        private int writeIntAt(int v, int offset) {
            int val = Math.max(0, v);
            if (val == 0) {
                tmpBuf[offset] = '0';
                return offset + 1;
            }
            int digits = 0;
            int t = val;
            while (t > 0) {
                digits++;
                t /= 10;
            }
            for (int i = digits - 1; i >= 0; i--) {
                tmpBuf[offset + i] = (char) ('0' + (val % 10));
                val /= 10;
            }
            return offset + digits;
        }

        private int loadTmp(String s) {
            if (s == null || s.isEmpty()) {
                return 0;
            }
            int len = Math.min(tmpBuf.length, s.length());
            s.getChars(0, len, tmpBuf, 0);
            return len;
        }

        private int loadPath(String s) {
            if (s == null || s.isEmpty()) {
                return 0;
            }
            int n = s.length();
            if (n + 4 > pathBuf.length) {
                pathBuf = new char[Math.max(n + 16, pathBuf.length * 2)];
            }
            s.getChars(0, n, pathBuf, 0);
            return n;
        }

        private int fitPathChars(FontMetrics fm, char[] buffer, int len, int maxW) {
            pathTruncFrom = -1;
            if (len <= 0 || maxW <= 0) {
                return 0;
            }
            if (fm.charsWidth(buffer, 0, len) <= maxW) {
                return len;
            }
            int ellW = fm.stringWidth("...");
            if (maxW <= ellW) {
                return 0;
            }
            int lo = 0;
            int hi = len;
            int limit = maxW - ellW;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (fm.charsWidth(buffer, 0, mid) <= limit) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            buffer[lo] = '.';
            buffer[lo + 1] = '.';
            buffer[lo + 2] = '.';
            pathTruncFrom = lo;
            return lo + 3;
        }
    }

    // ------------------------------------------------------------------ Matrix view (Model + Zero-alloc Renderer)
    static final class MatrixModel extends AbstractTableModel implements AuditRows {
        private static final int FIXED_COLS = 5;
        private List<ApiGroup> groups = List.of();
        private Map<ApiGroup, Integer> rowMap = new IdentityHashMap<>();
        private List<String> roleKeys = List.of();
        private List<String> roleNames = List.of();
        private RoleResult[][] grid = new RoleResult[0][0];

        void setGroups(List<ApiGroup> sorted) {
            this.groups = sorted == null ? List.of() : sorted;
            this.rowMap = new IdentityHashMap<>();
            Map<String, Integer> roleColMap = new LinkedHashMap<>();
            List<String> names = new ArrayList<>();
            for (int r = 0; r < this.groups.size(); r++) {
                ApiGroup g = this.groups.get(r);
                rowMap.put(g, r);
                for (RoleResult rr : g.results()) {
                    String k = rr.roleKey();
                    if (!roleColMap.containsKey(k)) {
                        roleColMap.put(k, roleColMap.size());
                        names.add(Text.nz(rr.roleName()));
                    }
                }
            }
            this.roleKeys = new ArrayList<>(roleColMap.keySet());
            this.roleNames = names;
            int rc = roleKeys.size();
            RoleResult[][] nextGrid = new RoleResult[this.groups.size()][rc];
            for (int r = 0; r < this.groups.size(); r++) {
                for (RoleResult rr : this.groups.get(r).results()) {
                    Integer c = roleColMap.get(rr.roleKey());
                    if (c != null) {
                        nextGrid[r][c] = rr;
                    }
                }
            }
            this.grid = nextGrid;
            fireTableStructureChanged();
        }

        int rowOf(ApiGroup g) {
            Integer idx = g == null ? null : rowMap.get(g);
            return idx != null ? idx : -1;
        }

        ApiGroup groupAt(int row) {
            return row >= 0 && row < groups.size() ? groups.get(row) : null;
        }

        RoleResult roleResultAt(int row, int col) {
            int rc = col - FIXED_COLS;
            if (row < 0 || row >= grid.length || rc < 0 || rc >= roleKeys.size()) {
                return null;
            }
            return grid[row][rc];
        }

        String roleFullName(int col) {
            int rc = col - FIXED_COLS;
            return rc >= 0 && rc < roleNames.size() ? roleNames.get(rc) : null;
        }

        @Override
        public int getRowCount() {
            return groups.size();
        }

        @Override
        public int getColumnCount() {
            return FIXED_COLS + roleKeys.size();
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "#";
                case 1 -> "Nhánh";
                case 2 -> "Method";
                case 3 -> "Path";
                case 4 -> "Gốc";
                default -> {
                    int rc = column - FIXED_COLS;
                    yield rc >= 0 && rc < roleNames.size() ? roleNames.get(rc) : "";
                }
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ApiGroup g = groupAt(rowIndex);
            if (g == null) {
                return "";
            }
            return switch (columnIndex) {
                case 0 -> Text.apiLabel(g.gid());
                case 1 -> g.branch();
                case 2 -> g.method();
                case 3 -> g.path();
                case 4 -> g.origStatus() != null ? g.origStatus() : "-";
                default -> compactRoleCode(roleResultAt(rowIndex, columnIndex));
            };
        }

        @Override
        public AuditLevel levelAt(int modelRow) {
            ApiGroup g = groupAt(modelRow);
            return g == null ? AuditLevel.NONE : Audit.display(g);
        }

        @Override
        public Object accentAt(int modelRow) {
            ApiGroup g = groupAt(modelRow);
            return g == null ? null : g.branchId();
        }
    }

    private static final class MatrixCellRenderer extends JComponent implements TableCellRenderer, ThemedTree.ExtensionRenderer {
        private final MatrixModel model;
        private Theme cachedTheme;
        private int cachedGen = -1;
        private Color cachedBg;
        private Color cachedFg;

        private Theme activeTheme;
        private int activeModelRow;
        private int activeModelCol;
        private boolean selected;
        private Color cellBg;
        private Color branchCol;
        private char[] buf = new char[256];
        private int textLen;
        private int pathTruncFrom = -1;
        private AuditLevel roleLevel = AuditLevel.NONE;
        private Theme.Token methodTok;
        private boolean roleCell;

        MatrixCellRenderer(MatrixModel model) {
            this.model = model;
            setOpaque(true);
        }

        private Theme resolveTheme(Color bg, Color fg) {
            int gen = Theme.generation();
            if (cachedTheme != null && cachedGen == gen && cachedBg == bg && cachedFg == fg) {
                return cachedTheme;
            }
            Theme t = Theme.of(bg, fg);
            cachedTheme = t;
            cachedGen = gen;
            cachedBg = bg;
            cachedFg = fg;
            return t;
        }

        private void loadText(String s) {
            if (s == null || s.isEmpty()) {
                textLen = 0;
                return;
            }
            int n = s.length();
            if (n + 4 > buf.length) {
                buf = new char[Math.max(n + 16, buf.length * 2)];
            }
            s.getChars(0, n, buf, 0);
            textLen = n;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Theme th = table != null ? resolveTheme(table.getBackground(), table.getForeground()) : Theme.current();
            this.activeTheme = th;
            this.selected = isSelected;
            this.activeModelRow = table != null && row >= 0 ? table.convertRowIndexToModel(row) : row;
            this.activeModelCol = table != null && column >= 0 ? table.convertColumnIndexToModel(column) : column;
            this.branchCol = th.branchAccent(model.accentAt(activeModelRow));

            String raw = value == null ? "" : value.toString();
            loadText(raw);
            this.methodTok = activeModelCol == 2 ? th.method(raw) : null;

            roleCell = activeModelCol >= MatrixModel.FIXED_COLS;
            if (roleCell) {
                ApiGroup g = model.groupAt(activeModelRow);
                RoleResult rr = model.roleResultAt(activeModelRow, activeModelCol);
                roleLevel = effectiveRoleLevel(g, rr);
                if (isSelected) {
                    cellBg = th.selectionBg;
                } else if (roleLevel != AuditLevel.NONE) {
                    cellBg = tokenForAudit(roleLevel).apply(th).fill();
                } else {
                    cellBg = th.zebra[row & 1];
                }
                setToolTipText(rr != null ? roleTooltip(g, rr) : null);
            } else {
                roleLevel = AuditLevel.NONE;
                if (isSelected) {
                    cellBg = th.selectionBg;
                } else {
                    cellBg = th.rowBackground(model.levelAt(activeModelRow), row);
                }
                if (activeModelCol == 3) {
                    ApiGroup g = model.groupAt(activeModelRow);
                    setToolTipText(g != null ? g.url() : null);
                } else {
                    setToolTipText(raw.isEmpty() ? null : raw);
                }
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme th = activeTheme != null ? activeTheme : Theme.current();
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                g2.setColor(cellBg != null ? cellBg : th.surface);
                g2.fillRect(0, 0, w, h);

                int pad = th.scaled(6);
                if (activeModelCol == 0 && branchCol != null) {
                    g2.setColor(branchCol);
                    g2.fillRect(0, 0, Math.max(3, th.scaled(3)), h);
                }

                if (roleCell) {
                    if (textLen > 0) {
                        Theme.Token tok = roleLevel == AuditLevel.NONE ? th.neutral : tokenForAudit(roleLevel).apply(th);
                        Color fg = selected ? th.selectionFg : (roleLevel == AuditLevel.NONE ? th.muted : tok.text());
                        g2.setFont(roleLevel == AuditLevel.NONE ? th.monoFont : th.boldFont);
                        FontMetrics fm = g2.getFontMetrics();
                        int tw = fm.charsWidth(buf, 0, textLen);
                        int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                        g2.setColor(fg);
                        g2.drawChars(buf, 0, textLen, Math.max(2, (w - tw) / 2), ty);
                    }
                    return;
                }

                if (activeModelCol == 2 && textLen > 0) {
                    Theme.Token mTok = methodTok != null ? methodTok : th.neutral;
                    g2.setFont(th.smallBoldFont);
                    FontMetrics fm = g2.getFontMetrics();
                    int pw = Math.min(w - pad * 2, fm.charsWidth(buf, 0, textLen) + th.scaled(12));
                    int ph = Math.min(h - th.scaled(6), th.scaled(19));
                    int px = pad;
                    int py = (h - ph) / 2;
                    g2.setColor(mTok.fill());
                    g2.fillRect(px, py, pw - 1, ph - 1);
                    g2.setColor(mTok.border());
                    g2.drawRect(px, py, pw - 1, ph - 1);
                    g2.setColor(mTok.text());
                    int ty = py + (ph - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawChars(buf, 0, textLen, px + Math.max(2, (pw - fm.charsWidth(buf, 0, textLen)) / 2), ty);
                    return;
                }

                if (activeModelCol == 3 && textLen > 0) {
                    g2.setFont(th.monoFont);
                    FontMetrics fm = g2.getFontMetrics();
                    int drawLen = fitBuf(fm, w - pad * 2);
                    int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                    AuditRenderer.drawPathBuffer(g2, fm, buf, drawLen, pathTruncFrom, pad, ty,
                            selected ? th.selectionFg : th.fg, selected ? th.selectionFg : th.muted);
                    return;
                }

                if (activeModelCol == 4 && textLen > 0) {
                    g2.setFont(th.boldFont);
                    FontMetrics fm = g2.getFontMetrics();
                    int code = 0;
                    for (int i = 0; i < textLen; i++) {
                        char ch = buf[i];
                        if (ch < '0' || ch > '9') {
                            code = -1;
                            break;
                        }
                        code = code * 10 + (ch - '0');
                    }
                    g2.setColor(selected ? th.selectionFg : (code > 0 ? th.statusColor(code) : th.muted));
                    int tw = fm.charsWidth(buf, 0, textLen);
                    int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawChars(buf, 0, textLen, Math.max(2, (w - tw) / 2), ty);
                    return;
                }

                if (textLen > 0) {
                    Font f = activeModelCol == 0 ? th.monoBoldFont : th.baseFont;
                    g2.setFont(f);
                    FontMetrics fm = g2.getFontMetrics();
                    int drawLen = fitBuf(fm, w - pad * 2);
                    int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                    g2.setColor(selected ? th.selectionFg : (activeModelCol == 0 ? th.muted : th.fg));
                    g2.drawChars(buf, 0, drawLen, pad, ty);
                }
            } finally {
                g2.dispose();
            }
        }

        private int fitBuf(FontMetrics fm, int maxW) {
            pathTruncFrom = -1;
            if (textLen <= 0 || maxW <= 0) {
                return 0;
            }
            if (fm.charsWidth(buf, 0, textLen) <= maxW) {
                return textLen;
            }
            int ellW = fm.stringWidth("...");
            if (maxW <= ellW) {
                return 0;
            }
            int lo = 0;
            int hi = textLen;
            int limit = maxW - ellW;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (fm.charsWidth(buf, 0, mid) <= limit) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            buf[lo] = '.';
            buf[lo + 1] = '.';
            buf[lo + 2] = '.';
            pathTruncFrom = lo;
            return lo + 3;
        }
    }

    private static final class MatrixHeaderRenderer extends JLabel implements TableCellRenderer, ThemedTree.ExtensionRenderer {
        private boolean roleCol;

        MatrixHeaderRenderer() {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Theme th = table != null ? Theme.of(table.getBackground(), table.getForeground()) : Theme.current();
            int mc = table != null && column >= 0 ? table.convertColumnIndexToModel(column) : column;
            roleCol = mc >= MatrixModel.FIXED_COLS;
            String full = value == null ? "" : value.toString();
            setFont(th.smallBoldFont);
            JTableHeader hdr = table != null ? table.getTableHeader() : null;
            setBackground(hdr != null && hdr.getBackground() != null ? hdr.getBackground() : th.surfaceAlt);
            setForeground(th.fg);
            setHorizontalAlignment(roleCol ? CENTER : LEFT);
            setToolTipText(full);
            int avail = table != null && column >= 0 && column < table.getColumnCount()
                    ? Math.max(24, table.getColumnModel().getColumn(column).getWidth() - th.scaled(12))
                    : 120;
            FontMetrics fm = getFontMetrics(th.smallBoldFont);
            setText(abbreviate(full, fm, avail));
            return this;
        }

        private static String abbreviate(String s, FontMetrics fm, int maxW) {
            if (s.isEmpty() || fm.stringWidth(s) <= maxW) {
                return s;
            }
            String ell = "...";
            int ew = fm.stringWidth(ell);
            if (maxW <= ew) {
                return ell;
            }
            int end = s.length();
            while (end > 1 && fm.stringWidth(s.substring(0, end)) + ew > maxW) {
                end--;
            }
            return s.substring(0, end) + ell;
        }
    }
}
