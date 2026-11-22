package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.ActionEngine.SuggestedPath;
import themythofcrawl.core.ActionEngine.SwapPreview;
import themythofcrawl.core.Config;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.NodeTypeCatalog;
import themythofcrawl.model.NodeTypeCatalog.NodeType;
import themythofcrawl.place.Decisions;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.kit.IconPickerPanel;
import themythofcrawl.ui.kit.RichHoverCard;
import themythofcrawl.ui.kit.StructuredAdviceCard;
import themythofcrawl.ui.theme.FontManager;
import themythofcrawl.ui.theme.Theme;

/**
 * Comprehensive verification suite for V_2.1_beta covering all 6 pillars:
 * bundled typography, 3-state display mode + RichHoverCard, 42-icon pack + custom Hex/PNG,
 * Duplicate/Lineage v2 tables & StructuredAdviceCard, IconPickerPanel v2, and Action Workbench v2.
 */
final class V21BetaTest {

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

    private static Engine.Message rawMsg(byte[] req, byte[] resp, String path) {
        return new Engine.Message(UiSmokeTest.SVC, req, resp, "https://api.example.test" + path);
    }

    @AfterEach
    void resetGlobals() throws Exception {
        edt(() -> {
            FontManager.setUiChoice(FontManager.UiFontChoice.BE_VIETNAM_PRO);
            FontManager.setMonoChoice(FontManager.MonoFontChoice.JETBRAINS_MONO);
            UiDisplayMode.set(UiDisplayMode.ICON_AND_TEXT);
            Theme.setFontScaleMultiplier(1.0f);
        });
    }

    @Test
    void pillar1BundledTypographyAndLiveFontSwitching(@TempDir Path dir) throws Exception {
        assertTrue(FontManager.bundledFontsAvailable(), "Bundled Be Vietnam Pro & JetBrains Mono TTFs must load");
        edt(() -> {
            applyLaf(false);
            Prefs prefs = Prefs.memory();

            UiPrefs.setUiFontChoice(prefs, FontManager.UiFontChoice.BE_VIETNAM_PRO);
            UiPrefs.setMonoFontChoice(prefs, FontManager.MonoFontChoice.JETBRAINS_MONO);
            UiPrefs.setFontScalePercent(prefs, 100);
            Theme t1 = Theme.current();
            assertTrue(t1.baseFont.getFamily().toLowerCase().contains("be vietnam"),
                    "Expected Be Vietnam Pro, got: " + t1.baseFont.getFamily());
            assertTrue(t1.monoFont.getFamily().toLowerCase().contains("jetbrains"),
                    "Expected JetBrains Mono, got: " + t1.monoFont.getFamily());
            assertTrue(t1.smallFont.getSize2D() >= 11.5f,
                    "smallFont must stay >= 11.5pt for Vietnamese diacritics legibility");

            UiPrefs.setUiFontChoice(prefs, FontManager.UiFontChoice.IBM_PLEX_SANS);
            Theme t2 = Theme.current();
            assertTrue(t2.baseFont.getFamily().toLowerCase().contains("ibm plex"),
                    "Expected IBM Plex Sans after live switch, got: " + t2.baseFont.getFamily());

            UiPrefs.setFontScalePercent(prefs, 120);
            assertEquals(120, UiPrefs.fontScalePercent(prefs));
            UiPrefs.setFontScalePercent(prefs, 115);
            assertEquals(115, UiPrefs.fontScalePercent(prefs));
            UiPrefs.setFontScalePercent(prefs, 110);
            assertEquals(110, UiPrefs.fontScalePercent(prefs));
        });
    }

