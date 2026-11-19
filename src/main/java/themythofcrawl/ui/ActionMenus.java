package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.Config;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Blob;
import themythofcrawl.model.RoleResult;
import themythofcrawl.ui.theme.Theme;

/**
 * Streamlined Action API right-click &amp; toolbar menu v2.1:
 * replaces the legacy 18-item cryptic PP1..PP4 menu with 5 crystal-clear actions
 * backed by the visual 4-tab {@link ActionWorkbenchDialog}.
 */
public final class ActionMenus {
    private ActionMenus() {
    }

    /**
     * Concise right-click submenu for any API (Tree, API Table, Role Table, History).
     */
    public static JMenu buildForApi(Component parent, Engine engine, ApiGroup g,
                                    Collection<RoleResult> explicitTargets, Runnable onAfterAction) {
        Theme th = Theme.current();
        String methodTag = g != null ? g.method() : "API";
        JMenu root = new JMenu("Xử lý Action & Đổi ID [" + methodTag + "]");
        root.setIcon(Icons.workbench(th.accent.text(), 14));
        if (g == null) {
            JMenuItem none = new JMenuItem("(Chưa chọn API)");
            none.setEnabled(false);
            root.add(none);
            return root;
        }
        populateApiActionItems(root, parent, engine, g, explicitTargets, onAfterAction);
        return root;
    }

