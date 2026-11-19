package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Text;
import themythofcrawl.model.NodeTypeCatalog;
import themythofcrawl.model.NodeTypeCatalog.NodeType;
import themythofcrawl.ui.kit.IconPickerPanel;
import themythofcrawl.ui.theme.Theme;

/**
 * Full CRUD manager v2.1 for {@link NodeTypeCatalog}: add / clone / reorder / edit / delete / reset
 * plus Export &amp; Import JSON pack, powered by the reusable {@link IconPickerPanel}
 * (42 searchable vectors, 13 colors + Hex, 3 monogram styles, base64 custom PNG/JPG).
 */
final class NodeTypeManagerDialog {
    private NodeTypeManagerDialog() {
    }

    static boolean show(Component parent, Engine engine) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, "Quản lý Loại Node & Biểu tượng (Icon Pack v2.1)", JDialog.ModalityType.APPLICATION_MODAL);
        final boolean[] modified = {false};

        DefaultListModel<NodeType> model = new DefaultListModel<>();
        JList<NodeType> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(Theme.current().scaled(30));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object val, int index, boolean sel, boolean focus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(l, val, index, sel, focus);
                Theme t = Theme.current();
                if (val instanceof NodeType nt) {
                    lbl.setIcon(Icons.nodeIcon(nt, t, 15));
                    lbl.setText(" " + nt.label() + "  [" + nt.id() + "]" + (nt.builtIn() ? "" : " *"));
                    lbl.setFont(t.smallFont);
                    lbl.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
                }
                return lbl;
            }
        });

        JTextField idField = new JTextField(16);
        JTextField labelField = new JTextField(20);
        IconPickerPanel picker = new IconPickerPanel();
        NodeEditorDialog.LiveNodePreview livePreview = new NodeEditorDialog.LiveNodePreview();
        final boolean[] selBuiltIn = {false};
        final boolean[] loading = {false};

        java.util.function.Supplier<NodeType> buildDraft = () ->
                picker.buildNodeType(idField.getText().trim(), labelField.getText().trim(), selBuiltIn[0]);

        Runnable refreshPreview = () -> {
            NodeType draft = buildDraft.get();
            livePreview.update(draft, draft.label(), "Toppage > " + draft.label());
        };

        picker.onChange(() -> {
            if (!loading[0]) {
                refreshPreview.run();
            }
        });
        DocumentListener liveDoc = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { if (!loading[0]) refreshPreview.run(); }
            @Override public void removeUpdate(DocumentEvent e) { if (!loading[0]) refreshPreview.run(); }
            @Override public void changedUpdate(DocumentEvent e) { if (!loading[0]) refreshPreview.run(); }
        };
        idField.getDocument().addDocumentListener(liveDoc);
        labelField.getDocument().addDocumentListener(liveDoc);

        Runnable reloadList = () -> {
            NodeType prev = list.getSelectedValue();
            String prevId = prev != null ? prev.id() : null;
            model.clear();
            int selIdx = 0;
            List<NodeType> all = engine.nodeTypes().all();
            for (int i = 0; i < all.size(); i++) {
                NodeType nt = all.get(i);
                model.addElement(nt);
                if (nt.id().equals(prevId)) {
                    selIdx = i;
                }
            }
            if (!all.isEmpty()) {
                list.setSelectedIndex(selIdx);
            }
        };

        Runnable loadSelected = () -> {
            NodeType nt = list.getSelectedValue();
            if (nt == null) {
                return;
            }
            loading[0] = true;
            try {
                idField.setText(nt.id());
                idField.setEditable(!nt.builtIn());
                labelField.setText(nt.label());
                selBuiltIn[0] = nt.builtIn();
                picker.loadFrom(nt);
            } finally {
                loading[0] = false;
            }
            refreshPreview.run();
        };

        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadSelected.run();
            }
        });

        JButton newBtn = Ui.button("Thêm mới", t -> Icons.plus(t.success.text()), () -> {
            loading[0] = true;
            try {
                list.clearSelection();
                idField.setEditable(true);
                idField.setText("custom_" + (engine.nodeTypes().all().size() + 1));
                labelField.setText("Loại mới");
                selBuiltIn[0] = false;
                picker.loadFrom(new NodeType("custom", "Loại mới", "star", "accent", "", "", false));
            } finally {
                loading[0] = false;
            }
            refreshPreview.run();
            labelField.requestFocusInWindow();
            labelField.selectAll();
        });

        JButton cloneBtn = Ui.button("Nhân bản", Icons::copy, () -> {
            NodeType cur = list.getSelectedValue();
            if (cur == null) {
                return;
            }
            String newId = cur.id() + "_copy";
            int n = 2;
            while (engine.nodeTypes().contains(newId)) {
                newId = cur.id() + "_copy" + n++;
            }
            NodeType cloned = new NodeType(newId, cur.label() + " (Bản sao)", cur.iconKind(), cur.colorKey(),
                    cur.monogram(), cur.imagePath(), false, cur.customHex(), cur.monogramStyle(), cur.boxed());
            engine.nodeTypes().upsert(cloned);
            engine.saveNodeTypes();
            Icons.clearCache();
            modified[0] = true;
            reloadList.run();
            selectById(list, model, cloned.id());
        });

        JButton upBtn = Ui.button("Lên", Icons::arrowUp, () -> {
            NodeType cur = list.getSelectedValue();
            if (cur != null && engine.nodeTypes().move(cur.id(), -1)) {
                engine.saveNodeTypes();
                modified[0] = true;
                reloadList.run();
                selectById(list, model, cur.id());
            }
        });

        JButton downBtn = Ui.button("Xuống", Icons::arrowDown, () -> {
            NodeType cur = list.getSelectedValue();
            if (cur != null && engine.nodeTypes().move(cur.id(), 1)) {
                engine.saveNodeTypes();
                modified[0] = true;
                reloadList.run();
                selectById(list, model, cur.id());
            }
        });

        JButton delBtn = Ui.button("Xóa", t -> Icons.trash(t.danger.text()), () -> {
            NodeType nt = list.getSelectedValue();
            if (nt == null) {
                return;
            }
            if (NodeTypeCatalog.DEFAULT_ID.equals(nt.id())) {
                JOptionPane.showMessageDialog(d, "Không thể xóa loại mặc định 'folder'.",
                        Text.EXT_NAME, JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (JOptionPane.showConfirmDialog(d, "Xóa loại node '" + nt.label() + "' (" + nt.id() + ")?",
                    Text.EXT_NAME, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                if (engine.nodeTypes().remove(nt.id())) {
                    engine.saveNodeTypes();
                    Icons.clearCache();
                    modified[0] = true;
                    reloadList.run();
                }
            }
        });

        JButton exportBtn = Ui.button("Xuất JSON...", Icons::download, () -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("tmoc-node-types.json"));
            fc.setFileFilter(new FileNameExtensionFilter("JSON Pack (*.json)", "json"));
            if (fc.showSaveDialog(d) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
                try {
                    Files.writeString(fc.getSelectedFile().toPath(), engine.nodeTypes().toJson(), StandardCharsets.UTF_8);
                    JOptionPane.showMessageDialog(d, "Đã xuất bộ Loại Node & Icon thành công.", Text.EXT_NAME, JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(d, "Không xuất được file: " + ex.getMessage(), Text.EXT_NAME, JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JButton importBtn = Ui.button("Nhập JSON...", Icons::upload, () -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("JSON Pack (*.json)", "json"));
            if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
                try {
                    String raw = Files.readString(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8);
                    engine.nodeTypes().loadJson(raw);
                    engine.saveNodeTypes();
                    Icons.clearCache();
                    modified[0] = true;
                    reloadList.run();
                    JOptionPane.showMessageDialog(d, "Đã nhập bộ Loại Node & Icon thành công.", Text.EXT_NAME, JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(d, "File JSON không hợp lệ: " + ex.getMessage(), Text.EXT_NAME, JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JButton resetBtn = Ui.button("Mặc định", Icons::refresh, () -> {
            if (JOptionPane.showConfirmDialog(d, "Khôi phục toàn bộ 7 loại node mặc định (xóa loại tùy chỉnh)?",
                    Text.EXT_NAME, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                engine.nodeTypes().resetDefaults();
                engine.saveNodeTypes();
                Icons.clearCache();
                modified[0] = true;
                reloadList.run();
            }
        });

        JPanel leftToolsTop = Ui.flow(FlowLayout.LEFT, newBtn, cloneBtn, delBtn);
        JPanel leftToolsSub = Ui.flow(FlowLayout.LEFT, upBtn, downBtn, resetBtn);
        JPanel leftTools = new JPanel(new BorderLayout(0, 2));
        leftTools.add(leftToolsTop, BorderLayout.NORTH);
        leftTools.add(leftToolsSub, BorderLayout.SOUTH);

        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.add(leftTools, BorderLayout.NORTH);
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        left.add(Ui.flow(FlowLayout.LEFT, exportBtn, importBtn), BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(265, 460));

        // Right editor
        JPanel metaForm = new JPanel(new GridBagLayout());
        metaForm.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 4, 3, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0;
        metaForm.add(Ui.bold("Mã (ID):"), gc);
        gc.gridx = 1; gc.weightx = 0.4;
        metaForm.add(idField, gc);
        gc.gridx = 2; gc.weightx = 0;
        metaForm.add(Ui.bold("Tên hiển thị:"), gc);
        gc.gridx = 3; gc.weightx = 0.6;
        metaForm.add(labelField, gc);

        JPanel rightTop = new JPanel(new BorderLayout(0, 8));
        rightTop.setOpaque(false);
        rightTop.add(livePreview, BorderLayout.NORTH);
        rightTop.add(metaForm, BorderLayout.SOUTH);

        JPanel rightCenter = new JPanel(new BorderLayout(0, 8));
        rightCenter.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        rightCenter.add(rightTop, BorderLayout.NORTH);
        rightCenter.add(picker, BorderLayout.CENTER);

        JButton saveBtn = Ui.accent("Lưu loại này", t -> Icons.save(t.accent.onSolid()), t -> t.accent, () -> {
            String rawId = idField.getText().trim();
            String rawLbl = labelField.getText().trim();
            if (rawId.isEmpty() || rawLbl.isEmpty()) {
                JOptionPane.showMessageDialog(d, "Nhập đủ Mã (ID) và Tên hiển thị.", Text.EXT_NAME, JOptionPane.WARNING_MESSAGE);
                return;
            }
            NodeType saved = buildDraft.get();
            engine.nodeTypes().upsert(saved);
            engine.saveNodeTypes();
            Icons.clearCache();
            modified[0] = true;
            reloadList.run();
            selectById(list, model, saved.id());
        });

        JPanel right = new JPanel(new BorderLayout());
        right.add(rightCenter, BorderLayout.CENTER);
        right.add(Ui.flow(FlowLayout.RIGHT, saveBtn), BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(275);
        split.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));

        d.setLayout(new BorderLayout(0, Theme.SPACE_1));
        d.add(Ui.dialogHeader("Quản lý Loại Node & Biểu tượng (Icon Pack v2.1)",
                "Thêm, nhân bản, sắp xếp, xuất/nhập JSON pack — hỗ trợ 42 vector sắc nét, màu Hex tùy ý, Monogram 3 kiểu và ảnh PNG/JPG nhúng"),
                BorderLayout.NORTH);
        d.add(split, BorderLayout.CENTER);
        JPanel bottom = Ui.flow(FlowLayout.RIGHT, Ui.button("Đóng", d::dispose));
        bottom.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));
        d.add(bottom, BorderLayout.SOUTH);

        reloadList.run();
        Theme.track(d);
        d.setSize(new Dimension(880, 620));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return modified[0];
    }

    private static void selectById(JList<NodeType> list, DefaultListModel<NodeType> model, String id) {
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).id().equals(id)) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                break;
            }
        }
    }
}
