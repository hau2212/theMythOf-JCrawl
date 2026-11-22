package themythofcrawl.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/**
 * V_11.4 "Dự án" (du-an.json, next to the account workspaces): the accounts (A = highest, B, C...), the team and
 * their roles (PIC, PIC phụ, Thành viên), the project list decisions (added / removed APIs), the column templates,
 * the workbook sheets and the sync settings. One copy per person (in their own OneDrive); the shared part travels
 * through the team sheet (record "project"), the PIC's / PIC phụ's version wins.
 */
public final class ProjectConfig {

    public enum Role {
        PIC("PIC", "Toàn quyền"),
        DEPUTY("PIC phụ", "Như PIC, trừ xóa dự án / đổi PIC / đổi PIC phụ"),
        MEMBER("Thành viên", "Crawl tài khoản được giao, tạo Q&A");

        public final String label;
        public final String explain;

        Role(String label, String explain) {
            this.label = label;
            this.explain = explain;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum BaselineMode {
        STANDARD("File Excel chuẩn"),
        FROM_A("Dựng từ API tài khoản A đã crawl"),
        BOTH("File chuẩn + API A bắt được mà file chuẩn không có");

        public final String label;

        BaselineMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public record Account(String letter, String name, String owner) {
        public Account {
            letter = Text.nz(letter).trim().toUpperCase(Locale.ROOT);
            name = Text.nz(name).trim();
            owner = Text.nz(owner).trim();
        }

        /** "A · admin". */
        public String display() {
            return name.isEmpty() || name.equalsIgnoreCase(letter) ? letter : letter + " · " + name;
        }
    }

    public record Member(String name, Role role, List<String> accounts, String note) {
        public Member {
            name = Text.nz(name).trim();
            role = role == null ? Role.MEMBER : role;
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            note = Text.nz(note);
        }
    }

    /** One column of a template: id (see {@link Columns}), shown header, visible. */
    public record Col(String id, String header, boolean visible) {
    }

    public record Profile(String name, List<Col> cols) {
        public Profile {
            cols = List.copyOf(cols);
        }
    }

    /** One API approved into the project list from a crawl ("Chỉ B có" / "Ngoài danh sách"). */
    public record Added(String id, String method, String path, String branch, String host) {
    }

    public String id = "prj-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    public String name = "Dự án";
    public long created = System.currentTimeMillis();
    public final List<Account> accounts = new ArrayList<>();
    public final List<Member> members = new ArrayList<>();
    public BaselineMode baseline = BaselineMode.BOTH;
    public final List<Added> added = new ArrayList<>();
    public final Set<String> excluded = new LinkedHashSet<>();
    public final List<Profile> profiles = new ArrayList<>();
    public String activeProfile = "Nội bộ";
    public final List<SheetDef> sheets = new ArrayList<>();
    /** Excel list (TargetList json) shared with members who joined from another machine. */
    public String targetsJson;
    public long updatedAt;
    public String updatedBy = "";

    // ---- sync (local only: never sent to the team sheet)
    public String sheetUrl = "";
    public String sheetPass = "";
    public int syncSeconds = 12;
    public boolean syncOn = false;
    public String me = "";
    public boolean sendRedactedRequests = false;

    public ProjectConfig() {
        profiles.addAll(Columns.presets());
        sheets.addAll(SheetDef.defaults());
    }

    // ------------------------------------------------------------------ accounts / members
    public Account account(String letter) {
        for (Account a : accounts) {
            if (a.letter().equalsIgnoreCase(letter)) {
                return a;
            }
        }
        return null;
    }

    public String accountDisplay(String letter) {
        Account a = account(letter);
        return a == null ? letter : a.display();
    }

    /** Next free letter: A, B, ... Z, then AA... */
    public String nextLetter() {
        Set<String> used = new LinkedHashSet<>();
        for (Account a : accounts) {
            used.add(a.letter());
        }
        for (int i = 0; i < 26 * 27; i++) {
            String l = i < 26 ? String.valueOf((char) ('A' + i)) : String.valueOf((char) ('A' + i / 26 - 1)) + (char) ('A' + i % 26);
            if (!used.contains(l)) {
                return l;
            }
        }
        return "Z" + accounts.size();
    }

    public Account ensureAccount(String letter, String name) {
        Account a = account(letter);
        if (a == null) {
            a = new Account(letter, name, "");
            accounts.add(a);
            accounts.sort((x, y) -> x.letter().length() != y.letter().length()
                    ? Integer.compare(x.letter().length(), y.letter().length()) : x.letter().compareTo(y.letter()));
        } else if (a.name().isEmpty() && !Text.blank(name)) {
            accounts.set(accounts.indexOf(a), new Account(letter, name, a.owner()));
            a = account(letter);
        }
        return a;
    }

    public Member member(String who) {
        for (Member m : members) {
            if (m.name().equalsIgnoreCase(Text.nz(who).trim())) {
                return m;
            }
        }
        return null;
    }

    public Role roleOf(String who) {
        Member m = member(who);
        if (m != null) {
            return m.role();
        }
        // A project nobody manages yet: whoever has it is its PIC.
        return members.stream().noneMatch(x -> x.role() == Role.PIC) ? Role.PIC : Role.MEMBER;
    }

    /** PIC or PIC phụ: project list, columns, sheets, conflicts, publishing, client rounds, restore. */
    public boolean canManage(String who) {
        Role r = roleOf(who);
        return r == Role.PIC || r == Role.DEPUTY;
    }

    public boolean isPic(String who) {
        return roleOf(who) == Role.PIC;
    }

    public String pic() {
        for (Member m : members) {
            if (m.role() == Role.PIC) {
                return m.name();
            }
        }
        return "";
    }

    public void putMember(Member m) {
        Member old = member(m.name());
        if (old != null) {
            members.set(members.indexOf(old), m);
        } else {
            members.add(m);
        }
        if (m.role() == Role.PIC) {
            for (int i = 0; i < members.size(); i++) {
                Member x = members.get(i);
                if (x != m && x.role() == Role.PIC && !x.name().equalsIgnoreCase(m.name())) {
                    members.set(i, new Member(x.name(), Role.DEPUTY, x.accounts(), x.note()));
                }
            }
        }
    }

    /** PIC phụ takes over (the PIC left / is away); the old PIC becomes PIC phụ. */
    public boolean takeOverPic(String deputy) {
        Member d = member(deputy);
        if (d == null || d.role() != Role.DEPUTY) {
            return false;
        }
        putMember(new Member(d.name(), Role.PIC, d.accounts(), d.note()));
        return true;
    }

    public Profile profile(String name) {
        for (Profile p : profiles) {
            if (p.name().equalsIgnoreCase(Text.nz(name))) {
                return p;
            }
        }
        return profiles.isEmpty() ? Columns.presets().get(0) : profiles.get(0);
    }

    public void putProfile(Profile p) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).name().equalsIgnoreCase(p.name())) {
                profiles.set(i, p);
                return;
            }
        }
        profiles.add(p);
    }

    public SheetDef sheet(String id) {
        for (SheetDef s : sheets) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    public void touch(String who) {
        updatedAt = System.currentTimeMillis();
        updatedBy = Text.nz(who);
    }

    // ------------------------------------------------------------------ json
    /** @param shared true = the part sent to the team (no sheet URL / passcode / local identity) */
    public Map<String, Object> toMap(boolean shared) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 1);
        m.put("id", id);
        m.put("name", name);
        m.put("created", created);
        List<Object> acc = new ArrayList<>();
        for (Account a : accounts) {
            acc.add(Map.of("letter", a.letter(), "name", a.name(), "owner", a.owner()));
        }
        m.put("accounts", acc);
        List<Object> mem = new ArrayList<>();
        for (Member x : members) {
            mem.add(Map.of("name", x.name(), "role", x.role().name(), "accounts", new ArrayList<>(x.accounts()), "note", x.note()));
        }
        m.put("members", mem);
        m.put("baseline", baseline.name());
        List<Object> add = new ArrayList<>();
        for (Added a : added) {
            add.add(Map.of("id", a.id(), "method", Text.nz(a.method()), "path", Text.nz(a.path()), "branch", Text.nz(a.branch()),
                    "host", Text.nz(a.host())));
        }
        m.put("added", add);
        m.put("excluded", new ArrayList<>(excluded));
        List<Object> pf = new ArrayList<>();
        for (Profile p : profiles) {
            List<Object> cols = new ArrayList<>();
            for (Col c : p.cols()) {
                cols.add(Map.of("id", c.id(), "header", Text.nz(c.header()), "visible", c.visible()));
            }
            pf.add(Map.of("name", p.name(), "cols", cols));
        }
        m.put("profiles", pf);
        m.put("active_profile", activeProfile);
        List<Object> sh = new ArrayList<>();
        for (SheetDef s : sheets) {
            sh.add(s.toMap());
        }
        m.put("sheets", sh);
        if (targetsJson != null) {
            m.put("targets", targetsJson);
        }
        m.put("updated_at", updatedAt);
        m.put("updated_by", updatedBy);
        if (!shared) {
            Map<String, Object> sync = new LinkedHashMap<>();
            sync.put("url", sheetUrl);
            sync.put("pass", sheetPass);
            sync.put("seconds", syncSeconds);
            sync.put("on", syncOn);
            sync.put("me", me);
            sync.put("send_requests", sendRedactedRequests);
            m.put("sync", sync);
        }
        return m;
    }

    public String toJson() {
        return Json.pretty(toMap(false));
    }

    @SuppressWarnings("unchecked")
    public static ProjectConfig fromMap(Map<String, Object> m) {
        ProjectConfig c = new ProjectConfig();
        c.applyShared(m);
        Map<String, Object> sync = Json.obj(m, "sync");
        c.sheetUrl = Json.str(sync, "url", "");
        c.sheetPass = Json.str(sync, "pass", "");
        c.syncSeconds = (int) Math.max(8, Json.num(sync, "seconds", 12));
        c.syncOn = Json.bool(sync, "on", false);
        c.me = Json.str(sync, "me", "");
        c.sendRedactedRequests = Json.bool(sync, "send_requests", false);
        return c;
    }

    /** Replaces the shared part (accounts, members, list decisions, columns, sheets) with the team's version. */
    @SuppressWarnings("unchecked")
    public void applyShared(Map<String, Object> m) {
        id = Json.str(m, "id", id);
        name = Json.str(m, "name", name);
        created = Json.num(m, "created", created);
        accounts.clear();
        for (Object o : Json.list(m, "accounts")) {
            if (o instanceof Map<?, ?> am) {
                Map<String, Object> a = (Map<String, Object>) am;
                accounts.add(new Account(Json.str(a, "letter", ""), Json.str(a, "name", ""), Json.str(a, "owner", "")));
            }
        }
        members.clear();
        for (Object o : Json.list(m, "members")) {
            if (o instanceof Map<?, ?> mm) {
                Map<String, Object> x = (Map<String, Object>) mm;
                Role r;
                try {
                    r = Role.valueOf(Json.str(x, "role", "MEMBER"));
                } catch (RuntimeException e) {
                    r = Role.MEMBER;
                }
                List<String> acc = new ArrayList<>();
                for (Object a : Json.list(x, "accounts")) {
                    acc.add(String.valueOf(a));
                }
                members.add(new Member(Json.str(x, "name", ""), r, acc, Json.str(x, "note", "")));
            }
        }
        try {
            baseline = BaselineMode.valueOf(Json.str(m, "baseline", baseline.name()));
        } catch (RuntimeException ignored) {
            baseline = BaselineMode.BOTH;
        }
        added.clear();
        for (Object o : Json.list(m, "added")) {
            if (o instanceof Map<?, ?> am) {
                Map<String, Object> a = (Map<String, Object>) am;
                added.add(new Added(Json.str(a, "id", ""), Json.str(a, "method", ""), Json.str(a, "path", ""),
                        Json.str(a, "branch", ""), Json.str(a, "host", "")));
            }
        }
        excluded.clear();
        for (Object o : Json.list(m, "excluded")) {
            excluded.add(String.valueOf(o));
        }
        List<Object> pf = Json.list(m, "profiles");
        if (!pf.isEmpty()) {
            profiles.clear();
            for (Object o : pf) {
                if (o instanceof Map<?, ?> pm) {
                    Map<String, Object> p = (Map<String, Object>) pm;
                    List<Col> cols = new ArrayList<>();
                    for (Object co : Json.list(p, "cols")) {
                        if (co instanceof Map<?, ?> cm) {
                            Map<String, Object> cc = (Map<String, Object>) cm;
                            cols.add(new Col(Json.str(cc, "id", ""), Json.str(cc, "header", ""), Json.bool(cc, "visible", true)));
                        }
                    }
                    profiles.add(new Profile(Json.str(p, "name", "Mẫu"), cols));
                }
            }
        }
        activeProfile = Json.str(m, "active_profile", activeProfile);
        List<Object> sh = Json.list(m, "sheets");
        if (!sh.isEmpty()) {
            sheets.clear();
            for (Object o : sh) {
                if (o instanceof Map<?, ?> sm) {
                    SheetDef d = SheetDef.fromMap((Map<String, Object>) sm);
                    if (d != null) {
                        sheets.add(d);
                    }
                }
            }
        }
        Object t = m.get("targets");
        if (t instanceof String ts && !ts.isBlank()) {
            targetsJson = ts;
        }
        updatedAt = Json.num(m, "updated_at", updatedAt);
        updatedBy = Json.str(m, "updated_by", updatedBy);
    }

    public static ProjectConfig fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ProjectConfig();
        }
        try {
            return fromMap(Json.parseObject(json));
        } catch (RuntimeException e) {
            return new ProjectConfig();
        }
    }
}
