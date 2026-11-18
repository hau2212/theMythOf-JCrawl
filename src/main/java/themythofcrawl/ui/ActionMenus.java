package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
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
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
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
 * Menu chuột phải và hộp thoại điều khiển 4 Phương pháp bảo vệ & tái tạo Action API
 * (POST / PUT / PATCH / DELETE) cho từng API bất kỳ lúc nào sau khi bắt, cũng như cấu hình toàn cục.
 */
public final class ActionMenus {
    private ActionMenus() {
    }

    /**
     * Tạo JMenu "Xử lý Action & Đổi ID (4 Phương pháp)" cho một API cụ thể (dùng trong menu chuột phải của Cây, Bảng API, Bảng Role, Lịch sử).
     */
    public static JMenu buildForApi(Component parent, Engine engine, ApiGroup g,
                                    Collection<RoleResult> explicitTargets, Runnable onAfterAction) {
        Theme th = Theme.current();
        String methodTag = g != null ? g.method() : "API";
        JMenu root = new JMenu("Xử lý Action & Đổi ID [" + methodTag + "]");
        root.setIcon(Icons.send(th.accent.text()));
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
     * Đổ danh sách thao tác Action & Đổi ID của một API vào JMenu hoặc JPopupMenu.
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

        // Thông tin tóm tắt ID & Trạng thái hiện tại của API
        String idSummary = currentId != null
                ? "ID đang dùng: [" + currentId + "]" + (engine.actionEngine().isConsumed(currentId) ? " (Đã xóa/tiêu thụ)" : " (Đang sống)")
                : "Không phát hiện ID tĩnh trên Path/Body";
        JMenuItem infoItem = new JMenuItem(Text.apiLabel(g.gid()) + " · Tài nguyên: " + resource + " · " + idSummary);
        infoItem.setEnabled(false);
        addItem(menuContainer, infoItem);
        if (g.activeIdNote() != null && !g.activeIdNote().isBlank()) {
            JMenuItem noteItem = new JMenuItem("Trạng thái Action: " + g.activeIdNote());
            noteItem.setEnabled(false);
            addItem(menuContainer, noteItem);
        }
        addSep(menuContainer);

        // =========================================================================
        // PHƯƠNG PHÁP 3: GIỎ ID SỐNG & ĐỔI ID BẤT KỲ LÚC NÀO
        // =========================================================================
        JMenuItem autoNextId = new JMenuItem("PP3: Tự đổi sang ID sống tiếp theo & Gửi ngay"
                + (!liveIds.isEmpty() ? " (-> [" + liveIds.get(0) + "])" : " (Giỏ trống)"), Icons.send(th.success.text()));
        autoNextId.setEnabled(currentId != null && !liveIds.isEmpty());
        autoNextId.addActionListener(e -> {
            engine.sendWithNextLiveId(g, targets);
            if (onAfterAction != null) onAfterAction.run();
        });
        addItem(menuContainer, autoNextId);

        JMenu pickLiveMenu = new JMenu("PP3: Chọn ID sống khác từ Giỏ ID (" + liveIds.size() + " ID khả dụng)");
        pickLiveMenu.setIcon(Icons.branch(th.accent.text()));
        if (liveIds.isEmpty()) {
            JMenuItem empty = new JMenuItem("(Chưa có ID sống khả dụng cho '" + resource + "')");
            empty.setEnabled(false);
            pickLiveMenu.add(empty);
        } else {
            int limit = Math.min(liveIds.size(), 25);
            for (int i = 0; i < limit; i++) {
                String candidateId = liveIds.get(i);
                String label = currentId != null
                        ? "Đổi [" + currentId + "] -> [" + candidateId + "] & Gửi lên Server"
                        : "Áp dụng ID [" + candidateId + "] & Gửi lên Server";
                JMenuItem idItem = new JMenuItem(label);
                idItem.addActionListener(e -> {
                    engine.replaceIdAndSend(g, currentId, candidateId, true, targets);
                    if (onAfterAction != null) onAfterAction.run();
                });
                pickLiveMenu.add(idItem);
            }
        }
        pickLiveMenu.addSeparator();
        JMenuItem addPoolIds = new JMenuItem("Nạp thêm danh sách ID sống vào Giỏ ID '" + resource + "'...");
        addPoolIds.addActionListener(e -> showAddLiveIdsDialog(parent, engine, resource, onAfterAction));
        pickLiveMenu.add(addPoolIds);
        addItem(menuContainer, pickLiveMenu);

        JMenuItem manualReplace = new JMenuItem("PP3: Nhập ID mới / Dải ID tùy chọn để thay thế & Gửi...", Icons.gear(th.fg));
        manualReplace.addActionListener(e -> showManualIdReplaceDialog(parent, engine, g, detectedIds, liveIds, targets, onAfterAction));
        addItem(menuContainer, manualReplace);
        addSep(menuContainer);

        // =========================================================================
        // PHƯƠNG PHÁP 4: GỌI API TẠO MỚI (PRODUCER) & LÀM MỚI UNIQUE
        // =========================================================================
        Integer linkedProdGid = g.producerGid() != null ? g.producerGid() : engine.actionEngine().findProducerGid(g.path());
        ApiGroup linkedProd = linkedProdGid != null ? engine.group(linkedProdGid) : null;
        String prodDesc = linkedProd != null
                ? " (" + Text.apiLabel(linkedProd.gid()) + " " + linkedProd.method() + " " + Text.shortPath(linkedProd.path(), 24) + ")"
                : " (Tự tìm POST cùng tài nguyên)";
        JMenuItem callProducer = new JMenuItem("PP4: Gọi API Tạo mới (Producer)" + prodDesc + " -> Lấy ID mới -> Gửi",
                Icons.send(th.accent.text()));
        callProducer.addActionListener(e -> {
            engine.sendWithProducer(g, linkedProd != null ? linkedProd.gid() : null, targets);
            if (onAfterAction != null) onAfterAction.run();
        });
        addItem(menuContainer, callProducer);

        JMenu selectProducerMenu = new JMenu("PP4: Chọn / Gán API Producer (POST tạo mới) cho API này");
        List<ApiGroup> postCandidates = new ArrayList<>();
        for (ApiGroup cand : engine.groups()) {
            if (cand.gid() != g.gid() && ("POST".equalsIgnoreCase(cand.method()) || "PUT".equalsIgnoreCase(cand.method()))) {
                postCandidates.add(cand);
            }
        }
        if (postCandidates.isEmpty()) {
            JMenuItem noPost = new JMenuItem("(Chưa có API POST/PUT nào trong danh sách)");
            noPost.setEnabled(false);
            selectProducerMenu.add(noPost);
        } else {
            int maxP = Math.min(postCandidates.size(), 25);
            for (int i = 0; i < maxP; i++) {
                ApiGroup cand = postCandidates.get(i);
                boolean isCurrent = linkedProd != null && linkedProd.gid() == cand.gid();
                JRadioButtonMenuItem candItem = new JRadioButtonMenuItem(
                        Text.apiLabel(cand.gid()) + " [" + cand.method() + " " + Text.shortPath(cand.path(), 34) + "] -> Gán & Chạy ngay",
                        isCurrent);
                candItem.addActionListener(e -> {
                    g.setProducerGid(cand.gid());
                    engine.actionEngine().registerProducer(resource, cand.gid());
                    engine.sendWithProducer(g, cand.gid(), targets);
                    if (onAfterAction != null) onAfterAction.run();
                });
                selectProducerMenu.add(candItem);
            }
        }
        addItem(menuContainer, selectProducerMenu);

        JMenuItem refreshUnique = new JMenuItem("PP4: Làm mới các trường Unique (email / code / nonce...) & Gửi lại");
        refreshUnique.addActionListener(e -> {
            engine.sendWithUniqueRefresh(g, targets);
            if (onAfterAction != null) onAfterAction.run();
        });
        addItem(menuContainer, refreshUnique);
        addSep(menuContainer);

        // =========================================================================
        // PHƯƠNG PHÁP 2: KHÓA BẢO VỆ 200 OK & 3 CÁCH GỬI LẠI
        // =========================================================================
        boolean hasSnap = g.hasFirstOkSnapshot();
        String snapTag = hasSnap ? " (Snapshot HTTP " + g.firstOkStatus() + " · " + g.firstOkLength() + "B)" : " (Chưa có Snapshot 2xx)";

        JMenuItem opt2A = new JMenuItem("PP2 - Cách 1A: Kế thừa kết quả 200 OK ban đầu (Không gửi lại ID đã xóa)" + snapTag);
        opt2A.setEnabled(hasSnap);
        opt2A.addActionListener(e -> {
            engine.inheritInitial2xx(g, targets);
            if (onAfterAction != null) onAfterAction.run();
        });
        addItem(menuContainer, opt2A);

        JMenuItem opt2B = new JMenuItem("PP2 - Cách 1B: Đẩy thật lên Server kèm Tự đổi ID sống / Gọi Producer (Đạt 200 OK mới)");
        opt2B.addActionListener(e -> {
            if (!engine.sendWithNextLiveId(g, targets)) {
                engine.sendWithProducer(g, null, targets);
            }
            if (onAfterAction != null) onAfterAction.run();
        });
        addItem(menuContainer, opt2B);

        JMenuItem opt2C = new JMenuItem("PP2 - Cách 2: Cưỡng bức gửi lại nguyên bản lên Server (Chấp nhận rủi ro 4xx)");
        opt2C.addActionListener(e -> {
            engine.forceRawReplay(g, targets);
            if (onAfterAction != null) onAfterAction.run();
        });
        addItem(menuContainer, opt2C);

        JMenuItem restore200 = new JMenuItem("PP2 - Khôi phục lại Response 200 OK ban đầu cho API gốc" + snapTag);
        restore200.setEnabled(hasSnap);
        restore200.addActionListener(e -> {
            engine.restoreInitial2xx(g);
            if (onAfterAction != null) onAfterAction.run();
        });
        addItem(menuContainer, restore200);
        addSep(menuContainer);

        // =========================================================================
        // PHƯƠNG PHÁP 1: CHẶN TẠI PROXY (GIỮ TRẠNG THÁI SERVER) & CHẾ ĐỘ RIÊNG CHO API NÀY
        // =========================================================================
        boolean epHeld = engine.actionEngine().isEndpointProxyHold(g.method(), g.path());
        JCheckBoxMenuItem holdEpItem = new JCheckBoxMenuItem(
                "PP1: Chặn trình duyệt thực thi endpoint này tại Proxy (Giữ nguyên ID trên Server)", epHeld);
        holdEpItem.addActionListener(e -> {
            engine.toggleEndpointProxyHold(g, holdEpItem.isSelected());
            if (onAfterAction != null) onAfterAction.run();
        });
        addItem(menuContainer, holdEpItem);

        JMenu modeMenu = new JMenu("Chế độ tự động riêng cho API #" + g.gid() + " khi bấm 'Gửi'");
        ButtonGroup bg = new ButtonGroup();
        ActionEngine.ActionMode currentOverride = g.actionModeOverride();
        JRadioButtonMenuItem defItem = new JRadioButtonMenuItem(
                "Theo mặc định toàn cục (" + engine.config().actionMode.label + ")", currentOverride == null);
        defItem.addActionListener(e -> {
            engine.setApiActionMode(g, null);
            if (onAfterAction != null) onAfterAction.run();
        });
        bg.add(defItem);
        modeMenu.add(defItem);
        modeMenu.addSeparator();
        for (ActionEngine.ActionMode m : ActionEngine.ActionMode.values()) {
            JRadioButtonMenuItem mi = new JRadioButtonMenuItem(m.label, currentOverride == m);
            mi.setToolTipText(m.description);
            mi.addActionListener(e -> {
                engine.setApiActionMode(g, m);
                if (onAfterAction != null) onAfterAction.run();
            });
            bg.add(mi);
            modeMenu.add(mi);
        }
        addItem(menuContainer, modeMenu);
    }

