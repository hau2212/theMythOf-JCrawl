package themythofcrawl.model;

import java.util.LinkedHashMap;
import java.util.Map;

import themythofcrawl.core.Hierarchy;

/** Roles switched on / off for one branch (and its sub-branches, also for APIs captured later). */
public record BranchRule(String prefix, Map<String, Boolean> roles) {
    public BranchRule {
        prefix = Hierarchy.normalize(prefix);
        roles = new LinkedHashMap<>(roles == null ? Map.of() : roles);
    }

    public BranchRule copy() {
        return new BranchRule(prefix, roles);
    }
}
