package themythofcrawl.excel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import themythofcrawl.model.ApiGroup;

/**
 * For Excel rows nobody captured: the closest captured API (one segment differs, e.g. /v1 vs /v2 or a
 * typo, or the API has an extra base path). Indexed by segment count so it stays cheap.
 */
public final class NearMissIndex {
    public record Suggestion(ApiGroup group, String note) {
    }

    private record Item(ApiGroup g, List<String> segs) {
    }

    private final Map<Integer, List<Item>> byLen = new HashMap<>();
    private final Map<String, List<Item>> byLast = new HashMap<>();

    public NearMissIndex(Collection<ApiGroup> groups) {
        for (ApiGroup g : groups) {
            List<String> segs = new ArrayList<>();
            for (String s : PathMatch.segments(g.path())) {
                segs.add(s.toLowerCase(Locale.ROOT));
            }
            Item it = new Item(g, segs);
            byLen.computeIfAbsent(segs.size(), k -> new ArrayList<>()).add(it);
            if (!segs.isEmpty()) {
                byLast.computeIfAbsent(segs.get(segs.size() - 1), k -> new ArrayList<>()).add(it);
            }
        }
    }

    public Suggestion suggest(TargetEntry e) {
        List<String> want = e.segments();
        int n = want.size();
        Suggestion best = null;
        for (Item it : byLen.getOrDefault(n, List.of())) {
            if (!e.acceptsMethod(it.g().method())) {
                continue;
            }
            String[] diff = null;
            int diffs = 0;
            for (int i = 0; i < n && diffs < 2; i++) {
                String a = want.get(i);
                if (PathMatch.segment(a, true).literal() && !a.toLowerCase(Locale.ROOT).equals(it.segs().get(i))) {
                    diffs++;
                    diff = new String[] {a, it.segs().get(i)};
                }
            }
            if (diffs == 1 && (best == null || it.g().gid() < best.group().gid())) {
                best = new Suggestion(it.g(), "khác 1 đoạn: " + diff[0] + " → " + diff[1]);
            }
        }
        if (best != null || want.isEmpty()) {
            return best;
        }
        PathMatch.Template t = e.patterns(true);
        for (Item it : byLast.getOrDefault(want.get(n - 1).toLowerCase(Locale.ROOT), List.of())) {
            if (it.segs().size() > n && e.acceptsMethod(it.g().method())
                    && t.prefixed().matcher("/" + String.join("/", it.segs())).matches()) {
                String extra = "/" + String.join("/", it.segs().subList(0, it.segs().size() - n));
                return new Suggestion(it.g(), "có thêm tiền tố " + extra + " (bật 'cho phép tiền tố')");
            }
        }
        return null;
    }
}
