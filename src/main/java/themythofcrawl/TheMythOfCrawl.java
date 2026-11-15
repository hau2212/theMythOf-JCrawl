package themythofcrawl;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Preferences;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseToBeSentAction;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import themythofcrawl.capture.CaptureService;
import themythofcrawl.ui.Host;
import themythofcrawl.ui.MainPanel;

/** Burp entry point (Montoya API). Burp-specific glue only; the logic lives in {@link App}. */
public final class TheMythOfCrawl implements BurpExtension {
    public static final String NAME = "theMythOfCrawl";

    private MontoyaApi api;
    private App app;
    private MainPanel panel;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName(NAME);
        Preferences prefs = api.persistence().preferences();
        App.KeyValue global = new App.KeyValue() {
            @Override
            public String get(String key) {
                return prefs.getString(key);
            }

            @Override
            public void set(String key, String value) {
                prefs.setString(key, value);
            }
        };
        // Pro: stored in the .burp project file, so each project reopens its own workspace.
        // Community (temporary project): harmless, the global preference above still applies.
        PersistedObject projectFile = api.persistence().extensionData();
        App.KeyValue project = new App.KeyValue() {
            @Override
            public String get(String key) {
                return projectFile.getString(key);
            }

            @Override
            public void set(String key, String value) {
                projectFile.setString(key, value);
            }
        };
        String projectName;
        String projectId;
        try {
            projectName = api.project().name();
            projectId = api.project().id();
        } catch (RuntimeException e) {
            projectName = "Burp project";
            projectId = "default";
        }
        app = new App(App.resolveRoot(global, api.extension().filename()), global, project, projectName, projectId,
                this::log, this::error);

        HttpRequestEditor requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        HttpResponseEditor responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        String finalProjectName = projectName;
        Host host = new Host() {
            @Override
            public Component requestViewer() {
                return requestEditor.uiComponent();
            }

            @Override
            public Component responseViewer() {
                return responseEditor.uiComponent();
            }

            @Override
            public void showMessage(byte[] request, byte[] response, boolean secure, String hostName, int port) {
                requestEditor.setRequest(request == null ? HttpRequest.httpRequest("")
                        : HttpRequest.httpRequest(HttpService.httpService(hostName, port, secure), ByteArray.byteArray(request)));
                responseEditor.setResponse(response == null ? HttpResponse.httpResponse("")
                        : HttpResponse.httpResponse(ByteArray.byteArray(response)));
            }

            @Override
            public void log(String message) {
                TheMythOfCrawl.this.log(message);
            }

            @Override
            public void error(String message) {
                TheMythOfCrawl.this.error(message);
            }

            @Override
            public String projectName() {
                return finalProjectName;
            }
        };

        try {
            app.start();
        } catch (Exception e) {
            error("Không mở được workspace: " + e);
        }
        Runnable buildUi = () -> {
            panel = new MainPanel(app, host);
            api.userInterface().applyThemeToComponent(panel);
            panel.ensureRenderers();
            api.userInterface().registerSuiteTab(NAME, panel);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            buildUi.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(buildUi);
            } catch (Exception e) {
                error("Không dựng được giao diện: " + e);
            }
        }

        api.proxy().registerResponseHandler(new ProxyResponseHandler() {
            @Override
            public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse response) {
                try {
                    onProxyResponse(response);
                } catch (RuntimeException e) {
                    error("Lỗi khi bắt API: " + e);
                }
                return ProxyResponseReceivedAction.continueWith(response);
            }

            @Override
            public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse response) {
                return ProxyResponseToBeSentAction.continueWith(response);
            }
        });
        api.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                return menuItems(event);
            }
        });
        api.extension().registerUnloadingHandler(app::shutdown);
        log(NAME + " (Java) đã nạp. Workspace lưu tại: " + app.store().root().toAbsolutePath());
    }

    private void onProxyResponse(InterceptedResponse response) {
        HttpRequest request = response.initiatingRequest();
        String method = request.method();
        String path = request.path();
        if (app.capture().quickReject(method, path) != null) {
            return;   // filtered on the proxy thread before any byte is copied
        }
        HttpService svc = request.httpService();
        boolean inScope = api.scope().isInScope(request.url());
        app.submit(new CaptureService.Message(method, svc.secure(), svc.host(), svc.port(), path,
                request.toByteArray().getBytes(), response.toByteArray().getBytes(), "Proxy", inScope), true);
    }

    private List<Component> menuItems(ContextMenuEvent event) {
        List<HttpRequestResponse> selected = new ArrayList<>(event.selectedRequestResponses());
        event.messageEditorRequestResponse().ifPresent(m -> {
            if (selected.isEmpty()) {
                selected.add(m.requestResponse());
            }
        });
        if (selected.isEmpty()) {
            return List.of();
        }
        JMenuItem save = new JMenuItem(NAME + ": Lưu " + selected.size() + " request vào workspace (bỏ qua bộ lọc)");
        save.addActionListener(e -> {
            for (HttpRequestResponse rr : selected) {
                HttpRequest req = rr.request();
                HttpService svc = req.httpService();
                byte[] resp = rr.response() == null ? null : rr.response().toByteArray().getBytes();
                app.submit(new CaptureService.Message(req.method(), svc.secure(), svc.host(), svc.port(), req.path(),
                        req.toByteArray().getBytes(), resp, "Manual", true), false);
            }
        });
        return List.of(save);
    }

    private void log(String message) {
        if (api != null) {
            api.logging().logToOutput(message);
        }
        if (panel != null) {
            panel.log(message);
        }
    }

    private void error(String message) {
        if (api != null) {
            api.logging().logToError(message);
        }
        if (panel != null) {
            panel.log("LỖI: " + message);
        }
    }
}
