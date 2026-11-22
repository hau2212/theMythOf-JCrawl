package themythofcrawl;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import themythofcrawl.app.Bridge;
import themythofcrawl.app.DirectHttp;
import themythofcrawl.model.Service;

/** V_10.4 "Gửi riêng": a raw HTTP/1.1 send outside Burp, with chunked bodies decoded. */
class V104QuietSendTest {
    @Test
    void sendsRawRequestAndDecodesChunked() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket c = server.accept()) {
                    InputStream in = c.getInputStream();
                    byte[] buf = new byte[4096];
                    int n = in.read(buf);
                    String req = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
                    String body = req.contains("Connection: close") && !req.contains("keep-alive") ? "ok" : "bad";
                    OutputStream out = c.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n" + body + "\r\n0\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                } catch (Exception ignored) {
                    // test thread
                }
            });
            t.start();
            byte[] req = "GET /x HTTP/2\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
            Bridge.Sent sent = DirectHttp.send(new Service("127.0.0.1", server.getLocalPort(), false), req);
            t.join(5000);
            assertNull(sent.error(), sent.error());
            String resp = new String(sent.response(), StandardCharsets.ISO_8859_1);
            assertTrue(resp.startsWith("HTTP/1.1 200"), resp);
            assertTrue(resp.contains("Content-Length: 2"), resp);
            assertTrue(resp.endsWith("\r\n\r\nok"), resp);
        }
    }
}
