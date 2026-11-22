package themythofcrawl.ui;

import java.awt.GridLayout;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import themythofcrawl.app.Engine;
import themythofcrawl.export.HarExport;
import themythofcrawl.model.ApiGroup;

/**
 * V_7.0 "Đẩy sang Logger++": replays the chosen APIs once through Burp so Logger++ (tool Extensions
 * enabled) records request + real response, or exports them as HAR for the test-management server.
 */
final class LoggerPushDialog {
    static final String PREF_MARKER = "tmoc.logger.marker.v7";

    private LoggerPushDialog() {
    }

    static void show(MainPanel ui, List<ApiGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            ui.flash("Chưa có API nào để đẩy");
            return;
        }
        Engine engine = ui.engine;
        JCheckBox only2xx = new JCheckBox("Chỉ API có response gốc 2xx", true);
        JCheckBox actions = new JCheckBox("Cho phép cả POST / PUT / PATCH / DELETE (có thể thay đổi dữ liệu thật!)", false);
        JCheckBox roles = new JCheckBox("Gửi cả bản của từng vai trò (mặc định chỉ bản gốc)", false);
        JCheckBox marker = new JCheckBox("Gắn header " + Engine.MARKER_HEADER + ": API#..;role=..;branch=.. để lọc trong Logger++",
                "1".equals(ui.uiPrefs.get(PREF_MARKER)));
        marker.setToolTipText("Tắt nếu không muốn để lại dấu vết header lạ trong log của server mục tiêu. Khi tắt, lọc "
                + "Logger++ theo Tool = Extensions.");
        JSpinner delay = new JSpinner(new SpinnerNumberModel(400, 0, 10000, 100));
        JPanel p = new JPanel(new GridLayout(0, 1, 2, 2));
        p.add(Ui.bold(groups.size() + " API sẽ được gửi lại 1 lần qua HTTP client của Burp."));
        p.add(new JLabel("Trong Logger++: bật ghi log tool Extensions để thấy các request này."));
        p.add(only2xx);
        p.add(actions);
        p.add(roles);
        p.add(marker);
        p.add(Ui.flow(java.awt.FlowLayout.LEFT, new JLabel("Giãn cách giữa 2 request (ms):"), delay));
        Object[] options = {"Đẩy sang Logger++", "Xuất HAR (không gửi)", "Hủy"};
        int choice = JOptionPane.showOptionDialog(Ui.ownerWindow(ui.root()), p, "Đẩy sang Logger++ / Xuất HAR", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        ui.uiPrefs.set(PREF_MARKER, marker.isSelected() ? "1" : "0");
        if (choice == 0) {
            if (actions.isSelected() && !ui.confirmDialog("Gửi lại cả POST/PUT/PATCH/DELETE có thể tạo / sửa / xóa dữ liệu"
                    + " thật trên server. Tiếp tục?")) {
                return;
            }
            Engine.LoggerPushOptions opt = new Engine.LoggerPushOptions(only2xx.isSelected(), actions.isSelected(),
                    roles.isSelected(), marker.isSelected(), ((Number) delay.getValue()).longValue());
            engine.pushToLogger(groups, opt, null);
            ui.flash("Đang đẩy " + groups.size() + " API sang Logger++ (xem kết quả ở tab Log)");
        } else if (choice == 1) {
            File f = ui.save("JCrawl_apis.har", "HAR", "har");
            if (f == null) {
                return;
            }
            try {
                Files.writeString(f.toPath(), HarExport.build(engine, groups, only2xx.isSelected(), roles.isSelected()),
                        StandardCharsets.UTF_8);
                ui.flash("Đã xuất HAR -> " + f.getName());
            } catch (Exception ex) {
                ui.error("Ghi HAR lỗi: " + ex.getMessage());
            }
        }
    }
}
