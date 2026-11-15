package themythofcrawl.capture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.Supplier;

import themythofcrawl.excel.TargetList;
import themythofcrawl.model.CapturedApi;
import themythofcrawl.model.HierarchyPath;
import themythofcrawl.workspace.Workspace;
import themythofcrawl.workspace.WorkspaceManager;

/**
 * Turns one observed request/response into a {@link CapturedApi} of the current workspace, or into
 * a skip reason. Independent of Burp so it can be unit-tested; the Burp handlers only feed it bytes.
 */
public final class CaptureService {
    /** One message to consider. {@code inScope} comes from Burp's target scope. */
    public record Message(String method, boolean secure, String host, int port, String path,
                          byte[] request, byte[] response, String source, boolean inScope) {
    }

    /** Outcome: the stored API, or why it was skipped. */
    public record Result(CapturedApi api, String skipReason) {
        static Result skipped(String why) {
            return new Result(null, why);
        }
    }

    private final WorkspaceManager workspaces;
    private final Supplier<TargetList> targets;
    private final Consumer<String> log;
    private volatile String lastSkip = "";
    private volatile int skipped;

    public CaptureService(WorkspaceManager workspaces, Supplier<TargetList> targets, Consumer<String> log) {
        this.workspaces = workspaces;
        this.targets = targets;
        this.log = log;
    }

    public String lastSkipReason() {
        return lastSkip;
    }

    public int skippedCount() {
        return skipped;
    }

    public static CaptureSettings settingsOf(Workspace w) {
        return w == null ? CaptureSettings.defaults() : new CaptureSettings(w.settings());
    }

    /** Cheap pre-check on the proxy thread, before any byte is copied. Null = keep going. */
    public String quickReject(String method, String path) {
        Workspace w = workspaces.current();
        if (w == null) {
            return "chưa mở workspace";
        }
        CaptureSettings s = settingsOf(w);
        if (!s.captureEnabled) {
            return "capture đang tắt";
        }
        return filterReason(s, method, path, true, true, targets.get());
    }

    private static String filterReason(CaptureSettings s, String method, String path, boolean inScope,
                                       boolean skipScope, TargetList list) {
        if (s.ignoreOptionsHead && ("OPTIONS".equals(method) || "HEAD".equals(method))) {
            return "bỏ qua " + method;
        }
        if (!skipScope && s.inScopeOnly && !inScope) {
            return "ngoài scope";
        }
        if (s.ignoreStatic && s.isStatic(path)) {
            return "file tĩnh";
        }
        if (s.targetOnly && list != null && list.size() > 0) {
            if (list.match(method, path, s.targetAllowPrefix) == null) {
                return "không có trong danh sách Excel";
            }
        } else if (!s.regexAccepts(path)) {
            return "path không khớp Regex";
        }
        return null;
    }

    /** Runs on the single capture thread: deterministic API numbering and de-duplication. */
    public Result capture(Message m, boolean applyFilters) {
        Workspace w = workspaces.current();
        if (w == null) {
            return Result.skipped("chưa mở workspace");
        }
        CaptureSettings s = settingsOf(w);
        TargetList list = targets.get();
        TargetList.Entry target = list == null ? null : list.match(m.method(), m.path(), s.targetAllowPrefix);
        String branch = w.activeBranch();
        if (target != null && !target.position.isEmpty() && s.targetUsePosition) {
            branch = HierarchyPath.normalize(target.position);
        }
        if (applyFilters) {
            String why = filterReason(s, m.method(), m.path(), m.inScope(), false, list);
            if (why == null && s.dedup
                    && !w.markSeen(s.dedupKey(m.method(), m.secure(), m.host(), m.port(), m.path(), branch))) {
                why = "trùng endpoint đã bắt";
            }
            if (why != null) {
                skipped++;
                lastSkip = why;
                return Result.skipped(why);
            }
        }
        int id = w.reserveId();
        CapturedApi api = new CapturedApi(id, m.method(), m.secure(), m.host(), m.port(), m.path(), branch,
                m.source(), System.currentTimeMillis());
        api.targetStt = target == null ? null : target.stt;
        if (m.response() != null && m.response().length > 0) {
            api.origStatus = statusCode(m.response());
            api.origLength = m.response().length - bodyOffset(m.response());
        }
        try {
            workspaces.store().writeMessage(w, id, "request", m.request());
            workspaces.store().writeMessage(w, id, "response", m.response());
        } catch (IOException e) {
            log.accept("Không ghi được request/response của API " + id + ": " + e);
        }
        w.addApi(api);
        return new Result(api, null);
    }

    public static int statusCode(byte[] response) {
        int end = 0;
        while (end < response.length && end < 256 && response[end] != '\r' && response[end] != '\n') {
            end++;
        }
        String[] parts = new String(response, 0, end, StandardCharsets.ISO_8859_1).split(" ");
        try {
            return parts.length > 1 ? Integer.parseInt(parts[1].trim()) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static int bodyOffset(byte[] msg) {
        for (int i = 0; i + 1 < msg.length; i++) {
            if (msg[i] == '\n') {
                if (msg[i + 1] == '\n') {
                    return i + 2;
                }
                if (msg[i + 1] == '\r' && i + 2 < msg.length && msg[i + 2] == '\n') {
                    return i + 3;
                }
            }
        }
        return msg.length;
    }
}
