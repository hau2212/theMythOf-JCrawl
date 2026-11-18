package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.Config;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.store.Workspaces;

/**
 * Folder layout of the branch tree: a folder's own APIs sit on top (inside a collapsible
 * "API của nhánh" bucket when the folder also has sub-folders) and the sub-folders come last.
 */
class BranchTreeLayoutTest {
    @Test
    void ownApisGoInCollapsibleBucketAboveSubfolders(@TempDir java.nio.file.Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine e = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        e.open(ws.start("p", "Project"));
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.confirmNewApis = false;
        e.applyConfig(c);
        e.setDestination("Toppage > Orders");
        e.process(UiSmokeTest.msg("GET", "/api/v1/orders"), "Proxy", true, e.activeBranch().id());
        e.process(UiSmokeTest.msg("POST", "/api/v1/cart"), "Proxy", true, e.activeBranch().id());
        e.setDestination("Toppage > Orders > Detail");
        e.process(UiSmokeTest.msg("GET", "/api/v1/orders/7"), "Proxy", true, e.activeBranch().id());
        e.setDestination("Toppage > Leaf");
        e.process(UiSmokeTest.msg("GET", "/api/v1/leaf"), "Proxy", true, e.activeBranch().id());
        assertEquals(4, e.groups().size());

        SwingUtilities.invokeAndWait(() -> {
            BranchTree bt = new BranchTree(e);
            bt.rebuild(false);
            Node root = (Node) bt.model.getRoot();
            Node orders = find(root, "Ftoppage > orders");
            assertNotNull(orders);

            // Orders = [APIS bucket (2 own APIs), Detail sub-folder] in that order
            assertEquals(2, orders.getChildCount());
            Node bucket = (Node) orders.getChildAt(0);
            Node detail = (Node) orders.getChildAt(1);
            assertEquals(Node.Kind.APIS, bucket.kind);
            assertEquals(Node.Kind.FOLDER, detail.kind);
            assertEquals(2, bucket.getChildCount());
            assertEquals(2, BranchTree.directGroups(bucket).size());
            for (int i = 0; i < bucket.getChildCount(); i++) {
                assertEquals(Node.Kind.GROUP, ((Node) bucket.getChildAt(i)).kind);
            }

            // A leaf folder (no sub-folders) keeps its APIs directly, without a bucket
            Node leaf = find(root, "Ftoppage > leaf");
            assertNotNull(leaf);
            assertEquals(1, leaf.getChildCount());
            assertEquals(Node.Kind.GROUP, ((Node) leaf.getChildAt(0)).kind);

            // Toppage has no own APIs: only sub-folders, no bucket
            Node top = find(root, "Ftoppage");
            for (int i = 0; i < top.getChildCount(); i++) {
                assertEquals(Node.Kind.FOLDER, ((Node) top.getChildAt(i)).kind);
            }

            // Selecting the bucket = the folder's own APIs only; branch = the folder
            bt.tree.setSelectionPath(new TreePath(bucket.getPath()));
            List<ApiGroup> sel = bt.selectedGroups();
            assertEquals(2, sel.size());
            assertEquals("Toppage > Orders", bt.selectedBranch());
            // Selecting the folder still brings everything under it
            bt.tree.setSelectionPath(new TreePath(orders.getPath()));
            assertEquals(3, bt.selectedGroups().size());

            // Collapsing the bucket survives a rebuild; the folder itself stays open
            bt.tree.collapsePath(new TreePath(bucket.getPath()));
            bt.rebuild(false);
            Node bucket2 = find((Node) bt.model.getRoot(), "Atoppage > orders");
            Node orders2 = find((Node) bt.model.getRoot(), "Ftoppage > orders");
            assertNotNull(bucket2);
            assertFalse(bt.tree.isExpanded(new TreePath(bucket2.getPath())));
            assertTrue(bt.tree.isExpanded(new TreePath(orders2.getPath())));

            // Renderer paints the bucket without HTML
            TreeRenderer tr = (TreeRenderer) bt.tree.getCellRenderer();
            tr.getTreeCellRendererComponent(bt.tree, bucket2, false, false, false, 0, false);
            assertFalse(tr.getText().contains("<html>"));
            assertTrue(tr.getText().contains("API"));
        });
        e.close();
    }

    private static Node find(Node root, String key) {
        for (java.util.Enumeration<?> en = root.depthFirstEnumeration(); en.hasMoreElements(); ) {
            Node n = (Node) en.nextElement();
            if (key.equals(n.key)) {
                return n;
            }
        }
        return null;
    }
}
