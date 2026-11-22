package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.RoleResult;
import themythofcrawl.store.FileStorage;
import themythofcrawl.store.Workspaces;

/** V_9.3.1: edit the crawl account's request (add / remove / change), keep the captured one, restore it. */
class V931EditOriginalTest {
    @Test
    void editOriginalKeepsCapturedAndRestores(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine e = V70MultiRoleTeamTest.engine(new V70MultiRoleTeamTest.Server(), "A");
        FileStorage st = ws.start("p", "Project");
        e.open(st);
        e.roles().setAll(V70MultiRoleTeamTest.roles());
        ApiGroup g = e.process(V70MultiRoleTeamTest.msg("GET", "/api/orders/7", "ADMIN_TOKEN"), "Proxy", true,
                e.activeBranch().id());
        String captured = new String(e.requestOf(g, g.origResult()), StandardCharsets.ISO_8859_1);
        assertFalse(g.originalEdited());

        // edit: new path, an added header, the original header line removed
        String edited = "POST /api/orders/8?x=1 HTTP/1.1\r\nHost: api.example.test\r\nX-Added: yes\r\n\r\n{\"a\":1}";
        assertTrue(e.updateApiRequestFromComposer(g, g.origResult(), g.service(), edited.getBytes(StandardCharsets.ISO_8859_1), null));
        assertTrue(g.originalEdited(), "the captured request is kept on the first edit");
        assertEquals("POST", g.method());
        assertEquals("/api/orders/8?x=1", g.path());
        RoleResult role = g.results().get(0);
        String roleReq = new String(e.requestOf(g, role), StandardCharsets.ISO_8859_1);
        assertTrue(roleReq.startsWith("POST /api/orders/8?x=1") && roleReq.contains("X-Added: yes"),
                "roles are built from the edited request: " + roleReq);

        // a second edit keeps the FIRST capture
        e.updateApiRequestFromComposer(g, g.origResult(), g.service(),
                "PUT /api/orders/9 HTTP/1.1\r\nHost: api.example.test\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), null);
        e.flush();

        // survives reopening the workspace
        Engine e2 = V70MultiRoleTeamTest.engine(new V70MultiRoleTeamTest.Server(), "A");
        e2.open(ws.open(st.dir()));
        ApiGroup g2 = e2.group(g.gid());
        assertTrue(g2.originalEdited());
        assertTrue(e2.restoreCapturedRequest(g2));
        assertFalse(g2.originalEdited());
        assertEquals("GET", g2.method());
        assertEquals("/api/orders/7", g2.path());
        assertEquals(captured, new String(e2.requestOf(g2, g2.origResult()), StandardCharsets.ISO_8859_1));
        // the crawl account edited like a role: header rules applied to APIs already captured
        e2.saveOriginalProfile(new themythofcrawl.model.Role(themythofcrawl.model.Role.ORIGINAL_PROFILE_NAME, true,
                java.util.List.of(themythofcrawl.model.HeaderRule.of("X-Crawl", "1", "REPLACE"))));
        assertEquals(e2.groups().size(), e2.applyOriginalProfileToAll());
        assertTrue(new String(e2.requestOf(g2, g2.origResult()), StandardCharsets.ISO_8859_1).contains("X-Crawl: 1"));
        e.close();
        e2.close();
    }
}
