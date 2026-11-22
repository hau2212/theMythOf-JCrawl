package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Json;
import themythofcrawl.core.Text;
import themythofcrawl.project.ProjectConfig;
import themythofcrawl.project.ProjectData;
import themythofcrawl.project.ProjectSync;
import themythofcrawl.project.SyncRecord;
import themythofcrawl.ui.theme.Theme;

/**
 * V_11.5 "Workspace đồng đội (chỉ xem)": the branch tree and APIs of an account someone else crawls, with status,
 * role results, bugs, tags, Q&A and the masked request / response (downloaded when opened, cached). Only when the PIC
 * turned on "Chia sẻ chi tiết".
 */
final class TeammateDialog {
    private TeammateDialog() {
    }

    /** One API of a teammate (an "api" record). */
    record Api(SyncRecord rec) {
        String letter() {
            return rec.account();
        }

        String member() {
            return rec.owner();
        }

        int gid() {
            return (int) rec.num("gid");
        }

        @Override
        public String toString() {
            long http = rec.num("http");
            return "API " + gid() + "  " + rec.str("method") + " " + Text.shortPath(rec.str("path"), 70) + (http > 0 ? "   ·   " + http : "   ·   chưa gửi");
        }
    }

    static List<Api> apis(ProjectService ps) {
        List<Api> out = new ArrayList<>();
        String me = ps.me();
        for (SyncRecord r : ps.store().records().values()) {
            if (SyncRecord.API.equals(r.type()) && !r.deleted() && !r.owner().equalsIgnoreCase(me)) {
                out.add(new Api(r));
            }
        }
        out.sort(Comparator.comparing((Api a) -> a.letter()).thenComparing(Api::member).thenComparingInt(Api::gid));
        return out;
    }

