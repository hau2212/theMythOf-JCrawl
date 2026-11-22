package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.TableCellEditor;
import javax.swing.text.JTextComponent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.app.Prefs;
import themythofcrawl.burp.Extension;
import themythofcrawl.core.Config;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.Role;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.kit.SegmentedToggle;
import themythofcrawl.ui.kit.StructuredAdviceCard;
import themythofcrawl.ui.theme.FontManager;
import themythofcrawl.ui.theme.Theme;

/**
 * Verification suite for V_2.3_beta:
 * 1) Permanent zero-HTML source guard across src/main/java/themythofcrawl/ui/ (Burp disables Swing HTML)
 * 2) Burp html.disable=true runtime verification across StructuredAdviceCard, ActionWorkbenchDialog &amp; LineageDialog
 * 3) Redesigned minimalist 2-cell [ BẬT | TẮT ] Roles switch renderer + custom editor + right SegmentedToggle
 * 4) Floating, draggable, resizable LineageDialog (modeless, Pin button, persisted size, auto-stretching columns)
 * 5) Version metadata consistency for immutable V_2.3_beta
 */
final class V23BetaTest {

    private static Engine newEngine(Prefs prefs) {
        Engine e = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        e.open(new MemoryStorage());
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.autoSend = false;
        c.confirmNewApis = false;
        c.dupNewPlace = true;
        e.applyConfig(c);
        return e;
    }

    private static Workspaces newWorkspaces(Prefs prefs, Path dir) {
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        return ws;
    }

    @AfterEach
    void resetGlobals() throws Exception {
        edt(() -> {
            UIManager.put("html.disable", null);
            FontManager.setUiChoice(FontManager.UiFontChoice.BE_VIETNAM_PRO);
            FontManager.setMonoChoice(FontManager.MonoFontChoice.JETBRAINS_MONO);
            UiDisplayMode.set(UiDisplayMode.ICON_AND_TEXT);
            Theme.setFontScaleMultiplier(1.0f);
        });
    }

