package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.ActionEngine.ActionMode;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.model.ActionPolicy;
import themythofcrawl.model.ActionPolicy.PathMatchType;
import themythofcrawl.model.ActionPolicy.ProxyEffect;
import themythofcrawl.model.ActionPolicy.ScopeType;
import themythofcrawl.model.ActionPolicy.Stage;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.WrappedText;
import themythofcrawl.ui.theme.Theme;

/**
 * Integrated zero-HTML Action Architecture Guide &amp; 1-Click Playbooks tab (V_3.5_beta).
 * <p>
 * Demystifies the 2-stage Action architecture (Proxy Gate vs. Replay Engine), compares all 6 Action
 * modes with concrete UI examples, and provides 5 actionable Playbooks with 1-click preset buttons.
 */
final class ActionGuideTabBuilder {

    private ActionGuideTabBuilder() {
    }

    static JPanel build(
            Engine engine,
            ApiGroup focusGroup,
            Runnable refreshBanner,
            Runnable onAfterAction,
            java.util.function.Consumer<Integer> switchTab) {
        Theme th = Theme.current();

        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setOpaque(false);
        root.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        JLabel statusBanner = new JLabel("Chọn 1 kịch bản bên dưới và bấm 'Áp dụng mẫu này ngay' để cài đặt tức thì mà không cần nhớ tham số.");
        statusBanner.setFont(th.smallBoldFont);
        statusBanner.setForeground(th.info.text());
        statusBanner.setIcon(Icons.info(th.info.text(), 13));
        statusBanner.setIconTextGap(6);

        JPanel bannerBox = new JPanel(new BorderLayout());
        bannerBox.setOpaque(true);
        bannerBox.setBackground(th.info.fill());
        bannerBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.info.border()),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        bannerBox.add(statusBanner, BorderLayout.CENTER);
        root.add(bannerBox, BorderLayout.NORTH);