    static void open(MainPanel ui, String focusRowId) {
        ProjectService ps = ui.project;
        ProjectConfig cfg = ps.config();
        if (!cfg.shareDetails) {
            ui.info("PIC chưa bật \"Chia sẻ chi tiết: request / response + Cây\" (Làm chung > PIC > D. Danh sách chuẩn).\n"
                    + "Khi chưa bật, Excel > Dự án vẫn có đủ trạng thái, lỗi, tag, Q&A của mọi tài khoản.");
            return;
        }
        List<Api> all = apis(ps);
        if (all.isEmpty()) {
            ui.info("Chưa nhận được workspace nào của đồng đội (đồng bộ mỗi ~12 giây; đồng đội cần dùng V_11.5 trở lên).");
            return;
        }
        Map<String, List<Api>> byOwner = new LinkedHashMap<>();
        for (Api a : all) {
            byOwner.computeIfAbsent(a.letter() + "|" + a.member(), k -> new ArrayList<>()).add(a);
        }
        JComboBox<String> who = new JComboBox<>();
        List<String> keys = new ArrayList<>(byOwner.keySet());
        for (String k : keys) {
            String[] p = k.split("\\|", 2);
            who.addItem(cfg.accountDisplay(p[0]) + " (" + p[1] + ", chỉ xem) · " + byOwner.get(k).size() + " API");
        }
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Workspace");
        DefaultTreeModel model = new DefaultTreeModel(root);
        JTree tree = new JTree(model);
        tree.setRootVisible(false);
        JTextArea info = area();
        JTextArea req = area();
        JTextArea resp = area();
        req.setFont(Theme.current().monoFont);
        resp.setFont(Theme.current().monoFont);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Thông tin", new JScrollPane(info));
        tabs.addTab("Request (đã che)", new JScrollPane(req));
        tabs.addTab("Response (đã che)", new JScrollPane(resp));
        Runnable fill = () -> {
            root.removeAllChildren();
            int i = who.getSelectedIndex();
            if (i < 0) {
                return;
            }
            Map<String, DefaultMutableTreeNode> folders = new LinkedHashMap<>();
            DefaultMutableTreeNode select = null;
            List<Api> list = new ArrayList<>(byOwner.get(keys.get(i)));
            list.sort(Comparator.comparing((Api a) -> a.rec().str("branch")).thenComparingInt(Api::gid));
            for (Api a : list) {
                if (Boolean.TRUE.equals(a.rec().data().get("hidden"))) {
                    continue;
                }
                DefaultMutableTreeNode parent = root;
                StringBuilder key = new StringBuilder();
                for (String part : Hierarchy.split(Text.blank(a.rec().str("branch")) ? Hierarchy.DEFAULT_PATH : a.rec().str("branch"))) {
                    key.append('/').append(part);
                    DefaultMutableTreeNode f = folders.get(key.toString());
                    if (f == null) {
                        f = new DefaultMutableTreeNode(part);
                        folders.put(key.toString(), f);
                        parent.add(f);
                    }
                    parent = f;
                }
                DefaultMutableTreeNode leaf = new DefaultMutableTreeNode(a);
                parent.add(leaf);
                if (focusRowId != null && focusRowId.equals(a.rec().str("row")) && select == null) {
                    select = leaf;
                }
            }
            model.reload();
            for (int r = 0; r < tree.getRowCount(); r++) {
                tree.expandRow(r);
            }
            if (select != null) {
                TreePath tp = new TreePath(select.getPath());
                tree.setSelectionPath(tp);
                tree.scrollPathToVisible(tp);
            }
        };
        who.addActionListener(Ui.safe(e -> fill.run()));
        tree.addTreeSelectionListener(e -> {
            Object o = e.getPath().getLastPathComponent();
            if (!(o instanceof DefaultMutableTreeNode n) || !(n.getUserObject() instanceof Api a)) {
                return;
            }
            info.setText(describe(ps, a));
            info.setCaretPosition(0);
            req.setText("Đang tải...");
            resp.setText("");
            String file = a.rec().str("file");
            if (file.isEmpty()) {
                req.setText("Chưa có (máy của " + a.member() + " sẽ gửi ở lần đồng bộ tới).");
                return;
            }
            new SwingWorker<Map<String, Object>, Void>() {
                @Override
                protected Map<String, Object> doInBackground() throws Exception {
                    ProjectSync s = ps.sync();
                    return s == null ? null : s.detail(file);
                }

                @Override
                protected void done() {
                    try {
                        Map<String, Object> d = get();
                        boolean cut = d != null && Json.bool(d, "truncated", false);
                        req.setText(d == null ? "(không có)" : Json.str(d, "request", "") + (cut ? "\n\n[đã cắt ở 256 KB]" : ""));
                        resp.setText(d == null ? "(không có)" : Json.str(d, "response", ""));
                        req.setCaretPosition(0);
                        resp.setCaretPosition(0);
                    } catch (Exception ex) {
                        req.setText("Không tải được: " + ProjectDialogs.rootMessage(ex));
                    }
                }
            }.execute();
        });
        if (focusRowId != null) {
            for (int i = 0; i < keys.size(); i++) {
                for (Api a : byOwner.get(keys.get(i))) {
                    if (focusRowId.equals(a.rec().str("row"))) {
                        who.setSelectedIndex(i);
                        i = keys.size();
                        break;
                    }
                }
            }
        }
        fill.run();
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(tree), tabs);
        split.setResizeWeight(0.4);
        JDialog dlg = new JDialog(Ui.ownerWindow(ui.root()), "Workspace đồng đội", java.awt.Dialog.ModalityType.MODELESS);
        JPanel body = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        body.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        body.add(Ui.dialogHeader("Workspace đồng đội (chỉ xem)", "Cây và API của tài khoản người khác crawl; request / response đã che"
                + " token, cookie, mật khẩu. Không gửi lại / sửa được ở đây."), BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(0, 4));
        center.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Tài khoản:"), who), BorderLayout.NORTH);
        center.add(split, BorderLayout.CENTER);
        body.add(center, BorderLayout.CENTER);
        body.add(Ui.flow(FlowLayout.RIGHT, Ui.secondaryBtn("Đóng", null, dlg::dispose)), BorderLayout.SOUTH);
        dlg.setContentPane(body);
        dlg.setSize(new Dimension(Theme.current().scaled(1150), Theme.current().scaled(720)));
        dlg.setLocationRelativeTo(ui.root());
        dlg.setVisible(true);
    }

    private static JTextArea area() {
        JTextArea t = new JTextArea();
        t.setEditable(false);
        t.setLineWrap(false);
        return t;
    }

    @SuppressWarnings("unchecked")
    static String describe(ProjectService ps, Api a) {
        SyncRecord r = a.rec();
        StringBuilder sb = new StringBuilder();
        sb.append(r.str("method")).append(' ').append(r.str("path")).append('\n');
        sb.append("Host: ").append(r.str("host")).append("\nNhánh: ").append(r.str("branch")).append('\n');
        sb.append("Tài khoản: ").append(ps.config().accountDisplay(a.letter())).append(" · người crawl: ").append(a.member()).append('\n');
        long http = r.num("http");
        sb.append("Status: ").append(http > 0 ? http : "chưa gửi").append("\n\n");
        Object roles = r.data().get("roles");
        if (roles instanceof List<?> l && !l.isEmpty()) {
            sb.append("Gửi lại theo vai trò:\n");
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> mm = (Map<String, Object>) m;
                    sb.append("  ").append(Json.str(mm, "role", "")).append(": ").append(Json.num(mm, "status", 0)).append(" · ")
                            .append(Json.num(mm, "length", 0)).append(" byte\n");
                }
            }
            sb.append('\n');
        }
        Object bugs = r.data().get("bugs");
        if (bugs instanceof List<?> l && !l.isEmpty()) {
            sb.append("Lỗi đã đánh dấu:\n");
            for (Object o : l) {
                sb.append("  ").append(o).append('\n');
            }
            sb.append('\n');
        }
        Object tags = r.data().get("tags");
        if (tags instanceof List<?> l && !l.isEmpty()) {
            sb.append("Tag: ").append(l).append("\n\n");
        }
        ProjectData d = ps.data();
        ProjectData.Row row = d == null ? null : d.row(r.str("row"));
        if (row != null && !row.qa.isEmpty()) {
            sb.append("Q&A / Info:\n");
            for (ProjectData.QaRef q : row.qa) {
                sb.append("  ").append(q.item().code()).append(" (").append(q.letter()).append(") ").append(q.item().status().label).append(": ")
                        .append(q.item().question()).append('\n');
                if (!q.item().answer().isEmpty()) {
                    sb.append("     Trả lời: ").append(q.item().answer()).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