    @Test
    void pillar2ThreeDisplayModesAndRichHoverCard() throws Exception {
        edt(() -> {
            applyLaf(false);
            JButton btn = Ui.actionButton("Gửi API", Icons::send,
                    "Thực thi API đang chọn qua tất cả Role", "Ctrl+Enter", () -> {});

            UiDisplayMode.set(UiDisplayMode.ICON_AND_TEXT);
            assertEquals("Gửi API", btn.getText());
            assertNotNull(btn.getIcon());

            UiDisplayMode.set(UiDisplayMode.ICON_ONLY);
            assertEquals("", btn.getText(), "ICON_ONLY mode must hide button text");
            assertNotNull(btn.getIcon(), "ICON_ONLY mode must keep icon visible");

            UiDisplayMode.set(UiDisplayMode.TEXT_ONLY);
            assertEquals("Gửi API", btn.getText(), "TEXT_ONLY mode must show label");
            assertNull(btn.getIcon(), "TEXT_ONLY mode must hide icon");

            UiDisplayMode.set(UiDisplayMode.ICON_AND_TEXT);
            assertEquals("Gửi API", btn.getText());
            assertNotNull(btn.getIcon());

            // Verify RichHoverCard renders crisply in Light & Dark
            RichHoverCard.Spec spec = new RichHoverCard.Spec(
                    "Chế độ Tập trung theo Nhánh",
                    "Chỉ hiển thị API thuộc nhánh đang chọn; bấm lại để quay về toàn bộ danh sách.",
                    "Ctrl+Shift+F",
                    t -> Icons.focus(t.info.text(), 15),
                    t -> t.info,
                    () -> "Đang tắt");
            for (boolean dark : new boolean[] {false, true}) {
                applyLaf(dark);
                Component card = RichHoverCard.previewComponent(spec);
                Dimension sz = card.getPreferredSize();
                assertTrue(sz.width >= 200 && sz.height >= 60, "RichHoverCard must have legible dimensions");
                BufferedImage img = renderOffscreen(card, sz.width, sz.height);
                assertNotNull(img);
            }
        });
    }