    /**
     * Đổ menu cấu hình & thao tác nhanh Action / ID trên thanh công cụ (MainPanel / ApiTab).
     */
    public static void populateToolbarDropdown(JPopupMenu menu, Component parent, Engine engine,
                                               Supplier<ApiGroup> activeGroupSupplier, Runnable onAfterAction) {
        Config cfg = engine.config();
        ApiGroup active = activeGroupSupplier != null ? activeGroupSupplier.get() : null;

        JMenuItem header = new JMenuItem("Cơ chế Bảo vệ Action API (POST / PUT / PATCH / DELETE)");
        header.setEnabled(false);
        menu.add(header);

        ButtonGroup modeGroup = new ButtonGroup();
        for (ActionEngine.ActionMode m : ActionEngine.ActionMode.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(m.label, cfg.actionMode == m);
            item.setToolTipText(m.description);
            item.addActionListener(e -> {
                Config next = engine.config().copy();
                next.actionMode = m;
                if (m == ActionEngine.ActionMode.HOLD_AT_PROXY) {
                    next.holdActionAtProxy = true;
                }
                engine.applyConfig(next);
                if (onAfterAction != null) onAfterAction.run();
            });
            modeGroup.add(item);
            menu.add(item);
        }

        menu.addSeparator();
        JCheckBoxMenuItem holdAll = new JCheckBoxMenuItem(
                "PP1: Bật Chặn tại Proxy cho mọi Action API khi Crawl (Giữ trạng thái Server)", cfg.holdActionAtProxy);
        holdAll.addActionListener(e -> {
            Config next = engine.config().copy();
            next.holdActionAtProxy = holdAll.isSelected();
            engine.applyConfig(next);
            if (onAfterAction != null) onAfterAction.run();
        });
        menu.add(holdAll);

        JMenu lockStrategyMenu = new JMenu("PP2: Tùy chọn mặc định khi Gửi lại ở chế độ Khóa 200 OK");
        ButtonGroup stratGroup = new ButtonGroup();
        for (ActionEngine.Lock2xxStrategy st : ActionEngine.Lock2xxStrategy.values()) {
            JRadioButtonMenuItem si = new JRadioButtonMenuItem(st.label, cfg.lock2xxStrategy == st);
            si.addActionListener(e -> {
                Config next = engine.config().copy();
                next.lock2xxStrategy = st;
                engine.applyConfig(next);
                if (onAfterAction != null) onAfterAction.run();
            });
            stratGroup.add(si);
            lockStrategyMenu.add(si);
        }
        menu.add(lockStrategyMenu);

        Map<String, List<String>> poolMap = engine.actionEngine().getAllResourceLiveIds();
        int totalIds = poolMap.values().stream().mapToInt(List::size).sum();
        JMenuItem poolOverview = new JMenuItem("PP3: Quản lý Giỏ ID sống (" + totalIds + " ID · "
                + poolMap.size() + " tài nguyên)...");
        poolOverview.addActionListener(e -> showLiveIdPoolManagerDialog(parent, engine, onAfterAction));
        menu.add(poolOverview);

        if (active != null) {
            menu.addSeparator();
            JMenu activeSub = buildForApi(parent, engine, active, null, onAfterAction);
            activeSub.setText("Thao tác ngay trên " + Text.apiLabel(active.gid()) + " [" + active.method() + " "
                    + Text.shortPath(active.path(), 26) + "]");
            menu.add(activeSub);
        }
    }

