package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumnModel;
import javax.swing.filechooser.FileNameExtensionFilter;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Text;
import themythofcrawl.excel.HitLog;
import themythofcrawl.excel.NearMissIndex;
import themythofcrawl.excel.TargetEntry;
import themythofcrawl.excel.TargetList;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.export.Reports;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ResultState;
import themythofcrawl.model.RoleResult;
import themythofcrawl.ui.kit.ThemedTable;

/**
 * 'Excel' tab: every line of the imported list with what was captured for it, how many times it was
 * met and where; below, each time the selected line was met (real path, position, result).
 */
final class ExcelTab {
    static final String[] FILTERS = {"Tất cả dòng", "Chưa bắt", "Đã bắt", "Đã gặp", "Chưa gặp lần nào"};

    final JPanel panel = new JPanel(new BorderLayout(4, 4));
    private final MainPanel ui;
    private final Engine engine;
    private final JCheckBox targetOnly = new JCheckBox("Chỉ bắt API có trong danh sách");
    private final JComboBox<String> filter = new JComboBox<>(FILTERS);
    private final javax.swing.JButton clear;
    private final JLabel summary = new JLabel(" ");
    private final CoverageModel coverage = new CoverageModel();
    private final JTable table = new ThemedTable(coverage);
    private final HitModel hitModel = new HitModel();
    private final JTable hitTable = new ThemedTable(hitModel);
    private final JLabel hitsLabel = Ui.bold(" Chọn 1 dòng Excel để xem các lần đã gặp (path thực tế, vị trí, kết quả).");
    boolean stale = true;
    private long nextOk;

    ExcelTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        clear = Ui.button("Bỏ danh sách", this::clear);
        targetOnly.addActionListener(e -> setTargetOnly(targetOnly.isSelected()));
        filter.addActionListener(e -> reload());
        JPanel bar = Ui.flow(FlowLayout.LEFT, Ui.accent("Nạp file Excel (.xlsx)...", Palette.PINK, this::load), clear,
                Ui.button("Xuất Excel độ phủ (.xlsx)...", ui::exportExcel),
                Ui.button("Gửi các API đã bắt trong danh sách", this::sendListed), targetOnly,
                Ui.button("Đặt lại bộ đếm lượt gặp", () -> {
                    if (engine.targets() != null && ui.confirmDialog("Đặt lại số lượt gặp của mọi dòng Excel về 0?")) {
                        engine.resetHits();
                        stale = true;
                        refreshIfVisible();
                    }
                }), new JLabel("   Xem:"), filter, Ui.info(Help.EXCEL, "Excel"));
        summary.setFont(summary.getFont().deriveFont(Font.BOLD, summary.getFont().getSize2D() + 1));
        summary.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        top.add(summary, BorderLayout.SOUTH);
        panel.add(top, BorderLayout.NORTH);

