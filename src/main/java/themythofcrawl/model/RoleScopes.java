package themythofcrawl.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import themythofcrawl.core.Hierarchy;

/**
 * Which roles an API gets: the role's global Enabled flag, overridden by branch rules (a deeper
 * branch wins over its parents), overridden by the API's own overrides.
 */
public final class RoleScopes {
    public enum Scope { GLOBAL, BRANCH, API }

    /** A role enabled for one API, with its position in the global list. */
    public record Indexed(Role role, int index) {
    }

    private RoleScopes() {
    }

    public static Map<String, Boolean> states(List<Role> all, String branch, List<BranchRule> rules,
                                              Map<String, Boolean> overrides) {
        Map<String, Boolean> state = new LinkedHashMap<>();
        for (Role r : all) {
            state.put(r.name(), r.enabled());
        }
        List<BranchRule> matching = new ArrayList<>();
        for (BranchRule r : rules == null ? List.<BranchRule>of() : rules) {
            if (Hierarchy.matchesPrefix(branch, r.prefix())) {
                matching.add(r);
            }
        }
        matching.sort(Comparator.comparingInt(r -> Hierarchy.depth(r.prefix())));
        for (BranchRule rule : matching) {
            rule.roles().forEach((name, on) -> {
                if (state.containsKey(name)) {
                    state.put(name, on);
                }
            });
        }
        if (overrides != null) {
            overrides.forEach((name, on) -> {
                if (state.containsKey(name)) {
                    state.put(name, on);
                }
            });
        }
        return state;
    }

    public static List<Indexed> effective(List<Role> all, String branch, List<BranchRule> rules,
                                          Map<String, Boolean> overrides) {
        Map<String, Boolean> state = states(all, branch, rules, overrides);
        List<Indexed> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (Boolean.TRUE.equals(state.get(all.get(i).name()))) {
                out.add(new Indexed(all.get(i), i));
            }
        }
        return out;
    }

    /** Upsert a branch rule (copy-on-write list). */
    public static List<BranchRule> withRule(List<BranchRule> rules, String prefix, Collection<String> roleNames,
                                            boolean enable) {
        List<BranchRule> out = new ArrayList<>();
        BranchRule target = null;
        String clean = Hierarchy.normalize(prefix);
        for (BranchRule r : rules) {
            BranchRule c = r.copy();
            if (c.prefix().equalsIgnoreCase(clean)) {
                target = c;
            }
            out.add(c);
        }
        if (target == null) {
            target = new BranchRule(clean, Map.of());
            out.add(target);
        }
        for (String n : roleNames) {
            target.roles().put(n, enable);
        }
        return out;
    }

    public record Preview(int changed, int added, int removed) {
    }

    /** What applying a scope rule would change on the captured APIs (shown before applying). */
    public static Preview preview(List<Role> all, Collection<ApiGroup> groups, List<BranchRule> rules,
                                  Collection<String> roleNames, boolean enable, Scope scope,
                                  Collection<?> targets) {
        Set<String> names = new HashSet<>(roleNames);
        List<Role> roles = new ArrayList<>();
        for (Role r : all) {
            roles.add(scope == Scope.GLOBAL && names.contains(r.name()) ? r.withEnabled(enable) : r);
        }
        List<BranchRule> rs = new ArrayList<>(rules);
        List<ApiGroup> affected = new ArrayList<>();
        if (scope == Scope.GLOBAL) {
            affected.addAll(groups);
        } else if (scope == Scope.BRANCH) {
            for (Object t : targets) {
                rs = withRule(rs, String.valueOf(t), names, enable);
            }
            for (ApiGroup g : groups) {
                for (Object t : targets) {
                    if (Hierarchy.matchesPrefix(g.branch(), String.valueOf(t))) {
                        affected.add(g);
                        break;
                    }
                }
            }
        } else {
            for (Object t : targets) {
                affected.add((ApiGroup) t);
            }
        }
        int changed = 0;
        int added = 0;
        int removed = 0;
        for (ApiGroup g : affected) {
            Map<String, Boolean> ov = new LinkedHashMap<>(g.roleOverrides());
            if (scope == Scope.API) {
                for (String n : names) {
                    ov.put(n, enable);
                }
            }
            Set<String> before = new HashSet<>();
            for (RoleResult r : g.results()) {
                before.add(r.roleKey());
            }
            Set<String> after = new HashSet<>();
            for (Indexed ir : effective(roles, g.branch(), rs, ov)) {
                after.add(ir.role().name());
            }
            if (!before.equals(after)) {
                changed++;
                Set<String> a = new HashSet<>(after);
                a.removeAll(before);
                Set<String> b = new HashSet<>(before);
                b.removeAll(after);
                added += a.size();
                removed += b.size();
            }
        }
        return new Preview(changed, added, removed);
    }

    /** [(branch, APIs inside)] for every branch level holding captured APIs, in tree order. */
    public static List<Map.Entry<String, Integer>> branchCounts(Collection<ApiGroup> groups) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ApiGroup g : groups) {
            for (String level : Hierarchy.levels(g.branch())) {
                counts.merge(level, 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> out = new ArrayList<>(counts.entrySet());
        out.sort((x, y) -> Hierarchy.key(x.getKey()).compareTo(Hierarchy.key(y.getKey())));
        return out;
    }
}
