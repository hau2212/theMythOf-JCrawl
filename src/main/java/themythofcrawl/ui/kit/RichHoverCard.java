package themythofcrawl.ui.kit;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import themythofcrawl.ui.UiDisplayMode;
import themythofcrawl.ui.theme.FontManager;
import themythofcrawl.ui.theme.Theme;

/**
 * Modern rectangular (arc = 0) rich hover card modeled after Material Design 3 Rich Tooltips
 * and Linear/JetBrains toolbar popovers:
 * <ul>
 *   <li>Header with icon, bold Vietnamese title, and optional shortcut chip</li>
 *   <li>Clear wrapped Vietnamese explanation rendered with bundled {@link FontManager} fonts</li>
 *   <li>Optional live status footer line</li>
 *   <li>Smart delay (300 ms in ICON_ONLY mode, 520 ms otherwise) with 0 ms warm-switching across adjacent controls</li>
 * </ul>
 */
public final class RichHoverCard {
    public record Spec(
            String title,
            String description,
            String shortcut,
            Function<Theme, Icon> iconFn,
            Function<Theme, Theme.Token> accentFn,
            Supplier<String> statusFn
    ) {
        public Spec(String title, String description, String shortcut, Function<Theme, Icon> iconFn) {
            this(title, description, shortcut, iconFn, t -> t.accent, null);
        }
    }

    private static final String PROP_SPEC = "tmoc.hoverCard.spec";
    private static final String PROP_INSTALLED = "tmoc.hoverCard.installed";

    private static Popup activePopup;
    private static JComponent activeAnchor;
    private static long lastDismissedAt;
    private static Timer showTimer;
    private static Timer hideTimer;

    private RichHoverCard() {
    }

