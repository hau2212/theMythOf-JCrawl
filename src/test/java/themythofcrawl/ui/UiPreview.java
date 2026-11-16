package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.Config;
import themythofcrawl.excel.TargetList;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Service;
import themythofcrawl.store.Workspaces;
import themythofcrawl.app.Bridge;
import themythofcrawl.model.RoleScopes;

/**
 * Opens the real UI outside Burp with sample data and saves a screenshot of every tab
 * (run under Xvfb: {@code xvfb-run java -cp ... themythofcrawl.UiPreview <outDir> [workspaceRoot]}).
 */
public final class UiPreview {
    static final class DemoBridge implements Bridge {
        volatile int status = 200;

        @Override
        public boolean inScope(String url) {
            return true;
        }

        @Override
        public Sent send(Service s, byte[] request) {
            return new Sent(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\n\r\n{\"ok\":" + status + "}")
                    .getBytes(StandardCharsets.ISO_8859_1), 3, null);
        }

        @Override
        public void toRepeater(Service s, byte[] request, String tabName) {
        }

        @Override
        public void out(String line) {
        }

        @Override
        public void err(String line) {
        }
    }

    static final class PlainHost implements UiHost {
        @Override
        public Viewer requestViewer(boolean editable) {
            return viewer(editable);
        }

        @Override
        public Viewer responseViewer() {
            return viewer(false);
        }

        private static Viewer viewer(boolean editable) {
            JTextArea t = new JTextArea();
            t.setEditable(editable);
            JScrollPane s = new JScrollPane(t);
            return new Viewer() {
                @Override
                public Component component() {
                    return s;
                }

                @Override
                public void show(Service service, byte[] message) {
                    t.setText(message == null ? "" : new String(message, StandardCharsets.ISO_8859_1));
                    t.setCaretPosition(0);
                }

                @Override
                public byte[] edited() {
                    return null;
                }
            };
        }

        @Override
        public void applyTheme(Component c) {
            if (System.getProperty("laf") != null) {
                SwingUtilities.updateComponentTreeUI(c);   // what a host theme pass does to a component tree
            }
        }

