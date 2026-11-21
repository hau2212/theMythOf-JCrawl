package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.swing.SwingUtilities;
import javax.swing.table.TableColumnModel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Bridge;
import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.app.Prefs;
import themythofcrawl.collab.TeamSyncService;
import themythofcrawl.core.Config;
import themythofcrawl.model.Service;
import themythofcrawl.store.Workspaces;

/**
 * V_7.0_beta: Burp-History style flat API table, role heatmap column, branch-only tree,
 * hideable layout with persistence, and the hardened (loopback + mandatory passcode) team room.
 */
class V70BurpTableLayoutTest {

    private static final class StubBridge implements Bridge {
        @Override public boolean inScope(String url) { return true; }
        @Override public Sent send(Service s, byte[] req) {
            return new Sent("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.ISO_8859_1), 5, null);
        }
        @Override public void toRepeater(Service service, byte[] request, String tabName) { }
        @Override public void out(String line) { }
        @Override public void err(String line) { }
    }

    private static Engine newEngine(Prefs prefs) {
        Engine e = new Engine(new StubBridge(), prefs, null);
        e.open(new MemoryStorage());
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.autoSend = false;
        c.confirmNewApis = false;
        c.ignoreStatic = false;
        e.applyConfig(c);
        return e;
    }

    private static Workspaces newWorkspaces(Prefs prefs, Path dir) {
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        return ws;
    }

    private static List<Integer> viewModelOrder(TableColumnModel cm) {
        List<Integer> out = new ArrayList<>();
        for (int v = 0; v < cm.getColumnCount(); v++) {
            out.add(cm.getColumn(v).getModelIndex());
        }
        return out;
    }

    @Test
    void flatTableUsesBurpColumnOrderRoleStripAndBranchOnlyTree(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        e.setDestination("Toppage > Orders");
        e.process(UiSmokeTest.msg("GET", "/api/v1/orders/1"), "Proxy", true, e.activeBranch().id());
        e.setDestination("Toppage > Users > Detail");
        e.process(UiSmokeTest.msg("GET", "/api/v1/users/7"), "Proxy", true, e.activeBranch().id());
        Workspaces ws = newWorkspaces(prefs, dir);

        SwingUtilities.invokeAndWait(() -> {
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v70-table");
            try {
                ui.tick(true);
                TableColumnModel cm = ui.api.table.getColumnModel();
                List<Integer> order = viewModelOrder(cm);
                assertEquals(List.of(GroupTableModel.GID_COL, GroupTableModel.METHOD_COL, GroupTableModel.HOST_COL,
                        GroupTableModel.URL_COL, GroupTableModel.STATUS_COL, GroupTableModel.LENGTH_COL,
                        GroupTableModel.ROLES_COL), order.subList(0, 7), "Burp History column order");
                for (int hidden : new int[] {GroupTableModel.COPY_COL, GroupTableModel.GROUP_COL,
                        GroupTableModel.SOURCE_COL, GroupTableModel.COPIED_COL}) {
                    assertFalse(order.contains(hidden), "column " + hidden + " must be hidden by default");
                }
                ColumnMemory mem = ColumnMemory.of(ui.api.table);
                mem.setColumnVisible(GroupTableModel.SOURCE_COL, true);
                assertTrue(viewModelOrder(cm).contains(GroupTableModel.SOURCE_COL), "hidden column can be shown again");
                mem.resetDefaults();
                assertFalse(viewModelOrder(cm).contains(GroupTableModel.SOURCE_COL), "reset brings back default hidden set");

                int stripView = ui.api.table.convertColumnIndexToView(GroupTableModel.ROLES_COL);
                assertTrue(ui.api.table.getCellRenderer(0, stripView) instanceof RoleStripRenderer);
                assertFalse(String.valueOf(ui.api.groupModel.getValueAt(0, GroupTableModel.ROLES_COL)).isBlank());

                // Branch-only tree: no API (GROUP) nodes by default
                ui.api.tree.rebuild(false);
                ui.api.tree.expandFolders(true);
                assertEquals(0, countGroups((Node) ui.api.tree.model.getRoot()), "tree shows only branches by default");
                ui.api.setTreeShowsApis(true);
                ui.api.tree.rebuild(false);
                assertEquals(2, countGroups((Node) ui.api.tree.model.getRoot()), "legacy mode shows API rows");
                assertEquals("1", ui.uiPrefs.get(ApiTab.KEY_TREE_SHOW_APIS));
                ui.api.setTreeShowsApis(false);

                // Dragging rows from the table produces the same payload the tree accepts
                ui.api.table.setRowSelectionInterval(0, 0);
                assertNotNull(BranchTree.transferableFor(ui.api.selectedGroups()));
            } finally {
                ui.dispose();
            }
        });
    }

