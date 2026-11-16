package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.junit.jupiter.api.Test;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.model.RuleMode;
import themythofcrawl.ui.kit.Breadcrumb;
import themythofcrawl.ui.kit.Card;
import themythofcrawl.ui.kit.EmptyState;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SearchField;
import themythofcrawl.ui.kit.SectionHeader;
import themythofcrawl.ui.kit.SegmentedToggle;
import themythofcrawl.ui.kit.StatCard;
import themythofcrawl.ui.kit.StatusDot;
import themythofcrawl.ui.kit.ToolbarGroup;
import themythofcrawl.ui.theme.Theme;

/**
 * Verifies Phase 1 design system: WCAG AA contrast ratios on light &amp; dark surfaces,
 * dynamic theme refresh, Palette delegation, all shared kit components &amp; Java2D icons,
 * and UI source hygiene (zero FlatLaf runtime imports / raw Color / emoji literals).
 */
class ThemeContrastTest {
    private static final Color[] BACKGROUNDS = {
        new Color(0xFF, 0xFF, 0xFF),
        new Color(0xF2, 0xF2, 0xF2),
        new Color(0x2B, 0x2B, 0x2B),
        new Color(0x1E, 0x1F, 0x22)
    };

    @Test
    void allTokensMeetWcagContrastOnLightAndDarkSurfaces() {
        Color[] accents = {
            new Color(0x25, 0x63, 0xEB),
            new Color(0xEA, 0x58, 0x0C),
            new Color(0xD9, 0x77, 0x06)
        };
        for (Color bg : BACKGROUNDS) {
            boolean dark = Theme.isDark(bg);
            Color fg = dark ? new Color(0xE2, 0xE8, 0xF0) : new Color(0x1E, 0x29, 0x3B);
            for (Color accent : accents) {
                Theme th = Theme.of(bg, fg, bg, accent, accent, null, null);
                assertEquals(dark, th.dark, "dark detection mismatch for " + bg);
                assertTrue(Theme.contrast(th.fg, th.bg) >= 4.5, "fg contrast on bg");
                assertTrue(Theme.contrast(th.muted, th.bg) >= 3.0, "muted contrast on bg");
                assertTrue(Theme.contrast(th.muted, th.surface) >= 3.0, "muted contrast on surface");
                assertTrue(Theme.contrast(th.muted, th.surfaceAlt) >= 3.0, "muted contrast on surfaceAlt");

                List<Theme.Token> tokens = new ArrayList<>(List.of(
                        th.success, th.warning, th.danger, th.critical, th.info, th.pending, th.dup,
                        th.ghost, th.accent, th.orig, th.pink, th.teal, th.orange, th.slate, th.neutral));
                for (AuditLevel lvl : AuditLevel.values()) {
                    tokens.add(th.audit(lvl));
                }
                for (RuleMode mode : RuleMode.values()) {
                    tokens.add(th.mode(mode));
                }
                for (String m : new String[] {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"}) {
                    tokens.add(th.method(m));
                }
                for (int d = 1; d <= Theme.LEVEL_HUES.length; d++) {
                    tokens.add(th.level(d));
                }
                for (Theme.Token tok : tokens) {
                    assertTrue(Theme.contrast(tok.text(), tok.fill()) >= 4.5,
                            "token text vs fill failed on bg=" + Integer.toHexString(bg.getRGB()));
                    assertTrue(Theme.contrast(tok.text(), tok.fillStrong()) >= 4.5,
                            "token text vs fillStrong failed on bg=" + Integer.toHexString(bg.getRGB()));
                    assertTrue(Theme.contrast(tok.text(), th.surface) >= 4.5,
                            "token text vs surface failed on bg=" + Integer.toHexString(bg.getRGB()));
                    assertTrue(Theme.contrast(tok.text(), th.surfaceAlt) >= 4.5,
                            "token text vs surfaceAlt failed on bg=" + Integer.toHexString(bg.getRGB()));
                    assertTrue(Theme.contrast(tok.text(), th.bg) >= 4.5,
                            "token text vs bg failed on bg=" + Integer.toHexString(bg.getRGB()));
                    assertTrue(Theme.contrast(tok.onSolid(), tok.solidFill()) >= 4.5,
                            "token onSolid vs solidFill failed on bg=" + Integer.toHexString(bg.getRGB()));
                }
            }
        }
    }

    @Test
    void paletteDelegatesToTheme() {
        for (Color bg : BACKGROUNDS) {
            Color fg = Theme.isDark(bg) ? Color.WHITE : Color.BLACK;
            Theme th = Theme.of(bg, fg);
            Palette pal = Palette.of(bg, fg);
            assertEquals(th.dark, pal.dark);
            assertEquals(th.fg, pal.fg);
            assertEquals(th.bg, pal.bg);
            assertEquals(th.muted, pal.muted);
            assertEquals(th.audit(AuditLevel.WARN).text(), pal.auditFg(AuditLevel.WARN));
            assertEquals(th.method("DELETE").text(), pal.methodFg("DELETE"));
        }
    }

    @Test
    void themeRefreshesCacheAndTrackedComponentsOnUiChange() throws Exception {
        Color origBg = UIManager.getColor("Panel.background");
        Color origFg = UIManager.getColor("Label.foreground");
        Color origTblBg = UIManager.getColor("Table.background");
        try {
            SwingUtilities.invokeAndWait(() -> {
                JPanel root = new JPanel();
                Pill pill = new Pill("OK", t -> t.success, t -> Icons.check(t.success.text()));
                root.add(pill);
                Theme.track(root);

                UIManager.put("Panel.background", new Color(0xFF, 0xFF, 0xFF));
                UIManager.put("Table.background", new Color(0xFF, 0xFF, 0xFF));
                UIManager.put("Label.foreground", new Color(0x1E, 0x1E, 0x1E));
                Theme.onThemeChanged();
                Theme light = Theme.current();
                assertFalse(light.dark);

                UIManager.put("Panel.background", new Color(0x1E, 0x1F, 0x22));
                UIManager.put("Table.background", new Color(0x1E, 0x1F, 0x22));
                UIManager.put("Label.foreground", new Color(0xE2, 0xE8, 0xF0));
                Theme.onThemeChanged();
                Theme dark = Theme.current();
                assertTrue(dark.dark);
                assertNotSame(light, dark);
            });
        } finally {
            UIManager.put("Panel.background", origBg);
            UIManager.put("Label.foreground", origFg);
            UIManager.put("Table.background", origTblBg);
            Theme.onThemeChanged();
        }
    }

    @Test
    void kitComponentsAndJava2dIconsRenderWithoutErrors() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BufferedImage img = new BufferedImage(360, 120, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                Pill pill = new Pill("WARN", t -> t.critical, t -> Icons.warn(t.critical.text()));
                Pill badge = Pill.badge("GET", t -> t.method("GET"));
                StatusDot dot = new StatusDot(t -> t.danger.base());
                Card card = new Card("Header", "Subtitle", badge, t -> t.info, true);
                SectionHeader sec = new SectionHeader("Section", "Muted description");
                ToolbarGroup tg = new ToolbarGroup(new JButton("Action")).addPrimary(new JButton("Primary"), t -> t.accent);
                SegmentedToggle toggle = new SegmentedToggle(
                        new SegmentedToggle.Option("CHỈ CRAWL", null, t -> t.slate, t -> Icons.hide(t.fg)),
                        new SegmentedToggle.Option("GỬI", null, t -> t.success, t -> Icons.send(t.fg)));
                SearchField search = new SearchField("Tìm kiếm...");
                StatCard stat = new StatCard("Đã bắt", "4/10", "40 %", t -> t.info, 0.4);
                EmptyState empty = new EmptyState(t -> Icons.excel(t.muted, 28), "Chưa có dữ liệu", "Nạp file", new JButton("Nạp"));
                Breadcrumb bc = new Breadcrumb("Toppage > auth > login").onDropdown(() -> { });

                for (java.awt.Component c : new java.awt.Component[] {
                        pill, badge, dot, card, sec, tg, toggle, search, stat, empty, bc}) {
                    c.setSize(320, 96);
                    c.doLayout();
                    c.paint(g);
                    SwingUtilities.updateComponentTreeUI(c);
                }

                Theme t = Theme.current();
                List<Icon> icons = new ArrayList<>(List.of(
                        Icons.dot(t.success.base(), false), Icons.dot(t.info.base(), true),
                        Icons.statusDot(t.danger.base(), 8), Icons.circle(t.accent.text(), true),
                        Icons.copy(t.muted), Icons.group(t.warning.base(), t.muted),
                        Icons.send(t.fg), Icons.sendAll(t.fg), Icons.stop(t.danger.text()),
                        Icons.repeater(t.fg), Icons.folder(t.fg), Icons.api(t.fg), Icons.role(t.fg),
                        Icons.check(t.success.text()), Icons.warn(t.critical.text()), Icons.alertMark(t.warning.text(), 14),
                        Icons.error(t.danger.text()), Icons.info(t.info.text()), Icons.redirect(t.info.text()),
                        Icons.hourglass(t.pending.text()), Icons.ghost(t.muted), Icons.dup(t.dup.text()),
                        Icons.hide(t.muted), Icons.show(t.fg), Icons.trash(t.danger.text()),
                        Icons.move(t.info.text()), Icons.undo(t.fg), Icons.filter(t.fg),
                        Icons.export(t.fg), Icons.excel(t.success.text()), Icons.document(t.fg),
                        Icons.save(t.fg), Icons.window(t.fg), Icons.pin(t.orig.text()),
                        Icons.gear(t.fg), Icons.refresh(t.fg), Icons.plus(t.success.text()),
                        Icons.minus(t.fg), Icons.search(t.muted), Icons.target(t.fg),
                        Icons.sliders(t.fg), Icons.edit(t.fg), Icons.branch(t.fg),
                        Icons.arrowUp(t.fg), Icons.arrowDown(t.fg), Icons.arrowRight(t.fg),
                        Icons.compass(t.info.text()), Icons.clock(t.muted), Icons.bolt(t.warning.text()),
                        Icons.globe(t.info.text()), Icons.clean(t.danger.text()),
                        Icons.chevronDown(t.fg), Icons.chevronRight(t.fg), Icons.chevronLeft(t.fg, 12),
                        Icons.codeBraces(t.fg), Icons.withChevron(Icons.gear(t.fg), t.fg)));
                for (AuditLevel lvl : AuditLevel.values()) {
                    icons.add(Icons.forAudit(lvl, t.audit(lvl).text()));
                }
                for (Icon ic : icons) {
                    assertTrue(ic.getIconWidth() > 0 && ic.getIconHeight() > 0);
                    ic.paintIcon(pill, g, 4, 4);
                }
            } finally {
                g.dispose();
            }
        });
    }

    @Test
    void uiSourcesContainZeroFlatLafImportsRawColorsPaletteOrLiteralEmoji() throws IOException {
        Path uiRoot = Path.of("src/main/java/themythofcrawl/ui");
        assertTrue(Files.isDirectory(uiRoot), "expected ui source root");
        try (Stream<Path> walk = Files.walk(uiRoot)) {
            for (Path p : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                String name = p.getFileName().toString();
                String content = Files.readString(p, StandardCharsets.UTF_8);
                assertFalse(content.contains("import com.formdev.flatlaf"),
                        name + " must never import com.formdev.flatlaf");
                if (!"Theme.java".equals(name)) {
                    assertFalse(content.contains("new Color("),
                            name + " must use Theme tokens instead of raw new Color(");
                }
                if (!"Palette.java".equals(name)) {
                    assertFalse(content.contains("Palette."),
                            name + " must use Theme directly instead of legacy Palette");
                }
                for (int i = 0; i < content.length(); i++) {
                    int cp = content.codePointAt(i);
                    assertFalse(cp >= 0x2600 || (cp >= 0x2190 && cp <= 0x25FF),
                            name + " contains literal symbol/emoji U+" + Integer.toHexString(cp));
                    if (Character.isSupplementaryCodePoint(cp)) {
                        i++;
                    }
                }
            }
        }
    }
}
