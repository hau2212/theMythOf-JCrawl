package themythofcrawl.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import themythofcrawl.core.Text;

/** One rule of a role profile: header / cookie name, value, mode. */
public record HeaderRule(String name, String value, RuleMode mode) {
    private static final Pattern BAD_NAME = Pattern.compile("[\\r\\n:\\s]");

    /** Header names: trimmed, no CR / LF / colon / whitespace; '' when invalid. */
    public static String cleanName(Object name) {
        String n = Text.nz(name).trim();
        return n.isEmpty() || BAD_NAME.matcher(n).find() ? "" : n;
    }

    /** Values never carry CR / LF. */
    public static String cleanValue(Object value) {
        return Text.nz(value).replace("\r", "").replace("\n", "").trim();
    }

    public static HeaderRule of(Object name, Object value, Object mode) {
        String n = cleanName(name);
        if (n.isEmpty()) {
            return null;
        }
        String raw = value == null ? "" : String.valueOf(value);
        return new HeaderRule(n, cleanValue(raw), RuleMode.of(mode, raw));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        m.put("mode", mode.name());
        return m;
    }
}
