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
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

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
import themythofcrawl.ui.kit.SegmentedToggle;
import themythofcrawl.ui.theme.Theme;

/**
 * Sharp minimalist dialog for creating a sub-node / sibling node with an explicit UI element type
 * (Folder, Link, Button, Icon, Tab, Modal, Form, or custom) or changing the type/icon of an existing branch.
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
        JDialog d = new JDialog(owner, "Thêm mục vào cấu trúc Crawl", JDialog.ModalityType.APPLICATION_MODAL);

        final AddResult[] out = {null};
        final String[] selectedType = {NodeTypeCatalog.DEFAULT_ID};

        SegmentedToggle relation = new SegmentedToggle(
                new SegmentedToggle.Option("Mục con (bên dưới)", "Tạo cấp con bên dưới nhánh hiện tại", t -> t.info, (t, s) -> null, false),
                new SegmentedToggle.Option("Cùng cấp (tiếp theo)", "Tạo nhánh cùng cấp (nút / action kế tiếp)", t -> t.accent, (t, s) -> null, false));
        relation.setFixedHeight(24);
        relation.setSelectedIndex(defaultSibling ? 1 : 0);

        JTextField nameField = new JTextField(28);
        nameField.setPreferredSize(new Dimension(320, Theme.current().scaled(26)));
        JLabel pathPreview = Ui.muted("");
        JCheckBox setDestBox = new JCheckBox("Đặt làm Đích (Destination) cho request mới luôn", defaultSetDest);

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

        Runnable updatePreview = () -> {
            String p = computePath.get();
            pathPreview.setText(p.isEmpty()
                    ? "Đường dẫn kết quả: " + (relation.selectedIndex() == 1 ? (parentOfBase.isEmpty() ? "(cấp gốc)" : parentOfBase) : base) + " > ..."
                    : "Đường dẫn kết quả: " + p);
        };

        relation.onChange(i -> updatePreview.run());
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updatePreview.run(); }
            @Override public void removeUpdate(DocumentEvent e) { updatePreview.run(); }
            @Override public void changedUpdate(DocumentEvent e) { updatePreview.run(); }
        });

        JPanel tileGrid = new JPanel(new GridLayout(0, 4, 6, 6));
        tileGrid.setOpaque(false);
        Runnable[] rebuildTiles = new Runnable[1];
        rebuildTiles[0] = () -> {
            tileGrid.removeAll();
            List<NodeType> all = engine.nodeTypes().all();
            if (!engine.nodeTypes().contains(selectedType[0]) && !all.isEmpty()) {
                selectedType[0] = all.get(0).id();
            }
            for (NodeType nt : all) {
                tileGrid.add(typeTile(nt, selectedType, () -> {
                    tileGrid.repaint();
                    if (nameField.getText().isBlank()) {
                        nameField.requestFocusInWindow();
                    }
                }));
            }
            tileGrid.revalidate();
            tileGrid.repaint();
        };
        rebuildTiles[0].run();

        JButton manageTypesBtn = Ui.button("Quản lý loại & Icon...", t -> Icons.gear(t.fg), () -> {
            if (NodeTypeManagerDialog.show(d, engine)) {
                rebuildTiles[0].run();
            }
        });

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        JPanel relRow = Ui.flow(FlowLayout.LEFT, new JLabel("Vị trí:"), relation);
        relRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(relRow);
        body.add(Box.createVerticalStrut(6));

        JPanel typeHeader = new JPanel(new BorderLayout());
        typeHeader.setOpaque(false);
        typeHeader.add(Ui.bold("Chọn loại phần tử UI (hiện biểu tượng ở Cây & thanh Đích):"), BorderLayout.WEST);
        typeHeader.add(manageTypesBtn, BorderLayout.EAST);
        typeHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(typeHeader);
        body.add(Box.createVerticalStrut(6));

        JScrollPane tileScroll = new JScrollPane(tileGrid);
        tileScroll.setBorder(BorderFactory.createEmptyBorder());
        tileScroll.setPreferredSize(new Dimension(500, 150));
        tileScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(tileScroll);
        body.add(Box.createVerticalStrut(8));

        JPanel nameRow = new JPanel(new BorderLayout(8, 0));
        nameRow.setOpaque(false);
        nameRow.add(Ui.bold("Tên mục:"), BorderLayout.WEST);
        nameRow.add(nameField, BorderLayout.CENTER);
        nameRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        body.add(nameRow);
        body.add(Box.createVerticalStrut(4));

        pathPreview.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(pathPreview);
        body.add(Box.createVerticalStrut(6));

        setDestBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(setDestBox);

        Runnable ok = () -> {
            String full = computePath.get();
            if (full.isEmpty() || full.replace(">", "").isBlank()) {
                nameField.requestFocusInWindow();
                return;
            }
            out[0] = new AddResult(full, selectedType[0], setDestBox.isSelected());
            d.dispose();
        };

        JButton okBtn = Ui.accent("Tạo mục", t -> Icons.check(t.accent.onSolid()), t -> t.accent, ok);
        JPanel footer = Ui.flow(FlowLayout.RIGHT, Ui.button("Hủy", d::dispose), okBtn);
        footer.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        d.setLayout(new BorderLayout());
        d.add(Ui.dialogHeader("Thêm phần tử vào nhánh: " + base,
                "Chọn loại (Thư mục, Link, Button, Icon, Tab, Modal, Form...) — hỗ trợ nhập nhiều cấp ngăn bởi '>'"),
                BorderLayout.NORTH);
        d.add(body, BorderLayout.CENTER);
        d.add(footer, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(okBtn);
        updatePreview.run();

        Theme.track(d);
        d.setSize(new Dimension(560, 420));
        d.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(nameField::requestFocusInWindow);
        d.setVisible(true);
        return out[0];
    }

    /** Dialog for changing the NodeType & Icon of an existing branch path. */
    static String showEditType(Component parent, Engine engine, String existingPath) {
        if (Text.blank(existingPath)) {
            return null;
        }
        String norm = Hierarchy.normalize(existingPath);
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, "Đổi loại & Icon nhánh", JDialog.ModalityType.APPLICATION_MODAL);

        final String[] selectedType = {engine.nodeTypeOf(norm).id()};
        final String[] out = {null};

        JPanel tileGrid = new JPanel(new GridLayout(0, 4, 6, 6));
        tileGrid.setOpaque(false);
        Runnable[] rebuildTiles = new Runnable[1];
        rebuildTiles[0] = () -> {
            tileGrid.removeAll();
            for (NodeType nt : engine.nodeTypes().all()) {
                tileGrid.add(typeTile(nt, selectedType, tileGrid::repaint));
            }
            tileGrid.revalidate();
            tileGrid.repaint();
        };
        rebuildTiles[0].run();

        JButton manageBtn = Ui.button("Quản lý loại & Icon...", t -> Icons.gear(t.fg), () -> {
            if (NodeTypeManagerDialog.show(d, engine)) {
                rebuildTiles[0].run();
            }
        });

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));
        JPanel topRow = new JPanel(new BorderLayout());
        topRow.setOpaque(false);
        topRow.add(Ui.bold("Chọn loại & biểu tượng cho nhánh:"), BorderLayout.WEST);
        topRow.add(manageBtn, BorderLayout.EAST);
        body.add(topRow, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(tileGrid);
        sp.setBorder(BorderFactory.createEmptyBorder());
        body.add(sp, BorderLayout.CENTER);

        JButton okBtn = Ui.accent("Áp dụng", t -> Icons.check(t.accent.onSolid()), t -> t.accent, () -> {
            out[0] = selectedType[0];
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

        Theme.track(d);
        d.setSize(new Dimension(540, 320));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return out[0];
    }

    private static JPanel typeTile(NodeType nt, String[] selectedRef, Runnable onPick) {
        JPanel tile = new JPanel() {
            private boolean hovered;

            {
                setPreferredSize(new Dimension(112, 50));
                setOpaque(false);
                setToolTipText(nt.label() + " [" + nt.id() + "]");
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hovered = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hovered = false;
                        repaint();
                    }

                    @Override
                    public void mouseClicked(MouseEvent e) {
                        selectedRef[0] = nt.id();
                        onPick.run();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Theme t = Theme.current();
                Theme.Token tok = Icons.tokenForKey(t, nt.colorKey());
                boolean sel = nt.id().equals(selectedRef[0]);
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
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
                    g2.drawString(lbl, (w - fm.stringWidth(lbl)) / 2, h - 10);
                } finally {
                    g2.dispose();
                }
            }
        };
        return tile;
    }
}
