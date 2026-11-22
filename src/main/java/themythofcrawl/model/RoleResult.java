package themythofcrawl.model;

import java.util.List;

import themythofcrawl.core.AuditLevel;

/**
 * One row of an API: its original request, or one role's variant. A role variant is virtual: it keeps
 * the role's rule snapshot and its request is built from the original when it is viewed / sent /
 * pushed (see RoleRequestBuilder), unless the user edited it by hand.
 */
public final class RoleResult {
    private volatile String roleName;  // display name, may carry a 01_ prefix
    private final String roleKey;      // the role's own name
    private final int roleIndex;       // position in the global role list (-1 for the original)
    private final boolean original;
    private final boolean strippedAuth;
    private volatile List<HeaderRule> rules;
    private volatile Blob customRequest;
    private volatile Blob response;
    private volatile Integer status;
    private volatile Integer length;
    private volatile Long elapsedMs;
    private volatile String state;
    private volatile String error = "";
    private volatile int sentCount;
    private volatile String tabName = "";
    private volatile Blob sentRequest;    // V_8.2: the request really sent last time, when it differs (ID swapped...)
    private volatile String sentNote = ""; // V_8.2: e.g. "ID 91 · tạo mới bởi API #3"
    private volatile AuditLevel auditLevel;
    private volatile String auditBadge;
    private volatile int id;           // stable id inside its API (names its stored messages)

    public RoleResult(String roleName, String roleKey, int roleIndex, boolean original, boolean strippedAuth,
                      List<HeaderRule> rules) {
        this.roleName = roleName;
        this.roleKey = roleKey == null ? roleName : roleKey;
        this.roleIndex = roleIndex;
        this.original = original;
        this.strippedAuth = strippedAuth;
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        this.state = original ? ResultState.ORIGIN : ResultState.NOT_SENT;
    }

    public int id() { return id; }
    public void setId(int i) { id = i; }
    public String roleName() { return roleName; }
    public void setRoleName(String n) { roleName = n; }
    public String roleKey() { return roleKey; }
    public int roleIndex() { return roleIndex; }
    public boolean original() { return original; }
    public boolean strippedAuth() { return strippedAuth; }
    public List<HeaderRule> rules() { return rules; }
    public void setRules(List<HeaderRule> r) { rules = r == null ? List.of() : List.copyOf(r); }
    public Blob customRequest() { return customRequest; }
    public void setCustomRequest(Blob b) { customRequest = b; }
    public Blob responseBlob() { return response; }
    public void setResponse(Blob b) { response = b; }
    public Integer status() { return status; }
    public void setStatus(Integer s) { status = s; }
    public Integer length() { return length; }
    public void setLength(Integer l) { length = l; }
    public Long elapsedMs() { return elapsedMs; }
    public void setElapsedMs(Long e) { elapsedMs = e; }
    public String state() { return state; }
    public void setState(String s) { state = s; }
    public String error() { return error; }
    public void setError(String e) { error = e == null ? "" : e; }
    public int sentCount() { return sentCount; }
    public void incSent() { sentCount++; }
    public void setSentCount(int n) { sentCount = n; }
    public String tabName() { return tabName; }
    public void setTabName(String t) { tabName = t == null ? "" : t; }
    public Blob sentRequest() { return sentRequest; }
    public void setSentRequest(Blob b) { sentRequest = b; }
    public String sentNote() { return sentNote; }
    public void setSentNote(String n) { sentNote = n == null ? "" : n; }
    public AuditLevel auditLevel() { return auditLevel; }
    public String auditBadge() { return auditBadge; }

    public void setAudit(AuditLevel level, String badge) {
        auditLevel = level;
        auditBadge = badge;
    }
}
