package themythofcrawl.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every option of the extension, with defaults and the same clamping rules as before. Mutable copy. */
public final class Config {
    public static final String DEFAULT_STATIC_EXT = "js,mjs,css,map,png,jpg,jpeg,gif,bmp,ico,svg,webp,avif,tif,tiff,"
            + "woff,woff2,ttf,otf,eot,mp3,mp4,m4a,webm,wav,ogg,avi,mov,flv,swf";
    public static final String DEFAULT_API_REGEX = "(?i)(/api/|/v[0-9]+(/|$)|/graphql|/rest/|/rpc/|/services?/|\\.json$)";

    /** Outgoing pacing presets; CUSTOM keeps the user's numbers. */
    public enum SendProfile {
        HUMAN("Như người dùng thật - an toàn (khuyên dùng)", 700, 40, 1),
        BALANCED("Cân bằng", 250, 30, 2),
        FAST("Nhanh - dễ bị chặn 429", 60, 10, 4),
        CUSTOM("Tùy chỉnh", -1, -1, -1);

        public final String label;
        public final int delayMs;
        public final int jitterPct;
        public final int concurrency;

        SendProfile(String label, int delayMs, int jitterPct, int concurrency) {
            this.label = label;
            this.delayMs = delayMs;
            this.jitterPct = jitterPct;
            this.concurrency = concurrency;
        }
    }

    public enum CopyFallback {
        NONE("Không đánh số (chỉ copy path)"),
        COUNTER("Số tăng dần theo lần copy (1, 2, 3...) - dễ đếm"),
        API("Dùng số API # (thứ tự bắt)");

        public final String label;

        CopyFallback(String label) {
            this.label = label;
        }
    }

    public enum QueryModeOption {
        IGNORE("Bỏ qua query trong file (mặc định)"),
        NAMES("Request phải có đúng tên tham số query của dòng"),
        VALUES("Tên + giá trị query phải khớp (app định tuyến kiểu ?action=getUser)");

        public final String label;

        QueryModeOption(String label) {
            this.label = label;
        }
    }

    // capture
    public boolean captureEnabled = false;
    public boolean inScopeOnly = true;
    public boolean ignoreStatic = true;
    public String staticExt = DEFAULT_STATIC_EXT;
    public boolean apiRegexEnabled = true;
    public String apiRegex = DEFAULT_API_REGEX;
    public boolean ignoreOptionsHead = true;
    public boolean dedup = true;
    public boolean dedupNormalizeIds = false;
    public boolean dedupPerBranch = false;
    // placement
    public boolean confirmNewApis = true;
    public boolean restoreKnownPlace = true;
    public boolean dupNewPlace = true;
    public boolean showGhosts = true;
    // sending
    public boolean autoRepeater = false;
    public boolean autoSend = false;
    public boolean autoSendOriginal = false;
    public boolean numberRolesInTab = true;
    public boolean includeBranchInTab = false;
    public int threads = 5;
    public int shortPathLen = 40;
    public SendProfile sendProfile = SendProfile.HUMAN;
    public int sendDelayMs = 700;
    public int sendJitterPct = 40;
    public int hostConcurrency = 1;
    public int maxRetries = 3;
    // Excel list
    public boolean targetOnly = false;
    public boolean targetUsePosition = true;
    public boolean targetAllowPrefix = false;
    public boolean targetIdsAsParams = true;
    public QueryModeOption targetQueryMode = QueryModeOption.IGNORE;
    // copy STT + path
    public String copyFormat = "{stt}. {path}";
    public CopyFallback copySttFallback = CopyFallback.NONE;
    public int copyCounter = 1;
    public boolean excelPositionCopyFormat = true;

    public Config copy() {
        return fromMap(toMap());
    }

