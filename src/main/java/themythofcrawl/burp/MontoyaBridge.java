package themythofcrawl.burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import themythofcrawl.app.Bridge;
import themythofcrawl.model.Service;

/** Engine -> Burp through the Montoya API. */
final class MontoyaBridge implements Bridge {
    private final MontoyaApi api;

    MontoyaBridge(MontoyaApi api) {
        this.api = api;
    }

    static HttpService service(Service s) {
        return HttpService.httpService(s.host(), s.port(), s.secure());
    }

    static HttpRequest request(Service s, byte[] bytes) {
        return HttpRequest.httpRequest(service(s), ByteArray.byteArray(bytes));
    }

    @Override
    public boolean inScope(String url) {
        try {
            return api.scope().isInScope(url);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Sent send(Service s, byte[] req) {
        long t0 = System.currentTimeMillis();
        try {
            HttpRequestResponse rr = api.http().sendRequest(request(s, req));
            long ms = System.currentTimeMillis() - t0;
            if (rr == null || !rr.hasResponse()) {
                return new Sent(null, ms, "No response (timeout / connection failure)");
            }
            return new Sent(rr.response().toByteArray().getBytes(), ms, null);
        } catch (RuntimeException e) {
            return new Sent(null, System.currentTimeMillis() - t0, String.valueOf(e.getMessage() != null ? e.getMessage() : e));
        }
    }

    @Override
    public void toRepeater(Service s, byte[] req, String tabName) {
        api.repeater().sendToRepeater(request(s, req), tabName);
    }

    @Override
    public void out(String line) {
        api.logging().logToOutput(line);
    }

    @Override
    public void err(String line) {
        api.logging().logToError(line);
    }
}
