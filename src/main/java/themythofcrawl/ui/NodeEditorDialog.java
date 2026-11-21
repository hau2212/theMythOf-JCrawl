package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.model.NodeTypeCatalog;
import themythofcrawl.model.NodeTypeCatalog.NodeType;
import themythofcrawl.ui.kit.IconPickerPanel;
import themythofcrawl.ui.kit.RichHoverCard;
import themythofcrawl.ui.kit.SegmentedToggle;
import themythofcrawl.ui.theme.FontManager;
import themythofcrawl.ui.theme.Theme;

/**
 * Sharp minimalist dialog v2.1 for creating a sub-node / sibling node or changing an existing node's type &amp; icon.
 * Features uniform preset tiles, inline custom {@link IconPickerPanel} (42 icons, Hex color, monogram, PNG upload),
 * and a live Tree + Destination Breadcrumb preview.
 */
final class NodeEditorDialog {
    record AddResult(String fullPath, String typeId, boolean setAsDestination) {
    }

    private NodeEditorDialog() {
    }

    static AddResult showAdd(Component parent, Engine engine, String basePath, boolean defaultSibling, boolean defaultSetDest) {
        String base = Text.blank(basePath) ? engine.activeBranch().name() : Hierarchy.normalize(basePath);
        String parentOfBase = Hierarchy.depth(base) > 1 ? Hierarchy.parent(base) : "";
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(Ui.ownerWindow(owner), "Thêm mục vào cấu trúc Crawl", JDialog.ModalityType.APPLICATION_MODAL);

        final AddResult[] out = {null};
        final String[] selectedType = {NodeTypeCatalog.DEFAULT_ID};
        final boolean[] customMode = {false};

        SegmentedToggle relation = new SegmentedToggle(
                new SegmentedToggle.Option("Mục con (bên dưới)", "Tạo cấp con bên dưới nhánh hiện tại", t -> t.info, (t, s) -> null, false),
                new SegmentedToggle.Option("Cùng cấp (tiếp theo)", "Tạo nhánh cùng cấp kế tiếp", t -> t.accent, (t, s) -> null, false));
        relation.setFixedHeight(26);
        relation.setSelectedIndex(defaultSibling ? 1 : 0);

        JTextField nameField = new JTextField(28);
        nameField.setPreferredSize(new Dimension(320, Theme.current().scaled(28)));
        JCheckBox setDestBox = new JCheckBox("Đặt làm Đích (Destination) cho request mới luôn", defaultSetDest);

        IconPickerPanel customPicker = new IconPickerPanel();
        customPicker.loadFrom(engine.nodeTypes().byId(selectedType[0]));
        JTextField customTypeLabelField = new JTextField(16);
        customTypeLabelField.setToolTipText("Tên hiển thị nếu muốn lưu thành loại dùng lại (để trống sẽ tự đặt theo tên mục)");
        JPanel customDrawer = new JPanel(new BorderLayout(0, 6));
        customDrawer.setOpaque(false);
        customDrawer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.current().border, 1),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        JPanel customNameRow = Ui.flow(FlowLayout.LEFT,
                Ui.bold("Tên loại custom (tùy chọn):"), customTypeLabelField,
                Ui.muted("Sẽ tự lưu vào danh mục để tái sử dụng"));
        customDrawer.add(customNameRow, BorderLayout.NORTH);
        customDrawer.add(customPicker, BorderLayout.CENTER);
        customDrawer.setVisible(false);

        java.util.function.Supplier<String> computePath = () -> {
            String raw = nameField.getText().trim();
            if (raw.isEmpty()) {
                return "";
            }
            if (relation.selectedIndex() == 1) {
                return parentOfBase.isEmpty() ? Hierarchy.normalize(raw) : Hierarchy.append(parentOfBase, raw);
            }
            return Hierarchy.append(base, raw);
        };

        java.util.function.Supplier<NodeType> activePreviewType = () -> {
            if (customMode[0]) {
                String lbl = customTypeLabelField.getText().trim();
                if (lbl.isEmpty()) {
                    lbl = nameField.getText().trim().isEmpty() ? "Tùy chỉnh" : nameField.getText().trim();
                }
                return customPicker.buildNodeType("custom_preview", lbl, false);
            }
            return engine.nodeTypes().byId(selectedType[0]);
        };

