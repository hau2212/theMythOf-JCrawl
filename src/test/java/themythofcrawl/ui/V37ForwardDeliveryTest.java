package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Bridge;
import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.Config;
import themythofcrawl.core.ForwardDeliveryGuard;
import themythofcrawl.core.ProxyInterceptQueue.PendingRequest;
import themythofcrawl.core.ProxyInterceptQueue.Resolution;
import themythofcrawl.core.ProxyInterceptQueue.ResolutionKind;
import themythofcrawl.model.Service;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.store.Workspaces;

/**
 * Verification suite for V_3.7_beta:
 * 1) Selecting a [CHỜ DUYỆT] node in TreeTab and clicking "Gửi mục chọn" forwards it to Server.
 * 2) Selecting a FOLDER containing [CHỜ DUYỆT] nodes in BranchTree includes them in selectedHeld() and forwards them.
 * 3) ForwardDeliveryGuard disarms cleanly when Burp Proxy dispatches normally (zero double-send).
 * 4) ForwardDeliveryGuard triggers direct-HTTP fallback when Burp Proxy silently drops (e.g. browser timeout).
 */
class V37ForwardDeliveryTest {

    private static final class StubBridge implements Bridge {
        @Override public boolean inScope(String url) { return true; }
        @Override public Sent send(Service s, byte[] req) {
            return new Sent("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.ISO_8859_1), 5, null);
        }
        @Override public void toRepeater(Service service, byte[] request, String tabName) { }
        @Override public void out(String line) { }
        @Override public void err(String line) { }
    }

    @Test
    void treeTabSendSelectedItemsForwardsSelectedHeldNodeAndFolder(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine engine = new Engine(new StubBridge(), prefs, null);
        engine.open(new MemoryStorage());
        Config cfg = engine.config().copy();
        cfg.captureEnabled = true;
        cfg.autoSend = false;
        cfg.confirmNewApis = false;
        cfg.ignoreStatic = false;
        engine.applyConfig(cfg);

        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);

        try {
            engine.startNewBranch("Toppage > Orders");
            Service svc = new Service("api.shop.vn", 443, true);
            byte[] raw1 = ("POST /api/orders/save HTTP/1.1\r\nHost: api.shop.vn\r\nContent-Length: 10\r\n\r\n{\"id\":101}")
                    .getBytes(StandardCharsets.ISO_8859_1);
            byte[] raw2 = ("DELETE /api/orders/102 HTTP/1.1\r\nHost: api.shop.vn\r\nContent-Length: 0\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1);

            MainPanel[] mpRef = new MainPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                mpRef[0] = new MainPanel(engine, ws, new UiSmokeTest.HeadlessHost(), "test-v37");
            });
            MainPanel mp = mpRef[0];

            // Enqueue 1st request asynchronously (simulating Proxy worker thread waiting for user)
            CompletableFuture<Resolution> res1Future = CompletableFuture.supplyAsync(() ->
                    engine.proxyQueue().enqueueAndAwait(
                            svc, "POST", "/api/orders/save", "https://api.shop.vn/api/orders/save",
                            "Toppage > Orders", "Chặn Action api.shop.vn", null, raw1, 10_000L));

            long deadline = System.currentTimeMillis() + 3000;
            while (engine.proxyQueue().pendingCount() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(15);
            }
            assertEquals(1, engine.proxyQueue().pendingCount());
            PendingRequest pr1 = engine.proxyQueue().pending().get(0);

            // Rebuild TreeTab, select the [CHỜ DUYỆT] node, and click "Gửi mục chọn" (sendSelectedItems)
            SwingUtilities.invokeAndWait(() -> {
                mp.treeTab.onProxyQueueChanged(true);
                Node root = (Node) mp.treeTab.tree.tree.getModel().getRoot();
                Node heldNode = findNodeByKey(root, "H" + pr1.id());
                assertNotNull(heldNode, "HELD_PENDING node must exist in TreeTab");
                mp.treeTab.tree.tree.setSelectionPath(new TreePath(heldNode.getPath()));
                assertEquals(1, mp.treeTab.tree.selectedHeld().size(),
                        "selectedHeld() must return the selected HELD_PENDING request");
                assertEquals("Toppage > Orders", mp.treeTab.tree.selectedBranch(),
                        "selectedBranch() must resolve branch of HELD_PENDING node");

                mp.treeTab.sendSelectedItems();
            });