    /** Clamp numbers and apply the selected send profile. */
    public Config normalized() {
        threads = clamp(threads, 1, 64);
        if (sendProfile != SendProfile.CUSTOM) {
            sendDelayMs = sendProfile.delayMs;
            sendJitterPct = sendProfile.jitterPct;
            hostConcurrency = sendProfile.concurrency;
        }
        sendDelayMs = clamp(sendDelayMs, 0, 60000);
        sendJitterPct = clamp(sendJitterPct, 0, 100);
        hostConcurrency = clamp(hostConcurrency, 1, 16);
        maxRetries = clamp(maxRetries, 0, 10);
        copyCounter = Math.max(1, copyCounter);
        if (copyFormat == null || copyFormat.isBlank()) {
            copyFormat = "{stt}. {path}";
        }
        shortPathLen = clamp(shortPathLen, 8, 200);
        staticExt = Text.nz(staticExt);
        apiRegex = Text.nz(apiRegex);
        return this;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capture_enabled", captureEnabled);
        m.put("in_scope_only", inScopeOnly);
        m.put("ignore_static", ignoreStatic);
        m.put("static_ext", staticExt);
        m.put("api_regex_enabled", apiRegexEnabled);
        m.put("api_regex", apiRegex);
        m.put("ignore_options_head", ignoreOptionsHead);
        m.put("dedup", dedup);
        m.put("dedup_normalize_ids", dedupNormalizeIds);
        m.put("dedup_per_feature", dedupPerBranch);
        m.put("confirm_new_apis", confirmNewApis);
        m.put("restore_known_place", restoreKnownPlace);
        m.put("dup_new_place", dupNewPlace);
        m.put("show_ghosts", showGhosts);
        m.put("auto_repeater", autoRepeater);
        m.put("auto_send", autoSend);
        m.put("auto_send_original", autoSendOriginal);
        m.put("number_roles_in_tab", numberRolesInTab);
        m.put("include_feature_in_tab", includeBranchInTab);
        m.put("threads", threads);
        m.put("short_path_len", shortPathLen);
        m.put("send_profile", sendProfile.name());
        m.put("send_delay_ms", sendDelayMs);
        m.put("send_jitter_pct", sendJitterPct);
        m.put("host_concurrency", hostConcurrency);
        m.put("max_retries", maxRetries);
        m.put("target_only", targetOnly);
        m.put("target_use_position", targetUsePosition);
        m.put("target_allow_prefix", targetAllowPrefix);
        m.put("target_ids_as_params", targetIdsAsParams);
        m.put("target_query_mode", targetQueryMode.name().toLowerCase(java.util.Locale.ROOT));
        m.put("copy_format", copyFormat);
        m.put("copy_stt_fallback", copySttFallback.name().toLowerCase(java.util.Locale.ROOT));
        m.put("copy_counter", copyCounter);
        m.put("excel_position_copy_format", excelPositionCopyFormat);
        return m;
    }

    public static Config fromMap(Map<String, Object> m) {
        Config c = new Config();
        if (m == null) {
            return c.normalized();
        }
        c.captureEnabled = b(m, "capture_enabled", c.captureEnabled);
        c.inScopeOnly = b(m, "in_scope_only", c.inScopeOnly);
        c.ignoreStatic = b(m, "ignore_static", c.ignoreStatic);
        c.staticExt = s(m, "static_ext", c.staticExt);
        c.apiRegexEnabled = b(m, "api_regex_enabled", c.apiRegexEnabled);
        c.apiRegex = s(m, "api_regex", c.apiRegex);
        c.ignoreOptionsHead = b(m, "ignore_options_head", c.ignoreOptionsHead);
        c.dedup = b(m, "dedup", c.dedup);
        c.dedupNormalizeIds = b(m, "dedup_normalize_ids", c.dedupNormalizeIds);
        c.dedupPerBranch = b(m, "dedup_per_feature", c.dedupPerBranch);
        c.confirmNewApis = b(m, "confirm_new_apis", c.confirmNewApis);
        c.restoreKnownPlace = b(m, "restore_known_place", c.restoreKnownPlace);
        c.dupNewPlace = b(m, "dup_new_place", c.dupNewPlace);
        c.showGhosts = b(m, "show_ghosts", c.showGhosts);
        c.autoRepeater = b(m, "auto_repeater", c.autoRepeater);
        c.autoSend = b(m, "auto_send", c.autoSend);
        c.autoSendOriginal = b(m, "auto_send_original", c.autoSendOriginal);
        c.numberRolesInTab = b(m, "number_roles_in_tab", c.numberRolesInTab);
        c.includeBranchInTab = b(m, "include_feature_in_tab", c.includeBranchInTab);
        c.threads = i(m, "threads", c.threads);
        c.shortPathLen = i(m, "short_path_len", c.shortPathLen);
        c.sendProfile = e(SendProfile.class, m.get("send_profile"), c.sendProfile);
        c.sendDelayMs = i(m, "send_delay_ms", c.sendDelayMs);
        c.sendJitterPct = i(m, "send_jitter_pct", c.sendJitterPct);
        c.hostConcurrency = i(m, "host_concurrency", c.hostConcurrency);
        c.maxRetries = i(m, "max_retries", c.maxRetries);
        c.targetOnly = b(m, "target_only", c.targetOnly);
        c.targetUsePosition = b(m, "target_use_position", c.targetUsePosition);
        c.targetAllowPrefix = b(m, "target_allow_prefix", c.targetAllowPrefix);
        c.targetIdsAsParams = b(m, "target_ids_as_params", c.targetIdsAsParams);
        c.targetQueryMode = e(QueryModeOption.class, m.get("target_query_mode"), c.targetQueryMode);
        c.copyFormat = s(m, "copy_format", c.copyFormat);
        c.copySttFallback = e(CopyFallback.class, m.get("copy_stt_fallback"), c.copySttFallback);
        c.copyCounter = i(m, "copy_counter", c.copyCounter);
        c.excelPositionCopyFormat = b(m, "excel_position_copy_format", c.excelPositionCopyFormat);
        return c.normalized();
    }

    private static boolean b(Map<String, Object> m, String k, boolean def) {
        return m.containsKey(k) ? Text.asBool(m.get(k), def) : def;
    }

    private static String s(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    private static int i(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static <E extends Enum<E>> E e(Class<E> type, Object v, E def) {
        if (v == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, String.valueOf(v).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return def;
        }
    }
}
