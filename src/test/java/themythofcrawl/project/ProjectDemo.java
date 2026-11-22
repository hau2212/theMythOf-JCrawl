package themythofcrawl.project;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dev helper: builds a demo project (A + B) and writes its workbook to args[0]. */
public final class ProjectDemo {
    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("jcrawl-demo");
        Path b = V110ProjectTest.family(tmp);
        ProjectStore st = ProjectStore.open(ProjectStore.projectDirOf(b));
        ProjectData d = ProjectData.build(st.config(), ProjectData.loadLocal(b), st.records(), "An");
        Files.write(Path.of(args[0]), ProjectWorkbook.xlsx(d, st.config().sheets, ProjectWorkbook.Options.internal(d)));
        System.out.println("ok " + args[0]);
    }
}