        @Override
        public String projectName() {
            return "Demo project";
        }
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/preview");
        Files.createDirectories(out);
        String laf = System.getProperty("laf");
        if (laf != null) {
            javax.swing.UIManager.setLookAndFeel(laf);
        }
        Path root = Files.createTempDirectory("tmoc-ws");
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), root.resolve("x.jar").toString());
        ws.setRoot(root);
        DemoBridge bridge = new DemoBridge();
        Engine e = new Engine(bridge, prefs, null);
        e.open(ws.start("demo", "Demo project"));
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.sendProfile = Config.SendProfile.CUSTOM;
        c.sendDelayMs = 0;
        c.sendJitterPct = 0;
        e.applyConfig(c);
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("STT", "PROTOCOL", "PATH", "POSITION"));
        String[] paths = {"/api/v1/login", "/api/v1/users/{id}", "/api/v1/contact/send", "/api/v1/orders", "/api/v1/orders/{id}",
            "/api/v1/cart", "/api/v1/profile", "/api/v1/search", "/api/v1/missing", "/api/v1/admin/stats"};
        for (int i = 0; i < paths.length; i++) {
            rows.add(List.of(String.valueOf(200 + i), i % 3 == 0 ? "POST" : "GET", paths[i], i == 2 ? "> contact > button \"send\"" : ""));
        }
        e.setTargets(TargetList.fromRows(rows, "danh_sach_api.xlsx", "Sheet1"));
        String[][] flow = {
            {"Toppage > menu > button \"Login\"", "POST /api/v1/login", "GET /api/v1/profile"},
            {"Toppage > menu > Users", "GET /api/v1/users/15", "GET /api/v1/users/16", "GET /api/v1/search?q=a"},
            {"Toppage > contact > button \"send\"", "POST /api/v1/contact/send", "GET /api/v1/profile"},
            {"Toppage > Orders", "GET /api/v1/orders", "GET /api/v1/orders/77", "POST /api/v1/cart", "GET /api/v1/admin/stat"},
        };
        for (String[] f : flow) {
            e.setDestination(f[0]);
            for (int i = 1; i < f.length; i++) {
                String[] mp = f[i].split(" ");
                e.process(UiSmokeTest.msg(mp[0], mp[1]), "Proxy", true, e.activeBranch().id());
            }
        }
        List<ApiGroup> all = e.groups();
        e.confirm(all.subList(0, Math.min(5, all.size())), "");
        bridge.status = 403;
        e.replay(all.get(0), e.defaultTargets(all.get(0), true));
        Thread.sleep(600);
        bridge.status = 200;
        e.replay(all.get(1), e.defaultTargets(all.get(1), true));
        e.remove(List.of(all.get(all.size() - 1)), "demo");
        Thread.sleep(800);
        MainPanel[] ui = new MainPanel[1];
        JFrame[] fr = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            ui[0] = new MainPanel(e, ws, new PlainHost(), "demo");
            JFrame f = new JFrame("preview");
            f.getContentPane().add(ui[0].root(), BorderLayout.CENTER);
            f.setSize(new Dimension(1500, 950));
            f.setVisible(true);
            fr[0] = f;
        });
        javax.swing.JTabbedPane tabs = find(ui[0].root(), javax.swing.JTabbedPane.class);
        for (int i = 0; i < tabs.getTabCount(); i++) {
            int idx = i;
            SwingUtilities.invokeAndWait(() -> tabs.setSelectedIndex(idx));
            Thread.sleep(700);
            String name = String.format("%02d_%s", i, tabs.getTitleAt(i).replaceAll("[^\\p{L}0-9]+", "_"));
            shot(fr[0], out.resolve(name + ".png").toFile());
        }
        MainPanel m = ui[0];
        step(fr[0], out, "10_api_chon", () -> {
            m.tabs.setSelectedIndex(0);
            m.api.select(all.get(0), null);
        });
        step(fr[0], out, "11_audit_giu_cho", () -> m.audit.show(1, all.get(0).bookKey()));
        step(fr[0], out, "12_audit_nhat_ky", () -> m.audit.show(3, null));
        step(fr[0], out, "13_audit_trung", () -> m.audit.show(2, null));
        step(fr[0], out, "14_cay_chon", () -> {
            m.tabs.setSelectedComponent(m.treeTab.panel);
            m.tick(true);
            m.treeTab.tree.selectGroup(all.get(0));
        });
        dialog(out, "20_dlg_role_scope", () -> m.roleScopeDialog(false, RoleScopes.Scope.BRANCH, List.of("Toppage > menu"), null, null));
        dialog(out, "21_dlg_push", () -> m.pushDialog(false, all, "Tất cả API"));
        dialog(out, "22_dlg_levels", () -> LevelsDialog.show(m.root(), e));
        step(fr[0], out, "23_dup_popup", () -> {
            m.tabs.setSelectedIndex(0);
            DupPopup.show(m, all.get(1), m.api.table, 200, 60);
        });
        SwingUtilities.invokeAndWait(() -> {
            ui[0].dispose();
            fr[0].dispose();
        });
        e.close();
        System.out.println("screenshots in " + out.toAbsolutePath());
        System.exit(0);
    }

    static void step(JFrame f, Path out, String name, Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
        Thread.sleep(700);
        shot(f, out.resolve(name + ".png").toFile());
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w instanceof javax.swing.JWindow || w.getClass().getName().contains("Popup")) {
                    w.setVisible(false);
                }
            }
            javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
        });
    }

    /** Opens a modal dialog, saves a picture of it, closes it. */
    static void dialog(Path out, String name, Runnable open) throws Exception {
        SwingUtilities.invokeLater(open);
        Thread.sleep(1200);
        java.awt.Window[] d = new java.awt.Window[1];
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w instanceof javax.swing.JDialog && w.isShowing()) {
                    d[0] = w;
                }
            }
        });
        if (d[0] == null) {
            System.out.println("no dialog for " + name);
            return;
        }
        BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            Component c = ((javax.swing.JDialog) d[0]).getContentPane();
            BufferedImage b = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = b.createGraphics();
            c.paint(g);
            g.dispose();
            img[0] = b;
        });
        ImageIO.write(img[0], "png", out.resolve(name + ".png").toFile());
        SwingUtilities.invokeAndWait(d[0]::dispose);
        Thread.sleep(300);
    }

    static <T> T find(Component c, Class<T> type) {
        if (type.isInstance(c)) {
            return type.cast(c);
        }
        if (c instanceof java.awt.Container k) {
            for (Component x : k.getComponents()) {
                T t = find(x, type);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    static void shot(JFrame f, File file) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            Component c = f.getContentPane();
            BufferedImage b = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = b.createGraphics();
            c.paint(g);
            g.dispose();
            img[0] = b;
        });
        ImageIO.write(img[0], "png", file);
    }
}
