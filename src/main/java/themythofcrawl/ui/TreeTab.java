package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.ProxyInterceptQueue;
import themythofcrawl.core.ProxyInterceptQueue.PendingRequest;
import themythofcrawl.core.ProxyInterceptQueue.ResolutionKind;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.RoleResult;
import themythofcrawl.ui.kit.Breadcrumb;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SegmentedToggle;
import themythofcrawl.ui.theme.Theme;

/**
 * 'Cây' tab (V_3.5_beta Tree-First Workspace):
 * - Full branch tree showing captured APIs AND live Proxy-held {@code [CHỜ DUYỆT]} requests right inside
 *   their exact target Branch/Path.
 * - Inline editable Request editor right below the tree with a contextual 1-click Quick Action Bar:
 *   - For live {@code [CHỜ DUYỆT]} requests: Forward to Server (with any inline edits), Save Draft, Drop,
 *     Format JSON, Restore, or open the Detailed Queue.
 *   - For normal APIs / Roles: Send Now (with inline edits), Save Edit to API, Format JSON, Restore,
 *     Quick Action menu, or open full Request Composer / Action Workbench only when needed.
 */
final class TreeTab {
    private static final String CARD_API = "api";
    private static final String CARD_HELD = "held";

    final JPanel panel = new JPanel(new BorderLayout());
    private final JPanel content = new JPanel(new BorderLayout());
    private final JPanel placeholder = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 40));
    private final MainPanel ui;
    private final Engine engine;
    final BranchTree tree;
    private final Breadcrumb headerBreadcrumb = new Breadcrumb();
    private final JLabel headerMeta = Ui.muted(" ");
    private final JButton windowBtn;
    private final JButton more;
    private final JPanel topHeaderStack;
    private final JPanel focusBanner;
    private JLabel focusLabel;
    private SegmentedToggle focusScopeToggle;

    private final JLabel reqLabel = Ui.bold(" Request — chọn 1 API / vai trò hoặc mục [Chờ duyệt] trong cây");
    private final JLabel respLabel = Ui.bold(" Response");
    private final UiHost.Viewer req;
    private final UiHost.Viewer resp;

    // Contextual inline action bar right above the Request/Response editors
    private final CardLayout inlineCardLayout = new CardLayout();
    private final JPanel inlineActionBar = new JPanel(inlineCardLayout);
    private final Pill heldBadge = new Pill("Chờ duyệt · Chưa gửi", t -> t.danger, t -> Icons.strategyShield(t.danger.text(), 12));
    private final JLabel heldMeta = Ui.muted("");
    private final JButton quickSendBtn;
    private final JButton quickSaveBtn;
    private final JButton quickActionBtn;
    private final JButton quickComposerBtn;

    private JFrame window;
    private JSplitPane treeSplit;
    private JPanel bottomDetail;
    private int lastTreeDivider = -1;
    boolean stale = true;
    private ApiGroup shownGroup;
    private RoleResult shown;
    private PendingRequest shownHeld;
    private Object shownState;
    private int lastKnownHeldCount;

    TreeTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        tree = new BranchTree(engine);
        tree.onMove(ui::moveViaDragDrop);
        tree.onChip((c, g, t, x, y) -> ui.chip(c, g, t, x, y));
        tree.tree.addTreeSelectionListener(e -> onSelected());
        tree.tree.addMouseListener(ApiTab.treePopup(tree.tree, e -> {
            ui.useSource(tree);
            TreeMenus.show(ui, tree, e.getComponent(), e.getX(), e.getY());
        }));
        Ui.key(tree.tree, "ctrl shift C", () -> {
            ui.useSource(tree);
            ui.copy(ui.scope().groups());
        });

        windowBtn = Ui.secondaryBtn("Mở cửa sổ riêng", t -> Icons.window(t.fg));
        windowBtn.addActionListener(Ui.safe(e -> toggleWindow()));
        more = Ui.dropdown("Thao tác", t -> Icons.withChevron(Icons.gear(t.fg), t.fg), "Thao tác nhánh / API", this::menu);

        headerBreadcrumb.setMaxSegments(4);
        headerBreadcrumb.onSegmentClick(seg -> tree.selectBranchPath(seg));

        JButton sendSelBtn = Ui.solidPrimary("Gửi mục chọn", t -> Icons.send(t.accent.onSolid()));
        sendSelBtn.setToolTipText("Gửi lên Server các mục đang chọn (gồm cả [Chờ duyệt] và API đã lưu)");
        sendSelBtn.addActionListener(Ui.safe(e -> sendSelectedItems()));

        JButton focusTreeBtn = Ui.secondaryBtn("Tập trung", t -> Icons.focus(t.accent.text(), 13));
        focusTreeBtn.setToolTipText("Tập trung vào nhánh đang chọn (mặc định hiển thị cả nhánh con bên dưới)");
        focusTreeBtn.addActionListener(Ui.safe(e -> {
            if (tree.focusPath() != null) {
                clearFocusBranch();
            } else {
                String target = tree.selectedBranch();
                if (target == null || target.isBlank()) {
                    target = engine.activeBranch().name();
                }
                setFocusBranch(target, true);
            }
        }));

        JPanel leftBar = Ui.flow(FlowLayout.LEFT, headerBreadcrumb, headerMeta);
        javax.swing.JComboBox<String> crawlRole = AccessMatrixTab.crawlRoleCombo(engine);
        crawlRole.setPrototypeDisplayValue("Tự nhận diện (token/cookie)");
        JPanel rightBar = Ui.flow(FlowLayout.RIGHT, ui.newTreeViewSwitch(), Ui.muted("Crawl bằng:"), crawlRole, focusTreeBtn, sendSelBtn,
                more, windowBtn);

        JPanel head = new JPanel(new BorderLayout(Theme.SPACE_1, 0));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(2, Theme.SPACE_1, 2, Theme.SPACE_1));
        head.add(leftBar, BorderLayout.CENTER);
        head.add(rightBar, BorderLayout.EAST);

        focusBanner = buildFocusBanner();
        focusBanner.setVisible(false);
        JPanel topStack = new JPanel(new BorderLayout(0, 2));
        topStack.setOpaque(false);
        topStack.add(head, BorderLayout.NORTH);
        topStack.add(focusBanner, BorderLayout.SOUTH);
        this.topHeaderStack = topStack;

        // Editable Request viewer right inside TreeTab (Tree-First inline quick edit!)
        req = ui.host.requestViewer(true);
        resp = ui.host.responseViewer();

        // ---- Card 1: Normal API / Role inline quick-edit & quick-send bar
        quickSendBtn = Ui.solidPrimary("Gửi ngay", t -> Icons.send(t.accent.onSolid(), 12));
        quickSendBtn.setToolTipText("Gửi trực tiếp nội dung Request đang hiển thị (kể cả phần bạn vừa sửa tại chỗ)");
        quickSendBtn.addActionListener(Ui.safe(e -> quickSendCurrent()));

        quickSaveBtn = Ui.tonalBtn("Lưu sửa vào API", t -> Icons.check(t.success.text(), 12), t -> t.success);
        quickSaveBtn.setToolTipText("Lưu nội dung Request vừa chỉnh sửa tại chỗ đè vào API này");
        quickSaveBtn.addActionListener(Ui.safe(e -> quickSaveEdit()));

        JButton fmtJsonApiBtn = Ui.secondaryBtn("Format JSON", null);
        fmtJsonApiBtn.setToolTipText("Định dạng đẹp JSON Body và cập nhật Content-Length");
        fmtJsonApiBtn.addActionListener(Ui.safe(e -> formatCurrentRequestJson()));

        JButton resetApiBtn = Ui.secondaryBtn("Khôi phục", t -> Icons.undo(t.muted, 12));
        resetApiBtn.setToolTipText("Khôi phục lại Request gốc chưa chỉnh sửa");
        resetApiBtn.addActionListener(Ui.safe(e -> restoreOriginalRequest()));

        JButton[] qaRef = new JButton[1];
        quickActionBtn = Ui.secondaryBtn("Chặn & gửi lại", t -> Icons.withChevron(Icons.strategyShield(t.accent.text(), 12), t.fg));
        quickActionBtn.setToolTipText("Chờ duyệt, Giữ lại, Cho đi thẳng cho API này, hoặc mở Chặn & gửi lại");
        quickActionBtn.addActionListener(Ui.safe(e -> {
            JPopupMenu m = new JPopupMenu();
            if (shownGroup != null) {
                ActionMenus.populateApiActionItems(m, panel, engine, shownGroup,
                        shown != null ? List.of(shown) : List.of(), () -> {
                            ui.syncHeader();
                            onSelected();
                        });
            } else {
                ActionMenus.populateToolbarDropdown(m, panel, engine, () -> shownGroup, ui::syncHeader);
            }
            m.show(qaRef[0], 0, qaRef[0].getHeight());
        }));
        qaRef[0] = quickActionBtn;

        quickComposerBtn = Ui.secondaryBtn("Sửa & gửi...", t -> Icons.edit(t.info.text(), 12));
        quickComposerBtn.setToolTipText("Mở trình sửa chi tiết (chọn nhiều vai trò, đổi Host/Port, So với Gốc, Lịch sử phiên)");
        quickComposerBtn.addActionListener(Ui.safe(e -> {
            if (shownGroup != null) {
                RequestComposerDialog.showFloating(panel, engine, shownGroup, shown, () -> {
                    ui.syncHeader();
                    if (shownGroup != null && shown != null) {
                        showEntry(shownGroup, shown);
                    }
                });
            }
        }));

        JPanel apiQuickCard = new JPanel(new BorderLayout(6, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setBackground(t.surfaceAlt);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 1, 0, t.border),
                        BorderFactory.createEmptyBorder(3, 6, 3, 6)));
            }
        };
        apiQuickCard.setOpaque(true);
        apiQuickCard.setBackground(Theme.current().surfaceAlt);
        apiQuickCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.current().border),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        apiQuickCard.add(Ui.flow(FlowLayout.LEFT, quickSendBtn, quickSaveBtn, fmtJsonApiBtn, resetApiBtn), BorderLayout.WEST);
        apiQuickCard.add(Ui.flow(FlowLayout.RIGHT, quickActionBtn, quickComposerBtn), BorderLayout.EAST);

        // ---- Card 2: Live HELD_PENDING inline quick-resolve bar (NO popup needed!)
        JButton heldForwardBtn = Ui.solidPrimary("Gửi Server", t -> Icons.send(t.accent.onSolid(), 12));
        heldForwardBtn.setToolTipText("Gửi lên server — đẩy ngay request này kèm mọi chỉnh sửa bên dưới lên Server thật");
        heldForwardBtn.addActionListener(Ui.safe(e -> resolveShownHeld(ResolutionKind.FORWARD)));

        JButton heldSaveDraftBtn = Ui.tonalBtn("Lưu nháp",
                t -> Icons.strategyShield(t.warning.text(), 12), t -> t.warning);
        heldSaveDraftBtn.setToolTipText("Lưu request vào Cây để xem/gửi sau, không gửi lên Server ngay");
        heldSaveDraftBtn.addActionListener(Ui.safe(e -> resolveShownHeld(ResolutionKind.HOLD_AND_SPOOF)));

        JButton heldDropBtn = Ui.dangerBtn("Hủy (Drop)", t -> Icons.stop(t.danger.text(), 12));
        heldDropBtn.setToolTipText("Hủy bỏ request tại Proxy — không gửi lên Server và không lưu");
        heldDropBtn.addActionListener(Ui.safe(e -> resolveShownHeld(ResolutionKind.DROP)));

        JButton heldFmtBtn = Ui.secondaryBtn("JSON", null);
        heldFmtBtn.setToolTipText("Định dạng đẹp JSON Body và cập nhật Content-Length");
        heldFmtBtn.addActionListener(Ui.safe(e -> formatCurrentRequestJson()));

        JButton heldQueueBtn = Ui.secondaryBtn("Hàng chờ...", t -> Icons.strategyShield(t.muted, 12));
        heldQueueBtn.setToolTipText("Mở cửa sổ Hàng chờ Chặn Proxy chi tiết");
        heldQueueBtn.addActionListener(Ui.safe(e -> ProxyInterceptDialog.showFloating(ui)));

        JPanel heldQuickCard = new JPanel(new BorderLayout(8, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setBackground(t.danger.fill());
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 1, 0, t.danger.border()),
                        BorderFactory.createEmptyBorder(3, 6, 3, 6)));
            }
        };
        heldQuickCard.setOpaque(true);
        heldQuickCard.setBackground(Theme.current().danger.fill());
        heldQuickCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.current().danger.border()),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));

        heldMeta.setMinimumSize(new Dimension(0, 18));
        JPanel heldLeft = new JPanel(new BorderLayout(6, 0));
        heldLeft.setOpaque(false);
        heldLeft.setMinimumSize(new Dimension(0, 22));
        heldLeft.add(heldBadge, BorderLayout.WEST);
        heldLeft.add(heldMeta, BorderLayout.CENTER);

        heldQuickCard.add(heldLeft, BorderLayout.CENTER);
        heldQuickCard.add(Ui.flow(FlowLayout.RIGHT, heldForwardBtn, heldSaveDraftBtn, heldDropBtn, heldFmtBtn, heldQueueBtn),
                BorderLayout.EAST);

        inlineActionBar.add(apiQuickCard, CARD_API);
        inlineActionBar.add(heldQuickCard, CARD_HELD);
        inlineCardLayout.show(inlineActionBar, CARD_API);

        JPanel rq = new JPanel(new BorderLayout());
        rq.add(reqLabel, BorderLayout.NORTH);
        rq.add(req.component(), BorderLayout.CENTER);
        JPanel rs = new JPanel(new BorderLayout());
        rs.add(respLabel, BorderLayout.NORTH);
        rs.add(resp.component(), BorderLayout.CENTER);
        JSplitPane viewers = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, rq, rs);
        viewers.setResizeWeight(0.5);


        JPanel bottomPane = new JPanel(new BorderLayout(0, 0));
        bottomPane.add(inlineActionBar, BorderLayout.NORTH);
        bottomPane.add(viewers, BorderLayout.CENTER);

        // V_7.0: tree-table — API / role rows line up in fixed columns under a header, whatever their depth
        TreeColumns.enable(tree.tree);
        javax.swing.JScrollPane treeScroll = MainPanel.scroll(tree.tree);
        treeScroll.getHorizontalScrollBar().setUnitIncrement(24);
        TreeColumns.installHeader(tree.tree, treeScroll);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeScroll, bottomPane);
        split.setResizeWeight(0.72);
        treeSplit = split;
        bottomDetail = bottomPane;
        // The tree is where most of the work happens: give it ~70% of the height by default
        bottomPane.setMinimumSize(new Dimension(0, 120));
        viewers.setPreferredSize(new Dimension(600, 200));
        split.setOneTouchExpandable(true);
        content.add(topHeaderStack, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        if ("0".equals(ui.uiPrefs.get(KEY_TREE_DETAIL))) {
            bottomPane.setVisible(false);
        }
        placeholder.add(new JLabel("Cây nhánh đang mở ở cửa sổ riêng."));
        placeholder.add(Ui.secondaryBtn("Gắn lại vào tab", null));
        ((JButton) placeholder.getComponent(1)).addActionListener(Ui.safe(e -> closeWindow()));
        panel.add(content, BorderLayout.CENTER);
        syncHeaderBreadcrumb();
        syncQuickButtons();
    }

    private JPanel buildFocusBanner() {
        focusLabel = new JLabel("Tập trung");
        focusLabel.setIcon(Icons.themed(t -> Icons.focus(t.accent.text(), 14)));
        focusLabel.setIconTextGap(6);
        focusScopeToggle = new SegmentedToggle(
                new SegmentedToggle.Option("Chỉ nhánh này", "Chỉ hiện API nằm trực tiếp trong nhánh đang tập trung", t -> t.accent, null),
                new SegmentedToggle.Option("Kèm nhánh con", "Hiện cả các nhánh con và API bên dưới", t -> t.accent, null)
        ).setFixedHeight(20).onChange(idx -> {
            String fp = tree.focusPath();
            if (fp != null) {
                setFocusBranch(fp, idx == 1);
            }
        });
        JButton exitBtn = Ui.button("Thoát", t -> Icons.error(t.danger.text(), 12), this::clearFocusBranch);
        exitBtn.setToolTipText("Thoát chế độ Tập trung, hiển thị lại toàn bộ cây");
        exitBtn.setPreferredSize(new Dimension(exitBtn.getPreferredSize().width, 20));

        JPanel bar = new JPanel(new BorderLayout(8, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setBackground(t.accent.fill());
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 1, 0, t.accent.border()),
                        BorderFactory.createEmptyBorder(2, 8, 2, 6)));
                if (focusLabel != null) {
                    focusLabel.setFont(t.smallBoldFont);
                    focusLabel.setForeground(t.accent.text());
                }
            }
        };
        bar.setOpaque(true);
        bar.setBackground(Theme.current().accent.fill());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.current().accent.border()),
                BorderFactory.createEmptyBorder(2, 8, 2, 6)));
        bar.setPreferredSize(new Dimension(0, 26));
        focusLabel.setFont(Theme.current().smallBoldFont);
        focusLabel.setForeground(Theme.current().accent.text());
        bar.add(focusLabel, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 1));
        right.setOpaque(false);
        right.add(focusScopeToggle);
        right.add(exitBtn);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    void setFocusBranch(String path, boolean recursive) {
        if (path == null || path.isBlank()) {
            clearFocusBranch();
            return;
        }
        String clean = themythofcrawl.core.Hierarchy.normalize(path);
        tree.setFocus(clean, recursive);
        focusLabel.setText("Tập trung: " + clean + (recursive ? "  (Kèm nhánh con)" : "  (Chỉ nhánh này)"));
        focusScopeToggle.setSelectedIndex(recursive ? 1 : 0);
        focusBanner.setVisible(true);
        tree.rebuild(engine.config().showGhosts);
        tree.expandFolders(true);
        syncHeaderBreadcrumb();
        topHeaderStack.revalidate();
        topHeaderStack.repaint();
        ui.flash("Đang tập trung vào: " + clean + (recursive ? " (Kèm nhánh con)" : " (Chỉ nhánh này)"));
    }

    void clearFocusBranch() {
        tree.clearFocus();
        focusBanner.setVisible(false);
        tree.rebuild(engine.config().showGhosts);
        syncHeaderBreadcrumb();
        topHeaderStack.revalidate();
        topHeaderStack.repaint();
    }

    /** Jumps directly to a specific API or Branch from the Excel / Inventory tab. */
    void jumpTo(ApiGroup targetGroup, String branchPath, boolean focusOnBranch) {
        String targetBranch = targetGroup != null && !Text.blank(targetGroup.branch())
                ? targetGroup.branch()
                : branchPath;
        if (focusOnBranch && !Text.blank(targetBranch)) {
            setFocusBranch(targetBranch, true);
        } else {
            if (tree.focusPath() != null && !Text.blank(targetBranch)
                    && !themythofcrawl.core.Hierarchy.matchesPrefix(targetBranch, tree.focusPath())) {
                clearFocusBranch();
            } else {
                tree.rebuild(engine.config().showGhosts);
            }
        }
        if (targetGroup != null) {
            tree.selectGroup(targetGroup);
            showEntry(targetGroup, targetGroup.origResult());
        } else if (!Text.blank(targetBranch)) {
            tree.selectBranchPath(targetBranch);
        }
        syncHeaderBreadcrumb();
    }

    private void syncQuickButtons() {
        boolean hasApi = shownGroup != null && shownHeld == null;
        quickSendBtn.setEnabled(hasApi);
        quickSaveBtn.setEnabled(hasApi);
        quickComposerBtn.setEnabled(hasApi);
    }

    private byte[] currentEditorBytes(byte[] fallback) {
        byte[] ed = req.edited();
        if (ed != null && ed.length > 0) {
            return RequestComposerDialog.recalculateContentLength(ed);
        }
        return fallback;
    }

    /**
     * Unified Send handler for the TreeTab header "Gửi mục chọn" button and menu item:
     * forwards any selected {@code [CHỜ DUYỆT]} requests (preserving inline edits for the currently shown item)
     * AND replays any selected saved API groups.
     */
    void sendSelectedItems() {
        ui.useSource(tree);
        List<PendingRequest> heldSel = tree.selectedHeld();
        List<ApiGroup> groupSel = tree.selectedGroups();
        int forwarded = 0;
        if (!heldSel.isEmpty()) {
            PendingRequest activeShown = shownHeld;
            for (PendingRequest pr : heldSel) {
                byte[] payload = (activeShown != null && activeShown.id() == pr.id())
                        ? currentEditorBytes(pr.originalRequest())
                        : pr.originalRequest();
                if (engine.proxyQueue().resolve(pr.id(), ResolutionKind.FORWARD, payload)) {
                    forwarded++;
                    if (activeShown != null && activeShown.id() == pr.id()) {
                        shownHeld = null;
                        inlineCardLayout.show(inlineActionBar, CARD_API);
                        respLabel.setText(" Response — đang đẩy lên Server...");
                    }
                }
            }
        }
        if (!groupSel.isEmpty()) {
            ui.send(groupSel);
        }
        if (forwarded > 0) {
            ui.syncHeader();
            tree.rebuild(engine.config().showGhosts);
            ui.flash("Đã gửi lên Server " + forwarded + " request chờ duyệt"
                    + (!groupSel.isEmpty() ? " + " + groupSel.size() + " API" : "") + ".");
        } else if (groupSel.isEmpty()) {
            ui.flash("Hãy chọn ít nhất 1 mục [Chờ duyệt], API hoặc Nhánh trên cây để gửi.");
        }
    }

    private void resolveShownHeld(ResolutionKind kind) {
        PendingRequest pr = shownHeld;
        if (pr == null) {
            return;
        }
        byte[] payload = (kind == ResolutionKind.DROP) ? null : currentEditorBytes(pr.originalRequest());
        boolean ok = engine.proxyQueue().resolve(pr.id(), kind, payload);
        if (ok) {
            shownHeld = null;
            inlineCardLayout.show(inlineActionBar, CARD_API);
            if (kind == ResolutionKind.FORWARD) {
                respLabel.setText(" Response — đang đẩy lên Server...");
            }
            ui.syncHeader();
            tree.rebuild(engine.config().showGhosts);
            ui.flash(kind.label + ": " + pr.method() + " " + Text.shortPath(pr.path(), 42));
        }
    }

    private void quickSendCurrent() {
        ApiGroup g = shownGroup;
        RoleResult r = shown;
        if (g == null) {
            return;
        }
        byte[] edited = req.edited();
        if (edited == null || edited.length == 0) {
            // Unedited -> standard replay for this group/role
            if (r != null && !r.original()) {
                ui.send(List.of(g));
            } else {
                byte[] orig = engine.requestOf(g, r != null ? r : g.origResult());
                sendAdHocAsync(g, r, orig);
            }
            return;
        }
        sendAdHocAsync(g, r, RequestComposerDialog.recalculateContentLength(edited));
    }

    private void sendAdHocAsync(ApiGroup g, RoleResult r, byte[] payload) {
        quickSendBtn.setEnabled(false);
        respLabel.setText(" Response — đang gửi trực tiếp lên Server...");
        Thread t = new Thread(() -> {
            Engine.AdHocSendResult res = engine.sendAdHocRequest(g.service(), payload, null, g, ActionEngine.ActionMode.NONE);
            SwingUtilities.invokeLater(() -> {
                quickSendBtn.setEnabled(shownGroup != null);
                if (res.error() != null && !res.error().isEmpty()) {
                    respLabel.setText(" Response [Lỗi: " + res.error() + "]");
                    resp.show(g.service(), ("Loi gui request: " + asciiSafe(res.error()))
                            .getBytes(StandardCharsets.ISO_8859_1));
                } else {
                    respLabel.setText(" Response [Gửi trực tiếp | HTTP " + res.status() + " | "
                            + (res.response() != null ? res.response().length : 0) + " B | "
                            + res.elapsedMs() + " ms]");
                    resp.show(g.service(), res.response());
                }
                ui.flash("Đã gửi nhanh " + g.method() + " " + Text.shortPath(g.path(), 36)
                        + " -> HTTP " + res.status());
            });
        }, "JCrawl-tree-quick-send");
        t.setDaemon(true);
        t.start();
    }

    private void quickSaveEdit() {
        ApiGroup g = shownGroup;
        if (g == null) {
            return;
        }
        byte[] edited = req.edited();
        if (edited == null || edited.length == 0) {
            ui.flash("Chưa có thay đổi nào trong khung Request để lưu.");
            return;
        }
        byte[] fixed = RequestComposerDialog.recalculateContentLength(edited);
        boolean ok = engine.updateApiRequestFromComposer(g, shown, g.service(), fixed, null);
        if (ok) {
            req.show(g.service(), fixed);
            ui.flash("Đã lưu chỉnh sửa Request vào " + Text.apiLabel(g.gid()) + "!");
        }
    }

    private void formatCurrentRequestJson() {
        if (shownHeld != null) {
            byte[] cur = currentEditorBytes(shownHeld.originalRequest());
            String pretty = RequestComposerDialog.formatHttpWithPrettyJson(cur);
            req.show(shownHeld.service(), RequestComposerDialog.recalculateContentLength(
                    pretty.getBytes(StandardCharsets.ISO_8859_1)));
        } else if (shownGroup != null) {
            byte[] base = engine.requestOf(shownGroup, shown != null ? shown : shownGroup.origResult());
            byte[] cur = currentEditorBytes(base);
            String pretty = RequestComposerDialog.formatHttpWithPrettyJson(cur);
            req.show(shownGroup.service(), RequestComposerDialog.recalculateContentLength(
                    pretty.getBytes(StandardCharsets.ISO_8859_1)));
        } else {
            ui.flash("Chọn 1 API / vai trò (hoặc mục [Chờ duyệt]) trong cây trước rồi bấm Format JSON.");
        }
    }

    private void restoreOriginalRequest() {
        if (shownHeld != null) {
            req.show(shownHeld.service(), shownHeld.originalRequest());
            ui.flash("Đã khôi phục Request gốc đang chờ duyệt.");
        } else if (shownGroup != null) {
            RoleResult r = shown != null ? shown : shownGroup.origResult();
            req.show(shownGroup.service(), engine.shownRequestOf(shownGroup, r));
            ui.flash("Đã khôi phục Request gốc của " + Text.apiLabel(shownGroup.gid()) + ".");
        } else {
            ui.flash("Chọn 1 API / vai trò trong cây trước rồi bấm Khôi phục.");
        }
    }

    private void menu() {
        ui.useSource(tree);
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Gửi mục đang chọn (nhánh / API / vai trò / chờ duyệt)", t -> Icons.send(t.critical.text()),
                this::sendSelectedItems));
        m.add(Ui.item("Đẩy mục đang chọn -> Burp Repeater", Icons::repeater, () -> ui.push(ui.scope().groups())));
        m.add(Ui.item("Đẩy / Gửi có chọn vai trò...", Icons::target, () -> ui.pushDialog(false, null, null)));
        m.add(Ui.item("Copy STT + Path (Ctrl+Shift+C)", t -> Icons.copy(t.muted), () -> ui.copy(ui.scope().groups())));
        m.add(Ui.item("Copy path nhánh", t -> Icons.copy(t.muted), ui::copyBranchPath));
        m.add(Ui.item("Bật / Tắt vai trò cho mục đang chọn...", Icons::sliders, () -> {
            Node n = tree.selectedNode();
            if (n != null && n.kind == Node.Kind.FOLDER) {
                ui.roleScopeDialog(false, themythofcrawl.model.RoleScopes.Scope.BRANCH, List.of((String) n.payload), null, null);
            } else {
                ui.roleScopeDialog(false, themythofcrawl.model.RoleScopes.Scope.API, null, tree.selectedGroups(), null);
            }
        }));
        m.addSeparator();
        m.add(Ui.item("Đặt nhánh đang chọn làm đích cho request mới", Icons::arrowRight, ui::destinationFromTree));
        m.add(Ui.item("Xác nhận vị trí các API đang chọn / trong nhánh", t -> Icons.check(t.success.text()),
                () -> engine.confirm(tree.selectedGroups(), "")));
        m.add(Ui.item("Chuyển các API đang chọn sang đường dẫn khác...", Icons::move, () -> ui.moveDialog(tree.selectedGroups())));
        m.add(Ui.item("Đưa các API đang chọn về vị trí trước", Icons::undo, () -> ui.revert(tree.selectedGroups())));
        m.add(Ui.item("Thêm nhánh con...", Icons::plus, () -> ui.addSubfolder(ui.selectedBranch())));
        m.add(Ui.item("Đổi tên nhánh...", Icons::edit, () -> {
            String b = ui.selectedBranch();
            if (b != null) {
                ui.renameBranchDialog(b);
            }
        }));
        m.add(Ui.item("Xóa nhánh...", t -> Icons.trash(t.danger.text()), () -> ui.deleteBranchDialog(ui.selectedBranch())));
        m.addSeparator();
        m.add(Ui.item("Mở tất cả các nhánh", Icons::plus, () -> tree.expandFolders(true)));
        m.add(Ui.item("Thu gọn tất cả", Icons::minus, () -> tree.expandFolders(false)));
        m.addSeparator();
        m.add(Ui.item("Hướng dẫn Action & Ví dụ thực chiến...", Icons::info,
                () -> ActionWorkbenchDialog.show(panel, engine, shownGroup, null, ActionWorkbenchDialog.TAB_GUIDE, ui::syncHeader)));
        m.add(Ui.item("Hướng dẫn Chọn nhiều...", Icons::info, () -> Ui.popover(more, "Chọn nhiều", Help.SELECT)));
        m.show(more, 0, more.getHeight());
    }

    private void onSelected() {
        if (tree.restoring()) {
            return;
        }
        ui.useSource(tree);
        if (tree.selectionCount() > 1) {
            ui.flash("Đang chọn " + tree.selectionCount() + " mục trong cây = " + tree.selectedGroups().size() + " API");
        }
        Node n = tree.selectedNode();
        syncHeaderBreadcrumb();
        if (n == null) {
            return;
        }
        PendingRequest pr = BranchTree.heldOf(n);
        if (pr != null) {
            showHeldEntry(pr);
            return;
        }
        ApiGroup g = BranchTree.groupOf(n);
        if (g == null) {
            return;
        }
        RoleResult r = n.payload instanceof Object[] arr && arr.length > 1 && arr[1] instanceof RoleResult x ? x : g.origResult();
        showEntry(g, r);
    }

    private void showHeldEntry(PendingRequest pr) {
        shownHeld = pr;
        shownGroup = null;
        shown = null;
        String host = pr.service() != null ? pr.service().hostLabel() : "";
        String meta = pr.method() + " " + Text.shortPath(pr.path(), 34)
                + (host.isEmpty() ? "" : " · " + host) + " · " + pr.reason();
        heldMeta.setText(meta);
        heldMeta.setToolTipText(meta);
        inlineCardLayout.show(inlineActionBar, CARD_HELD);
        reqLabel.setText(" Request [Chờ duyệt #" + pr.id() + " — Chỉnh sửa trực tiếp rồi bấm Gửi Server]  " + pr.url());
        respLabel.setText(" Response [Chưa gửi lên Server — đang giữ tại Proxy]");
        req.show(pr.service(), pr.originalRequest());
        resp.show(pr.service(), heldPreviewBytes(pr));
        syncQuickButtons();
    }

    /**
     * Builds an ISO-8859-1-safe synthetic HTTP preview for held-at-proxy requests so both Burp's
     * {@code HttpResponseEditor} and headless test viewers render cleanly without UTF-8 mojibake.
     */
    static byte[] heldPreviewBytes(PendingRequest pr) {
        String host = pr.service() != null ? pr.service().hostLabel() : "";
        String branch = asciiSafe(pr.branchName());
        String reason = asciiSafe(pr.reason());
        String preview = "HTTP/1.1 000 HELD AT PROXY\r\n"
                + "X-JCrawl-Status: HELD_PENDING (CHUA GUI LEN server)\r\n"
                + "X-JCrawl-Branch: " + branch + "\r\n"
                + (host.isEmpty() ? "" : "X-JCrawl-Host: " + asciiSafe(host) + "\r\n")
                + "X-JCrawl-Rule: " + reason + "\r\n"
                + "\r\n"
                + "[REQUEST DANG DUOC GIU TAI PROXY - CHUA GUI LEN server]\n"
                + "Nhanh dich : " + branch + "\n"
                + (host.isEmpty() ? "" : "Host       : " + asciiSafe(host) + "\n")
                + "Ly do chan : " + reason + "\n"
                + "Thoi diem  : " + pr.timeHms() + "\n\n"
                + "Thao tac nhanh ngay phia tren:\n"
                + "  1. Sua truc tiep Raw HTTP ben trai (neu can)\n"
                + "  2. Bam 'Gui Server' de thuc thi len Server that\n"
                + "  3. Bam 'Luu nhap' de giu lai tren Cay (khong gui)\n"
                + "  4. Bam 'Huy (Drop)' de huy bo hoan toan\n";
        return preview.getBytes(StandardCharsets.ISO_8859_1);
    }

    static String asciiSafe(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String norm = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(norm.length());
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            if (c == 'đ') {
                sb.append('d');
            } else if (c == 'Đ') {
                sb.append('D');
            } else if (c == '\u2014' || c == '\u2013') {
                sb.append('-');
            } else if (c == '\u00B7') {
                sb.append('|');
            } else if (c <= 0x7E) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    void syncHeaderBreadcrumb() {
        String path = currentBreadcrumbPath();
        headerBreadcrumb.setPath(path);
        int held = engine.proxyQueue().pendingCount();
        headerMeta.setText(engine.branches().size() + " nhánh, " + engine.groups().size() + " API"
                + (held > 0 ? ", " + held + " chờ duyệt" : "")
                + "  ·  Nhánh đang crawl: " + engine.activeBranch().name());
        if (window != null) {
            window.setTitle(detachedTitle());
        }
    }

    String detachedTitle() {
        return Text.EXT_NAME + " - " + currentBreadcrumbPath();
    }

    String currentBreadcrumbPath() {
        Node n = tree.selectedNode();
        if (n != null) {
            if ((n.kind == Node.Kind.FOLDER || n.kind == Node.Kind.APIS) && n.payload instanceof String p) {
                return p;
            }
            PendingRequest pr = BranchTree.heldOf(n);
            if (pr != null) {
                return pr.branchName() + " > [Chờ duyệt] " + pr.method() + " " + Text.shortPath(pr.path(), 32);
            }
            ApiGroup g = BranchTree.groupOf(n);
            if (g != null) {
                return g.branch() + " > " + Text.apiLabel(g.gid()) + " " + g.method() + " " + Text.shortPath(g.path(), 32);
            }
        }
        return engine.activeBranch().name();
    }

    private void showEntry(ApiGroup g, RoleResult r) {
        shownHeld = null;
        shownGroup = g;
        shown = r;
        inlineCardLayout.show(inlineActionBar, CARD_API);
        Object st = r.status() != null ? r.status() : r.state();
        reqLabel.setText(" Request [" + Text.groupId(g.gid()) + " | " + r.roleName() + " — sửa trực tiếp tại đây]  " + g.endpoint());
        respLabel.setText(" Response [" + r.roleName() + " | Status: " + st + " | Length: " + (r.length() != null ? r.length() : "-") + "]");
        req.show(g.service(), engine.shownRequestOf(g, r));
        resp.show(g.service(), engine.responseOf(g, r));
        shownState = r.state() + "/" + r.responseBlob();
        syncQuickButtons();
    }

    /** A response that arrives for the entry below the tree is shown without re-selecting it. */
    void dirty(Set<ApiGroup> gs) {
        if (shownHeld == null && shownGroup != null && gs.contains(shownGroup) && shown != null
                && !(shown.state() + "/" + shown.responseBlob()).equals(shownState)) {
            showEntry(shownGroup, shown);
        }
    }

    /**
     * Real-time push callback fired directly on the EDT whenever {@link ProxyInterceptQueue} changes
     * (new request intercepted, resolved externally, or released). Rebuilds the visible Tree immediately
     * without waiting for timer polling or tab switching.
     */
    void onProxyQueueChanged(boolean activeTab) {
        List<PendingRequest> pending = engine.proxyQueue().pending();
        int curCount = pending.size();
        boolean grew = curCount > lastKnownHeldCount;
        lastKnownHeldCount = curCount;

        // If currently displayed held request was resolved externally (e.g. from ProxyInterceptDialog), clear it live
        if (shownHeld != null) {
            long id = shownHeld.id();
            boolean stillPending = pending.stream().anyMatch(p -> p.id() == id);
            if (!stillPending) {
                shownHeld = null;
                inlineCardLayout.show(inlineActionBar, CARD_API);
                if (shownGroup != null && shown != null) {
                    showEntry(shownGroup, shown);
                } else {
                    reqLabel.setText(" Request — chọn 1 API / vai trò hoặc mục [Chờ duyệt] trong cây");
                    respLabel.setText(" Response");
                    syncQuickButtons();
                }
            }
        }

        stale = true;
        if (tree.tree.isShowing() || activeTab || window != null) {
            stale = false;
            tree.rebuild(engine.config().showGhosts);
            if (grew && shownHeld == null && !pending.isEmpty()) {
                tree.revealHeldNode(pending.get(pending.size() - 1).id());
            }
            syncHeaderBreadcrumb();
            tree.tree.revalidate();
            tree.tree.repaint();
        }
    }

    void refreshIfVisible() {
        if (stale && tree.tree.isShowing()) {
            stale = false;
            tree.rebuild(engine.config().showGhosts);
            syncHeaderBreadcrumb();
        }
    }

    void toggleWindow() {
        if (window != null) {
            closeWindow();
            return;
        }
        window = new JFrame(detachedTitle());
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                closeWindow();
            }
        });
        panel.remove(content);
        panel.add(placeholder, BorderLayout.CENTER);
        panel.revalidate();
        panel.repaint();
        JCheckBox onTop = new JCheckBox("Luôn nằm trên các cửa sổ khác");
        onTop.addActionListener(Ui.safe(e -> window.setAlwaysOnTop(onTop.isSelected())));
        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.add(Ui.flow(FlowLayout.LEFT, onTop), BorderLayout.NORTH);
        rootPanel.add(content, BorderLayout.CENTER);
        window.getContentPane().add(rootPanel);
        Theme.track(rootPanel);
        try {
            ui.host.applyTheme(rootPanel);
        } catch (RuntimeException ignored) {
            // outside Burp
        }
        window.setSize(new Dimension(980, 900));
        window.setLocationRelativeTo(panel);
        window.setVisible(true);
        windowBtn.setText("Gắn lại vào tab");
    }

    void closeWindow() {
        if (window == null) {
            return;
        }
        JFrame w = window;
        window = null;
        w.dispose();
        panel.remove(placeholder);
        panel.add(content, BorderLayout.CENTER);
        panel.revalidate();
        panel.repaint();
        windowBtn.setText("Mở cửa sổ riêng");
    }

    void dispose() {
        closeWindow();
    }

    Breadcrumb headerBreadcrumb() {
        return headerBreadcrumb;
    }

    String headerMetaText() {
        return headerMeta.getText();
    }

    PendingRequest shownHeld() {
        return shownHeld;
    }

    JFrame detachedWindow() {
        return window;
    }

    // =================================================================== V_7.0 hideable layout
    boolean detailVisible() {
        return bottomDetail != null && bottomDetail.isVisible();
    }

    /** Hide / show the Request / Response pane under the tree (Ctrl+Alt+2 while the Cây tab is open). */
    void setDetailVisible(boolean on) {
        if (bottomDetail == null || bottomDetail.isVisible() == on) {
            return;
        }
        if (!on && treeSplit.getDividerLocation() > 0) {
            lastTreeDivider = treeSplit.getDividerLocation();
        }
        bottomDetail.setVisible(on);
        if (on) {
            if (lastTreeDivider > 0) {
                treeSplit.setDividerLocation(lastTreeDivider);
            } else {
                treeSplit.resetToPreferredSizes();
            }
        }
        ui.uiPrefs.set(KEY_TREE_DETAIL, on ? "1" : "0");
        panel.revalidate();
        panel.repaint();
    }

    void toggleDetail() {
        setDetailVisible(!detailVisible());
    }

    boolean toolbarVisible() {
        return topHeaderStack.isVisible();
    }

    void setToolbarVisible(boolean on) {
        topHeaderStack.setVisible(on);
        panel.revalidate();
    }

    boolean maximized() {
        return !detailVisible() && !toolbarVisible() && !ui.headerVisible();
    }

    /** Ctrl+Alt+0 on the Cây tab: only the tree (with its column header) stays on screen. */
    void toggleMaximized() {
        boolean max = !maximized();
        setDetailVisible(!max);
        setToolbarVisible(!max);
        ui.setHeaderVisible(!max);
    }

    static final String KEY_TREE_DETAIL = "tmoc.ui.tree.detail.v7";
}