    /**
     * Populates 5 clean, jargon-free Action items into a JMenu or JPopupMenu.
     */
    public static void populateApiActionItems(Component menuContainer, Component parent, Engine engine, ApiGroup g,
                                              Collection<RoleResult> explicitTargets, Runnable onAfterAction) {
        Theme th = Theme.current();
        Collection<RoleResult> targets = (explicitTargets == null || explicitTargets.isEmpty())
                ? engine.defaultTargets(g, true) : explicitTargets;
        byte[] origBytes = Blob.bytesOf(g.origRequest());
        List<String> detectedIds = engine.actionEngine().detectIds(g.path(), origBytes);
        String currentId = detectedIds.isEmpty() ? null : detectedIds.get(0);
        String resource = ActionEngine.extractResourceKey(g.path());
        List<String> liveIds = engine.actionEngine().getAvailableLiveIds(g.path(), currentId);
        boolean hasSnap = g.hasFirstOkSnapshot();
        boolean epHeld = engine.actionEngine().isEndpointProxyHold(g.method(), g.path());

        // Compact status header
        String idSummary = currentId != null
                ? "ID [" + currentId + "]" + (engine.actionEngine().isConsumed(currentId) ? " · Đã tiêu thụ" : " · Còn sống")
                : "Không thấy ID tĩnh";
        JMenuItem info = new JMenuItem(Text.apiLabel(g.gid()) + " · " + resource + " · " + idSummary);
        info.setEnabled(false);
        addItem(menuContainer, info);
        addSep(menuContainer);

        // 1. Open visual 4-tab Action Workbench
        JMenuItem openWb = new JMenuItem("Mở Action Workbench (Trực quan 4 tab)...", Icons.workbench(th.accent.text(), 14));
        openWb.addActionListener(e -> ActionWorkbenchDialog.show(parent, engine, g, targets,
                ActionWorkbenchDialog.TAB_STRATEGY, onAfterAction));
        addItem(menuContainer, openWb);

        // 2. Quick send with next live ID
        JMenuItem quickSwap = new JMenuItem(
                !liveIds.isEmpty()
                        ? "Gửi nhanh với ID sống kế tiếp (-> " + liveIds.get(0) + ")"
                        : "Gửi nhanh với ID sống kế tiếp (Kho đang trống)",
                Icons.strategySwap(th.success.text(), 14));
        quickSwap.setEnabled(currentId != null && !liveIds.isEmpty());
        quickSwap.addActionListener(e -> {
            engine.sendWithNextLiveId(g, targets);
            if (onAfterAction != null) onAfterAction.run();
        });
        addItem(menuContainer, quickSwap);

        // 3. Pick another ID from Live Pool or manual swap
        JMenu pickMenu = new JMenu("Chọn ID khác từ Kho (" + liveIds.size() + " ID sống) & Gửi");
        pickMenu.setIcon(Icons.idPool(th.info.text(), 14));
        if (!liveIds.isEmpty()) {
            int limit = Math.min(liveIds.size(), 15);
            for (int i = 0; i < limit; i++) {
                String cand = liveIds.get(i);
                JMenuItem idItem = new JMenuItem(currentId != null
                        ? "Đổi [" + currentId + "] -> [" + cand + "] & Gửi"
                        : "Dùng ID [" + cand + "] & Gửi");
                idItem.addActionListener(e -> {
                    engine.replaceIdAndSend(g, currentId, cand, true, targets);
                    if (onAfterAction != null) onAfterAction.run();
                });
                pickMenu.add(idItem);
            }
            pickMenu.addSeparator();
        }
        JMenuItem manualItem = new JMenuItem("Nhập ID thủ công & Gửi...", Icons.edit(th.fg));
        manualItem.addActionListener(e -> showManualIdReplaceDialog(parent, engine, g, detectedIds, liveIds, targets, onAfterAction));
        pickMenu.add(manualItem);
        JMenuItem openPoolItem = new JMenuItem("Mở Kho ID Sống & Nạp hàng loạt...", Icons.idPool(th.success.text(), 14));
        openPoolItem.addActionListener(e -> ActionWorkbenchDialog.show(parent, engine, g, targets,
                ActionWorkbenchDialog.TAB_POOL, onAfterAction));
        pickMenu.add(openPoolItem);
        addItem(menuContainer, pickMenu);

        // 4. Producer Chain / Proxy Hold toggle
        JMenuItem chainItem = new JMenuItem("Chuỗi Tạo Mới -> Hành Động (Producer)...", Icons.strategyChain(th.accent.text(), 14));
        chainItem.addActionListener(e -> ActionWorkbenchDialog.show(parent, engine, g, targets,
                ActionWorkbenchDialog.TAB_CHAIN, onAfterAction));
        addItem(menuContainer, chainItem);

        // 5. Lock 200 OK & Replay (3 choices) + Proxy Hold quick toggle
        JMenuItem lockItem = new JMenuItem(
                hasSnap ? "Khóa 200 OK & Chọn cách gửi lại (Snapshot " + g.firstOkStatus() + ")..."
                        : "Khóa 200 OK & Chọn cách gửi lại...",
                Icons.strategySnapshot(th.info.text(), 14));
        lockItem.addActionListener(e -> ActionWorkbenchDialog.show(parent, engine, g, targets,
                ActionWorkbenchDialog.TAB_LOCK200, onAfterAction));
        addItem(menuContainer, lockItem);

        addSep(menuContainer);
        boolean epWait = engine.actionEngine().isEndpointInterceptWait(g.method(), g.path())
                && engine.actionEngine().isInterceptWaitEnabled();
        JMenu proxyMenu = new JMenu(epWait ? "Chặn Proxy cho API này [ĐANG CHẶN]" : "Chặn Proxy cho API này");
        proxyMenu.setIcon(Icons.strategyShield(epWait ? th.danger.text() : th.warning.text(), 14));

        JCheckBoxMenuItem waitEpItem = new JCheckBoxMenuItem("Chặn chờ duyệt (Đợi tôi bấm Gửi) cho API này", epWait);
        waitEpItem.setIcon(Icons.strategyShield(th.danger.text(), 14));
        waitEpItem.addActionListener(e -> {
            boolean next = waitEpItem.isSelected();
            engine.toggleEndpointInterceptWait(g, next);
            if (next) {
                MainPanel.openInterceptQueue(engine);
            }
            if (onAfterAction != null) onAfterAction.run();
        });
        proxyMenu.add(waitEpItem);

        JMenuItem openQueueItem = new JMenuItem("Mở Hàng chờ Chặn Proxy trực tiếp...", Icons.strategyShield(th.accent.text(), 14));
        openQueueItem.addActionListener(e -> MainPanel.openInterceptQueue(engine));
        proxyMenu.add(openQueueItem);
        proxyMenu.addSeparator();

        JCheckBoxMenuItem holdEpItem = new JCheckBoxMenuItem("Giữ ở Proxy cho endpoint này (200 ảo / Không gửi Server)", epHeld);
        holdEpItem.setIcon(Icons.strategyShield(th.warning.text(), 14));
        holdEpItem.addActionListener(e -> {
            engine.toggleEndpointProxyHold(g, holdEpItem.isSelected());
            if (onAfterAction != null) onAfterAction.run();
        });
        proxyMenu.add(holdEpItem);
        addItem(menuContainer, proxyMenu);
    }

