package themythofcrawl.ui;

import java.awt.Dimension;
import java.lang.reflect.Field;
import java.util.prefs.Preferences;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.kit.ToastPane;
import themythofcrawl.ui.theme.Theme;

/**
 * UI preference keys and resolution over the shared {@link Prefs} store without modifying
 * non-UI packages. Falls back to JVM user {@link Preferences} outside managed workspaces.
 */
public final class UiPrefs {
    public static final String KEY_ANIMATIONS = "tmoc.ui.animations";
    public static final String KEY_FONT_SCALE = "tmoc.ui.fontScale";
    public static final String KEY_FONT_UI = "tmoc.ui.fontUi";
    public static final String KEY_FONT_MONO = "tmoc.ui.fontMono";
    public static final String KEY_DISPLAY_MODE = "tmoc.ui.displayMode";
    public static final String KEY_TOP_COMPACT = "tmoc.ui.topCompact";
    public static final String KEY_INSPECTOR_EXPANDED = "tmoc.ui.inspectorExpanded";
    public static final String KEY_LINEAGE_W = "tmoc.ui.lineage.width";
    public static final String KEY_LINEAGE_H = "tmoc.ui.lineage.height";
    public static final String KEY_INTERCEPT_W = "tmoc.ui.intercept.width";
    public static final String KEY_INTERCEPT_H = "tmoc.ui.intercept.height";
    public static final String KEY_COMPOSER_W = "tmoc.ui.composer.width";
    public static final String KEY_COMPOSER_H = "tmoc.ui.composer.height";
    public static final String KEY_COLUMNS_PREFIX = "tmoc.ui.columns.";

    public static final int[] FONT_SCALE_CHOICES = {90, 100, 110, 115, 120};

    private UiPrefs() {
    }

    /** Resolves the backing {@link Prefs} store from {@code Workspaces} or {@code Engine}. */
    public static Prefs resolve(Workspaces ws, Engine engine) {
        Prefs p = extractField(ws, Workspaces.class, "prefs");
        if (p != null) {
            return p;
        }
        p = extractField(engine, Engine.class, "prefs");
        if (p != null) {
            return p;
        }
        return jvmNodePrefs();
    }

