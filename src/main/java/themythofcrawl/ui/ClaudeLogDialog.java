package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ActivityLog;
import themythofcrawl.core.Text;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.Role;
import themythofcrawl.model.RuleMode;
import themythofcrawl.ui.theme.Theme;

/**
 * V_8.2 "Nhật ký cho Claude": one Markdown file with what an assistant needs to understand a problem: app and
 * environment, current state and settings, recent errors with stack traces, and the timeline of every action.
 * Tokens / cookies / passwords are masked; no request or response bodies are included.
 */
final class ClaudeLogDialog {
    private ClaudeLogDialog() {
    }

    static String build(Engine engine, int maxEvents) {
        StringBuilder sb = new StringBuilder();
        sb.append("# theMythOf-JCrawl - nhật ký thao tác\n\n");
        sb.append("> Gửi cho Claude: đây là nhật ký của Burp extension theMythOf-JCrawl (crawl API qua Proxy, gửi lại API với N vai trò\n"
                + "> để tìm lỗi phân quyền BOLA / BFLA, đẩy API 2xx sang Logger++ / server quản lý test case).\n"
                + "> Hãy đọc mục 'Lỗi gần đây' và 'Dòng thời gian' (cũ -> mới) để hiểu tôi đã làm gì, app phản hồi ra sao,\n"
                + "> chỗ nào sai so với mong đợi và đề xuất cách sửa. Token / cookie / mật khẩu đã được che.\n\n");

        sb.append("## Môi trường\n\n");
        sb.append("- Phiên bản: ").append(Diagnostics.VERSION).append('\n');
        sb.append("- Java: ").append(System.getProperty("java.version")).append(" · OS: ").append(System.getProperty("os.name"))
                .append(' ').append(System.getProperty("os.arch")).append('\n');
        sb.append("- Giao diện: ").append(Diagnostics.isDark() ? "tối" : "sáng").append(" · ").append(Diagnostics.lafName())
                .append(" · màn hình ").append(Diagnostics.screenZoom()).append('\n');
        sb.append("- Workspace: ").append(engine.workspaceName()).append('\n');
        sb.append("- Thời điểm xuất: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");

        sb.append("## Trạng thái hiện tại\n\n");
        Engine.Stats st = engine.stats();
        var cfg = engine.config();
        sb.append("- API: ").append(st.apis()).append(" · nhánh: ").append(st.branches()).append(" · chờ xử lý: ").append(st.pending())
                .append(" · đang gửi / chờ gửi: ").append(engine.activeSends()).append('\n');
        sb.append("- Capture: ").append(cfg.captureEnabled ? "bật" : "dừng").append(" · Crawl/Gửi: ")
                .append(cfg.autoSend ? "Gửi (bắt xong gửi luôn)" : "Chỉ crawl").append('\n');
        sb.append("- Chế độ thao tác: ").append(cfg.actionMode.label).append(" · gửi lại khi có 200: ").append(cfg.lock2xxStrategy.label)
                .append('\n');
        try {
            sb.append("- Chặn: ").append(engine.proxyGate().sentence()).append('\n');
        } catch (RuntimeException e) {
            sb.append("- Chặn: (không đọc được: ").append(e.getMessage()).append(")\n");
        }
        sb.append("- Luật chặn đang bật: ").append(engine.actionEngine().policyLedger().activeCount()).append('/')
                .append(engine.actionEngine().policyLedger().size()).append('\n');
        sb.append("- Lỗi đã đánh dấu: ").append(engine.bugRows().size()).append('\n');
        sb.append("- Gửi: ").append(cfg.sendProfile.label).append(" · nghỉ ").append(cfg.sendDelayMs).append(" ms · song song / host ")
                .append(cfg.hostConcurrency).append('\n');
        List<Role> roles = engine.roles().all();
        sb.append("- Vai trò (").append(roles.size()).append("):\n");
        for (Role r : roles) {
            sb.append("  - ").append(r.name()).append(" [").append(r.tier()).append("] ").append(r.enabled() ? "bật" : "tắt")
                    .append(" · đăng nhập bằng: ").append(loginSummary(r)).append('\n');
        }
        sb.append('\n');

        Map<ActivityLog.Kind, Integer> counts = ActivityLog.counts();
        sb.append("## Thống kê nhật ký\n\n");
        for (Map.Entry<ActivityLog.Kind, Integer> e : counts.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        sb.append('\n');

        List<ActivityLog.Event> errs = ActivityLog.errors(15);
        sb.append("## Lỗi gần đây (").append(errs.size()).append(")\n\n");
        if (errs.isEmpty()) {
            sb.append("(không có)\n\n");
        }
        for (ActivityLog.Event e : errs) {
            sb.append("### ").append(e.time()).append(" ").append(e.text()).append("\n\n");
            if (!e.detail().isEmpty()) {
                sb.append("```\n").append(e.detail()).append("\n```\n\n");
            }
        }

        List<ActivityLog.Event> ev = ActivityLog.recent(maxEvents);
        sb.append("## Dòng thời gian (").append(ev.size()).append(" sự kiện gần nhất, cũ -> mới)\n\n");
        sb.append("Loại: UI = bấm nút / menu · TAB = đổi tab · SEND = kết quả gửi 1 dòng · CAPTURE = bắt API · CONFIG = đổi cài đặt"
                + " · ENGINE = thông báo của app · ERROR = lỗi\n\n```\n");
        for (ActivityLog.Event e : ev) {
            sb.append(e.time()).append("  ").append(String.format("%-7s", e.kind())).append(' ').append(e.text()).append('\n');
        }
        sb.append("```\n");
        return ActivityLog.redact(sb.toString());
    }

    /** "Token, X-Role" style summary of how a role logs in; never the values. */
    static String loginSummary(Role r) {
        if (r.rules().isEmpty()) {
            return "giống tài khoản crawl";
        }
        StringBuilder sb = new StringBuilder();
        for (HeaderRule h : r.rules()) {
            String k = Text.nz(h.name());
            String part = switch (h.mode() == null ? RuleMode.REPLACE : h.mode()) {
                case COOKIE_SET -> "cookie " + k;
                case COOKIE_REMOVE -> "bỏ cookie " + k;
                case REMOVE -> "bỏ " + k;
                default -> k.equalsIgnoreCase("authorization") ? "token" : k;
            };
            sb.append(sb.length() == 0 ? "" : ", ").append(part);
        }
        return sb.toString();
    }

    static void show(Component owner, Engine engine) {
        engine.persistActivity();
        JDialog dlg = new JDialog(Ui.ownerWindow(owner), "Nhật ký cho Claude", java.awt.Dialog.ModalityType.MODELESS);
        JTextArea area = new JTextArea(build(engine, 1500));
        area.setEditable(false);
        area.setFont(Theme.current().monoSmallFont);
        area.setCaretPosition(0);
        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setBorder(javax.swing.BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        root.add(Ui.dialogHeader("Nhật ký cho Claude",
                "Mọi thao tác, lần gửi, đổi cài đặt và lỗi (token / cookie đã che). Copy hoặc lưu file rồi gửi cho Claude để nhờ phân tích."),
                BorderLayout.NORTH);
        root.add(MainPanel.scroll(area), BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.add(Ui.button("Làm mới", Icons::refresh, () -> {
            area.setText(build(engine, 1500));
            area.setCaretPosition(0);
        }));
        btns.add(Ui.button("Lưu file .md...", Icons::download, () -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("jcrawl-nhat-ky-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md"));
            if (fc.showSaveDialog(dlg) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
                try {
                    Files.writeString(fc.getSelectedFile().toPath(), area.getText(), StandardCharsets.UTF_8);
                    Ui.hint(area, "Đã lưu " + fc.getSelectedFile().getName());
                } catch (Exception ex) {
                    Ui.hint(area, "Không lưu được: " + ex.getMessage());
                }
            }
        }));
        btns.add(Ui.accent("Copy", t -> Icons.copy(t.accent.onSolid()), t -> t.accent, () -> {
            Ui.copyToClipboard(area.getText());
            Ui.hint(area, "Đã copy " + area.getText().length() + " ký tự");
        }));
        root.add(btns, BorderLayout.SOUTH);
        dlg.setContentPane(root);
        dlg.setSize(new Dimension(Theme.current().scaled(900), Theme.current().scaled(640)));
        dlg.setLocationRelativeTo(Ui.ownerWindow(owner));
        dlg.setVisible(true);
    }
}