        Ui.style(table);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        AuditRenderer r = new AuditRenderer(coverage, null, 8, 1, 0, 4);
        int[] w = {64, 80, 300, 220, 150, 110, 260, 220, 240, 230, 380, 170, 300};
        for (int c = 0; c < w.length; c++) {
            table.getColumnModel().getColumn(c).setPreferredWidth(w[c]);
            table.getColumnModel().getColumn(c).setCellRenderer(r);
        }
        TableColumnModel cm = table.getColumnModel();
        cm.moveColumn(CoverageModel.HITS_COL, 5);
        cm.moveColumn(CoverageModel.PLACES_COL, 6);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showHits();
            }
        });
        Ui.onDoubleClick(table, () -> {
            int v = table.getSelectedRow();
            if (v >= 0) {
                List<ApiGroup> gs = coverage.groupsAt(table.convertRowIndexToModel(v));
                if (!gs.isEmpty()) {
                    ui.jumpToGroup(gs.get(0));
                }
            }
        });
        Ui.onPopup(table, e -> {
            int v = table.getSelectedRow();
            if (v < 0) {
                return;
            }
            int mr = table.convertRowIndexToModel(v);
            TargetEntry te = coverage.entryAt(mr);
            List<ApiGroup> gs = coverage.groupsAt(mr);
            javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
            m.add(Ui.item("🔎 Mở API đã bắt (" + gs.size() + ")", !gs.isEmpty(), () -> ui.jumpToGroup(gs.get(0))));
            m.add(Ui.item("▶ Gửi " + gs.size() + " API của dòng này", !gs.isEmpty(), () -> ui.send(gs)));
            m.add(Ui.item("📤 Đẩy -> Burp Repeater", !gs.isEmpty(), () -> ui.push(gs)));
            m.add(Ui.item("📋 Copy STT + Path", !gs.isEmpty(), () -> ui.copy(gs)));
            m.addSeparator();
            m.add(Ui.item("📋 Copy dòng: " + te.sttText() + ". " + te.methodLabel() + " " + te.path(), () -> Ui.copyToClipboard(
                    te.sttText() + "\t" + te.methodLabel() + "\t" + te.path() + "\t" + Text.nz(te.position()))));
            m.show(e.getComponent(), e.getX(), e.getY());
        });

        Ui.style(hitTable);
        AuditRenderer hr = new AuditRenderer(hitModel, null, 2, 4, 0);
        int[] hw = {45, 120, 130, 70, 70, 320, 160, 260, 260, 70, 200};
        for (int c = 0; c < hw.length; c++) {
            hitTable.getColumnModel().getColumn(c).setPreferredWidth(hw[c]);
            hitTable.getColumnModel().getColumn(c).setCellRenderer(hr);
        }
        Ui.onDoubleClick(hitTable, () -> {
            int v = hitTable.getSelectedRow();
            Integer gid = v < 0 ? null : hitModel.events.get(v).gid();
            ApiGroup g = gid == null ? null : engine.group(gid);
            if (g != null) {
                ui.jumpToGroup(g);
            } else if (gid != null) {
                ui.flash("API #" + gid + " không còn (đã xóa)");
            }
        });
        hitsLabel.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        JPanel hits = new JPanel(new BorderLayout());
        hits.add(hitsLabel, BorderLayout.NORTH);
        hits.add(MainPanel.scroll(hitTable), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, MainPanel.scroll(table), hits);
        split.setResizeWeight(0.62);
        split.setOneTouchExpandable(true);
        panel.add(split, BorderLayout.CENTER);
        controls(engine.groups());
    }

    // ------------------------------------------------------------------ actions
    void load() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Excel (.xlsx)", "xlsx"));
        if (fc.showOpenDialog(ui.root()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = fc.getSelectedFile();
        TargetList t;
        try {
            List<String> sheets = Xlsx.sheetNames(f.toPath());
            int index = 0;
            if (sheets.size() > 1) {
                Object pick = JOptionPane.showInputDialog(ui.root(), "File có " + sheets.size()
                        + " sheet. Chọn sheet chứa danh sách API:", Text.EXT_NAME, JOptionPane.QUESTION_MESSAGE, null,
                        sheets.toArray(), sheets.get(0));
                if (pick == null) {
                    return;
                }
                index = sheets.indexOf(pick);
            }
            Xlsx.Sheet s = Xlsx.read(f.toPath(), index);
            t = TargetList.fromRows(s.rows(), f.getName(), s.name());
        } catch (Exception e) {
            ui.error("Không đọc được file Excel: " + e.getMessage());
            return;
        }
        if (t.size() == 0) {
            JOptionPane.showMessageDialog(ui.root(), "Không tìm thấy dòng API nào. File cần có cột PATH (hoặc URL / Endpoint) "
                    + "chứa đường dẫn bắt đầu bằng '/'.", Text.EXT_NAME, JOptionPane.WARNING_MESSAGE);
            return;
        }
        engine.setTargets(t);
        long already = Reports.coverage(t, engine.groups()).values().stream().filter(l -> !l.isEmpty()).count();
        engine.log("Đã nạp " + t.size() + " API từ file Excel " + t.source() + " (sheet " + t.sheet() + "); khớp sẵn " + already
                + " API đã bắt.", null);
        boolean only = JOptionPane.showConfirmDialog(ui.root(), "Đã nạp " + t.size() + " API từ " + t.source() + ".\n" + already
                + " API trong danh sách đã được bắt từ trước.\n\nBật chế độ 'Chỉ bắt API có trong danh sách'?\n"
                + "(Không: vẫn bắt mọi API, danh sách chỉ để theo dõi độ phủ)", Text.EXT_NAME, JOptionPane.YES_NO_OPTION)
                == JOptionPane.YES_OPTION;
        setTargetOnly(only);
        ui.tabs.setSelectedComponent(panel);
    }

    private void clear() {
        if (engine.targets() == null || !ui.confirmDialog("Bỏ danh sách API từ file " + engine.targets().source() + "?")) {
            return;
        }
        setTargetOnly(false);
        engine.setTargets(null);
        engine.log("Đã bỏ danh sách API từ file Excel.", null);
        stale = true;
        ui.tick(true);
    }

    private void setTargetOnly(boolean on) {
        ui.update(c -> c.targetOnly = on);
        stale = true;
        ui.tick(true);
    }

    private void sendListed() {
        int n = 0;
        for (ApiGroup g : engine.groups()) {
            if (g.target() == null) {
                continue;
            }
            List<RoleResult> rs = g.results().stream().filter(r -> ResultState.NOT_SENT.equals(r.state())).toList();
            if (!rs.isEmpty()) {
                engine.replay(g, rs);
                n++;
            }
        }
        if (n == 0) {
            ui.info("Không có API nào trong danh sách đang chờ gửi.");
        } else {
            engine.log("Xếp hàng gửi " + n + " API thuộc danh sách Excel.", null);
        }
    }

    /** Show one line (from the [Excel #STT] badge of an API). */
    void select(TargetEntry e) {
        if (e == null) {
            return;
        }
        stale = true;
        nextOk = 0;
        if (filter.getSelectedIndex() != 0) {
            filter.setSelectedIndex(0);
        }
        reload();
        MainPanel.selectRow(table, coverage.rowOf(e));
    }

    // ------------------------------------------------------------------ refresh
    void refreshIfVisible() {
        if (!stale || !table.isShowing() || System.currentTimeMillis() < nextOk) {
            return;
        }
        long t0 = System.nanoTime();
        reload();
        nextOk = System.currentTimeMillis() + Math.min(4000, (System.nanoTime() - t0) / 1_000_000 * 6);
    }

    private void reload() {
        stale = false;
        TargetEntry keep = selected();
        List<ApiGroup> groups = engine.groups();
        coverage.reload(engine.targets(), groups, filter.getSelectedIndex(), engine.hits());
        if (keep != null) {
            int r = coverage.rowOf(keep);
            if (r >= 0) {
                int v = table.convertRowIndexToView(r);
                if (v >= 0) {
                    table.setRowSelectionInterval(v, v);
                }
            }
        }
        showHits();
        controls(groups);
    }

    private TargetEntry selected() {
        int v = table.getSelectedRow();
        return v < 0 ? null : coverage.entryAt(table.convertRowIndexToModel(v));
    }

    private void showHits() {
        TargetEntry e = selected();
        if (e == null) {
            hitModel.set(List.of());
            hitsLabel.setText(" Chọn 1 dòng Excel để xem các lần đã gặp (path thực tế, vị trí, kết quả).");
            return;
        }
        HitLog h = engine.hits();
        int[] st = h.stats(e);
        hitModel.set(h.events(e));
        hitsLabel.setText(" Dòng #" + e.sttText() + " · " + e.methodLabel() + " " + e.path() + "   ·   gặp " + h.count(e) + " lần"
                + " (bắt mới " + st[0] + " · gặp lại " + st[1] + " · bị lọc " + st[2] + ")"
                + (h.count(e) > HitLog.MAX_EVENTS ? "   ·   chỉ giữ " + HitLog.MAX_EVENTS + " lần gần nhất" : "")
                + "   ·   click đúp 1 dòng để mở API");
    }

    private void controls(List<ApiGroup> groups) {
        TargetList t = engine.targets();
        boolean loaded = t != null;
        targetOnly.setEnabled(loaded);
        clear.setEnabled(loaded);
        targetOnly.setSelected(engine.config().targetOnly);
        if (!loaded) {
            summary.setText("Chưa nạp file. Bấm 'Nạp file Excel' - file cần các cột STT | PROTOCOL (method) | PATH | "
                    + "POSITION (tùy chọn); tham số {id} [id] (id) <id> :id * trong path được hiểu là giá trị bất kỳ.");
            return;
        }
        Map<TargetEntry, List<ApiGroup>> cov = Reports.coverage(t, groups);
        long hit = cov.values().stream().filter(l -> !l.isEmpty()).count();
        int total = t.size();
        long outside = groups.stream().filter(g -> g.target() == null).count();
        long copied = groups.stream().filter(g -> g.copiedLabel() != null).count();
        int dups = t.duplicates().size();
        StringBuilder extra = new StringBuilder();
        if (outside > 0) {
            extra.append("  ·  ").append(outside).append(" API ngoài danh sách");
        }
        if (dups > 0) {
            extra.append("  ·  ").append(dups).append(" dòng trùng trong file");
        }
        if (copied > 0) {
            extra.append("  ·  📋 đã copy ").append(copied);
        }
        summary.setText(t.source() + "  ·  " + total + " API  ·  Đã bắt " + hit + "/" + total + " (" + (total > 0 ? hit * 100 / total : 0)
                + "%)  ·  Còn " + (total - hit) + " chưa bắt  ·  Đã gặp " + engine.hits().rowsSeen() + "/" + total + " dòng" + extra
                + "  ·  Chế độ: " + (engine.config().targetOnly ? "chỉ bắt API trong danh sách" : "bắt mọi API (danh sách chỉ để đối chiếu)"));
    }

    // ------------------------------------------------------------------ models
    /** One row per line of the imported list, with what was captured for it. */
    static final class CoverageModel extends AbstractTableModel implements AuditRows {
        static final String[] COLS = {"STT", "Method", "Path (trong file)", "POSITION", "Trạng thái", "API #", "Path thực tế",
            "Vị trí tìm thấy", "Đánh giá", "📋 Đã copy", "Gợi ý gần đúng (dòng chưa bắt)", "Lượt gặp",
            "Các vị trí đã gặp (mới nhất trước)"};
        static final int HITS_COL = 11;
        static final int PLACES_COL = 12;

        private record Row(Object[] cells, AuditLevel level, List<ApiGroup> found, TargetEntry entry) {
        }

        private List<Row> rows = new ArrayList<>();
        private final Map<TargetEntry, Integer> index = new java.util.IdentityHashMap<>();

        void reload(TargetList targets, List<ApiGroup> groups, int filterIdx, HitLog hits) {
            List<Row> out = new ArrayList<>();
            NearMissIndex near = null;
            for (Map.Entry<TargetEntry, List<ApiGroup>> en : Reports.coverage(targets, groups).entrySet()) {
                TargetEntry e = en.getKey();
                List<ApiGroup> found = new ArrayList<>(en.getValue());
                if ((filterIdx == 1 && !found.isEmpty()) || (filterIdx == 2 && found.isEmpty())) {
                    continue;
                }
                int met = hits.count(e);
                if ((filterIdx == 3 && met == 0) || (filterIdx == 4 && met > 0)) {
                    continue;
                }
                found.sort(Comparator.comparingInt(ApiGroup::gid));
                ApiGroup first = found.isEmpty() ? null : found.get(0);
                AuditLevel level;
                String state;
                if (first == null) {
                    level = AuditLevel.NONE;
                    state = "Chưa bắt";
                } else {
                    level = Audit.display(first);
                    List<RoleResult> rs = first.results().isEmpty() ? List.of(first.origResult()) : first.results();
                    boolean sent = rs.stream().anyMatch(r -> ResultState.finished(r.state()));
                    state = "Đã bắt · " + (sent ? "đã gửi" : "chưa gửi") + (found.size() > 1 ? " (" + found.size() + " lần)" : "");
                    if (level == AuditLevel.NONE) {
                        level = AuditLevel.REDIRECT;   // captured but not sent yet: blue, distinct from "not captured"
                    }
                }
                Set<String> positions = new LinkedHashSet<>();
                for (ApiGroup g : found) {
                    positions.add(g.branch());
                }
                StringBuilder ids = new StringBuilder();
                for (int i = 0; i < Math.min(4, found.size()); i++) {
                    ids.append(i > 0 ? ", " : "").append(Text.apiLabel(found.get(i).gid()));
                }
                if (found.size() > 4) {
                    ids.append(" ...");
                }
                String copied = found.stream().map(ApiGroup::copiedLabel).filter(java.util.Objects::nonNull).findFirst().orElse("");
                String hint = "";
                if (found.isEmpty()) {
                    if (near == null) {
                        near = new NearMissIndex(groups);
                    }
                    NearMissIndex.Suggestion s = near.suggest(e);
                    if (s != null) {
                        hint = Text.apiLabel(s.group().gid()) + " " + s.group().method() + " " + Text.stripQuery(s.group().path())
                                + "  (" + s.note() + ")";
                    }
                }
                List<String> where = hits.positions(e);
                Object[] cells = {e.sttText(), e.methodLabel(), e.path(), Text.nz(e.position()), state, ids.toString(),
                    first != null ? Text.stripQuery(first.path()) : "", String.join("; ", positions),
                    first != null ? Text.nz(Audit.summary(first)) : "", copied, hint, hits.cell(e),
                    String.join("; ", where.subList(0, Math.min(6, where.size())))};
                out.add(new Row(cells, level, found, e));
            }
            rows = out;
            index.clear();
            for (int i = 0; i < rows.size(); i++) {
                index.put(rows.get(i).entry(), i);
            }
            fireTableDataChanged();
        }

        TargetEntry entryAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r).entry() : null;
        }

        List<ApiGroup> groupsAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r).found() : List.of();
        }

        int rowOf(TargetEntry e) {
            return index.getOrDefault(e, -1);
        }

        @Override
        public AuditLevel levelAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r).level() : AuditLevel.NONE;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLS[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return String.class;
        }

        @Override
        public Object getValueAt(int r, int c) {
            return rows.get(r).cells()[c];
        }
    }

    /** Every time the selected line was met while crawling (newest first). */
    static final class HitModel extends AbstractTableModel implements AuditRows {
        static final String[] COLS = {"Lần", "Thời gian", "Kết quả", "API #", "Method", "Path thực tế", "Host",
            "Vị trí đang đứng", "Lưu vào nhánh", "Nguồn", "Ghi chú"};
        List<HitLog.Event> events = List.of();

        void set(List<HitLog.Event> evs) {
            List<HitLog.Event> l = new ArrayList<>(evs);
            Collections.reverse(l);
            events = l;
            fireTableDataChanged();
        }

        @Override
        public AuditLevel levelAt(int r) {
            if (r < 0 || r >= events.size()) {
                return AuditLevel.NONE;
            }
            return switch (events.get(r).result()) {
                case NEW -> AuditLevel.OK;
                case DUP -> AuditLevel.REDIRECT;
                case FILTERED -> AuditLevel.INFO;
            };
        }

        @Override
        public int getRowCount() {
            return events.size();
        }

        @Override
        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLS[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            HitLog.Event e = events.get(r);
            return switch (c) {
                case 0 -> e.n();
                case 1 -> e.time();
                case 2 -> e.result().label;
                case 3 -> e.gid() != null ? Text.apiLabel(e.gid()) : "";
                case 4 -> e.method();
                case 5 -> e.path();
                case 6 -> e.host();
                case 7 -> e.branch();
                case 8 -> e.filed();
                case 9 -> e.source();
                default -> Text.nz(e.note());
            };
        }
    }
}
