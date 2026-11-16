package themythofcrawl.core;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** String / HTTP-line helpers shared by capture, matching, labels and exports (no Burp, no Swing). */
public final class Text {
    public static final String EXT_NAME = "theMythOf-JCrawl";

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern NUM = Pattern.compile("^[0-9]+$");
    private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]{16,}$");
    private static final Pattern ROLE_NUM_PREFIX = Pattern.compile("^[0-9]+[_\\-. ].*");
    private static final Pattern PCT = Pattern.compile("%([0-9A-Fa-f]{2})");

    private Text() {
    }

    public static String nz(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    public static boolean blank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    // ------------------------------------------------------------------ paths

    /** '/a/b?x=1#f' -> '/a/b' (never empty). */
    public static String stripQuery(String path) {
        String p = path == null || path.isEmpty() ? "/" : path;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        int h = p.indexOf('#');
        if (h >= 0) {
            p = p.substring(0, h);
        }
        return p.isEmpty() ? "/" : p;
    }

    public static String pathExtension(String path) {
        String p = stripQuery(path);
        String last = p.substring(p.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        return dot < 0 ? "" : last.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static Set<String> parseStaticExtensions(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : nz(text).split("[,\\s;]+")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            while (p.startsWith(".")) {
                p = p.substring(1);
            }
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    public static boolean isStaticPath(String path, Set<String> exts) {
        String ext = pathExtension(path);
        return !ext.isEmpty() && exts.contains(ext);
    }

    public static boolean isIdSegment(String s) {
        return NUM.matcher(s).matches() || UUID.matcher(s).matches() || HEX.matcher(s).matches();
    }

    /** Numbers, UUIDs and long hex strings in a path become {id}. */
    public static String normalizePathIds(String path) {
        String[] parts = stripQuery(path).split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(!parts[i].isEmpty() && isIdSegment(parts[i]) ? "{id}" : parts[i]);
        }
        return sb.toString();
    }

    /** Identity of an endpoint for de-duplication and for the placement book. */
    public static String dedupKey(String method, String protocol, String host, int port, String path,
                                  boolean normalizeIds) {
        String p = normalizeIds ? normalizePathIds(path) : stripQuery(path);
        return nz(method).toUpperCase(Locale.ROOT) + "|" + nz(protocol).toLowerCase(Locale.ROOT) + "://"
                + nz(host).toLowerCase(Locale.ROOT) + ":" + port + "|" + p;
    }

    public static String shortPath(String path, int maxLen) {
        String p = stripQuery(path);
        if (maxLen > 0 && p.length() > maxLen) {
            p = "..." + p.substring(p.length() - Math.max(maxLen - 3, 1));
        }
        return p;
    }

    /** Method and path from the first request line, without parsing the whole message. */
    public static String[] requestLine(byte[] req) {
        if (req == null) {
            return null;
        }
        int limit = Math.min(req.length, 2048);
        int end = 0;
        while (end < limit && req[end] != '\r' && req[end] != '\n') {
            end++;
        }
        String line = new String(req, 0, end, StandardCharsets.ISO_8859_1);
        String[] parts = line.split(" ");
        if (parts.length < 2) {
            return null;
        }
        String method = parts[0].trim().toUpperCase(Locale.ROOT);
        String target = parts[1].trim();
        String path;
        if (target.startsWith("http://") || target.startsWith("https://")) {
            int idx = target.indexOf('/', target.indexOf("://") + 3);
            path = idx >= 0 ? target.substring(idx) : "/";
        } else {
            path = target.isEmpty() ? "/" : target;
        }
        return new String[] {method, path};
    }

    // ------------------------------------------------------------------ labels

    public static String groupId(int gid) {
        return String.format("G%02d", gid);
    }

    public static String apiLabel(int gid) {
        return "API " + gid;
    }

    public static String featureLabel(int fid, String name) {
        String clean = nz(name).trim();
        if (clean.isEmpty() || clean.equals(String.valueOf(fid))) {
            return "Tên " + fid;
        }
        return "Tên " + fid + ": " + clean;
    }

    public static String displayRoleName(String roleName, int index, boolean numberRoles) {
        String name = blank(roleName) ? "role" + (index + 1) : roleName;
        if (numberRoles && !ROLE_NUM_PREFIX.matcher(name).matches()) {
            return String.format("%02d_%s", index + 1, name);
        }
        return name;
    }

    public static String repeaterTabName(int gid, String method, String path, String role, int maxLen,
                                         Integer fid, boolean includeFeature) {
        String tag = groupId(gid);
        if (includeFeature && fid != null) {
            tag = String.format("F%02d-%s", fid, tag);
        }
        return "[" + tag + ":" + nz(method).toUpperCase(Locale.ROOT) + " " + shortPath(path, maxLen) + "] " + nz(role);
    }

    public static String compactRepeaterTabName(int gid, String role, Integer fid, boolean includeFeature) {
        String tag = groupId(gid);
        if (includeFeature && fid != null) {
            tag = String.format("F%02d•%s", fid, tag);
        }
        return tag + "•" + nz(role);
    }

    // ------------------------------------------------------------------ text forms

    /** Lower-case, accent-free form used to recognise column headers ('Vị trí' -> 'vi tri'). */
    public static String fold(Object text) {
        String t = nz(text).trim().toLowerCase(Locale.ROOT).replace('đ', 'd');
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return String.join(" ", t.replace('_', ' ').trim().split("\\s+"));
    }

    /** Decode %XX escapes of ASCII characters except %2F and control characters. */
    public static String pctDecode(String text) {
        Matcher m = PCT.matcher(nz(text));
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int code = Integer.parseInt(m.group(1), 16);
            String rep;
            if (code == 0x2F || code < 0x20) {
                rep = "%" + m.group(1).toUpperCase(Locale.ROOT);
            } else if (code < 0x80) {
                rep = String.valueOf((char) code);
            } else {
                rep = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static boolean asBool(Object v, boolean def) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("on") || s.equals("y");
    }

    /** Hard-wrap each paragraph to `width` columns; bullet lines get a hanging indent. */
    public static String wrap(String text, int width) {
        StringBuilder out = new StringBuilder();
        String[] lines = nz(text).split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            if (li > 0) {
                out.append('\n');
            }
            if (line.isBlank()) {
                continue;
            }
            String indent = line.startsWith("• ") || line.startsWith("   ") ? "   " : "";
            int col = 0;
            boolean first = true;
            for (String word : line.trim().split(" +")) {
                if (!first && col + 1 + word.length() > width) {
                    out.append('\n').append(indent);
                    col = indent.length();
                } else if (!first) {
                    out.append(' ');
                    col++;
                } else if (line.startsWith("   ")) {
                    out.append("   ");
                    col = 3;
                }
                out.append(word);
                col += word.length();
                first = false;
            }
        }
        return out.toString();
    }
}
