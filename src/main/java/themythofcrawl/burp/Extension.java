package themythofcrawl.burp;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseToBeSentAction;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.Text;
import themythofcrawl.model.Branch;
import themythofcrawl.model.Service;
import themythofcrawl.rolereq.RoleRules;
import themythofcrawl.store.FileStorage;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.MainPanel;

/** Burp entry point (Montoya API). */
public final class Extension implements BurpExtension {
    public static final String VERSION = "2.0.0";

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName(Text.EXT_NAME);
        Prefs prefs = MontoyaPrefs.of(api.persistence().preferences());
        Prefs project = null;
        try {
            project = MontoyaPrefs.of(api.persistence().extensionData());
        } catch (RuntimeException ignored) {
            // Community edition / temporary project: no project storage
        }
        Engine engine = new Engine(new MontoyaBridge(api), prefs, RoleRules.INSTANCE);
        Workspaces workspaces = new Workspaces(prefs, project, safeFilename(api));
        String projectId = projectId(api);
        String projectName = new MontoyaUiHost(api).projectName();
        try {
            FileStorage st = workspaces.start(projectId, projectName);
            engine.open(st);
            workspaces.remember(projectId, st);
        } catch (RuntimeException e) {
            api.logging().logToError(Text.EXT_NAME + ": không mở được workspace (" + e + "), dữ liệu chỉ nằm trong bộ nhớ.");
        }

        MainPanel[] panel = new MainPanel[1];
        Runnable build = () -> {
            panel[0] = new MainPanel(engine, workspaces, new MontoyaUiHost(api), projectId);
            api.userInterface().registerSuiteTab(Text.EXT_NAME, panel[0].root());
        };
        try {
            SwingUtilities.invokeAndWait(build);
        } catch (Exception e) {
            api.logging().logToError(Text.EXT_NAME + ": lỗi dựng giao diện: " + e);
        }

        api.proxy().registerResponseHandler(new ProxyResponseHandler() {
            @Override
            public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse r) {
                return ProxyResponseReceivedAction.continueWith(r);
            }

            @Override
            public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse r) {
                try {
                    var req = r.initiatingRequest();
                    var svc = req.httpService();
                    engine.onProxyMessage(new Engine.Message(new Service(svc.host(), svc.port(), svc.secure()),
                            req.toByteArray().getBytes(), r.toByteArray().getBytes(), req.url()));
                } catch (RuntimeException e) {
                    api.logging().logToError(Text.EXT_NAME + " capture: " + e);
                }
                return ProxyResponseToBeSentAction.continueWith(r);
            }
        });

        api.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                List<HttpRequestResponse> sel = new ArrayList<>(event.selectedRequestResponses());
                event.messageEditorRequestResponse().ifPresent(m -> sel.add(m.requestResponse()));
                List<Engine.Message> ms = new ArrayList<>();
                for (HttpRequestResponse rr : sel) {
                    if (rr.request() == null) {
                        continue;
                    }
                    var svc = rr.httpService();
                    ms.add(new Engine.Message(new Service(svc.host(), svc.port(), svc.secure()),
                            rr.request().toByteArray().getBytes(),
                            rr.hasResponse() ? rr.response().toByteArray().getBytes() : null, rr.request().url()));
                }
                if (ms.isEmpty()) {
                    return List.of();
                }
                Branch cur = engine.activeBranch();
                List<Component> items = new ArrayList<>();
                JMenuItem a = new JMenuItem(Text.EXT_NAME + ": Clone to '" + cur.name() + "' (" + ms.size() + " message(s))");
                a.addActionListener(e -> engine.submitManual(ms, false, cur.id()));
                JMenuItem b = new JMenuItem(Text.EXT_NAME + ": Clone to NEW Group Path... (" + ms.size() + " message(s))");
                b.addActionListener(e -> {
                    Object name = JOptionPane.showInputDialog(panel[0] == null ? null : panel[0].root(),
                            "Nhập đường dẫn nhánh (VD: Toppage > menu > button \"Search\"):", cur.name());
                    if (name != null && !name.toString().isBlank()) {
                        engine.submitManual(ms, false, engine.startNewBranch(name.toString()).id());
                    }
                });
                JMenuItem c = new JMenuItem(Text.EXT_NAME + ": Clone to '" + cur.name() + "' (apply capture filters + dedup)");
                c.addActionListener(e -> engine.submitManual(ms, true, cur.id()));
                items.add(a);
                items.add(b);
                items.add(c);
                return items;
            }
        });

        api.extension().registerUnloadingHandler(() -> {
            if (panel[0] != null) {
                panel[0].dispose();
            }
            engine.close();
        });
        engine.log(Text.EXT_NAME + " " + VERSION + " (Java) loaded - " + engine.roles().count() + " role(s) configured.", null);
    }

    private static String safeFilename(MontoyaApi api) {
        try {
            return api.extension().filename();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String projectId(MontoyaApi api) {
        try {
            return api.project().id();
        } catch (RuntimeException e) {
            return "default";
        }
    }
}
