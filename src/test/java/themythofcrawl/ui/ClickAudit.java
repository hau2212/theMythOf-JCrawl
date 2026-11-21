package themythofcrawl.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollBar;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JToggleButton;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.kit.RichHoverCard;

/**
 * Manual audit (run under Xvfb, not part of the suite): clicks every enabled button / menu item of the
 * main tabs, the dialogs and the action menus; reports clicks that THROW (in Burp those look like
 * "nothing happens") and icon buttons with no name. Usage: java ... themythofcrawl.ui.ClickAudit out.txt
 */
public final class ClickAudit {
    static final List<String> report = new ArrayList<>();
    static int clicks;
    static final java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
    static MainPanel main;
    static Engine engine;

    /** Cheap fingerprint of everything a click could visibly change. */
    static String state() {
        StringBuilder sb = new StringBuilder();
        sb.append(opened.get()).append('|').append(engine.logbook().system().size()).append('|')
                .append(main.toastPane.lastMessage()).append('|').append(main.selectedTab()).append('|')
                .append(engine.config().toMap()).append('|').append(engine.actionEngine().policyLedger().toJson())
                .append('|').append(engine.groups().size()).append('|').append(engine.proxyGate());
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("laf") != null) {
            javax.swing.UIManager.setLookAndFeel(System.getProperty("laf"));
            themythofcrawl.ui.theme.Theme.onThemeChanged();
        }
        Path dir = Files.createTempDirectory("audit");
        Prefs prefs = Prefs.memory();
        Engine e = V70MultiRoleTeamTest.engine(new V70MultiRoleTeamTest.Server(), "A");
        e.setDestination("Toppage > Orders");
        e.process(V70MultiRoleTeamTest.msg("GET", "/api/orders", "ADMIN_TOKEN"), "Proxy", true, e.activeBranch().id());
        e.process(V70MultiRoleTeamTest.msg("DELETE", "/api/orders/7", "ADMIN_TOKEN"), "Proxy", true, e.activeBranch().id());
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);

        engine = e;
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(ev -> {
            if (ev.getID() == java.awt.event.WindowEvent.WINDOW_OPENED) {
                opened.incrementAndGet();
            }
        }, java.awt.AWTEvent.WINDOW_EVENT_MASK);
        AtomicBoolean run = new AtomicBoolean(true);
        Thread dog = new Thread(() -> {
            while (run.get()) {
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ie) {
                    return;
                }
                for (Window w : Window.getWindows()) {
                    if (w instanceof JDialog d && d.isVisible() && d.isModal()) {
                        SwingUtilities.invokeLater(d::dispose);
                    }
                }
            }
        });
        dog.setDaemon(true);
        dog.start();

        MainPanel[] ui = new MainPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            ui[0] = new MainPanel(e, ws, new UiPreview.PlainHost(), "audit");
            JFrame f = new JFrame("audit-main");
            f.getContentPane().add(ui[0].root());
            f.setSize(1500, 950);
            f.setVisible(true);
            ui[0].tick(true);
            main = ui[0];
        });
        ApiGroup g = e.groups().get(1);

        SwingUtilities.invokeAndWait(() -> {
            List<AbstractButton> hb = new ArrayList<>();
            collect(ui[0].headerPanel(), hb, new IdentityHashMap<>());
            for (AbstractButton b : hb) {
                report.add("[Thanh trên] nút: " + label(b));
            }
        });

        JTabbedPane tabs = ui[0].tabs;
        for (int t = 0; t < tabs.getTabCount(); t++) {
            int idx = t;
            SwingUtilities.invokeAndWait(() -> tabs.setSelectedIndex(idx));
            Thread.sleep(250);
            String name = tabs.getTitleAt(t);
            Component body = tabs.getComponentAt(t);
            clickAll("Tab " + name, () -> (Container) body, () -> {
                if (tabs.getSelectedIndex() != idx) {
                    tabs.setSelectedIndex(idx);
                }
            });
        }

        dialog("Cổng chặn Proxy", () -> new ProxyGateDialog.Gate(e, g, null).panel);
        dialog("Bảo vệ dữ liệu", () -> DataProtectionDialog.build(null, e, g, null));
        dialog("Action Workbench", () -> ActionWorkbenchDialog.buildWorkbenchPanel(null, e, g, null, 0, null, null));
        dialog("Nhóm", () -> TeamCollabDialog.buildPanel(null, e, 0, null, null));
        dialog("Hàng chờ chặn", () -> ProxyInterceptDialog.buildView(e, ui[0], null, null).panel());
        dialog("Composer", () -> RequestComposerDialog.buildView(e, g, null, ui[0], null, null).panel());
        dialog("Lineage", () -> LineageDialog.buildView(ui[0], g.bookKey(), g, null, null, null, null).panel());
        dialog("Excel placeholder", () -> ExcelPlaceholderDialog.buildPanel(null, e, null, null, null));

        menu("Menu API (chuột phải)", () -> ActionMenus.buildForApi(ui[0].root(), e, g, null, null));
        menu("Menu Bảo vệ dữ liệu", () -> {
            JPopupMenu m = new JPopupMenu();
            ActionMenus.populateToolbarDropdown(m, ui[0].root(), e, () -> g, null);
            return m;
        });
        menu("Menu Bố cục", () -> ui[0].api.layoutMenu());

        run.set(false);
        report.add("== Tổng số click: " + clicks);
        Files.write(Path.of(args.length > 0 ? args[0] : "click-audit.txt"), report);
        System.exit(0);
    }

    static void dialog(String name, Supplier<JComponent> build) throws Exception {
        JFrame[] f = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                JComponent c = build.get();
                f[0] = new JFrame("audit-" + name);
                f[0].getContentPane().add(new javax.swing.JScrollPane(c));
                f[0].setSize(1200, 900);
                f[0].setVisible(true);
            } catch (Throwable ex) {
                report.add("[" + name + "] MỞ LỖI: " + ex + " " + frames(ex));
            }
        });
        if (f[0] == null) {
            return;
        }
        Thread.sleep(300);
        clickAll(name, () -> f[0].getContentPane(), () -> { });
        SwingUtilities.invokeAndWait(() -> f[0].dispose());
    }

    static void menu(String name, Supplier<Component> build) throws Exception {
        List<JMenuItem> items = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                collectMenu(build.get(), items);
            } catch (Throwable ex) {
                report.add("[" + name + "] DỰNG MENU LỖI: " + ex + " " + frames(ex));
            }
        });
        for (JMenuItem it : items) {
            String[] res = {null};
            String[] mb = {null};
            SwingUtilities.invokeAndWait(() -> {
                if (!it.isEnabled()) {
                    return;
                }
                clicks++;
                if (it.getActionListeners().length == 0 && it.getAction() == null) {
                    res[0] = "không gắn hành động";
                    return;
                }
                mb[0] = state();
                try {
                    it.doClick(0);
                } catch (Throwable ex) {
                    res[0] = "LỖI " + ex + " " + frames(ex);
                }
            });
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(() -> {
                if (res[0] == null && mb[0] != null && mb[0].equals(state())) {
                    res[0] = "bấm xong KHÔNG thấy phản hồi gì";
                }
            });
            Thread.sleep(150);
            cleanup();
            if (res[0] != null) {
                report.add("[" + name + "] " + it.getText() + " -> " + res[0]);
            }
        }
    }

    static void collectMenu(Component c, List<JMenuItem> out) {
        if (c instanceof JMenu m) {
            for (Component k : m.getMenuComponents()) {
                collectMenu(k, out);
            }
        } else if (c instanceof JMenuItem it) {
            out.add(it);
        } else if (c instanceof JPopupMenu p) {
            for (Component k : p.getComponents()) {
                collectMenu(k, out);
            }
        }
    }

    static void clickAll(String where, Supplier<Container> root, Runnable restore) throws Exception {
        List<AbstractButton> buttons = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> collect(root.get(), buttons, new IdentityHashMap<>()));
        for (AbstractButton b : buttons) {
            String label = label(b);
            if (label.contains("cửa sổ") || label.contains("FlatOneTouch") || label.contains("Gắn lại")
                    || label.contains("Xóa tất cả")) {
                continue;
            }
            String[] res = {null};
            String[] before = {null};
            boolean[] popup = {false};
            SwingUtilities.invokeAndWait(() -> {
                if (!b.isEnabled()) {
                    return;
                }
                clicks++;
                if (b.getActionListeners().length == 0 && b.getAction() == null) {
                    res[0] = "không gắn hành động";
                    return;
                }
                before[0] = state() + "|" + b.getText();
                try {
                    b.doClick(0);
                } catch (Throwable ex) {
                    res[0] = "LỖI " + ex + " " + frames(ex);
                    return;
                }
                popup[0] = MenuSelectionManager.defaultManager().getSelectedPath().length > 0;
            });
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(() -> {
                if (res[0] == null && before[0] != null && !popup[0] && before[0].equals(state() + "|" + b.getText())) {
                    res[0] = "bấm xong KHÔNG thấy phản hồi gì";
                }
            });
            Thread.sleep(150);
            cleanup();
            SwingUtilities.invokeAndWait(restore);
            if (res[0] != null) {
                report.add("[" + where + "] " + label + " -> " + res[0]);
            }
            if (label.startsWith("[icon")) {
                report.add("[" + where + "] nút chỉ có icon, không tên/tooltip: " + label);
            }
        }
    }

    static void cleanup() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            for (Window w : Window.getWindows()) {
                boolean ours = w instanceof JFrame jf && jf.getTitle() != null && jf.getTitle().startsWith("audit");
                if (w.isVisible() && !ours) {
                    w.dispose();
                }
            }
        });
    }

    static String frames(Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String line : sw.toString().split("\n")) {
            if (line.contains("themythofcrawl")) {
                sb.append(line.trim()).append(" | ");
                if (++n >= 3) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    static String label(AbstractButton b) {
        String t = b.getText();
        if (t != null && !t.isBlank()) {
            return t.trim();
        }
        if (b.getToolTipText() != null && !b.getToolTipText().isBlank()) {
            return "(icon) " + b.getToolTipText();
        }
        if (b.getClientProperty("tmoc.hoverCard.spec") instanceof RichHoverCard.Spec s) {
            return "(icon) " + s.title();
        }
        return "[icon: " + b.getClass().getSimpleName() + " in " + b.getParent().getClass().getSimpleName() + "]";
    }

    static void collect(Component c, List<AbstractButton> out, Map<Component, Boolean> seen) {
        if (c == null || seen.put(c, Boolean.TRUE) != null) {
            return;
        }
        if (c instanceof JComboBox<?> || c instanceof JScrollBar || c instanceof JSpinner || c instanceof JPopupMenu) {
            return;
        }
        if (c instanceof AbstractButton b && !(b instanceof JCheckBox) && !(b instanceof JRadioButton) && !(b instanceof JMenu)
                && !(b instanceof JToggleButton) && b.isVisible()) {
            out.add(b);
        }
        if (c instanceof Container box) {
            for (Component k : box.getComponents()) {
                collect(k, out, seen);
            }
        }
    }
}
