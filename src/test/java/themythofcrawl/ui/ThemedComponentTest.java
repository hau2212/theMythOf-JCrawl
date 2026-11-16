package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.place.Decisions;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.kit.ThemedTree;

/** Verifies Phase 0 fixes: diagnostics strings and Burp renderer protection in ThemedTree / ThemedTable. */
class ThemedComponentTest {
    @Test
    void themedTreeRetainsExtensionRendererAcrossForeignOverwriteAndUpdateUi() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Node root = new Node(Node.Kind.ROOT, "", "Root", AuditLevel.NONE, "R", 0);
            DefaultTreeModel model = new DefaultTreeModel(root);
            TreeRenderer ext = new TreeRenderer();
            ThemedTree tree = new ThemedTree(model, ext);
            tree.setRowHeight(26);
            tree.setLargeModel(true);
            tree.setToggleClickCount(2);

            tree.setCellRenderer(new DefaultTreeCellRenderer());
            assertSame(ext, tree.getCellRenderer(), "foreign DefaultTreeCellRenderer must be ignored");

            tree.setCellRenderer(null);
            assertSame(ext, tree.getCellRenderer(), "null renderer must be ignored once extension renderer is installed");

            SwingUtilities.updateComponentTreeUI(tree);
            assertSame(ext, tree.getCellRenderer(), "updateComponentTreeUI must retain extension renderer");
            assertEquals(26, tree.getRowHeight());
            assertTrue(tree.isLargeModel());
            assertEquals(2, tree.getToggleClickCount());
            assertFalse(ext.isOpaque());

