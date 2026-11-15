package themythofcrawl.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader/writer: objects -> LinkedHashMap, arrays -> ArrayList, numbers -> Long/Double. */
public final class Json {
    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        Json p = new Json(text == null ? "" : text);
        p.skipWs();
        Object value = p.value();
        p.skipWs();
        if (p.pos != p.src.length()) {
            throw p.error("Unexpected trailing data");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) v;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException(msg + " at offset " + pos);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw error("Unexpected end of JSON");
        }
        return src.charAt(pos);
    }

    private Object value() {
        char c = peek();
        if (c == '{') {
            return object();
        }
        if (c == '[') {
            return array();
        }
        if (c == '"') {
            return string();
        }
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw error("Expected object key");
            }
            String key = string();
            skipWs();
            if (peek() != ':') {
                throw error("Expected ':'");
            }
            pos++;
            skipWs();
            map.put(key, value());
            skipWs();
            char c = peek();
            pos++;
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("Expected ',' or '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++;
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(value());
            skipWs();
            char c = peek();
            pos++;
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("Expected ',' or ']'");
            }
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        pos++;
        while (true) {
            char c = peek();
            pos++;
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = peek();
            pos++;
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (pos + 4 > src.length()) {
                        throw error("Bad unicode escape");
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> sb.append(e);
            }
        }
    }

    private Object number() {
        int start = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        String num = src.substring(start, pos);
        if (num.isEmpty()) {
            throw error("Unexpected character '" + src.charAt(pos) + "'");
        }
        try {
            if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
                return Long.parseLong(num);
            }
            return Double.parseDouble(num);
        } catch (NumberFormatException ex) {
            throw error("Bad number '" + num + "'");
        }
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, -1, 0);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 2, 0);
        return sb.toString();
    }

    private static void newline(StringBuilder sb, int indent, int depth) {
        if (indent >= 0) {
            sb.append('\n');
            sb.append(" ".repeat(indent * depth));
        }
    }

    private static void write(StringBuilder sb, Object v, int indent, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, indent, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(indent >= 0 ? ": " : ":");
                write(sb, e.getValue(), indent, depth + 1);
            }
            newline(sb, indent, depth);
            sb.append('}');
        } else if (v instanceof Iterable<?> items) {
            sb.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, indent, depth + 1);
                write(sb, item, indent, depth + 1);
            }
            if (!first) {
                newline(sb, indent, depth);
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---- typed accessors for parsed maps
    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString();
    }

    public static long num(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        return v instanceof Boolean b ? b : def;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }
}
