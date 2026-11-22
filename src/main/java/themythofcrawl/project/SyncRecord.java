package themythofcrawl.project;

import java.util.LinkedHashMap;
import java.util.Map;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/**
 * One unit of team data, the same on every machine, in the team sheet ("_data") and in every backup:
 * <ul>
 * <li>{@code row:<rowId>}: an API of the project (method, path, branch) found by some account</li>
 * <li>{@code cell:<rowId>:<letter>:<member>}: what one member's crawl of one account found (auto)</li>
 * <li>{@code mark:<rowId>:<letter>}: a manual status / note (JCrawl, the sheet, or a client round) - wins over auto</li>
 * <li>{@code note:<rowId>}: the row's note</li>
 * <li>{@code qa:<qaId>}: a Q&A / Info item; {@code qans:<qaId>}: an answer written in the sheet / client file</li>
 * <li>{@code project}: the shared project settings (PIC / PIC phụ); {@code presence:<member>}: who is testing what</li>
 * <li>{@code conflict:...}: two people wrote different values; the PIC picks one</li>
 * </ul>
 *
 * @param at server time of the last write (local time until pushed); later wins
 * @param base the {@code at} the writer had seen (conflict detection)
 */
public record SyncRecord(String id, String type, String owner, String account, Map<String, Object> data, long at, String by,
                         boolean deleted, long base) {

    public static final String ROW = "row";
    public static final String CELL = "cell";
    public static final String MARK = "mark";
    public static final String NOTE = "note";
    public static final String QA = "qa";
    public static final String QANS = "qans";
    public static final String PROJECT = "project";
    public static final String PRESENCE = "presence";
    public static final String CONFLICT = "conflict";
    /** V_11.5: one API of a teammate's workspace (shared only when the PIC turns on "Chia sẻ chi tiết"). */
    public static final String API = "api";
    /** V_11.5: a screenshot a client pasted in a report, for the Q&A owner to attach. */
    public static final String QAIMG = "qaimg";
    /** V_11.5: one web account of the shared list ("acct:<letter>"): name, other names, merged into, fingerprints. */
    public static final String ACCT = "acct";

    public SyncRecord {
        id = Text.nz(id);
        type = Text.blank(type) ? typeOf(id) : type;
        owner = Text.nz(owner);
        account = Text.nz(account);
        data = data == null ? Map.of() : Map.copyOf(clean(data));
        by = Text.nz(by);
    }

    private static Map<String, Object> clean(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k, v);
            }
        });
        return out;
    }

    public static String typeOf(String id) {
        int i = Text.nz(id).indexOf(':');
        return i < 0 ? Text.nz(id) : id.substring(0, i);
    }

    public static SyncRecord of(String id, String owner, String account, Map<String, Object> data, String by) {
        return new SyncRecord(id, typeOf(id), owner, account, data, System.currentTimeMillis(), by, false, 0L);
    }

    public SyncRecord stamped(long serverAt) {
        return new SyncRecord(id, type, owner, account, data, serverAt, by, deleted, base);
    }

    public SyncRecord withBase(long b) {
        return new SyncRecord(id, type, owner, account, data, at, by, deleted, b);
    }

    public String str(String key) {
        Object v = data.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    public long num(String key) {
        Object v = data.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? 0 : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Same content (ignores time / base): nothing to push. */
    public boolean sameContent(SyncRecord o) {
        return o != null && id.equals(o.id) && deleted == o.deleted && Json.write(new java.util.TreeMap<>(data))
                .equals(Json.write(new java.util.TreeMap<>(o.data))) && owner.equals(o.owner) && account.equals(o.account);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("owner", owner);
        m.put("account", account);
        m.put("data", new LinkedHashMap<>(data));
        m.put("at", at);
        m.put("by", by);
        if (deleted) {
            m.put("deleted", true);
        }
        if (base > 0) {
            m.put("base", base);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    public static SyncRecord fromMap(Map<String, Object> m) {
        Object d = m.get("data");
        Map<String, Object> data;
        if (d instanceof Map<?, ?> dm) {
            data = (Map<String, Object>) dm;
        } else if (d instanceof String ds && !ds.isBlank()) {
            try {
                data = Json.parseObject(ds);
            } catch (RuntimeException e) {
                data = Map.of();
            }
        } else {
            data = Map.of();
        }
        Object del = m.get("deleted");
        boolean deleted = Boolean.TRUE.equals(del) || "true".equalsIgnoreCase(String.valueOf(del)) || "1".equals(String.valueOf(del));
        return new SyncRecord(Json.str(m, "id", ""), Json.str(m, "type", null), Json.str(m, "owner", ""), Json.str(m, "account", ""),
                data, Json.num(m, "at", 0), Json.str(m, "by", ""), deleted, Json.num(m, "base", 0));
    }
}
