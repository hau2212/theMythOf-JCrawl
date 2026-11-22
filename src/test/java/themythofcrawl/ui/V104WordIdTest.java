package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import themythofcrawl.app.Engine;
import themythofcrawl.excel.PathMatch;
import themythofcrawl.excel.PlaceholderCatalog;
import themythofcrawl.excel.TargetList;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.app.MemoryStorage;

/** V_10.4: IDs can be words. /api/a/b/new and /api/a/b/old on an Excel row /api/a/b/{ID} are ONE API. */
class V104WordIdTest {
    static boolean matches(String tpl, String path) {
        return PathMatch.template(tpl, false, new PlaceholderCatalog()).exact().matcher(PathMatch.normalize(path)).matches();
    }

    @Test
    void placeholderMatchesWordsAndCanonicalIsShared() {
        assertTrue(matches("/api/a/b/{ID}", "/api/a/b/new"));
        assertTrue(matches("/api/a/b/{ID}", "/api/a/b/old"));
        assertTrue(matches("/api/a/b/{id}", "/api/a/b/15"));
        assertEquals(PathMatch.canonical("/api/a/b/{ID}", "/api/a/b/new", false),
                PathMatch.canonical("/api/a/b/{ID}", "/api/a/b/old?x=1", false));
        assertNull(PathMatch.canonical("/api/a/b/c", "/api/a/b/c", false), "no placeholder: no canonical form");
    }

    @Test
    void testBuildCatalogGoesBackToAnyValue() {
        PlaceholderCatalog c = new PlaceholderCatalog();
        assertEquals(PlaceholderCatalog.MatchKind.ANY_SEGMENT, c.byName("id").kind());
        c.loadJson("{\"version\":2,\"placeholders\":[{\"name\":\"id\",\"kind\":\"ID_LIKE\",\"builtIn\":true}]}");
        assertEquals(PlaceholderCatalog.MatchKind.ANY_SEGMENT, c.byName("id").kind());
        c.upsert("id", PlaceholderCatalog.MatchKind.ID_LIKE, "", "user choice");
        PlaceholderCatalog c2 = new PlaceholderCatalog();
        c2.loadJson(c.toJson());
        assertEquals(PlaceholderCatalog.MatchKind.ID_LIKE, c2.byName("id").kind(), "a choice saved by the user is kept");
    }

    private static TargetList excel(String... paths) {
        List<List<Object>> rows = new java.util.ArrayList<>();
        rows.add(List.of("STT", "PROTOCOL", "PATH"));
        int i = 1;
        for (String p : paths) {
            rows.add(List.of(String.valueOf(i++), "GET", p));
        }
        return TargetList.fromRows(rows, "t.xlsx", "S");
    }

    @Test
    void excelRowMakesWordIdsOneApi() {
        Engine e = V70MultiRoleTeamTest.engine(new V70MultiRoleTeamTest.Server(), "A");
        e.open(new MemoryStorage());
        e.setTargets(excel("/api/a/b/{ID}", "/api/a/b/export"));
        ApiGroup n = e.process(V70MultiRoleTeamTest.msg("GET", "/api/a/b/new", "T"), "Proxy", true, e.activeBranch().id());
        assertNotNull(n);
        ApiGroup old = e.process(V70MultiRoleTeamTest.msg("GET", "/api/a/b/old", "T"), "Proxy", true, e.activeBranch().id());
        assertNull(old, "same API (same Excel row with {ID}): not a new API");
        assertTrue(e.classifyHeld(new themythofcrawl.core.ProxyInterceptQueue().enqueue(
                n.service(), "GET", "/api/a/b/zzz", null, "", "", null, new byte[0])).startsWith("Trùng"));
        ApiGroup exp = e.process(V70MultiRoleTeamTest.msg("GET", "/api/a/b/export", "T"), "Proxy", true, e.activeBranch().id());
        assertNotNull(exp, "a literal Excel row stays its own API");
        e.close();
    }

    @Test
    void capturedBeforeExcelAreMergedAndUserPatternWorks() {
        Engine e = V70MultiRoleTeamTest.engine(new V70MultiRoleTeamTest.Server(), "A");
        e.open(new MemoryStorage());
        ApiGroup a = e.process(V70MultiRoleTeamTest.msg("GET", "/api/a/b/new", "T"), "Proxy", true, e.activeBranch().id());
        ApiGroup b = e.process(V70MultiRoleTeamTest.msg("GET", "/api/a/b/old", "T"), "Proxy", true, e.activeBranch().id());
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(e.isOrigin(b), "without Excel or pattern: two APIs");
        e.setTargets(excel("/api/a/b/{ID}"));
        assertTrue(e.isOrigin(a));
        assertFalse(e.isOrigin(b), "after the Excel list: old is a capture of the same API");
        assertEquals(2, b.dupNo());

        ApiGroup x = e.process(V70MultiRoleTeamTest.msg("GET", "/api/u/alice/orders", "T"), "Proxy", true, e.activeBranch().id());
        ApiGroup y = e.process(V70MultiRoleTeamTest.msg("GET", "/api/u/bob/orders", "T"), "Proxy", true, e.activeBranch().id());
        assertTrue(e.isOrigin(y));
        assertEquals(1, e.addPathPattern("GET", "/api/u/{id}/orders"));
        assertFalse(e.isOrigin(y));
        assertNull(e.process(V70MultiRoleTeamTest.msg("GET", "/api/u/carol/orders", "T"), "Proxy", true, e.activeBranch().id()));
        e.removePathPattern("GET /api/u/{id}/orders");
        assertTrue(e.isOrigin(y), "removing the pattern splits them again");
        assertNotNull(x);
        e.close();
    }
}
