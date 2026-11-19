package themythofcrawl.ui.kit;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import themythofcrawl.core.Text;
import themythofcrawl.model.NodeTypeCatalog;
import themythofcrawl.model.NodeTypeCatalog.NodeType;
import themythofcrawl.model.NodeTypeCatalog.VectorMeta;
import themythofcrawl.ui.Icons;
import themythofcrawl.ui.Ui;
import themythofcrawl.ui.theme.FontManager;
import themythofcrawl.ui.theme.Theme;

/**
 * Reusable sharp-corner Icon &amp; Color customization panel (v2.1):
 * <ul>
 *   <li>Tab 1: 42 categorized &amp; searchable sharp vector icons + optional boxed frame.</li>
 *   <li>Tab 2: 1–3 char Monogram with 3 sharp styles (Soft / Solid / Outline).</li>
 *   <li>Tab 3: Custom PNG/JPG/GIF image encoded as self-contained 48x48 base64 PNG data URI.</li>
 *   <li>13 semantic color swatches + arbitrary {@code #RRGGBB} custom Hex color with auto WCAG contrast.</li>
 * </ul>
 */
public final class IconPickerPanel extends JPanel {
    private String selectedVector = "folder";
    private String selectedColorKey = "info";
    private String selectedCustomHex = "";
    private String selectedCategory = "Tất cả";
    private String selectedMonoStyle = "soft";
    private String imageDataUri = "";
    private boolean boxed = false;
    private boolean loading = false;

    private final SegmentedToggle modeToggle;
    private final CardLayout modeCards = new CardLayout();
    private final JPanel modePanel = new JPanel(modeCards);

    private final SearchField vectorSearch = new SearchField("Tìm biểu tượng (VD: button, giỏ hàng, bảo mật, api)...", 18);
    private final JPanel categoryBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final JPanel vectorGrid = new JPanel(new GridLayout(0, 7, 4, 4));
    private final JLabel vectorCountLbl = new JLabel();
    private final JCheckBox boxedChk = new JCheckBox("Đặt trong khung vuông viền sắc nét");
    private final List<JPanel> vectorCells = new ArrayList<>();
    private final List<JButton> categoryButtons = new ArrayList<>();

    private final JTextField monoField = new JTextField(6);
    private final SegmentedToggle monoStyleToggle;

    private final JLabel imgThumb = new JLabel("", JLabel.CENTER);
    private final JLabel imgStatus = new JLabel("Chưa chọn ảnh (hỗ trợ PNG, JPG, GIF — tự thu gọn 48x48 nhúng trực tiếp)");
    private final JButton clearImgBtn;

    private final JPanel swatchStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final List<JPanel> swatchCells = new ArrayList<>();
    private final JTextField hexField = new JTextField(8);
    private final JPanel hexPreview;

    private final List<Runnable> changeListeners = new ArrayList<>();

