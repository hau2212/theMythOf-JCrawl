package themythofcrawl.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Text;
import themythofcrawl.excel.TargetEntry;

/**
 * One captured API: the original request / response of the crawl account plus one virtual row per
 * role, where it sits in the branch tree, and its placement state (pending, duplicate copy, hidden).
 */
public final class ApiGroup {
    private final int gid;
    private final Service service;
    private final String method;
    private final String path;
    private final String url;
    private final String source;
    private final String created;
    private final long createdMillis;
    private volatile int branchId;
    private volatile String branch;
    private volatile Blob origRequest;
    private volatile Blob origResponse;
    private volatile Integer origStatus;
    private volatile Integer origLength;
    private final RoleResult origResult;
    private volatile List<RoleResult> results = List.of();
    private volatile TargetEntry target;
    private final Map<String, Boolean> roleOverrides = new LinkedHashMap<>();
    private volatile Integer copyStt;
    private volatile String copiedLabel;
    private volatile String bookKey;
    private volatile String dedupKey;
    private volatile boolean pending;
    private volatile boolean dupPending;
    private volatile int dupNo = 1;
    private volatile int dupTotal = 1;
    private volatile boolean hidden;
    private volatile PlaceHint placeHint;
    private final List<String> placeHistory = new ArrayList<>();
    private volatile AuditLevel auditLevel;
    private volatile String auditSummary;

    public ApiGroup(int gid, Service service, String method, String path, String url, String source,
                    int branchId, String branch, Blob origRequest, Blob origResponse, String created, long createdMillis) {
        this.gid = gid;
        this.service = service;
        this.method = Text.nz(method);
        this.path = Text.nz(path);
        this.url = Text.nz(url);
        this.source = Text.nz(source);
        this.branchId = branchId;
        this.branch = Text.blank(branch) ? "Chức năng " + branchId : branch.trim();
        this.origRequest = origRequest;
        this.origResponse = origResponse;
        this.created = created == null ? Clock.hms() : created;
        this.createdMillis = createdMillis;
        this.origResult = new RoleResult(Role.ORIGINAL_LABEL, Role.ORIGINAL_PROFILE_NAME, -1, true, false, List.of());
        this.origResult.setState(origResponse != null ? ResultState.ORIGIN : ResultState.NOT_SENT);
    }

    public int gid() { return gid; }
    public Service service() { return service; }
    public String host() { return service.host(); }
    public int port() { return service.port(); }
    public String protocol() { return service.protocol(); }
    public String method() { return method; }
    public String path() { return path; }
    public String url() { return url; }
    public String source() { return source; }
    public String created() { return created; }
    public long createdMillis() { return createdMillis; }
    public int branchId() { return branchId; }
    public String branch() { return branch; }

    public void setBranch(int id, String name) {
        branchId = id;
        branch = name;
    }

    public Blob origRequest() { return origRequest; }
    public Blob origResponse() { return origResponse; }

    public void setOriginal(Blob req, Blob resp) {
        origRequest = req;
        origResponse = resp;
    }

    public Integer origStatus() { return origStatus; }
    public Integer origLength() { return origLength; }

    public void setOrigMeta(Integer status, Integer length) {
        origStatus = status;
        origLength = length;
    }

    public RoleResult origResult() { return origResult; }
    public List<RoleResult> results() { return results; }
    public void setResults(List<RoleResult> r) { results = List.copyOf(r); }

    /** [API gốc] + roles. */
    public List<RoleResult> entries() {
        List<RoleResult> out = new ArrayList<>(results.size() + 1);
        out.add(origResult);
        out.addAll(results);
        return out;
    }

    public TargetEntry target() { return target; }
    public void setTarget(TargetEntry t) { target = t; }

    public synchronized Map<String, Boolean> roleOverrides() {
        return new LinkedHashMap<>(roleOverrides);
    }

    public synchronized void setRoleOverride(String role, Boolean on) {
        if (on == null) {
            roleOverrides.remove(role);
        } else {
            roleOverrides.put(role, on);
        }
    }

    public synchronized void clearRoleOverrides() {
        roleOverrides.clear();
    }

    public Integer copyStt() { return copyStt; }
    public void setCopyStt(Integer n) { copyStt = n; }
    public String copiedLabel() { return copiedLabel; }
    public void setCopiedLabel(String s) { copiedLabel = s; }
    public String bookKey() { return bookKey; }
    public void setBookKey(String k) { bookKey = k; }
    public String dedupKey() { return dedupKey; }
    public void setDedupKey(String k) { dedupKey = k; }
    public boolean pending() { return pending; }
    public void setPending(boolean p) { pending = p; }
    public boolean dupPending() { return dupPending; }
    public void setDupPending(boolean p) { dupPending = p; }
    public int dupNo() { return dupNo; }
    public void setDupNo(int n) { dupNo = n; }
    public int dupTotal() { return dupTotal; }
    public void setDupTotal(int n) { dupTotal = n; }
    public boolean hidden() { return hidden; }
    public void setHidden(boolean h) { hidden = h; }
    public PlaceHint placeHint() { return placeHint; }
    public void setPlaceHint(PlaceHint h) { placeHint = h; }

    public synchronized List<String> placeHistory() {
        return new ArrayList<>(placeHistory);
    }

    public synchronized void pushPlace(String previous) {
        placeHistory.add(previous);
        while (placeHistory.size() > 30) {
            placeHistory.remove(0);
        }
    }

    public synchronized String popPlace() {
        return placeHistory.isEmpty() ? null : placeHistory.remove(placeHistory.size() - 1);
    }

    public AuditLevel auditLevel() { return auditLevel; }
    public String auditSummary() { return auditSummary; }

    public void setAudit(AuditLevel level, String summary) {
        auditLevel = level;
        auditSummary = summary;
    }

    public String endpoint() {
        return method + " " + Text.stripQuery(path);
    }
}
