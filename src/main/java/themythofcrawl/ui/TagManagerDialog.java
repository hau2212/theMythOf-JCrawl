package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Config;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ApiTagCatalog;
import themythofcrawl.place.PlacementBook;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * Cửa sổ Quản lý API Tag & Chính sách REFRESH (GET-after-Action):
 * - Cấu hình chế độ xử lý tự động M1–M4 ({@link Config.RefreshPolicy}) & bật/tắt tự phát hiện GET sau POST/PUT/DELETE
 * - Danh sách toàn bộ endpoint đã gắn tag / nghi vấn REFRESH kèm thao tác nhanh (Lineage, Toggle REFRESH, Gỡ tag)
 * - Thêm / sửa / xóa danh mục Tag tùy biến ({@link ApiTagCatalog}).
 */
final class TagManagerDialog {
    private static final String[] COLOR_KEYS = {"warn", "info", "purple", "accent", "teal", "ok", "bad", "muted"};

    private TagManagerDialog() {
    }

    static void show(MainPanel ui) {
        if (ui == null) {
            return;
        }
        Engine engine = ui.engine;
        JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(ui.root()),
                "Quản lý API Tag & Chính sách REFRESH (GET sau Action)", JDialog.ModalityType.APPLICATION_MODAL);

        // --- North: Refresh Policy (M1-M4) + Auto-Detect toggle
        JComboBox<Config.RefreshPolicy> policyBox = new JComboBox<>(Config.RefreshPolicy.values());
        policyBox.setSelectedItem(engine.config().refreshPolicy);
        policyBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof Config.RefreshPolicy rp) {
                    setText(rp.label);
                }
                return this;
            }
        });
        JCheckBox autoDetectChk = new JCheckBox(
                "Tự động phát hiện API GET xuất hiện ngay sau hành động (POST/PUT/PATCH/DELETE trong <= 2.5s) và gợi ý [? Refresh]",
                engine.config().autoDetectRefresh);
        autoDetectChk.setOpaque(false);

        Runnable savePolicy = () -> {
            Config c = engine.config().copy();
            if (policyBox.getSelectedItem() instanceof Config.RefreshPolicy rp) {
                c.refreshPolicy = rp;
            }
            c.autoDetectRefresh = autoDetectChk.isSelected();
            engine.applyConfig(c.normalized());
            ui.settings.load();
            ui.flash("Đã lưu chính sách REFRESH: " + c.refreshPolicy.name());
        };
        policyBox.addActionListener(e -> savePolicy.run());
        autoDetectChk.addActionListener(e -> savePolicy.run());

        JPanel policyPanel = new JPanel(new BorderLayout(6, 4)) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setBackground(t.surfaceAlt);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(t.border, 1),
                        BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            }
        };
        policyPanel.setOpaque(true);
        JPanel polRow = new JPanel(new BorderLayout(8, 0));
        polRow.setOpaque(false);
        polRow.add(Ui.bold("Chính sách khi bắt gặp API gắn [REFRESH]:"), BorderLayout.WEST);
        polRow.add(policyBox, BorderLayout.CENTER);
        policyPanel.add(polRow, BorderLayout.NORTH);
        policyPanel.add(autoDetectChk, BorderLayout.SOUTH);

        // --- Tab 1: Tagged Endpoints & REFRESH list
        JComboBox<String> filterBox = new JComboBox<>();
        TaggedModel taggedModel = new TaggedModel(engine);
        ThemedTable taggedTable = new ThemedTable(taggedModel);
        Ui.style(taggedTable);
        taggedTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Ui.widths(taggedTable, 250, 130, 170, 180, 76, 70);

        Runnable reloadFilterAndTagged = () -> {
            Object prev = filterBox.getSelectedItem();
            filterBox.removeAllItems();
            filterBox.addItem("Tất cả có Tag / Gợi ý REFRESH");
            filterBox.addItem("Chỉ gợi ý ? REFRESH");
            for (ApiTagCatalog.TagDef td : engine.tagCatalog().all()) {
                filterBox.addItem("Tag: " + td.id());
            }
            if (prev != null) {
                filterBox.setSelectedItem(prev);
            }
            taggedModel.reload(String.valueOf(filterBox.getSelectedItem()));
        };
        filterBox.addActionListener(e -> {
            if (filterBox.getSelectedItem() != null) {
                taggedModel.reload(String.valueOf(filterBox.getSelectedItem()));
            }
        });

        JButton openLineageBtn = Ui.button("Xem Lineage...", Icons::dup, () -> {
            TaggedRow tr = taggedModel.rowAt(taggedTable.getSelectedRow());
            if (tr != null) {
                LineageDialog.showByKey(ui, tr.bookKey, tr.sampleGroup);
                taggedModel.reload(String.valueOf(filterBox.getSelectedItem()));
            }
        });
        JButton toggleRefreshBtn = Ui.button("Bật / Tắt [REFRESH]", Icons::tag, () -> {
            TaggedRow tr = taggedModel.rowAt(taggedTable.getSelectedRow());
            if (tr != null && tr.bookKey != null) {
                boolean has = tr.tags.contains(ApiTagCatalog.TAG_REFRESH);
                engine.toggleEndpointTag(tr.bookKey, ApiTagCatalog.TAG_REFRESH, !has);
                taggedModel.reload(String.valueOf(filterBox.getSelectedItem()));
                ui.tick(true);
            }
        });
        JButton keepOrigBtn = Ui.button("Chỉ giữ GỐC #1 & Ẩn #2+", t -> Icons.check(t.success.text()), () -> {
            TaggedRow tr = taggedModel.rowAt(taggedTable.getSelectedRow());
            if (tr != null && tr.bookKey != null) {
                int n = engine.keepOnlyOriginAndHideOthers(tr.bookKey);
                ui.flash("Đã giữ GỐC #1 và ẩn " + n + " bản trùng");
                taggedModel.reload(String.valueOf(filterBox.getSelectedItem()));
                ui.tick(true);
            }
        });
        JButton removeAllTagsBtn = Ui.button("Gỡ tất cả Tag", t -> Icons.trash(t.danger.text()), () -> {
            TaggedRow tr = taggedModel.rowAt(taggedTable.getSelectedRow());
            if (tr != null && tr.bookKey != null) {
                for (String tid : new ArrayList<>(tr.tags)) {
                    engine.toggleEndpointTag(tr.bookKey, tid, false);
                }
                taggedModel.reload(String.valueOf(filterBox.getSelectedItem()));
                ui.tick(true);
            }
        });

        JPanel tab1 = new JPanel(new BorderLayout(6, 6));
        tab1.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        tab1.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Lọc:"), filterBox, openLineageBtn, toggleRefreshBtn, keepOrigBtn, removeAllTagsBtn),
                BorderLayout.NORTH);
        tab1.add(MainPanel.scroll(taggedTable), BorderLayout.CENTER);

        // --- Tab 2: Tag Catalog CRUD
        CatalogModel catModel = new CatalogModel(engine);
        ThemedTable catTable = new ThemedTable(catModel);
        Ui.style(catTable);
        catTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Ui.widths(catTable, 120, 130, 80, 360, 74);

        JTextField idField = new JTextField(14);
        JTextField labelField = new JTextField(14);
        JComboBox<String> colorBox = new JComboBox<>(COLOR_KEYS);
        JTextField descField = new JTextField(32);

        catTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                ApiTagCatalog.TagDef td = catModel.at(catTable.getSelectedRow());
                if (td != null) {
                    idField.setText(td.id());
                    labelField.setText(td.label());
                    colorBox.setSelectedItem(td.colorKey());
                    descField.setText(td.description());
                }
            }
        });

        JButton saveTagBtn = Ui.accent("Thêm / Cập nhật Tag", t -> Icons.save(t.accent.onSolid()), t -> t.accent, () -> {
            String rawId = idField.getText().trim();
            if (rawId.isEmpty()) {
                ui.info("Nhập mã Tag (VD: REFRESH, AUTH, NOISE...).");
                return;
            }
            String normId = ApiTagCatalog.normalizeId(rawId);
            ApiTagCatalog.TagDef existing = engine.tagCatalog().byId(normId);
            boolean builtIn = existing != null && existing.builtIn();
            String lbl = labelField.getText().isBlank() ? normId : labelField.getText().trim();
            String col = colorBox.getSelectedItem() != null ? colorBox.getSelectedItem().toString() : "info";
            engine.tagCatalog().upsert(new ApiTagCatalog.TagDef(normId, lbl, col, descField.getText().trim(), builtIn));
            engine.saveTagCatalog();
            catModel.reload();
            reloadFilterAndTagged.run();
            ui.tick(true);
            ui.flash("Đã lưu Tag [" + normId + "]");
        });
        JButton delTagBtn = Ui.button("Xóa Tag", t -> Icons.trash(t.danger.text()), () -> {
            ApiTagCatalog.TagDef td = catModel.at(catTable.getSelectedRow());
            if (td == null) {
                return;
            }
            if (ApiTagCatalog.TAG_REFRESH.equals(td.id())) {
                ui.info("Không thể xóa Tag hệ thống [REFRESH].");
                return;
            }
            if (JOptionPane.showConfirmDialog(dlg, "Xóa Tag [" + td.id() + "]?", "Xóa Tag", JOptionPane.YES_NO_OPTION)
                    == JOptionPane.YES_OPTION) {
                engine.tagCatalog().remove(td.id());
                engine.saveTagCatalog();
                catModel.reload();
                reloadFilterAndTagged.run();
                ui.tick(true);
            }
        });
        JButton resetTagBtn = Ui.button("Khôi phục mặc định", Icons::undo, () -> {
            if (JOptionPane.showConfirmDialog(dlg, "Khôi phục danh mục Tag về 4 tag mặc định (REFRESH, INIT, POLLING, ACTION-CORE)?",
                    "Khôi phục", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                engine.tagCatalog().resetDefaults();
                engine.saveTagCatalog();
                catModel.reload();
                reloadFilterAndTagged.run();
                ui.tick(true);
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.current().border, 1),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 4, 3, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.gridx = 0; gc.gridy = 0; form.add(Ui.bold("Mã Tag:"), gc);
        gc.gridx = 1; form.add(idField, gc);
        gc.gridx = 2; form.add(Ui.bold("Tên hiển thị:"), gc);
        gc.gridx = 3; form.add(labelField, gc);
        gc.gridx = 4; form.add(Ui.bold("Màu:"), gc);
        gc.gridx = 5; form.add(colorBox, gc);
        gc.gridx = 0; gc.gridy = 1; form.add(Ui.bold("Mô tả:"), gc);
        gc.gridx = 1; gc.gridwidth = 5; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1.0;
        form.add(descField, gc);
        gc.gridx = 0; gc.gridy = 2; gc.gridwidth = 6; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        form.add(Ui.flow(FlowLayout.LEFT, saveTagBtn, delTagBtn, resetTagBtn), gc);

        JPanel tab2 = new JPanel(new BorderLayout(6, 6));
        tab2.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        tab2.add(MainPanel.scroll(catTable), BorderLayout.CENTER);
        tab2.add(form, BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Endpoint đã gắn Tag & REFRESH", tab1);
        tabs.addTab("Danh mục Tag (Thêm / Sửa / Xóa)", tab2);

        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(policyPanel, BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        root.add(Ui.flow(FlowLayout.RIGHT, Ui.button("Đóng", (String) null, dlg::dispose)), BorderLayout.SOUTH);

        catModel.reload();
        reloadFilterAndTagged.run();
        if (catModel.getRowCount() > 0) {
            catTable.setRowSelectionInterval(0, 0);
        }

        dlg.setContentPane(root);
        dlg.setSize(new Dimension(920, 540));
        dlg.setLocationRelativeTo(ui.root());
        dlg.setVisible(true);
    }

    private record TaggedRow(
            String bookKey,
            String endpoint,
            List<String> tags,
            String tagsText,
            String originBranch,
            String triggers,
            int refreshCount,
            int copies,
            ApiGroup sampleGroup
    ) {
    }

    private static final class TaggedModel extends AbstractTableModel {
        private static final String[] COLS = {"Endpoint", "Tags", "Gốc #1", "Sau hành động (Trigger)", "Lần lặp", "Số bản"};
        private final Engine engine;
        private final List<TaggedRow> rows = new ArrayList<>();

        TaggedModel(Engine engine) {
            this.engine = engine;
        }

        void reload(String filter) {
            rows.clear();
            String wantedTag = filter != null && filter.startsWith("Tag: ") ? filter.substring(5).trim() : null;
            boolean suspectedOnly = "Chỉ gợi ý ? REFRESH".equals(filter);

            for (PlacementBook.Record rec : engine.book().records()) {
                List<ApiGroup> copies = engine.copiesOf(rec.key);
                boolean hasSuspected = copies.stream().anyMatch(ApiGroup::suspectedRefresh) || rec.refreshCount > 0;
                List<String> tagList = new ArrayList<>(rec.tags);
                for (ApiGroup c : copies) {
                    for (String tid : c.tags()) {
                        if (!tagList.contains(tid)) {
                            tagList.add(tid);
                        }
                    }
                }
                if (wantedTag != null) {
                    if (!tagList.contains(wantedTag)) {
                        continue;
                    }
                } else if (suspectedOnly) {
                    if (!hasSuspected) {
                        continue;
                    }
                } else {
                    if (tagList.isEmpty() && !hasSuspected) {
                        continue;
                    }
                }
                StringBuilder tagTxt = new StringBuilder();
                for (String t : tagList) {
                    if (tagTxt.length() > 0) tagTxt.append(' ');
                    tagTxt.append('[').append(t).append(']');
                }
                if (hasSuspected && !tagList.contains(ApiTagCatalog.TAG_REFRESH)) {
                    if (tagTxt.length() > 0) tagTxt.append(' ');
                    tagTxt.append("[? Refresh]");
                }
                ApiGroup orig = engine.originOf(rec.key);
                String origBranch = orig != null ? orig.branch() : Text.nz(rec.originBranch != null ? rec.originBranch : rec.place);
                String triggers = String.join(", ", rec.triggeredByActions);
                if (triggers.isEmpty()) {
                    for (ApiGroup c : copies) {
                        if (!Text.blank(c.triggeredByAction())) {
                            triggers = c.triggeredByAction();
                            break;
                        }
                    }
                }
                ApiGroup sample = orig != null ? orig : (copies.isEmpty() ? null : copies.get(0));
                rows.add(new TaggedRow(rec.key, Text.nz(rec.method) + " " + Text.nz(rec.path),
                        tagList, tagTxt.toString(), origBranch, triggers, rec.refreshCount, copies.size(), sample));
            }
            fireTableDataChanged();
        }

        TaggedRow rowAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            TaggedRow tr = rows.get(r);
            return switch (c) {
                case 0 -> tr.endpoint;
                case 1 -> tr.tagsText;
                case 2 -> tr.originBranch;
                case 3 -> tr.triggers;
                case 4 -> tr.refreshCount;
                default -> tr.copies;
            };
        }
    }

    private static final class CatalogModel extends AbstractTableModel {
        private static final String[] COLS = {"Mã Tag", "Tên hiển thị", "Màu", "Mô tả", "Hệ thống"};
        private final Engine engine;
        private List<ApiTagCatalog.TagDef> list = List.of();

        CatalogModel(Engine engine) {
            this.engine = engine;
        }

        void reload() {
            list = engine.tagCatalog().all();
            fireTableDataChanged();
        }

        ApiTagCatalog.TagDef at(int r) {
            return r >= 0 && r < list.size() ? list.get(r) : null;
        }

        @Override public int getRowCount() { return list.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            ApiTagCatalog.TagDef td = list.get(r);
            return switch (c) {
                case 0 -> td.id();
                case 1 -> td.label();
                case 2 -> td.colorKey();
                case 3 -> td.description();
                default -> td.builtIn() ? "Mặc định" : "Tùy biến";
            };
        }
    }
}
