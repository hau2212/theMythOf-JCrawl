package themythofcrawl.model;

import java.util.LinkedHashMap;
import java.util.Map;

import themythofcrawl.util.Json;

/**
 * One captured API (the original request seen through Burp). The raw request / response bytes live
 * in the workspace's {@code messages} folder; this object only holds what the tables need.
 */
public final class CapturedApi {
    public final int id;
    public final String method;
    public final boolean secure;
    public final String host;
    public final int port;
    public final String path;
    public final String source;
    public final long createdMillis;
    public volatile String branch;
    public volatile int origStatus;      // -1 when there was no response
    public volatile int origLength;      // body length, -1 unknown
    public volatile String targetStt;    // STT of the Excel row this API matched, or null

    public CapturedApi(int id, String method, boolean secure, String host, int port, String path,
                       String branch, String source, long createdMillis) {
        this.id = id;
        this.method = method;
        this.secure = secure;
        this.host = host;
        this.port = port;
        this.path = path;
        this.branch = HierarchyPath.normalize(branch);
        this.source = source;
        this.createdMillis = createdMillis;
        this.origStatus = -1;
        this.origLength = -1;
    }

    public String label() {
        return "API " + id;
    }

    public String url() {
        boolean defaultPort = (secure && port == 443) || (!secure && port == 80);
        return (secure ? "https://" : "http://") + host + (defaultPort ? "" : ":" + port) + path;
    }

    public String pathWithoutQuery() {
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("method", method);
        m.put("secure", secure);
        m.put("host", host);
        m.put("port", port);
        m.put("path", path);
        m.put("branch", branch);
        m.put("source", source);
        m.put("created", createdMillis);
        m.put("origStatus", origStatus);
        m.put("origLength", origLength);
        m.put("targetStt", targetStt);
        return m;
    }

    public static CapturedApi fromJson(Map<String, Object> m) {
        CapturedApi api = new CapturedApi(
                (int) Json.num(m, "id", 0), Json.str(m, "method", "GET"), Json.bool(m, "secure", true),
                Json.str(m, "host", ""), (int) Json.num(m, "port", 443), Json.str(m, "path", "/"),
                Json.str(m, "branch", HierarchyPath.DEFAULT), Json.str(m, "source", "Proxy"),
                Json.num(m, "created", 0));
        api.origStatus = (int) Json.num(m, "origStatus", -1);
        api.origLength = (int) Json.num(m, "origLength", -1);
        api.targetStt = Json.str(m, "targetStt", null);
        return api;
    }
}
