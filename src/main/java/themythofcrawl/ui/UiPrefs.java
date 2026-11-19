package themythofcrawl.ui;

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

    public static void applyStartup(Prefs prefs) {
        ToastPane.setAnimationsEnabled(animationsEnabled(prefs));
        themythofcrawl.ui.theme.FontManager.setUiChoice(uiFontChoice(prefs));
        themythofcrawl.ui.theme.FontManager.setMonoChoice(monoFontChoice(prefs));
        Theme.setFontScaleMultiplier(fontScalePercent(prefs) / 100f);
        UiDisplayMode.set(displayMode(prefs));
    }
}