    /**
     * Hộp thoại nhập ID mới hoặc dải ID để thay thế & gửi cho một API bất kỳ.
     */
    public static void showManualIdReplaceDialog(Component parent, Engine engine, ApiGroup g,
                                                 List<String> detectedIds, List<String> liveIds,
                                                 Collection<RoleResult> targets, Runnable onAfterAction) {
        Window win = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dlg = new JDialog(win, "Đổi ID & Gửi lại — " + Text.apiLabel(g.gid()) + " " + g.method() + " " + g.path(),
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

        // Hàng 1: Chọn ID cũ cần thay
        gc.gridx = 0;
        gc.gridy = 0;
        gc.weightx = 0;
        form.add(new JLabel("ID cũ cần thay trong Request:"), gc);

        JComboBox<String> oldIdCombo = new JComboBox<>(detectedIds.toArray(new String[0]));
        oldIdCombo.setEditable(true);
        if (detectedIds.isEmpty()) {
            oldIdCombo.setSelectedItem("");
        }
        gc.gridx = 1;
        gc.weightx = 1.0;
        form.add(oldIdCombo, gc);

        // Hàng 2: Chọn nhanh từ Giỏ ID sống hoặc nhập ID mới
        gc.gridx = 0;
        gc.gridy = 1;
        gc.weightx = 0;
        form.add(new JLabel("ID mới (hoặc chọn từ Giỏ ID sống):"), gc);

        JComboBox<String> newIdCombo = new JComboBox<>(liveIds.toArray(new String[0]));
        newIdCombo.setEditable(true);
        if (!liveIds.isEmpty()) {
            newIdCombo.setSelectedIndex(0);
        }
        gc.gridx = 1;
        gc.weightx = 1.0;
        form.add(newIdCombo, gc);

        // Hàng 3: Tùy chọn cập nhật luôn vào API gốc
        JCheckBox updateGroupChk = new JCheckBox("Cập nhật ID mới làm trạng thái chính của API này (Path & Request gốc)", true);
        updateGroupChk.setOpaque(false);
        gc.gridx = 0;
        gc.gridy = 2;
        gc.gridwidth = 2;
        form.add(updateGroupChk, gc);

        JCheckBox alsoAddToPoolChk = new JCheckBox("Lưu ID mới vào Giỏ ID sống của tài nguyên '"
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
        dlg.pack();
        dlg.setMinimumSize(new Dimension(520, 210));
        dlg.setLocationRelativeTo(win);
        dlg.setVisible(true);
    }

    private static void showAddLiveIdsDialog(Component parent, Engine engine, String resource, Runnable onAfterAction) {
        String input = JOptionPane.showInputDialog(parent,
                "Nhập danh sách ID sống cho tài nguyên '" + resource + "' (phân cách bằng dấu phẩy hoặc khoảng trắng):",
                "");
        if (input != null && !input.isBlank()) {
            int added = 0;
            for (String token : input.split("[,;\\s]+")) {
                String clean = token.trim();
                if (!clean.isEmpty()) {
                    engine.actionEngine().addLiveId(resource, clean);
                    added++;
                }
            }
            engine.log("Đã nạp thêm " + added + " ID sống vào Giỏ ID '" + resource + "'.", null);
            if (onAfterAction != null) onAfterAction.run();
        }
    }

    private static void showLiveIdPoolManagerDialog(Component parent, Engine engine, Runnable onAfterAction) {
        Window win = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dlg = new JDialog(win, "Quản lý Giỏ ID sống (Phương pháp 3 - Live ID Pool)",
                JDialog.ModalityType.APPLICATION_MODAL);
        Theme th = Theme.current();

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBackground(th.surface);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        Map<String, List<String>> all = engine.actionEngine().getAllResourceLiveIds();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : all.entrySet()) {
            sb.append(e.getKey()).append(": ").append(String.join(", ", e.getValue())).append("\n");
        }
        if (sb.length() == 0) {
            sb.append("orders: 101, 102, 103\nusers: 15, 16, 17\n");
        }

        JLabel hint = new JLabel("Mỗi dòng theo định dạng: tên_tài_nguyên: id1, id2, id3 (Tự động thu thập từ các API GET/POST 2xx)");
        root.add(hint, BorderLayout.NORTH);

        JTextArea area = new JTextArea(sb.toString(), 10, 50);
        area.setFont(th.monoFont);
        root.add(new JScrollPane(area), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        bottom.setOpaque(false);
        JTextField addResField = new JTextField("orders", 10);
        JButton closeBtn = Ui.button("Đóng", dlg::dispose);
        JButton saveBtn = Ui.accent("Cập nhật Giỏ ID", t -> Icons.check(t.accent.onSolid()), t -> t.accent, () -> {
            String[] lines = area.getText().split("\r?\n");
            int count = 0;
            for (String line : lines) {
                int colon = line.indexOf(':');
                String res = colon > 0 ? line.substring(0, colon).trim() : addResField.getText().trim();
                String rest = colon > 0 ? line.substring(colon + 1) : line;
                for (String id : rest.split("[,;\\s]+")) {
                    if (!id.isBlank()) {
                        engine.actionEngine().addLiveId(res, id.trim());
                        count++;
                    }
                }
            }
            engine.log("Đã cập nhật " + count + " ID sống trong Giỏ ID.", null);
            dlg.dispose();
            if (onAfterAction != null) onAfterAction.run();
        });
        bottom.add(closeBtn);
        bottom.add(saveBtn);
        root.add(bottom, BorderLayout.SOUTH);

        dlg.setContentPane(root);
        dlg.pack();
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
