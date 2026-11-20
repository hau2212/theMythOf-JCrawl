package themythofcrawl.collab;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Git-style conflict record emitted when two team members discover or update the same API
 * endpoint with differing status, branch location, HTTP outcome, or tags.
 */
public final class ConflictRecord {

    public enum Resolution {
        KEEP_LOCAL("Giữ bản của tôi"),
        KEEP_REMOTE("Giữ bản của đồng đội"),
        MERGE_BOTH("Gộp cả hai (Done A, B)"),
        KEEP_BOTH_VARIANTS("Giữ cả 2 biến thể");

        public final String label;

        Resolution(String label) {
            this.label = label;
        }
    }

    public record Side(
            String member,
            InventoryCatalog.ItemStatus status,
            int httpStatus,
            String branch,
            Set<String> tags,
            String note,
            long updatedAtMs) {
        public Side {
            member = member == null || member.isBlank() ? "?" : member.trim();
            status = status == null ? InventoryCatalog.ItemStatus.INFO : status;
            branch = branch == null ? "" : branch;
            tags = tags == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(tags));
            note = note == null ? "" : note;
        }

        public String summary() {
            String st = status.formatWithOwners(Set.of(member));
            String http = httpStatus > 0 ? "HTTP " + httpStatus : "Chưa có HTTP";
            return st + " · " + http + (branch.isEmpty() ? "" : " · " + branch);
        }
    }

    private final String id;
    private final String endpointKey;
    private final String method;
    private final String host;
    private final String pathTemplate;
    private final Side localSide;
    private final Side remoteSide;
    private final long detectedAtMs;
    private final String autoSuggestion;

    public ConflictRecord(
            String id,
            String endpointKey,
            String method,
            String host,
            String pathTemplate,
            Side localSide,
            Side remoteSide,
            long detectedAtMs) {
        this.id = id;
        this.endpointKey = endpointKey;
        this.method = method;
        this.host = host;
        this.pathTemplate = pathTemplate;
        this.localSide = localSide;
        this.remoteSide = remoteSide;
        this.detectedAtMs = detectedAtMs;
        this.autoSuggestion = computeSuggestion(localSide, remoteSide);
    }

    private static String computeSuggestion(Side local, Side remote) {
        boolean local2xx = local.httpStatus() >= 200 && local.httpStatus() < 300;
        boolean remote2xx = remote.httpStatus() >= 200 && remote.httpStatus() < 300;
        if (local2xx && !remote2xx) {
            return "Gợi ý: Gộp hoặc giữ bản " + local.member() + " (2xx) + lưu bản lỗi của " + remote.member() + " làm ghi chú.";
        }
        if (remote2xx && !local2xx) {
            return "Gợi ý: Gộp hoặc giữ bản " + remote.member() + " (2xx) + lưu bản lỗi của " + local.member() + " làm ghi chú.";
        }
        if (!local.branch().equalsIgnoreCase(remote.branch()) && !local.branch().isEmpty() && !remote.branch().isEmpty()) {
            return "Gợi ý: Gộp cả hai để giữ đủ 2 nhánh UI (" + local.branch() + " & " + remote.branch() + ").";
        }
        return "Gợi ý: Gộp cả hai (Done " + local.member() + ", " + remote.member() + ") để không mất dữ liệu.";
    }

    public String id() { return id; }
    public String endpointKey() { return endpointKey; }
    public String method() { return method; }
    public String host() { return host; }
    public String pathTemplate() { return pathTemplate; }
    public Side localSide() { return localSide; }
    public Side remoteSide() { return remoteSide; }
    public long detectedAtMs() { return detectedAtMs; }
    public String autoSuggestion() { return autoSuggestion; }
}
