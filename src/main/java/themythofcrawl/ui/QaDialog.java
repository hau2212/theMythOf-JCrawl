package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
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
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.filechooser.FileNameExtensionFilter;

import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.QaItem;
import themythofcrawl.ui.theme.Theme;

/**
 * V_10.4 "Hỏi đáp": write a question (Q&A) or a note (Info) about one API, attach any number of screenshots (file,
 * or paste from the clipboard with Ctrl+V), and later write the client's / dev's answer.
 */
final class QaDialog {
    private QaDialog() {
    }

    static void open(MainPanel ui, ApiGroup g, QaItem existing) {
        if (g == null) {
            ui.flash("Chọn 1 API trước.");
            return;
        }
        JDialog dlg = new JDialog(Ui.ownerWindow(ui.root()), existing == null ? "Hỏi đáp mới" : "Sửa hỏi đáp",
                java.awt.Dialog.ModalityType.MODELESS);
        JComboBox<QaItem.Kind> kind = new JComboBox<>(QaItem.Kind.values());
        JComboBox<QaItem.Status> status = new JComboBox<>(QaItem.Status.values());
        JTextArea question = new JTextArea(5, 60);
        JTextArea answer = new JTextArea(4, 60);
        question.setLineWrap(true);
        question.setWrapStyleWord(true);
        answer.setLineWrap(true);
        answer.setWrapStyleWord(true);
        List<String> images = new ArrayList<>();
        if (existing != null) {
            kind.setSelectedItem(existing.kind());
            status.setSelectedItem(existing.status());
            question.setText(existing.question());
            answer.setText(existing.answer());
            images.addAll(existing.images());
        }
        JPanel thumbs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        Runnable[] redraw = new Runnable[1];
        redraw[0] = () -> {
            thumbs.removeAll();
            for (String name : new ArrayList<>(images)) {
                byte[] b = ui.engine.qaImage(g, name);
                JPanel cell = new JPanel(new BorderLayout());
                JLabel pic = new JLabel(thumb(b));
                pic.setToolTipText(name);
                cell.add(pic, BorderLayout.CENTER);
                cell.add(Ui.button("Xóa", t -> Icons.trash(t.danger.text()), "Bỏ ảnh này", () -> {
                    images.remove(name);
                    redraw[0].run();
                }), BorderLayout.SOUTH);
                thumbs.add(cell);
            }
            if (images.isEmpty()) {
                thumbs.add(Ui.muted("Chưa có ảnh. Thêm từ file hoặc dán (Ctrl+V) ảnh chụp màn hình."));
            }
            thumbs.revalidate();
            thumbs.repaint();
        };
        Runnable paste = () -> {
            try {
                var cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                if (!cb.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                    Ui.hint(thumbs, "Clipboard không có ảnh");
                    return;
                }
                Image img = (Image) cb.getData(DataFlavor.imageFlavor);
                BufferedImage bi = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB);
                bi.getGraphics().drawImage(img, 0, 0, null);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                javax.imageio.ImageIO.write(bi, "png", bo);
                String name = ui.engine.addQaImage(g, bo.toByteArray());
                if (name != null) {
                    images.add(name);
                    redraw[0].run();
                }
            } catch (Exception ex) {
                Ui.hint(thumbs, "Không dán được ảnh: " + ex.getMessage());
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
                                String name = ui.engine.addQaImage(g, Files.readAllBytes(f.toPath()));
                                if (name != null) {
                                    images.add(name);
                                }
                            } catch (Exception ex) {
                                Ui.hint(thumbs, "Không đọc được " + f.getName());
                            }
                        }
                        redraw[0].run();
                    }
                }),
                Ui.button("Dán ảnh (Ctrl+V)", Icons::copy, "Dán ảnh chụp màn hình từ clipboard", paste));
        question.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ctrl shift V"), "tmoc.paste.image");
        question.getActionMap().put("tmoc.paste.image", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                paste.run();
            }
        });
        dlg.getRootPane().registerKeyboardAction(e -> paste.run(), javax.swing.KeyStroke.getKeyStroke("ctrl V"),
                javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        redraw[0].run();

        JPanel form = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        JPanel top = new JPanel(new GridLayout(0, 1, 0, 4));
        top.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Loại:"), kind, Ui.bold("Trạng thái:"), status,
                Ui.muted(Text.apiLabel(g.gid()) + " · " + g.method() + " " + Text.shortPath(g.path(), 60))));
        form.add(top, BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        JPanel q = new JPanel(new BorderLayout(0, 2));
        q.add(Ui.bold("Câu hỏi / ghi chú"), BorderLayout.NORTH);
        q.add(new JScrollPane(question), BorderLayout.CENTER);
        center.add(q, BorderLayout.NORTH);
        JPanel imgs = new JPanel(new BorderLayout(0, 2));
        imgs.add(imgBar, BorderLayout.NORTH);
        JScrollPane thumbScroll = new JScrollPane(thumbs);
        thumbScroll.setPreferredSize(new Dimension(600, 190));
        imgs.add(thumbScroll, BorderLayout.CENTER);
        center.add(imgs, BorderLayout.CENTER);
        JPanel a = new JPanel(new BorderLayout(0, 2));
        a.add(Ui.bold("Khách hàng / dev trả lời"), BorderLayout.NORTH);
        a.add(new JScrollPane(answer), BorderLayout.CENTER);
        center.add(a, BorderLayout.SOUTH);
        form.add(center, BorderLayout.CENTER);
        form.add(Ui.flow(FlowLayout.RIGHT, Ui.secondaryBtn("Đóng", null, dlg::dispose),
                Ui.accent("Lưu", t -> Icons.save(t.accent.onSolid()), t -> t.accent, () -> {
                    if (question.getText().isBlank() && images.isEmpty()) {
                        Ui.hint(question, "Nhập câu hỏi / ghi chú hoặc thêm ảnh");
                        return;
                    }
                    QaItem.Kind k = (QaItem.Kind) kind.getSelectedItem();
                    QaItem.Status st = (QaItem.Status) status.getSelectedItem();
                    String member = ui.engine.inventoryCatalog().memberName();
                    QaItem item = existing == null
                            ? QaItem.create(k, question.getText(), images, ui.engine.workspaceName(), member)
                            : existing.with(k, question.getText(), images);
                    if (!answer.getText().isBlank() && st == QaItem.Status.OPEN) {
                        st = QaItem.Status.ANSWERED;
                    }
                    item = item.withAnswer(answer.getText(), member, st);
                    ui.engine.putQa(g, item);
                    ui.tick(true);
                    ui.flash("Đã lưu " + k.label + " cho " + Text.apiLabel(g.gid()));
                    dlg.dispose();
                })), BorderLayout.SOUTH);
        form.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        JPanel root = new JPanel(new BorderLayout());
        root.add(Ui.dialogHeader("Hỏi đáp", "Q&A: câu hỏi cần khách hàng / dev trả lời. Info: ghi chú. Ảnh và câu trả lời"
                + " được xuất vào Excel Q&A (Excel > Hỏi đáp)."), BorderLayout.NORTH);
        root.add(form, BorderLayout.CENTER);
        dlg.setContentPane(root);
        dlg.setSize(new Dimension(Theme.current().scaled(760), Theme.current().scaled(680)));
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
            double s = Math.min(1.0, Math.min(180.0 / bi.getWidth(), 120.0 / bi.getHeight()));
            return new ImageIcon(bi.getScaledInstance(Math.max(1, (int) (bi.getWidth() * s)), Math.max(1, (int) (bi.getHeight() * s)),
                    Image.SCALE_SMOOTH));
        } catch (Exception e) {
            return new ImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        }
    }

}
