package themythofcrawl.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.Config;
import themythofcrawl.excel.TargetList;
import themythofcrawl.store.Workspaces;

/** Rough timings on a big crawl: {@code java -cp ... themythofcrawl.ui.Bench [apis]}. */
public final class Bench {
    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        Path root = Files.createTempDirectory("tmoc-bench");
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), root.resolve("x.jar").toString());
        ws.setRoot(root);
        Engine e = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        e.open(ws.start("b", "Bench"));
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.confirmNewApis = false;
        e.applyConfig(c);
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("STT", "PROTOCOL", "PATH"));
        for (int i = 0; i < 2000; i++) {
            rows.add(List.of(String.valueOf(i + 1), "GET", "/api/v1/res" + i + "/{id}"));
        }
        e.setTargets(TargetList.fromRows(rows, "big.xlsx", "S"));

        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            if (i % 25 == 0) {
                e.setDestination("Toppage > menu" + (i / 500) + " > button \"b" + (i / 25) + "\"");
            }
            e.process(UiSmokeTest.msg("GET", "/api/v1/res" + i + "/" + i), "Proxy", true, e.activeBranch().id());
        }
        long capture = System.nanoTime() - t0;
        System.out.printf("capture %d APIs x %d roles: %d ms (%.3f ms / API)%n", n, e.roles().count(), capture / 1_000_000,
                capture / 1e6 / n);

        t0 = System.nanoTime();
        e.flush();
        System.out.printf("save workspace: %d ms%n", (System.nanoTime() - t0) / 1_000_000);

        MainPanel[] ui = new MainPanel[1];
        long[] t = new long[6];
        SwingUtilities.invokeAndWait(() -> {
            long a = System.nanoTime();
            ui[0] = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "b");
            t[0] = System.nanoTime() - a;
            a = System.nanoTime();
            ui[0].api.tree.rebuild(true);
            t[1] = System.nanoTime() - a;
            a = System.nanoTime();
            ui[0].api.reloadAll();
            t[2] = System.nanoTime() - a;
            a = System.nanoTime();
            ui[0].tick(true);
            t[3] = System.nanoTime() - a;
            a = System.nanoTime();
            themythofcrawl.export.Reports.history(e.branches(), e.groups());
            t[4] = System.nanoTime() - a;
        });
        System.out.printf("build UI: %d ms | tree rebuild: %d ms | API table reload: %d ms | tick: %d ms | history report: %d ms%n",
                t[0] / 1_000_000, t[1] / 1_000_000, t[2] / 1_000_000, t[3] / 1_000_000, t[4] / 1_000_000);

        e.close();
        t0 = System.nanoTime();
        Engine e2 = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        e2.open(ws.start("b", "Bench"));
        System.out.printf("reopen workspace (%d APIs): %d ms%n", e2.groups().size(), (System.nanoTime() - t0) / 1_000_000);
        long a = System.nanoTime();
        byte[] x = themythofcrawl.export.Reports.workbook(e2.groups(), e2.targets(), "{stt}. {path}", true, g -> e2.copyLabel(g, false),
                e2.hits(), e2.book());
        System.out.printf("Excel export: %d ms (%d KB)%n", (System.nanoTime() - a) / 1_000_000, x.length / 1024);
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
        System.out.printf("heap kept with %d APIs open (after GC): %d MB%n", e2.groups().size(),
                (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        e2.close();
        SwingUtilities.invokeAndWait(() -> ui[0].dispose());
        System.exit(0);
    }
}
