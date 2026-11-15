package themythofcrawl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.Config;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.store.FileStorage;
import themythofcrawl.store.Workspaces;

class WorkspaceTest {
    @Test
    void savesAndReopensFromDisk(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        prefs.set(Workspaces.PREF_ROOT, dir.toString());
        Workspaces ws = new Workspaces(prefs, null, null);
        FileStorage st = ws.start("p1", "Dự án: test/1");
        assertTrue(st.dir().startsWith(dir.resolve(Workspaces.FOLDER)));
        assertEquals("Default", st.name());
        EngineTest.FakeBridge bridge = new EngineTest.FakeBridge();
        Engine e = new Engine(bridge, prefs, null);
        Config c = e.config().copy();
        c.captureEnabled = true;
        e.applyConfig(c);
        e.open(st);
        ws.remember("p1", st);
        e.setDestination("Toppage > menu");
        ApiGroup a = e.process(EngineTest.msg("POST", "/api/a"), "Proxy", true, e.activeBranch().id());
        ApiGroup b = e.process(EngineTest.msg("GET", "/api/b"), "Proxy", true, e.activeBranch().id());
        e.confirm(List.of(a, b), "");
        e.remove(List.of(b), "");
        e.close();
        assertTrue(Files.isRegularFile(st.dir().resolve("apis/1/orig.req")));
        assertTrue(!Files.exists(st.dir().resolve("apis/2")));

        Engine e2 = new Engine(bridge, prefs, null);
        Workspaces ws2 = new Workspaces(prefs, null, null);
        FileStorage again = ws2.start("p1", "x");
        assertEquals(st.dir(), again.dir());
        e2.open(again);
        assertEquals(1, e2.groups().size());
        ApiGroup back = e2.groups().get(0);
        assertEquals("Toppage > menu", back.branch());
        assertNotNull(e2.requestOf(back, back.origResult()));
        assertEquals("Toppage > menu", e2.book().get(back.bookKey()).place);
        assertEquals("Toppage > menu", e2.book().get(e2.placeKey("GET", EngineTest.SVC, "/api/b")).place);   // giữ chỗ
        assertEquals(1, ws2.list().size());
        FileStorage other = ws2.create("Dự án: test/1", "Second");
        assertEquals(2, ws2.list().size());
        ws2.delete(other.dir());
        assertEquals(1, ws2.list().size());
        e2.close();
    }

    @Test
    void defaultRootIsTheProjectOfTheJar() {
        assertEquals(Path.of("/repo"), Workspaces.defaultRoot("/repo/build/libs/theMythOfCrawl.jar"));
        assertEquals(Path.of("/downloads"), Workspaces.defaultRoot("/downloads/theMythOfCrawl.jar"));
        assertEquals("a_b_c", Workspaces.safe("a:b/c"));
    }
}
