package themythofcrawl.ui;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Branch;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.Role;
import themythofcrawl.ui.kit.Card;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SearchField;
import themythofcrawl.ui.theme.Theme;

/**
 * Floating global Spotlight search overlay triggered by Ctrl+K.
 * Searches across captured APIs (#gid, method, path, Excel STT), Branches, and Roles.
 */
public final class SpotlightDialog extends JPanel {
    enum Kind { API, BRANCH, ROLE }

    record Match(Kind kind, String title, String subtitle, String badge, AuditLevel audit, int rank, Runnable action) {
    }

    private static final int MAX_RESULTS = 45;

    private final MainPanel ui;
    private final Engine engine;
    private final Card card = new Card(t -> t.accent, false);
    private final SearchField search = new SearchField("Tìm nhanh API (#, method, path), Nhánh hoặc vai trò...", 32);
    private final Pill shortcutPill = new Pill("Ctrl+K", t -> t.accent);
    private final DefaultListModel<Match> model = new DefaultListModel<>();
    private final JList<Match> list = new JList<>(model);
    private final JLabel countLabel = Ui.muted("0 kết quả");
    private Component previousFocus;

    SpotlightDialog(MainPanel ui) {
        super(null);
        this.ui = ui;
        this.engine = ui.engine;
        setOpaque(false);
        setVisible(false);

        shortcutPill.setPadding(1, 7, 1, 7);

        JPanel topRow = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        topRow.setOpaque(false);
        JLabel searchIcon = new JLabel(Icons.themed(t -> Icons.search(t.accent.text(), 15)));
        topRow.add(searchIcon, BorderLayout.WEST);
        topRow.add(search, BorderLayout.CENTER);
        topRow.add(shortcutPill, BorderLayout.EAST);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new MatchRenderer());
        list.setFixedCellHeight(Theme.current().scaled(36));
        list.setFocusable(false);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = list.locationToIndex(e.getPoint());
                if (idx >= 0) {
                    list.setSelectedIndex(idx);
                    executeSelected();
                }
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setPreferredSize(new Dimension(600, 260));

