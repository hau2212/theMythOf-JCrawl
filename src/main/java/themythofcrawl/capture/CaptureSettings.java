package themythofcrawl.capture;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import themythofcrawl.util.Json;

/** Capture filters of a workspace (saved inside workspace.json). Immutable; rebuild to change. */
public final class CaptureSettings {
    public static final String DEFAULT_STATIC = "js,mjs,css,map,png,jpg,jpeg,gif,bmp,ico,svg,webp,avif,tif,tiff,"
            + "woff,woff2,ttf,otf,eot,mp3,mp4,m4a,webm,wav,ogg,avi,mov,flv,swf";
    public static final String DEFAULT_REGEX = "(?i)(/api/|/v[0-9]+(/|$)|/graphql|/rest/|/rpc/|/services?/|\\.json$)";

    public final boolean captureEnabled;
    public final boolean inScopeOnly;
    public final boolean ignoreStatic;
    public final String staticExt;
    public final boolean regexEnabled;
    public final String regex;
    public final boolean ignoreOptionsHead;
    public final boolean dedup;
    public final boolean dedupNormalizeIds;
    public final boolean dedupPerBranch;
    public final boolean targetOnly;
    public final boolean targetUsePosition;
    public final boolean targetAllowPrefix;

    private final Set<String> staticSet;
    private final Pattern compiled;
    private final String regexError;

    public CaptureSettings(Map<String, Object> m) {
        captureEnabled = Json.bool(m, "captureEnabled", false);
        inScopeOnly = Json.bool(m, "inScopeOnly", true);
        ignoreStatic = Json.bool(m, "ignoreStatic", true);
        staticExt = Json.str(m, "staticExt", DEFAULT_STATIC);
        regexEnabled = Json.bool(m, "regexEnabled", true);
        regex = Json.str(m, "regex", DEFAULT_REGEX);
        ignoreOptionsHead = Json.bool(m, "ignoreOptionsHead", true);
        dedup = Json.bool(m, "dedup", true);
        dedupNormalizeIds = Json.bool(m, "dedupNormalizeIds", false);
        dedupPerBranch = Json.bool(m, "dedupPerBranch", false);
        targetOnly = Json.bool(m, "targetOnly", false);
        targetUsePosition = Json.bool(m, "targetUsePosition", true);
        targetAllowPrefix = Json.bool(m, "targetAllowPrefix", false);
        staticSet = new HashSet<>();
        for (String e : staticExt.split("[,\\s;]+")) {
            String t = e.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith(".")) {
                t = t.substring(1);
            }
            if (!t.isEmpty()) {
                staticSet.add(t);
            }
        }
        Pattern p = null;
        String err = null;
        if (!regex.isBlank()) {
            try {
                p = Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                err = e.getDescription();
            }
        }
        compiled = p;
        regexError = err;
    }

    public static CaptureSettings defaults() {
        return new CaptureSettings(new LinkedHashMap<>());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("captureEnabled", captureEnabled);
        m.put("inScopeOnly", inScopeOnly);
        m.put("ignoreStatic", ignoreStatic);
        m.put("staticExt", staticExt);
        m.put("regexEnabled", regexEnabled);
        m.put("regex", regex);
        m.put("ignoreOptionsHead", ignoreOptionsHead);
        m.put("dedup", dedup);
        m.put("dedupNormalizeIds", dedupNormalizeIds);
        m.put("dedupPerBranch", dedupPerBranch);
        m.put("targetOnly", targetOnly);
        m.put("targetUsePosition", targetUsePosition);
        m.put("targetAllowPrefix", targetAllowPrefix);
        return m;
    }

    public CaptureSettings with(String key, Object value) {
        Map<String, Object> m = toMap();
        m.put(key, value);
        return new CaptureSettings(m);
    }

    public String regexError() {
        return regexError;
    }

    public boolean isStatic(String path) {
        String p = stripQuery(path);
        String last = p.substring(p.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        return dot >= 0 && staticSet.contains(last.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public boolean regexAccepts(String path) {
        return !regexEnabled || compiled == null || compiled.matcher(stripQuery(path)).find();
    }

    public static String stripQuery(String path) {
        String p = path == null || path.isEmpty() ? "/" : path;
        int cut = p.length();
        int q = p.indexOf('?');
        int h = p.indexOf('#');
        if (q >= 0) {
            cut = q;
        }
        if (h >= 0) {
            cut = Math.min(cut, h);
        }
        String out = p.substring(0, cut);
        return out.isEmpty() ? "/" : out;
    }

    private static final Pattern NUM = Pattern.compile("^[0-9]+$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]{16,}$");

    /** Key of an endpoint for de-duplication: METHOD|scheme://host:port|path (ids optionally folded). */
    public String dedupKey(String method, boolean secure, String host, int port, String path, String branch) {
        String p = stripQuery(path);
        if (dedupNormalizeIds) {
            String[] segs = p.split("/", -1);
            for (int i = 0; i < segs.length; i++) {
                if (NUM.matcher(segs[i]).matches() || UUID.matcher(segs[i]).matches() || HEX.matcher(segs[i]).matches()) {
                    segs[i] = "{id}";
                }
            }
            p = String.join("/", segs);
        }
        String key = method.toUpperCase(Locale.ROOT) + "|" + (secure ? "https" : "http") + "://"
                + host.toLowerCase(Locale.ROOT) + ":" + port + "|" + p;
        return dedupPerBranch ? branch.toLowerCase(Locale.ROOT) + "|" + key : key;
    }
}