    @Test
    void pillar3IconPack42AndCustomHexMonogramPng(@TempDir Path dir) throws Exception {
        assertEquals(42, NodeTypeCatalog.VECTOR_META.size(), "Icon pack v2.1 must provide 42 categorized node icons");
        assertEquals(6, NodeTypeCatalog.CATEGORIES.size());

        // Create a tiny real PNG file to test self-contained base64 data URI encoding
        File samplePng = dir.resolve("custom_badge.png").toFile();
        BufferedImage sample = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = sample.createGraphics();
        sg.setColor(Color.ORANGE);
        sg.fillRect(0, 0, 32, 32);
        sg.dispose();
        ImageIO.write(sample, "png", samplePng);

        String dataUri = Icons.encodeImageDataUri(samplePng);
        assertNotNull(dataUri);
        assertTrue(dataUri.startsWith("data:image/png;base64,"), "Custom image must be encoded as self-contained base64 PNG");

        edt(() -> {
            for (boolean dark : new boolean[] {false, true}) {
                applyLaf(dark);
                Theme t = Theme.current();
                BufferedImage sheet = new BufferedImage(480, 120, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = sheet.createGraphics();
                int x = 4;
                for (NodeTypeCatalog.VectorMeta m : NodeTypeCatalog.VECTOR_META) {
                    Icon ic = Icons.vectorIcon(m.id(), t.fg, 16);
                    assertNotNull(ic);
                    assertEquals(16, ic.getIconWidth());
                    ic.paintIcon(null, g2, x % 440, (x / 440) * 24 + 4);
                    x += 22;
                }
                // Verify all 3 monogram styles + custom hex + boxed vector + data URI image
                for (String style : NodeTypeCatalog.MONOGRAM_STYLES) {
                    NodeType monoNt = new NodeType("m_" + style, "Mono", "folder", "info", "VIP", "", false, "#7C3AED", style, false);
                    Icon mic = Icons.nodeIcon(monoNt, t, 16);
                    assertNotNull(mic);
                    mic.paintIcon(null, g2, 10, 90);
                }
                NodeType boxedNt = new NodeType("bx", "Boxed", "shield", "accent", "", "", false, "#0EA5E9", "soft", true);
                Icons.nodeIcon(boxedNt, t, 16).paintIcon(null, g2, 40, 90);

                Icon decodedImg = Icons.imageIcon(dataUri, 16);
                assertNotNull(decodedImg, "Base64 data URI must decode cleanly");
                decodedImg.paintIcon(null, g2, 70, 90);

                // Verify arbitrary Hex token works in Light & Dark
                Theme.Token hexTok = t.tokenForHex("#FF5722");
                assertNotNull(hexTok.text());
                assertNotNull(hexTok.fill());
                g2.dispose();
            }
        });

        // Verify NodeTypeCatalog v2 JSON round-trip and reordering
        NodeTypeCatalog cat = new NodeTypeCatalog();
        NodeType custom = new NodeType("vip_pay", "Thanh toán VIP", "credit-card", "emerald", "", dataUri, false, "#FF5722", "solid", true);
        cat.upsert(custom);
        assertTrue(cat.move("vip_pay", -1), "Moving last item up should succeed");
        String json = cat.toJson();
        NodeTypeCatalog restored = new NodeTypeCatalog();
        restored.loadJson(json);
        NodeType back = restored.byId("vip_pay");
        assertEquals("Thanh toán VIP", back.label());
        assertEquals("#FF5722", back.customHex());
        assertEquals("solid", back.monogramStyle());
        assertTrue(back.boxed());
        assertEquals(dataUri, back.imagePath());
    }

    @Test
    void pillar4DuplicateLineageAndIconPickerPanel(@TempDir Path dir) throws Exception {
        Engine engine = newEngine(Prefs.memory());
        try {
            byte[] req = "GET /api/v1/orders/78 HTTP/1.1\r\nHost: api.example.test\r\n\r\n".getBytes(StandardCharsets.UTF_8);
            byte[] resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"id\":78}".getBytes(StandardCharsets.UTF_8);
            engine.setDestination("Toppage > Orders");
            ApiGroup orig = engine.process(rawMsg(req, resp, "/api/v1/orders/78"), "Proxy", true, engine.activeBranch().id());
            assertNotNull(orig);
            engine.setDestination("Toppage > Cart");
            ApiGroup dup = engine.process(rawMsg(req, resp, "/api/v1/orders/78"), "Proxy", true, engine.activeBranch().id());
            assertNotNull(dup);

            assertEquals(9, Decisions.DUP_COLS.size(), "DUP_COLS must separate Trạng thái & Nhãn (9 columns)");
            assertEquals(7, Decisions.PLACE_UI_COLS.size(), "PLACE_UI_COLS must be clean 7 columns");
            var placeRows = Decisions.placeUiRows(engine.book(), 0, "");
            assertFalse(placeRows.isEmpty());
            assertEquals(7, placeRows.get(0).cells().size());

            Decisions.SmartAdvice adv = Decisions.advise(engine, dup);
            assertFalse(adv.facts().isEmpty(), "SmartAdvice v2 must include structured FactRows");
            assertTrue(adv.explanation().contains("Toppage > Orders"));

            edt(() -> {
                applyLaf(false);
                StructuredAdviceCard card = new StructuredAdviceCard();
                card.setAdvice(adv);
                Dimension sz = card.getPreferredSize();
                assertNotNull(renderOffscreen(card, Math.max(520, sz.width), Math.max(150, sz.height)));

                IconPickerPanel picker = new IconPickerPanel();
                assertEquals(42, picker.filteredVectorCount());
                picker.setCategory("Bảo mật");
                assertEquals(6, picker.filteredVectorCount());
                picker.setCategory("Tất cả");
                picker.setSearchQuery("giỏ hàng");
                assertTrue(picker.filteredVectorCount() >= 1);
                picker.setCustomHex("#0EA5E9");
                NodeType built = picker.buildNodeType("my_cart", "Giỏ hàng", false);
                assertEquals("#0EA5E9", built.customHex());
            });
        } finally {
            engine.close();
        }
    }

    @Test
    void pillar5ActionEngineUpgradesAndWorkbenchDialog(@TempDir Path dir) throws Exception {
        ActionEngine ae = new ActionEngine();
        byte[] prodResp = ("HTTP/1.1 201 Created\r\nLocation: /api/v1/orders/905\r\nContent-Type: application/json\r\n\r\n"
                + "{\"status\":\"ok\",\"data\":{\"order_id\":905,\"items\":[{\"id\":\"sku_77\"}]}}").getBytes(StandardCharsets.UTF_8);

        List<SuggestedPath> suggestions = ActionEngine.suggestJsonPaths(prodResp);
        assertFalse(suggestions.isEmpty(), "Should auto-suggest JSONPath & Location header");
        assertTrue(suggestions.stream().anyMatch(s -> s.expr().equals("header:Location") && s.sampleValue().equals("905")));
        assertTrue(suggestions.stream().anyMatch(s -> s.expr().equals("$.data.order_id") && s.sampleValue().equals("905")));

        assertEquals("905", ActionEngine.extractByExpr(prodResp, "$.data.order_id"));
        assertEquals("sku_77", ActionEngine.extractByExpr(prodResp, "$.data.items[0].id"));
        assertEquals("905", ActionEngine.extractByExpr(prodResp, "header:Location"));
        assertEquals("905", ActionEngine.extractByExpr(prodResp, "regex:\"order_id\":(\\d+)"));

        byte[] delReq = ("DELETE /api/v1/orders/78?order_id=78 HTTP/1.1\r\nHost: api.example.test\r\nX-Order-Id: 78\r\n\r\n"
                + "{\"order_id\":78}").getBytes(StandardCharsets.UTF_8);
        SwapPreview pv = ActionEngine.previewSwap(delReq, "78", "905", true, true, true, true);
        assertEquals(4, pv.replacements());
        assertTrue(pv.afterRequestLine().contains("/api/v1/orders/905?order_id=905"));
        assertTrue(pv.afterBodySnippet().contains("905"));
        assertTrue(pv.locations().containsAll(List.of("URL Path", "Query", "Body", "Header")));

        // Pool bulk, consume/restore, CSV export/import
        assertEquals(3, ae.addBulkLiveIds("orders", "101, 102 103"));
        ae.markConsumed("101");
        assertTrue(ae.isConsumed("101"));
        String csv = ae.exportPoolCsv();
        assertTrue(csv.contains("orders,101,consumed"));
        assertTrue(csv.contains("orders,102,alive"));

        ActionEngine ae2 = new ActionEngine();
        assertEquals(3, ae2.importPoolCsv(csv));
        assertTrue(ae2.isConsumed("101"));
        ae2.restoreConsumed("101");
        assertFalse(ae2.isConsumed("101"));
        assertTrue(ae2.removeLiveId("orders", "103"));

        // Workbench UI + streamlined ActionMenus
        Engine engine = newEngine(Prefs.memory());
        try {
            byte[] postReq = "POST /api/v1/orders HTTP/1.1\r\nHost: api.example.test\r\n\r\n{\"sku\":\"A1\"}".getBytes(StandardCharsets.UTF_8);
            engine.setDestination("Toppage > Orders");
            engine.process(rawMsg(postReq, prodResp, "/api/v1/orders"), "Proxy", true, engine.activeBranch().id());
            ApiGroup delGroup = engine.process(rawMsg(delReq,
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"deleted\":true}".getBytes(StandardCharsets.UTF_8),
                    "/api/v1/orders/78?order_id=78"), "Proxy", true, engine.activeBranch().id());
            assertNotNull(delGroup);

            edt(() -> {
                applyLaf(false);
                JMenu m = ActionMenus.buildForApi(null, engine, delGroup, null, null);
                assertTrue(m.getItemCount() <= 9, "Streamlined ActionMenus must be concise, got " + m.getItemCount());
                assertTrue(m.getItem(2).getText().contains("Luật chặn"));

                for (boolean dark : new boolean[] {false, true}) {
                    applyLaf(dark);
                    JPanel wb = ActionWorkbenchDialog.buildWorkbenchPanel(null, engine, delGroup, null, 0, null, null);
                    assertNotNull(renderOffscreen(wb, 960, 620));
                }
            });
        } finally {
            engine.close();
        }
    }

    @Test
    void visualVerificationSnapshots(@TempDir Path dir) throws Exception {
        Path outDir = Path.of("/tmp/v21_verify");
        Files.createDirectories(outDir);

        Prefs prefs = Prefs.memory();
        prefs.set(Workspaces.PREF_ROOT, dir.toString());
        Workspaces ws = new Workspaces(prefs, null, null);
        Engine engine = newEngine(prefs);
        try {
            byte[] postReq = "POST /api/v1/orders HTTP/1.1\r\nHost: api.example.test\r\n\r\n{\"sku\":\"A1\"}".getBytes(StandardCharsets.UTF_8);
            byte[] prodResp = ("HTTP/1.1 201 Created\r\nLocation: /api/v1/orders/905\r\nContent-Type: application/json\r\n\r\n"
                    + "{\"id\":905,\"data\":{\"order_id\":905}}").getBytes(StandardCharsets.UTF_8);
            byte[] delReq = "DELETE /api/v1/orders/78 HTTP/1.1\r\nHost: api.example.test\r\n\r\n{\"id\":78}".getBytes(StandardCharsets.UTF_8);
            byte[] okResp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            engine.setDestination("Toppage > Orders");
            engine.process(rawMsg(postReq, prodResp, "/api/v1/orders"), "Proxy", true, engine.activeBranch().id());
            ApiGroup del = engine.process(rawMsg(delReq, okResp, "/api/v1/orders/78"), "Proxy", true, engine.activeBranch().id());
            assertNotNull(del);
            engine.setDestination("Toppage > Cart");
            engine.process(rawMsg(delReq, okResp, "/api/v1/orders/78"), "Proxy", true, engine.activeBranch().id());
            engine.actionEngine().addBulkLiveIds("orders", "901, 902, 903");
            engine.actionEngine().markConsumed("78");

            edt(() -> {
                applyLaf(false);
                MainPanel mp = new MainPanel(engine, ws, new UiSmokeTest.HeadlessHost(), "v21-test");
                try {
                    // 1. MainPanel Light — ICON_AND_TEXT
                    UiDisplayMode.set(UiDisplayMode.ICON_AND_TEXT);
                    mp.tick(true);
                    savePng(renderOffscreen(mp.root(), 1240, 720), outDir.resolve("01_main_light_icon_text.png"));

                    // 2. MainPanel Light — ICON_ONLY
                    UiDisplayMode.set(UiDisplayMode.ICON_ONLY);
                    savePng(renderOffscreen(mp.root(), 1240, 720), outDir.resolve("02_main_light_icon_only.png"));

                    // 3. MainPanel Light — TEXT_ONLY
                    UiDisplayMode.set(UiDisplayMode.TEXT_ONLY);
                    savePng(renderOffscreen(mp.root(), 1240, 720), outDir.resolve("03_main_light_text_only.png"));

                    // 4. Audit Duplicate tab Light
                    UiDisplayMode.set(UiDisplayMode.ICON_AND_TEXT);
                    mp.audit.show(2, null);
                    mp.audit.reload();
                    JTable decide = mp.audit.decideTable();
                    assertEquals(JTable.AUTO_RESIZE_OFF, decide.getAutoResizeMode());
                    savePng(renderOffscreen(mp.root(), 1240, 720), outDir.resolve("04_audit_dup_light.png"));

                    // 5. Settings tab Light
                    mp.selectTab(mp.settings.panel);
                    mp.settings.load();
                    savePng(renderOffscreen(mp.root(), 1240, 720), outDir.resolve("05_settings_light.png"));

                    // 6. IconPickerPanel Light
                    IconPickerPanel picker = new IconPickerPanel();
                    picker.setCustomHex("#0EA5E9");
                    savePng(renderOffscreen(picker, 560, 420), outDir.resolve("06_icon_picker_light.png"));

                    // 7. Action Workbench Light (Strategy + Chain)
                    JPanel wb0 = ActionWorkbenchDialog.buildWorkbenchPanel(null, engine, del, null, 0, null, null);
                    savePng(renderOffscreen(wb0, 960, 620), outDir.resolve("07_action_workbench_strategy_light.png"));
                    JPanel wb2 = ActionWorkbenchDialog.buildWorkbenchPanel(null, engine, del, null, 2, null, null);
                    savePng(renderOffscreen(wb2, 960, 620), outDir.resolve("08_action_workbench_chain_light.png"));

                    // 8. Dark theme verification (Main + Action Workbench + RichHoverCard)
                    applyLaf(true);
                    mp.selectTab(mp.api.panel);
                    savePng(renderOffscreen(mp.root(), 1240, 720), outDir.resolve("09_main_dark.png"));
                    JPanel wbDark = ActionWorkbenchDialog.buildWorkbenchPanel(null, engine, del, null, 0, null, null);
                    savePng(renderOffscreen(wbDark, 960, 620), outDir.resolve("10_action_workbench_dark.png"));

                    Component hover = RichHoverCard.previewComponent(new RichHoverCard.Spec(
                            "Action Workbench (Bảo vệ Action API)",
                            "Mở giao diện trực quan 4 tab: Chiến lược xử lý, Kho ID Sống, Chuỗi Tạo Mới -> Hành Động và Khóa 200 OK.",
                            "Ctrl+Shift+A",
                            t -> Icons.workbench(t.accent.text(), 16),
                            t -> t.accent,
                            () -> "Đang dùng: Tự động thông minh"));
                    Dimension hs = hover.getPreferredSize();
                    savePng(renderOffscreen(hover, hs.width, hs.height), outDir.resolve("11_rich_hover_dark.png"));
                } finally {
                    mp.dispose();
                }
            });
        } finally {
            engine.close();
        }
    }

    private static void applyLaf(boolean dark) {
        try {
            UIManager.setLookAndFeel(dark ? new FlatDarkLaf() : new FlatLightLaf());
            Theme.onThemeChanged();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static BufferedImage renderOffscreen(Component c, int w, int h) {
        c.setSize(w, h);
        layoutTree(c);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Theme.current().bg);
        g2.fillRect(0, 0, w, h);
        c.paint(g2);
        g2.dispose();
        return img;
    }

    private static void layoutTree(Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container ct) {
            for (Component ch : ct.getComponents()) {
                layoutTree(ch);
            }
        }
    }

    private static void savePng(BufferedImage img, Path path) {
        try {
            ImageIO.write(img, "png", path.toFile());
        } catch (Exception e) {
            throw new RuntimeException(e);
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
            if (err.get() instanceof Exception ex) throw ex;
            throw new RuntimeException(err.get());
        }
    }
}