    public IconPickerPanel() {
        super(new BorderLayout(0, Theme.SPACE_2));
        setOpaque(false);

        modeToggle = new SegmentedToggle(
                new SegmentedToggle.Option("Bộ Vector (42)", "42 biểu tượng vector sắc nét chia 5 nhóm, có tìm kiếm",
                        t -> t.info, (t, s) -> Icons.vectorIcon("icon", s ? t.info.onSolid() : t.fg, 13), false),
                new SegmentedToggle.Option("Chữ viết tắt", "Tạo icon monogram 1-3 ký tự (Soft / Đặc / Viền)",
                        t -> t.info, (t, s) -> Icons.fontGlyph(s ? t.info.onSolid() : t.fg, 13), false),
                new SegmentedToggle.Option("Ảnh Custom", "Tải ảnh PNG/JPG/GIF từ máy và nhúng vĩnh viễn",
                        t -> t.info, (t, s) -> Icons.upload(s ? t.info.onSolid() : t.fg, 13), false));
        modeToggle.setFixedHeight(26);

        monoStyleToggle = new SegmentedToggle(
                new SegmentedToggle.Option("Nền mềm", "Nền nhạt + chữ đậm theo màu chọn", t -> t.info, (t, s) -> null, false),
                new SegmentedToggle.Option("Nền đặc", "Ô vuông màu đặc + chữ tương phản", t -> t.accent, (t, s) -> null, false),
                new SegmentedToggle.Option("Chỉ viền", "Nền trong suốt + viền 1px sắc nét", t -> t.neutral, (t, s) -> null, false));
        monoStyleToggle.setFixedHeight(24);
        monoStyleToggle.onChange(idx -> {
            selectedMonoStyle = NodeTypeCatalog.MONOGRAM_STYLES.get(Math.max(0, Math.min(idx, 2)));
            fireChanged();
        });

        hexPreview = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Theme t = Theme.current();
                Theme.Token tok = activeToken(t);
                g.setColor(tok.solidFill());
                g.fillRect(2, 2, getWidth() - 5, getHeight() - 5);
                g.setColor(t.border);
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            }
        };
        hexPreview.setPreferredSize(new Dimension(24, 24));
        hexPreview.setToolTipText("Màu đang áp dụng");

        clearImgBtn = Ui.button("Xóa ảnh", t -> Icons.trash(t.danger.text()), () -> {
            imageDataUri = "";
            updateImagePreview();
            fireChanged();
        });

        add(buildColorSection(), BorderLayout.NORTH);
        add(buildModeSection(), BorderLayout.CENTER);
        rebuildVectorGrid();
        updateImagePreview();
    }

    private JPanel buildColorSection() {
        Theme th = Theme.current();
        JPanel wrap = new JPanel();
        wrap.setOpaque(false);
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Màu sắc biểu tượng (13 màu chuẩn hoặc mã #RRGGBB tùy chọn):");
        title.setFont(th.smallBoldFont);
        title.setForeground(th.fg);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.add(title);
        wrap.add(Box.createVerticalStrut(4));

        swatchStrip.setOpaque(false);
        swatchStrip.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (String ck : NodeTypeCatalog.COLOR_KEYS) {
            JPanel cell = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    Theme t = Theme.current();
                    Theme.Token tok = Icons.tokenForKey(t, ck);
                    boolean active = selectedCustomHex.isEmpty() && ck.equals(selectedColorKey);
                    g.setColor(tok.solidFill());
                    g.fillRect(3, 3, getWidth() - 6, getHeight() - 6);
                    g.setColor(active ? t.fg : t.border);
                    g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                    if (active) {
                        g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
                    }
                }
            };
            cell.setPreferredSize(new Dimension(24, 24));
            cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            RichHoverCard.install(cell, new RichHoverCard.Spec(
                    "Màu: " + colorLabel(ck),
                    "Áp dụng bộ màu tương phản tự động cho cả giao diện Sáng và Tối.",
                    null,
                    t -> Icons.palette(Icons.tokenForKey(t, ck).solidFill(), 14),
                    t -> Icons.tokenForKey(t, ck),
                    () -> selectedCustomHex.isEmpty() && ck.equals(selectedColorKey) ? "Đang chọn" : null));
            cell.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    selectedColorKey = ck;
                    selectedCustomHex = "";
                    loading = true;
                    hexField.setText("");
                    loading = false;
                    repaintColors();
                    fireChanged();
                }
            });
            swatchCells.add(cell);
            swatchStrip.add(cell);
        }

        hexField.setFont(th.monoSmallFont);
        hexField.setToolTipText("Nhập mã màu Hex tùy ý, ví dụ: #FF5722 hoặc #0EA5E9");
        hexField.setPreferredSize(new Dimension(88, th.scaled(24)));
        hexField.getDocument().addDocumentListener(simpleDoc(() -> {
            if (loading) {
                return;
            }
            String norm = Theme.normalizeHex(hexField.getText());
            selectedCustomHex = norm;
            repaintColors();
            fireChanged();
        }));
        JButton clearHexBtn = Ui.button("Mặc định", () -> {
            selectedCustomHex = "";
            loading = true;
            hexField.setText("");
            loading = false;
            repaintColors();
            fireChanged();
        });
        clearHexBtn.setToolTipText("Quay lại màu chuẩn đang chọn");

        JPanel hexRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        hexRow.setOpaque(false);
        hexRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel hexLbl = new JLabel("Mã Hex tùy chỉnh:");
        hexLbl.setFont(th.smallFont);
        hexLbl.setForeground(th.muted);
        hexRow.add(hexLbl);
        hexRow.add(hexPreview);
        hexRow.add(hexField);
        hexRow.add(clearHexBtn);

        wrap.add(swatchStrip);
        wrap.add(Box.createVerticalStrut(4));
        wrap.add(hexRow);
        return wrap;
    }

    private JPanel buildModeSection() {
        JPanel wrap = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        wrap.setOpaque(false);

        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(modeToggle);
        topBar.add(left, BorderLayout.WEST);

        boxedChk.setOpaque(false);
        boxedChk.setFont(Theme.current().smallFont);
        boxedChk.setToolTipText("Bọc biểu tượng trong khung vuông đồng bộ trên Cây nhánh");
        boxedChk.addActionListener(e -> {
            boxed = boxedChk.isSelected();
            repaintVectors();
            fireChanged();
        });
        topBar.add(boxedChk, BorderLayout.EAST);
        wrap.add(topBar, BorderLayout.NORTH);

        modePanel.setOpaque(false);
        modePanel.add(buildVectorTab(), "0");
        modePanel.add(buildMonogramTab(), "1");
        modePanel.add(buildImageTab(), "2");
        modeToggle.onChange(idx -> {
            modeCards.show(modePanel, String.valueOf(idx));
            boxedChk.setVisible(idx == 0);
            fireChanged();
        });
        wrap.add(modePanel, BorderLayout.CENTER);
        return wrap;
    }

    private JPanel buildVectorTab() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);

        JPanel filterTop = new JPanel();
        filterTop.setOpaque(false);
        filterTop.setLayout(new BoxLayout(filterTop, BoxLayout.Y_AXIS));

        JPanel searchRow = new JPanel(new BorderLayout(8, 0));
        searchRow.setOpaque(false);
        searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        vectorSearch.getDocument().addDocumentListener(simpleDoc(this::rebuildVectorGrid));
        vectorCountLbl.setFont(Theme.current().smallFont);
        vectorCountLbl.setForeground(Theme.current().muted);
        searchRow.add(vectorSearch, BorderLayout.CENTER);
        searchRow.add(vectorCountLbl, BorderLayout.EAST);
        filterTop.add(searchRow);
        filterTop.add(Box.createVerticalStrut(4));

        categoryBar.setOpaque(false);
        categoryBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (String cat : NodeTypeCatalog.CATEGORIES) {
            JButton btn = new JButton(cat);
            btn.setFocusable(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.setMargin(new java.awt.Insets(2, 8, 2, 8));
            btn.addActionListener(e -> {
                selectedCategory = cat;
                updateCategoryButtonStyles();
                rebuildVectorGrid();
            });
            categoryButtons.add(btn);
            categoryBar.add(btn);
        }
        updateCategoryButtonStyles();
        filterTop.add(categoryBar);
        p.add(filterTop, BorderLayout.NORTH);

        vectorGrid.setOpaque(false);
        vectorGrid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel gridHolder = new JPanel(new BorderLayout());
        gridHolder.setOpaque(false);
        gridHolder.add(vectorGrid, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(gridHolder);
        sp.setBorder(BorderFactory.createLineBorder(Theme.current().border, 1));
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.setPreferredSize(new Dimension(420, 178));
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildMonogramTab() {
        Theme th = Theme.current();
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.border, 1),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row1.setOpaque(false);
        row1.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel lbl = new JLabel("Ký tự viết tắt (1–3 ký tự, tự in hoa):");
        lbl.setFont(th.smallBoldFont);
        monoField.setFont(th.monoBoldFont);
        monoField.setPreferredSize(new Dimension(92, th.scaled(26)));
        monoField.getDocument().addDocumentListener(simpleDoc(() -> {
            if (!loading) {
                fireChanged();
            }
        }));
        row1.add(lbl);
        row1.add(monoField);
        p.add(row1);
        p.add(Box.createVerticalStrut(6));

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row2.setOpaque(false);
        row2.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel styleLbl = new JLabel("Kiểu khung:");
        styleLbl.setFont(th.smallBoldFont);
        row2.add(styleLbl);
        row2.add(monoStyleToggle);
        p.add(row2);
        p.add(Box.createVerticalStrut(6));

        JLabel hint = Ui.muted("Ví dụ hay dùng: API, BTN, LNK, PAY, ADM, V2 — hiển thị rõ nét ngay cả ở kích thước 14px.");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(hint);
        return p;
    }

    private JPanel buildImageTab() {
        Theme th = Theme.current();
        JPanel p = new JPanel(new BorderLayout(10, 8));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.border, 1),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));

        imgThumb.setPreferredSize(new Dimension(56, 56));
        imgThumb.setBorder(BorderFactory.createLineBorder(th.border, 1));
        imgThumb.setOpaque(true);
        imgThumb.setBackground(th.surfaceAlt);

        JButton browseBtn = Ui.accent("Chọn ảnh PNG / JPG...", t -> Icons.upload(t.accent.onSolid()), t -> t.accent, () -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Hình ảnh (PNG, JPG, GIF)", "png", "jpg", "jpeg", "gif"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                if (f != null) {
                    String dataUri = Icons.encodeImageDataUri(f);
                    imageDataUri = dataUri != null ? dataUri : f.getAbsolutePath();
                    updateImagePreview();
                    fireChanged();
                }
            }
        });

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btns.setOpaque(false);
        btns.setAlignmentX(Component.LEFT_ALIGNMENT);
        btns.add(browseBtn);
        btns.add(clearImgBtn);
        right.add(btns);
        right.add(Box.createVerticalStrut(6));
        imgStatus.setFont(th.smallFont);
        imgStatus.setForeground(th.muted);
        imgStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        right.add(imgStatus);

        p.add(imgThumb, BorderLayout.WEST);
        p.add(right, BorderLayout.CENTER);
        return p;
    }

    private void rebuildVectorGrid() {
        vectorGrid.removeAll();
        vectorCells.clear();
        String query = vectorSearch.getText();
        int shown = 0;
        for (VectorMeta meta : NodeTypeCatalog.VECTOR_META) {
            if (!"Tất cả".equals(selectedCategory) && !selectedCategory.equals(meta.category())) {
                continue;
            }
            if (!meta.matches(query)) {
                continue;
            }
            shown++;
            JPanel cell = new JPanel(new BorderLayout()) {
                private boolean hovered;

                {
                    setPreferredSize(new Dimension(44, 38));
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
                            selectedVector = meta.id();
                            repaintVectors();
                            fireChanged();
                        }
                    });
                }

                @Override
                protected void paintComponent(Graphics g) {
                    Theme t = Theme.current();
                    boolean active = meta.id().equals(selectedVector);
                    Theme.Token tok = activeToken(t);
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        FontManager.applyTextHints(g2);
                        int w = getWidth();
                        int h = getHeight();
                        g2.setColor(active ? tok.fillStrong() : (hovered ? t.hover : t.surface));
                        g2.fillRect(0, 0, w - 1, h - 1);
                        g2.setColor(active ? tok.solidFill() : (hovered ? tok.border() : t.border));
                        g2.drawRect(0, 0, w - 1, h - 1);
                        if (active) {
                            g2.fillRect(0, h - 3, w, 3);
                        }
                        Icon ic = boxed
                                ? Icons.boxedVectorIcon(meta.id(), tok, 18)
                                : Icons.vectorIcon(meta.id(), active ? tok.text() : t.fg, 16);
                        ic.paintIcon(this, g2, (w - ic.getIconWidth()) / 2, (h - ic.getIconHeight()) / 2);
                    } finally {
                        g2.dispose();
                    }
                }
            };
            RichHoverCard.install(cell, new RichHoverCard.Spec(
                    meta.label(),
                    "Nhóm: " + meta.category() + " · Mã: " + meta.id(),
                    null,
                    t -> Icons.vectorIcon(meta.id(), activeToken(t).text(), 16),
                    this::activeToken,
                    () -> meta.id().equals(selectedVector) ? "Đang chọn" : "Bấm để chọn biểu tượng này"));
            vectorCells.add(cell);
            vectorGrid.add(cell);
        }
        vectorCountLbl.setText(shown + " / " + NodeTypeCatalog.VECTOR_META.size() + " icon");
        vectorGrid.revalidate();
        vectorGrid.repaint();
    }

    private void updateCategoryButtonStyles() {
        Theme t = Theme.current();
        for (JButton b : categoryButtons) {
            boolean active = b.getText().equals(selectedCategory);
            b.setFont(active ? t.smallBoldFont : t.smallFont);
            b.setBackground(active ? t.info.fillStrong() : t.surface);
            b.setForeground(active ? t.info.text() : t.fg);
            b.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(active ? t.info.solidFill() : t.border, 1),
                    BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        }
    }

    private void updateImagePreview() {
        Theme t = Theme.current();
        if (Text.blank(imageDataUri)) {
            imgThumb.setIcon(Icons.vectorIcon("upload", t.muted, 20));
            imgThumb.setText("");
            imgStatus.setText("Chưa có ảnh — bấm 'Chọn ảnh' để nhúng PNG/JPG (tự tối ưu 48x48, không bao giờ mất link)");
            clearImgBtn.setEnabled(false);
        } else {
            Icon ic = Icons.imageIcon(imageDataUri, 32);
            imgThumb.setIcon(ic != null ? ic : Icons.error(t.danger.text(), 18));
            imgThumb.setText("");
            imgStatus.setText(imageDataUri.startsWith("data:image/")
                    ? "Đã nhúng ảnh trực tiếp (Base64 PNG · " + imageDataUri.length() + " ký tự)"
                    : "Đường dẫn ảnh: " + Text.shortPath(imageDataUri, 42));
            clearImgBtn.setEnabled(true);
        }
    }

    private Theme.Token activeToken(Theme t) {
        return t.tokenForColorKey(selectedColorKey, selectedCustomHex);
    }

    private void repaintColors() {
        for (JPanel c : swatchCells) {
            c.repaint();
        }
        hexPreview.repaint();
        repaintVectors();
    }

    private void repaintVectors() {
        for (JPanel c : vectorCells) {
            c.repaint();
        }
    }

    public void loadFrom(NodeType nt) {
        if (nt == null) {
            return;
        }
        loading = true;
        try {
            selectedVector = nt.iconKind();
            selectedColorKey = nt.colorKey();
            selectedCustomHex = nt.customHex();
            selectedMonoStyle = nt.monogramStyle();
            imageDataUri = nt.imagePath();
            boxed = nt.boxed();

            hexField.setText(selectedCustomHex);
            monoField.setText(nt.monogram());
            int styleIdx = Math.max(0, NodeTypeCatalog.MONOGRAM_STYLES.indexOf(selectedMonoStyle));
            monoStyleToggle.setSelectedIndex(styleIdx, false);
            boxedChk.setSelected(boxed);

            int mode = !Text.blank(nt.imagePath()) ? 2 : (!Text.blank(nt.monogram()) ? 1 : 0);
            modeToggle.setSelectedIndex(mode, false);
            modeCards.show(modePanel, String.valueOf(mode));
            boxedChk.setVisible(mode == 0);

            updateImagePreview();
            repaintColors();
        } finally {
            loading = false;
        }
    }

    public NodeType buildNodeType(String id, String label, boolean builtIn) {
        int mode = modeToggle.selectedIndex();
        String mono = mode == 1 ? monoField.getText().trim().toUpperCase(Locale.ROOT) : "";
        String img = mode == 2 ? imageDataUri : "";
        return new NodeType(
                id,
                label,
                selectedVector,
                selectedColorKey,
                mono,
                img,
                builtIn,
                selectedCustomHex,
                selectedMonoStyle,
                mode == 0 && boxed);
    }

    public void setSearchQuery(String query) {
        vectorSearch.setText(query == null ? "" : query);
        rebuildVectorGrid();
    }

    public void setCategory(String category) {
        if (category != null && NodeTypeCatalog.CATEGORIES.contains(category)) {
            selectedCategory = category;
            updateCategoryButtonStyles();
            rebuildVectorGrid();
        }
    }

    public void setCustomHex(String hex) {
        selectedCustomHex = Theme.normalizeHex(hex);
        loading = true;
        hexField.setText(selectedCustomHex);
        loading = false;
        repaintColors();
        fireChanged();
    }

    public int filteredVectorCount() {
        return vectorCells.size();
    }

    public void onChange(Runnable r) {
        if (r != null) {
            changeListeners.add(r);
        }
    }

    private void fireChanged() {
        for (Runnable r : changeListeners) {
            r.run();
        }
    }

    private static DocumentListener simpleDoc(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    private static String colorLabel(String key) {
        return switch (key) {
            case "info" -> "Xanh dương (Info)";
            case "indigo" -> "Chàm (Indigo)";
            case "purple" -> "Tím (Purple)";
            case "pink" -> "Hồng (Pink)";
            case "bad" -> "Đỏ (Danger)";
            case "accent" -> "Cam điểm nhấn (Accent)";
            case "warn" -> "Vàng đậm (Warn)";
            case "amber" -> "Hổ phách (Amber)";
            case "ok" -> "Xanh lá (Success)";
            case "emerald" -> "Ngọc lục bảo (Emerald)";
            case "teal" -> "Xanh mòng két (Teal)";
            case "cyan" -> "Xanh lơ (Cyan)";
            default -> "Xám trung tính (Muted)";
        };
    }
}
