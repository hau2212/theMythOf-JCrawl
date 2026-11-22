package themythofcrawl;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import themythofcrawl.excel.PathMatch;
import themythofcrawl.excel.PlaceholderCatalog;

/** V_10.4: {id} only matches values that look like an ID; plain words stay literal endpoints. */
class V104IdLikeMatchTest {
    static boolean matches(String tpl, String path) {
        return PathMatch.template(tpl, false, new PlaceholderCatalog()).exact().matcher(PathMatch.normalize(path)).matches();
    }

    @Test
    void idPlaceholderSkipsWords() {
        assertTrue(matches("/api/a/b/{id}", "/api/a/b/15"));
        assertTrue(matches("/api/a/b/{id}", "/api/a/b/550e8400-e29b-41d4-a716-446655440000"));
        assertTrue(matches("/api/a/b/{id}", "/api/a/b/ORD-2024-001"));
        assertTrue(matches("/api/a/b/{id}", "/api/a/b/65f1c2aa9b1e4c0012ab34cd"));
        assertFalse(matches("/api/a/b/{id}", "/api/a/b/old"), "plain word is a different endpoint");
        assertFalse(matches("/api/a/b/{id}", "/api/a/b/new"));
        assertFalse(matches("/api/a/b/{id}", "/api/a/b/export"));
        // named ids follow the same rule; other names still match anything
        assertTrue(matches("/api/orders/{orderId}/items", "/api/orders/77/items"));
        assertFalse(matches("/api/orders/{orderId}/items", "/api/orders/mine/items"));
        assertTrue(matches("/api/users/:user_id", "/api/users/42"));
        assertFalse(matches("/api/users/:user_id", "/api/users/me"));
        assertTrue(matches("/api/a/{session}/c", "/api/a/rand/c"));
        // a literal row still matches its literal path
        assertTrue(matches("/api/a/b/new", "/api/a/b/new"));
    }

    @Test
    void oldSavedCatalogUpgrades() {
        PlaceholderCatalog c = new PlaceholderCatalog();
        c.loadJson("{\"version\":1,\"placeholders\":[{\"name\":\"id\",\"kind\":\"ANY_SEGMENT\",\"builtIn\":true}]}");
        assertEquals(PlaceholderCatalog.MatchKind.ID_LIKE, c.byName("id").kind());
        c.upsert("id", PlaceholderCatalog.MatchKind.ANY_SEGMENT, "", "user choice");
        PlaceholderCatalog c2 = new PlaceholderCatalog();
        c2.loadJson(c.toJson());
        assertEquals(PlaceholderCatalog.MatchKind.ANY_SEGMENT, c2.byName("id").kind(), "a choice saved by the user is kept");
    }
}
