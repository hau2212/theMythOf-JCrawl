package themythofcrawl.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ResultState;
import themythofcrawl.model.RoleResult;

/** Colours and verdicts of roles, APIs and branches (cached on the objects for O(1) painting). */
public final class Audit {
    private Audit() {
    }

    /** Level + badge of one row; cached on the row. */
    public static void evaluate(ApiGroup group, RoleResult res) {
        if (res == null) {
            return;
        }
        Integer st = res.status();
        if (res.original()) {
            if (st == null) {
                res.setAudit(AuditLevel.NONE, "API GỐC (Chưa có Response)");
            } else if (st >= 200 && st < 300) {
                res.setAudit(AuditLevel.OK, "API GỐC (Acc Crawl: " + st + ")");
            } else if (st >= 300 && st < 400) {
                res.setAudit(AuditLevel.REDIRECT, "API GỐC (Redirect: " + st + ")");
            } else {
                res.setAudit(AuditLevel.INFO, "API GỐC (Status: " + st + ")");
            }
            return;
        }
        if (ResultState.ERROR.equals(res.state())) {
            String msg = Text.blank(res.error()) ? "Timeout / Connection Error" : res.error();
            if (msg.length() > 34) {
                msg = msg.substring(0, 31) + "...";
            }
            res.setAudit(AuditLevel.INFO, "INFO: Lỗi gọi API (" + msg + ")");
            return;
        }
        if (st == null) {
            res.setAudit(AuditLevel.NONE, res.state());
            return;
        }
        int code = st;
        if (code == 401) {
            res.setAudit(AuditLevel.INFO, "INFO: Chưa xác thực (401)");
        } else if (code == 403) {
            res.setAudit(AuditLevel.INFO, "INFO: Không đủ quyền (403)");
        } else if (code >= 400 && code < 500) {
            res.setAudit(AuditLevel.INFO, "INFO: Role gọi lỗi (" + code + ")");
        } else if (code >= 500) {
            res.setAudit(AuditLevel.INFO, "INFO: Lỗi Server (" + code + ")");
        } else if (code >= 300) {
            res.setAudit(AuditLevel.REDIRECT, "INFO: Chuyển hướng (" + code + ")");
        } else if (code >= 200) {
            List<RoleResult> results = group == null ? List.of() : group.results();
            boolean anyDenied = false;
            for (RoleResult r : results) {
                if (r != res && r.status() != null && (r.status() == 401 || r.status() == 403)) {
                    anyDenied = true;
                    break;
                }
            }
            if (res.strippedAuth() && anyDenied) {
                res.setAudit(AuditLevel.WARN, "WARN: Guest trả về " + code + "!");
            } else {
                Integer base = null;
                for (RoleResult r : results) {
                    if (r.status() != null && r.status() >= 200 && r.status() < 300 && r.length() != null) {
                        base = r.length();
                        break;
                    }
                }
                if (base == null && group != null) {
                    base = group.origLength();
                }
                if (base != null && res.length() != null && !res.length().equals(base)) {
                    res.setAudit(AuditLevel.INFO, String.format("INFO: 2xx lệch Body (%+dB)", res.length() - base));
                } else {
                    res.setAudit(AuditLevel.OK, "OK: Thành công (" + code + ")");
                }
            }
        } else {
            res.setAudit(AuditLevel.INFO, "INFO: Status " + code);
        }
    }

