package themythofcrawl.excel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import themythofcrawl.core.Text;

/** One row of the user's Excel API list. */
public final class TargetEntry {
    private final Object stt;
    private final List<String> methods;
    private final String path;
    private final String position;
    private final int rowNo;
    private final String host;
    private final List<String[]> query;
    private final String norm;
    private final List<String> segments;
    private final PathMatch.Template plain;
    private final PathMatch.Template ids;

    public TargetEntry(Object stt, List<String> methods, String path, String position, int rowNo) {
        this.stt = stt instanceof Double d && d == Math.rint(d) ? (Object) d.longValue() : stt;
        this.methods = List.copyOf(methods);
        this.path = Text.nz(path);
        this.position = Text.nz(position).trim();
        this.rowNo = rowNo;
        PathMatch.HostQuery hq = PathMatch.hostAndQuery(this.path);
        this.host = hq.host();
        this.query = hq.query();
        this.norm = PathMatch.normalize(this.path);
        this.segments = PathMatch.segments(this.path);
        this.plain = PathMatch.template(this.path, false);
        this.ids = PathMatch.template(this.path, true);
    }

    public Object stt() { return stt; }
    public String sttText() { return stt == null ? "" : String.valueOf(stt); }
    public List<String> methods() { return methods; }
    public String path() { return path; }
    public String position() { return position; }
    public int rowNo() { return rowNo; }
    public String host() { return host; }
    public List<String[]> query() { return query; }
    public String norm() { return norm; }
    public List<String> segments() { return segments; }

    public PathMatch.Template patterns(boolean idsAsParams) {
        return idsAsParams ? ids : plain;
    }

    public String methodLabel() {
        return String.join(", ", methods);
    }

    public boolean acceptsMethod(String method) {
        return methods.contains(PathMatch.ANY_METHOD) || methods.contains(Text.nz(method).toUpperCase(java.util.Locale.ROOT));
    }

    /** With query mode 'names' / 'values': the request carries the row's query parameters. */
    public boolean queryOk(Map<String, List<String>> params, String mode) {
        for (String[] q : query) {
            List<String> got = params.get(q[0]);
            if (got == null) {
                return false;
            }
            if ("names".equals(mode) || q[1] == null || PathMatch.hasPlaceholder(q[1])) {
                continue;
            }
            if (!got.contains(q[1])) {
                return false;
            }
        }
        return true;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stt", stt);
        m.put("methods", methods);
        m.put("path", path);
        m.put("position", position);
        m.put("row", rowNo);
        return m;
    }
}
