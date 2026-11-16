package themythofcrawl.ui.kit;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.Timer;

import themythofcrawl.ui.Icons;
import themythofcrawl.ui.theme.Theme;

/**
 * Layered overlay container that hosts the primary UI on the default layer and displays
 * non-blocking 4-second slide-in notification toasts in the bottom-right corner on the popup layer.
 */
public class ToastPane extends JLayeredPane {
    public static final int DEFAULT_DURATION_MS = 4000;
    private static volatile boolean animationsEnabled = true;

    private final Component content;
    private final Card toastCard;
    private final JLabel messageLabel = new JLabel();
    private final JLabel iconLabel = new JLabel();
    private final Pill actionBtn = new Pill("", t -> t.info);
    private final Timer hideTimer;
    private final Timer animTimer;
    private Runnable onAction;
    private javax.swing.JComponent modalOverlay;
    private int slideOffsetX;
    private String lastMessage;

    public ToastPane(Component content) {
        this.content = content;
        add(content, JLayeredPane.DEFAULT_LAYER);

        actionBtn.setSolidWhenActive(true);
        actionBtn.setActive(true);
        actionBtn.setPadding(1, 7, 1, 7);
        actionBtn.setVisible(false);
        actionBtn.onClick(this::triggerAction);

        JLabel closeBtn = new JLabel(Icons.themed(t -> Icons.error(t.muted, 11)));
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.setToolTipText("Đóng thông báo");
        closeBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                hideToast();
            }
        });

        iconLabel.setIcon(Icons.themed(t -> Icons.info(t.info.text(), 14)));
        javax.swing.JPanel rightRow = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        rightRow.setOpaque(false);
        rightRow.add(actionBtn);
        rightRow.add(closeBtn);

        javax.swing.JPanel row = new javax.swing.JPanel(new java.awt.BorderLayout(Theme.SPACE_2, 0));
        row.setOpaque(false);
        row.add(iconLabel, java.awt.BorderLayout.WEST);
        row.add(messageLabel, java.awt.BorderLayout.CENTER);
        row.add(rightRow, java.awt.BorderLayout.EAST);

        toastCard = new Card(t -> t.info, false);
        toastCard.setPadding(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3);
        toastCard.setBody(row);
        toastCard.setVisible(false);
        add(toastCard, JLayeredPane.POPUP_LAYER);

        hideTimer = new Timer(DEFAULT_DURATION_MS, e -> hideToast());
        hideTimer.setRepeats(false);
        animTimer = new Timer(15, e -> stepSlide());
        syncStyles();
    }

    /** Attaches a modal overlay component (such as Spotlight search) tracked in layout. */
    public void setModalOverlay(javax.swing.JComponent overlay) {
        if (this.modalOverlay != null) {
            remove(this.modalOverlay);
        }
        this.modalOverlay = overlay;
        if (overlay != null) {
            add(overlay, JLayeredPane.MODAL_LAYER);
        }
        revalidate();
        repaint();
    }

    public static void setAnimationsEnabled(boolean enabled) {
        animationsEnabled = enabled;
    }

    public static boolean animationsEnabled() {
        return animationsEnabled;
    }

    public void showToast(String message) {
        showToast(message, DEFAULT_DURATION_MS);
    }

    public void showToast(String message, int durationMs) {
        showToastInternal(message, durationMs, null, null);
    }

    /** Shows a toast with a clickable inline action button (for example Undo after drag & drop). */
    public void showUndoToast(String message, String actionLabel, Runnable action) {
        showToastInternal(message, 5500, actionLabel, action);
    }

    private void showToastInternal(String message, int durationMs, String actionLabel, Runnable action) {
        if (message == null || message.isBlank()) {
            return;
        }
        lastMessage = message;
        this.onAction = action;
        if (actionLabel != null && !actionLabel.isBlank() && action != null) {
            actionBtn.setText(actionLabel);
            actionBtn.setVisible(true);
        } else {
            actionBtn.setVisible(false);
        }
        String shown = message.length() > 110 ? message.substring(0, 108) + "..." : message;
        messageLabel.setText(shown);
        toastCard.setToolTipText(message);
        syncStyles();
        if (animationsEnabled && getWidth() > 0) {
            slideOffsetX = 36;
            animTimer.start();
        } else {
            slideOffsetX = 0;
            animTimer.stop();
        }
        toastCard.setVisible(true);
        hideTimer.stop();
        hideTimer.setInitialDelay(Math.max(500, durationMs));
        hideTimer.restart();
        revalidate();
        repaint();
    }

    /** Invokes the current toast's action (if present) and dismisses the toast. */
    public void triggerAction() {
        Runnable r = this.onAction;
        hideToast();
        if (r != null) {
            r.run();
        }
    }

    public boolean hasAction() {
        return onAction != null && actionBtn.isVisible();
    }

    public void hideToast() {
        animTimer.stop();
        hideTimer.stop();
        onAction = null;
        actionBtn.setVisible(false);
        if (toastCard.isVisible()) {
            toastCard.setVisible(false);
            repaint();
        }
    }

    public boolean isToastVisible() {
        return toastCard.isVisible();
    }

    public String lastMessage() {
        return lastMessage;
    }

    public void dispose() {
        animTimer.stop();
        hideTimer.stop();
    }

    private void stepSlide() {
        if (slideOffsetX <= 0) {
            slideOffsetX = 0;
            animTimer.stop();
            return;
        }
        slideOffsetX = Math.max(0, slideOffsetX - 6);
        doLayout();
        toastCard.repaint();
    }

    private void syncStyles() {
        Theme t = Theme.current();
        messageLabel.setFont(t.smallBoldFont);
        messageLabel.setForeground(t.fg);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (messageLabel != null) {
            syncStyles();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return content != null ? content.getPreferredSize() : super.getPreferredSize();
    }

    @Override
    public Dimension getMinimumSize() {
        return content != null ? content.getMinimumSize() : super.getMinimumSize();
    }

    @Override
    public void doLayout() {
        int w = getWidth();
        int h = getHeight();
        if (content != null) {
            content.setBounds(0, 0, w, h);
        }
        if (modalOverlay != null && modalOverlay.isVisible()) {
            modalOverlay.setBounds(0, 0, w, h);
            modalOverlay.doLayout();
        }
        if (toastCard != null && toastCard.isVisible()) {
            Dimension pref = toastCard.getPreferredSize();
            int maxW = Math.max(180, w - 32);
            int tw = Math.min(Math.max(180, pref.width), maxW);
            int th = Math.max(30, pref.height);
            int x = Math.max(8, w - tw - 16) + slideOffsetX;
            int y = Math.max(8, h - th - 14);
            toastCard.setBounds(x, y, tw, th);
        }
    }
}