        LiveNodePreview previewPanel = new LiveNodePreview();
        Runnable updatePreview = () -> {
            String p = computePath.get();
            String leaf = nameField.getText().trim().isEmpty() ? "Tên mục mới" : Hierarchy.leaf(p.isEmpty() ? nameField.getText().trim() : p);
            String fullDisplay = p.isEmpty()
                    ? (relation.selectedIndex() == 1 ? (parentOfBase.isEmpty() ? leaf : parentOfBase + " > " + leaf) : base + " > " + leaf)
                    : p;
            previewPanel.update(activePreviewType.get(), leaf, fullDisplay);
        };

        relation.onChange(i -> updatePreview.run());
        DocumentListener doc = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updatePreview.run(); }
            @Override public void removeUpdate(DocumentEvent e) { updatePreview.run(); }
            @Override public void changedUpdate(DocumentEvent e) { updatePreview.run(); }
        };
        nameField.getDocument().addDocumentListener(doc);
        customTypeLabelField.getDocument().addDocumentListener(doc);
        customPicker.onChange(updatePreview);

        JPanel tileGrid = new JPanel(new GridLayout(0, 5, 6, 6));
        tileGrid.setOpaque(false);
        Runnable[] rebuildTiles = new Runnable[1];
        rebuildTiles[0] = () -> {
            tileGrid.removeAll();
            List<NodeType> all = engine.nodeTypes().all();
            if (!engine.nodeTypes().contains(selectedType[0]) && !all.isEmpty()) {
                selectedType[0] = all.get(0).id();
            }
            for (NodeType nt : all) {
                tileGrid.add(typeTile(nt, selectedType, customMode, () -> {
                    customMode[0] = false;
                    customDrawer.setVisible(false);
                    customPicker.loadFrom(nt);
                    tileGrid.repaint();
                    updatePreview.run();
                    if (nameField.getText().isBlank()) {
                        nameField.requestFocusInWindow();
                    }
                    d.pack();
                }));
            }
            tileGrid.revalidate();
            tileGrid.repaint();
        };
        rebuildTiles[0].run();

        JButton toggleCustomBtn = Ui.button("Tùy chỉnh Icon & Màu...", Icons::palette, () -> {
            customMode[0] = !customMode[0];
            if (customMode[0]) {
                customPicker.loadFrom(engine.nodeTypes().byId(selectedType[0]));
            }
            customDrawer.setVisible(customMode[0]);
            tileGrid.repaint();
            updatePreview.run();
            d.pack();
        });
        RichHoverCard.install(toggleCustomBtn, new RichHoverCard.Spec(
                "Tùy chỉnh Icon & Màu riêng",
                "Mở nhanh bộ 42 biểu tượng vector, bảng mã màu Hex tùy ý, Monogram 1-3 ký tự hoặc tải ảnh PNG/JPG ngay tại đây.",
                null,
                t -> Icons.palette(t.accent.text(), 15),
                t -> t.accent,
                () -> customMode[0] ? "Đang bật chế độ tùy chỉnh" : "Bấm để mở"));

        JButton manageTypesBtn = Ui.button("Quản lý danh mục...", Icons::gear, () -> {
            if (NodeTypeManagerDialog.show(d, engine)) {
                rebuildTiles[0].run();
                updatePreview.run();
            }
        });

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        JPanel relRow = Ui.flow(FlowLayout.LEFT, Ui.bold("Vị trí:"), relation);
        relRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(relRow);
        body.add(Box.createVerticalStrut(6));

        JPanel nameRow = new JPanel(new BorderLayout(8, 0));
        nameRow.setOpaque(false);
        nameRow.add(Ui.bold("Tên mục:"), BorderLayout.WEST);
        nameRow.add(nameField, BorderLayout.CENTER);
        nameRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        body.add(nameRow);
        body.add(Box.createVerticalStrut(8));

        JPanel typeHeader = new JPanel(new BorderLayout(8, 0));
        typeHeader.setOpaque(false);
        typeHeader.add(Ui.bold("Loại phần tử UI & Biểu tượng:"), BorderLayout.WEST);
        typeHeader.add(Ui.flow(FlowLayout.RIGHT, toggleCustomBtn, manageTypesBtn), BorderLayout.EAST);
        typeHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(typeHeader);
        body.add(Box.createVerticalStrut(6));

        JScrollPane tileScroll = new JScrollPane(tileGrid);
        tileScroll.setBorder(BorderFactory.createLineBorder(Theme.current().border, 1));
        tileScroll.setPreferredSize(new Dimension(580, 126));
        tileScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(tileScroll);
        body.add(Box.createVerticalStrut(6));

        customDrawer.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(customDrawer);
        body.add(Box.createVerticalStrut(8));

        previewPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(previewPanel);
        body.add(Box.createVerticalStrut(6));

        setDestBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(setDestBox);

        Runnable ok = () -> {
            String full = computePath.get();
            if (full.isEmpty() || full.replace(">", "").isBlank()) {
                nameField.requestFocusInWindow();
                return;
            }
            String chosenTypeId = selectedType[0];
            if (customMode[0]) {
                chosenTypeId = persistCustomType(engine, customPicker, customTypeLabelField.getText().trim(), Hierarchy.leaf(full));
            }
            out[0] = new AddResult(full, chosenTypeId, setDestBox.isSelected());
            d.dispose();
        };

        JButton okBtn = Ui.accent("Tạo mục", t -> Icons.check(t.accent.onSolid()), t -> t.accent, ok);
        JPanel footer = Ui.flow(FlowLayout.RIGHT, Ui.button("Hủy", d::dispose), okBtn);
        footer.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        d.setLayout(new BorderLayout());
        d.add(Ui.dialogHeader("Thêm phần tử vào nhánh: " + base,
                "Chọn nhanh loại có sẵn hoặc tự tùy chỉnh Icon (42 vector / Chữ viết tắt / Ảnh PNG) & Màu Hex — hỗ trợ nhập nhiều cấp ngăn bởi '>'"),
                BorderLayout.NORTH);
        d.add(body, BorderLayout.CENTER);
        d.add(footer, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(okBtn);
        updatePreview.run();

        Theme.track(d);
        d.pack();
        d.setMinimumSize(new Dimension(640, 440));
        d.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(nameField::requestFocusInWindow);
        d.setVisible(true);
        return out[0];
    }

    /** Dialog for changing the NodeType &amp; Icon of an existing branch path, including instant custom icon override. */
    static String showEditType(Component parent, Engine engine, String existingPath) {
        if (Text.blank(existingPath)) {
            return null;
        }
        String norm = Hierarchy.normalize(existingPath);
        String leaf = Hierarchy.leaf(norm);
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(Ui.ownerWindow(owner), "Đổi loại & Icon nhánh", JDialog.ModalityType.APPLICATION_MODAL);

        final String[] selectedType = {engine.nodeTypeOf(norm).id()};
        final boolean[] customMode = {false};
        final String[] out = {null};

        IconPickerPanel customPicker = new IconPickerPanel();
        customPicker.loadFrom(engine.nodeTypeOf(norm));
        JTextField customLabelField = new JTextField(16);
        JPanel customDrawer = new JPanel(new BorderLayout(0, 6));
        customDrawer.setOpaque(false);
        customDrawer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.current().border, 1),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        customDrawer.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Tên loại custom:"), customLabelField), BorderLayout.NORTH);
        customDrawer.add(customPicker, BorderLayout.CENTER);
        customDrawer.setVisible(false);

        LiveNodePreview previewPanel = new LiveNodePreview();
        Runnable updatePreview = () -> {
            NodeType nt = customMode[0]
                    ? customPicker.buildNodeType("custom_preview",
                            customLabelField.getText().isBlank() ? leaf : customLabelField.getText().trim(), false)
                    : engine.nodeTypes().byId(selectedType[0]);
            previewPanel.update(nt, leaf, norm);
        };
        customPicker.onChange(updatePreview);

        JPanel tileGrid = new JPanel(new GridLayout(0, 5, 6, 6));
        tileGrid.setOpaque(false);
        Runnable[] rebuildTiles = new Runnable[1];
        rebuildTiles[0] = () -> {
            tileGrid.removeAll();
            for (NodeType nt : engine.nodeTypes().all()) {
                tileGrid.add(typeTile(nt, selectedType, customMode, () -> {
                    customMode[0] = false;
                    customDrawer.setVisible(false);
                    customPicker.loadFrom(nt);
                    tileGrid.repaint();
                    updatePreview.run();
                    d.pack();
                }));
            }
            tileGrid.revalidate();
            tileGrid.repaint();
        };
        rebuildTiles[0].run();

        JButton toggleCustomBtn = Ui.button("Tùy chỉnh Icon & Màu...", Icons::palette, () -> {
            customMode[0] = !customMode[0];
            customDrawer.setVisible(customMode[0]);
            tileGrid.repaint();
            updatePreview.run();
            d.pack();
        });
        JButton manageBtn = Ui.button("Quản lý danh mục...", Icons::gear, () -> {
            if (NodeTypeManagerDialog.show(d, engine)) {
                rebuildTiles[0].run();
                updatePreview.run();
            }
        });

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        JPanel topRow = new JPanel(new BorderLayout(8, 0));
        topRow.setOpaque(false);
        topRow.add(Ui.bold("Chọn loại & biểu tượng cho nhánh:"), BorderLayout.WEST);
        topRow.add(Ui.flow(FlowLayout.RIGHT, toggleCustomBtn, manageBtn), BorderLayout.EAST);
        topRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(topRow);
        body.add(Box.createVerticalStrut(6));

        JScrollPane sp = new JScrollPane(tileGrid);
        sp.setBorder(BorderFactory.createLineBorder(Theme.current().border, 1));
        sp.setPreferredSize(new Dimension(580, 126));
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(sp);
        body.add(Box.createVerticalStrut(6));

        customDrawer.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(customDrawer);
        body.add(Box.createVerticalStrut(8));

        previewPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(previewPanel);

        JButton okBtn = Ui.accent("Áp dụng", t -> Icons.check(t.accent.onSolid()), t -> t.accent, () -> {
            if (customMode[0]) {
                out[0] = persistCustomType(engine, customPicker, customLabelField.getText().trim(), leaf);
            } else {
                out[0] = selectedType[0];
            }
            d.dispose();
        });
        JPanel footer = Ui.flow(FlowLayout.RIGHT, Ui.button("Hủy", d::dispose), okBtn);
        footer.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        d.setLayout(new BorderLayout());
        d.add(Ui.dialogHeader("Đổi loại & Icon: " + norm,
                "Thay đổi biểu tượng hiển thị trên Cây nhánh và thanh Đích mà không ảnh hưởng API bên trong"),
                BorderLayout.NORTH);
        d.add(body, BorderLayout.CENTER);
        d.add(footer, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(okBtn);
        updatePreview.run();

        Theme.track(d);
        d.pack();
        d.setMinimumSize(new Dimension(640, 360));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return out[0];
    }

    private static String persistCustomType(Engine engine, IconPickerPanel picker, String customLabel, String fallbackLeaf) {
        String label = !customLabel.isBlank() ? customLabel : (fallbackLeaf.isBlank() ? "Custom" : fallbackLeaf);
        String slug = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        if (slug.isEmpty()) {
            slug = "custom";
        }
        String id = "custom_" + slug;
        int n = 2;
        while (engine.nodeTypes().contains(id)) {
            id = "custom_" + slug + "_" + n++;
        }
        NodeType nt = picker.buildNodeType(id, label, false);
        engine.nodeTypes().upsert(nt);
        engine.saveNodeTypes();
        Icons.clearCache();
        return nt.id();
    }

    private static JPanel typeTile(NodeType nt, String[] selectedRef, boolean[] customModeRef, Runnable onPick) {
        JPanel tile = new JPanel() {
            private boolean hovered;

            {
                setPreferredSize(new Dimension(106, 52));
                setOpaque(false);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
                    @Override public void mouseClicked(MouseEvent e) {
                        selectedRef[0] = nt.id();
                        onPick.run();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Theme t = Theme.current();
                Theme.Token tok = Icons.tokenForNodeType(t, nt);
                boolean sel = !customModeRef[0] && nt.id().equals(selectedRef[0]);
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    FontManager.applyTextHints(g2);
                    int w = getWidth();
                    int h = getHeight();
                    Color bg = sel ? tok.fillStrong() : (hovered ? t.hover : t.surface);
                    Color bd = sel ? tok.solidFill() : (hovered ? tok.border() : t.border);
                    g2.setColor(bg);
                    g2.fillRect(0, 0, w - 1, h - 1);
                    g2.setColor(bd);
                    g2.drawRect(0, 0, w - 1, h - 1);
                    if (sel) {
                        g2.fillRect(0, h - 3, w, 3);
                    }
                    Icon ic = Icons.nodeIcon(nt, t, 16);
                    ic.paintIcon(this, g2, (w - ic.getIconWidth()) / 2, 7);

                    g2.setFont(sel ? t.smallBoldFont : t.smallFont);
                    g2.setColor(sel ? tok.text() : t.fg);
                    FontMetrics fm = g2.getFontMetrics();
                    String lbl = nt.label();
                    int maxW = w - 8;
                    if (fm.stringWidth(lbl) > maxW) {
                        int cut = lbl.length();
                        while (cut > 2 && fm.stringWidth(lbl.substring(0, cut) + "..") > maxW) {
                            cut--;
                        }
                        lbl = lbl.substring(0, cut) + "..";
                    }
                    g2.drawString(lbl, (w - fm.stringWidth(lbl)) / 2, h - 9);
                } finally {
                    g2.dispose();
                }
            }
        };
        RichHoverCard.install(tile, new RichHoverCard.Spec(
                nt.label(),
                "Mã loại: " + nt.id() + (nt.builtIn() ? " (Mặc định)" : " (Tùy chỉnh)"),
                null,
                t -> Icons.nodeIcon(nt, t, 16),
                t -> Icons.tokenForNodeType(t, nt),
                () -> !customModeRef[0] && nt.id().equals(selectedRef[0]) ? "Đang chọn" : "Bấm để áp dụng loại này"));
        return tile;
    }

    /** Live dual preview showing exact appearance in the Tree and on the Destination Breadcrumb bar. */
    static final class LiveNodePreview extends JPanel {
        private final JLabel treeRowLbl = new JLabel();
        private final JLabel crumbLbl = new JLabel();
        private final JLabel pathLbl = Ui.muted("");

        LiveNodePreview() {
            super(new BorderLayout(8, 4));
            setOpaque(true);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.current().border, 1),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)));

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
            top.setOpaque(false);
            JLabel tHead = Ui.muted("Xem trước Cây:");
            JLabel cHead = Ui.muted("Thanh Đích:");
            crumbLbl.setOpaque(true);
            crumbLbl.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            top.add(tHead);
            top.add(treeRowLbl);
            top.add(cHead);
            top.add(crumbLbl);

            add(top, BorderLayout.NORTH);
            add(pathLbl, BorderLayout.SOUTH);
            applyTheme();
        }

        void applyTheme() {
            Theme t = Theme.current();
            setBackground(t.surfaceAlt);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(t.border, 1),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            treeRowLbl.setFont(t.boldFont);
            treeRowLbl.setForeground(t.fg);
            crumbLbl.setFont(t.smallBoldFont);
        }

        void update(NodeType nt, String leafName, String fullPath) {
            Theme t = Theme.current();
            applyTheme();
            Theme.Token tok = Icons.tokenForNodeType(t, nt);
            treeRowLbl.setIcon(Icons.nodeIcon(nt, t, 15));
            treeRowLbl.setText(" " + leafName + "   (0 API)");

            crumbLbl.setIcon(Icons.nodeIcon(nt, t, 14));
            crumbLbl.setText(" " + leafName);
            crumbLbl.setBackground(tok.fill());
            crumbLbl.setForeground(tok.text());
            crumbLbl.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(tok.border(), 1),
                    BorderFactory.createEmptyBorder(3, 8, 3, 8)));

            pathLbl.setText("Đường dẫn kết quả: " + fullPath);
            repaint();
        }
    }
}
