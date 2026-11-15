package themythofcrawl.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Branch paths such as 'Toppage > menu > button "Search"': split, normalise, compare (case
 * insensitive), and edit one level at a time.
 */
public final class Hierarchy {
    public static final String ROOT = "Toppage";
    public static final String DEFAULT_PATH = "Toppage > menu > button \"Search\"";
    public static final String SEP = " > ";

    private Hierarchy() {
    }

    public static List<String> split(String path) {
        String raw = Text.nz(path).trim();
        List<String> parts = new ArrayList<>();
        if (raw.isEmpty()) {
            parts.add(ROOT);
            return parts;
        }
        for (String p : raw.split(">")) {
            if (!p.isBlank()) {
                parts.add(p.trim());
            }
        }
        if (parts.isEmpty()) {
            parts.add(raw);
        }
        return parts;
    }

    public static String join(List<String> parts) {
        List<String> clean = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                clean.add(p.trim());
            }
        }
        return clean.isEmpty() ? ROOT : String.join(SEP, clean);
    }

    public static String normalize(String path) {
        return join(split(path));
    }

    /** Case-insensitive identity of a path (the key of folder counters). */
    public static String key(String path) {
        return key(split(path));
    }

    public static String key(List<String> parts) {
        List<String> low = new ArrayList<>();
        for (String p : parts) {
            low.add(p.toLowerCase(Locale.ROOT));
        }
        return String.join(SEP, low);
    }

    /** True when `path` is `prefix` or inside it. An empty prefix contains everything. */
    public static boolean matchesPrefix(String path, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return true;
        }
        List<String> g = split(path);
        List<String> p = split(prefix);
        if (p.size() > g.size()) {
            return false;
        }
        for (int i = 0; i < p.size(); i++) {
            if (!g.get(i).equalsIgnoreCase(p.get(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean same(String a, String b) {
        return normalize(a).equalsIgnoreCase(normalize(b));
    }

    public static String append(String path, String segment) {
        List<String> parts = split(path);
        for (String s : Text.nz(segment).split(">")) {
            if (!s.isBlank()) {
                parts.add(s.trim());
            }
        }
        return join(parts);
    }

    public static String parent(String path) {
        List<String> parts = split(path);
        if (parts.size() > 1) {
            parts.remove(parts.size() - 1);
        }
        return join(parts);
    }

    public static String prefix(String path, int levels) {
        List<String> parts = split(path);
        return join(parts.subList(0, Math.max(1, Math.min(levels, parts.size()))));
    }

    public static String replaceAt(String path, int index, String replacement) {
        List<String> parts = split(path);
        if (index < 0 || index >= parts.size()) {
            return join(parts);
        }
        List<String> rep = split(replacement);
        if (Text.blank(replacement)) {
            return deleteAt(path, index);
        }
        List<String> out = new ArrayList<>(parts.subList(0, index));
        out.addAll(rep);
        out.addAll(parts.subList(index + 1, parts.size()));
        return join(out);
    }

    public static String insertAfter(String path, int index, String segment) {
        List<String> parts = split(path);
        if (Text.blank(segment)) {
            return join(parts);
        }
        int pos = Math.max(0, Math.min(parts.size(), index + 1));
        List<String> out = new ArrayList<>(parts.subList(0, pos));
        out.addAll(split(segment));
        out.addAll(parts.subList(pos, parts.size()));
        return join(out);
    }

    public static String deleteAt(String path, int index) {
        List<String> parts = split(path);
        if (index >= 0 && index < parts.size()) {
            parts.remove(index);
        }
        return join(parts);
    }

    /** 'a > b > c' with prefix 'a > b' renamed to 'x' becomes 'x > c'; other paths are unchanged. */
    public static String replacePrefix(String path, String oldPrefix, String newPrefix) {
        if (!matchesPrefix(path, oldPrefix)) {
            return normalize(path);
        }
        List<String> f = split(path);
        List<String> out = new ArrayList<>(split(newPrefix));
        out.addAll(f.subList(split(oldPrefix).size(), f.size()));
        return join(out);
    }

    /** Every level of a path: 'a > b > c' -> ['a', 'a > b', 'a > b > c']. */
    public static List<String> levels(String path) {
        List<String> parts = split(path);
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= parts.size(); i++) {
            out.add(join(parts.subList(0, i)));
        }
        return out;
    }

    public static int depth(String path) {
        return split(path).size();
    }

    public static String leaf(String path) {
        List<String> parts = split(path);
        return parts.get(parts.size() - 1);
    }
}