    private static Prefs extractField(Object target, Class<?> owner, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            Field f = owner.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object val = f.get(target);
            if (val instanceof Prefs p) {
                return p;
            }
        } catch (Throwable ignored) {
            // Fall through cleanly if restricted
        }
        return null;
    }

    private static Prefs jvmNodePrefs() {
        try {
            Preferences node = Preferences.userNodeForPackage(MainPanel.class);
            return new Prefs() {
                @Override
                public String get(String key) {
                    return node.get(key, null);
                }

                @Override
                public void set(String key, String value) {
                    if (value == null) {
                        node.remove(key);
                    } else {
                        node.put(key, value);
                    }
                }
            };
        } catch (Throwable ignored) {
            return Prefs.memory();
        }
    }

    public static boolean animationsEnabled(Prefs prefs) {
        if (prefs == null) {
            return true;
        }
        String raw = prefs.get(KEY_ANIMATIONS);
        return raw == null || !raw.equalsIgnoreCase("false");
    }

    public static void setAnimationsEnabled(Prefs prefs, boolean enabled) {
        if (prefs != null) {
            prefs.set(KEY_ANIMATIONS, Boolean.toString(enabled));
        }
        ToastPane.setAnimationsEnabled(enabled);
    }

    public static int fontScalePercent(Prefs prefs) {
        if (prefs == null) {
            return 100;
        }
        String raw = prefs.get(KEY_FONT_SCALE);
        if (raw == null || raw.isBlank()) {
            return 100;
        }
        try {
            int pct = Integer.parseInt(raw.trim().replace("%", ""));
            return snapScale(pct);
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    private static int snapScale(int pct) {
        int best = 100;
        int bestDist = Integer.MAX_VALUE;
        for (int c : FONT_SCALE_CHOICES) {
            int d = Math.abs(c - pct);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    public static void setFontScalePercent(Prefs prefs, int percent) {
        int clamped = snapScale(percent);
        if (prefs != null) {
            prefs.set(KEY_FONT_SCALE, Integer.toString(clamped));
        }
        Theme.setFontScaleMultiplier(clamped / 100f);
    }

    public static themythofcrawl.ui.theme.FontManager.UiFontChoice uiFontChoice(Prefs prefs) {
        String raw = prefs != null ? prefs.get(KEY_FONT_UI) : null;
        return themythofcrawl.ui.theme.FontManager.UiFontChoice.fromId(raw);
    }

    public static void setUiFontChoice(Prefs prefs, themythofcrawl.ui.theme.FontManager.UiFontChoice choice) {
        themythofcrawl.ui.theme.FontManager.UiFontChoice c = choice == null
                ? themythofcrawl.ui.theme.FontManager.UiFontChoice.BE_VIETNAM_PRO : choice;
        if (prefs != null) {
            prefs.set(KEY_FONT_UI, c.id);
        }
        themythofcrawl.ui.theme.FontManager.setUiChoice(c);
    }

    public static themythofcrawl.ui.theme.FontManager.MonoFontChoice monoFontChoice(Prefs prefs) {
        String raw = prefs != null ? prefs.get(KEY_FONT_MONO) : null;
        return themythofcrawl.ui.theme.FontManager.MonoFontChoice.fromId(raw);
    }

    public static void setMonoFontChoice(Prefs prefs, themythofcrawl.ui.theme.FontManager.MonoFontChoice choice) {
        themythofcrawl.ui.theme.FontManager.MonoFontChoice c = choice == null
                ? themythofcrawl.ui.theme.FontManager.MonoFontChoice.JETBRAINS_MONO : choice;
        if (prefs != null) {
            prefs.set(KEY_FONT_MONO, c.id);
        }
        themythofcrawl.ui.theme.FontManager.setMonoChoice(c);
    }

    public static UiDisplayMode displayMode(Prefs prefs) {
        String raw = prefs != null ? prefs.get(KEY_DISPLAY_MODE) : null;
        return UiDisplayMode.fromId(raw);
    }

    public static void setDisplayMode(Prefs prefs, UiDisplayMode mode) {
        UiDisplayMode m = mode == null ? UiDisplayMode.ICON_AND_TEXT : mode;
        if (prefs != null) {
            prefs.set(KEY_DISPLAY_MODE, m.id);
        }
        UiDisplayMode.set(m);
    }

    public static boolean topCompact(Prefs prefs) {
        if (prefs == null) {
            return false;
        }
        return "true".equalsIgnoreCase(prefs.get(KEY_TOP_COMPACT));
    }

    public static void setTopCompact(Prefs prefs, boolean compact) {
        if (prefs != null) {
            prefs.set(KEY_TOP_COMPACT, Boolean.toString(compact));
        }
    }

    public static boolean inspectorExpanded(Prefs prefs) {
        if (prefs == null) {
            return false;
        }
        return "true".equalsIgnoreCase(prefs.get(KEY_INSPECTOR_EXPANDED));
    }

    public static void setInspectorExpanded(Prefs prefs, boolean expanded) {
        if (prefs != null) {
            prefs.set(KEY_INSPECTOR_EXPANDED, Boolean.toString(expanded));
        }
    }

    public static Dimension lineageSize(Prefs prefs) {
        int defW = 1040;
        int defH = 620;
        if (prefs == null) {
            return new Dimension(defW, defH);
        }
        int w = parseClampedInt(prefs.get(KEY_LINEAGE_W), defW, 860, 2400);
        int h = parseClampedInt(prefs.get(KEY_LINEAGE_H), defH, 520, 1400);
        return new Dimension(w, h);
    }

    public static void setLineageSize(Prefs prefs, int width, int height) {
        if (prefs == null || width < 700 || height < 420) {
            return;
        }
        prefs.set(KEY_LINEAGE_W, Integer.toString(Math.max(860, Math.min(2400, width))));
        prefs.set(KEY_LINEAGE_H, Integer.toString(Math.max(520, Math.min(1400, height))));
    }

    public static Dimension interceptSize(Prefs prefs) {
        int defW = 1060;
        int defH = 640;
        if (prefs == null) {
            return new Dimension(defW, defH);
        }
        int w = parseClampedInt(prefs.get(KEY_INTERCEPT_W), defW, 860, 2400);
        int h = parseClampedInt(prefs.get(KEY_INTERCEPT_H), defH, 520, 1400);
        return new Dimension(w, h);
    }

    public static void setInterceptSize(Prefs prefs, int width, int height) {
        if (prefs == null || width < 700 || height < 420) {
            return;
        }
        prefs.set(KEY_INTERCEPT_W, Integer.toString(Math.max(860, Math.min(2400, width))));
        prefs.set(KEY_INTERCEPT_H, Integer.toString(Math.max(520, Math.min(1400, height))));
    }

    public static Dimension composerSize(Prefs prefs) {
        int defW = 1120;
        int defH = 680;
        if (prefs == null) {
            return new Dimension(defW, defH);
        }
        int w = parseClampedInt(prefs.get(KEY_COMPOSER_W), defW, 920, 2400);
        int h = parseClampedInt(prefs.get(KEY_COMPOSER_H), defH, 580, 1400);
        return new Dimension(w, h);
    }

    public static void setComposerSize(Prefs prefs, int width, int height) {
        if (prefs == null || width < 700 || height < 420) {
            return;
        }
        prefs.set(KEY_COMPOSER_W, Integer.toString(Math.max(920, Math.min(2400, width))));
        prefs.set(KEY_COMPOSER_H, Integer.toString(Math.max(580, Math.min(1400, height))));
    }

    private static int parseClampedInt(String raw, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static void applyStartup(Prefs prefs) {
        ToastPane.setAnimationsEnabled(animationsEnabled(prefs));
        themythofcrawl.ui.theme.FontManager.setUiChoice(uiFontChoice(prefs));
        themythofcrawl.ui.theme.FontManager.setMonoChoice(monoFontChoice(prefs));
        Theme.setFontScaleMultiplier(fontScalePercent(prefs) / 100f);
        UiDisplayMode.set(displayMode(prefs));
    }
}
