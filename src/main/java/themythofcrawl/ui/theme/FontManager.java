package themythofcrawl.ui.theme;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

import javax.swing.JTextArea;
import javax.swing.text.JTextComponent;

/**
 * Bundled SIL OFL 1.1 typography manager for Vietnamese-first modern UI rendering:
 * Be Vietnam Pro (default), IBM Plex Sans (compact technical alternative), JetBrains Mono
 * (HTTP/URL/JSON/ID), plus fallback to host Burp system fonts.
 */
public final class FontManager {
    public enum UiFontChoice {
        BE_VIETNAM_PRO("be_vietnam_pro", "Be Vietnam Pro (Mặc định - Tiếng Việt hiện đại)"),
        IBM_PLEX_SANS("ibm_plex_sans", "IBM Plex Sans (Gọn kỹ thuật, tối giản)"),
        SYSTEM("system", "Font hệ thống Burp (Mặc định OS)");

        public final String id;
        public final String label;

        UiFontChoice(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static UiFontChoice fromId(String id) {
            if (id != null) {
                for (UiFontChoice c : values()) {
                    if (c.id.equalsIgnoreCase(id.trim())) {
                        return c;
                    }
                }
            }
            return BE_VIETNAM_PRO;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum MonoFontChoice {
        JETBRAINS_MONO("jetbrains_mono", "JetBrains Mono (Khuyên dùng - URL / JSON / ID)"),
        SYSTEM("system", "Monospace hệ thống");

        public final String id;
        public final String label;

        MonoFontChoice(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static MonoFontChoice fromId(String id) {
            if (id != null) {
                for (MonoFontChoice c : values()) {
                    if (c.id.equalsIgnoreCase(id.trim())) {
                        return c;
                    }
                }
            }
            return JETBRAINS_MONO;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum Asset {
        BE_REG("/fonts/BeVietnamPro-Regular.ttf"),
        BE_MED("/fonts/BeVietnamPro-Medium.ttf"),
        BE_SEMI("/fonts/BeVietnamPro-SemiBold.ttf"),
        IBM_REG("/fonts/IBMPlexSans-Regular.ttf"),
        IBM_SEMI("/fonts/IBMPlexSans-SemiBold.ttf"),
        JB_REG("/fonts/JetBrainsMono-Regular.ttf"),
        JB_SEMI("/fonts/JetBrainsMono-SemiBold.ttf");

        final String path;

        Asset(String path) {
            this.path = path;
        }
    }

    private static final Map<Asset, Font> LOADED = new EnumMap<>(Asset.class);
    private static volatile UiFontChoice uiChoice = UiFontChoice.BE_VIETNAM_PRO;
    private static volatile MonoFontChoice monoChoice = MonoFontChoice.JETBRAINS_MONO;
    private static volatile boolean registered;

    private FontManager() {
    }

    public static UiFontChoice uiChoice() {
        return uiChoice;
    }

    public static MonoFontChoice monoChoice() {
        return monoChoice;
    }

    public static void setUiChoice(UiFontChoice choice) {
        UiFontChoice next = choice == null ? UiFontChoice.BE_VIETNAM_PRO : choice;
        if (uiChoice != next) {
            uiChoice = next;
            Theme.onThemeChanged();
        }
    }

    public static void setMonoChoice(MonoFontChoice choice) {
        MonoFontChoice next = choice == null ? MonoFontChoice.JETBRAINS_MONO : choice;
        if (monoChoice != next) {
            monoChoice = next;
            Theme.onThemeChanged();
        }
    }

    private static synchronized Font load(Asset a) {
        if (LOADED.containsKey(a)) {
            return LOADED.get(a);
        }
        Font f = null;
        try (InputStream is = FontManager.class.getResourceAsStream(a.path)) {
            if (is != null) {
                f = Font.createFont(Font.TRUETYPE_FONT, is);
                registerSafe(f);
            }
        } catch (Throwable ignored) {
            f = null;
        }
        LOADED.put(a, f);
        return f;
    }

    private static void registerSafe(Font f) {
        if (f == null) {
            return;
        }
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
            registered = true;
        } catch (Throwable ignored) {
            // Headless or restricted security manager
        }
    }

    public static boolean bundledFontsAvailable() {
        return load(Asset.BE_REG) != null && load(Asset.JB_REG) != null;
    }

    public static Font uiRegular(Font systemFallback, float pt) {
        Font base = switch (uiChoice) {
            case BE_VIETNAM_PRO -> load(Asset.BE_REG);
            case IBM_PLEX_SANS -> load(Asset.IBM_REG);
            case SYSTEM -> null;
        };
        if (base != null) {
            return base.deriveFont(Font.PLAIN, pt);
        }
        Font fb = systemFallback != null ? systemFallback : new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(pt));
        return fb.deriveFont(Font.PLAIN, pt);
    }

    public static Font uiMedium(Font systemFallback, float pt) {
        Font base = switch (uiChoice) {
            case BE_VIETNAM_PRO -> load(Asset.BE_MED);
            case IBM_PLEX_SANS -> load(Asset.IBM_SEMI);
            case SYSTEM -> null;
        };
        if (base != null) {
            return base.deriveFont(Font.PLAIN, pt);
        }
        return uiBold(systemFallback, pt);
    }

    public static Font uiBold(Font systemFallback, float pt) {
        Font base = switch (uiChoice) {
            case BE_VIETNAM_PRO -> load(Asset.BE_SEMI);
            case IBM_PLEX_SANS -> load(Asset.IBM_SEMI);
            case SYSTEM -> null;
        };
        if (base != null) {
            return base.deriveFont(Font.PLAIN, pt);
        }
        Font fb = systemFallback != null ? systemFallback : new Font(Font.SANS_SERIF, Font.BOLD, Math.round(pt));
        return fb.deriveFont(Font.BOLD, pt);
    }

    public static Font monoRegular(Font systemFallback, float pt) {
        Font base = monoChoice == MonoFontChoice.JETBRAINS_MONO ? load(Asset.JB_REG) : null;
        if (base != null) {
            return base.deriveFont(Font.PLAIN, pt);
        }
        Font fb = systemFallback != null ? systemFallback : new Font(Font.MONOSPACED, Font.PLAIN, Math.round(pt));
        return fb.deriveFont(Font.PLAIN, pt);
    }

    public static Font monoBold(Font systemFallback, float pt) {
        Font base = monoChoice == MonoFontChoice.JETBRAINS_MONO ? load(Asset.JB_SEMI) : null;
        if (base != null) {
            return base.deriveFont(Font.PLAIN, pt);
        }
        Font fb = systemFallback != null ? systemFallback : new Font(Font.MONOSPACED, Font.BOLD, Math.round(pt));
        return fb.deriveFont(Font.BOLD, pt);
    }

    /** Applies crisp LCD subpixel antialiasing hints to custom Java2D text painting. */
    public static void applyTextHints(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    }

    /**
     * Walks a Swing subtree and applies the active {@link Theme} typography to standard controls
     * while preserving explicit bold/small/mono derivations.
     */
    public static void applyTreeFonts(Component root, Theme th) {
        if (root == null || th == null) {
            return;
        }
        Font cur = root.getFont();
        if (cur != null) {
            boolean isMono = isMonospace(cur) || (root instanceof JTextArea && Boolean.TRUE.equals(((JTextArea) root).getClientProperty("tmoc.mono")));
            if (isMono) {
                root.setFont(cur.isBold() ? th.monoBoldFont : th.monoFont);
            } else if (cur.getSize2D() >= th.baseFont.getSize2D() + 1.5f) {
                root.setFont(th.headingFont);
            } else if (cur.getSize2D() <= th.smallFont.getSize2D() + 0.1f && cur.getSize2D() < th.baseFont.getSize2D() - 0.4f) {
                root.setFont(cur.isBold() ? th.smallBoldFont : th.smallFont);
            } else if (cur.isBold()) {
                root.setFont(th.boldFont);
            } else if (cur.isItalic()) {
                root.setFont(th.italicFont);
            } else if (!(root instanceof JTextComponent) || cur.getFamily().equalsIgnoreCase(Font.DIALOG)
                    || cur.getFamily().contains("Be Vietnam") || cur.getFamily().contains("IBM Plex")
                    || cur.getFamily().contains("DejaVu") || cur.getFamily().contains("Sans")) {
                root.setFont(th.baseFont);
            }
        }
        if (root instanceof Container cont) {
            for (Component child : cont.getComponents()) {
                applyTreeFonts(child, th);
            }
        }
    }

    private static boolean isMonospace(Font f) {
        String fam = f.getFamily();
        if (fam == null) {
            return false;
        }
        String low = fam.toLowerCase(java.util.Locale.ROOT);
        return low.contains("mono") || low.contains("courier") || low.contains("consolas") || low.contains("menlo");
    }

    public static String summary() {
        return uiChoice.label + " / " + monoChoice.label + (registered ? " (bundled)" : "");
    }
}
