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

    @Test
    void renderersHandleNullAndEmptyValuesAndHeaderTruncatesWithoutHtml(@TempDir java.nio.file.Path dir) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(320, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            try {
                // Null AuditRows model + null cell value across every column mode
                for (AuditRenderer.Mode mode : AuditRenderer.Mode.values()) {
                    AuditRenderer r = new AuditRenderer(null, 0, 1, 2, mode, 0).pathCols(3).numberCols(4).deltaCol(5).timeCols(6);
                    for (int col = 0; col <= 7; col++) {
                        Component comp = r.getTableCellRendererComponent(null, null, col == 0, false, 0, col);
                        comp.setSize(120, 28);
                        comp.paint(g);
                    }
                }

                // Narrow table column -> HeaderRenderer truncates & supplies full tooltip
                RowsModel<String> m = new RowsModel<>(List.of("VeryLongHeaderColumnTitleThatOverflows", "B"));
                ThemedTable t = new ThemedTable(m);
                Ui.style(t);
                t.getColumnModel().getColumn(0).setWidth(48);
                AuditRenderer.HeaderRenderer hdr = (AuditRenderer.HeaderRenderer) t.getTableHeader().getDefaultRenderer();
                Component hc = hdr.getTableCellRendererComponent(t, "VeryLongHeaderColumnTitleThatOverflows", false, false, -1, 0);
                hc.setSize(48, 28);
                hc.paint(g);
                assertEquals("VeryLongHeaderColumnTitleThatOverflows", ((javax.swing.JComponent) hc).getToolTipText());

                // TreeRenderer handles null value, plain string value, and GROUP nodes without HTML
                TreeRenderer tr = new TreeRenderer();
                Component nullComp = tr.getTreeCellRendererComponent(null, null, false, false, true, 0, false);
                assertFalse(tr.getText().contains("<html>"));
                nullComp.setSize(160, 26);
                nullComp.paint(g);
                Component strComp = tr.getTreeCellRendererComponent(null, "Plain", true, false, true, 1, false);
                strComp.setSize(160, 26);
                strComp.paint(g);
            } finally {
                g.dispose();
            }
        });
    }

    @Test
    void multiSelectFilterChipsPinnedOrigSortAndBranchTreeChipHitTestWork(@TempDir java.nio.file.Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine e = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        e.open(ws.start("p", "Project"));
        e.setTargets(themythofcrawl.excel.TargetList.fromRows(
                List.of(List.of("STT", "PROTOCOL", "PATH"), List.of("1", "GET", "/api/v1/ok")),
                "t.xlsx", "S"));
        e.setDestination("Toppage > menu");
        e.process(UiSmokeTest.msg("GET", "/api/v1/ok"), "Proxy", true, e.activeBranch().id());
        e.process(UiSmokeTest.msg("GET", "/api/v1/warn"), "Proxy", true, e.activeBranch().id());
        // Capture duplicate of /api/v1/ok on a second branch so DUP badge renders on the tree row
        e.setDestination("Toppage > second");
        e.process(UiSmokeTest.msg("GET", "/api/v1/ok"), "Proxy", true, e.activeBranch().id());

        List<ApiGroup> all = e.groups();
        assertEquals(3, all.size());
        ApiGroup gOk = all.get(0);
        ApiGroup gWarn = all.get(1);
        ApiGroup gDupOk = all.get(2);

        gOk.origResult().setStatus(200);
        gOk.origResult().setLength(100);
        gOk.setOrigMeta(200, 100);
        // Set role results: one OK (200), one INFO (403), one WARN bypass, one ERROR
        var r0 = gOk.results().get(0);
        r0.setStatus(200);
        r0.setLength(100);
        r0.setState(themythofcrawl.model.ResultState.DONE);
        var r1 = gOk.results().get(1);
        r1.setStatus(403);
        r1.setLength(15);
        r1.setState(themythofcrawl.model.ResultState.DONE);
        var r2 = gOk.results().get(2);
        r2.setStatus(500);
        r2.setLength(40);
        r2.setState(themythofcrawl.model.ResultState.DONE);

        gWarn.origResult().setStatus(200);
        gWarn.origResult().setLength(200);
        gWarn.setOrigMeta(200, 200);
        boolean setDenied = false;
        for (var r : gWarn.results()) {
            if (!r.strippedAuth() && !setDenied) {
                r.setStatus(403);
                r.setLength(20);
                setDenied = true;
            } else {
                r.setStatus(200);
                r.setLength(200);
            }
            r.setState(themythofcrawl.model.ResultState.DONE);
        }
        gOk.setPending(false);
        gWarn.setPending(false);
        gDupOk.setPending(true);
        themythofcrawl.core.Audit.recalc(gOk);
        themythofcrawl.core.Audit.recalc(gWarn);

        SwingUtilities.invokeAndWait(() -> {
            MainPanel panel = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "p");
            panel.api.reloadAll();
            panel.api.tree.rebuild(true);

            // Multi-select filter chips in ApiTab
            assertEquals(3, panel.api.groupModel.getRowCount());
            panel.api.toggleAuditFilter(AuditLevel.PENDING);
            assertEquals(1, panel.api.groupModel.getRowCount());
            assertSame(gDupOk, panel.api.groupModel.at(0));

            panel.api.toggleAuditFilter(AuditLevel.WARN);
            assertEquals(2, panel.api.groupModel.getRowCount());
            panel.api.toggleAuditFilter(AuditLevel.PENDING);
            assertEquals(1, panel.api.groupModel.getRowCount());
            assertSame(gWarn, panel.api.groupModel.at(0));
            panel.api.clearAuditFilters();
            assertEquals(3, panel.api.groupModel.getRowCount());

            // Result table sorting keeps GOC pinned at view row 0 in ASC and DESC
            panel.api.showGroup(gOk);
            JTable res = panel.api.results;
            res.getRowSorter().toggleSortOrder(4); // Status ASC
            assertEquals("GỐC", res.getValueAt(0, 0));
            res.getRowSorter().toggleSortOrder(4); // Status DESC
            assertEquals("GỐC", res.getValueAt(0, 0));
            res.getRowSorter().toggleSortOrder(5); // Body Len ASC
            assertEquals("GỐC", res.getValueAt(0, 0));
            res.getRowSorter().toggleSortOrder(5); // Body Len DESC
            assertEquals("GỐC", res.getValueAt(0, 0));
            res.getRowSorter().toggleSortOrder(6); // Delta DESC
            assertEquals("GỐC", res.getValueAt(0, 0));

            // BranchTree chip hit testing (Copy, Excel, Dup badges)
            BranchTree bt = panel.api.tree;
            bt.expandFolders(true);
            bt.tree.setSize(720, 480);
            bt.tree.doLayout();
            boolean hitCopy = false;
            boolean hitExcel = false;
            boolean hitDup = false;
            TreeRenderer tr = (TreeRenderer) bt.tree.getCellRenderer();
            for (int row = 0; row < bt.tree.getRowCount(); row++) {
                javax.swing.tree.TreePath tp = bt.tree.getPathForRow(row);
                Node node = (Node) tp.getLastPathComponent();
                if (node.kind == Node.Kind.GROUP && node.payload == gDupOk) {
                    java.awt.Rectangle bounds = bt.tree.getPathBounds(tp);
                    assertNotNull(bounds);
                    tr.getTreeCellRendererComponent(bt.tree, node, false, false, false, row, false);
                    assertFalse(tr.getText().contains("<html>"));
                    int y = bounds.y + bounds.height / 2;
                    for (int dx = 0; dx < bounds.width; dx += 4) {
                        Object[] hit = bt.chipAt(bounds.x + dx, y);
                        if (hit != null) {
                            if (hit[0] == BranchTree.Chip.COPY) {
                                hitCopy = true;
                            } else if (hit[0] == BranchTree.Chip.EXCEL) {
                                hitExcel = true;
                            } else if (hit[0] == BranchTree.Chip.DUP) {
                                hitDup = true;
                            }
                        }
                    }
                }
            }
            assertTrue(hitCopy, "BranchTree.chipAt must hit Copy icon on GROUP row");
            assertTrue(hitExcel, "BranchTree.chipAt must hit Excel badge on GROUP row");
            assertTrue(hitDup, "BranchTree.chipAt must hit Dup badge on GROUP row");

            panel.dispose();
        });
        e.close();
    }

    @Test
    void historyReportViewKpisModesHeatmapJumpAnd5000ApiVirtualization(@TempDir java.nio.file.Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine e = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        e.open(ws.start("p", "Project"));
        e.setTargets(themythofcrawl.excel.TargetList.fromRows(
                List.of(List.of("STT", "PROTOCOL", "PATH"),
                        List.of("1", "GET", "/api/v1/ok"),
                        List.of("2", "GET", "/api/v1/missing")),
                "cov.xlsx", "S"));

        // Branch 1 (default): ok, bypass, error
        e.setDestination("Toppage > auth");
        e.process(UiSmokeTest.msg("GET", "/api/v1/ok"), "Proxy", true, e.activeBranch().id());
        e.process(UiSmokeTest.msg("GET", "/api/v1/bypass"), "Proxy", true, e.activeBranch().id());
        e.process(UiSmokeTest.msg("GET", "/api/v1/err"), "Proxy", true, e.activeBranch().id());
        // Branch 2: empty branch
        e.setDestination("Toppage > empty");
        // Branch 3: diff + unsent
        e.setDestination("Toppage > orders");
        e.process(UiSmokeTest.msg("GET", "/api/v1/diff"), "Proxy", true, e.activeBranch().id());
        e.process(UiSmokeTest.msg("GET", "/api/v1/unsent"), "Proxy", true, e.activeBranch().id());

        List<ApiGroup> gs = e.groups();
        assertEquals(5, gs.size());
        ApiGroup gOk = gs.get(0);
        ApiGroup gBypass = gs.get(1);
        ApiGroup gErr = gs.get(2);
        ApiGroup gDiff = gs.get(3);
        ApiGroup gUnsent = gs.get(4);

        gOk.setOrigMeta(200, 100);
        gOk.origResult().setStatus(200);
        gOk.origResult().setLength(100);
        for (var r : gOk.results()) {
            r.setStatus(200);
            r.setLength(100);
            r.setState(themythofcrawl.model.ResultState.DONE);
        }

        gBypass.setOrigMeta(200, 120);
        gBypass.origResult().setStatus(200);
        gBypass.origResult().setLength(120);
        boolean deniedOnce = false;
        for (var r : gBypass.results()) {
            if (!r.strippedAuth() && !deniedOnce) {
                r.setStatus(403);
                r.setLength(10);
                deniedOnce = true;
            } else {
                r.setStatus(200);
                r.setLength(120);
            }
            r.setState(themythofcrawl.model.ResultState.DONE);
        }

        gErr.setOrigMeta(200, 80);
        gErr.origResult().setStatus(200);
        gErr.origResult().setLength(80);
        for (int i = 0; i < gErr.results().size(); i++) {
            var r = gErr.results().get(i);
            if (i == 0) {
                r.setState(themythofcrawl.model.ResultState.ERROR);
                r.setError("Timeout");
            } else {
                r.setStatus(200);
                r.setLength(80);
                r.setState(themythofcrawl.model.ResultState.DONE);
            }
        }

        gDiff.setOrigMeta(200, 90);
        gDiff.origResult().setStatus(200);
        gDiff.origResult().setLength(90);
        for (var r : gDiff.results()) {
            if (r.strippedAuth()) {
                r.setStatus(401);
                r.setLength(20);
            } else {
                r.setStatus(403);
                r.setLength(10);
            }
            r.setState(themythofcrawl.model.ResultState.DONE);
        }

        for (ApiGroup g : List.of(gOk, gBypass, gErr, gDiff, gUnsent)) {
            themythofcrawl.core.Audit.recalc(g);
        }

        SwingUtilities.invokeAndWait(() -> {
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "p");
            ui.root().setSize(1100, 760);
            ui.root().doLayout();
            HistoryReportView rv = ui.history.report;
            rv.setSize(680, 620);
            rv.doLayout();
            rv.setData(e.branches(), e.groups(), e.targets());

            // 1. KPI strip counts & Excel coverage
            assertEquals("5", rv.kpiValue(1)); // Total API
            assertEquals("1", rv.kpiValue(2)); // OK
            assertEquals("1", rv.kpiValue(3)); // Lệch
            assertEquals("1", rv.kpiValue(4)); // Bypass
            assertEquals("1", rv.kpiValue(5)); // Lỗi
            assertEquals("1", rv.kpiValue(6)); // Chưa gửi
            assertEquals("50%", rv.kpiValue(7)); // Excel 1/2
            assertEquals(e.branches().size(), rv.branchCardCount());

            // 2. Empty branch displays an EmptyState card body
            List<themythofcrawl.ui.kit.EmptyState> emptyStates = collect(rv, themythofcrawl.ui.kit.EmptyState.class);
            assertFalse(emptyStates.isEmpty(), "empty branch card must display an EmptyState placeholder");

            // 3. Heatmap tooltip + click-to-jump onto specific (ApiGroup, RoleResult)
            HistoryReportView.BranchRowsPanel brp = rv.rowsPanelForBranch(gDiff.branchId());
            assertNotNull(brp);
            brp.setSize(640, 60);
            var hitRole = gDiff.results().get(0);
            java.awt.Point center = brp.roleSquareCenter(0, 0);
            assertNotNull(center);
            java.awt.event.MouseEvent hoverEv = new java.awt.event.MouseEvent(brp,
                    java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, center.x, center.y, 0, false);
            String tip = brp.getToolTipText(hoverEv);
            assertNotNull(tip);
            assertTrue(tip.contains(hitRole.roleName() + ": 403 (10 B)"), "tooltip was: " + tip);

            java.awt.event.MouseEvent clickEv = new java.awt.event.MouseEvent(brp,
                    java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                    java.awt.event.InputEvent.BUTTON1_DOWN_MASK, center.x, center.y, 1, false,
                    java.awt.event.MouseEvent.BUTTON1);
            brp.dispatchEvent(clickEv);
            assertSame(ui.api.panel, ui.tabs.getSelectedComponent());
            assertSame(gDiff, ui.api.currentGroup());
            assertSame(hitRole, ui.api.selectedResult());

            // 4. Mode switching (Ma trận + Văn bản) & Burp renderer retention
            ui.tabs.setSelectedComponent(ui.history.panel);
            rv.setViewMode(HistoryReportView.MODE_MATRIX);
            UiPreview.PlainHost.simulateBurpTheme(rv);
            ThemedTable mat = rv.matrixTable();
            assertEquals(5, mat.getRowCount());
            assertTrue(mat.getColumnCount() >= 5 + gOk.results().size());
            for (int col = 0; col < mat.getColumnCount(); col++) {
                assertInstanceOf(ThemedTree.ExtensionRenderer.class,
                        mat.getColumnModel().getColumn(col).getCellRenderer());
            }

            rv.setViewMode(HistoryReportView.MODE_TEXT);
            assertTrue(rv.summaryTextArea().getText().contains("BÁO CÁO LỊCH SỬ"));

            // 5. Collapse / Expand all & scrollToBranchPath
            rv.setViewMode(HistoryReportView.MODE_CARDS);
            rv.expandAll(false);
            assertNull(rv.rowsPanelForBranch(gDiff.branchId()), "collapsed card hides row body");
            assertTrue(rv.scrollToBranchPath("Toppage > orders"));
            assertNotNull(rv.rowsPanelForBranch(gDiff.branchId()), "scrolling to branch auto-expands it");

            // Paint & dynamic theme switch check
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(680, 620,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2 = img.createGraphics();
            try {
                rv.paint(g2);
                themythofcrawl.ui.theme.Theme.onThemeChanged();
            } finally {
                g2.dispose();
            }
            ui.dispose();
        });
        e.close();

        // 6. Scale test: 5000 APIs across 200 branches < 300 ms and virtualized card realization
        Engine big = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        big.open(ws.start("big-h", "BigHistory"));
        themythofcrawl.core.Config cfg = big.config().copy();
        cfg.captureEnabled = true;
        cfg.confirmNewApis = false;
        big.applyConfig(cfg);
        for (int i = 0; i < 5000; i++) {
            if (i % 25 == 0) {
                big.setDestination("Toppage > m" + (i / 500) + " > b" + (i / 25));
            }
            big.process(UiSmokeTest.msg("GET", "/api/v1/r" + i + "/" + i), "Proxy", true, big.activeBranch().id());
        }
        SwingUtilities.invokeAndWait(() -> {
            HistoryReportView bigView = new HistoryReportView(null, null);
            bigView.setSize(720, 600);
            bigView.doLayout();
            long t0 = System.nanoTime();
            bigView.setData(big.branches(), big.groups(), big.targets());
            bigView.doLayout();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(elapsedMs < 300, "5000-API HistoryReportView setData+layout must be < 300 ms, took " + elapsedMs + " ms");
            assertEquals("5000", bigView.kpiValue(1));
            assertTrue(bigView.branchCardCount() >= 200);
            assertTrue(bigView.realizedCardCount() < 50,
                    "virtualized card list should materialize only viewport cards, realized=" + bigView.realizedCardCount());
        });
        big.close();
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
