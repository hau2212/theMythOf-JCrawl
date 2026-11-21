package themythofcrawl.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V_7.0 "Quyền mong đợi": which roles are SUPPOSED to use a branch (screen) of the app, e.g.
 * {@code Toppage > Admin} → {01_Admin}. When set, a role outside the list that still gets the same 2xx
 * content is a confirmed access-control violation even if its UI showed the menu. The most specific
 * (longest) matching branch prefix wins. Thread-safe.
 */
public final class ExpectedAccess {

    /** One rule: roles allowed on {@code branchPrefix} and below. */
    public record Rule(String branchPrefix, Set<String> allowedRoles, String note) {
        public Rule {
            branchPrefix = Hierarchy.normalize(branchPrefix == null ? "" : branchPrefix);
            allowedRoles = Set.copyOf(new LinkedHashSet<>(allowedRoles == null ? Set.of() : allowedRoles));
            note = note == null ? "" : note;
        }

        public boolean allows(String role) {
            return role != null && allowedRoles.contains(role);
        }

        /** Plain Vietnamese sentence shown in the UI. */
        public String sentence() {
            return "Nhánh \"" + branchPrefix + "\" (kèm nhánh con) chỉ dành cho: "
                    + (allowedRoles.isEmpty() ? "(không role nào)" : String.join(", ", allowedRoles));
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    public synchronized List<Rule> rules() {
        return List.copyOf(rules);
    }

    /** Adds or replaces the rule of the same branch prefix. */
    public synchronized void put(Rule r) {
        if (r == null || r.branchPrefix().isBlank()) {
            return;
        }
        rules.removeIf(x -> Hierarchy.same(x.branchPrefix(), r.branchPrefix()));
        rules.add(r);
    }

    public synchronized boolean remove(String branchPrefix) {
        return rules.removeIf(x -> Hierarchy.same(x.branchPrefix(), branchPrefix));
    }

    public synchronized void clear() {
        rules.clear();
    }

    /** The most specific rule covering {@code branch}, or null. */
    public synchronized Rule match(String branch) {
        Rule best = null;
        for (Rule r : rules) {
            if (Hierarchy.matchesPrefix(branch, r.branchPrefix())
                    && (best == null || r.branchPrefix().length() > best.branchPrefix().length())) {
                best = r;
            }
        }
        return best;
    }

    public synchronized String toJson() {
        List<Object> out = new ArrayList<>();
        for (Rule r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("branch", r.branchPrefix());
            m.put("roles", new ArrayList<>(r.allowedRoles()));
            m.put("note", r.note());
            out.add(m);
        }
        return Json.write(out);
    }

    @SuppressWarnings("unchecked")
    public synchronized void loadJson(String text) {
        rules.clear();
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            Object root = Json.parse(text);
            if (!(root instanceof List<?> list)) {
                return;
            }
            for (Object o : list) {
                if (o instanceof Map<?, ?> raw) {
                    Map<String, Object> m = (Map<String, Object>) raw;
                    Set<String> roles = new LinkedHashSet<>();
                    for (Object x : Json.list(m, "roles")) {
                        if (x != null) {
                            roles.add(String.valueOf(x));
                        }
                    }
                    put(new Rule(Json.str(m, "branch", ""), roles, Json.str(m, "note", "")));
                }
            }
        } catch (RuntimeException ignored) {
            // corrupted preference: start empty
        }
    }
}
