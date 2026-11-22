package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.core.Config;
import themythofcrawl.model.ApiGroup;

/** V_10.4: deleting a duplicate capture is direct; deleting a bản gốc promotes the next capture. */
class V104TreeDeleteTest {
    @Test
    void deleteDuplicateThenOrigin() {
        Engine e = V70MultiRoleTeamTest.engine(new V70MultiRoleTeamTest.Server(), "A");
        e.open(new MemoryStorage());
        Config c = e.config().copy();
        c.dupNewPlace = true;
        e.applyConfig(c);
        e.setDestination("A");
        ApiGroup g1 = e.process(V70MultiRoleTeamTest.msg("GET", "/api/x", "T"), "Proxy", true, e.activeBranch().id());
        e.setDestination("B");
        ApiGroup g2 = e.process(V70MultiRoleTeamTest.msg("GET", "/api/x", "T"), "Proxy", true, e.activeBranch().id());
        e.setDestination("C");
        ApiGroup g3 = e.process(V70MultiRoleTeamTest.msg("GET", "/api/x", "T"), "Proxy", true, e.activeBranch().id());
        assertNotNull(g2);
        assertNotNull(g3);
        assertTrue(e.isOrigin(g1));
        assertFalse(e.isOrigin(g3));

        Engine.DeletePlan dup = e.deletePlan(List.of(g3));
        assertFalse(dup.needsConfirm(), "a duplicate capture is deleted without asking");
        e.removeSmart(List.of(g3), "");
        assertNull(e.group(g3.gid()));

        Engine.DeletePlan orig = e.deletePlan(List.of(g1));
        assertTrue(orig.needsConfirm());
        assertEquals(1, orig.originsWithCopies().size());
        e.removeSmart(List.of(g1), "");
        assertNull(e.group(g1.gid()));
        assertTrue(e.isOrigin(g2), "the next capture left becomes the bản gốc");
        assertEquals(1, g2.dupNo());

        Engine.DeletePlan last = e.deletePlan(List.of(g2));
        assertEquals(1, last.onlyCopies().size(), "no other capture left: asks before deleting");
        e.close();
    }
}
