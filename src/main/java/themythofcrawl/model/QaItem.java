package themythofcrawl.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/**
 * V_10.4 "Hỏi đáp": a question to the client / dev team about one API (Q&A) or a note (Info), with screenshots,
 * the client's answer and a status. Exported as an Excel sheet with the images embedded and an answer column.
 *
 * @param images stored message names of the screenshots, kept with the API in the workspace
 */
public record QaItem(String id, Kind kind, String question, List<String> images, String answer, Status status,
                     String account, String by, long atMs, String answeredBy, long answeredAtMs) {

    public enum Kind {
        QA("Q&A", "Câu hỏi cần khách hàng / dev trả lời"),
        INFO("Info", "Ghi chú / thông tin cần lưu ý");

        public final String label;
        public final String explain;

        Kind(String label, String explain) {
            this.label = label;
            this.explain = explain;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Status {
        OPEN("Chờ trả lời"),
        ANSWERED("Đã trả lời"),
        CLOSED("Đóng");

        public final String label;

        Status(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public QaItem {
        id = Text.blank(id) ? "qa-" + UUID.randomUUID().toString().substring(0, 8) : id;
        kind = kind == null ? Kind.QA : kind;
        question = Text.nz(question).trim();
        images = images == null ? List.of() : List.copyOf(images);
        answer = Text.nz(answer).trim();
        status = status == null ? (answer.isEmpty() ? Status.OPEN : Status.ANSWERED) : status;
        account = Text.nz(account).trim();
        by = Text.nz(by).trim();
        atMs = atMs > 0 ? atMs : System.currentTimeMillis();
        answeredBy = Text.nz(answeredBy).trim();
    }

    public static QaItem create(Kind kind, String question, List<String> images, String account, String by) {
        return new QaItem(null, kind, question, images, "", Status.OPEN, account, by, 0L, "", 0L);
    }

    public QaItem withAnswer(String a, String who, Status st) {
        boolean changed = !Text.nz(a).trim().equals(answer);
        return new QaItem(id, kind, question, images, a, st, account, by, atMs, changed ? who : answeredBy,
                changed ? System.currentTimeMillis() : answeredAtMs);
    }

    public QaItem with(Kind k, String q, List<String> imgs) {
        return new QaItem(id, k, q, imgs, answer, status, account, by, atMs, answeredBy, answeredAtMs);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind.name());
        m.put("question", question);
        m.put("images", new ArrayList<>(images));
        m.put("answer", answer);
        m.put("status", status.name());
        m.put("account", account);
        m.put("by", by);
        m.put("at", atMs);
        m.put("answered_by", answeredBy);
        m.put("answered_at", answeredAtMs);
        return m;
    }

    public static QaItem fromMap(Map<String, Object> m) {
        List<String> imgs = new ArrayList<>();
        for (Object o : Json.list(m, "images")) {
            if (o != null) {
                imgs.add(String.valueOf(o));
            }
        }
        Kind k;
        Status st;
        try {
            k = Kind.valueOf(Json.str(m, "kind", "QA"));
        } catch (RuntimeException e) {
            k = Kind.QA;
        }
        try {
            st = Status.valueOf(Json.str(m, "status", "OPEN"));
        } catch (RuntimeException e) {
            st = Status.OPEN;
        }
        return new QaItem(Json.str(m, "id", null), k, Json.str(m, "question", ""), imgs, Json.str(m, "answer", ""), st,
                Json.str(m, "account", ""), Json.str(m, "by", ""), (long) Json.num(m, "at", 0),
                Json.str(m, "answered_by", ""), (long) Json.num(m, "answered_at", 0));
    }
}
