package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
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
import themythofcrawl.ui.kit.SegmentedToggle;
import themythofcrawl.ui.theme.Theme;

/**
 * Full CRUD manager for {@link NodeTypeCatalog}: add / edit / delete / reset node types
 * (Folder, Link, Button, Icon, Tab, Modal, Form & custom types) with vector icon, 1–3 char monogram,
 * or custom PNG/JPG/GIF image icon.
 */
final class NodeTypeManagerDialog {
    private NodeTypeManagerDialog() {
    }

    static boolean show(Component parent, Engine engine) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, "Quản lý Loại Node & Biểu tượng (Icon)", JDialog.ModalityType.APPLICATION_MODAL);
        final boolean[] modified = {false};

        DefaultListModel<NodeType> model = new DefaultListModel<>();
        JList<NodeType> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(Theme.current().scaled(28));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object val, int index, boolean sel, boolean focus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(l, val, index, sel, focus);
                Theme t = Theme.current();
                if (val instanceof NodeType nt) {
                    lbl.setIcon(Icons.nodeIcon(nt, t, 14));
                    lbl.setText(nt.label() + "  [" + nt.id() + "]" + (nt.builtIn() ? "" : " *"));
                    lbl.setFont(t.smallFont);
                    lbl.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
                }
                return lbl;
            }
        });

        // Editor state
        JTextField idField = new JTextField(16);
        JTextField labelField = new JTextField(20);
        JTextField monoField = new JTextField(6);
        JTextField imageField = new JTextField(22);
        final String[] selColor = {"info"};
        final String[] selVector = {"folder"};
        final boolean[] selBuiltIn = {false};
        final boolean[] loading = {false};

        JLabel previewBox = new JLabel("", JLabel.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Theme t = Theme.current();
                g.setColor(t.surfaceAlt);
                g.fillRect(0, 0, getWidth() - 1, getHeight() - 1);
                g.setColor(t.border);
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                super.paintComponent(g);
            }
        };
        previewBox.setPreferredSize(new Dimension(170, 32));
        previewBox.setOpaque(false);

        SegmentedToggle iconMode = new SegmentedToggle(
                new SegmentedToggle.Option("Vector", "Biểu tượng vector sắc nét có sẵn", t -> t.info, (t, s) -> null, false),
                new SegmentedToggle.Option("Ký tự (1-3)", "Ô vuông monogram 1-3 chữ cái", t -> t.info, (t, s) -> null, false),
                new SegmentedToggle.Option("Ảnh PNG/JPG", "File ảnh tùy chọn từ máy (scale 14x14)", t -> t.info, (t, s) -> null, false));
        iconMode.setFixedHeight(24);

        CardLayout modeCards = new CardLayout();
        JPanel modePanel = new JPanel(modeCards);

        java.util.function.Supplier<NodeType> buildDraft = () -> {
            int mode = iconMode.selectedIndex();
            String mono = mode == 1 ? monoField.getText().trim().toUpperCase(Locale.ROOT) : "";
            String img = mode == 2 ? imageField.getText().trim() : "";
            return new NodeType(
                    idField.getText().trim(),
                    labelField.getText().trim(),
                    selVector[0],
                    selColor[0],
                    mono,
                    img,
                    selBuiltIn[0]);
        };

        Runnable refreshPreview = () -> {
            NodeType draft = buildDraft.get();
            Theme t = Theme.current();
            previewBox.setIcon(Icons.nodeIcon(draft, t, 16));
            previewBox.setText(" " + draft.label());
            previewBox.setFont(t.smallBoldFont);
            previewBox.setForeground(Icons.tokenForKey(t, draft.colorKey()).text());
            previewBox.repaint();
        };

        // Vector icon grid (sharp 30x30 cells)
        JPanel vectorGrid = new JPanel(new GridLayout(0, 6, 4, 4));
        vectorGrid.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        List<JPanel> vectorCells = new java.util.ArrayList<>();
        Runnable repaintVectors = () -> {
            for (JPanel cell : vectorCells) {
                cell.repaint();
            }
        };
        for (String vk : NodeTypeCatalog.VECTOR_ICONS) {
            JPanel cell = new JPanel(new BorderLayout()) {
                @Override
                protected void paintComponent(Graphics g) {
                    Theme t = Theme.current();
                    boolean active = vk.equals(selVector[0]);
                    Theme.Token tok = Icons.tokenForKey(t, selColor[0]);
                    g.setColor(active ? tok.fillStrong() : t.surface);
                    g.fillRect(0, 0, getWidth() - 1, getHeight() - 1);
                    g.setColor(active ? tok.solidFill() : t.border);
                    g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                    Icon ic = Icons.vectorIcon(vk, active ? tok.text() : t.fg, 16);
                    ic.paintIcon(this, g, (getWidth() - ic.getIconWidth()) / 2, (getHeight() - ic.getIconHeight()) / 2);
                }
            };
            cell.setPreferredSize(new Dimension(34, 30));
            cell.setToolTipText("Icon: " + vk);
            cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            cell.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    selVector[0] = vk;
                    repaintVectors.run();
                    refreshPreview.run();
                }
            });
            vectorCells.add(cell);
            vectorGrid.add(cell);
        }

        // Color swatches (sharp 24x22 cells)
        JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        List<JPanel> colorCells = new java.util.ArrayList<>();
        Runnable repaintColors = () -> {
            for (JPanel c : colorCells) {
                c.repaint();
            }
            repaintVectors.run();
        };
        for (String ck : NodeTypeCatalog.COLOR_KEYS) {
            JPanel swatch = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    Theme t = Theme.current();
                    Theme.Token tok = Icons.tokenForKey(t, ck);
                    boolean active = ck.equals(selColor[0]);
                    Graphics2D g2 = (Graphics2D) g;
                    g2.setColor(tok.solidFill());
                    g2.fillRect(2, 2, getWidth() - 5, getHeight() - 5);
                    g2.setColor(active ? t.fg : t.border);
                    g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                    if (active) {
                        g2.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
                    }
                }
            };
            swatch.setPreferredSize(new Dimension(24, 22));
            swatch.setToolTipText("Màu: " + ck);
            swatch.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            swatch.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    selColor[0] = ck;
                    repaintColors.run();
                    refreshPreview.run();
                }
            });
            colorCells.add(swatch);
            colorRow.add(swatch);
        }

        // Monogram panel
        JPanel monoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        monoPanel.add(new JLabel("Ký tự viết tắt (1-3 ký tự, VD: API, BTN, LNK):"));
        monoPanel.add(monoField);

        // Image panel
        JPanel imgPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        imgPanel.add(new JLabel("File ảnh:"));
        imgPanel.add(imageField);
        JButton browseImg = Ui.button("Chọn ảnh...", () -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Hình ảnh (PNG, JPG, GIF)", "png", "jpg", "jpeg", "gif"));
            if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                if (f != null) {
                    imageField.setText(f.getAbsolutePath());
                    refreshPreview.run();
                }
            }
        });
        imgPanel.add(browseImg);

        modePanel.add(vectorGrid, "0");
        modePanel.add(monoPanel, "1");
        modePanel.add(imgPanel, "2");
        iconMode.onChange(idx -> {
            modeCards.show(modePanel, String.valueOf(idx));
            refreshPreview.run();
        });

        DocumentListener liveDoc = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { if (!loading[0]) refreshPreview.run(); }
            @Override public void removeUpdate(DocumentEvent e) { if (!loading[0]) refreshPreview.run(); }
            @Override public void changedUpdate(DocumentEvent e) { if (!loading[0]) refreshPreview.run(); }
        };
        idField.getDocument().addDocumentListener(liveDoc);
        labelField.getDocument().addDocumentListener(liveDoc);
        monoField.getDocument().addDocumentListener(liveDoc);
        imageField.getDocument().addDocumentListener(liveDoc);

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
                selVector[0] = nt.iconKind();
                selColor[0] = nt.colorKey();
                selBuiltIn[0] = nt.builtIn();
                monoField.setText(nt.monogram());
                imageField.setText(nt.imagePath());
                int mode = !Text.blank(nt.imagePath()) ? 2 : (!Text.blank(nt.monogram()) ? 1 : 0);
                iconMode.setSelectedIndex(mode, false);
                modeCards.show(modePanel, String.valueOf(mode));
                repaintColors.run();
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

        // Left pane
        JButton newBtn = Ui.button("Thêm mới", t -> Icons.plus(t.success.text()), () -> {
            loading[0] = true;
            try {
                list.clearSelection();
                idField.setEditable(true);
                idField.setText("custom_" + (engine.nodeTypes().all().size() + 1));
                labelField.setText("Loại mới");
                selVector[0] = "star";
                selColor[0] = "accent";
                selBuiltIn[0] = false;
                monoField.setText("");
                imageField.setText("");
                iconMode.setSelectedIndex(0, false);
                modeCards.show(modePanel, "0");
                repaintColors.run();
            } finally {
                loading[0] = false;
            }
            refreshPreview.run();
            labelField.requestFocusInWindow();
            labelField.selectAll();
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
        JButton resetBtn = Ui.button("Mặc định", t -> Icons.refresh(t.fg), () -> {
            if (JOptionPane.showConfirmDialog(d, "Khôi phục toàn bộ 7 loại node mặc định (xóa loại tùy chỉnh)?",
                    Text.EXT_NAME, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                engine.nodeTypes().resetDefaults();
                engine.saveNodeTypes();
                Icons.clearCache();
                modified[0] = true;
                reloadList.run();
            }
        });

        JPanel leftTools = Ui.flow(FlowLayout.LEFT, newBtn, delBtn, resetBtn);
        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.add(leftTools, BorderLayout.NORTH);
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        left.setPreferredSize(new Dimension(235, 360));

        // Right editor form
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        form.add(new JLabel("Xem trước:"), gc);
        gc.gridx = 1; gc.weightx = 1;
        JPanel prevWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        prevWrap.add(previewBox);
        form.add(prevWrap, gc);

        row++;
        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        form.add(new JLabel("Mã (ID):"), gc);
        gc.gridx = 1; gc.weightx = 1;
        form.add(idField, gc);

        row++;
        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        form.add(new JLabel("Tên hiển thị:"), gc);
        gc.gridx = 1; gc.weightx = 1;
        form.add(labelField, gc);

        row++;
        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        form.add(new JLabel("Màu sắc:"), gc);
        gc.gridx = 1; gc.weightx = 1;
        form.add(colorRow, gc);

        row++;
        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        form.add(new JLabel("Kiểu Icon:"), gc);
        gc.gridx = 1; gc.weightx = 1;
        JPanel modeWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        modeWrap.add(iconMode);
        form.add(modeWrap, gc);

        row++;
        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2; gc.weightx = 1; gc.weighty = 1;
        gc.fill = GridBagConstraints.BOTH;
        form.add(modePanel, gc);

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
            for (int i = 0; i < model.size(); i++) {
                if (model.get(i).id().equals(saved.id())) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        });
        JPanel formBottom = Ui.flow(FlowLayout.RIGHT, saveBtn);
        JPanel right = new JPanel(new BorderLayout());
        right.add(form, BorderLayout.CENTER);
        right.add(formBottom, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(245);
        split.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));

        d.setLayout(new BorderLayout(0, Theme.SPACE_1));
        d.add(Ui.dialogHeader("Quản lý Loại Node & Icon",
                "Thêm, sửa, xóa loại phần tử UI (Folder, Link, Button, Icon, Tab, Modal, Form...) và tùy chỉnh biểu tượng"),
                BorderLayout.NORTH);
        d.add(split, BorderLayout.CENTER);
        JPanel bottom = Ui.flow(FlowLayout.RIGHT, Ui.button("Đóng", d::dispose));
        bottom.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));
        d.add(bottom, BorderLayout.SOUTH);

        reloadList.run();
        Theme.track(d);
        d.setSize(new Dimension(740, 500));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return modified[0];
    }
}
