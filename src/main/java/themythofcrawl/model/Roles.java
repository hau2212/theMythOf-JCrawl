package themythofcrawl.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/** Thread-safe ordered role list + JSON import / export, the 10-role template and bulk paste parsing. */
public final class Roles {
    private final List<Role> roles = new ArrayList<>();

    public synchronized List<Role> all() {
        return List.copyOf(roles);
    }

    public synchronized List<Role> enabled() {
        return roles.stream().filter(Role::enabled).toList();
    }

    public synchronized int count() {
        return roles.size();
    }

    public synchronized int enabledCount() {
        return (int) roles.stream().filter(Role::enabled).count();
    }

    public synchronized Role get(int i) {
        return roles.get(i);
    }

    public synchronized void setAll(List<Role> list) {
        roles.clear();
        roles.addAll(list);
    }

    public synchronized int add(Role r) {
        roles.add(r);
        return roles.size() - 1;
    }

    public synchronized void update(int i, Role r) {
        if (i >= 0 && i < roles.size()) {
            roles.set(i, r);
        }
    }

    public synchronized void setEnabled(int i, boolean on) {
        if (i >= 0 && i < roles.size()) {
            roles.set(i, roles.get(i).withEnabled(on));
        }
    }

    public synchronized void setAllEnabled(boolean on) {
        roles.replaceAll(r -> r.withEnabled(on));
    }

    public synchronized void setEnabledByName(java.util.Collection<String> names, boolean on) {
        roles.replaceAll(r -> names.contains(r.name()) ? r.withEnabled(on) : r);
    }

    public synchronized void remove(java.util.Collection<Integer> indices) {
        indices.stream().distinct().sorted((a, b) -> b - a).forEach(i -> {
            if (i >= 0 && i < roles.size()) {
                roles.remove((int) i);
            }
        });
    }

    /** Swap with the neighbour; returns the role's new index. */
    public synchronized int move(int i, int delta) {
        int j = i + delta;
        if (i >= 0 && i < roles.size() && j >= 0 && j < roles.size()) {
            Role t = roles.get(i);
            roles.set(i, roles.get(j));
            roles.set(j, t);
            return j;
        }
        return i;
    }

    // ------------------------------------------------------------------ JSON

    public static List<Role> fromJson(String text) {
        Object data = Json.parse(text);
        if (data instanceof Map<?, ?> m) {
            data = m.get("roles");
        }
        if (!(data instanceof List<?> list)) {
            throw new IllegalArgumentException("Cần một danh sách role JSON hoặc object có khóa 'roles'");
        }
        List<Role> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            out.add(Role.fromMap(list.get(i), i));
        }
        return out;
    }

    public static String toJson(List<Role> roles) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("extension", Text.EXT_NAME);
        m.put("version", 1);
        List<Object> rs = new ArrayList<>();
        for (Role r : roles) {
            rs.add(r.toMap());
        }
        m.put("roles", rs);
        return Json.pretty(m);
    }

    /** 10 starter profiles (01_Admin .. 10_Guest). */
    public static List<Role> template() {
        String rm = RuleMode.REMOVE_TOKEN;
        Object[][] specs = {
            {"01_Admin", new String[][] {{"Authorization", "Bearer ADMIN_TOKEN", "REPLACE"}, {"X-Role", "admin", "REPLACE"}}},
            {"02_Manager", new String[][] {{"Authorization", "Bearer MANAGER_TOKEN", "REPLACE"}, {"X-Role", "manager", "REPLACE"}}},
            {"03_Auditor", new String[][] {{"Authorization", "Bearer AUDITOR_TOKEN", "REPLACE"}}},
            {"04_Support", new String[][] {{"JSESSIONID", "SUPPORT_SESSION", "COOKIE_SET"}, {"Authorization", rm, "REMOVE"}}},
            {"05_UserA_TenantA", new String[][] {{"Authorization", "Bearer USER_A_TOKEN", "REPLACE"}, {"X-Tenant-ID", "tenant-a", "REPLACE"}}},
            {"06_UserB_TenantA", new String[][] {{"Authorization", "Bearer USER_B_TOKEN", "REPLACE"}, {"X-Tenant-ID", "tenant-a", "REPLACE"}}},
            {"07_UserC_TenantB", new String[][] {{"Authorization", "Bearer USER_C_TOKEN", "REPLACE"}, {"X-Tenant-ID", "tenant-b", "REPLACE"}}},
            {"08_TenantSwap", new String[][] {{"X-Tenant-ID", "tenant-b", "REPLACE"}}},
            {"09_ExpiredToken", new String[][] {{"Authorization", "Bearer EXPIRED_TOKEN", "REPLACE"}}},
            {"10_Guest", new String[][] {{"Authorization", rm, "REMOVE"}, {"Cookie", rm, "REMOVE"}, {"X-Role", rm, "REMOVE"},
                {"X-Tenant-ID", rm, "REMOVE"}}},
        };
        List<Role> out = new ArrayList<>();
        for (Object[] spec : specs) {
            List<HeaderRule> rules = new ArrayList<>();
            for (String[] h : (String[][]) spec[1]) {
                rules.add(HeaderRule.of(h[0], h[1], h[2]));
            }
            out.add(new Role((String) spec[0], true, rules));
        }
        return out;
    }

    /** 'Cookie: a=1; b=2' or 'a=1; b=2' lines -> one COOKIE_SET (COOKIE_REMOVE for [REMOVE]) rule per key. */
    public static List<HeaderRule> parseBulkCookies(String text) {
        List<HeaderRule> out = new ArrayList<>();
        for (String line : Text.nz(text).split("\\R")) {
            String s = line.trim();
            if (s.toLowerCase(java.util.Locale.ROOT).startsWith("cookie:")) {
                s = s.substring(7).trim();
            }
            for (String part : s.split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = HeaderRule.cleanName(p.substring(0, eq));
                if (name.isEmpty()) {
                    continue;
                }
                String val = HeaderRule.cleanValue(p.substring(eq + 1));
                out.add(new HeaderRule(name, val,
                        val.equalsIgnoreCase(RuleMode.REMOVE_TOKEN) ? RuleMode.COOKIE_REMOVE : RuleMode.COOKIE_SET));
            }
        }
        return out;
    }

    /** Pasted 'Name: value' lines -> rules. */
    public static List<HeaderRule> parseBulkHeaders(String text) {
        List<HeaderRule> out = new ArrayList<>();
        for (String line : Text.nz(text).split("\\R")) {
            int c = line.indexOf(':');
            if (c <= 0) {
                continue;
            }
            HeaderRule r = HeaderRule.of(line.substring(0, c), line.substring(c + 1), null);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }
}