        JPanel footer = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, 2, 0, 2));
        footer.add(Ui.muted("Up/Down để chọn · Enter để nhảy tới · Esc để đóng"), BorderLayout.WEST);
        footer.add(countLabel, BorderLayout.EAST);

        JPanel inner = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        inner.setOpaque(false);
        inner.add(topRow, BorderLayout.NORTH);
        inner.add(scroll, BorderLayout.CENTER);
        inner.add(footer, BorderLayout.SOUTH);

        card.setPadding(Theme.SPACE_3, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3);
        card.setBody(inner);
        add(card);

        search.onChange(this::refreshResults);
        installKeys();

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!card.getBounds().contains(e.getPoint())) {
                    close();
                }
            }
        });
    }

    private void installKeys() {
        search.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "spotlight.down");
        search.getActionMap().put("spotlight.down", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                moveSelection(1);
            }
        });
        search.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "spotlight.up");
        search.getActionMap().put("spotlight.up", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                moveSelection(-1);
            }
        });
        search.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "spotlight.go");
        search.getActionMap().put("spotlight.go", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                executeSelected();
            }
        });
        search.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "spotlight.esc");
        search.getActionMap().put("spotlight.esc", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                close();
            }
        });
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "spotlight.esc");
        getActionMap().put("spotlight.esc", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                close();
            }
        });
    }

    public void toggle() {
        if (isVisible()) {
            close();
        } else {
            open();
        }
    }

    public void open() {
        previousFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        search.setText("");
        refreshResults("");
        setVisible(true);
        if (getParent() != null) {
            getParent().doLayout();
        }
        doLayout();
        search.focusField();
        repaint();
    }

    public void close() {
        if (!isVisible()) {
            return;
        }
        setVisible(false);
        repaint();
        if (previousFocus != null && previousFocus.isDisplayable()) {
            SwingUtilities.invokeLater(previousFocus::requestFocusInWindow);
        }
    }

    void moveSelection(int delta) {
        int n = model.getSize();
        if (n <= 0) {
            return;
        }
        int cur = list.getSelectedIndex();
        int next = cur < 0 ? 0 : Math.max(0, Math.min(n - 1, cur + delta));
        list.setSelectedIndex(next);
        list.ensureIndexIsVisible(next);
    }

    public boolean executeSelected() {
        Match sel = list.getSelectedValue();
        if (sel == null && model.getSize() > 0) {
            sel = model.getElementAt(0);
        }
        if (sel == null) {
            return false;
        }
        close();
        sel.action().run();
        return true;
    }

    void refreshResults(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String digits = q.startsWith("#") ? q.substring(1) : (q.startsWith("g") && q.length() > 1 ? q.substring(1) : q);
        boolean numericQuery = !digits.isEmpty() && digits.chars().allMatch(Character::isDigit);

        List<Match> hits = new ArrayList<>();

        // 1. APIs
        for (ApiGroup g : engine.groups()) {
            int rank = scoreApi(g, q, digits, numericQuery);
            if (rank >= 0) {
                String gidLbl = Text.groupId(g.gid());
                String title = "#" + g.gid() + " " + g.method() + " " + g.path();
                String excel = g.target() != null && !g.target().sttText().isBlank() ? " · Excel #" + g.target().sttText() : "";
                String sub = g.branch() + excel + (g.origStatus() != null ? " · " + g.origStatus() : "");
                AuditLevel lvl = Audit.display(g);
                hits.add(new Match(Kind.API, title, sub, gidLbl, lvl, rank, () -> ui.jumpToGroup(g)));
            }
        }

        // 2. Branches
        for (Branch b : engine.branches()) {
            int rank = q.isEmpty() ? 20 : scoreText(b.name(), q);
            if (rank >= 0) {
                int count = engine.groupsUnder(b.name()).size();
                hits.add(new Match(Kind.BRANCH, b.name(), "Nhánh nhánh · " + count + " API",
                        count + " API", AuditLevel.NONE, rank + 2, () -> ui.jumpToBranch(b.name())));
            }
        }

        // 3. Roles + API gốc profile
        if (q.isEmpty() || "api gốc".contains(q) || "gốc".contains(q) || "original".contains(q)) {
            hits.add(new Match(Kind.ROLE, "Tài khoản crawl (profile)", "Rule áp dụng riêng cho request gốc trước khi nhân bản",
                    engine.originalProfile().enabled() ? "Bật" : "Tắt", AuditLevel.REDIRECT, q.isEmpty() ? 30 : 6,
                    () -> ui.jumpToRole(-1)));
        }
        List<Role> allRoles = engine.roles().all();
        for (int i = 0; i < allRoles.size(); i++) {
            Role role = allRoles.get(i);
            int rank = scoreRole(role, q);
            if (rank >= 0) {
                int idx = i;
                String stateBadge = role.enabled() ? "Bật · " + role.rules().size() + " rule" : "Tắt";
                hits.add(new Match(Kind.ROLE, role.name(), role.rulesSummary(), stateBadge,
                        role.enabled() ? AuditLevel.OK : AuditLevel.PENDING, rank + 4, () -> ui.jumpToRole(idx)));
            }
        }

        hits.sort((a, b) -> Integer.compare(a.rank(), b.rank()));
        model.clear();
        int shown = Math.min(MAX_RESULTS, hits.size());
        for (int i = 0; i < shown; i++) {
            model.addElement(hits.get(i));
        }
        if (shown > 0) {
            list.setSelectedIndex(0);
            list.ensureIndexIsVisible(0);
        }
        countLabel.setText(hits.size() > shown ? shown + " / " + hits.size() + " kết quả" : hits.size() + " kết quả");
    }

    private static int scoreApi(ApiGroup g, String q, String digits, boolean numericQuery) {
        if (q.isEmpty()) {
            return 10;
        }
        String gidStr = String.valueOf(g.gid());
        String grpId = Text.groupId(g.gid()).toLowerCase(Locale.ROOT);
        if (numericQuery && gidStr.equals(digits)) {
            return 0;
        }
        if (grpId.equals(q)) {
            return 0;
        }
        if (numericQuery && gidStr.startsWith(digits)) {
            return 3;
        }
        String path = Text.nz(g.path()).toLowerCase(Locale.ROOT);
        String method = Text.nz(g.method()).toLowerCase(Locale.ROOT);
        String branch = Text.nz(g.branch()).toLowerCase(Locale.ROOT);
        String stt = g.target() != null ? Text.nz(g.target().sttText()).toLowerCase(Locale.ROOT) : "";
        if (path.equals(q) || (!stt.isEmpty() && stt.equals(q))) {
            return 1;
        }
        if (path.contains(q)) {
            return path.startsWith(q) ? 4 : 8;
        }
        if ((method + " " + path).contains(q)) {
            return 9;
        }
        if (branch.contains(q) || Text.nz(g.url()).toLowerCase(Locale.ROOT).contains(q) || (!stt.isEmpty() && stt.contains(q))) {
            return 14;
        }
        return -1;
    }

    private static int scoreRole(Role r, String q) {
        if (q.isEmpty()) {
            return 25;
        }
        int nameScore = scoreText(r.name(), q);
        if (nameScore >= 0) {
            return nameScore;
        }
        for (HeaderRule hr : r.rules()) {
            if (Text.nz(hr.name()).toLowerCase(Locale.ROOT).contains(q)
                    || Text.nz(hr.value()).toLowerCase(Locale.ROOT).contains(q)) {
                return 16;
            }
        }
        return -1;
    }

    private static int scoreText(String text, String q) {
        if (q.isEmpty()) {
            return 20;
        }
        if (text == null) {
            return -1;
        }
        String low = text.toLowerCase(Locale.ROOT);
        if (low.equals(q)) {
            return 2;
        }
        if (low.startsWith(q)) {
            return 5;
        }
        if (low.contains(q)) {
            return 11;
        }
        return -1;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (list != null) {
            list.setFixedCellHeight(Theme.current().scaled(36));
        }
    }

    @Override
    public void doLayout() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int cw = Math.min(Math.max(360, w - 64), 680);
        int ch = Math.min(Math.max(240, h - 64), 390);
        int x = Math.max(16, (w - cw) / 2);
        int y = Math.max(24, Math.min(72, (h - ch) / 4));
        card.setBounds(x, y, cw, ch);
        card.doLayout();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Theme t = Theme.current();
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, t.dark ? 0.42f : 0.22f));
            g2.setColor(t.fg);
            g2.fillRect(0, 0, getWidth(), getHeight());
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }

    // ---- Test / inspection helpers ----
    SearchField searchField() {
        return search;
    }

    JList<Match> resultList() {
        return list;
    }

    int itemCount() {
        return model.getSize();
    }

    private static final class MatchRenderer extends JPanel implements ListCellRenderer<Match> {
        private final JLabel icon = new JLabel();
        private final Pill kindPill = new Pill("", t -> t.neutral);
        private final JLabel title = new JLabel();
        private final JLabel sub = new JLabel();
        private final Pill rightBadge = new Pill("", t -> t.neutral);

        MatchRenderer() {
            super(new BorderLayout(Theme.SPACE_2, 0));
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(3, Theme.SPACE_2, 3, Theme.SPACE_2));

            kindPill.setPadding(1, 6, 1, 6);
            rightBadge.setPadding(1, 6, 1, 6);

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_1, 0));
            left.setOpaque(false);
            left.add(icon);
            left.add(kindPill);

            JPanel textStack = new JPanel(new BorderLayout(0, 1));
            textStack.setOpaque(false);
            textStack.add(title, BorderLayout.NORTH);
            textStack.add(sub, BorderLayout.SOUTH);

            add(left, BorderLayout.WEST);
            add(textStack, BorderLayout.CENTER);
            add(rightBadge, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Match> jList, Match m, int index, boolean selected,
                                                      boolean cellHasFocus) {
            Theme th = Theme.current();
            setBackground(selected ? th.selectionBg : (index % 2 == 0 ? th.surface : th.surfaceAlt));
            if (m == null) {
                title.setText("");
                sub.setText("");
                return this;
            }
            switch (m.kind()) {
                case API -> {
                    icon.setIcon(Icons.api(selected ? th.selectionFg : th.accent.text(), 13));
                    kindPill.setToken(t -> t.info).setText("API");
                    rightBadge.setToken(t -> t.audit(m.audit())).setText(m.badge());
                }
                case BRANCH -> {
                    icon.setIcon(Icons.folder(selected ? th.selectionFg : th.warning.text(), 13));
                    kindPill.setToken(t -> t.warning).setText("Nhánh");
                    rightBadge.setToken(t -> t.neutral).setText(m.badge());
                }
                case ROLE -> {
                    icon.setIcon(Icons.role(selected ? th.selectionFg : th.dup.text(), 13));
                    kindPill.setToken(t -> t.dup).setText("Vai trò");
                    rightBadge.setToken(t -> t.audit(m.audit())).setText(m.badge());
                }
            }
            title.setFont(m.kind() == Kind.API ? th.monoBoldFont : th.boldFont);
            title.setForeground(selected ? th.selectionFg : th.fg);
            title.setText(m.title());

            sub.setFont(th.smallFont);
            sub.setForeground(selected ? th.selectionFg : th.muted);
            sub.setText(m.subtitle());
            return this;
        }
    }
}
