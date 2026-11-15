package themythofcrawl.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Branch paths such as {@code Toppage > menu > button "Search"}. */
public final class HierarchyPath {
    public static final String DEFAULT = "Toppage > menu > button \"Search\"";

    private HierarchyPath() {
    }

    public static List<String> split(String path) {
        List<String> parts = new ArrayList<>();
        if (path != null) {
            for (String p : path.split(">")) {
                String t = p.trim();
                if (!t.isEmpty()) {
                    parts.add(t);
                }
            }
        }
        if (parts.isEmpty()) {
            parts.add("Toppage");
        }
        return parts;
    }

    public static String normalize(String path) {
        return String.join(" > ", split(path));
    }

    /** True if {@code path} is {@code prefix} or lies below it (case-insensitive, segment-wise). */
    public static boolean startsWith(String path, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return true;
        }
        List<String> p = split(path);
        List<String> q = split(prefix);
        if (q.size() > p.size()) {
            return false;
        }
        for (int i = 0; i < q.size(); i++) {
            if (!p.get(i).toLowerCase(Locale.ROOT).equals(q.get(i).toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
