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

    void toRepeater(Service service, byte[] request, String tabName);

    void out(String line);

    void err(String line);
}
