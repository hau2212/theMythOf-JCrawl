package themythofcrawl.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import themythofcrawl.core.Text;

/** A role profile (01_Admin, 10_Guest ...) or the API gốc profile. Immutable. */
public record Role(String name, boolean enabled, List<HeaderRule> rules) {
    public static final String ORIGINAL_PROFILE_NAME = "00_API GỐC (Acc Crawl)";
    public static final String ORIGINAL_LABEL = "[API GỐC] 00_Original (Acc Crawl)";

    public Role {
        rules = List.copyOf(rules == null ? List.of() : rules);
    }

    public Role withEnabled(boolean on) {
        return new Role(name, on, rules);
    }

    public Role withRules(List<HeaderRule> r) {
        return new Role(name, enabled, r);
    }

    public Role withName(String n) {
        return new Role(n, enabled, rules);
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
        return new Role(name, enabled, rules);
    }
}
