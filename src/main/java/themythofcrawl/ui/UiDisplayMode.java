package themythofcrawl.ui;

import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;

/**
 * Global toolbar &amp; action-button display mode:
 * <ul>
 *   <li>{@link #ICON_ONLY}: minimalist square icon buttons + rich hover description card</li>
 *   <li>{@link #ICON_AND_TEXT}: icon paired with clear Vietnamese label</li>
 *   <li>{@link #TEXT_ONLY}: pure typography labels with no toolbar icons</li>
 * </ul>
 */
public enum UiDisplayMode {
    ICON_ONLY("icon_only", "Chỉ Icon + Thẻ mô tả rê chuột (Tối giản)", "Chỉ Icon"),
    ICON_AND_TEXT("icon_and_text", "Icon kết hợp Chữ (Mặc định)", "Icon + Chữ"),
    TEXT_ONLY("text_only", "Chỉ Chữ (Không Icon)", "Chỉ Chữ");

    public final String id;
    public final String label;
    public final String shortLabel;

    UiDisplayMode(String id, String label, String shortLabel) {
        this.id = id;
        this.label = label;
        this.shortLabel = shortLabel;
    }

    private static volatile UiDisplayMode current = ICON_AND_TEXT;
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    public static UiDisplayMode current() {
        return current;
    }

    public static UiDisplayMode fromId(String id) {
        if (id != null) {
            for (UiDisplayMode m : values()) {
                if (m.id.equalsIgnoreCase(id.trim())) {
                    return m;
                }
            }
        }
        return ICON_AND_TEXT;
    }

    public static void set(UiDisplayMode next) {
        UiDisplayMode mode = next == null ? ICON_AND_TEXT : next;
        if (current == mode) {
            return;
        }
        current = mode;
        notifyListeners();
    }

    public static UiDisplayMode cycle() {
        UiDisplayMode[] vals = values();
        UiDisplayMode next = vals[(current.ordinal() + 1) % vals.length];
        set(next);
        return next;
    }

    public static void addListener(Runnable r) {
        if (r != null) {
            synchronized (LISTENERS) {
                if (!LISTENERS.contains(r)) {
                    LISTENERS.add(r);
                }
            }
        }
    }

    private static void notifyListeners() {
        List<Runnable> copy;
        synchronized (LISTENERS) {
            copy = new ArrayList<>(LISTENERS);
        }
        Runnable fire = () -> {
            for (Runnable r : copy) {
                try {
                    r.run();
                } catch (Throwable ignored) {
                    // Best-effort UI refresh
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            fire.run();
        } else {
            SwingUtilities.invokeLater(fire);
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
