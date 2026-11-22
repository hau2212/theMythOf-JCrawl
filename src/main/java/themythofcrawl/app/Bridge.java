package themythofcrawl.app;

import themythofcrawl.model.Service;

/** What the engine needs from Burp (a fake one in tests). */
public interface Bridge {
    /** Response (null on failure), elapsed time, error text. */
    record Sent(byte[] response, long elapsedMs, String error) {
    }

    boolean inScope(String url);

    /** Blocking; called from send worker threads. */
    Sent send(Service service, byte[] request);

    /**
     * V_10.4 "Gửi riêng": send outside Burp's networking so Logger / Logger++ do not see it.
     * The default (tests, other hosts) is a normal send.
     */
    default Sent sendQuiet(Service service, byte[] request) {
        return send(service, request);
    }

    void toRepeater(Service service, byte[] request, String tabName);

    void out(String line);

    void err(String line);
}
