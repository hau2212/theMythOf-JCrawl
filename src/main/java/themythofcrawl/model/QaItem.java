package themythofcrawl.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/**
 * "Hỏi đáp": a question to the client / dev team about one API (Q&A) or a note (Info).
 * <p>
 * V_11.4: every screenshot has a role (step 1..n, actual result, expected result, extra, client image), a title and a
 * description, so a reader understands the context; the item has a short code (QA-1A2B3C) used to match answers
 * coming back from Excel / Google Sheets, a severity, who must answer, a due date and a conversation thread.
 * Old (V_10.4) saved items load unchanged: plain image names become "Bổ sung" images without a title.
 */
public record QaItem(String id, Kind kind, String question, List<QaImage> imgs, String answer, Status status,
                     String account, String by, long atMs, String answeredBy, long answeredAtMs,
                     Severity severity, AskTo askTo, String due, List<Msg> thread, long updatedAtMs) {

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

        public static Kind parse(String s) {
            for (Kind k : values()) {
                if (k.name().equalsIgnoreCase(Text.nz(s).trim()) || k.label.equalsIgnoreCase(Text.nz(s).trim())) {
                    return k;
                }
            }
            return QA;
        }
    }

    /** OPEN keeps its stored name (V_10.4 files) but reads "Mới". */
    public enum Status {
        OPEN("Mới"),
        SENT("Đã gửi khách hàng"),
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

        public boolean waiting() {
            return this == OPEN || this == SENT;
        }

        public static Status parse(String s) {
            String t = Text.nz(s).trim();
            for (Status st : values()) {
                if (st.name().equalsIgnoreCase(t) || st.label.equalsIgnoreCase(t)) {
                    return st;
                }
            }
            if (t.equalsIgnoreCase("Chờ trả lời")) {
                return OPEN;
            }
            return null;
        }
    }

    public enum Severity {
        LOW("Thấp"), MEDIUM("Trung bình"), HIGH("Cao");

        public final String label;

        Severity(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum AskTo {
        CUSTOMER("Khách hàng"), DEV("Dev"), PIC("PIC");

        public final String label;

        AskTo(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** One screenshot: stored file name (kept with the API), role, title, description, id once uploaded to the team. */
    public record QaImage(String name, Role role, int step, String title, String desc, String remoteId) {
        public enum Role {
            STEP("Bước"), ACTUAL("Kết quả thực tế"), EXPECTED("Kết quả mong đợi"), EXTRA("Bổ sung"), CUSTOMER("Ảnh khách hàng");

            public final String label;

            Role(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public QaImage {
            name = Text.nz(name);
            role = role == null ? Role.EXTRA : role;
            title = Text.nz(title).trim();
            desc = Text.nz(desc).trim();
            remoteId = Text.nz(remoteId);
        }

        public static QaImage of(String name) {
            return new QaImage(name, Role.EXTRA, 0, "", "", "");
        }

        /** "Bước 2: Đăng nhập bằng B" / "Kết quả thực tế" / "Bổ sung: ...". */
        public String caption() {
            String head = role == Role.STEP ? "Bước " + Math.max(1, step) : role.label;
            return title.isEmpty() ? head : head + ": " + title;
        }

        public QaImage withRemote(String id) {
            return new QaImage(name, role, step, title, desc, id);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("role", role.name());
            m.put("step", step);
            m.put("title", title);
            m.put("desc", desc);
            if (!remoteId.isEmpty()) {
                m.put("remote", remoteId);
            }
            return m;
        }

        public static QaImage fromObj(Object o) {
            if (o instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) raw;
                Role r;
                try {
                    r = Role.valueOf(Json.str(m, "role", "EXTRA"));
                } catch (RuntimeException e) {
                    r = Role.EXTRA;
                }
                return new QaImage(Json.str(m, "name", ""), r, (int) Json.num(m, "step", 0), Json.str(m, "title", ""),
                        Json.str(m, "desc", ""), Json.str(m, "remote", ""));
            }
            return o == null ? null : of(String.valueOf(o));
        }
    }

    /** One message of the conversation (question, follow-up, client answer...). */
    public record Msg(String by, long atMs, String text, boolean fromCustomer) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("by", by);
            m.put("at", atMs);
            m.put("text", text);
            m.put("customer", fromCustomer);
            return m;
        }

        @SuppressWarnings("unchecked")
        static Msg fromObj(Object o) {
            if (!(o instanceof Map<?, ?> raw)) {
                return null;
            }
            Map<String, Object> m = (Map<String, Object>) raw;
            return new Msg(Json.str(m, "by", ""), Json.num(m, "at", 0), Json.str(m, "text", ""), Json.bool(m, "customer", false));
        }
    }

    public QaItem {
        id = Text.blank(id) ? "qa-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) : id;
        kind = kind == null ? Kind.QA : kind;
        question = Text.nz(question).trim();
        imgs = imgs == null ? List.of() : List.copyOf(imgs.stream().filter(java.util.Objects::nonNull).toList());
        answer = Text.nz(answer).trim();
        status = status == null ? (answer.isEmpty() ? Status.OPEN : Status.ANSWERED) : status;
        account = Text.nz(account).trim();
        by = Text.nz(by).trim();
        atMs = atMs > 0 ? atMs : System.currentTimeMillis();
        answeredBy = Text.nz(answeredBy).trim();
        severity = severity == null ? Severity.MEDIUM : severity;
        askTo = askTo == null ? AskTo.CUSTOMER : askTo;
        due = Text.nz(due).trim();
        thread = thread == null ? List.of() : List.copyOf(thread);
        updatedAtMs = updatedAtMs > 0 ? updatedAtMs : atMs;
    }

    /** V_10.4 constructor (image names only). */
    public QaItem(String id, Kind kind, String question, List<String> images, String answer, Status status,
                  String account, String by, long atMs, String answeredBy, long answeredAtMs) {
        this(id, kind, question, images == null ? List.of() : images.stream().map(QaImage::of).toList(), answer, status,
                account, by, atMs, answeredBy, answeredAtMs, null, null, "", List.of(), 0L);
    }

    public static QaItem create(Kind kind, String question, List<String> images, String account, String by) {
        return new QaItem(null, kind, question, images, "", Status.OPEN, account, by, 0L, "", 0L);
    }

    /** Short code shown to the client and used to match answers: QA-1A2B3C. */
    public String code() {
        String raw = id.startsWith("qa-") ? id.substring(3) : id;
        raw = raw.replaceAll("[^A-Za-z0-9]", "");
        return (kind == Kind.INFO ? "IN-" : "QA-") + (raw.length() > 6 ? raw.substring(0, 6) : raw).toUpperCase(Locale.ROOT);
    }

    /** File names of the screenshots (V_10.4 accessor). */
    public List<String> images() {
        return imgs.stream().map(QaImage::name).toList();
    }

    /** Images in reading order: steps by number, actual, expected, extra, client. */
    public List<QaImage> orderedImages() {
        List<QaImage> out = new ArrayList<>(imgs);
        out.sort((a, b) -> a.role() != b.role() ? Integer.compare(a.role().ordinal(), b.role().ordinal())
                : Integer.compare(a.step(), b.step()));
        return out;
    }

    public QaItem withAnswer(String a, String who, Status st) {
        boolean changed = !Text.nz(a).trim().equals(answer);
        long now = System.currentTimeMillis();
        return new QaItem(id, kind, question, imgs, a, st, account, by, atMs, changed ? who : answeredBy,
                changed ? now : answeredAtMs, severity, askTo, due, thread, now);
    }

    public QaItem with(Kind k, String q, List<String> imageNames) {
        List<QaImage> keep = new ArrayList<>();
        for (String n : imageNames) {
            QaImage old = imgs.stream().filter(i -> i.name().equals(n)).findFirst().orElse(QaImage.of(n));
            keep.add(old);
        }
        return new QaItem(id, k, q, keep, answer, status, account, by, atMs, answeredBy, answeredAtMs, severity, askTo, due,
                thread, System.currentTimeMillis());
    }

    public QaItem withDetails(Kind k, String q, List<QaImage> images, Severity sev, AskTo to, String dueDate) {
        return new QaItem(id, k, q, images, answer, status, account, by, atMs, answeredBy, answeredAtMs, sev, to, dueDate,
                thread, System.currentTimeMillis());
    }

    public QaItem withStatus(Status st) {
        return new QaItem(id, kind, question, imgs, answer, st, account, by, atMs, answeredBy, answeredAtMs, severity, askTo,
                due, thread, System.currentTimeMillis());
    }

    public QaItem withImages(List<QaImage> images) {
        return new QaItem(id, kind, question, images, answer, status, account, by, atMs, answeredBy, answeredAtMs, severity,
                askTo, due, thread, System.currentTimeMillis());
    }

    /** Adds a message to the thread; a client message also becomes the answer (status Đã trả lời). */
    public QaItem withMessage(Msg msg) {
        List<Msg> t = new ArrayList<>(thread);
        t.add(msg);
        long now = System.currentTimeMillis();
        if (msg.fromCustomer()) {
            return new QaItem(id, kind, question, imgs, msg.text(), status == Status.CLOSED ? Status.CLOSED : Status.ANSWERED,
                    account, by, atMs, msg.by(), msg.atMs() > 0 ? msg.atMs() : now, severity, askTo, due, t, now);
        }
        return new QaItem(id, kind, question, imgs, answer, status, account, by, atMs, answeredBy, answeredAtMs, severity, askTo,
                due, t, now);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind.name());
        m.put("question", question);
        List<Object> im = new ArrayList<>();
        for (QaImage i : imgs) {
            im.add(i.toMap());
        }
        m.put("images", im);
        m.put("answer", answer);
        m.put("status", status.name());
        m.put("account", account);
        m.put("by", by);
        m.put("at", atMs);
        m.put("answered_by", answeredBy);
        m.put("answered_at", answeredAtMs);
        m.put("severity", severity.name());
        m.put("ask_to", askTo.name());
        m.put("due", due);
        List<Object> th = new ArrayList<>();
        for (Msg x : thread) {
            th.add(x.toMap());
        }
        m.put("thread", th);
        m.put("updated_at", updatedAtMs);
        return m;
    }

    public static QaItem fromMap(Map<String, Object> m) {
        List<QaImage> imgs = new ArrayList<>();
        for (Object o : Json.list(m, "images")) {
            QaImage i = QaImage.fromObj(o);
            if (i != null && !i.name().isEmpty()) {
                imgs.add(i);
            }
        }
        List<Msg> thread = new ArrayList<>();
        for (Object o : Json.list(m, "thread")) {
            Msg x = Msg.fromObj(o);
            if (x != null) {
                thread.add(x);
            }
        }
        Status st = Status.parse(Json.str(m, "status", "OPEN"));
        Severity sev;
        AskTo to;
        try {
            sev = Severity.valueOf(Json.str(m, "severity", "MEDIUM"));
        } catch (RuntimeException e) {
            sev = Severity.MEDIUM;
        }
        try {
            to = AskTo.valueOf(Json.str(m, "ask_to", "CUSTOMER"));
        } catch (RuntimeException e) {
            to = AskTo.CUSTOMER;
        }
        return new QaItem(Json.str(m, "id", null), Kind.parse(Json.str(m, "kind", "QA")), Json.str(m, "question", ""), imgs,
                Json.str(m, "answer", ""), st == null ? Status.OPEN : st, Json.str(m, "account", ""), Json.str(m, "by", ""),
                Json.num(m, "at", 0), Json.str(m, "answered_by", ""), Json.num(m, "answered_at", 0), sev, to,
                Json.str(m, "due", ""), thread, Json.num(m, "updated_at", 0));
    }
}
