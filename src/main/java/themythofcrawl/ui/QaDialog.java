package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;

import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.QaItem;
import themythofcrawl.ui.theme.Theme;

/**
 * "Hỏi đáp" (V_11.0): a question (Q&A) or a note (Info) about one API. Every screenshot has a role (step 1..n, actual
 * result, expected result, extra, client image), a title and a description so the reader understands the context;
 * severity, who must answer, due date, a conversation thread and the answer. Images: files (several at once) or
 * paste (Ctrl+V).
 */
final class QaDialog {
    private QaDialog() {
    }

    /** One editable image row. */
    private static final class Img {
        final String name;
        final JComboBox<QaItem.QaImage.Role> role = new JComboBox<>(QaItem.QaImage.Role.values());
        final JSpinner step = new JSpinner(new SpinnerNumberModel(1, 1, 99, 1));
        final JTextField title = new JTextField(28);
        final JTextArea desc = new JTextArea(2, 28);
        final String remote;

        Img(QaItem.QaImage i) {
            name = i.name();
            remote = i.remoteId();
            role.setSelectedItem(i.role());
            step.setValue(Math.max(1, i.step()));
            title.setText(i.title());
            desc.setText(i.desc());
            desc.setLineWrap(true);
            desc.setWrapStyleWord(true);
            title.putClientProperty("JTextField.placeholderText", "Tiêu đề, vd Đăng nhập bằng tài khoản B");
            desc.putClientProperty("JTextField.placeholderText", "Mô tả ngắn");
            step.setEnabled(i.role() == QaItem.QaImage.Role.STEP);
            role.addActionListener(e -> step.setEnabled(role.getSelectedItem() == QaItem.QaImage.Role.STEP));
        }

        QaItem.QaImage value() {
            QaItem.QaImage.Role r = (QaItem.QaImage.Role) role.getSelectedItem();
            return new QaItem.QaImage(name, r, r == QaItem.QaImage.Role.STEP ? (Integer) step.getValue() : 0, title.getText(), desc.getText(),
                    remote);
        }
    }

