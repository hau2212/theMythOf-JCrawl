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
 * V_8.2 / V_10.4 "Nhật ký": one Markdown file with what is needed to understand a problem: app and
 * environment, current state and settings, recent errors with stack traces, and the timeline of every action.
 * Tokens / cookies / passwords are masked; no request or response bodies are included.
 */
final class ClaudeLogDialog {
    private ClaudeLogDialog() {
    }

    static String build(Engine engine, int maxEvents) {
        StringBuilder sb = new StringBuilder();
        sb.append("# theMythOf-JCrawl - nhật ký\n\n");
        sb.append("> Cách đọc: extension Burp crawl API qua Proxy, gửi lại API với N vai trò để tìm lỗi phân quyền (BOLA / BFLA),\n"
                + "> đẩy API 2xx sang Logger++ / server quản lý test case. Đọc 'Báo lỗi của người dùng', 'Lỗi gần đây', 'Chậm & đứng',\n"
                + "> 'Thói quen' rồi 'Dòng thời gian' (cũ -> mới) để biết người dùng đã làm gì, app phản hồi ra sao, tính năng nào\n"
                + "> chậm / khó / hay lặp nhiều bước. Token / cookie / mật khẩu đã được che.\n\n");
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

        List<ActivityLog.Event> reports = new java.util.ArrayList<>();
        List<ActivityLog.Event> slow = new java.util.ArrayList<>();
        for (ActivityLog.Event e : ActivityLog.recent(4000)) {
            if (e.kind() == ActivityLog.Kind.REPORT) {
                reports.add(e);
            } else if (e.kind() == ActivityLog.Kind.SLOW || e.kind() == ActivityLog.Kind.STALL) {
                slow.add(e);
            }
        }
        sb.append("## Báo lỗi của người dùng (").append(reports.size()).append(")\n\n");
        if (reports.isEmpty()) {
            sb.append("(không có)\n\n");
        }
        for (ActivityLog.Event e : reports) {
            sb.append("### ").append(e.time()).append(" ").append(e.text()).append("\n\n```\n").append(e.detail()).append("\n```\n\n");
        }
        sb.append("## Chậm & đứng (").append(slow.size()).append(")\n\n");
        for (ActivityLog.Event e : slow.subList(Math.max(0, slow.size() - 30), slow.size())) {
            sb.append("- ").append(e.time()).append(" ").append(e.kind()).append(" ").append(e.text()).append('\n');
        }
        sb.append('\n');
        sb.append("## Tính năng dùng nhiều (số lần · trung bình ms · lâu nhất ms)\n\n");
        List<Map.Entry<String, long[]>> use = new java.util.ArrayList<>(ActivityLog.usage().entrySet());
        use.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        for (Map.Entry<String, long[]> u : use.subList(0, Math.min(25, use.size()))) {
            long[] v = u.getValue();
            sb.append("- ").append(u.getKey()).append(" · ").append(v[0]).append(" lần · ").append(v[0] == 0 ? 0 : v[1] / v[0])
                    .append(" ms · ").append(v[2]).append(" ms\n");
        }
        sb.append('\n');
        sb.append("## Thói quen (chuỗi thao tác hay lặp lại, mỗi bước cách nhau < 15 s)\n\n");
        for (Map.Entry<String, Integer> h : ActivityLog.habits(15)) {
            sb.append("- ").append(h.getValue()).append(" lần: ").append(h.getKey()).append('\n');
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
            sb.append(e.time()).append("  ").append(String.format("%-7s", e.kind())).append(' ').append(e.text())
                    .append(e.ms() > 0 ? "  [" + e.ms() + " ms]" : "").append('\n');
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
        JDialog dlg = new JDialog(Ui.ownerWindow(owner), "Nhật ký", java.awt.Dialog.ModalityType.MODELESS);
        JTextArea area = new JTextArea(build(engine, 1500));
        area.setEditable(false);
        area.setFont(Theme.current().monoSmallFont);
        area.setCaretPosition(0);
        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setBorder(javax.swing.BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        root.add(Ui.dialogHeader("Nhật ký",
                "Mọi thao tác, thời gian phản hồi, lần gửi, đổi cài đặt, lỗi và báo lỗi của bạn (token / cookie đã che)."
                        + " Dùng để tìm nguyên nhân lỗi và cải thiện các thao tác hay dùng."),
                BorderLayout.NORTH);
        root.add(MainPanel.scroll(area), BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.add(Ui.button("Làm mới", Icons::refresh, () -> {
            area.setText(build(engine, 1500));
            area.setCaretPosition(0);
        }));
        btns.add(Ui.button("Lưu JSONL...", Icons::download, "Dạng máy đọc: mỗi dòng 1 sự kiện JSON", () -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("jcrawl-nhat-ky-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".jsonl"));
            if (fc.showSaveDialog(dlg) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
                try {
                    Files.writeString(fc.getSelectedFile().toPath(), ActivityLog.jsonl(4000), StandardCharsets.UTF_8);
                    Ui.hint(area, "Đã lưu " + fc.getSelectedFile().getName());
                } catch (Exception ex) {
                    Ui.hint(area, "Không lưu được: " + ex.getMessage());
                }
            }
        }));
        btns.add(Ui.button("Báo lỗi...", t -> Icons.alertMark(t.danger.text(), 13), "Ghi vấn đề bạn gặp vào Nhật ký",
                () -> ReportIssueDialog.open(dlg, "Nhật ký")));
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
