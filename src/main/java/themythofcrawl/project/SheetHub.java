package themythofcrawl.project;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/**
 * V_11.0: client of the team Google Sheet (Apps Script web app, {@link #script()}). Every call carries the project
 * passcode; the script stamps server time on every record so "later wins" does not depend on anyone's clock.
 */
public final class SheetHub {
    public static final int HUB_VERSION = 2;

    private final String url;
    private final String pass;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public SheetHub(String url, String pass) {
        this.url = Text.nz(url).trim();
        this.pass = Text.nz(pass);
    }

    /** The Apps Script the PIC pastes into the sheet (Extensions > Apps Script). */
    public static String script() {
        try (InputStream in = SheetHub.class.getResourceAsStream("/themythofcrawl/project/JCrawlSheetHub.gs")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static final class HubException extends IOException {
        public final boolean retry;

        HubException(String msg, boolean retry) {
            super(msg);
            this.retry = retry;
        }
    }

    private Map<String, Object> call(Map<String, Object> body, int timeoutSec) throws IOException {
        if (url.isEmpty()) {
            throw new HubException("Chưa có link Google Sheet", false);
        }
        Map<String, Object> b = new LinkedHashMap<>(body);
        b.put("pass", pass);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "text/plain; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(b), StandardCharsets.UTF_8)).build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HubException("Bị ngắt", true);
        } catch (IOException e) {
            throw new HubException("Không kết nối được Google Sheet: " + e.getMessage(), true);
        }
        int sc = resp.statusCode();
        if (sc == 429 || sc >= 500) {
            throw new HubException("Google Sheet bận (HTTP " + sc + ")", true);
        }
        if (sc < 200 || sc >= 300) {
            throw new HubException("Google Sheet trả HTTP " + sc, false);
        }
        String text = resp.body() == null ? "" : resp.body().trim();
        if (!text.startsWith("{")) {
            throw new HubException(text.toLowerCase(java.util.Locale.ROOT).contains("<html")
                    ? "Link không phải ứng dụng web Apps Script (hoặc chưa cho phép 'Bất kỳ ai')" : "Phản hồi lạ từ Google Sheet", false);
        }
        Map<String, Object> m = Json.parseObject(text);
        if (!Json.bool(m, "ok", false)) {
            throw new HubException(Json.str(m, "error", "Google Sheet báo lỗi"), Json.bool(m, "retry", false));
        }
        return m;
    }

    public record Hello(boolean created, String project, String name, long serverTime) {
    }

    /** First call creates the hub with this passcode; later calls must use the same passcode. */
    public Hello hello(String projectId, String projectName) throws IOException {
        Map<String, Object> m = call(Map.of("action", "hello", "project", Text.nz(projectId), "name", Text.nz(projectName)), 30);
        if (Json.num(m, "hub", 0) < HUB_VERSION) {
            throw new HubException("Apps Script trong Sheet là bản cũ: dán lại mã mới rồi Triển khai > Quản lý > Phiên bản mới", false);
        }
        return new Hello(Json.bool(m, "created", false), Json.str(m, "project", ""), Json.str(m, "name", ""), Json.num(m, "serverTime", 0));
    }

    public record Pushed(List<SyncRecord> stamped, int conflicts) {
    }

    public Pushed push(List<SyncRecord> records) throws IOException {
        List<SyncRecord> out = new ArrayList<>();
        int conflicts = 0;
        for (int i = 0; i < records.size(); i += 150) {
            List<SyncRecord> part = records.subList(i, Math.min(records.size(), i + 150));
            List<Object> list = new ArrayList<>();
            Map<String, SyncRecord> byId = new LinkedHashMap<>();
            for (SyncRecord r : part) {
                Map<String, Object> m = r.toMap();
                m.put("data", Json.write(new LinkedHashMap<>(r.data())));
                list.add(m);
                byId.put(r.id(), r);
            }
            Map<String, Object> resp = call(Map.of("action", "push", "records", list), 60);
            conflicts += (int) Json.num(resp, "conflicts", 0);
            for (Object o : Json.list(resp, "stamped")) {
                if (o instanceof Map<?, ?> sm) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> s = (Map<String, Object>) sm;
                    SyncRecord r = byId.get(Json.str(s, "id", ""));
                    if (r != null) {
                        out.add(r.stamped(Json.num(s, "at", r.at())));
                    }
                }
            }
        }
        return new Pushed(out, conflicts);
    }

    public record Pulled(List<SyncRecord> records, long serverTime) {
    }

    public Pulled pull(long since) throws IOException {
        Map<String, Object> m = call(Map.of("action", "pull", "since", since), 60);
        List<SyncRecord> out = new ArrayList<>();
        for (Object o : Json.list(m, "records")) {
            if (o instanceof Map<?, ?> rm) {
                @SuppressWarnings("unchecked")
                SyncRecord r = SyncRecord.fromMap((Map<String, Object>) rm);
                if (!r.id().isEmpty()) {
                    out.add(r);
                }
            }
        }
        return new Pulled(out, Json.num(m, "serverTime", 0));
    }

    /** Draws the human sheets; ignored by the script when an equal or newer version was already drawn. */
    public boolean render(long version, List<Map<String, Object>> sheets) throws IOException {
        Map<String, Object> m = call(Map.of("action", "render", "version", version, "sheets", sheets), 120);
        return !Json.bool(m, "skipped", false);
    }

    public record Uploaded(String id, String url) {
    }

    public Uploaded uploadImage(String qaId, String name, byte[] data) throws IOException {
        String mime = data.length > 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 ? "image/jpeg" : "image/png";
        Map<String, Object> m = call(Map.of("action", "image", "qa", qaId, "name", name, "mime", mime,
                "b64", Base64.getEncoder().encodeToString(data)), 120);
        return new Uploaded(Json.str(m, "id", ""), Json.str(m, "url", ""));
    }

    public byte[] downloadImage(String fileId) throws IOException {
        Map<String, Object> m = call(Map.of("action", "imageget", "id", fileId), 120);
        return Base64.getDecoder().decode(Json.str(m, "b64", ""));
    }

    public void compact() throws IOException {
        call(Map.of("action", "compact"), 120);
    }

    public static String driveLink(String fileId) {
        return "https://drive.google.com/file/d/" + fileId + "/view";
    }
}
