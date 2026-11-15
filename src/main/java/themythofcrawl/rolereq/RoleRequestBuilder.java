package themythofcrawl.rolereq;

import java.util.List;

import themythofcrawl.model.HeaderRule;

/**
 * Builds the request a role sends from the crawl account's original request.
 *
 * <p>The whole extension calls this one method whenever a role's request is needed: to show it, to send
 * it, to push it to Repeater, to export it. Implement it in {@link RoleRules}.
 */
public interface RoleRequestBuilder {
    /**
     * @param original raw bytes of the original HTTP request (request line, headers, blank line, body),
     *                 exactly as captured; never modified by the caller and must not be modified here
     * @param rules    the role's rules in order (name, value, mode); may be empty
     * @return raw bytes of the role's request; returning {@code original} means "same as the original"
     */
    byte[] build(byte[] original, List<HeaderRule> rules);
}
