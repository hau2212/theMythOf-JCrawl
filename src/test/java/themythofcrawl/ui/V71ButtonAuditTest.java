package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollBar;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.store.Workspaces;

/**
 * V_7.1: every clickable button / menu item the user can press must DO something. Walks the main panel,
 * every tab, the action dialogs and the action menus, and fails on any enabled button with no action.
 */
class V71ButtonAuditTest {

    /** Enabled push buttons / menu items with no ActionListener and no Action. */
    static void collectDead(Component c, String where, List<String> out, Map<Component, Boolean> seen) {
        if (c == null || seen.put(c, Boolean.TRUE) != null) {
            return;
        }
        if (c instanceof JComboBox<?> || c instanceof JScrollBar || c instanceof JSpinner) {
            return; // L&F internals (arrow buttons) are wired by the UI delegate
        }
        if (c instanceof AbstractButton b && !(b instanceof JCheckBox) && !(b instanceof JRadioButton)
                && !(b instanceof JMenu) && !(b instanceof JToggleButton && !(b instanceof javax.swing.JCheckBoxMenuItem))) {
            boolean wired = b.getActionListeners().length > 0 || b.getAction() != null;
            if (!wired && b.isEnabled()) {
                String label = b.getText() != null && !b.getText().isBlank() ? b.getText()
                        : (b.getToolTipText() != null ? "[icon] " + b.getToolTipText() : "[icon " + b.getClass().getName() + "]");
                out.add(where + " :: " + label);
            }
        }
        if (c instanceof JMenu m) {
            for (Component k : m.getMenuComponents()) {
                collectDead(k, where + " > " + m.getText(), out, seen);
            }
        }
        if (c instanceof Container box) {
            for (Component k : box.getComponents()) {
                collectDead(k, where, out, seen);
            }
        }
    }

    @Test
    void everyEnabledButtonAndMenuItemDoesSomething(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = V70MultiRoleTeamTest.engine(new V70MultiRoleTeamTest.Server(), "A");
        e.setDestination("Toppage > Orders");
        e.process(V70MultiRoleTeamTest.msg("GET", "/api/orders", "ADMIN_TOKEN"), "Proxy", true, e.activeBranch().id());
        e.process(V70MultiRoleTeamTest.msg("DELETE", "/api/orders/7", "ADMIN_TOKEN"), "Proxy", true, e.activeBranch().id());
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        List<String> dead = new ArrayList<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "audit");
                try {
                    ui.tick(true);
                    ApiGroup g = e.groups().get(1);
                    Map<Component, Boolean> seen = new IdentityHashMap<>();
                    collectDead(ui.root(), "MainPanel", dead, seen);
                    collectDead(new ProxyGateDialog.Gate(e, g, null).panel, "Cổng chặn Proxy", dead, seen);
                    collectDead(DataProtectionDialog.build(null, e, g, null), "Bảo vệ dữ liệu", dead, seen);
                    for (int tab = 0; tab < 5; tab++) {
                        collectDead(ActionWorkbenchDialog.buildWorkbenchPanel(null, e, g, null, tab, null, null),
                                "Workbench tab " + tab, dead, seen);
                    }
                    collectDead(TeamCollabDialog.buildPanel(null, e, 0, null, null), "Nhóm", dead, seen);
                    collectDead(ProxyInterceptDialog.buildView(e, ui, null, null).panel(), "Hàng chờ chặn", dead, seen);
                    collectDead(RequestComposerDialog.buildView(e, g, null, ui, null, null).panel(), "Composer", dead, seen);
                    collectDead(LineageDialog.buildView(ui, g.bookKey(), g, null, null, null, null).panel(), "Lineage", dead, seen);
                    collectDead(ExcelPlaceholderDialog.buildPanel(null, e, null, null, null), "Excel placeholder", dead, seen);
                    collectDead(ActionMenus.buildForApi(null, e, g, null, null), "Menu API", dead, seen);
                    JPopupMenu tb = new JPopupMenu();
                    ActionMenus.populateToolbarDropdown(tb, null, e, () -> g, null);
                    collectDead(tb, "Menu Bảo vệ dữ liệu", dead, seen);
                    collectDead(ui.api.layoutMenu(), "Menu Bố cục", dead, seen);
                } finally {
                    ui.dispose();
                }
            });
        } finally {
            e.close();
        }
        dead.forEach(System.out::println);
        assertTrue(dead.isEmpty(), "Buttons / menu items that do nothing:\n" + String.join("\n", dead));
    }

    @Test
    void clickErrorsAreReportedInsteadOfSwallowed() {
        List<String> sink = new ArrayList<>();
        java.util.function.Consumer<String> prev = Ui.errorSink;
        Ui.errorSink = sink::add;
        try {
            javax.swing.JButton b = new javax.swing.JButton("Thử lỗi");
            b.addActionListener(Ui.safe(e -> {
                throw new IllegalStateException("boom");
            }));
            assertDoesNotThrow(() -> b.doClick(0));
            assertEquals(1, sink.size());
            assertTrue(sink.get(0).contains("\"Thử lỗi\"") && sink.get(0).contains("boom"), sink.get(0));
            javax.swing.JButton f = Ui.button("Factory", () -> {
                throw new IllegalArgumentException("bad");
            });
            assertDoesNotThrow(() -> f.doClick(0));
            assertEquals(2, sink.size());
            assertDoesNotThrow(() -> Ui.hint(null, "Chọn 1 dòng trước"));
            assertNull(Ui.ownerWindow(null), "headless: no window to own the dialog");
        } finally {
            Ui.errorSink = prev;
        }
    }

    static boolean isInfo(JMenuItem m) {
        return !m.isEnabled();
    }
}
