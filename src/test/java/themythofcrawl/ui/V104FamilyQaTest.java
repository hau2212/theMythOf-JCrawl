package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ApiTagCatalog;
import themythofcrawl.model.QaItem;
import themythofcrawl.store.FamilyReport;
import themythofcrawl.store.FileStorage;
import themythofcrawl.store.Workspaces;

/** V_10.4: workspace per account (child keeps the tree), account matrix, Q&A with screenshots in Excel. */
class V104FamilyQaTest {
    static byte[] png() throws Exception {
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(300, 200, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bi, "png", bo);
        return bo.toByteArray();
    }

    @Test
    void childWorkspaceMatrixAndQaWorkbook(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        // account 1 (highest): admin
        Engine e = V70MultiRoleTeamTest.engine(new V70MultiRoleTeamTest.Server(), "A");
        FileStorage admin = ws.start("p", "Project");
        e.open(admin);
        e.setDestination("Admin > Users");
        ApiGroup users = e.process(V70MultiRoleTeamTest.msg("GET", "/api/users/15", "T"), "Proxy", true, e.activeBranch().id());
        ApiGroup stats = e.process(V70MultiRoleTeamTest.msg("GET", "/api/admin/stats", "T"), "Proxy", true, e.activeBranch().id());
        String img = e.addQaImage(users, png());
        assertNotNull(img);
        e.putQa(users, QaItem.create(QaItem.Kind.QA, "Vì sao user thường xem được user khác?", List.of(img, img), "admin", "A"));
        assertTrue(users.hasTag(ApiTagCatalog.TAG_QA));
        e.flush();

        // account 2: a child workspace keeps the branch tree, no API
        String template = e.templateStateJson();
        FileStorage child = ws.createChild(admin.dir(), "user", template);
        Engine e2 = V70MultiRoleTeamTest.engine(new V70MultiRoleTeamTest.Server(), "A");
        e2.open(child);
        assertTrue(e2.groups().isEmpty(), "the next account starts with no API");
        assertTrue(e2.branches().stream().anyMatch(b -> b.name().equals("Admin > Users")), "but with the same branch tree");
        e2.setDestination("Admin > Users");
        e2.process(V70MultiRoleTeamTest.msg("GET", "/api/users/99", "U"), "Proxy", true, e2.activeBranch().id());
        e2.process(V70MultiRoleTeamTest.msg("GET", "/api/profile", "U"), "Proxy", true, e2.activeBranch().id());
        e2.flush();

        List<Workspaces.Info> fam = Workspaces.family(child.dir());
        assertEquals(2, fam.size());
        FamilyReport rep = FamilyReport.load(child.dir());
        assertEquals(List.of("Default", "user"), rep.members().stream().map(FamilyReport.Member::account).toList());
        FamilyReport.Row usersRow = rep.rows().stream().filter(r -> r.path().startsWith("/api/users")).findFirst().orElseThrow();
        assertEquals(2, usersRow.reached(), "/api/users/{id} reached by both accounts (IDs normalised)");
        FamilyReport.Row statsRow = rep.rows().stream().filter(r -> r.path().equals("/api/admin/stats")).findFirst().orElseThrow();
        assertEquals(1, statsRow.reached(), "admin-only API");

        byte[] xlsx = rep.workbook(true, List.of(QaItem.Kind.QA, QaItem.Kind.INFO), null);
        List<String> entries = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry en; (en = z.getNextEntry()) != null; ) {
                entries.add(en.getName());
            }
        }
        assertTrue(entries.contains("xl/drawings/drawing1.xml"), entries.toString());
        assertEquals(2, entries.stream().filter(n -> n.startsWith("xl/media/")).count(), "both screenshots embedded");
        assertTrue(entries.stream().filter(n -> n.startsWith("xl/worksheets/sheet")).count() >= 5,
                "Tổng quan + Ma trận + 2 accounts + Q&A: " + entries);
        e.close();
        e2.close();
    }
}
