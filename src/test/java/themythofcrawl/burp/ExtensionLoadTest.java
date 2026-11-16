package themythofcrawl.burp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import themythofcrawl.store.Workspaces;

/**
 * Loads the extension the way Burp does (initialize(MontoyaApi)) against a stand-in API, passes one
 * proxied request through the registered handler, unloads, and checks the API was saved to disk.
 */
class ExtensionLoadTest {
    /** Every Montoya interface answered by a proxy: strings / prefs from a map, the rest stubbed. */
    static final class Stub implements InvocationHandler {
        final Map<String, String> prefs = new ConcurrentHashMap<>();
        final List<Object> registered = new ArrayList<>();
        final List<String> outputs = new ArrayList<>();
        final String filename;

        Stub(String filename) {
            this.filename = filename;
        }

        @SuppressWarnings("unchecked")
        <T> T make(Class<T> type) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, this);
        }

        @Override
        public Object invoke(Object self, Method m, Object[] args) {
            String n = m.getName();
            Class<?> rt = m.getReturnType();
            switch (n) {
                case "hashCode":
                    return System.identityHashCode(self);
                case "equals":
                    return self == args[0];
                case "toString":
                    return "stub " + m.getDeclaringClass().getSimpleName();
                case "filename":
                    return filename;
                case "logToOutput":
                    if (args != null && args.length > 0 && args[0] != null) {
                        outputs.add(args[0].toString());
                    }
                    return null;
                case "getString":
                    return prefs.get((String) args[0]);
                case "setString":
                    prefs.put((String) args[0], (String) args[1]);
                    return null;
                case "deleteString":
                    prefs.remove((String) args[0]);
                    return null;
                default:
                    break;
            }
            if (n.startsWith("register") && args != null && args.length > 0) {
                registered.add(args[args.length - 1]);
                for (Object a : args) {
                    if (a instanceof Component c) {
                        registered.add(c);
                    }
                }
            }
            if (rt == void.class) {
                return null;
            }
            if (rt == String.class) {
                return n.equals("name") ? "Demo project" : n.equals("id") ? "demo-id" : "x";
            }
            if (rt == boolean.class) {
                return n.equals("isInScope");   // the test request is in Burp's target scope
            }
            if (rt == int.class || rt == long.class || rt == short.class) {
                return rt == long.class ? 0L : 0;
            }
            if (rt == Optional.class) {
                return Optional.empty();
            }
            if (rt == List.class) {
                return List.of();
            }
            if (Component.class.isAssignableFrom(rt)) {
                return new javax.swing.JPanel();
            }
            if (rt.isInterface()) {
                return make(rt);
            }
            return null;
        }
    }

    static ByteArray bytes(byte[] b) {
        return (ByteArray) Proxy.newProxyInstance(ByteArray.class.getClassLoader(), new Class<?>[] {ByteArray.class},
                (p, m, a) -> m.getName().equals("getBytes") ? b : m.getName().equals("length") ? b.length : null);
    }

    @Test
    void loadsCapturesAndSavesLikeInBurp(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("build/libs/theMythOf-JCrawl.jar");
        Files.createDirectories(jar.getParent());
        Stub stub = new Stub(jar.toString());
        stub.prefs.put(themythofcrawl.app.Engine.PREF_CONFIG, "{\"capture_enabled\": true}");   // off by default, as in Python
        MontoyaApi api = stub.make(MontoyaApi.class);
        new Extension().initialize(api);
        assertTrue(stub.outputs.stream().anyMatch(s -> s.startsWith("theMythOf-JCrawl ") && s.contains("loaded · LAF=") && s.contains("dark=")),
                "expected load log line in Burp Output, got: " + stub.outputs);

        ProxyResponseHandler handler = (ProxyResponseHandler) stub.registered.stream()
                .filter(o -> o instanceof ProxyResponseHandler).findFirst().orElseThrow();
        assertTrue(stub.registered.stream().anyMatch(o -> o instanceof Component), "suite tab registered");
        Runnable unload = (Runnable) stub.registered.stream()
                .filter(o -> o instanceof burp.api.montoya.extension.ExtensionUnloadingHandler).findFirst()
                .map(h -> (Runnable) ((burp.api.montoya.extension.ExtensionUnloadingHandler) h)::extensionUnloaded).orElseThrow();

        byte[] req = "POST /api/v1/transfer HTTP/1.1\r\nHost: bank.test\r\nAuthorization: Bearer A\r\n\r\n{}"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] resp = "HTTP/1.1 200 OK\r\n\r\n{\"ok\":1}".getBytes(StandardCharsets.ISO_8859_1);
        HttpService svc = (HttpService) Proxy.newProxyInstance(HttpService.class.getClassLoader(), new Class<?>[] {HttpService.class},
                (p, m, a) -> switch (m.getName()) {
                    case "host" -> "bank.test";
                    case "port" -> 443;
                    case "secure" -> true;
                    default -> null;
                });
        HttpRequest request = (HttpRequest) Proxy.newProxyInstance(HttpRequest.class.getClassLoader(), new Class<?>[] {HttpRequest.class},
                (p, m, a) -> switch (m.getName()) {
                    case "httpService" -> svc;
                    case "toByteArray" -> bytes(req);
                    case "url" -> "https://bank.test/api/v1/transfer";
                    default -> null;
                });
        InterceptedResponse r = (InterceptedResponse) Proxy.newProxyInstance(InterceptedResponse.class.getClassLoader(),
                new Class<?>[] {InterceptedResponse.class}, (p, m, a) -> switch (m.getName()) {
                    case "initiatingRequest" -> request;
                    case "toByteArray" -> bytes(resp);
                    default -> null;
                });
        try {
            handler.handleResponseToBeSent(r);
        } catch (RuntimeException expected) {
            // continueWith(...) needs Burp's own factory; the capture happened before it
        }
        Thread.sleep(500);
        unload.run();

        Path base = dir.resolve(Workspaces.FOLDER);
        assertTrue(Files.isDirectory(base), "workspace folder next to the project of the jar");
        List<Workspaces.Info> found = new ArrayList<>();
        try (var projects = Files.list(base)) {
            for (Path p : projects.toList()) {
                try (var wss = Files.list(p)) {
                    for (Path w : wss.toList()) {
                        Workspaces.Info i = Workspaces.info(w);
                        if (i != null) {
                            found.add(i);
                        }
                    }
                }
            }
        }
        assertEquals(1, found.size());
        assertEquals("Demo project", found.get(0).project());
        assertEquals(1, found.get(0).apis());
        assertNotNull(stub.prefs.get(Engine_PREF_CONFIG));
    }

    static final String Engine_PREF_CONFIG = themythofcrawl.app.Engine.PREF_CONFIG;
}