        java.util.function.Consumer<String> flashGuide = msg -> {
            Theme t = Theme.current();
            statusBanner.setIcon(Icons.check(t.success.text(), 13));
            statusBanner.setForeground(t.success.text());
            statusBanner.setText(msg);
            bannerBox.setBackground(t.success.fill());
            bannerBox.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(t.success.border()),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)));
            if (refreshBanner != null) refreshBanner.run();
            if (onAfterAction != null) onAfterAction.run();
        };

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(4, 2, 12, 6));

        // =====================================================================
        // SECTION 1: 2-STAGE ARCHITECTURE DIAGRAM (ZERO HTML)
        // =====================================================================
        content.add(sectionTitle("1. Hai lúc có thể can thiệp"));
        content.add(Box.createVerticalStrut(4));

        final int boxW = 79;
        String border = "+" + "-".repeat(boxW) + "+";
        JTextArea diagram = new JTextArea(
                "[Trình duyệt]  bạn bấm Lưu / Xóa / Duyệt trên web\n"
                + "         |\n"
                + "         v\n"
                + border + "\n"
                + boxLine(" LÚC 1 - KHI CRAWL (request đi qua Burp, trước khi tới server)", boxW) + "\n"
                + boxLine("  - Chờ duyệt: dừng ở Burp, hiện trong Hàng chặn, bạn bấm Gửi mới đi.", boxW) + "\n"
                + boxLine("  - Giữ lại: trình duyệt nhận 200 giả, server không đổi gì.", boxW) + "\n"
                + boxLine("  - Cho qua: luật ưu tiên, đi thẳng server (login, telemetry...).", boxW) + "\n"
                + border + "\n"
                + "         |\n"
                + "         v   (API đã lưu vào Cây)\n"
                + border + "\n"
                + boxLine(" LÚC 2 - KHI GỬI LẠI (bấm Gửi / test N vai trò)", boxW) + "\n"
                + boxLine("  - Tắt: gửi y nguyên (đã xóa rồi thì server báo 400 / 404).", boxW) + "\n"
                + boxLine("  - Bằng chứng 200: giữ response 200 đầu tiên để báo cáo.", boxW) + "\n"
                + boxLine("  - Đổi ID: thay ID đã dùng bằng ID còn sống CÙNG họ API.", boxW) + "\n"
                + boxLine("  - Tạo trước: gọi API tạo (POST cùng họ) lấy ID mới rồi mới gửi.", boxW) + "\n"
                + border);
        diagram.setEditable(false);
        diagram.setFont(th.monoSmallFont);
        diagram.setBackground(th.surfaceAlt);
        diagram.setForeground(th.fg);
        diagram.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.border),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        diagram.setAlignmentX(Component.LEFT_ALIGNMENT);
        diagram.setMaximumSize(new Dimension(Integer.MAX_VALUE, diagram.getPreferredSize().height + 4));
        content.add(diagram);
        content.add(Box.createVerticalStrut(12));

        // =====================================================================
        // SECTION 2: 6 ACTION MODES EXPLAINED WITH REAL EXAMPLES
        // =====================================================================
        content.add(sectionTitle("2. Sáu chế độ - dùng khi nào"));
        content.add(Box.createVerticalStrut(6));

        JPanel modesGrid = new JPanel(new GridLayout(3, 2, 8, 8));
        modesGrid.setOpaque(false);
        modesGrid.setAlignmentX(Component.LEFT_ALIGNMENT);

        modesGrid.add(modeCard("Tắt", "Mặc định", t -> t.neutral,
                "Không chặn, không đổi ID, không 200 giả.",
                "Ví dụ: Gửi DELETE /orders/10 lần 1 ra 200, gửi lại lần 2 ra 400/404 đúng thực tế Server."));

        modesGrid.add(modeCard("Chờ duyệt", "Lúc crawl - khuyên dùng", t -> t.danger,
                "Request từ trình duyệt dừng ngay trên Cây [CHỜ DUYỆT · CHƯA GỬI]. Bạn sửa xong mới bấm Gửi lên Server.",
                "Ví dụ: Bấm nút 'Lưu cấu hình' trên web -> Request hiện trong nhánh Cây -> bạn đổi roleId=1 -> bấm Gửi."));

        modesGrid.add(modeCard("Giữ lại", "Lúc crawl - an toàn", t -> t.warning,
                "Khi bấm Xóa trên UI, trình duyệt nhận 200 OK ảo để không báo lỗi, còn bản ghi trên Server vẫn còn nguyên.",
                "Ví dụ: Bạn muốn thu thập API DELETE /users/55 vào bảng nhưng chưa muốn user #55 bị xóa thật ngay lúc crawl."));

        modesGrid.add(modeCard("Bằng chứng 200", "Lúc gửi lại - báo cáo", t -> t.info,
                "Giữ nguyên kết quả 200 OK chụp được lúc đầu khi cần xuất báo cáo/bằng chứng, tránh bị ghi đè thành 404.",
                "Ví dụ: Đã test xong API duyệt đơn 200 OK; khi chạy lại cả nhánh không muốn mất trạng thái 200 OK này."));

        modesGrid.add(modeCard("Đổi ID", "Lúc gửi lại - nhiều vai trò", t -> t.success,
                "Lấy ID từ response 2xx CÙNG họ API (POST tạo, GET danh sách / chi tiết) rồi thay vào request Xóa / Sửa.",
                "Ví dụ: Test DELETE /orders/{id} với 3 Role (Admin, Staff, User) -> mỗi Role tự nhận 1 ID tươi khác nhau."));

        modesGrid.add(modeCard("Tạo trước", "Lúc gửi lại - gửi nhiều lần", t -> t.accent,
                "Trước DELETE / PUT, tự gọi POST cùng họ API để tạo bản ghi mới, lấy ID trả về rồi mới gửi.",
                "Ví dụ: Mỗi lần bấm Gửi DELETE /items/{id}, Extension tự gọi POST /items -> lấy $.data.id -> xóa đúng ID vừa tạo."));

        content.add(modesGrid);
        content.add(Box.createVerticalStrut(12));

        // =====================================================================
        // SECTION 3: 5 STEP-BY-STEP PLAYBOOKS WITH 1-CLICK APPLY
        // =====================================================================
        content.add(sectionTitle("3. 5 Kịch bản thực tế hay dùng nhất (Bấm 1 nút để áp dụng ngay)"));
        content.add(Box.createVerticalStrut(6));

        String curBranch = engine.activeBranch().name();

        content.add(playbookCard(
                "Kịch bản A — Bấm nút trên Web -> Dừng ngay trên Cây đợi tôi chỉnh sửa & gửi",
                "Bước 1: Bật Chặn chờ duyệt (POST/PUT/PATCH/DELETE). Bước 2: Bấm nút Save/Xóa trên Web. Bước 3: Bấm dòng [CHỜ DUYỆT] ngay dưới nhánh trên Cây, sửa Raw HTTP bên dưới rồi bấm 'Gửi lên Server'.",
                "Áp dụng mẫu A ngay",
                t -> t.danger,
                () -> {
                    engine.actionEngine().setInterceptWaitAllScope(true);
                    engine.setInterceptWaitEnabled(true);
                    flashGuide.accept("Đã bật Chặn chờ duyệt (POST/PUT/PATCH/DELETE) trên mọi nhánh — hãy bấm nút trên trình duyệt và xem ngay trên Cây!");
                }));
        content.add(Box.createVerticalStrut(6));

        content.add(playbookCard(
                "Kịch bản B — Chỉ chặn riêng Nhánh đang chọn (\"" + Hierarchy.leaf(curBranch) + "\"), nhánh khác đi bình thường",
                "Tạo 1 luật Phạm vi = Nhánh cây cho \"" + curBranch + "\" (Method: POST/PUT/PATCH/DELETE -> Chặn chờ duyệt). Mọi nhánh khác không bị ảnh hưởng.",
                "Tạo luật cho nhánh \"" + Text.shortPath(Hierarchy.leaf(curBranch), 20) + "\"",
                t -> t.accent,
                () -> {
                    long now = System.currentTimeMillis();
                    ActionPolicy pol = new ActionPolicy(
                            null,
                            "Chặn chờ duyệt · Nhánh " + Hierarchy.leaf(curBranch),
                            "Tạo nhanh từ Hướng dẫn Kịch bản B",
                            true, -1,
                            ScopeType.BRANCH, List.of(Hierarchy.normalize(curBranch)), true, "",
                            PathMatchType.PREFIX, "", null,
                            ActionEngine.ACTION_METHODS,
                            Stage.PROXY_GATE, ProxyEffect.INTERCEPT_WAIT, ActionMode.NONE,
                            200, null, null, "", now, now, "Hướng dẫn mẫu", 0L, "", 0);
                    engine.actionPolicyLedger().upsert(pol);
                    engine.saveActionPolicies();
                    flashGuide.accept("Đã tạo luật Chờ duyệt cho nhánh \"" + curBranch + "\" (xem tab Luật).");
                    if (switchTab != null) switchTab.accept(ActionWorkbenchDialog.TAB_LEDGER);
                }));
        content.add(Box.createVerticalStrut(6));

        String allowTargetDesc = focusGroup != null
                ? focusGroup.method() + " " + ActionEngine.normalizePathTemplate(focusGroup.path())
                : "POST /api/auth/refresh";
        content.add(playbookCard(
                "Kịch bản C — Đang chặn cả nhánh nhưng muốn 'Cho qua luôn (ALLOW)' 1 API ngoại lệ",
                "Tạo luật ưu tiên cao nhất: Cho qua cho " + allowTargetDesc
                        + ". Request khớp API này sẽ đi thẳng lên Server dù luật chặn phía dưới đang bật.",
                "Tạo luật Cho qua (ALLOW) cho " + Text.shortPath(allowTargetDesc, 26),
                t -> t.success,
                () -> {
                    long now = System.currentTimeMillis();
                    String path = focusGroup != null ? ActionEngine.normalizePathTemplate(focusGroup.path()) : "/api/auth/refresh";
                    String method = focusGroup != null ? focusGroup.method() : "POST";
                    String host = focusGroup != null ? focusGroup.host() : "";
                    ActionPolicy pol = new ActionPolicy(
                            null,
                            "Cho qua (ALLOW) · " + method + " " + Text.shortPath(path, 28),
                            "Tạo nhanh từ Hướng dẫn Kịch bản C",
                            true, 0,
                            focusGroup != null ? ScopeType.API_EXACT : ScopeType.PATH_PATTERN,
                            List.of(), true, host,
                            PathMatchType.TEMPLATE, path, focusGroup != null ? focusGroup.gid() : null,
                            List.of(method),
                            Stage.PROXY_GATE, ProxyEffect.ALLOW_EXPLICIT, ActionMode.NONE,
                            200, null, null, "", now, now, "Hướng dẫn mẫu", 0L, "", 0);
                    engine.actionPolicyLedger().createAtTop(pol);
                    engine.saveActionPolicies();
                    flashGuide.accept("Đã thêm luật ưu tiên #1 Cho qua (ALLOW) cho " + method + " " + path + "!");
                    if (switchTab != null) switchTab.accept(ActionWorkbenchDialog.TAB_LEDGER);
                }));
        content.add(Box.createVerticalStrut(6));

        content.add(playbookCard(
                "Kịch bản D - Test Xóa / Sửa qua nhiều vai trò bằng Đổi ID",
                "Bật tự lấy ID từ response 2xx cùng họ API và chế độ Đổi ID. Mỗi vai trò nhận 1 ID còn sống khác nhau.",
                "Bật Đổi ID",
                t -> t.info,
                () -> {
                    engine.actionEngine().setAutoHarvest(true);
                    engine.applyQuickActionMode(ActionMode.LIVE_ID_POOL);
                    flashGuide.accept("Đã bật Tự lấy ID + chế độ Đổi ID.");
                }));
        content.add(Box.createVerticalStrut(6));

        content.add(playbookCard(
                "Kịch bản E — Lỡ bật nhầm hoặc muốn dừng sạch mọi can thiệp Action",
                "Đưa mọi luật vào Thùng rác (khôi phục được), tắt chặn, về chế độ Tắt.",
                "Đặt lại",
                t -> t.warning,
                () -> {
                    engine.resetAllActionControls();
                    flashGuide.accept("Đã đặt lại: tắt mọi chặn. Luật cũ nằm trong Thùng rác, khôi phục được.");
                }));

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        root.add(scroll, BorderLayout.CENTER);
        return root;
    }

    private static JLabel sectionTitle(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.current().headingFont);
        l.setForeground(Theme.current().fg);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JPanel modeCard(
            String title,
            String badge,
            java.util.function.Function<Theme, Theme.Token> tokenFn,
            String purpose,
            String example) {
        Theme th = Theme.current();
        Theme.Token tok = tokenFn.apply(th);
        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.setOpaque(true);
        card.setBackground(th.surface);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 3, 1, 1, tok.border()),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        JPanel head = new JPanel(new BorderLayout(6, 0));
        head.setOpaque(false);
        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(th.boldFont);
        titleLbl.setForeground(tok.text());
        Pill badgePill = new Pill(badge, tokenFn).setPadding(1, 6, 1, 6);
        head.add(titleLbl, BorderLayout.CENTER);
        head.add(badgePill, BorderLayout.EAST);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        WrappedText pText = WrappedText.body(purpose, 380);
        pText.setAlignmentX(Component.LEFT_ALIGNMENT);
        WrappedText exText = WrappedText.muted(example, 380);
        exText.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(pText);
        body.add(Box.createVerticalStrut(3));
        body.add(exText);

        card.add(head, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private static JPanel playbookCard(
            String title,
            String desc,
            String buttonLabel,
            java.util.function.Function<Theme, Theme.Token> tokenFn,
            Runnable onApply) {
        Theme th = Theme.current();
        Theme.Token tok = tokenFn.apply(th);
        JPanel card = new JPanel(new BorderLayout(10, 0));
        card.setOpaque(true);
        card.setBackground(th.surface);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 3, 1, 1, tok.border()),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel textCol = new JPanel();
        textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
        textCol.setOpaque(false);
        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(th.boldFont);
        titleLbl.setForeground(th.fg);
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        WrappedText descTxt = WrappedText.muted(desc, 560);
        descTxt.setAlignmentX(Component.LEFT_ALIGNMENT);
        textCol.add(titleLbl);
        textCol.add(Box.createVerticalStrut(3));
        textCol.add(descTxt);

        JButton applyBtn = Ui.solidPrimary(buttonLabel, t -> Icons.playTest(t.accent.onSolid(), 13), onApply);
        JPanel rightWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 2));
        rightWrap.setOpaque(false);
        rightWrap.add(applyBtn);

        card.add(textCol, BorderLayout.CENTER);
        card.add(rightWrap, BorderLayout.EAST);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height + 16));
        return card;
    }

    private static String boxLine(String text, int innerWidth) {
        if (text.length() >= innerWidth) {
            return "|" + text.substring(0, innerWidth) + "|";
        }
        return "|" + text + " ".repeat(innerWidth - text.length()) + "|";
    }
}
