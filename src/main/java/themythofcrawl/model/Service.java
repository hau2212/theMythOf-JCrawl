package themythofcrawl.model;

import java.util.Locale;

/** Target host of a request. */
public record Service(String host, int port, boolean secure) {
    public Service {
        host = host == null ? "" : host;
    }

    public String protocol() {
        return secure ? "https" : "http";
    }

    public boolean defaultPort() {
        return (secure && port == 443) || (!secure && port == 80);
    }

    public String hostLabel() {
        return defaultPort() ? host : host + ":" + port;
    }

    /** Key of the per-host send queue. */
    public String key() {
        return protocol() + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
    }
}