    private static int countGroups(Node root) {
        int n = 0;
        for (Enumeration<?> en = root.depthFirstEnumeration(); en.hasMoreElements(); ) {
            if (((Node) en.nextElement()).kind == Node.Kind.GROUP) {
                n++;
            }
        }
        return n;
    }

    @Test
    void layoutPanesHideShowPersistAndMaximize(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        Workspaces ws = newWorkspaces(prefs, dir);
        SwingUtilities.invokeAndWait(() -> {
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v70-layout");
            try {
                ui.selectTab(ui.api.panel);
                assertTrue(ui.api.paneVisible(ApiTab.Pane.TREE));
                assertTrue(ui.dispatchShortcut("ctrl alt 1"));
                assertFalse(ui.api.paneVisible(ApiTab.Pane.TREE));
                assertTrue(ui.dispatchShortcut("ctrl alt 2"));
                assertFalse(ui.api.paneVisible(ApiTab.Pane.DETAIL));
                assertTrue(ui.dispatchShortcut("ctrl alt H"));
                assertFalse(ui.headerVisible());
                String saved = ui.uiPrefs.get(ApiTab.KEY_LAYOUT);
                assertTrue(saved.contains("tree=0") && saved.contains("detail=0") && saved.contains("header=0"), saved);

                MainPanel ui2 = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v70-layout");
                try {
                    assertFalse(ui2.api.paneVisible(ApiTab.Pane.TREE), "hidden tree restores");
                    assertFalse(ui2.api.paneVisible(ApiTab.Pane.DETAIL), "hidden detail restores");
                    assertFalse(ui2.headerVisible(), "hidden header restores");
                } finally {
                    ui2.dispose();
                }

                ui.api.applyPreset("all");
                assertTrue(ui.api.paneVisible(ApiTab.Pane.TREE) && ui.api.paneVisible(ApiTab.Pane.DETAIL)
                        && ui.api.paneVisible(ApiTab.Pane.TOOLBAR) && ui.headerVisible());
                assertTrue(ui.dispatchShortcut("ctrl alt 0"));
                assertTrue(ui.api.maximized(), "Ctrl+Alt+0 leaves only the API table");
                assertTrue(ui.dispatchShortcut("ctrl alt 0"));
                assertFalse(ui.api.maximized(), "Ctrl+Alt+0 again restores everything");
                assertTrue(ui.api.layoutMenu().getComponentCount() >= 8);
            } finally {
                ui.dispose();
            }
        });
    }

    @Test
    void teamRoomIsLoopbackOnlyAndRequiresStrongPasscode() throws Exception {
        Engine e = newEngine(Prefs.memory());
        TeamSyncService host = e.teamSync();
        try {
            assertThrows(IllegalArgumentException.class, () -> host.startHost(0, ""), "empty passcode refused");
            assertThrows(IllegalArgumentException.class, () -> host.startHost(0, "short"), "short passcode refused");
            String pass = TeamSyncService.randomPasscode();
            assertTrue(pass.length() >= TeamSyncService.MIN_PASSCODE);
            host.startHost(0, pass);
            assertTrue(host.boundPort() > 0);

            HttpClient http = HttpClient.newHttpClient();
            URI events = URI.create("http://127.0.0.1:" + host.boundPort() + "/api/sync/events?since=0");
            HttpResponse<String> noPass = http.send(HttpRequest.newBuilder(events).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, noPass.statusCode(), "request without passcode is rejected");
            HttpResponse<String> wrong = http.send(HttpRequest.newBuilder(events)
                    .header("X-JCrawl-Passcode", pass + "x").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, wrong.statusCode(), "wrong passcode is rejected");
            HttpResponse<String> ok = http.send(HttpRequest.newBuilder(events)
                    .header("X-JCrawl-Passcode", pass).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
        } finally {
            host.stop();
            e.close();
        }
    }
}
