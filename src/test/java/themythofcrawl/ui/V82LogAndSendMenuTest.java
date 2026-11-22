package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.ActivityLog;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.store.Workspaces;

/** V_8.2: "Gửi lại" submenu covers every scope; "Nhật ký cho Claude" has state, errors, timeline and no secrets. */
class V82LogAndSendMenuTest {

    @Test
    void sendSubmenuAndClaudeReport(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine e = V70MultiRoleTeamTest.engine(new V70MultiRoleTeamTest.Server(), "A");
        e.open(ws.start("p", "Project"));
        e.roles().setAll(V70MultiRoleTeamTest.roles());
        ApiGroup g = e.process(V70MultiRoleTeamTest.msg("DELETE", "/api/admin/users/7", "ADMIN_TOKEN"), "Proxy", true,
                e.activeBranch().id());
        assertNotNull(g);
        ActivityLog.clear();

        SwingUtilities.invokeAndWait(() -> {
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "log");
            try {
                JMenu m = SendMenus.build(ui, List.of(g), null);
                List<String> labels = new ArrayList<>();
                for (int i = 0; i < m.getItemCount(); i++) {
                    JMenuItem it = m.getItem(i);
                    if (it != null) {
                        labels.add(it.getText());
                    }
                }
                assertTrue(labels.stream().anyMatch(s -> s.startsWith("Tất cả")), labels.toString());
                assertTrue(labels.contains("Chỉ tài khoản crawl"), labels.toString());
                assertTrue(labels.stream().anyMatch(s -> s.startsWith("Chỉ vai trò")), labels.toString());
                assertTrue(labels.contains("Chọn vai trò..."), labels.toString());
                assertTrue(labels.stream().anyMatch(s -> s.startsWith("Mỗi vai trò 1 bản ghi")), "DELETE offers per-role objects: " + labels);
                // clicking a menu item is recorded
                m.getItem(2).doClick();
                assertTrue(ActivityLog.recent(20).stream().anyMatch(ev -> ev.kind() == ActivityLog.Kind.UI
                        && ev.text().contains("Chỉ tài khoản crawl")), ActivityLog.recent(20).toString());
            } finally {
                ui.dispose();
            }
        });
        ActivityLog.error("Lỗi thử Authorization: Bearer SECRETSECRET123", new RuntimeException("boom"));
        String report = ClaudeLogDialog.build(e, 500);
        assertTrue(report.contains("## Môi trường"));
        assertTrue(report.contains("## Trạng thái hiện tại"));
        assertTrue(report.contains("## Lỗi gần đây"));
        assertTrue(report.contains("## Dòng thời gian"));
        assertTrue(report.contains("RuntimeException: boom"));
        assertFalse(report.contains("SECRETSECRET123"), "secrets are masked");
        assertFalse(report.contains("ADMIN_TOKEN"), "role token values never appear");
        e.close();
    }
}
