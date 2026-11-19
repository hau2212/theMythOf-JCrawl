package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.place.Decisions;
import themythofcrawl.place.PlacementBook;
import themythofcrawl.ui.kit.Card;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SearchField;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * 'Audit' tab: card-style subtabs with count badges (Cần xử lý with inline decision Card,
 * Giữ chỗ, API trùng &amp; ẩn, and color-coded Nhật ký).
 */
final class AuditTab {
    static final String[] SECTIONS = {"decide", "places", "dups", "log"};

    final JPanel panel = new JPanel(new BorderLayout());
    private final MainPanel ui;
    private final Engine engine;
    private final JTabbedPane tabs = new JTabbedPane();
    boolean stale = true;
    private boolean reloading;

    private final RowsModel<ApiGroup> decideModel = new RowsModel<>(Decisions.DECIDE_COLS);
    private final JTable decideTable;
    private final JTextArea decideText = new JTextArea(4, 60);
    private final Pill decideKindPill = new Pill("Chưa chọn", t -> t.neutral);
    private final Card decideCard = new Card("Chi tiết quyết định", "Chọn 1 hoặc nhiều dòng để xem giải thích & xử lý ngay",
            decideKindPill, t -> t.pending, false);
    private JButton cardKeep;
    private JButton cardFound;
    private JButton cardOther;
    private JButton cardHide;
    private JButton cardDelete;

    private static final List<String> UI_PLACE_COLS = List.of("Method", "Path", "Host", "Vị trí giữ chỗ", "API",
            "Trạng thái", "Đã xóa", "Lần gặp", "Bỏ qua", "Lần cuối", "Đích gặp cuối", "Đổi chỗ");

    private final SearchField placeFilter = new SearchField("Lọc method, path, vị trí...", 15);
    private final JComboBox<String> placeView = new JComboBox<>(new String[] {"Tất cả", "Đang có API", "Đã xóa (giữ chỗ)",
        "Chờ xác nhận"});
    private final JLabel placesLabel = new JLabel(" ");
    private final RowsModel<PlacementBook.Record> placeModel = new RowsModel<>(UI_PLACE_COLS);
    private final JTable placeTable;

    private final JComboBox<String> dupView = new JComboBox<>(new String[] {"Tất cả API trùng & ẩn", "Chỉ đang ẩn"});
    private final RowsModel<ApiGroup> dupModel = new RowsModel<>(Decisions.DUP_COLS);
    private final JTable dupTable;

    private final SearchField logFilter = new SearchField("Lọc nhật ký (endpoint, hành động)...", 15);
    private final JButton logScope = new JButton("Tất cả endpoint");
    private final RowsModel<PlacementBook.Event> logModel = new RowsModel<>(Decisions.LOG_COLS);
    private final JTable logTable;
    private String logKey;

    AuditTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        tabs.putClientProperty("JTabbedPane.tabType", "card");
        decideTable = table(decideModel, new int[] {170, 80, 300, 300, 300}, 0, null,
                r -> r.pathCols(2), 1);
        placeTable = table(placeModel, new int[] {78, 205, 130, 205, 68, 145, 74, 80, 74, 115, 190, 76}, 5, 0,
                r -> r.pathCols(1).numberCols(6, 7, 8, 11).timeCols(9), 3);
        dupTable = table(dupModel, new int[] {320, 120, 80, 320, 145, 70, 90}, 4, null,
                r -> r.pathCols(0).timeCols(6), 1);
        logTable = table(logModel, new int[] {54, 110, 230, 70, 84, 260, 240, 240, 300}, 2, 4,
                r -> r.pathCols(5).numberCols(0).timeCols(1), 0);
        ColumnMemory.install(decideTable, "audit_decide", ui.uiPrefs);
        ColumnMemory.install(placeTable, "audit_places", ui.uiPrefs);
        ColumnMemory.install(dupTable, "audit_dups", ui.uiPrefs);
        ColumnMemory.install(logTable, "audit_log", ui.uiPrefs);
        tabs.addTab("Cần xử lý (0)", decidePanel());
        tabs.addTab("Giữ chỗ (0)", placesPanel());
        tabs.addTab("API trùng & ẩn (0)", dupsPanel());
        tabs.addTab("Nhật ký (0)", logPanel());
        tabs.addChangeListener(e -> reload());
        panel.add(tabs, BorderLayout.CENTER);
    }

    SearchField activeSearchField() {
        int sec = tabs.getSelectedIndex();
        if (sec == 1) {
            return placeFilter;
        }
        if (sec == 3) {
            return logFilter;
        }
        return null;
    }

    private static JTable table(RowsModel<?> m, int[] widths, int auditCol, Integer methodCol,
                                java.util.function.Consumer<AuditRenderer> cfg, int... bold) {
        JTable t = new ThemedTable(m);
        Ui.style(t);
        t.setAutoCreateRowSorter(true);
        AuditRenderer r = new AuditRenderer(m, null, auditCol, methodCol, bold);
        if (cfg != null) {
            cfg.accept(r);
        }
        for (int c = 0; c < widths.length; c++) {
            t.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
            t.getColumnModel().getColumn(c).setCellRenderer(r);
        }
        return t;
    }

    private static <T> List<T> selected(JTable t, RowsModel<T> m) {
        List<T> out = new ArrayList<>();
        for (int v : t.getSelectedRows()) {
            T p = m.payload(t.convertRowIndexToModel(v));
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private static <T> void reselect(JTable t, RowsModel<T> m, List<T> keep) {
        for (T p : keep) {
            int r = m.indexOf(p);
            if (r >= 0) {
                int v = t.convertRowIndexToView(r);
                if (v >= 0) {
                    t.addRowSelectionInterval(v, v);
                }
            }
        }
    }

    private static void onText(JTextField f, Runnable r) {
        f.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                r.run();
            }
        });
    }

    // ================================================================== Cần xử lý
    private JPanel decidePanel() {
        JButton keep = Ui.accent("Giữ chỗ hiện tại", t -> Icons.check(t.accent.onSolid()), t -> t.accent, () -> decide("keep"));
        decideTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !reloading) {
                explain();
            }
        });
        Ui.onDoubleClick(decideTable, () -> {
            List<ApiGroup> gs = selected(decideTable, decideModel);
            if (!gs.isEmpty()) {
                ui.jumpToGroup(gs.get(0));
            }
        });
        Ui.onPopup(decideTable, e -> {
            List<ApiGroup> gs = selected(decideTable, decideModel);
            JPopupMenu m = new JPopupMenu();
            m.add(Ui.item("Xem chuỗi Gốc & Trùng (Lineage)...", Icons::dup, gs.size() == 1,
                    () -> LineageDialog.show(ui, gs.get(0))));
            m.add(Ui.item("Đặt làm API GỐC #1", Icons::star, gs.size() == 1, () -> {
                if (engine.promoteToOrigin(gs.get(0))) {
                    ui.flash("Đã đặt " + Text.groupId(gs.get(0).gid()) + " làm API GỐC #1");
                    stale = true;
                    ui.tick(true);
                }
            }));
            m.add(Ui.item("Gắn Tag [REFRESH]", Icons::tag, !gs.isEmpty(), () -> {
                engine.setApiTag(gs, themythofcrawl.model.ApiTagCatalog.TAG_REFRESH, true);
                ui.flash("Đã gắn tag [REFRESH] cho " + gs.size() + " API");
                stale = true;
                ui.tick(true);
            }));
            m.addSeparator();
            m.add(Ui.item("Giữ chỗ hiện tại", t -> Icons.check(t.success.text()), !gs.isEmpty(), () -> decide("keep")));
            m.add(Ui.item("Về nơi vừa gặp", Icons::move, !gs.isEmpty(), () -> decide("found")));
            m.add(Ui.item("Path khác...", Icons::folder, !gs.isEmpty(), () -> decide("other")));
            m.add(Ui.item("Không giữ (ẩn)", Icons::hide, !gs.isEmpty(), () -> decide("hide")));
            m.add(Ui.item("Xóa", t -> Icons.trash(t.danger.text()), !gs.isEmpty(), () -> decide("delete")));
            m.addSeparator();
            m.add(Ui.item("Mở ở tab API", Icons::search, gs.size() == 1, () -> ui.jumpToGroup(gs.get(0))));
            m.add(Ui.item("Lịch sử vị trí", Icons::compass, gs.size() == 1, () -> show(1, gs.get(0).bookKey())));
            m.add(Ui.item("Copy STT + Path", t -> Icons.copy(t.muted), !gs.isEmpty(), () -> ui.copy(gs)));
            m.show(e.getComponent(), e.getX(), e.getY());
        });

        decideText.setEditable(false);
        decideText.setOpaque(false);
        decideText.setLineWrap(true);
        decideText.setWrapStyleWord(true);
        decideText.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        cardKeep = Ui.accent("Giữ chỗ hiện tại", t -> Icons.check(t.accent.onSolid()), t -> t.accent, () -> decide("keep"));
        cardFound = Ui.button("Về nơi vừa gặp", Icons::move, () -> decide("found"));
        cardOther = Ui.button("Path khác...", Icons::folder, () -> decide("other"));
        cardHide = Ui.button("Không giữ", Icons::hide, () -> decide("hide"));
        cardDelete = Ui.button("Xóa", t -> Icons.trash(t.danger.text()), () -> decide("delete"));
        JButton cardLineage = Ui.button("Chuỗi Gốc & Trùng...", Icons::dup, () -> {
            List<ApiGroup> gs = selected(decideTable, decideModel);
            if (!gs.isEmpty()) {
                LineageDialog.show(ui, gs.get(0));
            }
        });
        JButton cardTagRefresh = Ui.button("Tag REFRESH", Icons::tag, () -> {
            List<ApiGroup> gs = selected(decideTable, decideModel);
            if (!gs.isEmpty()) {
                engine.setApiTag(gs, themythofcrawl.model.ApiTagCatalog.TAG_REFRESH, true);
                ui.flash("Đã gắn tag [REFRESH] cho " + gs.size() + " API");
                stale = true;
                ui.tick(true);
            }
        });

        JPanel cardActions = Ui.flow(FlowLayout.LEFT, cardKeep, cardFound, cardOther, cardHide, cardDelete, cardLineage, cardTagRefresh);
        JPanel cardInner = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        cardInner.setOpaque(false);
        cardInner.add(MainPanel.scroll(decideText), BorderLayout.CENTER);
        cardInner.add(cardActions, BorderLayout.SOUTH);
        decideCard.setPadding(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3);
        decideCard.setBody(cardInner);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, MainPanel.scroll(decideTable), decideCard);
        split.setResizeWeight(0.60);
        JButton[] moreDecideRef = new JButton[1];
        JButton moreDecide = Ui.dropdown("Thao tác khác", null, "Chọn cách xử lý, Lineage, Tag REFRESH hoặc xem hướng dẫn", () -> {
            JPopupMenu m = new JPopupMenu();
            m.add(Ui.item("Xem chuỗi Gốc & Trùng (Lineage)...", Icons::dup, () -> {
                List<ApiGroup> gs = selected(decideTable, decideModel);
                if (!gs.isEmpty()) {
                    LineageDialog.show(ui, gs.get(0));
                }
            }));
            m.add(Ui.item("Quản lý API Tag & Chính sách REFRESH...", Icons::tag, ui::openTagManager));
            m.addSeparator();
            m.add(Ui.item("Về nơi vừa gặp", Icons::move, () -> decide("found")));
            m.add(Ui.item("Chuyển sang Path khác...", Icons::folder, () -> decide("other")));
            m.add(Ui.item("Không giữ (ẩn)", Icons::hide, () -> decide("hide")));
            m.add(Ui.item("Xóa", t -> Icons.trash(t.danger.text()), () -> decide("delete")));
            m.addSeparator();
            m.add(Ui.item("Hướng dẫn Cần xử lý...", Icons::info,
                    () -> Ui.popover(moreDecideRef[0], "Cần xử lý", Help.DECIDE)));
            m.show(moreDecideRef[0], 0, moreDecideRef[0].getHeight());
        });
        moreDecideRef[0] = moreDecide;
        JPanel p = new JPanel(new BorderLayout());
        p.add(Ui.flow(FlowLayout.LEFT, keep,
                Ui.button("Về nơi vừa gặp", Icons::move, () -> decide("found")),
                Ui.button("Chọn hết", decideTable::selectAll),
                Ui.button("Tag & REFRESH...", Icons::tag, ui::openTagManager),
                moreDecide), BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        explain();
        return p;
    }

    private void explain() {
        List<ApiGroup> gs = selected(decideTable, decideModel);
        boolean hasSel = !gs.isEmpty();
        cardKeep.setEnabled(hasSel);
        cardFound.setEnabled(hasSel);
        cardOther.setEnabled(hasSel);
        cardHide.setEnabled(hasSel);
        cardDelete.setEnabled(hasSel);
        if (gs.isEmpty()) {
            decideKindPill.setToken(t -> t.neutral).setIcon(null).setText("Chưa chọn");
            decideCard.setAccentToken(t -> t.neutral, false);
            decideText.setText(decideModel.getRowCount() == 0 ? "Không có việc nào cần xử lý."
                    : "Chọn 1 dòng để xem giải thích bằng lời nói tự nhiên và khuyến nghị hành động.");
        } else if (gs.size() == 1) {
            ApiGroup g = gs.get(0);
            Decisions.Kind k = Decisions.kind(g);
            Decisions.SmartAdvice advice = Decisions.advise(engine, g);
            decideKindPill.setText(Icons.stripLeadingSymbol(k.label) + (advice.suspectedRefresh() ? " · ?REFRESH" : ""));
            switch (k) {
                case DUP -> {
                    decideKindPill.setToken(t -> t.dup).setIcon(t -> Icons.dup(t.dup.text(), 11));
                    decideCard.setAccentToken(t -> t.dup, true);
                }
                case RETURN, RETURN_MISSING -> {
                    decideKindPill.setToken(t -> t.info).setIcon(t -> Icons.undo(t.info.text(), 11));
                    decideCard.setAccentToken(t -> t.info, true);
                }
                default -> {
                    decideKindPill.setToken(t -> t.pending).setIcon(t -> Icons.hourglass(t.pending.text(), 11));
                    decideCard.setAccentToken(t -> t.pending, true);
                }
            }
            decideText.setText(Icons.stripInlineSymbols(advice.toFormattedText()));
        } else {
            decideKindPill.setToken(t -> t.accent).setIcon(null).setText("Đang chọn " + gs.size() + " API");
            decideCard.setAccentToken(t -> t.accent, true);
            decideText.setText(gs.size() + " API đang chọn. Bấm một trong các nút hành động bên dưới để áp dụng cho tất cả.");
        }
        decideText.setCaretPosition(0);
    }

    private void decide(String action) {
        List<ApiGroup> gs = selected(decideTable, decideModel);
        if (gs.isEmpty()) {
            ui.info("Chọn ít nhất 1 dòng.");
            return;
        }
        String msg;
        switch (action) {
            case "keep" -> msg = "Đã giữ chỗ hiện tại cho " + engine.confirm(gs, "giữ chỗ hiện tại") + " API";
            case "found" -> {
                int n = 0;
                for (ApiGroup g : gs) {
                    String found = g.placeHint() != null ? g.placeHint().found() : null;
                    if (found != null && !found.isBlank()) {
                        n += engine.moveToPath(List.of(g), found, "về nơi vừa gặp");
                    } else {
                        n += engine.confirm(List.of(g), "đã ở nơi vừa gặp");
                    }
                }
                msg = n + " API đã ở nơi vừa gặp";
            }
            case "other" -> msg = "Đã chuyển " + ui.moveDialog(gs) + " API";
            case "hide" -> msg = "Đã ẩn " + engine.hide(gs, "không giữ") + " API (xem lại ở tab API trùng & ẩn)";
            default -> {
                if (!ui.confirmDialog("Xóa " + gs.size() + " API?")) {
                    return;
                }
                engine.remove(gs, "xóa từ Cần xử lý");
                msg = "Đã xóa " + gs.size() + " API";
            }
        }
        ui.flash(msg);
        stale = true;
        ui.tick(true);
    }

    // ================================================================== Giữ chỗ
    private JPanel placesPanel() {
        placeFilter.setToolTipText("Lọc theo method, path, vị trí...");
        onText(placeFilter, this::reload);
        placeView.addActionListener(e -> reload());
        JButton[] moreRef = new JButton[1];
        JButton more = Ui.dropdown("Thao tác", null, "Thao tác giữ chỗ, Lineage & Tag REFRESH", () -> {
            PlacementBook.Record r = selectedPlace();
            JPopupMenu m = new JPopupMenu();
            m.add(Ui.item("Xem chuỗi Gốc & Trùng (Lineage)...", Icons::dup, r != null,
                    () -> LineageDialog.showByKey(ui, r.key, null)));
            m.add(Ui.item("Quản lý API Tag & Chính sách REFRESH...", Icons::tag, ui::openTagManager));
            m.addSeparator();
            m.add(Ui.item("Thêm vị trí giữ chỗ thủ công...", Icons::plus, this::addPlace));
            m.add(Ui.item("Sửa vị trí giữ chỗ đang chọn...", Icons::edit, () -> editPlace(selectedPlace())));
            m.addSeparator();
            m.add(Ui.item("Quên giữ chỗ (lần sau vào đích hiện tại)", Icons::error, this::forgetPlace));
            m.add(Ui.item("Cho phép bắt lại ngay (xóa bộ nhớ chống trùng)", Icons::refresh, this::recapture));
            m.add(Ui.item("Mở API đang có", Icons::search, this::openPlaceApi));
            m.add(Ui.item("Xem nhật ký của endpoint này", Icons::clock, this::logOfSelected));
            m.addSeparator();
            m.add(Ui.item("Hướng dẫn Giữ chỗ...", Icons::info,
                    () -> Ui.popover(moreRef[0], "Giữ chỗ", Help.PLACES)));
            m.show(moreRef[0], 0, moreRef[0].getHeight());
        });
        moreRef[0] = more;
        Ui.onDoubleClick(placeTable, () -> editPlace(selectedPlace()));
        Ui.onPopup(placeTable, e -> {
            PlacementBook.Record r = selectedPlace();
            JPopupMenu m = new JPopupMenu();
            m.add(Ui.item("Xem chuỗi Gốc & Trùng (Lineage)...", Icons::dup, r != null,
                    () -> LineageDialog.showByKey(ui, r.key, null)));
            m.add(Ui.item("Bật / Tắt Tag [REFRESH]", Icons::tag, r != null, () -> {
                boolean has = r.tags.contains(themythofcrawl.model.ApiTagCatalog.TAG_REFRESH);
                engine.toggleEndpointTag(r.key, themythofcrawl.model.ApiTagCatalog.TAG_REFRESH, !has);
                stale = true;
                ui.tick(true);
            }));
            m.addSeparator();
            m.add(Ui.item("Sửa chỗ...", Icons::edit, r != null, () -> editPlace(r)));
            m.add(Ui.item("Quên giữ chỗ", Icons::error, r != null, this::forgetPlace));
            m.add(Ui.item("Cho phép bắt lại ngay", Icons::refresh, r != null, this::recapture));
            m.add(Ui.item("Mở API đang có", Icons::search, r != null && r.gid != null, this::openPlaceApi));
            m.add(Ui.item("Xem nhật ký của endpoint này", Icons::clock, r != null, this::logOfSelected));
            m.add(Ui.item("Copy endpoint", t -> Icons.copy(t.muted), r != null,
                    () -> Ui.copyToClipboard(r.method + " " + r.host + " " + r.path)));
            m.show(e.getComponent(), e.getX(), e.getY());
        });
        placesLabel.setForeground(Theme.current().muted);
        JPanel top = Ui.flow(FlowLayout.LEFT, placeFilter, placeView,
                Ui.button("Sửa chỗ...", Icons::edit, () -> editPlace(selectedPlace())),
                Ui.button("Lineage...", Icons::dup, () -> {
                    PlacementBook.Record r = selectedPlace();
                    if (r != null) {
                        LineageDialog.showByKey(ui, r.key, null);
                    }
                }),
                more, placesLabel);
        JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(MainPanel.scroll(placeTable), BorderLayout.CENTER);
        return p;
    }

    private PlacementBook.Record selectedPlace() {
        int v = placeTable.getSelectedRow();
        return v < 0 ? null : placeModel.payload(placeTable.convertRowIndexToModel(v));
    }

    private boolean selectPlace(String key, boolean scroll) {
        for (int r = 0; r < placeModel.getRowCount(); r++) {
            PlacementBook.Record rec = placeModel.payload(r);
            if (rec != null && rec.key.equals(key)) {
                int v = placeTable.convertRowIndexToView(r);
                if (v >= 0) {
                    placeTable.setRowSelectionInterval(v, v);
                    if (scroll) {
                        placeTable.scrollRectToVisible(placeTable.getCellRect(v, 0, true));
                    }
                }
                return true;
            }
        }
        return false;
    }

    /** 'Sửa vị trí giữ chỗ' (also from the ghost rows of the trees). */
    void editPlace(PlacementBook.Record rec) {
        if (rec == null) {
            return;
        }
        String p = PathPicker.pick(ui.root(), "Vị trí giữ chỗ cho " + rec.method + " " + rec.path, engine.knownPaths(),
                rec.place != null ? rec.place : engine.activeBranch().name(),
                "Lần sau API này được bắt lại (sau khi xóa) sẽ vào đường dẫn này.");
        if (p != null) {
            engine.editPlace(rec.key, p);
            stale = true;
            ui.tick(true);
        }
    }

    private void addPlace() {
        List<ApiGroup> all = engine.groups();
        Object raw = javax.swing.JOptionPane.showInputDialog(ui.root(),
                "Endpoint cần giữ chỗ, dạng 'METHOD host /path'\nVD: POST api.example.com /contact/send",
                "GET " + (all.isEmpty() ? "host" : all.get(0).host()) + " /");
        if (raw == null) {
            return;
        }
        String[] parts = raw.toString().trim().split("\\s+");
        if (parts.length != 3 || !parts[2].startsWith("/")) {
            ui.info("Cần đúng 3 phần: METHOD host /path");
            return;
        }
        String p = PathPicker.pick(ui.root(), "Vị trí giữ chỗ", engine.knownPaths(), engine.activeBranch().name(), null);
        if (p != null) {
            String key = engine.addManualPlace(parts[0], parts[1], parts[2], p, true);
            reload();
            selectPlace(key, true);
        }
    }

    private void forgetPlace() {
        PlacementBook.Record r = selectedPlace();
        if (r != null && ui.confirmDialog("Quên vị trí giữ chỗ của " + r.method + " " + r.path
                + "?\nLần sau bắt lại sẽ vào đích hiện tại.")) {
            engine.forgetPlace(r.key);
            stale = true;
            ui.tick(true);
        }
    }

    private void recapture() {
        PlacementBook.Record r = selectedPlace();
        if (r != null) {
            int n = engine.allowRecapture(r.key);
            ui.flash("Đã xóa " + n + " khóa chống trùng: request tiếp theo của endpoint này sẽ được bắt");
            stale = true;
            ui.tick(true);
        }
    }

    private void openPlaceApi() {
        PlacementBook.Record r = selectedPlace();
        ApiGroup g = r == null || r.gid == null ? null : engine.group(r.gid);
        if (g == null) {
            ui.flash("Endpoint này hiện không có API (đã xóa)");
            return;
        }
        ui.jumpToGroup(g);
    }

    private void logOfSelected() {
        PlacementBook.Record r = selectedPlace();
        if (r != null) {
            logKey = r.key;
            tabs.setSelectedIndex(3);
            reload();
        }
    }

    /** Placement book filtered to a view (0 all, 1 live, 2 deleted, 3 pending). */
    void placeView(int view) {
        ui.tabs.setSelectedComponent(panel);
        tabs.setSelectedIndex(1);
        placeFilter.setText("");
        placeView.setSelectedIndex(view);
        reload();
    }

    // ================================================================== API trùng & ẩn
    private JPanel dupsPanel() {
        dupView.addActionListener(e -> reload());
        Ui.onDoubleClick(dupTable, () -> {
            List<ApiGroup> gs = selected(dupTable, dupModel);
            if (!gs.isEmpty()) {
                LineageDialog.show(ui, gs.get(0));
                stale = true;
                ui.tick(true);
            }
        });
        Ui.onPopup(dupTable, e -> {
            List<ApiGroup> gs = selected(dupTable, dupModel);
            ApiGroup first = gs.isEmpty() ? null : gs.get(0);
            boolean one = gs.size() == 1 && first != null;
            boolean hasRefresh = first != null && engine.book().hasTag(first.bookKey(), themythofcrawl.model.ApiTagCatalog.TAG_REFRESH);
            JPopupMenu m = new JPopupMenu();
            m.add(Ui.item("Xem chuỗi Gốc & Trùng (Lineage)...", Icons::dup, one, () -> dupAction("lineage")));
            m.add(Ui.item("Mở ở tab API", Icons::search, !gs.isEmpty(), () -> dupAction("open")));
            m.addSeparator();
            m.add(Ui.item("Đặt làm API GỐC #1", Icons::check, one, () -> dupAction("promote")));
            m.add(Ui.item("Chỉ giữ GỐC #1 & Ẩn #2+", Icons::hide, one, () -> dupAction("keep_origin")));
            m.add(Ui.item("Giữ (bản chờ)", t -> Icons.check(t.success.text()), !gs.isEmpty(), () -> dupAction("keep")));
            m.add(Ui.item("Hiện", Icons::show, !gs.isEmpty(), () -> dupAction("show")));
            m.add(Ui.item("Ẩn", Icons::hide, !gs.isEmpty(), () -> dupAction("hide")));
            m.addSeparator();
            m.add(Ui.item(hasRefresh ? "Gỡ Tag [REFRESH]" : "Gắn Tag [REFRESH]", Icons::tag, one, () -> dupAction("toggle_refresh")));
            m.add(Ui.item("Quản lý Tag & REFRESH...", Icons::tag, ui::openTagManager));
            m.add(Ui.item("Lịch sử vị trí", Icons::compass, one, () -> show(1, first.bookKey())));
            m.add(Ui.item("Xóa", t -> Icons.trash(t.danger.text()), !gs.isEmpty(), () -> dupAction("delete")));
            m.show(e.getComponent(), e.getX(), e.getY());
        });
        JButton[] moreDupsRef = new JButton[1];
        JButton moreDups = Ui.dropdown("Thao tác", null, "Lineage, Gốc #1, REFRESH Tag, xóa hoặc xem hướng dẫn", () -> {
            List<ApiGroup> gs = selected(dupTable, dupModel);
            ApiGroup first = gs.isEmpty() ? null : gs.get(0);
            boolean one = gs.size() == 1 && first != null;
            boolean hasRefresh = first != null && engine.book().hasTag(first.bookKey(), themythofcrawl.model.ApiTagCatalog.TAG_REFRESH);
            JPopupMenu m = new JPopupMenu();
            m.add(Ui.item("Xem chuỗi Gốc & Trùng (Lineage)...", Icons::dup, one, () -> dupAction("lineage")));
            m.add(Ui.item("Mở ở tab API", Icons::search, !gs.isEmpty(), () -> dupAction("open")));
            m.addSeparator();
            m.add(Ui.item("Đặt làm API GỐC #1", Icons::check, one, () -> dupAction("promote")));
            m.add(Ui.item("Chỉ giữ GỐC #1 & Ẩn #2+", Icons::hide, one, () -> dupAction("keep_origin")));
            m.add(Ui.item("Hiện bản đang chọn", Icons::show, !gs.isEmpty(), () -> dupAction("show")));
            m.add(Ui.item("Ẩn bản đang chọn", Icons::hide, !gs.isEmpty(), () -> dupAction("hide")));
            m.add(Ui.item("Giữ (bản chờ)", t -> Icons.check(t.success.text()), !gs.isEmpty(), () -> dupAction("keep")));
            m.addSeparator();
            m.add(Ui.item(hasRefresh ? "Gỡ Tag [REFRESH]" : "Gắn Tag [REFRESH]", Icons::tag, one, () -> dupAction("toggle_refresh")));
            m.add(Ui.item("Quản lý Tag & REFRESH...", Icons::tag, ui::openTagManager));
            m.add(Ui.item("Lịch sử vị trí", Icons::compass, one, () -> show(1, first.bookKey())));
            m.add(Ui.item("Xóa bản đang chọn", t -> Icons.trash(t.danger.text()), !gs.isEmpty(), () -> dupAction("delete")));
            m.addSeparator();
            m.add(Ui.item("Hướng dẫn API trùng & ẩn...", Icons::info,
                    () -> Ui.popover(moreDupsRef[0], "API trùng & ẩn", Help.DUPS)));
            m.show(moreDupsRef[0], 0, moreDupsRef[0].getHeight());
        });
        moreDupsRef[0] = moreDups;
        JPanel p = new JPanel(new BorderLayout());
        p.add(Ui.flow(FlowLayout.LEFT, dupView,
                Ui.button("Lineage...", Icons::dup, () -> dupAction("lineage")),
                Ui.button("Đặt GỐC #1", Icons::check, () -> dupAction("promote")),
                Ui.button("Giữ (bản chờ)", t -> Icons.check(t.success.text()), () -> dupAction("keep")),
                Ui.button("Hiện", Icons::show, () -> dupAction("show")),
                Ui.button("Ẩn", Icons::hide, () -> dupAction("hide")),
                moreDups), BorderLayout.NORTH);
        p.add(MainPanel.scroll(dupTable), BorderLayout.CENTER);
        return p;
    }

    private void dupAction(String action) {
        List<ApiGroup> gs = selected(dupTable, dupModel);
        if (gs.isEmpty()) {
            return;
        }
        switch (action) {
            case "lineage" -> LineageDialog.show(ui, gs.get(0));
            case "promote" -> {
                engine.promoteToOrigin(gs.get(0));
                ui.flash("Đã đặt API #" + gs.get(0).gid() + " làm API GỐC #1");
            }
            case "keep_origin" -> {
                int hidden = engine.keepOnlyOriginAndHideOthers(gs.get(0).bookKey());
                ui.flash("Đã giữ GỐC #1 và ẩn " + hidden + " bản trùng");
            }
            case "toggle_refresh" -> {
                String key = gs.get(0).bookKey();
                boolean has = engine.book().hasTag(key, themythofcrawl.model.ApiTagCatalog.TAG_REFRESH);
                engine.toggleEndpointTag(key, themythofcrawl.model.ApiTagCatalog.TAG_REFRESH, !has);
                ui.flash(!has ? "Đã gắn Tag [REFRESH]" : "Đã gỡ Tag [REFRESH]");
            }
            case "show" -> engine.show(gs);
            case "hide" -> engine.hide(gs, "");
            case "keep" -> engine.keepDuplicates(gs);
            case "open" -> {
                ui.jumpToGroup(gs.get(0));
                return;
            }
            default -> {
                if (!ui.confirmDialog("Xóa " + gs.size() + " bản?")) {
                    return;
                }
                engine.remove(gs, "xóa bản trùng");
            }
        }
        stale = true;
        ui.tick(true);
    }

    // ================================================================== Nhật ký
    private JPanel logPanel() {
        onText(logFilter, this::reload);
        logScope.setToolTipText("Đang xem nhật ký của 1 endpoint - bấm để xem tất cả");
        logScope.setHorizontalTextPosition(SwingConstants.LEADING);
        logScope.addActionListener(e -> {
            logKey = null;
            reload();
        });
        Ui.onDoubleClick(logTable, this::revertLog);
        Ui.onPopup(logTable, e -> {
            List<PlacementBook.Event> evs = selected(logTable, logModel);
            JPopupMenu m = new JPopupMenu();
            m.add(Ui.item("Hoàn tác dòng này", Icons::undo, evs.size() == 1, this::revertLog));
            m.add(Ui.item("Mở API", Icons::search, evs.size() == 1 && evs.get(0).gid() != null, () -> {
                ApiGroup g = engine.group(evs.get(0).gid());
                if (g != null) {
                    ui.jumpToGroup(g);
                } else {
                    ui.flash("API #" + evs.get(0).gid() + " không còn (đã xóa)");
                }
            }));
            m.add(Ui.item("Chỉ xem endpoint này", Icons::clock, evs.size() == 1 && evs.get(0).key() != null, () -> {
                logKey = evs.get(0).key();
                reload();
            }));
            m.show(e.getComponent(), e.getX(), e.getY());
        });
        JButton[] moreLogRef = new JButton[1];
        JButton moreLog = Ui.dropdown("Thao tác", null, "Hoàn tác, xóa nhật ký hoặc xem hướng dẫn", () -> {
            JPopupMenu m = new JPopupMenu();
            m.add(Ui.item("Hoàn tác dòng đang chọn", Icons::undo, this::revertLog));
            m.add(Ui.item("Xóa toàn bộ nhật ký vị trí...", t -> Icons.trash(t.danger.text()), () -> {
                if (ui.confirmDialog("Xóa toàn bộ nhật ký vị trí? (vị trí giữ chỗ vẫn giữ nguyên)")) {
                    engine.book().clearLog();
                    engine.changes().structure();
                    stale = true;
                    ui.tick(true);
                }
            }));
            m.addSeparator();
            m.add(Ui.item("Hướng dẫn Nhật ký vị trí...", Icons::info,
                    () -> Ui.popover(moreLogRef[0], "Nhật ký vị trí", Help.LOG)));
            m.show(moreLogRef[0], 0, moreLogRef[0].getHeight());
        });
        moreLogRef[0] = moreLog;
        JPanel p = new JPanel(new BorderLayout());
        p.add(Ui.flow(FlowLayout.LEFT, logFilter, logScope,
                Ui.button("Hoàn tác dòng chọn", Icons::undo, this::revertLog),
                moreLog), BorderLayout.NORTH);
        p.add(MainPanel.scroll(logTable), BorderLayout.CENTER);
        return p;
    }

    private void revertLog() {
        int v = logTable.getSelectedRow();
        if (v < 0) {
            return;
        }
        PlacementBook.Event ev = logModel.payload(logTable.convertRowIndexToModel(v));
        String msg = engine.revertEvent(ev);
        ui.flash(msg);
        ui.info(msg);
        stale = true;
        ui.tick(true);
    }

    // ================================================================== refresh
    /** Open a section (0 Cần xử lý, 1 Giữ chỗ, 2 trùng & ẩn, 3 nhật ký); `key` selects an endpoint. */
    void show(int section, String key) {
        ui.tabs.setSelectedComponent(panel);
        if (section == 1) {
            placeView.setSelectedIndex(0);
            placeFilter.setText("");
            logKey = key;
        }
        tabs.setSelectedIndex(section);
        reload();
        if (key != null && section == 1) {
            selectPlace(key, true);
        }
    }

    void refreshIfVisible() {
        if (stale && panel.isShowing()) {
            reload();
        }
    }

    void reload() {
        if (reloading) {
            return;
        }
        reloading = true;
        stale = false;
        try {
            List<ApiGroup> pending = engine.pendingGroups();
            int sec = tabs.getSelectedIndex();
            if (sec == 0) {
                List<ApiGroup> keep = selected(decideTable, decideModel);
                decideModel.set(Decisions.decideRows(pending));
                reselect(decideTable, decideModel, keep);
            } else if (sec == 1) {
                PlacementBook.Record keep = selectedPlace();
                placeModel.set(Decisions.placeRows(engine.book(), placeView.getSelectedIndex(), placeFilter.getText()));
                if (keep != null) {
                    selectPlace(keep.key, false);
                }
            } else if (sec == 2) {
                List<ApiGroup> keep = selected(dupTable, dupModel);
                dupModel.set(Decisions.dupRows(engine.copies(), dupView.getSelectedIndex() == 1));
                reselect(dupTable, dupModel, keep);
            } else {
                logModel.set(Decisions.logRows(engine.book(), logKey, logFilter.getText()));
                PlacementBook.Record rec = logKey == null ? null : engine.book().get(logKey);
                logScope.setText(rec != null ? "Chỉ: " + rec.method + " " + Text.shortPath(Text.nz(rec.path), 30)
                        : "Tất cả endpoint");
                logScope.setIcon(rec != null ? Icons.themed(t -> Icons.error(t.muted, 11)) : null);
                logScope.setEnabled(rec != null);
            }
            int live = 0;
            int deleted = 0;
            List<PlacementBook.Record> recs = engine.book().records();
            for (PlacementBook.Record r : recs) {
                switch (r.state()) {
                    case LIVE, PENDING -> live++;
                    case DELETED -> deleted++;
                    default -> { }
                }
            }
            placesLabel.setText(recs.size() + " endpoint · " + live + " đang có API · " + deleted
                    + " đã xóa (giữ chỗ) · click đúp để sửa chỗ");
            int dupCount = (int) engine.copies().values().stream().filter(l -> l.size() > 1).count();
            String[] titles = {"Cần xử lý (" + pending.size() + ")", "Giữ chỗ (" + recs.size() + ")",
                "API trùng & ẩn (" + dupCount + " trùng · " + engine.hiddenGroups().size() + " ẩn)",
                "Nhật ký (" + engine.book().log().size() + ")"};
            for (int i = 0; i < titles.length; i++) {
                if (!tabs.getTitleAt(i).equals(titles[i])) {
                    tabs.setTitleAt(i, titles[i]);
                }
            }
        } finally {
            reloading = false;
        }
        if (tabs.getSelectedIndex() == 0) {
            explain();
        }
    }

    // ---- Test / inspection helpers ----
    JTabbedPane tabs() {
        return tabs;
    }

    Card decideCard() {
        return decideCard;
    }

    List<JButton> inlineActionButtons() {
        return List.of(cardKeep, cardFound, cardOther, cardHide, cardDelete);
    }

    SearchField placeSearch() {
        return placeFilter;
    }

    SearchField logSearch() {
        return logFilter;
    }

    JTable decideTable() {
        return decideTable;
    }

    JTable logTable() {
        return logTable;
    }
}
