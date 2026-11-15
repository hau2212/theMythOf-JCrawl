package themythofcrawl.burp;

import java.awt.Component;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import themythofcrawl.model.Service;
import themythofcrawl.ui.UiHost;

/** Burp's own request / response editors and theme for the extension UI. */
final class MontoyaUiHost implements UiHost {
    private final MontoyaApi api;

    MontoyaUiHost(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public Viewer requestViewer(boolean editable) {
        HttpRequestEditor ed = editable ? api.userInterface().createHttpRequestEditor()
                : api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        return new Viewer() {
            @Override
            public Component component() {
                return ed.uiComponent();
            }

            @Override
            public void show(Service s, byte[] msg) {
                byte[] b = msg == null ? new byte[0] : msg;
                ed.setRequest(s == null ? HttpRequest.httpRequest(ByteArray.byteArray(b))
                        : HttpRequest.httpRequest(MontoyaBridge.service(s), ByteArray.byteArray(b)));
            }

            @Override
            public byte[] edited() {
                return editable && ed.isModified() ? ed.getRequest().toByteArray().getBytes() : null;
            }
        };
    }

    @Override
    public Viewer responseViewer() {
        HttpResponseEditor ed = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        return new Viewer() {
            @Override
            public Component component() {
                return ed.uiComponent();
            }

            @Override
            public void show(Service s, byte[] msg) {
                ed.setResponse(HttpResponse.httpResponse(ByteArray.byteArray(msg == null ? new byte[0] : msg)));
            }

            @Override
            public byte[] edited() {
                return null;
            }
        };
    }

    @Override
    public void applyTheme(Component c) {
        api.userInterface().applyThemeToComponent(c);
    }

    @Override
    public String projectName() {
        try {
            return api.project().name();
        } catch (RuntimeException e) {
            return "Burp project";
        }
    }
}
