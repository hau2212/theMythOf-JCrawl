package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ApiTagCatalog;
import themythofcrawl.place.Decisions;
import themythofcrawl.place.PlacementBook;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.StructuredAdviceCard;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * Cửa sổ Quản lý Bản Gốc (#1) &amp; các bản trùng lặp v2:
 * - Bảng rộng thoáng (AUTO_RESIZE_OFF) tách riêng cột Trạng thái và cột Nhãn, không đè/cắt chữ
 * - Thẻ diễn giải tiếng Việt có cấu trúc {@link StructuredAdviceCard}
 * - Đầy đủ thao tác: Chỉ giữ Bản Gốc, Đặt làm Bản Gốc, Sửa STT, Chuyển vị trí, Gắn nhãn Tự tải lại, Hiện/Ẩn/Xóa.
 */
final class LineageDialog {
    private static final List<String> COLS = List.of(
            "Bản", "Vai trò", "Mã API", "Vị trí trong cây", "Trạng thái", "Nhãn", "STT / Excel", "Lần cuối");

    private LineageDialog() {
    }

    static void show(MainPanel ui, ApiGroup focus) {
        if (focus == null || focus.bookKey() == null) {
            ui.info("Chọn 1 API để xem Bản Gốc và các lần xuất hiện trùng.");
            return;
        }
        showByKey(ui, focus.bookKey(), focus);
    }

    static void showByKey(MainPanel ui, String bookKey, ApiGroup focus) {
        if (Text.blank(bookKey)) {
            ui.info("Không tìm thấy thông tin vị trí cho API này.");
            return;
        }
        PlacementBook.Record rec = ui.engine.book().get(bookKey);
        String titleEndpoint = focus != null ? focus.endpoint()
                : (rec != null ? Text.nz(rec.method) + " " + Text.nz(rec.path) : bookKey);

        JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(ui.root()),
                "Quản lý bản trùng & Bản Gốc — " + titleEndpoint, JDialog.ModalityType.APPLICATION_MODAL);
        dlg.setLayout(new BorderLayout(0, Theme.SPACE_2));

        RowsModel<ApiGroup> model = new RowsModel<>(COLS);
        JTable table = new ThemedTable(model);
        Ui.style(table);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        Ui.widths(table, 60, 122, 82, 260, 126, 112, 98, 118);
        AuditRenderer renderer = new AuditRenderer(model, null, 4, null, 0, 1)
                .numberCols(0)
                .timeCols(7);
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(renderer);
        }

        StructuredAdviceCard adviceCard = new StructuredAdviceCard();
        Pill headerBadge = Pill.badge("Bản Gốc & Trùng", t -> t.dup);

        JButton keepOrigBtn = Ui.accent("Chỉ giữ Bản Gốc (#1) & Ẩn bản lặp",
                t -> Icons.originCrown(t.accent.onSolid(), 14), t -> t.accent,
                "Giữ lại duy nhất Bản Gốc (#1) trên màn hình chính và ẩn toàn bộ các bản xuất hiện lại phía sau.",
                null, () -> { });
        JButton promoteBtn = Ui.button("Đặt làm Bản Gốc (#1)", t -> Icons.star(t.info.text(), 14),
                "Đổi dòng đang chọn trở thành Bản Gốc (#1) chuẩn cho endpoint này.", () -> { });
        JButton keepCopyBtn = Ui.button("Giữ bản này", t -> Icons.check(t.success.text(), 14),
                "Xác nhận giữ bản trùng này hiển thị song song tại vị trí mới.", () -> { });
        JButton toggleRefreshBtn = Ui.button("Nhãn Tự tải lại", Icons::refresh,
                "Đánh dấu endpoint này là API tự tải lại dữ liệu (REFRESH) sau thao tác.", () -> { });
        JButton openBtn = Ui.button("Mở API", Icons::search, "Nhảy tới đúng dòng API này trong tab API.", () -> { });
        JButton editSttBtn = Ui.button("Sửa STT...", Icons::edit, "Đặt lại số thứ tự (STT) hiển thị khi copy báo cáo.", () -> { });
        JButton moveBtn = Ui.button("Chuyển nhánh...", Icons::folder, "Di chuyển bản này sang một nhánh khác trong cây.", () -> { });
        JButton moveOrigBtn = Ui.button("Về chỗ Bản Gốc", Icons::move, "Đưa bản trùng này về cùng nhánh với Bản Gốc (#1).", () -> { });
        JButton toggleVisBtn = Ui.button("Ẩn / Hiện", Icons::hide, "Ẩn hoặc hiện lại bản trùng này trên danh sách chính.", () -> { });
        JButton deleteBtn = Ui.button("Xóa bản này", t -> Icons.trash(t.danger.text(), 14),
                "Xóa vĩnh viễn bản trùng đang chọn khỏi danh sách.", () -> { });

        Runnable[] reloadRef = new Runnable[1];
        java.util.function.Supplier<ApiGroup> selectedFn = () -> {
            int r = table.getSelectedRow();
            return r >= 0 ? model.payload(table.convertRowIndexToModel(r)) : null;
        };

        Runnable updateDetails = () -> {
            ApiGroup sel = selectedFn.get();
            Decisions.SmartAdvice adv = sel != null
                    ? Decisions.advise(ui.engine, sel)
                    : Decisions.adviseEndpoint(ui.engine, bookKey);
            adviceCard.setAdvice(adv);

            boolean hasSel = sel != null;
            ApiGroup orig = ui.engine.originOf(bookKey);
            boolean isOrig = hasSel && orig != null && orig.gid() == sel.gid();
            boolean hasRefresh = ui.engine.book().hasTag(bookKey, ApiTagCatalog.TAG_REFRESH);

            promoteBtn.setEnabled(hasSel && !isOrig);
            keepCopyBtn.setEnabled(hasSel && sel.dupPending());
            moveOrigBtn.setEnabled(hasSel && !isOrig && orig != null);
            openBtn.setEnabled(hasSel);
            editSttBtn.setEnabled(hasSel);
            moveBtn.setEnabled(hasSel);
            toggleVisBtn.setEnabled(hasSel);
            toggleVisBtn.setText(hasSel && sel.hidden() ? "Hiện lại" : "Ẩn bản này");
            deleteBtn.setEnabled(hasSel);
            toggleRefreshBtn.setText(hasRefresh ? "Gỡ nhãn Tự tải lại" : "Gắn nhãn Tự tải lại");
            keepOrigBtn.setEnabled(model.getRowCount() > 1);
            headerBadge.setText(model.getRowCount() + " lần xuất hiện");
        };

        reloadRef[0] = () -> {
            ApiGroup prev = selectedFn.get();
            List<ApiGroup> copies = new ArrayList<>(ui.engine.copiesOf(bookKey));
            copies.sort(Comparator.comparingInt(ApiGroup::dupNo));
            ApiGroup origin = ui.engine.originOf(bookKey);
            List<Decisions.Row<ApiGroup>> rows = new ArrayList<>();
            for (ApiGroup c : copies) {
                boolean isOrig = origin != null && c.gid() == origin.gid();
                String role = isOrig ? "Bản Gốc (#1)" : "Bản trùng";
                String state = Decisions.copyStateShort(c);
                String tag = Decisions.copyTagLabel(c);
                String stt = c.target() != null ? "Excel #" + c.target().sttText()
                        : (c.copyStt() != null ? "STT " + c.copyStt() : "—");
                AuditLevel lvl = isOrig ? AuditLevel.OK : Decisions.copyStateLevel(c);
                rows.add(new Decisions.Row<>(c, lvl, List.of(
                        "#" + c.dupNo(), role, Text.apiLabel(c.gid()), c.branch(), state, tag, stt, c.created())));
            }
            model.set(rows);
            int idx = -1;
            if (prev != null) {
                idx = copies.indexOf(prev);
            }
            if (idx < 0 && focus != null) {
                idx = copies.indexOf(focus);
            }
            if (idx < 0 && !copies.isEmpty()) {
                idx = 0;
            }
            if (idx >= 0) {
                table.setRowSelectionInterval(idx, idx);
            }
            updateDetails.run();
        };

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateDetails.run();
            }
        });
        Ui.onDoubleClick(table, () -> {
            ApiGroup sel = selectedFn.get();
            if (sel != null) {
                dlg.dispose();
                ui.jumpToGroup(sel);
            }
        });

        keepOrigBtn.addActionListener(e -> {
            int n = ui.engine.keepOnlyOriginAndHideOthers(bookKey);
            ui.flash("Đã giữ Bản Gốc (#1) và ẩn " + n + " bản trùng");
            ui.tick(true);
            reloadRef[0].run();
        });
        promoteBtn.addActionListener(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel != null && ui.engine.promoteToOrigin(sel)) {
                ui.flash("Đã đặt " + Text.apiLabel(sel.gid()) + " làm Bản Gốc (#1)");
                ui.tick(true);
                reloadRef[0].run();
            }
        });
        keepCopyBtn.addActionListener(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel != null) {
                ui.engine.keepDuplicates(List.of(sel));
                ui.flash("Đã giữ bản trùng #" + sel.dupNo() + " tại " + sel.branch());
                ui.tick(true);
                reloadRef[0].run();
            }
        });
        toggleRefreshBtn.addActionListener(e -> {
            boolean next = !ui.engine.book().hasTag(bookKey, ApiTagCatalog.TAG_REFRESH);
            ui.engine.toggleEndpointTag(bookKey, ApiTagCatalog.TAG_REFRESH, next);
            ui.flash(next ? "Đã gắn nhãn Tự tải lại (REFRESH)" : "Đã gỡ nhãn Tự tải lại");
            ui.tick(true);
            reloadRef[0].run();
        });
        openBtn.addActionListener(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel != null) {
                dlg.dispose();
                ui.jumpToGroup(sel);
            }
        });
        editSttBtn.addActionListener(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel == null) {
                return;
            }
            String cur = sel.copyStt() != null ? String.valueOf(sel.copyStt()) : "";
            String input = JOptionPane.showInputDialog(dlg,
                    "Nhập số thứ tự (STT) cho " + Text.apiLabel(sel.gid()) + " (để trống để tự động):", cur);
            if (input == null) {
                return;
            }
            Integer val = null;
            if (!input.isBlank()) {
                try {
                    val = Integer.parseInt(input.trim());
                } catch (NumberFormatException ex) {
                    ui.info("STT phải là số nguyên dương (ví dụ: 1, 2, 15).");
                    return;
                }
            }
            ui.engine.setApiCopyStt(sel, val);
            ui.tick(true);
            reloadRef[0].run();
        });
        moveBtn.addActionListener(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel != null) {
                ui.moveDialog(List.of(sel));
                ui.tick(true);
                reloadRef[0].run();
            }
        });
        moveOrigBtn.addActionListener(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel != null && ui.engine.moveCopyToOrigin(sel)) {
                ui.flash("Đã chuyển " + Text.apiLabel(sel.gid()) + " về cùng nhánh với Bản Gốc (#1)");
                ui.tick(true);
                reloadRef[0].run();
            }
        });
        toggleVisBtn.addActionListener(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel == null) {
                return;
            }
            if (sel.hidden()) {
                ui.engine.show(List.of(sel));
                ui.flash("Đã hiện lại bản #" + sel.dupNo());
            } else {
                ui.engine.hide(List.of(sel), "ẩn từ Quản lý bản trùng");
                ui.flash("Đã ẩn bản #" + sel.dupNo());
            }
            ui.tick(true);
            reloadRef[0].run();
        });
        deleteBtn.addActionListener(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel == null) {
                return;
            }
            if (ui.confirmDialog("Xóa bản #" + sel.dupNo() + " (" + Text.apiLabel(sel.gid()) + ")?")) {
                ui.engine.remove(List.of(sel), "xóa từ Quản lý bản trùng");
                ui.tick(true);
                reloadRef[0].run();
            }
        });

        JButton closeBtn = Ui.button("Đóng", dlg::dispose);

        JPanel primaryRow = Ui.flow(FlowLayout.LEFT, keepOrigBtn, promoteBtn, keepCopyBtn, toggleRefreshBtn);
        JPanel secondaryRow = new JPanel(new BorderLayout());
        secondaryRow.setOpaque(false);
        secondaryRow.add(Ui.flow(FlowLayout.LEFT, openBtn, editSttBtn, moveBtn, moveOrigBtn, toggleVisBtn), BorderLayout.CENTER);
        secondaryRow.add(Ui.flow(FlowLayout.RIGHT, deleteBtn, closeBtn), BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        south.setOpaque(false);
        south.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));
        south.add(primaryRow, BorderLayout.NORTH);
        south.add(secondaryRow, BorderLayout.SOUTH);

        JPanel topAdviceWrap = new JPanel(new BorderLayout());
        topAdviceWrap.setOpaque(false);
        topAdviceWrap.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_3, 0, Theme.SPACE_3));
        topAdviceWrap.add(adviceCard, BorderLayout.CENTER);

        JPanel tableWrap = new JPanel(new BorderLayout());
        tableWrap.setOpaque(false);
        tableWrap.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_3, 0, Theme.SPACE_3));
        tableWrap.add(MainPanel.scroll(table), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topAdviceWrap, tableWrap);
        split.setResizeWeight(0.42);
        split.setDividerSize(5);
        split.setBorder(BorderFactory.createEmptyBorder());

        dlg.add(Ui.dialogHeader("Quản lý bản trùng & Bản Gốc (#1)",
                "Endpoint: " + titleEndpoint + "  ·  Chọn dòng để xem chi tiết hoặc xử lý nhanh",
                headerBadge), BorderLayout.NORTH);
        dlg.add(split, BorderLayout.CENTER);
        dlg.add(south, BorderLayout.SOUTH);

        reloadRef[0].run();
        Theme.track(dlg);
        dlg.setMinimumSize(new Dimension(860, 540));
        dlg.setPreferredSize(new Dimension(980, 620));
        dlg.pack();
        dlg.setLocationRelativeTo(ui.root());
        dlg.setVisible(true);
    }
}