    /**
     * Streamlined toolbar dropdown for Action API controls.
     */
    public static void populateToolbarDropdown(JPopupMenu menu, Component parent, Engine engine,
                                               Supplier<ApiGroup> activeGroupSupplier, Runnable onAfterAction) {
        Theme th = Theme.current();
        Config cfg = engine.config();
        ApiGroup active = activeGroupSupplier != null ? activeGroupSupplier.get() : null;

        JMenuItem openWorkbench = new JMenuItem("Mở Action Workbench (4 Tab trực quan)...", Icons.workbench(th.accent.text(), 14));
        openWorkbench.addActionListener(e -> ActionWorkbenchDialog.show(parent, engine, active, null,
                ActionWorkbenchDialog.TAB_STRATEGY, onAfterAction));
        menu.add(openWorkbench);

        Map<String, List<String>> poolMap = engine.actionEngine().getAllResourceLiveIds();
        int totalIds = poolMap.values().stream().mapToInt(List::size).sum();
        JMenuItem openPool = new JMenuItem("Quản lý Kho ID Sống (" + totalIds + " ID · " + poolMap.size() + " tài nguyên)...",
                Icons.idPool(th.success.text(), 14));
        openPool.addActionListener(e -> ActionWorkbenchDialog.show(parent, engine, active, null,
                ActionWorkbenchDialog.TAB_POOL, onAfterAction));
        menu.add(openPool);

        JMenuItem openChain = new JMenuItem("Thiết lập Chuỗi Tạo Mới -> Hành Động...", Icons.strategyChain(th.accent.text(), 14));
        openChain.addActionListener(e -> ActionWorkbenchDialog.show(parent, engine, active, null,
                ActionWorkbenchDialog.TAB_CHAIN, onAfterAction));
        menu.add(openChain);

        menu.addSeparator();
        JCheckBoxMenuItem holdAll = new JCheckBoxMenuItem("Chặn tại Proxy cho mọi Action API khi Crawl", cfg.holdActionAtProxy);
        holdAll.setIcon(Icons.strategyShield(th.warning.text(), 14));
        holdAll.addActionListener(e -> {
            Config next = engine.config().copy();
            next.holdActionAtProxy = holdAll.isSelected();
            engine.applyConfig(next);
            if (onAfterAction != null) onAfterAction.run();
        });
        menu.add(holdAll);

        if (active != null) {
            menu.addSeparator();
            JMenu activeSub = buildForApi(parent, engine, active, null, onAfterAction);
            activeSub.setText("Thao tác ngay: " + Text.apiLabel(active.gid()) + " [" + active.method() + " "
                    + Text.shortPath(active.path(), 24) + "]");
            menu.add(activeSub);
        }
    }