    static void open(MainPanel ui, ApiGroup g, QaItem existing) {
        if (g == null) {
            ui.flash("Chọn 1 API trước.");
            return;
        }
        JDialog dlg = new JDialog(Ui.ownerWindow(ui.root()), existing == null ? "Hỏi đáp mới" : "Sửa hỏi đáp " + existing.code(),
                java.awt.Dialog.ModalityType.MODELESS);
        JComboBox<QaItem.Kind> kind = new JComboBox<>(QaItem.Kind.values());
        JComboBox<QaItem.Status> status = new JComboBox<>(QaItem.Status.values());
        JComboBox<QaItem.Severity> severity = new JComboBox<>(QaItem.Severity.values());
        JComboBox<QaItem.AskTo> askTo = new JComboBox<>(QaItem.AskTo.values());
        JTextField due = new JTextField(9);
        due.putClientProperty("JTextField.placeholderText", "yyyy-mm-dd");
        severity.setSelectedItem(QaItem.Severity.MEDIUM);
        JTextArea question = new JTextArea(4, 60);
        JTextArea answer = new JTextArea(3, 60);
        question.setLineWrap(true);
        question.setWrapStyleWord(true);
        answer.setLineWrap(true);
        answer.setWrapStyleWord(true);
        List<Img> images = new ArrayList<>();
        List<QaItem.Msg> thread = new ArrayList<>();
        if (existing != null) {
            kind.setSelectedItem(existing.kind());
            status.setSelectedItem(existing.status());
            severity.setSelectedItem(existing.severity());
            askTo.setSelectedItem(existing.askTo());
            due.setText(existing.due());
            question.setText(existing.question());
            answer.setText(existing.answer());
            for (QaItem.QaImage i : existing.imgs()) {
                images.add(new Img(i));
            }
            thread.addAll(existing.thread());
        }
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        Runnable[] redraw = new Runnable[1];
        redraw[0] = () -> {
            list.removeAll();
            for (int k = 0; k < images.size(); k++) {
                Img im = images.get(k);
                int idx = k;
                JPanel row = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
                row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.current().border),
                        BorderFactory.createEmptyBorder(4, 2, 4, 2)));
                JLabel pic = new JLabel(thumb(ui.engine.qaImage(g, im.name)));
                pic.setToolTipText(im.name);
                row.add(pic, BorderLayout.WEST);
                JPanel f = new JPanel(new GridLayout(0, 1, 0, 2));
                f.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Ảnh " + (k + 1) + ":"), im.role, Ui.muted("số bước"), im.step));
                f.add(Ui.flow(FlowLayout.LEFT, Ui.muted("Tiêu đề"), im.title));
                JPanel d = new JPanel(new BorderLayout(4, 0));
                d.add(Ui.muted("Mô tả"), BorderLayout.WEST);
                d.add(new JScrollPane(im.desc), BorderLayout.CENTER);
                f.add(d);
                row.add(f, BorderLayout.CENTER);
                JPanel btn = new JPanel(new GridLayout(0, 1, 0, 2));
                btn.add(Ui.button("Lên", Icons::minus, "Đưa ảnh lên", () -> {
                    if (idx > 0) {
                        images.add(idx - 1, images.remove(idx));
                        redraw[0].run();
                    }
                }));
                btn.add(Ui.button("Xuống", Icons::plus, "Đưa ảnh xuống", () -> {
                    if (idx < images.size() - 1) {
                        images.add(idx + 1, images.remove(idx));
                        redraw[0].run();
                    }
                }));
                btn.add(Ui.button("Xóa", t -> Icons.trash(t.danger.text()), "Bỏ ảnh này", () -> {
                    images.remove(idx);
                    redraw[0].run();
                }));
                row.add(btn, BorderLayout.EAST);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
                list.add(row);
            }
            if (images.isEmpty()) {
                list.add(Ui.muted("Chưa có ảnh. Thêm từ file (chọn nhiều) hoặc dán (Ctrl+V) ảnh chụp màn hình. Mỗi ảnh: Bước 1, 2..."
                        + " / Kết quả thực tế / Kết quả mong đợi / Bổ sung, kèm tiêu đề và mô tả."));
            }
            list.revalidate();
            list.repaint();
        };
        java.util.function.Consumer<byte[]> addImage = bytes -> {
            String name = ui.engine.addQaImage(g, bytes);
            if (name != null) {
                int steps = (int) images.stream().filter(i -> i.role.getSelectedItem() == QaItem.QaImage.Role.STEP).count();
                images.add(new Img(new QaItem.QaImage(name, QaItem.QaImage.Role.STEP, steps + 1, "", "", "")));
            }
        };
        Runnable paste = () -> {
            try {
                var cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                if (!cb.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                    Ui.hint(list, "Clipboard không có ảnh");
                    return;
                }
                Image img = (Image) cb.getData(DataFlavor.imageFlavor);
                BufferedImage bi = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB);
                bi.getGraphics().drawImage(img, 0, 0, null);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                javax.imageio.ImageIO.write(bi, "png", bo);
                addImage.accept(bo.toByteArray());
                redraw[0].run();
            } catch (Exception ex) {
                Ui.hint(list, "Không dán được ảnh: " + ex.getMessage());
            }
        };
        JPanel imgBar = Ui.flow(FlowLayout.LEFT,
                Ui.button("Thêm ảnh...", Icons::upload, "Chọn 1 hoặc nhiều ảnh (png, jpg, gif, bmp)", () -> {
                    JFileChooser fc = new JFileChooser();
                    fc.setMultiSelectionEnabled(true);
                    fc.setFileFilter(new FileNameExtensionFilter("Ảnh", "png", "jpg", "jpeg", "gif", "bmp"));
                    if (fc.showOpenDialog(dlg) == JFileChooser.APPROVE_OPTION) {
                        for (File f : fc.getSelectedFiles()) {
                            try {
                                addImage.accept(Files.readAllBytes(f.toPath()));
                            } catch (Exception ex) {
                                Ui.hint(list, "Không đọc được " + f.getName());
                            }
                        }
                        redraw[0].run();
                    }
                }),
                Ui.button("Dán ảnh (Ctrl+V)", Icons::copy, "Dán ảnh chụp màn hình từ clipboard", paste));
        dlg.getRootPane().registerKeyboardAction(e -> {
            java.awt.Component f = dlg.getFocusOwner();
            if (!(f instanceof javax.swing.text.JTextComponent)) {
                paste.run();
            }
        }, javax.swing.KeyStroke.getKeyStroke("ctrl V"), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        question.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ctrl shift V"), "tmoc.paste.image");
        question.getActionMap().put("tmoc.paste.image", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                paste.run();
            }
        });
        redraw[0].run();

        // thread
        JTextArea threadView = new JTextArea(4, 60);
        threadView.setEditable(false);
        threadView.setLineWrap(true);
        Runnable showThread = () -> {
            StringBuilder sb = new StringBuilder();
            for (QaItem.Msg m : thread) {
                sb.append('[').append(themythofcrawl.project.ProjectStore.time(m.atMs())).append("] ").append(m.by())
                        .append(m.fromCustomer() ? " (khách hàng)" : "").append(": ").append(m.text()).append('\n');
            }
            threadView.setText(sb.length() == 0 ? "(chưa có trao đổi)" : sb.toString());
        };
        showThread.run();
        JTextField say = new JTextField(40);
        say.putClientProperty("JTextField.placeholderText", "Hỏi thêm / ghi lại câu trả lời...");
        JCheckBox fromClient = new JCheckBox("Khách hàng nói", false);
        String member = ui.project.ready() ? ui.project.me() : ui.engine.inventoryCatalog().memberName();
        JPanel threadBar = Ui.flow(FlowLayout.LEFT, say, fromClient, Ui.button("Thêm", Icons::plus, "Thêm vào trao đổi", () -> {
            if (say.getText().isBlank()) {
                return;
            }
            QaItem.Msg m = new QaItem.Msg(fromClient.isSelected() ? "Khách hàng" : member, System.currentTimeMillis(), say.getText().trim(),
                    fromClient.isSelected());
            thread.add(m);
            if (m.fromCustomer()) {
                answer.setText(m.text());
                status.setSelectedItem(QaItem.Status.ANSWERED);
            }
            say.setText("");
            showThread.run();
        }));

        JPanel top = new JPanel(new GridLayout(0, 1, 0, 4));
        top.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Loại:"), kind, Ui.bold("Trạng thái:"), status, Ui.bold("Mức độ:"), severity,
                Ui.bold("Cần ai trả lời:"), askTo, Ui.bold("Hạn:"), due));
        top.add(Ui.muted((existing == null ? "" : existing.code() + " · ") + Text.apiLabel(g.gid()) + " · " + g.method() + " "
                + Text.shortPath(g.path(), 80) + " · " + g.branch()));
        JPanel q = new JPanel(new BorderLayout(0, 2));
        q.add(Ui.bold("Câu hỏi / ghi chú (bối cảnh, bước tái hiện, mong đợi)"), BorderLayout.NORTH);
        q.add(new JScrollPane(question), BorderLayout.CENTER);
        JPanel imgs = new JPanel(new BorderLayout(0, 2));
        imgs.add(imgBar, BorderLayout.NORTH);
        imgs.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel ans = new JPanel(new BorderLayout(0, 2));
        JPanel tv = new JPanel(new BorderLayout(0, 2));
        tv.add(Ui.bold("Trao đổi (hỏi thêm, câu trả lời từng lượt)"), BorderLayout.NORTH);
        tv.add(new JScrollPane(threadView), BorderLayout.CENTER);
        tv.add(threadBar, BorderLayout.SOUTH);
        ans.add(tv, BorderLayout.CENTER);
        JPanel a2 = new JPanel(new BorderLayout(0, 2));
        a2.add(Ui.bold("Khách hàng / dev trả lời"), BorderLayout.NORTH);
        a2.add(new JScrollPane(answer), BorderLayout.CENTER);
        ans.add(a2, BorderLayout.SOUTH);
        JSplitPane split1 = new JSplitPane(JSplitPane.VERTICAL_SPLIT, q, imgs);
        split1.setResizeWeight(0.25);
        JSplitPane split2 = new JSplitPane(JSplitPane.VERTICAL_SPLIT, split1, ans);
        split2.setResizeWeight(0.72);
        JPanel form = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        form.add(top, BorderLayout.NORTH);
        form.add(split2, BorderLayout.CENTER);
        form.add(Ui.flow(FlowLayout.RIGHT, Ui.secondaryBtn("Đóng", null, dlg::dispose),
                Ui.accent("Lưu", t -> Icons.save(t.accent.onSolid()), t -> t.accent, () -> {
                    if (question.getText().isBlank() && images.isEmpty()) {
                        Ui.hint(question, "Nhập câu hỏi / ghi chú hoặc thêm ảnh");
                        return;
                    }
                    QaItem.Kind k = (QaItem.Kind) kind.getSelectedItem();
                    QaItem.Status st = (QaItem.Status) status.getSelectedItem();
                    List<QaItem.QaImage> ims = new ArrayList<>();
                    for (Img i : images) {
                        ims.add(i.value());
                    }
                    String letter = ui.project.ready() ? ui.project.myLetter() : ui.engine.workspaceName();
                    QaItem item = existing == null
                            ? QaItem.create(k, question.getText(), List.of(), letter, member)
                            : existing;
                    item = item.withDetails(k, question.getText(), ims, (QaItem.Severity) severity.getSelectedItem(),
                            (QaItem.AskTo) askTo.getSelectedItem(), due.getText());
                    for (int i = existing == null ? 0 : existing.thread().size(); i < thread.size(); i++) {
                        item = item.withMessage(thread.get(i));
                    }
                    if (!answer.getText().isBlank() && st.waiting()) {
                        st = QaItem.Status.ANSWERED;
                    }
                    item = item.withAnswer(answer.getText(), member, st);
                    ui.engine.putQa(g, item);
                    ui.tick(true);
                    if (ui.project.ready()) {
                        ui.project.syncSoon();
                    }
                    ui.flash("Đã lưu " + k.label + " " + item.code() + " cho " + Text.apiLabel(g.gid()));
                    dlg.dispose();
                })), BorderLayout.SOUTH);
        form.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        JPanel root = new JPanel(new BorderLayout());
        root.add(Ui.dialogHeader("Hỏi đáp", "Q&A: câu hỏi cần khách hàng / dev trả lời. Info: ghi chú. Ảnh theo bước có tiêu đề + mô tả,"
                + " xuất vào Excel (Q&A, Chi tiết Q&A) và đồng bộ với nhóm."), BorderLayout.NORTH);
        root.add(form, BorderLayout.CENTER);
        dlg.setContentPane(root);
        dlg.setSize(new Dimension(Theme.current().scaled(900), Theme.current().scaled(800)));
        dlg.setLocationRelativeTo(ui.root());
        dlg.setVisible(true);
    }

    static ImageIcon thumb(byte[] b) {
        if (b == null) {
            return new ImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        }
        try {
            BufferedImage bi = javax.imageio.ImageIO.read(new ByteArrayInputStream(b));
            if (bi == null) {
                return new ImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
            }
            double s = Math.min(1.0, Math.min(160.0 / bi.getWidth(), 100.0 / bi.getHeight()));
            return new ImageIcon(bi.getScaledInstance(Math.max(1, (int) (bi.getWidth() * s)), Math.max(1, (int) (bi.getHeight() * s)),
                    Image.SCALE_SMOOTH));
        } catch (Exception e) {
            return new ImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        }
    }
}