            Resolution res1 = res1Future.get(3, TimeUnit.SECONDS);
            assertEquals(ResolutionKind.FORWARD, res1.kind(), "Gửi mục chọn on [CHỜ DUYỆT] must FORWARD to Server");
            assertArrayEquals(raw1, res1.editedRequest());
            assertEquals(0, engine.proxyQueue().pendingCount());

            // Now test selecting the parent FOLDER containing a held request
            CompletableFuture<Resolution> res2Future = CompletableFuture.supplyAsync(() ->
                    engine.proxyQueue().enqueueAndAwait(
                            svc, "DELETE", "/api/orders/102", "https://api.shop.vn/api/orders/102",
                            "Toppage > Orders", "Chặn Action api.shop.vn", null, raw2, 10_000L));

            deadline = System.currentTimeMillis() + 3000;
            while (engine.proxyQueue().pendingCount() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(15);
            }
            assertEquals(1, engine.proxyQueue().pendingCount());

            SwingUtilities.invokeAndWait(() -> {
                mp.treeTab.onProxyQueueChanged(true);
                assertTrue(mp.treeTab.tree.selectBranchPath("Toppage > Orders"));
                List<PendingRequest> heldInFolder = mp.treeTab.tree.selectedHeld();
                assertEquals(1, heldInFolder.size(),
                        "Selecting parent FOLDER must include contained HELD_PENDING requests");
                mp.treeTab.sendSelectedItems();
            });

            Resolution res2 = res2Future.get(3, TimeUnit.SECONDS);
            assertEquals(ResolutionKind.FORWARD, res2.kind(), "Gửi mục chọn on FOLDER must FORWARD contained held requests");
            assertEquals(0, engine.proxyQueue().pendingCount());

            SwingUtilities.invokeAndWait(mp::dispose);
        } finally {
            engine.close();
        }
    }

    @Test
    void forwardDeliveryGuardPreventsDoubleSendWhenProxyDispatches() throws Exception {
        try (ForwardDeliveryGuard guard = new ForwardDeliveryGuard(120L)) {
            AtomicInteger fallbackCalls = new AtomicInteger(0);
            guard.arm(42, fallbackCalls::incrementAndGet);
            assertTrue(guard.markProxyDispatched(42), "First markProxyDispatched must win the race");
            assertFalse(guard.markProxyDispatched(42), "Second markProxyDispatched must be a no-op");
            Thread.sleep(250L);
            assertEquals(0, fallbackCalls.get(), "Fallback MUST NOT fire when Burp Proxy dispatched normally");
        }
    }

    @Test
    void forwardDeliveryGuardFiresFallbackOnceWhenProxySilentlyDrops() throws Exception {
        try (ForwardDeliveryGuard guard = new ForwardDeliveryGuard(100L)) {
            AtomicInteger fallbackCalls = new AtomicInteger(0);
            CountDownLatch fired = new CountDownLatch(1);
            guard.arm(99, () -> {
                fallbackCalls.incrementAndGet();
                fired.countDown();
            });
            assertTrue(fired.await(2, TimeUnit.SECONDS), "Fallback must fire when Burp Proxy silently drops the request");
            assertFalse(guard.markProxyDispatched(99), "Late markProxyDispatched after fallback must return false");
            Thread.sleep(150L);
            assertEquals(1, fallbackCalls.get(), "Fallback must execute exactly once");
        }
    }

    private static Node findNodeByKey(Node root, String key) {
        if (root == null) return null;
        if (key.equals(root.key)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            Node found = findNodeByKey((Node) root.getChildAt(i), key);
            if (found != null) return found;
        }
        return null;
    }
}