    @Test
    void permanentZeroHtmlSourceGuardAcrossUiPackage() throws Exception {
        Path uiRoot = Path.of("src/main/java/themythofcrawl/ui");
        assertTrue(Files.isDirectory(uiRoot), "UI source directory must exist");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(uiRoot)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String trimmed = lines.get(i).trim();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        continue;
                    }
                    String low = trimmed.toLowerCase(Locale.ROOT);
                    if (low.contains("\"<html") || low.contains("\"<body") || low.contains("</html>\"")) {
                        violations.add(p + ":" + (i + 1) + " -> " + trimmed);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "Burp Suite sets UIManager html.disable=true; UI sources must NEVER use HTML strings in Swing labels:\n"
                        + String.join("\n", violations));
    }

    @Test
    void versionMetadataIsConsistentWithProperties() throws Exception {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("version.properties"))) {
            props.load(in);
        }
        String ver = props.getProperty("versionName");
        String tag = props.getProperty("releaseTag");
        String jar = props.getProperty("jarName");
        assertTrue(tag != null && tag.startsWith("V_"));
        assertEquals("theMythOf-JCrawl_" + tag + ".jar", jar);
        assertEquals(ver, Extension.VERSION);
        assertEquals(ver, Diagnostics.VERSION);
    }

    @Test
    void burpHtmlDisabledZeroTagLeakInWorkbenchAndAdvice(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        e.setDestination("Quản lý nhân sự > Danh sách");
        e.process(UiSmokeTest.msg("GET", "/api/v1/users/profile"), "Proxy", true, e.activeBranch().id());
        e.setDestination("Quản lý nhân sự > Cập nhật hồ sơ");
        e.process(UiSmokeTest.msg("GET", "/api/v1/users/profile"), "Proxy", true, e.activeBranch().id());
        Workspaces ws = newWorkspaces(prefs, dir);

        edt(() -> {
            applyLaf(false);
            UIManager.put("html.disable", Boolean.TRUE);
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v23-html");
            try {
                ApiGroup dup = e.groups().get(1);
                JPanel wb = ActionWorkbenchDialog.buildWorkbenchPanel(
                        ui.root(), e, dup, null, dup.branch(), ActionWorkbenchDialog.TAB_STRATEGY, () -> { }, () -> { });
                assertNoRawHtmlAnywhere(wb, "ActionWorkbenchDialog Tab 1");

                LineageDialog.View view = LineageDialog.buildView(ui, dup.bookKey(), dup, ui.root(), () -> { }, p -> { }, t -> { });
                assertNoRawHtmlAnywhere(view.panel(), "LineageDialog");

                JPopupMenu pop = DupPopup.show(ui, dup, ui.api.table, 0, 0);
                assertNoRawHtmlAnywhere(pop, "DupPopup");
            } finally {
                ui.dispose();
            }
        });
    }

    @Test
    void rolesTabSegmentedSwitchAndCustomEditor(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        e.roles().setAll(List.of(
                new Role("01_Admin", true,
                        List.of(HeaderRule.of("Authorization", "Bearer admin-tok", "REPLACE")), Role.Tier.ADMIN),
                new Role("02_Guest", false,
                        List.of(HeaderRule.of("Authorization", "[REMOVE]", "REMOVE")), Role.Tier.GUEST)));
        e.saveRoles();
        Workspaces ws = newWorkspaces(prefs, dir);

        edt(() -> {
            applyLaf(false);
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v23-roles");
            try {
                ui.tick(true);
                JTable tbl = ui.roles.roleTable();
                assertEquals(Theme.current().scaled(36), tbl.getRowHeight());
                assertInstanceOf(RolesTab.SquareStatusRenderer.class, tbl.getColumnModel().getColumn(1).getCellRenderer());
                assertTrue(tbl.getColumnModel().getColumn(1).getPreferredWidth() >= 64,
                        "Bật column must fit the switch + label");

                TableCellEditor ed = tbl.getColumnModel().getColumn(1).getCellEditor();
                assertNotNull(ed, "Column 1 must use a custom TableCellEditor");
                Component edComp = ed.getTableCellEditorComponent(tbl, Boolean.FALSE, true, 1, 1);
                assertFalse(edComp instanceof JCheckBox,
                        "Custom status editor must NEVER flash a default Swing JCheckBox");
                assertInstanceOf(RolesTab.SquareStatusRenderer.class, edComp);

                List<themythofcrawl.ui.kit.ModernToggleSwitch> toggles = findAll(ui.roles.panel, themythofcrawl.ui.kit.ModernToggleSwitch.class);
                assertFalse(toggles.isEmpty(), "Profile editor header must use ModernToggleSwitch for BẬT / TẮT");

                // Toggle row 1 ON via table model and verify sync
                tbl.setValueAt(Boolean.TRUE, 1, 1);
                assertTrue(e.roles().get(1).enabled());
                tbl.setValueAt(Boolean.FALSE, 1, 1);
                assertFalse(e.roles().get(1).enabled());
            } finally {
                ui.dispose();
            }
        });
    }

    @Test
    void lineageDialogIsFloatingDraggableResizableAndPersistsSize(@TempDir Path dir) throws Exception {
        // Verify floating/modeless/resizable contract in LineageDialog source & MainPanel DUP handler
        String lineageSrc = Files.readString(Path.of("src/main/java/themythofcrawl/ui/LineageDialog.java"), StandardCharsets.UTF_8);
        assertTrue(lineageSrc.contains("ModalityType.MODELESS"), "LineageDialog must be non-modal/floating");
        assertTrue(lineageSrc.contains("dlg.setResizable(true)"), "LineageDialog must be resizable");
        assertTrue(lineageSrc.contains("dlg.setAlwaysOnTop"), "LineageDialog must support Pin on top");
        String mainSrc = Files.readString(Path.of("src/main/java/themythofcrawl/ui/MainPanel.java"), StandardCharsets.UTF_8);
        assertTrue(mainSrc.contains("LineageDialog.showFloating(this, g)"),
                "Clicking DUP chip in MainPanel must open floating LineageDialog instead of frozen DupPopup");

        Prefs prefs = Prefs.memory();
        UiPrefs.setLineageSize(prefs, 1180, 680);
        assertEquals(new Dimension(1180, 680), UiPrefs.lineageSize(prefs));

        Engine e = newEngine(prefs);
        e.setDestination("Hệ thống > Cài đặt > Phân quyền");
        e.process(UiSmokeTest.msg("GET", "/api/v1/admin/permissions"), "Proxy", true, e.activeBranch().id());
        e.setDestination("Hệ thống > Người dùng > Vai trò & Quyền");
        e.process(UiSmokeTest.msg("GET", "/api/v1/admin/permissions"), "Proxy", true, e.activeBranch().id());
        Workspaces ws = newWorkspaces(prefs, dir);

        edt(() -> {
            applyLaf(false);
            UIManager.put("html.disable", Boolean.TRUE);
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v23-lineage");
            try {
                ApiGroup dup = e.groups().get(1);
                AtomicReference<String> titleRef = new AtomicReference<>("");
                LineageDialog.View view = LineageDialog.buildView(
                        ui, dup.bookKey(), dup, ui.root(), () -> { }, p -> { }, titleRef::set);

                assertTrue(titleRef.get().contains("/api/v1/admin/permissions"),
                        "Header title must include target endpoint");
                assertNotNull(view.pinButton(), "Floating LineageDialog must provide a Pin toggle button");
                assertEquals("lineagePinBtn", view.pinButton().getName());

                JTable tbl = view.table();
                assertTrue(tbl.getColumnModel().getColumn(4).getPreferredWidth() >= 128,
                        "Trạng thái column must fit 'Chờ bạn chọn' without clipping");
                assertTrue(tbl.getColumnModel().getColumn(7).getPreferredWidth() >= 92,
                        "Lần cuối column must fit '07:21:45' without clipping");

                // Verify dynamic column stretching when window widens to 1280px
                view.stretchForWidth().accept(1240);
                int stretchedPathW = tbl.getColumnModel().getColumn(LineageDialog.PATH_COL).getPreferredWidth();
                assertTrue(stretchedPathW > 480,
                        "Vị trí trong cây column must expand to absorb wider window width (got " + stretchedPathW + ")");

                List<StructuredAdviceCard> cards = findAll(view.panel(), StructuredAdviceCard.class);
                assertEquals(1, cards.size());
                assertNoRawHtmlAnywhere(view.panel(), "Floating LineageDialog");
            } finally {
                ui.dispose();
            }
        });
    }

    @Test
    void v23VisualVerificationScreenshots(@TempDir Path dir) throws Exception {
        if (!"1".equals(System.getenv("V23_SCREENSHOTS"))) {
            return;
        }
        Path outDir = Path.of("/tmp/v23_verify");
        Files.createDirectories(outDir);

        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        e.roles().setAll(List.of(
                new Role("01_Admin", true,
                        List.of(HeaderRule.of("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiIsImV4cCI6MTk5OTk5OTk5OX0.sig", "REPLACE")), Role.Tier.ADMIN),
                new Role("02_User", false,
                        List.of(HeaderRule.of("Authorization", "Bearer user-session-token", "REPLACE")), Role.Tier.USER),
                new Role("03_Guest", true,
                        List.of(HeaderRule.of("Authorization", "[REMOVE]", "REMOVE")), Role.Tier.GUEST)));
        e.saveRoles();

        e.setDestination("Quản trị > Bảo mật > Tài khoản");
        e.process(UiSmokeTest.msg("DELETE", "/api/v1/users/1042"), "Proxy", true, e.activeBranch().id());
        e.setDestination("Trang chủ > Chi tiết hồ sơ > Nút Xóa");
        e.process(UiSmokeTest.msg("DELETE", "/api/v1/users/1042"), "Proxy", true, e.activeBranch().id());
        e.setDestination("Nhật ký kiểm toán > Xem nhanh > Thao tác");
        e.process(UiSmokeTest.msg("DELETE", "/api/v1/users/1042"), "Proxy", true, e.activeBranch().id());

        Workspaces ws = newWorkspaces(prefs, dir);

        edt(() -> {
            // Simulate exact Burp Suite environment!
            UIManager.put("html.disable", Boolean.TRUE);

            // LIGHT THEME
            applyLaf(false);
            MainPanel uiLight = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v23-vis-light");
            try {
                uiLight.tick(true);
                ApiGroup dup = e.groups().get(1);

                // 1. ActionWorkbench Tab 1 Light
                JPanel wbL = ActionWorkbenchDialog.buildWorkbenchPanel(
                        uiLight.root(), e, dup, null, dup.branch(), ActionWorkbenchDialog.TAB_STRATEGY, () -> { }, () -> { });
                savePng(outDir.resolve("01_workbench_tab1_light.png"), renderOffscreen(wbL, 1020, 660));

                // 3. RolesTab Light (with row 1 OFF selected, row 0 ON, row 2 ON)
                uiLight.selectTab(uiLight.roles.panel);
                uiLight.roles.roleTable().setRowSelectionInterval(1, 1);
                savePng(outDir.resolve("03_roles_switch_light.png"), renderOffscreen(uiLight.roles.panel, 1180, 680));

                // 5. Floating LineageDialog Light (default 1040x620)
                LineageDialog.View viewL = LineageDialog.buildView(
                        uiLight, dup.bookKey(), dup, uiLight.root(), () -> { }, p -> { }, t -> { });
                viewL.stretchForWidth().accept(1000);
                savePng(outDir.resolve("05_lineage_floating_light.png"), renderOffscreen(viewL.panel(), 1040, 620));

                // 7. Expanded LineageDialog Light (1280x700)
                viewL.stretchForWidth().accept(1240);
                savePng(outDir.resolve("07_lineage_expanded_light.png"), renderOffscreen(viewL.panel(), 1280, 700));

                // 8. Backward-compat DupPopup Light
                JPopupMenu pop = DupPopup.show(uiLight, dup, uiLight.api.table, 0, 0);
                savePng(outDir.resolve("08_dup_popup_fixed_light.png"), renderOffscreen(pop.getComponent(0), 870, 440));
            } finally {
                uiLight.dispose();
            }

            // DARK THEME
            applyLaf(true);
            MainPanel uiDark = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v23-vis-dark");
            try {
                uiDark.tick(true);
                ApiGroup dup = e.groups().get(1);

                // 2. ActionWorkbench Tab 1 Dark
                JPanel wbD = ActionWorkbenchDialog.buildWorkbenchPanel(
                        uiDark.root(), e, dup, null, dup.branch(), ActionWorkbenchDialog.TAB_STRATEGY, () -> { }, () -> { });
                savePng(outDir.resolve("02_workbench_tab1_dark.png"), renderOffscreen(wbD, 1020, 660));

                // 4. RolesTab Dark
                uiDark.selectTab(uiDark.roles.panel);
                uiDark.roles.roleTable().setRowSelectionInterval(1, 1);
                savePng(outDir.resolve("04_roles_switch_dark.png"), renderOffscreen(uiDark.roles.panel, 1180, 680));

                // 6. Floating LineageDialog Dark
                LineageDialog.View viewD = LineageDialog.buildView(
                        uiDark, dup.bookKey(), dup, uiDark.root(), () -> { }, p -> { }, t -> { });
                viewD.stretchForWidth().accept(1000);
                savePng(outDir.resolve("06_lineage_floating_dark.png"), renderOffscreen(viewD.panel(), 1040, 620));
            } finally {
                uiDark.dispose();
            }
        });

        assertTrue(Files.exists(outDir.resolve("01_workbench_tab1_light.png")));
        assertTrue(Files.exists(outDir.resolve("05_lineage_floating_light.png")));
        assertTrue(Files.exists(outDir.resolve("07_lineage_expanded_light.png")));
    }

    // ------------------------------------------------------------------ Helpers
    private static void assertNoRawHtmlAnywhere(Component root, String where) {
        if (root instanceof JLabel lbl) {
            checkCleanText(lbl.getText(), where + " JLabel");
        } else if (root instanceof JTextComponent tc) {
            checkCleanText(tc.getText(), where + " " + tc.getClass().getSimpleName());
        }
        if (root instanceof Container ct) {
            for (Component ch : ct.getComponents()) {
                assertNoRawHtmlAnywhere(ch, where);
            }
        }
    }

    private static void checkCleanText(String txt, String ctx) {
        if (txt == null || txt.isEmpty()) {
            return;
        }
        String low = txt.toLowerCase(Locale.ROOT);
        assertFalse(low.contains("<html") || low.contains("<body") || low.contains("</html>"),
                ctx + " leaked raw HTML: " + txt);
        assertFalse(txt.contains("&amp;") || txt.contains("&gt;") || txt.contains("&lt;"),
                ctx + " leaked raw HTML entities: " + txt);
    }

    private static void applyLaf(boolean dark) {
        try {
            UIManager.setLookAndFeel(dark ? new FlatDarkLaf() : new FlatLightLaf());
            Theme.onThemeChanged();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void layoutTree(Component c) {
        c.doLayout();
        if (c instanceof Container ct) {
            for (Component ch : ct.getComponents()) {
                layoutTree(ch);
            }
        }
    }

    private static <T extends Component> List<T> findAll(Container root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends Component> void collect(Component c, Class<T> type, List<T> out) {
        if (type.isInstance(c)) {
            out.add(type.cast(c));
        }
        if (c instanceof Container ct) {
            for (Component ch : ct.getComponents()) {
                collect(ch, type, out);
            }
        }
    }

    private static BufferedImage renderOffscreen(Component c, int w, int h) {
        c.setSize(new Dimension(w, h));
        layoutTree(c);
        c.validate();
        layoutTree(c);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setColor(Theme.current().bg);
            g2.fillRect(0, 0, w, h);
            c.paint(g2);
        } finally {
            g2.dispose();
        }
        return img;
    }

    private static void savePng(Path path, BufferedImage img) {
        try {
            File f = path.toFile();
            ImageIO.write(img, "png", f);
        } catch (Exception ex) {
            throw new RuntimeException("Failed saving screenshot " + path, ex);
        }
    }

    private static void edt(Runnable r) throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                err.set(t);
            }
        });
        if (err.get() != null) {
            if (err.get() instanceof Exception ex) {
                throw ex;
            }
            if (err.get() instanceof Error e) {
                throw e;
            }
            throw new RuntimeException(err.get());
        }
    }
}
