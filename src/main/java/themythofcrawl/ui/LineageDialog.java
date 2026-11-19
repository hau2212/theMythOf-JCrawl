package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ApiTagCatalog;
import themythofcrawl.place.Decisions;
import themythofcrawl.place.PlacementBook;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * Cửa sổ Quản lý Chuỗi Gốc & Trùng lặp (Duplicate Lineage & Placement Inspector):
 * - Khẳng định rõ vị trí #1 API GỐC ĐỊNH HÌNH (ghim trên cùng, hoặc Ghost nếu gốc đã xóa)
 * - Liệt kê toàn bộ các vị trí xuất hiện khác (#2 .. #N) cùng STT Copy/Excel, Đích lúc bắt, Trạng thái
 * - Đầy đủ thao tác CRUD: Thêm vị trí giữ chỗ, Sửa Đường dẫn, Sửa STT, Đặt làm GỐC #1, Ẩn/Hiện, Xóa, Mở
 * - Diễn giải bằng lời nói tự nhiên (SmartAdvice) + thanh nút Khuyến nghị hành động 1-click.
 */
final class LineageDialog {
    private LineageDialog() {
    }

    static void show(MainPanel ui, ApiGroup focusGroup) {
        if (ui == null || focusGroup == null) {
            return;
        }
        showByKey(ui, focusGroup.bookKey(), focusGroup);
    }

    static void showByKey(MainPanel ui, String bookKey, ApiGroup initialSelection) {
        if (ui == null || (bookKey == null && initialSelection == null)) {
            return;
        }
        Engine engine = ui.engine;
        String key = bookKey != null ? bookKey : initialSelection.bookKey();
        JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(ui.root()),
                "Chuỗi API Gốc & Trùng lặp (Lineage)", JDialog.ModalityType.APPLICATION_MODAL);

        LineageModel model = new LineageModel(engine, key);
        ThemedTable table = new ThemedTable(model);
        Ui.style(table);
        table.setRowHeight(Theme.current().scaled(26));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Ui.widths(table, 64, 138, 66, 220, 175, 92, 118, 76);

        DefaultTableCellRenderer cellRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                Theme th = Theme.current();
                LineageRow lr = model.rowAt(row);
                setFont(col == 1 || (lr != null && lr.isOrigin) ? th.smallBoldFont : th.smallFont);
                if (!sel && lr != null) {
                    if (lr.ghost) {
                        setBackground(th.surfaceAlt);
                        setForeground(th.muted);
                    } else if (lr.isOrigin) {
                        setBackground(th.orig.fill());
                        setForeground(col == 1 ? th.orig.text() : th.fg);
                    } else if (lr.group != null && lr.group.hidden()) {
                        setBackground(th.surfaceAlt);
                        setForeground(th.muted);
                    } else if (lr.group != null && lr.group.dupPending()) {
                        setBackground(th.dup.fill());
                        setForeground(col == 1 ? th.dup.text() : th.fg);
                    } else {
                        setBackground(th.zebra[row & 1]);
                        setForeground(th.fg);
                    }
                }
                return this;
            }
        };
        for (int c = 0; c < table.getColumnCount(); c++) {
            table.getColumnModel().getColumn(c).setCellRenderer(cellRenderer);
        }

        JLabel headerTitle = Ui.bold("");
        JLabel headerMeta = Ui.muted("");
        JPanel headerBar = new JPanel(new BorderLayout(8, 2)) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setBackground(t.surfaceAlt);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(t.border, 1),
                        BorderFactory.createEmptyBorder(6, 10, 6, 10)));
            }
        };
        headerBar.setOpaque(true);
        headerBar.add(headerTitle, BorderLayout.NORTH);
        headerBar.add(headerMeta, BorderLayout.SOUTH);

        JLabel adviceHead = Ui.bold("");
        JTextArea adviceArea = new JTextArea(6, 70);
        adviceArea.setEditable(false);
        adviceArea.setLineWrap(true);
        adviceArea.setWrapStyleWord(true);
        adviceArea.setOpaque(false);
        adviceArea.setFont(Theme.current().baseFont);
        adviceArea.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));

        JPanel adviceBox = new JPanel(new BorderLayout(4, 4)) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setBackground(t.surfaceAlt);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(t.accent.border(), 1),
                        BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            }
        };
        adviceBox.setOpaque(true);

        JButton recKeepOriginOnly = Ui.accent("Chỉ giữ GỐC #1 & Ẩn #2+",
                t -> Icons.check(t.accent.onSolid()), t -> t.accent, null);
        JButton recKeepSelected = Ui.button("Giữ bản ở chỗ mới", t -> Icons.check(t.success.text()), null);
        JButton recMoveToOrigin = Ui.button("Đưa về chỗ Gốc", Icons::undo, null);
        JButton recToggleRefresh = Ui.button("Gắn Tag [REFRESH]", Icons::tag, null);

        JPanel recBar = Ui.flow(FlowLayout.LEFT, recKeepOriginOnly, recKeepSelected, recMoveToOrigin, recToggleRefresh);
        adviceBox.add(adviceHead, BorderLayout.NORTH);
        adviceBox.add(MainPanel.scroll(adviceArea), BorderLayout.CENTER);
        adviceBox.add(recBar, BorderLayout.SOUTH);

        Runnable[] refreshRef = new Runnable[1];
        Runnable updateAdvice = () -> {
            int sel = table.getSelectedRow();
            LineageRow lr = sel >= 0 ? model.rowAt(sel) : null;
            ApiGroup g = lr != null ? lr.group : null;
            Decisions.SmartAdvice adv = g != null ? Decisions.advise(engine, g) : Decisions.adviseEndpoint(engine, key);
            adviceHead.setText("Diễn giải & Khuyến nghị: " + adv.headline());
            adviceHead.setForeground(adv.suspectedRefresh() ? Theme.current().warning.text() : Theme.current().accent.text());
            adviceArea.setText(adv.toFormattedText());
            adviceArea.setCaretPosition(0);

            PlacementBook.Record rec = key != null ? engine.book().get(key) : null;
            boolean hasRefresh = (g != null && g.hasTag(ApiTagCatalog.TAG_REFRESH))
                    || (rec != null && rec.tags.contains(ApiTagCatalog.TAG_REFRESH));
            recToggleRefresh.setText(hasRefresh ? "Gỡ Tag [REFRESH]" : "Gắn Tag [REFRESH]");
            recMoveToOrigin.setEnabled(g != null && !lr.isOrigin);
            recKeepSelected.setEnabled(g != null);
        };

        refreshRef[0] = () -> {
            int prev = table.getSelectedRow();
            model.reload();
            PlacementBook.Record rec = key != null ? engine.book().get(key) : null;
            ApiGroup any = model.firstGroup();
            String endpoint = any != null ? any.endpoint()
                    : (rec != null ? Text.nz(rec.method) + " " + Text.nz(rec.path) : "(Endpoint)");
            String host = any != null ? any.host() : (rec != null ? Text.nz(rec.host) : "");
            headerTitle.setText(endpoint + (host.isEmpty() ? "" : "   ·   " + host));

            List<String> meta = new ArrayList<>();
            meta.add("Tổng: " + model.copyCount() + " bản");
            if (rec != null && rec.originBranch != null && !rec.originBranch.isBlank()) {
                meta.add("Gốc định hình: " + rec.originBranch);
            }
            if (rec != null && !rec.tags.isEmpty()) {
                meta.add("Tag: " + String.join(", ", rec.tags));
            }
            if (rec != null && rec.refreshCount > 0) {
                meta.add("Tự tải lại (REFRESH): " + rec.refreshCount + " lần");
            }
            if (rec != null && !rec.triggeredByActions.isEmpty()) {
                meta.add("Sau hành động: " + String.join(", ", rec.triggeredByActions));
            }
            headerMeta.setText(String.join("   |   ", meta));

            if (model.getRowCount() > 0) {
                int targetRow = prev >= 0 && prev < model.getRowCount() ? prev : 0;
                table.setRowSelectionInterval(targetRow, targetRow);
            }
            updateAdvice.run();
            ui.tick(true);
        };

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateAdvice.run();
            }
        });

        // CRUD Toolbar actions
        JButton addPlaceBtn = Ui.button("+ Thêm / Sửa giữ chỗ", Icons::plus, () -> {
            PlacementBook.Record rec = key != null ? engine.book().get(key) : null;
            String cur = rec != null && rec.place != null ? rec.place : engine.activeBranch().name();
            String chosen = PathPicker.pick(dlg, "Chọn đường dẫn giữ chỗ mặc định cho endpoint này",
                    engine.knownPaths(), cur, "API khi bị xóa và bắt lại sau này sẽ tự động quay về vị trí này.");
            if (chosen != null && key != null) {
                engine.editPlace(key, chosen);
                refreshRef[0].run();
            }
        });
        JButton editPathBtn = Ui.button("Sửa Đường dẫn", Icons::edit, () -> {
            LineageRow lr = selectedRow(table, model);
            if (lr == null || lr.group == null) {
                return;
            }
            String chosen = PathPicker.pick(dlg, "Đổi Đường dẫn cho " + Text.groupId(lr.group.gid()),
                    engine.knownPaths(), lr.group.branch(), null);
            if (chosen != null) {
                engine.moveToPath(List.of(lr.group), chosen, "sửa đường dẫn từ Lineage");
                refreshRef[0].run();
            }
        });
        JButton editSttBtn = Ui.button("Sửa STT", Icons::sliders, () -> {
            LineageRow lr = selectedRow(table, model);
            if (lr == null || lr.group == null) {
                return;
            }
            String init = lr.group.copyStt() != null ? String.valueOf(lr.group.copyStt()) : "";
            String raw = JOptionPane.showInputDialog(dlg,
                    "Nhập số STT Copy cho " + Text.groupId(lr.group.gid()) + " (để trống = tự động):", init);
            if (raw == null) {
                return;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                engine.setApiCopyStt(lr.group, null);
                refreshRef[0].run();
                return;
            }
            try {
                int stt = Integer.parseInt(trimmed);
                if (stt <= 0) {
                    throw new NumberFormatException();
                }
                engine.setApiCopyStt(lr.group, stt);
                refreshRef[0].run();
            } catch (NumberFormatException ex) {
                ui.info("STT phải là số nguyên dương (VD: 1, 2, 15...).");
            }
        });
        JButton promoteBtn = Ui.button("Đặt làm GỐC #1", Icons::star, () -> {
            LineageRow lr = selectedRow(table, model);
            if (lr == null || lr.group == null) {
                return;
            }
            if (engine.promoteToOrigin(lr.group)) {
                ui.flash("Đã đặt " + Text.groupId(lr.group.gid()) + " tại [" + lr.group.branch() + "] làm API GỐC #1");
                refreshRef[0].run();
            }
        });
        JButton toggleHideBtn = Ui.button("Ẩn / Hiện", Icons::hide, () -> {
            LineageRow lr = selectedRow(table, model);
            if (lr == null || lr.group == null) {
                return;
            }
            if (lr.group.hidden()) {
                engine.show(List.of(lr.group));
            } else {
                engine.hide(List.of(lr.group), "ẩn từ Lineage");
            }
            refreshRef[0].run();
        });
        JButton deleteBtn = Ui.button("Xóa bản này", t -> Icons.trash(t.danger.text()), () -> {
            LineageRow lr = selectedRow(table, model);
            if (lr == null || lr.group == null) {
                return;
            }
            if (ui.confirmDialog("Xóa bản #" + lr.group.dupNo() + " (" + Text.groupId(lr.group.gid()) + ")?")) {
                engine.remove(List.of(lr.group), "xóa từ Lineage");
                refreshRef[0].run();
            }
        });
        JButton openApiBtn = Ui.button("Mở ở Tab API", Icons::search, () -> {
            LineageRow lr = selectedRow(table, model);
            if (lr != null && lr.group != null) {
                dlg.dispose();
                ui.jumpToGroup(lr.group);
            }
        });

        // Recommended 1-click actions
        recKeepOriginOnly.addActionListener(e -> {
            if (key == null) {
                return;
            }
            int hidden = engine.keepOnlyOriginAndHideOthers(key);
            ui.flash("Đã giữ nguyên API GỐC #1 và ẩn " + hidden + " bản trùng");
            refreshRef[0].run();
        });
        recKeepSelected.addActionListener(e -> {
            LineageRow lr = selectedRow(table, model);
            if (lr != null && lr.group != null) {
                if (lr.group.dupPending()) {
                    engine.keepDuplicates(List.of(lr.group));
                } else if (lr.group.hidden()) {
                    engine.show(List.of(lr.group));
                } else {
                    engine.confirm(List.of(lr.group), "xác nhận giữ bản tại Lineage");
                }
                refreshRef[0].run();
            }
        });
        recMoveToOrigin.addActionListener(e -> {
            LineageRow lr = selectedRow(table, model);
            if (lr != null && lr.group != null && engine.moveCopyToOrigin(lr.group)) {
                ui.flash("Đã đưa " + Text.groupId(lr.group.gid()) + " về nhánh Gốc");
                refreshRef[0].run();
            }
        });
        recToggleRefresh.addActionListener(e -> {
            if (key == null) {
                return;
            }
            PlacementBook.Record rec = engine.book().get(key);
            boolean currentlyTagged = rec != null && rec.tags.contains(ApiTagCatalog.TAG_REFRESH);
            engine.toggleEndpointTag(key, ApiTagCatalog.TAG_REFRESH, !currentlyTagged);
            ui.flash(!currentlyTagged ? "Đã gắn tag [REFRESH] cho toàn bộ chuỗi endpoint này"
                    : "Đã gỡ tag [REFRESH] khỏi endpoint này");
            refreshRef[0].run();
        });

        JPanel crudBar = Ui.flow(FlowLayout.LEFT, addPlaceBtn, editPathBtn, editSttBtn, promoteBtn, toggleHideBtn, deleteBtn, openApiBtn);
        JPanel topPanel = new JPanel(new BorderLayout(4, 4));
        topPanel.add(headerBar, BorderLayout.NORTH);
        topPanel.add(crudBar, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, MainPanel.scroll(table), adviceBox);
        split.setResizeWeight(0.56);

        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(topPanel, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(Ui.flow(FlowLayout.RIGHT, Ui.button("Đóng", (String) null, dlg::dispose)), BorderLayout.SOUTH);

        refreshRef[0].run();
        if (initialSelection != null) {
            int idx = model.indexOf(initialSelection);
            if (idx >= 0) {
                table.setRowSelectionInterval(idx, idx);
            }
        }

        dlg.setContentPane(root);
        dlg.setSize(new Dimension(980, 560));
        dlg.setLocationRelativeTo(ui.root());
        dlg.setVisible(true);
    }

    private static LineageRow selectedRow(JTable t, LineageModel m) {
        int r = t.getSelectedRow();
        return r < 0 ? null : m.rowAt(r);
    }

    private record LineageRow(
            String sttLabel,
            String roleLabel,
            String gidLabel,
            String branch,
            String capturedAt,
            String copyStt,
            String status,
            String time,
            boolean isOrigin,
            boolean ghost,
            ApiGroup group
    ) {
    }

    private static final class LineageModel extends AbstractTableModel {
        private static final String[] COLS = {
                "Bản", "Vai trò", "API #", "Vị trí hiện tại (Đường dẫn)", "Đích lúc bắt", "STT Copy/Excel", "Trạng thái", "Bắt lúc"
        };
        private final Engine engine;
        private final String bookKey;
        private final List<LineageRow> rows = new ArrayList<>();

        LineageModel(Engine engine, String bookKey) {
            this.engine = engine;
            this.bookKey = bookKey;
        }

        void reload() {
            rows.clear();
            List<ApiGroup> copies = bookKey == null ? List.of() : new ArrayList<>(engine.copiesOf(bookKey));
            copies.sort(Comparator.comparingInt(ApiGroup::dupNo));
            ApiGroup origin = bookKey == null ? null : engine.originOf(bookKey);
            PlacementBook.Record rec = bookKey == null ? null : engine.book().get(bookKey);

            // Pin #1 API GỐC at row 0 (or Ghost placeholder if origin was deleted)
            if (origin != null) {
                rows.add(rowFor(origin, true, copies.size()));
            } else if (rec != null && rec.originBranch != null && !rec.originBranch.isBlank()) {
                rows.add(new LineageRow("#1", "GỐC (Đã xóa / Ghost)",
                        rec.originGid != null ? Text.groupId(rec.originGid) : "—",
                        rec.originBranch, Text.nz(rec.lastFoundAt), "—",
                        "Giữ chỗ (" + rec.deleted + "x xóa)", Text.nz(rec.originAt),
                        true, true, null));
            }
            for (ApiGroup c : copies) {
                if (origin != null && c.gid() == origin.gid()) {
                    continue;
                }
                rows.add(rowFor(c, false, copies.size()));
            }
            fireTableDataChanged();
        }

        private LineageRow rowFor(ApiGroup g, boolean isOrig, int total) {
            String role = isOrig ? "API GỐC ĐỊNH HÌNH" : "BẢN TRÙNG #" + g.dupNo();
            String stt = g.target() != null ? "Excel #" + g.target().sttText()
                    : (g.copyStt() != null ? "STT " + g.copyStt() : "(Tự động)");
            String state = g.hidden() ? "Đang ẩn"
                    : g.dupPending() ? "Chờ quyết định"
                    : g.pending() ? "Chờ xác nhận" : "Đang hiện";
            if (g.hasTag(ApiTagCatalog.TAG_REFRESH)) {
                state += " · [REFRESH]";
            } else if (g.suspectedRefresh()) {
                state += " · [? Refresh]";
            }
            return new LineageRow("#" + g.dupNo() + "/" + Math.max(1, total), role,
                    Text.groupId(g.gid()), g.branch(), g.capturedAtBranch(), stt, state, g.created(),
                    isOrig, false, g);
        }

        LineageRow rowAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        ApiGroup firstGroup() {
            for (LineageRow lr : rows) {
                if (lr.group != null) {
                    return lr.group;
                }
            }
            return null;
        }

        int copyCount() {
            int n = 0;
            for (LineageRow lr : rows) {
                if (!lr.ghost) {
                    n++;
                }
            }
            return n;
        }

        int indexOf(ApiGroup target) {
            if (target == null) {
                return -1;
            }
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).group != null && rows.get(i).group.gid() == target.gid()) {
                    return i;
                }
            }
            return -1;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            LineageRow lr = rows.get(r);
            return switch (c) {
                case 0 -> lr.sttLabel;
                case 1 -> lr.roleLabel;
                case 2 -> lr.gidLabel;
                case 3 -> lr.branch;
                case 4 -> lr.capturedAt;
                case 5 -> lr.copyStt;
                case 6 -> lr.status;
                default -> lr.time;
            };
        }
    }
}
