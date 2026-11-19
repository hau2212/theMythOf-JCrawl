package themythofcrawl.ui.kit;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.place.Decisions;
import themythofcrawl.ui.Icons;
import themythofcrawl.ui.theme.FontManager;
import themythofcrawl.ui.theme.Theme;

/**
 * Clean, sharp-cornered (arc = 0) structured explanation card replacing raw walls-of-text
 * in Duplicate, Lineage, and Audit decision panels.
 * Uses pure-Swing {@link WrappedText} (zero HTML) so Burp Suite's {@code html.disable}
 * never leaks raw {@code <html>} or entity codes, and lays out flush-left at 100% full width.
 */
public final class StructuredAdviceCard extends JPanel {
    private final Pill badgePill = new Pill("Chưa chọn", t -> t.neutral);
    private final JLabel headlineLbl = new JLabel("Chọn 1 dòng để xem giải thích & hướng xử lý");
    private final JPanel factsPanel = new JPanel(new GridBagLayout());
    private final JPanel tipsPanel = new JPanel();
    private final JPanel bodyWrap = new JPanel(new BorderLayout(0, Theme.SPACE_1));
    private Function<Theme, Theme.Token> accentFn = t -> t.info;

    public StructuredAdviceCard() {
        super(new BorderLayout(0, Theme.SPACE_1));
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3 + 3, Theme.SPACE_2, Theme.SPACE_3));

        JPanel head = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        head.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, 0));
        left.setOpaque(false);
        left.add(badgePill);
        left.add(headlineLbl);
        head.add(left, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);

        factsPanel.setOpaque(false);
        tipsPanel.setOpaque(true);
        tipsPanel.setLayout(new BoxLayout(tipsPanel, BoxLayout.Y_AXIS));

        // BorderLayout ensures factsPanel and tipsPanel ALWAYS span 100% full width flush left (zero BoxLayout center-drift)
        bodyWrap.setOpaque(false);
        bodyWrap.add(factsPanel, BorderLayout.NORTH);
        bodyWrap.add(tipsPanel, BorderLayout.CENTER);
        add(bodyWrap, BorderLayout.CENTER);

        applyTheme();
    }

    public void setEmpty(String headline, String detail) {
        accentFn = t -> t.neutral;
        badgePill.setText("Chưa chọn");
        badgePill.setToken(t -> t.neutral);
        badgePill.setIcon(null);
        headlineLbl.setText(headline == null ? "" : Icons.stripInlineSymbols(headline));
        factsPanel.removeAll();
        tipsPanel.removeAll();
        if (detail != null && !detail.isBlank()) {
            WrappedText d = WrappedText.muted(Icons.stripInlineSymbols(detail), 640);
            tipsPanel.add(d);
            tipsPanel.setVisible(true);
        } else {
            tipsPanel.setVisible(false);
        }
        applyTheme();
        revalidate();
        repaint();
    }

    public void setAdvice(Decisions.SmartAdvice adv) {
        Theme th = Theme.current();
        if (adv == null) {
            accentFn = t -> t.neutral;
            badgePill.setText("Chưa chọn");
            badgePill.setToken(t -> t.neutral);
            headlineLbl.setText("Chọn 1 API trong bảng để xem chi tiết vị trí & gợi ý xử lý");
            factsPanel.removeAll();
            tipsPanel.removeAll();
            tipsPanel.setVisible(false);
            applyTheme();
            revalidate();
            repaint();
            return;
        }

        accentFn = resolveAccent(adv);
        badgePill.setText(adv.badgeLabel());
        badgePill.setToken(accentFn);
        badgePill.setIcon(t -> resolveIcon(adv, accentFn.apply(t)));
        headlineLbl.setText(adv.headline() == null ? "" : Icons.stripInlineSymbols(adv.headline()));

        factsPanel.removeAll();
        List<Decisions.FactRow> facts = adv.facts();
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(2, 0, 2, Theme.SPACE_2);
        if (facts != null && !facts.isEmpty()) {
            for (Decisions.FactRow f : facts) {
                gc.gridx = 0;
                gc.weightx = 0;
                gc.fill = GridBagConstraints.NONE;
                JLabel k = new JLabel(f.label() + ":");
                k.setFont(th.smallBoldFont);
                k.setForeground(th.muted);
                factsPanel.add(k, gc);

                gc.gridx = 1;
                gc.weightx = 1.0;
                gc.fill = GridBagConstraints.HORIZONTAL;
                JLabel v = new JLabel(Icons.stripInlineSymbols(f.value()));
                v.setFont(f.mono() ? th.monoSmallFont : th.baseFont);
                v.setForeground(th.fg);
                factsPanel.add(v, gc);

                if (f.badge() != null && !f.badge().isBlank()) {
                    gc.gridx = 2;
                    gc.weightx = 0;
                    gc.fill = GridBagConstraints.NONE;
                    AuditLevel lvl = f.level() != null ? f.level() : AuditLevel.NONE;
                    Pill p = Pill.badge(f.badge(), t -> t.audit(lvl));
                    factsPanel.add(p, gc);
                }
                gc.gridy++;
            }
        } else if (adv.explanation() != null && !adv.explanation().isBlank()) {
            gc.gridx = 0;
            gc.gridwidth = 3;
            gc.weightx = 1.0;
            gc.fill = GridBagConstraints.HORIZONTAL;
            WrappedText desc = WrappedText.body(Icons.stripInlineSymbols(adv.explanation()), 680);
            factsPanel.add(desc, gc);
        }

        tipsPanel.removeAll();
        List<String> recs = adv.recommendations();
        if (recs != null && !recs.isEmpty()) {
            tipsPanel.setVisible(true);
            JLabel tipTitle = new JLabel("Gợi ý xử lý nhanh:");
            tipTitle.setFont(th.smallBoldFont);
            tipTitle.setForeground(accentFn.apply(th).text());
            tipTitle.setAlignmentX(LEFT_ALIGNMENT);
            tipTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
            tipsPanel.add(tipTitle);
            for (int i = 0; i < Math.min(3, recs.size()); i++) {
                String line = Icons.stripInlineSymbols(recs.get(i));
                WrappedText row = WrappedText.small((i + 1) + ". " + line, 720);
                row.setBorder(BorderFactory.createEmptyBorder(1, 6, 2, 0));
                tipsPanel.add(row);
            }
        } else {
            tipsPanel.setVisible(false);
        }

        applyTheme();
        revalidate();
        repaint();
    }

    private static Function<Theme, Theme.Token> resolveAccent(Decisions.SmartAdvice adv) {
        if (adv.suspectedRefresh()) {
            return t -> t.teal;
        }
        String act = adv.suggestedAction() == null ? "" : adv.suggestedAction();
        if ("KEEP_ORIGIN_HIDE_OTHERS".equals(act) || "KEEP_ORIGIN_AND_TAG_REFRESH".equals(act)) {
            return t -> t.dup;
        }
        if ("RETURN_DECIDE".equals(act)) {
            return t -> t.warning;
        }
        if ("GHOST".equals(act)) {
            return t -> t.pending;
        }
        return t -> t.info;
    }

    private static javax.swing.Icon resolveIcon(Decisions.SmartAdvice adv, Theme.Token tok) {
        if (adv.suspectedRefresh()) {
            return Icons.refresh(tok.text(), 12);
        }
        String act = adv.suggestedAction() == null ? "" : adv.suggestedAction();
        if ("KEEP_ORIGIN_HIDE_OTHERS".equals(act)) {
            return Icons.dup(tok.text(), 12);
        }
        if ("RETURN_DECIDE".equals(act)) {
            return Icons.undo(tok.text(), 12);
        }
        if ("GHOST".equals(act)) {
            return Icons.ghost(tok.text(), 12);
        }
        return Icons.originCrown(tok.text(), 12);
    }

    private void applyTheme() {
        Theme th = Theme.current();
        if (headlineLbl == null || tipsPanel == null) {
            return;
        }
        setBackground(th.surface);
        headlineLbl.setFont(th.boldFont);
        headlineLbl.setForeground(th.fg);
        Theme.Token tok = accentFn != null ? accentFn.apply(th) : th.info;
        tipsPanel.setBackground(tok.fill());
        tipsPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(tok.border(), 1),
                BorderFactory.createEmptyBorder(Theme.SPACE_1 + 2, Theme.SPACE_2, Theme.SPACE_1 + 2, Theme.SPACE_2)));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        applyTheme();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Theme th = Theme.current();
        Theme.Token tok = accentFn != null ? accentFn.apply(th) : th.info;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            FontManager.applyTextHints(g2);
            g2.setColor(th.surface);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(tok.base());
            g2.fillRect(0, 0, 3, getHeight());
            g2.setColor(th.border);
            g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
        } finally {
            g2.dispose();
        }
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension pref = getPreferredSize();
        return new Dimension(Integer.MAX_VALUE, pref.height);
    }
}
