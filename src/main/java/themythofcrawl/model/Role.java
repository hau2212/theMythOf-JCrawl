package themythofcrawl.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import themythofcrawl.core.Text;

/** A role profile (01_Admin, 10_Guest ...) or the API gốc profile. Immutable. */
public record Role(String name, boolean enabled, List<HeaderRule> rules, Tier tier) {
    public static final String ORIGINAL_PROFILE_NAME = "00_API GỐC (Acc Crawl)";
    /** Display name of the crawl account row (V_9.3; old workspaces stored "[API GỐC] 00_Original (Acc Crawl)"). */
    public static final String ORIGINAL_LABEL = "Tài khoản crawl";

    /** Cấp quyền dự kiến của Role (dùng để nhận diện nhanh & phân tầng trong bảng Role). */
    public enum Tier {
        ADMIN("ADM", "Quản trị cao nhất (Admin)"),
        MANAGER("MGR", "Quản lý / Điều hành (Manager)"),
        STAFF("STF", "Nhân viên / Hỗ trợ / Auditor"),
        USER("USR", "Người dùng thường (User)"),
        GUEST("GST", "Khách / Chưa đăng nhập (Guest)"),
        CUSTOM("CUS", "Tùy chỉnh");

        public final String badge;
        public final String label;

        Tier(String badge, String label) {
            this.badge = badge;
            this.label = label;
        }

        public static Tier infer(String roleName, List<HeaderRule> rules) {
            String n = Text.nz(roleName).toLowerCase(Locale.ROOT);
            if (n.contains("admin") || n.contains("root") || n.contains("super")) {
                return ADMIN;
            }
            if (n.contains("manager") || n.contains("lead") || n.contains("mod")) {
                return MANAGER;
            }
            if (n.contains("staff") || n.contains("support") || n.contains("audit") || n.contains("operator")) {
                return STAFF;
            }
            if (n.contains("guest") || n.contains("anon") || n.contains("unauth") || n.contains("noauth")
                    || n.contains("public") || n.contains("khach") || n.contains("expired")) {
                return GUEST;
            }
            if (n.contains("user") || n.contains("member") || n.contains("tenant") || n.contains("client")) {
                return USER;
            }
            if (rules != null && !rules.isEmpty()) {
                boolean removed = false;
                boolean added = false;
                for (HeaderRule r : rules) {
                    String hn = r.name().toLowerCase(Locale.ROOT);
                    if (hn.equals("authorization") || hn.equals("cookie") || hn.equals("x-auth-token")
                            || hn.equals("x-api-key") || r.mode().cookie()) {
                        if (r.mode().removes()) {
                            removed = true;
                        } else {
                            added = true;
                        }
                    }
                }
                if (removed && !added) {
                    return GUEST;
                }
            }
            return CUSTOM;
        }
    }

    public Role {
        rules = List.copyOf(rules == null ? List.of() : rules);
        tier = tier == null ? Tier.infer(name, rules) : tier;
    }

    public Role(String name, boolean enabled, List<HeaderRule> rules) {
        this(name, enabled, rules, Tier.infer(name, rules));
    }

    public Role withEnabled(boolean on) {
        return new Role(name, on, rules, tier);
    }

    public Role withRules(List<HeaderRule> r) {
        return new Role(name, enabled, r, tier);
    }

    public Role withName(String n) {
        return new Role(n, enabled, rules, tier);
    }

    public Role withTier(Tier t) {
        return new Role(name, enabled, rules, t == null ? Tier.infer(name, rules) : t);
    }

    /** Unauthenticated / stripped-credentials profile (Guest, Anon, or only removes auth headers). */
    public boolean strippedAuth() {
        String n = Text.nz(name).toLowerCase(Locale.ROOT);
        for (String tag : new String[] {"guest", "anon", "unauth", "noauth", "public", "khach"}) {
            if (n.contains(tag)) {
                return true;
            }
        }
        if (rules.isEmpty()) {
            return false;
        }
        boolean removed = false;
        boolean added = false;
        for (HeaderRule r : rules) {
            String hn = r.name().toLowerCase(Locale.ROOT);
            if (hn.equals("authorization") || hn.equals("cookie") || hn.equals("x-auth-token")
                    || hn.equals("x-api-key") || r.mode().cookie()) {
                if (r.mode().removes()) {
                    removed = true;
                } else {
                    added = true;
                }
            }
        }
        return removed && !added;
    }

    /** One-line summary of the rules for the roles table. */
    public String rulesSummary() {
        List<String> parts = new ArrayList<>();
        for (HeaderRule r : rules) {
            parts.add(r.mode().removes() ? "−" + r.name() : r.name() + "=" + Text.shortPath("/" + r.value(), 18).substring(1));
        }
        return String.join(", ", parts);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("enabled", enabled);
        m.put("tier", tier.name());
        List<Object> hs = new ArrayList<>();
        for (HeaderRule r : rules) {
            hs.add(r.toMap());
        }
        m.put("headers", hs);
        return m;
    }

    /** Accepts the flexible JSON shapes of role files (list or map of headers, "Name: value" strings). */
    @SuppressWarnings("unchecked")
    public static Role fromMap(Object obj, int index) {
        if (!(obj instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("Role #" + (index + 1) + " is not a JSON object");
        }
        String name = Text.nz(m.get("name")).trim();
        if (name.isEmpty()) {
            name = String.format("Role_%02d", index + 1);
        }
        boolean enabled = Text.asBool(m.containsKey("enabled") ? m.get("enabled") : Boolean.TRUE, true);
        Object raw = m.get("headers");
        List<Object> items = new ArrayList<>();
        if (raw instanceof Map<?, ?> hm) {
            for (Map.Entry<?, ?> e : hm.entrySet()) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("name", e.getKey());
                h.put("value", e.getValue());
                items.add(h);
            }
        } else if (raw instanceof List<?> l) {
            items.addAll(l);
        }
        List<HeaderRule> rules = new ArrayList<>();
        for (Object h : items) {
            HeaderRule rule = null;
            if (h instanceof String s) {
                int c = s.indexOf(':');
                if (c > 0) {
                    rule = HeaderRule.of(s.substring(0, c), s.substring(c + 1), null);
                }
            } else if (h instanceof Map<?, ?> hm2) {
                Map<String, Object> hmap = (Map<String, Object>) hm2;
                rule = HeaderRule.of(hmap.get("name"), hmap.get("value"), hmap.get("mode"));
            }
            if (rule != null) {
                rules.add(rule);
            }
        }
        Tier tier = null;
        Object rawTier = m.get("tier");
        if (rawTier != null) {
            try {
                tier = Tier.valueOf(String.valueOf(rawTier).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                tier = null;
            }
        }
        return new Role(name, enabled, rules, tier);
    }
}