    /** Quick modal for typing a custom ID to replace &amp; send immediately. */
    public static void showManualIdReplaceDialog(Component parent, Engine engine, ApiGroup g,
                                                 List<String> detectedIds, List<String> liveIds,
                                                 Collection<RoleResult> targets, Runnable onAfterAction) {
        Window win = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dlg = new JDialog(win, "Đổi ID & Gửi ngay — " + Text.apiLabel(g.gid()) + " " + g.method() + " " + g.path(),
                JDialog.ModalityType.APPLICATION_MODAL);
        Theme th = Theme.current();

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBackground(th.surface);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0;
        form.add(new JLabel("ID cũ cần thay trong Request:"), gc);
        JComboBox<String> oldIdCombo = new JComboBox<>(detectedIds.toArray(new String[0]));
        oldIdCombo.setEditable(true);
        if (detectedIds.isEmpty()) {
            oldIdCombo.setSelectedItem("");
        }
        gc.gridx = 1; gc.weightx = 1.0;
        form.add(oldIdCombo, gc);

        gc.gridx = 0; gc.gridy = 1; gc.weightx = 0;
        form.add(new JLabel("ID mới (hoặc chọn từ Kho ID sống):"), gc);
        JComboBox<String> newIdCombo = new JComboBox<>(liveIds.toArray(new String[0]));
        newIdCombo.setEditable(true);
        if (!liveIds.isEmpty()) {
            newIdCombo.setSelectedIndex(0);
        }
        gc.gridx = 1; gc.weightx = 1.0;
        form.add(newIdCombo, gc);

        JCheckBox updateGroupChk = new JCheckBox("Cập nhật ID mới làm trạng thái chính của API này", true);
        updateGroupChk.setOpaque(false);
        gc.gridx = 0; gc.gridy = 2; gc.gridwidth = 2;
        form.add(updateGroupChk, gc);

        JCheckBox alsoAddToPoolChk = new JCheckBox("Lưu ID mới vào Kho ID sống của '"
                + ActionEngine.extractResourceKey(g.path()) + "'", true);
        alsoAddToPoolChk.setOpaque(false);
        gc.gridy = 3;
        form.add(alsoAddToPoolChk, gc);

        root.add(form, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        JButton cancelBtn = Ui.button("Hủy", dlg::dispose);
        JButton sendBtn = Ui.accent("Thay ID & Gửi ngay", t -> Icons.send(t.accent.onSolid()), t -> t.accent, () -> {
            Object oldObj = oldIdCombo.getEditor().getItem();
            Object newObj = newIdCombo.getEditor().getItem();
            String oldId = oldObj != null ? oldObj.toString().trim() : "";
            String newId = newObj != null ? newObj.toString().trim() : "";
            if (newId.isEmpty()) {
                JOptionPane.showMessageDialog(dlg, "Vui lòng nhập hoặc chọn ID mới.", "Thiếu ID mới", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (alsoAddToPoolChk.isSelected()) {
                engine.actionEngine().addLiveId(ActionEngine.extractResourceKey(g.path()), newId);
            }
            engine.replaceIdAndSend(g, oldId, newId, updateGroupChk.isSelected(), targets);
            dlg.dispose();
            if (onAfterAction != null) onAfterAction.run();
        });
        actions.add(cancelBtn);
        actions.add(sendBtn);
        root.add(actions, BorderLayout.SOUTH);

        dlg.setContentPane(root);
        Theme.track(dlg);
        dlg.pack();
        dlg.setMinimumSize(new Dimension(520, 210));
        dlg.setLocationRelativeTo(win);
        dlg.setVisible(true);
    }

    private static void addItem(Component container, JMenuItem item) {
        if (container instanceof JMenu m) {
            m.add(item);
        } else if (container instanceof JPopupMenu p) {
            p.add(item);
        }
    }

    private static void addSep(Component container) {
        if (container instanceof JMenu m) {
            m.addSeparator();
        } else if (container instanceof JPopupMenu p) {
            p.addSeparator();
        }
    }
}