            TreeRenderer ext2 = new TreeRenderer();
            tree.setCellRenderer(ext2);
            assertSame(ext2, tree.getCellRenderer(), "replacing with another extension renderer is allowed");
        });
    }

    @Test
    void themedTableRetainsRenderersStyleAndMovedColumnsAcrossBurpTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RowsModel<String> model = new RowsModel<>(List.of("ColA", "ColB", "ColC"));
            model.set(List.of(new Decisions.Row<>("x", AuditLevel.OK, List.of("1", "GET", "/v1/a"))));
            ThemedTable table = new ThemedTable(model);
            Ui.style(table);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

            AuditRenderer mainRenderer = new AuditRenderer(model, null, null, 1, 0);
            AuditRenderer copyRenderer = new AuditRenderer(model, null, null, null, AuditRenderer.Mode.COPY);
            table.setDefaultRenderer(Object.class, mainRenderer);
            table.getColumnModel().getColumn(0).setCellRenderer(copyRenderer);
            table.getColumnModel().getColumn(1).setCellRenderer(mainRenderer);
            table.getColumnModel().getColumn(2).setCellRenderer(mainRenderer);

            // Simulate column reordering (e.g. ApiTab moves Copy/Excel/Dup columns)
            table.getColumnModel().moveColumn(0, 2);

            // Simulate Burp applying DefaultTableCellRenderer to defaults and columns
            DefaultTableCellRenderer foreign = new DefaultTableCellRenderer();
            table.setDefaultRenderer(Object.class, foreign);
            for (int i = 0; i < table.getColumnCount(); i++) {
                table.getColumnModel().getColumn(i).setCellRenderer(foreign);
            }
            SwingUtilities.updateComponentTreeUI(table);

            assertSame(mainRenderer, table.getDefaultRenderer(Object.class));
            assertSame(copyRenderer, table.getColumn("ColA").getCellRenderer());
            assertSame(mainRenderer, table.getColumn("ColB").getCellRenderer());
            assertSame(mainRenderer, table.getColumn("ColC").getCellRenderer());
            assertEquals(themythofcrawl.ui.theme.Theme.ROW_TABLE, table.getRowHeight());
            assertEquals(JTable.AUTO_RESIZE_OFF, table.getAutoResizeMode());
            assertTrue(table.getShowHorizontalLines());
            assertFalse(table.getShowVerticalLines());
            assertTrue(table.getFillsViewportHeight());
            assertTrue(table.getTableHeader().getFont().isBold());

            // Structure change rebuilds TableColumns from TableModel and should re-attach extension renderers
            model.fireTableStructureChanged();
            assertSame(copyRenderer, table.getColumn("ColA").getCellRenderer());
            assertSame(mainRenderer, table.getColumn("ColB").getCellRenderer());
            assertSame(mainRenderer, table.getColumn("ColC").getCellRenderer());
        });
    }

    @Test
    void booleanCheckboxColumnRemainsNullRendererAfterSimulateBurp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AbstractTableModel model = new AbstractTableModel() {
                @Override
                public int getRowCount() {
                    return 1;
                }

                @Override
                public int getColumnCount() {
                    return 2;
                }

                @Override
                public Class<?> getColumnClass(int c) {
                    return c == 0 ? Boolean.class : String.class;
                }

                @Override
                public Object getValueAt(int r, int c) {
                    return c == 0 ? Boolean.TRUE : "Role";
                }
            };
            ThemedTable t = new ThemedTable(model);
            UiPreview.PlainHost.simulateBurpTheme(t);
            assertNull(t.getColumnModel().getColumn(0).getCellRenderer(),
                    "columns without custom extension renderer stay null so Boolean checkboxes keep rendering");
        });
    }

    @Test
    void mainPanelKeepsAllExtensionRenderersAndShowsDiagnosticsUnderSimulatedBurpTheme(@TempDir java.nio.file.Path dir)
            throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine e = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        e.open(ws.start("p", "Project"));
        e.setDestination("Toppage > menu");
        e.process(UiSmokeTest.msg("GET", "/api/v1/users/1"), "Proxy", true, e.activeBranch().id());
        List<ApiGroup> all = e.groups();
        assertEquals(1, all.size());

        UiHost burpLikeHost = new UiHost() {
            private final UiHost delegate = new UiSmokeTest.HeadlessHost();

            @Override
            public Viewer requestViewer(boolean editable) {
                return delegate.requestViewer(editable);
            }

            @Override
            public Viewer responseViewer() {
                return delegate.responseViewer();
            }

            @Override
            public void applyTheme(Component c) {
                SwingUtilities.updateComponentTreeUI(c);
                UiPreview.PlainHost.simulateBurpTheme(c);
            }

            @Override
            public String projectName() {
                return "Project";
            }
        };

        MainPanel[] ui = new MainPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            ui[0] = new MainPanel(e, ws, burpLikeHost, "p");
            for (int i = 0; i < ui[0].tabs.getTabCount(); i++) {
                ui[0].tabs.setSelectedIndex(i);
                ui[0].tick(true);
            }
            // Re-run simulateBurpTheme after all tabs have populated data (runtime theme switch)
            burpLikeHost.applyTheme(ui[0].root());

            List<JTree> trees = collect(ui[0].root(), JTree.class);
            assertTrue(trees.size() >= 4, "expected BranchTree + LogTab tree instances");
            for (JTree t : trees) {
                assertInstanceOf(ThemedTree.class, t);
                assertInstanceOf(ThemedTree.ExtensionRenderer.class, t.getCellRenderer(),
                        "tree renderer must survive Burp applyTheme");
            }

            // API, Branch, Results, Audit, Excel tables must keep AuditRenderer
            for (JTable table : List.of(ui[0].api.table, ui[0].api.branchTable, ui[0].api.results)) {
                assertInstanceOf(ThemedTable.class, table);
                for (int col = 0; col < table.getColumnCount(); col++) {
                    TableCellRenderer cr = table.getColumnModel().getColumn(col).getCellRenderer();
                    assertInstanceOf(ThemedTree.ExtensionRenderer.class, cr,
                            "table column " + col + " lost its extension renderer");
                }
            }

            // Diagnostics assertions
            String loadLine = Diagnostics.loadLogLine();
            assertTrue(loadLine.startsWith("theMythOf-JCrawl "), loadLine);
            assertTrue(loadLine.contains(" loaded · LAF="), loadLine);
            assertTrue(loadLine.contains("dark="), loadLine);

            String summary = Diagnostics.summary();
            assertTrue(summary.contains("LAF: "), summary);
            assertTrue(summary.contains("font="), summary);
            assertNotNull(ui[0].status.getToolTipText());
            assertTrue(ui[0].status.getToolTipText().contains("LAF:"));

            List<String> labels = collect(ui[0].settings.panel, JLabel.class).stream().map(JLabel::getText).toList();
            assertTrue(labels.stream().anyMatch(s -> s != null && s.contains("Panel.background=")),
                    "Settings Section 8 should populate theme colors");
            assertTrue(labels.stream().anyMatch(s -> s != null && s.contains("sun.java2d.uiScale=")),
                    "Settings Section 8 should populate scale & font");

            ui[0].dispose();
        });
        e.close();
    }

    @Test
    void headerFitsTwoRowsAt1100PxAndToastAndPopoverWork(@TempDir java.nio.file.Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine e = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        e.open(ws.start("p", "Project"));
        e.setTargets(themythofcrawl.excel.TargetList.fromRows(
                List.of(List.of("STT", "PROTOCOL", "PATH"), List.of("1", "GET", "/api/v1/users/{id}")),
                "t.xlsx", "S"));
        e.setDestination("Toppage > menu > button \"Search\" > confirm");
        e.process(UiSmokeTest.msg("GET", "/api/v1/users/1"), "Proxy", true, e.activeBranch().id());
        e.setDestination("Toppage > second");
        e.process(UiSmokeTest.msg("GET", "/api/v1/users/2"), "Proxy", true, e.activeBranch().id());

        SwingUtilities.invokeAndWait(() -> {
            MainPanel panel = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "p");
            panel.tick(true);
            java.awt.Dimension pref = panel.headerPanel().getPreferredSize();
            assertTrue(pref.height <= 64, "2-row header preferred height must be <= 64 px, was " + pref.height);

            panel.root().setSize(1100, 760);
            panel.root().doLayout();
            panel.headerPanel().doLayout();
            assertTrue(panel.headerPanel().getHeight() <= 68,
                    "header at 1100px width should stay within 2 compact rows, height=" + panel.headerPanel().getHeight());
            assertTrue(pref.width <= 1100, "header preferred width should fit 1100px window, was " + pref.width);

            assertTrue(panel.status.getText().startsWith("API "), panel.status.getText());
            assertTrue(panel.dest.path().contains(">"), panel.dest.path());

            themythofcrawl.ui.kit.ToastPane.setAnimationsEnabled(false);
            panel.flash("Đã copy đường dẫn Test");
            assertTrue(panel.toastPane.isToastVisible(), "flash() must reveal non-blocking ToastPane toast");
            assertEquals("Đã copy đường dẫn Test", panel.toastPane.lastMessage());
            panel.toastPane.doLayout();
            panel.toastPane.hideToast();
            assertFalse(panel.toastPane.isToastVisible(), "hideToast() must hide toast");
            themythofcrawl.ui.kit.ToastPane.setAnimationsEnabled(true);

            javax.swing.JPopupMenu pop = Ui.popover(panel.status, "Trợ giúp", "Nội dung mẫu");
            assertEquals(1, pop.getComponentCount());
            assertInstanceOf(themythofcrawl.ui.kit.Card.class, pop.getComponent(0),
                    "Ui.popover must render inside a themed Card");

            panel.dispose();
        });
        e.close();
    }

    private static <T> List<T> collect(Component root, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Container c) {
            for (Component ch : c.getComponents()) {
                out.addAll(collect(ch, type));
            }
        }
        return out;
    }
}
