package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.place.Decisions;
import themythofcrawl.place.PlacementBook;

/**
 * 'Audit' tab: Cần xử lý (what waits for a decision, explained) · Giữ chỗ (the remembered place of
 * every endpoint) · API trùng & ẩn · Nhật ký (every placement change, undoable).
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

    private final JTextField placeFilter = new JTextField(16);
    private final JComboBox<String> placeView = new JComboBox<>(new String[] {"Tất cả", "Đang có API", "👻 Đã xóa (giữ chỗ)",
        "⏳ Chờ xác nhận"});
    private final JLabel placesLabel = new JLabel(" ");
    private final RowsModel<PlacementBook.Record> placeModel = new RowsModel<>(Decisions.PLACE_COLS);
    private final JTable placeTable;

    private final JComboBox<String> dupView = new JComboBox<>(new String[] {"Tất cả API trùng & ẩn", "Chỉ đang ẩn"});
    private final RowsModel<ApiGroup> dupModel = new RowsModel<>(Decisions.DUP_COLS);
    private final JTable dupTable;

    private final JTextField logFilter = new JTextField(16);
    private final JButton logScope = new JButton("Tất cả endpoint");
    private final RowsModel<PlacementBook.Event> logModel = new RowsModel<>(Decisions.LOG_COLS);
    private final JTable logTable;
    private String logKey;

    AuditTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        decideTable = table(decideModel, new int[] {150, 80, 300, 300, 300}, 0, null, 1);
        placeTable = table(placeModel, new int[] {65, 280, 150, 280, 80, 140, 70, 60, 90, 110, 240, 80}, 5, 0, 3);
        dupTable = table(dupModel, new int[] {320, 70, 80, 320, 140, 70, 80}, 4, null, 1);
        logTable = table(logModel, new int[] {45, 110, 230, 70, 65, 260, 240, 240, 300}, 2, 4, 0);
        tabs.addTab("Cần xử lý", decidePanel());
        tabs.addTab("Giữ chỗ", placesPanel());
        tabs.addTab("API trùng & ẩn", dupsPanel());
        tabs.addTab("Nhật ký", logPanel());
        tabs.addChangeListener(e -> reload());
        panel.add(tabs, BorderLayout.CENTER);
    }

    private static JTable table(RowsModel<?> m, int[] widths, int auditCol, Integer methodCol, int... bold) {
        JTable t = new JTable(m);
        Ui.style(t);
        t.setAutoCreateRowSorter(true);
        AuditRenderer r = new AuditRenderer(m, null, auditCol, methodCol, bold);
        for (int c = 0; c < widths.length; c++) {
            t.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
            t.getColumnModel().getColumn(c).setCellRenderer(r);
        }
        return t;
    }

    private static JPanel bar(String help, String title, Component... controls) {
        JPanel b = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
        b.add(Ui.info(help, title));
        for (Component c : controls) {
            b.add(c);
        }
        return b;
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
        JButton keep = Ui.button("✔ Giữ chỗ hiện tại", () -> decide("keep"));
        keep.setFont(keep.getFont().deriveFont(Font.BOLD));
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
            m.add(Ui.item("✔ Giữ chỗ hiện tại", !gs.isEmpty(), () -> decide("keep")));
            m.add(Ui.item("↪ Về nơi vừa gặp", !gs.isEmpty(), () -> decide("found")));
            m.add(Ui.item("📁 Path khác...", !gs.isEmpty(), () -> decide("other")));
            m.add(Ui.item("🙈 Không giữ (ẩn)", !gs.isEmpty(), () -> decide("hide")));
            m.add(Ui.item("🗑 Xóa", !gs.isEmpty(), () -> decide("delete")));
            m.addSeparator();
            m.add(Ui.item("🔎 Mở ở tab API", gs.size() == 1, () -> ui.jumpToGroup(gs.get(0))));
            m.add(Ui.item("🧭 Lịch sử vị trí", gs.size() == 1, () -> show(1, gs.get(0).bookKey())));
            m.add(Ui.item("📋 Copy STT + Path", !gs.isEmpty(), () -> ui.copy(gs)));
            m.show(e.getComponent(), e.getX(), e.getY());
        });
        decideText.setEditable(false);
        decideText.setLineWrap(true);
        decideText.setWrapStyleWord(true);
        decideText.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, MainPanel.scroll(decideTable), MainPanel.scroll(decideText));
        split.setResizeWeight(0.65);
        JPanel p = new JPanel(new BorderLayout());
        p.add(bar(Help.DECIDE, "Cần xử lý", keep, Ui.button("↪ Về nơi vừa gặp", () -> decide("found")),
                Ui.button("📁 Path khác...", () -> decide("other")), Ui.button("🙈 Không giữ", () -> decide("hide")),
                Ui.button("🗑 Xóa", () -> decide("delete")), Ui.button("Chọn hết", decideTable::selectAll)), BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private void explain() {
        List<ApiGroup> gs = selected(decideTable, decideModel);
        if (gs.isEmpty()) {
            decideText.setText(decideModel.getRowCount() == 0 ? "Không có việc nào cần xử lý. 🎉"
                    : "Chọn 1 dòng để xem giải thích và lựa chọn.");
        } else if (gs.size() == 1) {
            decideText.setText(Decisions.text(gs.get(0), engine.copiesOf(gs.get(0).bookKey())));
        } else {
            decideText.setText(gs.size() + " API đang chọn. Các nút phía trên áp dụng cho tất cả.");
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
            case "keep" -> msg = "✔ Đã giữ chỗ hiện tại cho " + engine.confirm(gs, "giữ chỗ hiện tại") + " API";
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
                msg = "↪ " + n + " API đã ở nơi vừa gặp";
            }
            case "other" -> msg = "📁 Đã chuyển " + ui.moveDialog(gs) + " API";
            case "hide" -> msg = "🙈 Đã ẩn " + engine.hide(gs, "không giữ") + " API (xem lại ở tab API trùng & ẩn)";
            default -> {
                if (!ui.confirmDialog("Xóa " + gs.size() + " API?")) {
                    return;
                }
                engine.remove(gs, "xóa từ Cần xử lý");
                msg = "🗑 Đã xóa " + gs.size() + " API";
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
        JButton more = new JButton("Thêm ▾");
        more.addActionListener(e -> {
            JPopupMenu m = new JPopupMenu();
            m.add(Ui.item("✖ Quên giữ chỗ (lần sau vào đích hiện tại)", this::forgetPlace));
            m.add(Ui.item("♻ Cho phép bắt lại ngay (xóa bộ nhớ chống trùng)", this::recapture));
            m.add(Ui.item("🔎 Mở API đang có", this::openPlaceApi));
            m.add(Ui.item("🕘 Xem nhật ký của endpoint này", this::logOfSelected));
            m.show(more, 0, more.getHeight());
        });
        Ui.onDoubleClick(placeTable, () -> editPlace(selectedPlace()));
        Ui.onPopup(placeTable, e -> {
            PlacementBook.Record r = selectedPlace();
            JPopupMenu m = new JPopupMenu();
            m.add(Ui.item("✏ Sửa chỗ...", r != null, () -> editPlace(r)));
            m.add(Ui.item("✖ Quên giữ chỗ", r != null, this::forgetPlace));
            m.add(Ui.item("♻ Cho phép bắt lại ngay", r != null, this::recapture));
            m.add(Ui.item("🔎 Mở API đang có", r != null && r.gid != null, this::openPlaceApi));
            m.add(Ui.item("🕘 Xem nhật ký của endpoint này", r != null, this::logOfSelected));
            m.add(Ui.item("📋 Copy endpoint", r != null, () -> Ui.copyToClipboard(r.method + " " + r.host + " " + r.path)));
            m.show(e.getComponent(), e.getX(), e.getY());
        });
        placesLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 2, 8));
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar(Help.PLACES, "Giữ chỗ", new JLabel("Lọc:"), placeFilter, placeView,
                Ui.button("✏ Sửa chỗ...", () -> editPlace(selectedPlace())), Ui.button("➕ Thêm...", this::addPlace), more),
                BorderLayout.NORTH);
        top.add(placesLabel, BorderLayout.SOUTH);
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

    /** 'Sửa vị trí giữ chỗ' (also from the 👻 rows of the trees). */
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
            ui.flash("♻ Đã xóa " + n + " khóa chống trùng: request tiếp theo của endpoint này sẽ được bắt");
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
        Ui.onDoubleClick(dupTable, () -> dupAction("open"));
        Ui.onPopup(dupTable, e -> {
            List<ApiGroup> gs = selected(dupTable, dupModel);
            JPopupMenu m = new JPopupMenu();
            for (String[] a : new String[][] {{"🔎 Mở", "open"}, {"👁 Hiện", "show"}, {"🙈 Ẩn", "hide"}, {"✔ Giữ (bản chờ)", "keep"},
                {"🗑 Xóa", "delete"}}) {
                m.add(Ui.item(a[0], !gs.isEmpty(), () -> dupAction(a[1])));
            }
            m.add(Ui.item("🧭 Lịch sử vị trí", gs.size() == 1, () -> show(1, gs.get(0).bookKey())));
            m.show(e.getComponent(), e.getX(), e.getY());
        });
        JPanel p = new JPanel(new BorderLayout());
        p.add(bar(Help.DUPS, "API trùng & ẩn", dupView, Ui.button("👁 Hiện", () -> dupAction("show")),
                Ui.button("🙈 Ẩn", () -> dupAction("hide")), Ui.button("✔ Giữ (bản chờ)", () -> dupAction("keep")),
                Ui.button("🔎 Mở", () -> dupAction("open")), Ui.button("🗑 Xóa", () -> dupAction("delete"))), BorderLayout.NORTH);
        p.add(MainPanel.scroll(dupTable), BorderLayout.CENTER);
        return p;
    }

    private void dupAction(String action) {
        List<ApiGroup> gs = selected(dupTable, dupModel);
        if (gs.isEmpty()) {
            return;
        }
        switch (action) {
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
        logScope.addActionListener(e -> {
            logKey = null;
            reload();
        });
        Ui.onDoubleClick(logTable, this::revertLog);
        Ui.onPopup(logTable, e -> {
            List<PlacementBook.Event> evs = selected(logTable, logModel);
            JPopupMenu m = new JPopupMenu();
            m.add(Ui.item("↶ Hoàn tác dòng này", evs.size() == 1, this::revertLog));
            m.add(Ui.item("🔎 Mở API", evs.size() == 1 && evs.get(0).gid() != null, () -> {
                ApiGroup g = engine.group(evs.get(0).gid());
                if (g != null) {
                    ui.jumpToGroup(g);
                } else {
                    ui.flash("API #" + evs.get(0).gid() + " không còn (đã xóa)");
                }
            }));
            m.add(Ui.item("🕘 Chỉ xem endpoint này", evs.size() == 1 && evs.get(0).key() != null, () -> {
                logKey = evs.get(0).key();
                reload();
            }));
            m.show(e.getComponent(), e.getX(), e.getY());
        });
        JPanel p = new JPanel(new BorderLayout());
        p.add(bar(Help.LOG, "Nhật ký vị trí", new JLabel("Lọc:"), logFilter, logScope,
                Ui.button("↶ Hoàn tác dòng chọn", this::revertLog), Ui.button("Xóa nhật ký...", () -> {
                    if (ui.confirmDialog("Xóa toàn bộ nhật ký vị trí? (vị trí giữ chỗ vẫn giữ nguyên)")) {
                        engine.book().clearLog();
                        engine.changes().structure();
                        stale = true;
                        ui.tick(true);
                    }
                })), BorderLayout.NORTH);
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

    private void reload() {
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
                logScope.setText(rec != null ? "Chỉ: " + rec.method + " " + Text.shortPath(Text.nz(rec.path), 30) + " ✖"
                        : "Tất cả endpoint");
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
            placesLabel.setText(recs.size() + " endpoint · " + live + " đang có API · 👻 " + deleted
                    + " đã xóa (giữ chỗ) · click đúp để sửa chỗ");
            int dupCount = (int) engine.copies().values().stream().filter(l -> l.size() > 1).count();
            String[] titles = {"Cần xử lý (" + pending.size() + ")", "Giữ chỗ (" + recs.size() + ")",
                "API trùng & ẩn (" + dupCount + " ⧉ · " + engine.hiddenGroups().size() + " 🙈)",
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
}