    /** Level + summary of an API; cached on the group. */
    public static void evaluate(ApiGroup g) {
        List<RoleResult> results = g.results();
        if (results.isEmpty()) {
            Integer st = g.origResult().status();
            if (st == null) {
                g.setAudit(AuditLevel.NONE, "API gốc (chưa có response)");
            } else {
                AuditLevel lvl = st >= 200 && st < 300 ? AuditLevel.OK
                        : st >= 300 && st < 400 ? AuditLevel.REDIRECT : AuditLevel.INFO;
                g.setAudit(lvl, "API gốc (" + st + ")");
            }
            return;
        }
        List<RoleResult> done = new ArrayList<>();
        for (RoleResult r : results) {
            if (r.status() != null || ResultState.ERROR.equals(r.state())) {
                done.add(r);
            }
        }
        if (done.isEmpty()) {
            g.setAudit(AuditLevel.NONE, "Chưa Gửi (1 Gốc + " + results.size() + " Role)");
            return;
        }
        List<RoleResult> ok = new ArrayList<>();
        List<RoleResult> err = new ArrayList<>();
        List<RoleResult> redir = new ArrayList<>();
        for (RoleResult r : done) {
            Integer st = r.status();
            if (st != null && st >= 200 && st < 300) {
                ok.add(r);
            }
            if (ResultState.ERROR.equals(r.state()) || (st != null && st >= 400)) {
                err.add(r);
            }
            if (st != null && st >= 300 && st < 400) {
                redir.add(r);
            }
        }
        if (!err.isEmpty()) {
            List<String> codes = new ArrayList<>();
            for (RoleResult r : err) {
                String c = ResultState.ERROR.equals(r.state()) ? "ERR" : String.valueOf(r.status());
                if (!codes.contains(c)) {
                    codes.add(c);
                }
            }
            String cs = String.join(",", codes.subList(0, Math.min(4, codes.size())));
            g.setAudit(AuditLevel.INFO, ok.isEmpty()
                    ? "INFO: Cả " + err.size() + " Role lỗi (" + cs + ")"
                    : "INFO: " + ok.size() + " OK / " + err.size() + " Lỗi (" + cs + ")");
        } else if (!ok.isEmpty()) {
            Set<Integer> lengths = new HashSet<>();
            for (RoleResult r : ok) {
                if (r.length() != null) {
                    lengths.add(r.length());
                }
            }
            if (!redir.isEmpty()) {
                g.setAudit(AuditLevel.INFO, "INFO: " + ok.size() + " OK / " + redir.size() + " Redirect (3xx)");
            } else if (lengths.size() > 1) {
                g.setAudit(AuditLevel.INFO, "INFO: Cả " + ok.size() + " Role 2xx (Lệch Body)");
            } else {
                g.setAudit(AuditLevel.OK, "OK: Cả " + ok.size() + " Role 2xx");
            }
        } else if (!redir.isEmpty()) {
            g.setAudit(AuditLevel.REDIRECT, "INFO: " + redir.size() + " Role Redirect (3xx)");
        } else {
            g.setAudit(AuditLevel.NONE, "Đang xử lý");
        }
    }

    /** Re-evaluate every row of an API then the API itself. */
    public static void recalc(ApiGroup g) {
        evaluate(g, g.origResult());
        for (RoleResult r : g.results()) {
            evaluate(g, r);
        }
        evaluate(g);
    }

    public static AuditLevel level(ApiGroup g) {
        if (g.auditLevel() == null) {
            recalc(g);
        }
        return g.auditLevel();
    }

    public static String summary(ApiGroup g) {
        if (g.auditSummary() == null) {
            recalc(g);
        }
        return g.auditSummary();
    }

    public static AuditLevel level(ApiGroup g, RoleResult r) {
        if (r.auditLevel() == null) {
            evaluate(g, r);
        }
        return r.auditLevel();
    }

    public static String badge(ApiGroup g, RoleResult r) {
        if (r.auditBadge() == null) {
            evaluate(g, r);
        }
        return r.auditBadge();
    }

    /** Grey while the API's place is not confirmed, else its audit colour. */
    public static AuditLevel display(ApiGroup g) {
        return g.pending() ? AuditLevel.PENDING : level(g);
    }

    public record Verdict(AuditLevel level, String summary) {
    }

    public static Verdict fromCounts(int total, int flagged, int ok) {
        if (total <= 0) {
            return new Verdict(AuditLevel.NONE, "-");
        }
        if (flagged > 0) {
            return new Verdict(AuditLevel.INFO, "INFO: " + flagged + "/" + total + " API có Role lỗi/lệch");
        }
        if (ok > 0) {
            return new Verdict(AuditLevel.OK, "OK: " + ok + "/" + total + " API 2xx");
        }
        return new Verdict(AuditLevel.NONE, "Chưa Gửi (" + total + " API)");
    }

    public static Verdict branch(Collection<ApiGroup> groups) {
        int flagged = 0;
        int ok = 0;
        for (ApiGroup g : groups) {
            AuditLevel l = level(g);
            if (l.flagged()) {
                flagged++;
            } else if (l == AuditLevel.OK) {
                ok++;
            }
        }
        return fromCounts(groups.size(), flagged, ok);
    }

    /** Folder counters of the trees computed in one pass: key = Hierarchy.key(prefix) -> [total, flagged, ok]. */
    public static Map<String, int[]> prefixCounts(Collection<ApiGroup> groups) {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (ApiGroup g : groups) {
            AuditLevel l = level(g);
            List<String> parts = Hierarchy.split(g.branch());
            for (int i = 1; i <= parts.size(); i++) {
                int[] c = out.computeIfAbsent(Hierarchy.key(parts.subList(0, i)), k -> new int[3]);
                c[0]++;
                if (l.flagged()) {
                    c[1]++;
                }
                if (l == AuditLevel.OK) {
                    c[2]++;
                }
            }
        }
        return out;
    }
}
