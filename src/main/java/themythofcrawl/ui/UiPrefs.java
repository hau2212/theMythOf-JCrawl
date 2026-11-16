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
    public static final String KEY_COLUMNS_PREFIX = "tmoc.ui.columns.";

    public static final int[] FONT_SCALE_CHOICES = {90, 100, 115};

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
            if (pct <= 95) {
                return 90;
            }
            if (pct >= 108) {
                return 115;
            }
            return 100;
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    public static void setFontScalePercent(Prefs prefs, int percent) {
        int clamped = percent <= 95 ? 90 : percent >= 108 ? 115 : 100;
        if (prefs != null) {
            prefs.set(KEY_FONT_SCALE, Integer.toString(clamped));
        }
        Theme.setFontScaleMultiplier(clamped / 100f);
    }

    public static void applyStartup(Prefs prefs) {
        ToastPane.setAnimationsEnabled(animationsEnabled(prefs));
        Theme.setFontScaleMultiplier(fontScalePercent(prefs) / 100f);
    }
}
