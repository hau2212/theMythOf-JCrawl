package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import themythofcrawl.app.Engine;
import themythofcrawl.collab.InventoryCatalog;
import themythofcrawl.collab.InventoryCatalog.InventoryRow;
import themythofcrawl.collab.InventoryCatalog.ItemStatus;
import themythofcrawl.collab.InventoryCatalog.ViewContext;
import themythofcrawl.core.Text;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * Unified Auto-Inventory (7 contexts), Colored Status &amp; Custom Tag Board, Jump-to-Path Search,
 * Final 4-Sheet Colored Excel Export, Google Sheets Online &amp; Team Collaboration Bar (V_5.0_beta).
 */
final class InventoryBoardPanel extends JPanel {

    private final MainPanel ui;
    private final Engine engine;
    private final InventoryCatalog catalog;

    private final List<Pill> contextPills = new ArrayList<>();
    private ViewContext activeContext = ViewContext.TIMELINE;
    private final JComboBox<String> statusCombo = new JComboBox<>(new String[] {
            "Mọi trạng thái", "Đã bắt", "Cần xem", "Chưa bắt", "Ngoài phạm vi", "Xung đột", "Đang làm"
    });
    private final JComboBox<String> tagCombo = new JComboBox<>();
    private final JTextField searchField = new JTextField(18);
    private final JLabel statsLabel = Ui.muted("0 API");
    private final JButton conflictBtn;
    private final JButton teamBtn;

    private final DefaultTableModel model;
    private final JTable table;
    private final List<InventoryRow> displayed = new ArrayList<>();
    private boolean refreshingTags;

