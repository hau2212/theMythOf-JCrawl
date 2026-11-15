package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import themythofcrawl.app.Bridge;
import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.excel.TargetList;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Service;
import themythofcrawl.store.Workspaces;

/** Builds the whole UI (no window needed) over sample data and drives every tab's refresh. */
class UiSmokeTest {
    static final Service SVC = new Service("api.example.test", 443, true);

    static final Bridge BRIDGE = new Bridge() {
        @Override
        public boolean inScope(String url) {
            return true;
        }

        @Override
        public Sent send(Service s, byte[] request) {
            return new Sent("HTTP/1.1 200 OK\r\n\r\n{}".getBytes(StandardCharsets.ISO_8859_1), 1, null);
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
    };

    static Engine.Message msg(String method, String path) {
        byte[] req = (method + " " + path + " HTTP/1.1\r\nHost: api.example.test\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        return new Engine.Message(SVC, req, "HTTP/1.1 200 OK\r\n\r\n{}".getBytes(StandardCharsets.ISO_8859_1),
                "https://api.example.test" + path);
    }

    @Test
    void everyTabBuildsAndRefreshes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine e = new Engine(BRIDGE, prefs, null);
        e.open(ws.start("p", "Project"));
        e.setTargets(TargetList.fromRows(List.of(List.of("STT", "PROTOCOL", "PATH"), List.of("1", "GET", "/api/v1/users/{id}")),
                "t.xlsx", "S"));
        e.setDestination("Toppage > menu");
        for (String p : new String[] {"/api/v1/users/1", "/api/v1/a", "/api/v1/b"}) {
            e.process(msg("GET", p), "Proxy", true, e.activeBranch().id());
        }
        e.setDestination("Toppage > other");
        e.process(msg("POST", "/api/v1/c"), "Proxy", true, e.activeBranch().id());
        List<ApiGroup> all = e.groups();
        assertEquals(4, all.size());
        e.remove(List.of(all.get(3)), "");
        MainPanel[] ui = new MainPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            ui[0] = new MainPanel(e, ws, new HeadlessHost(), "p");
            for (int i = 0; i < ui[0].tabs.getTabCount(); i++) {
                ui[0].tabs.setSelectedIndex(i);
                ui[0].tick(true);
            }
            for (int s = 0; s < 4; s++) {
                ui[0].audit.show(s, null);
            }
            ui[0].excel.select(all.get(0).target());
            ui[0].jumpToGroup(all.get(1));
            ui[0].settings.load();
            ui[0].history.tree.rebuild(true);
            ui[0].treeTab.tree.rebuild(true);
            assertTrue(ui[0].status.getText().contains("API"));
            ui[0].dispose();
        });
        e.close();
    }

    static final class HeadlessHost implements UiHost {
        @Override
        public Viewer requestViewer(boolean editable) {
            javax.swing.JTextArea t = new javax.swing.JTextArea();
            return new Viewer() {
                @Override
                public java.awt.Component component() {
                    return t;
                }

                @Override
                public void show(Service service, byte[] message) {
                    t.setText(message == null ? "" : new String(message, StandardCharsets.ISO_8859_1));
                }

                @Override
                public byte[] edited() {
                    return null;
                }
            };
        }

        @Override
        public Viewer responseViewer() {
            return requestViewer(false);
        }

        @Override
        public void applyTheme(java.awt.Component c) {
        }

        @Override
        public String projectName() {
            return "Project";
        }
    }
}