    /** Attaches or updates a rich hover card on {@code target} and clears legacy Swing tooltip to avoid overlap. */
    public static void install(JComponent target, Spec spec) {
        if (target == null || spec == null) {
            return;
        }
        target.putClientProperty(PROP_SPEC, spec);
        target.setToolTipText(null);
        if (Boolean.TRUE.equals(target.getClientProperty(PROP_INSTALLED))) {
            return;
        }
        target.putClientProperty(PROP_INSTALLED, Boolean.TRUE);
        target.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                scheduleShow(target);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                scheduleHide(target);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                dismiss();
            }
        });
        target.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !target.isShowing()) {
                if (activeAnchor == target) {
                    dismiss();
                }
            }
        });
    }

    public static Spec specOf(JComponent target) {
        if (target == null) {
            return null;
        }
        Object v = target.getClientProperty(PROP_SPEC);
        return v instanceof Spec s ? s : null;
    }

    private static void scheduleShow(JComponent target) {
        if (hideTimer != null) {
            hideTimer.stop();
        }
        if (showTimer != null) {
            showTimer.stop();
        }
        boolean warm = activePopup != null || (System.currentTimeMillis() - lastDismissedAt) <= 280L;
        int delayMs = warm ? 0 : (UiDisplayMode.current() == UiDisplayMode.ICON_ONLY ? 280 : 520);
        if (delayMs == 0) {
            showNow(target);
            return;
        }
        showTimer = new Timer(delayMs, e -> showNow(target));
        showTimer.setRepeats(false);
        showTimer.start();
    }

    private static void scheduleHide(JComponent target) {
        if (showTimer != null) {
            showTimer.stop();
        }
        if (activeAnchor != target) {
            return;
        }
        if (hideTimer != null) {
            hideTimer.stop();
        }
        hideTimer = new Timer(120, e -> dismiss());
        hideTimer.setRepeats(false);
        hideTimer.start();
    }

    public static void dismiss() {
        if (showTimer != null) {
            showTimer.stop();
        }
        if (hideTimer != null) {
            hideTimer.stop();
        }
        if (activePopup != null) {
            try {
                activePopup.hide();
            } catch (Throwable ignored) {
                // Already disposed
            }
            activePopup = null;
            activeAnchor = null;
            lastDismissedAt = System.currentTimeMillis();
        }
    }

    private static void showNow(JComponent anchor) {
        if (anchor == null || !anchor.isShowing()) {
            return;
        }
        Spec spec = specOf(anchor);
        if (spec == null) {
            return;
        }
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            dismiss();
            CardView view = new CardView(spec);
            Dimension pref = view.getPreferredSize();
            Point loc = anchor.getLocationOnScreen();
            int x = loc.x;
            int y = loc.y + anchor.getHeight() + 6;

            Rectangle bounds = screenBoundsFor(anchor, loc);
            if (x + pref.width > bounds.x + bounds.width - 8) {
                x = Math.max(bounds.x + 8, bounds.x + bounds.width - pref.width - 8);
            }
            if (x < bounds.x + 8) {
                x = bounds.x + 8;
            }
            if (y + pref.height > bounds.y + bounds.height - 8) {
                y = loc.y - pref.height - 6;
            }
            if (y < bounds.y + 8) {
                y = bounds.y + 8;
            }

            activePopup = PopupFactory.getSharedInstance().getPopup(anchor, view, x, y);
            activeAnchor = anchor;
            activePopup.show();
        } catch (Throwable ignored) {
            activePopup = null;
            activeAnchor = null;
        }
    }

    private static Rectangle screenBoundsFor(Component anchor, Point loc) {
        GraphicsConfiguration gc = anchor.getGraphicsConfiguration();
        if (gc != null) {
            Rectangle b = gc.getBounds();
            Insets ins = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            return new Rectangle(b.x + ins.left, b.y + ins.top,
                    Math.max(320, b.width - ins.left - ins.right),
                    Math.max(240, b.height - ins.top - ins.bottom));
        }
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        return new Rectangle(0, 0, Math.max(320, scr.width), Math.max(240, scr.height));
    }

    /** Creates an offscreen-renderable panel instance for tests &amp; previews. */
    public static JComponent previewComponent(Spec spec) {
        return new CardView(spec);
    }

    private static final class CardView extends JPanel {
        private static final int MAX_BODY_W = 290;
        private final Spec spec;
        private final String statusText;
        private final List<String> descLines = new ArrayList<>();
        private final List<String> statusLines = new ArrayList<>();
        private final int widthPx;
        private final int heightPx;

        CardView(Spec spec) {
            this.spec = spec;
            String st = null;
            if (spec.statusFn() != null) {
                try {
                    st = spec.statusFn().get();
                } catch (Throwable ignored) {
                    st = null;
                }
            }
            this.statusText = st != null && !st.isBlank() ? st.trim() : null;
            setOpaque(true);

            Theme th = Theme.current();
            int maxBody = th.scaled(MAX_BODY_W);
            FontMetrics fmTitle = getFontMetrics(th.boldFont);
            FontMetrics fmSmall = getFontMetrics(th.smallFont);
            FontMetrics fmKey = getFontMetrics(th.monoSmallFont);

            String title = spec.title() == null ? "" : spec.title().trim();
            Icon ic = spec.iconFn() != null ? spec.iconFn().apply(th) : null;
            int iconW = ic != null ? ic.getIconWidth() + th.scaled(7) : 0;
            int scW = (spec.shortcut() != null && !spec.shortcut().isBlank())
                    ? fmKey.stringWidth(spec.shortcut().trim()) + th.scaled(16) : 0;
            int headerW = iconW + fmTitle.stringWidth(title) + (scW > 0 ? scW + th.scaled(12) : 0);

            String desc = spec.description() == null ? "" : spec.description().trim();
            if (!desc.isEmpty()) {
                wrapInto(desc, fmSmall, maxBody, descLines);
            }
            if (statusText != null) {
                wrapInto(statusText, fmSmall, maxBody, statusLines);
            }

            int bodyMax = headerW;
            for (String line : descLines) {
                bodyMax = Math.max(bodyMax, fmSmall.stringWidth(line));
            }
            for (String line : statusLines) {
                bodyMax = Math.max(bodyMax, fmSmall.stringWidth(line));
            }

            int stripeW = th.scaled(3);
            int padX = th.scaled(10);
            int padY = th.scaled(8);
            this.widthPx = Math.min(th.scaled(440), Math.max(th.scaled(210), stripeW + padX * 2 + bodyMax + 4));

            int h = padY + Math.max(fmTitle.getHeight(), ic != null ? ic.getIconHeight() : 14);
            if (!descLines.isEmpty()) {
                h += th.scaled(6) + 1 + th.scaled(5) + descLines.size() * (fmSmall.getHeight() + 2);
            }
            if (!statusLines.isEmpty()) {
                h += th.scaled(6) + statusLines.size() * (fmSmall.getHeight() + 2);
            }
            h += padY;
            this.heightPx = h;
            setPreferredSize(new Dimension(widthPx, heightPx));
        }

        private static void wrapInto(String text, FontMetrics fm, int maxW, List<String> out) {
            for (String para : text.split("\n")) {
                String p = para.trim();
                if (p.isEmpty()) {
                    continue;
                }
                String[] words = p.split("\\s+");
                StringBuilder cur = new StringBuilder();
                for (String w : words) {
                    if (cur.isEmpty()) {
                        cur.append(w);
                    } else if (fm.stringWidth(cur + " " + w) <= maxW) {
                        cur.append(' ').append(w);
                    } else {
                        out.add(cur.toString());
                        cur.setLength(0);
                        cur.append(w);
                    }
                }
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                }
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme th = Theme.current();
            Theme.Token tok = spec.accentFn() != null ? spec.accentFn().apply(th) : th.accent;
            if (tok == null) {
                tok = th.accent;
            }
            int w = getWidth();
            int h = getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                FontManager.applyTextHints(g2);

                // Sharp rectangular surface with 1px crisp border + 3px left semantic stripe
                g2.setColor(th.surface);
                g2.fillRect(0, 0, w, h);
                g2.setColor(tok.base());
                g2.fillRect(0, 0, th.scaled(3), h);
                g2.setColor(th.border);
                g2.drawRect(0, 0, w - 1, h - 1);

                int x = th.scaled(3) + th.scaled(10);
                int y = th.scaled(8);
                int rightEdge = w - th.scaled(10);

                Icon ic = spec.iconFn() != null ? spec.iconFn().apply(th) : null;
                FontMetrics fmTitle = g2.getFontMetrics(th.boldFont);
                int headerH = Math.max(fmTitle.getHeight(), ic != null ? ic.getIconHeight() : 14);

                int tx = x;
                if (ic != null) {
                    ic.paintIcon(this, g2, tx, y + (headerH - ic.getIconHeight()) / 2);
                    tx += ic.getIconWidth() + th.scaled(7);
                }

                // Shortcut badge on right
                String sc = spec.shortcut() != null ? spec.shortcut().trim() : "";
                if (!sc.isEmpty()) {
                    g2.setFont(th.monoSmallFont);
                    FontMetrics fmKey = g2.getFontMetrics();
                    int bw = fmKey.stringWidth(sc) + th.scaled(10);
                    int bh = fmKey.getHeight() + th.scaled(2);
                    int bx = rightEdge - bw;
                    int by = y + (headerH - bh) / 2;
                    g2.setColor(th.surfaceAlt);
                    g2.fillRect(bx, by, bw, bh);
                    g2.setColor(th.border);
                    g2.drawRect(bx, by, bw - 1, bh - 1);
                    g2.setColor(th.muted);
                    g2.drawString(sc, bx + th.scaled(5), by + (bh - fmKey.getHeight()) / 2 + fmKey.getAscent());
                }

                // Title
                g2.setFont(th.boldFont);
                g2.setColor(th.fg);
                String title = spec.title() == null ? "" : spec.title().trim();
                g2.drawString(title, tx, y + (headerH - fmTitle.getHeight()) / 2 + fmTitle.getAscent());
                y += headerH;

                // Divider + description
                if (!descLines.isEmpty()) {
                    y += th.scaled(5);
                    g2.setColor(th.grid);
                    g2.drawLine(x, y, rightEdge, y);
                    y += th.scaled(6);

                    g2.setFont(th.smallFont);
                    FontMetrics fmSmall = g2.getFontMetrics();
                    g2.setColor(th.fg);
                    for (String line : descLines) {
                        g2.drawString(line, x, y + fmSmall.getAscent());
                        y += fmSmall.getHeight() + 2;
                    }
                }

                // Optional status footer
                if (!statusLines.isEmpty()) {
                    y += th.scaled(4);
                    g2.setFont(th.smallBoldFont);
                    FontMetrics fmSt = g2.getFontMetrics();
                    g2.setColor(tok.text());
                    for (String line : statusLines) {
                        g2.drawString(line, x, y + fmSt.getAscent());
                        y += fmSt.getHeight() + 2;
                    }
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