    InventoryBoardPanel(MainPanel ui) {
        super(new BorderLayout(0, Theme.SPACE_1));
        this.ui = ui;
        this.engine = ui.engine;
        this.catalog = engine.inventoryCatalog();
        setOpaque(false);

        // ---- Row 1: Action & Collaboration Strip
        JButton exportFinalBtn = Ui.solidPrimary("Xuất Excel Final (Có màu)...",
                t -> Icons.excel(t.accent.onSolid()), this::exportFinalXlsx);
        exportFinalBtn.setToolTipText("Xuất file .xlsx 4 sheet (Master + CRUD Matrix + Theo Nhánh + Tổng hợp đồng đội) có tô màu sẵn cho Microsoft Excel Online");

        JButton gsheetsBtn = Ui.tonalBtn("Google Sheets...",
                t -> Icons.upload(t.success.text()), t -> t.success,
                () -> TeamCollabDialog.show(this, engine, TeamCollabDialog.TAB_GSHEETS, this::reload));
        gsheetsBtn.setToolTipText("Kết nối đồng bộ 2 chiều Google Sheets Online (tự tô màu Xanh/Vàng/Đỏ/Xám)");

        teamBtn = Ui.secondaryBtn("Đồng đội: " + catalog.memberName(),
                t -> Icons.target(t.info.text()),
                () -> TeamCollabDialog.show(this, engine, TeamCollabDialog.TAB_TEAM, this::reload));
        teamBtn.setToolTipText("Mở phòng / Tham gia đồng bộ Real-time giữa nhiều Extension & đặt tên thành viên");

        JButton tagsBtn = Ui.secondaryBtn("Tag...",
                t -> Icons.plus(t.accent.text()),
                () -> TeamCollabDialog.show(this, engine, TeamCollabDialog.TAB_TAGS, this::reload));
        tagsBtn.setToolTipText("Tạo / xóa các Tag tùy chỉnh (tự đồng bộ sang đồng đội & xuất cột Tags)");

        conflictBtn = Ui.secondaryBtn("Xung đột: 0",
                t -> Icons.warn(t.warning.text()),
                () -> TeamCollabDialog.show(this, engine, TeamCollabDialog.TAB_CONFLICTS, this::reload));
        conflictBtn.setToolTipText("Mở hộp giải quyết xung đột kiểu Git khi 2 thành viên cùng sửa 1 API");

        JButton jumpBtn = Ui.tonalBtn("Đi tới Nhánh trên Cây",
                t -> Icons.arrowRight(t.accent.text()), t -> t.accent, () -> jumpSelected(false));
        jumpBtn.setToolTipText("Chuyển sang Tab Cây và mở đúng Nhánh/API đang chọn");

        JButton focusBranchBtn = Ui.secondaryBtn("Tập trung nhánh",
                t -> Icons.focus(t.accent.text(), 13), () -> jumpSelected(true));
        focusBranchBtn.setToolTipText("Mở Tab Cây và bật Tập trung (kèm nhánh con) vào đúng nhánh chứa API này");

        JPanel row1 = new JPanel(new BorderLayout(6, 0));
        row1.setOpaque(false);
        row1.add(Ui.flow(FlowLayout.LEFT, exportFinalBtn, gsheetsBtn, teamBtn, tagsBtn, conflictBtn), BorderLayout.WEST);
        row1.add(Ui.flow(FlowLayout.RIGHT, jumpBtn, focusBranchBtn), BorderLayout.EAST);

        // ---- Row 2: 7 Multi-Context Synthesizer Pills
        JPanel contextStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        contextStrip.setOpaque(false);
        contextStrip.add(Ui.bold("Ngữ cảnh:"));
        ViewContext[] contexts = ViewContext.values();
        for (int i = 0; i < contexts.length; i++) {
            ViewContext vc = contexts[i];
            Pill p = new Pill(vc.label, i == 0 ? t -> t.accent : t -> t.neutral);
            p.setSolidWhenActive(true);
            p.setActive(i == 0);
            p.setToolTipText(vc.description);
            p.onClick(() -> selectContext(vc));
            contextPills.add(p);
            contextStrip.add(p);
        }

        // ---- Row 3: Search + Status Filter + Tag Filter + Stats
        searchField.putClientProperty("JTextField.placeholderText", "Tìm theo Path, Method, Nhánh, Tag, Người tìm (Enter để đi tới Cây)...");
        searchField.addActionListener(Ui.safe(e -> jumpFirstOrSelected()));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { reload(); }
            @Override public void removeUpdate(DocumentEvent e) { reload(); }
            @Override public void changedUpdate(DocumentEvent e) { reload(); }
        });
        statusCombo.addActionListener(Ui.safe(e -> reload()));
        tagCombo.addActionListener(Ui.safe(e -> {
            if (!refreshingTags) {
                reload();
            }
        }));

        JPanel filterRow = new JPanel(new BorderLayout(6, 0));
        filterRow.setOpaque(false);
        filterRow.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Tìm:"), searchField, statusCombo, tagCombo), BorderLayout.CENTER);
        filterRow.add(Ui.flow(FlowLayout.RIGHT, statsLabel), BorderLayout.EAST);

        JPanel headerStack = new JPanel(new BorderLayout(0, 3));
        headerStack.setOpaque(false);
        headerStack.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        headerStack.add(row1, BorderLayout.NORTH);
        headerStack.add(contextStrip, BorderLayout.CENTER);
        headerStack.add(filterRow, BorderLayout.SOUTH);
        add(headerStack, BorderLayout.NORTH);

        // ---- Center: Color-Coded Inventory Table
        String[] cols = {
                "STT", "Nhóm Ngữ cảnh", "Trạng thái", "Method", "Host", "Path",
                "Mẫu Path {id}", "Nhóm CRUD", "Nhánh UI", "HTTP", "Tags", "Ghi chú"
        };
        model = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new ThemedTable(model);
        Ui.style(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(25);
        int[] widths = {66, 150, 138, 66, 150, 240, 210, 110, 220, 56, 150, 180};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(2).setCellRenderer(new StatusToneRenderer());

        Ui.onDoubleClick(table, () -> jumpSelected(false));
        Ui.onPopup(table, e -> showRowPopup(e.getComponent(), e.getX(), e.getY()));

        add(MainPanel.scroll(table), BorderLayout.CENTER);

        catalog.addListener(() -> SwingUtilities.invokeLater(this::reload));
        engine.teamSync().addListener(() -> SwingUtilities.invokeLater(this::reload));
        reload();
    }

    void selectContext(ViewContext vc) {
        if (vc == null) return;
        this.activeContext = vc;
        ViewContext[] all = ViewContext.values();
        for (int i = 0; i < all.length && i < contextPills.size(); i++) {
            boolean active = all[i] == vc;
            Pill p = contextPills.get(i);
            p.setActive(active);
            p.setToken(active ? t -> t.accent : t -> t.neutral);
        }
        reload();
    }

    ViewContext activeContext() {
        return activeContext;
    }

    List<InventoryRow> displayedRows() {
        return List.copyOf(displayed);
    }

    void reload() {
        refreshTagCombo();
        ItemStatus stFilter = switch (statusCombo.getSelectedIndex()) {
            case 1 -> ItemStatus.DONE;
            case 2 -> ItemStatus.INFO;
            case 3 -> ItemStatus.MISSING;
            case 4 -> ItemStatus.OOS;
            case 5 -> ItemStatus.CONFLICT;
            case 6 -> ItemStatus.DOING;
            default -> null;
        };
        String tagFilter = tagCombo.getSelectedItem() == null ? "" : tagCombo.getSelectedItem().toString();
        List<InventoryRow> rows = catalog.synthesize(engine, activeContext, stFilter, tagFilter, searchField.getText());
        displayed.clear();
        displayed.addAll(rows);

        model.setRowCount(0);
        int done = 0, info = 0, miss = 0, oos = 0, conf = 0;
        for (InventoryRow r : rows) {
            switch (r.status()) {
                case DONE -> done++;
                case INFO -> info++;
                case MISSING -> miss++;
                case OOS -> oos++;
                case CONFLICT -> conf++;
                default -> { }
            }
            model.addRow(new Object[] {
                    r.stt(),
                    r.groupBucket(),
                    r.status().vi + r.statusDisplay().substring(Math.min(r.statusDisplay().length(), r.status().shortLabel.length())),
                    r.method(),
                    r.host(),
                    r.path(),
                    r.pathTemplate(),
                    r.crudResource(),
                    r.branchSummary(),
                    r.httpStatus() > 0 ? r.httpStatus() : "-",
                    r.tagsDisplay(),
                    r.note()
            });
        }
        statsLabel.setText(rows.size() + " dòng · Done: " + done + " · Info: " + info
                + " · OOS: " + oos + " · Missing: " + miss + (conf > 0 ? " · Conflict: " + conf : ""));
        int openConf = catalog.conflictCount();
        conflictBtn.setText("Xung đột: " + openConf);
        int peers = engine.teamSync().activePeers().size();
        teamBtn.setText("Đồng đội: " + catalog.memberName() + (peers > 0 ? " (+" + peers + ")" : ""));
    }

    private void refreshTagCombo() {
        refreshingTags = true;
        try {
            Object prev = tagCombo.getSelectedItem();
            tagCombo.removeAllItems();
            tagCombo.addItem("Mọi Tag");
            for (String t : catalog.allTags()) {
                tagCombo.addItem(t);
            }
            if (prev != null) {
                tagCombo.setSelectedItem(prev);
            }
        } finally {
            refreshingTags = false;
        }
    }

    private InventoryRow selectedRow() {
        int vr = table.getSelectedRow();
        if (vr < 0) return null;
        int mr = table.convertRowIndexToModel(vr);
        return mr >= 0 && mr < displayed.size() ? displayed.get(mr) : null;
    }

    private void jumpFirstOrSelected() {
        if (!displayed.isEmpty()) {
            if (table.getSelectedRow() < 0) {
                table.setRowSelectionInterval(0, 0);
            }
            jumpSelected(false);
        }
    }

    void jumpSelected(boolean focusOnBranch) {
        InventoryRow row = selectedRow();
        if (row == null) {
            ui.flash("Chọn 1 dòng trong bảng để đi tới Nhánh/API trên Cây.");
            return;
        }
        String branch = row.primaryBranch();
        if (row.primaryGroup() == null && Text.blank(branch)) {
            ui.flash("Dòng này chưa có API/Nhánh nào trên Cây (trạng thái: " + row.statusDisplay() + ").");
            return;
        }
        ui.jumpToTreeBranchOrApi(row.primaryGroup(), branch, focusOnBranch);
    }

    private void showRowPopup(Component invoker, int x, int y) {
        InventoryRow row = selectedRow();
        if (row == null) return;
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Đi tới Nhánh trên Cây (" + Text.shortPath(row.primaryBranch(), 36) + ")",
                t -> Icons.arrowRight(t.accent.text()), () -> jumpSelected(false)));
        m.add(Ui.item("Tập trung (Focus) vào Nhánh này (Kèm nhánh con)",
                t -> Icons.focus(t.accent.text(), 13), () -> jumpSelected(true)));
        m.addSeparator();
        String me = catalog.memberName();
        m.add(Ui.item("Đánh dấu Done (" + me + ") [Xanh lá]", t -> Icons.check(t.success.text()),
                () -> updateStatus(row, ItemStatus.DONE)));
        m.add(Ui.item("Đánh dấu Info (" + me + ") [Vàng]", t -> Icons.warn(t.warning.text()),
                () -> updateStatus(row, ItemStatus.INFO)));
        m.add(Ui.item("Đánh dấu OOS (" + me + ") — Out of Scope [Xám]", t -> Icons.stop(t.muted),
                () -> updateStatus(row, ItemStatus.OOS)));
        m.add(Ui.item("Đánh dấu Doing (" + me + ") [Xanh dương]", t -> Icons.sliders(t.info.text()),
                () -> updateStatus(row, ItemStatus.DOING)));
        m.add(Ui.item("Khôi phục trạng thái Tự động", Icons::undo,
                () -> updateStatus(row, null)));
        m.addSeparator();

        JMenu tagMenu = new JMenu("Gắn / Bỏ Tag");
        for (String tag : catalog.allTags()) {
            boolean has = row.tags().contains(tag);
            tagMenu.add(Ui.item((has ? "[x] " : "[ ] ") + tag, null, () -> {
                catalog.toggleTagOnEndpoint(row.key(), row.method(), row.host(), row.pathTemplate(), tag);
                var updated = catalog.metaFor(row.key());
                if (updated != null) engine.teamSync().broadcastEndpoint(updated);
                reload();
            }));
        }
        tagMenu.addSeparator();
        tagMenu.add(Ui.item("+ Tạo Tag mới...", Icons::plus, () -> {
            String nt = JOptionPane.showInputDialog(Ui.ownerWindow(this), "Nhập tên Tag mới:", "Tạo Tag mới", JOptionPane.PLAIN_MESSAGE);
            if (nt != null && !nt.isBlank()) {
                catalog.addCustomTag(nt.trim());
                catalog.toggleTagOnEndpoint(row.key(), row.method(), row.host(), row.pathTemplate(), nt.trim());
                engine.saveCollabPrefs();
                engine.teamSync().broadcastCustomTag(nt.trim());
                var updated = catalog.metaFor(row.key());
                if (updated != null) engine.teamSync().broadcastEndpoint(updated);
                reload();
            }
        }));
        m.add(tagMenu);

        m.add(Ui.item("Sửa ghi chú...", Icons::edit, () -> {
            String note = JOptionPane.showInputDialog(Ui.ownerWindow(this), "Ghi chú cho " + row.method() + " " + row.pathTemplate() + ":", row.note());
            if (note != null) {
                var updated = catalog.setNote(row.key(), row.method(), row.host(), row.pathTemplate(), note);
                engine.teamSync().broadcastEndpoint(updated);
                reload();
            }
        }));
        m.show(invoker, x, y);
    }

    private void updateStatus(InventoryRow row, ItemStatus st) {
        var updated = catalog.setManualStatus(row.key(), row.method(), row.host(), row.pathTemplate(),
                st, row.primaryBranch(), null);
        engine.teamSync().broadcastEndpoint(updated);
        reload();
    }

    private void exportFinalXlsx() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("theMythOf-JCrawl-Final-Report.xlsx"));
        fc.setFileFilter(new FileNameExtensionFilter("Microsoft Excel (*.xlsx)", "xlsx"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) {
            return;
        }
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".xlsx")) {
            f = new File(f.getParentFile(), f.getName() + ".xlsx");
        }
        try {
            byte[] xlsx = catalog.exportFinalColoredXlsx(engine);
            Files.write(f.toPath(), xlsx);
            ui.flash("Đã xuất file Excel Final 4 sheet có màu: " + f.getName());
            JOptionPane.showMessageDialog(Ui.ownerWindow(this),
                    "Đã xuất thành công file Excel Final (4 Sheet có tô màu sẵn cho Microsoft Excel Online):\n" + f.getAbsolutePath(),
                    Text.EXT_NAME, JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(Ui.ownerWindow(this), "Không xuất được file Excel: " + ex.getMessage(),
                    Text.EXT_NAME, JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Theme-token-backed semantic status cell renderer (zero raw Color literals). */
    private final class StatusToneRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable tbl, Object val, boolean sel, boolean foc, int row, int col) {
            JLabel lbl = (JLabel) super.getTableCellRendererComponent(tbl, val, sel, foc, row, col);
            int mr = tbl.convertRowIndexToModel(row);
            Theme th = Theme.current();
            if (!sel && mr >= 0 && mr < displayed.size()) {
                ItemStatus st = displayed.get(mr).status();
                Theme.Token tok = switch (st) {
                    case DONE -> th.success;
                    case INFO -> th.warning;
                    case MISSING -> th.danger;
                    case OOS -> th.neutral;
                    case CONFLICT -> th.critical;
                    case DOING -> th.info;
                };
                lbl.setBackground(tok.fill());
                lbl.setForeground(tok.text());
                lbl.setFont(th.smallBoldFont);
            } else {
                lbl.setFont(th.smallBoldFont);
            }
            lbl.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            return lbl;
        }
    }
}
