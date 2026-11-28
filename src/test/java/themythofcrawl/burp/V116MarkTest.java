package themythofcrawl.burp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class V116MarkTest {
    @Test
    void responseHandedToBrowserIsMarked() {
        byte[] r = "HTTP/1.1 201 Created\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1);
        String out = new String(Extension.markSentByJCrawl(r), StandardCharsets.ISO_8859_1);
        assertEquals("HTTP/1.1 201 Created\r\nX-JCrawl-Sent: 1\r\nContent-Length: 2\r\n\r\nok", out);
    }
}
