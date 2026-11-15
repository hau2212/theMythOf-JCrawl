package themythofcrawl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Config;
import themythofcrawl.core.CopyFormat;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Jwt;
import themythofcrawl.core.Text;
import themythofcrawl.excel.HitLog;
import themythofcrawl.excel.NearMissIndex;
import themythofcrawl.excel.PathMatch;
import themythofcrawl.excel.TargetList;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.export.Reports;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Blob;
import themythofcrawl.model.BranchRule;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.ResultState;
import themythofcrawl.model.Role;
import themythofcrawl.model.RoleResult;
import themythofcrawl.model.RoleScopes;
import themythofcrawl.model.Roles;
import themythofcrawl.model.RuleMode;
import themythofcrawl.model.Service;
import themythofcrawl.place.Decisions;
import themythofcrawl.place.PlacementBook;

class CoreTest {
    static final TargetList.QueryMode IGNORE = TargetList.QueryMode.IGNORE;

    static ApiGroup group(int gid, String method, String path, String branch) {
        return new ApiGroup(gid, new Service("api.example.test", 443, true), method, path,
                "https://api.example.test" + path, "Proxy", 1, branch, Blob.of(new byte[1]), Blob.of(new byte[1]),
                "10:00:00", 0L);
    }

    static List<List<Object>> rows(Object[]... rows) {
        List<List<Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new ArrayList<>(Arrays.asList(r)));
        }
        return out;
    }

    // ------------------------------------------------------------------ text / hierarchy
    @Test
    void textHelpers() {
        assertEquals("/a/b", Text.stripQuery("/a/b?x=1#f"));
        assertEquals("/", Text.stripQuery(""));
        assertEquals("/users/{id}/x/{id}", Text.normalizePathIds("/users/42/x/3f2a9c1e-1111-2222-3333-444455556666?q"));
        assertEquals("GET|https://api.test:443|/a", Text.dedupKey("get", "HTTPS", "API.test", 443, "/a?x=1", false));
        assertEquals("...a/c/d", Text.shortPath("/aaaaaaa/c/d", 8));
        assertTrue(Text.isStaticPath("/x/app.JS?v=1", Text.parseStaticExtensions(".js, css")));
        assertArrayEquals2(new String[] {"POST", "/v1/x?y=1"}, Text.requestLine("POST https://h/v1/x?y=1 HTTP/1.1\r\n".getBytes()));
        assertEquals("vi tri tim thay", Text.fold("Vị_trí  tìm thấy"));
        assertEquals("/caf%C3%A9/{x}/a%2Fb", Text.pctDecode("/caf%C3%A9/%7Bx%7D/a%2Fb"));
        assertEquals("[G01:POST /v1/transfer] 01_Admin", Text.repeaterTabName(1, "post", "/v1/transfer?x=1", "01_Admin", 40, null, false));
        assertEquals("G01•01_Admin", Text.compactRepeaterTabName(1, "01_Admin", null, false));
        assertEquals("01_Admin", Text.displayRoleName("Admin", 0, true));
        assertEquals("10_Guest", Text.displayRoleName("10_Guest", 9, true));
        String wrapped = Text.wrap("• " + "chữ ".repeat(40), 30);
        for (String l : wrapped.split("\n")) {
            assertTrue(l.length() <= 30, l);
        }
        assertTrue(wrapped.split("\n")[1].startsWith("   "));
    }

    static void assertArrayEquals2(String[] a, String[] b) {
        assertEquals(Arrays.asList(a), Arrays.asList(b));
    }

    @Test
    void hierarchy() {
        assertEquals(List.of("Toppage", "menu", "button \"Search\""), Hierarchy.split(" Toppage >menu>  button \"Search\" "));
        assertEquals("Toppage", Hierarchy.normalize(""));
        assertTrue(Hierarchy.matchesPrefix("Toppage > Menu > x", "toppage > menu"));
        assertFalse(Hierarchy.matchesPrefix("Toppage", "Toppage > menu"));
        assertTrue(Hierarchy.matchesPrefix("a", ""));
        assertEquals("a > b > c", Hierarchy.append("a", "b > c"));
        assertEquals("a", Hierarchy.parent("a > b"));
        assertEquals("a", Hierarchy.parent("a"));
        assertEquals("a > x > y > c", Hierarchy.replaceAt("a > b > c", 1, "x > y"));
        assertEquals("a > c", Hierarchy.replaceAt("a > b > c", 1, ""));
        assertEquals("a > b > n > c", Hierarchy.insertAfter("a > b > c", 1, "n"));
        assertEquals("x > c", Hierarchy.replacePrefix("a > b > c", "A > B", "x"));
        assertEquals("q > r", Hierarchy.replacePrefix("q > r", "a", "x"));
        assertEquals(List.of("a", "a > b", "a > b > c"), Hierarchy.levels("a > b > c"));
    }

    // ------------------------------------------------------------------ Excel list
    static final Object[][] DEMO = {
        {"STT", "PROTOCOL", "PATH", "POSITION"},
        {1L, "GET", "/apt/test"}, {2L, "POST", "/apt/test"},
        {5L, "GET", "/apt/test/{id}/me/{session}"}, {6L, "post", "/apt/test/[id]/me/(sid)"},
        {7L, "", "https://x.test/files/<f>.json?v=1", "Toppage > contact > button \"search\""},
    };

    @Test
    void detectsColumnsAndBuildsList() {
        TargetList tl = TargetList.fromRows(rows(DEMO), "demo.xlsx", "APIs");
        assertEquals(List.of("STT", "PROTOCOL", "PATH", "POSITION"), tl.headers());
        assertEquals(5, tl.size());
        assertEquals(List.of("POST"), tl.entries().get(3).methods());
        assertEquals(List.of("ANY"), tl.entries().get(4).methods());
        assertEquals("Toppage > contact > button \"search\"", tl.entries().get(4).position());
        TargetList vi = TargetList.fromRows(rows(new Object[] {"Số thứ tự", "Phương thức", "Đường dẫn", "Vị trí"},
                new Object[] {1L, "GET, PUT", "/a/b"}), "", "");
        assertEquals(List.of("GET", "PUT"), vi.entries().get(0).methods());
        TargetList bare = TargetList.fromRows(rows(new Object[] {1L, "DELETE", "/x/{id}", "/ > c"},
                new Object[] {2L, "GET", "/y"}), "", "");
        assertEquals(List.of("/x/{id}", "/y"), bare.entries().stream().map(e -> e.path()).toList());
        assertEquals("/ > c", bare.entries().get(0).position());
    }

    @Test
    void anyBracketStyleIsAWildcard() {
        TargetList tl = TargetList.fromRows(rows(DEMO), "", "");
        assertEquals("1", tl.match("GET", "/apt/test/", false, null, false, IGNORE).sttText());
        assertEquals("5", tl.match("GET", "/apt/test/42/me/s3ss10n?x=1", false, null, false, IGNORE).sttText());
        assertEquals("6", tl.match("POST", "/APT/TEST/42/me/abc", false, null, false, IGNORE).sttText());
        assertEquals("7", tl.match("PATCH", "/files/report.json", false, null, false, IGNORE).sttText());
        assertNull(tl.match("PATCH", "/apt/test", false, null, false, IGNORE));
        assertNull(tl.match("GET", "/apt/test/42/me", false, null, false, IGNORE));
        assertNull(tl.match("GET", "/api/v1/apt/test", false, null, false, IGNORE));
        assertEquals("1", tl.match("GET", "/api/v1/apt/test", true, null, false, IGNORE).sttText());
        TargetList again = TargetList.fromJson(tl.toJson());
        assertEquals("5", again.match("GET", "/apt/test/1/me/2", false, null, false, IGNORE).sttText());
    }

    @Test
    void literalRowsBeatTemplatesAndNormalisation() {
        TargetList tl = TargetList.fromRows(rows(new Object[] {"STT", "METHOD", "PATH"}, new Object[] {1L, "GET", "/u/{id}"},
                new Object[] {2L, "GET", "/u/me"}), "", "");
        assertEquals("2", tl.match("GET", "/u/me", false, null, false, IGNORE).sttText());
        assertEquals("1", tl.match("GET", "/u/42", false, null, false, IGNORE).sttText());
        assertEquals("/a/b", PathMatch.normalize("https://h.test/a//b;jsessionid=1/?q=1#f"));
        TargetList t2 = TargetList.fromRows(rows(new Object[] {"STT", "METHOD", "PATH"},
                new Object[] {1L, "GET", "/api/users/{id}/"}, new Object[] {2L, "GET", "/files/a%2Fb"}), "", "");
        assertEquals("1", t2.match("GET", "/api//users/7;v=2?x=1", false, null, false, IGNORE).sttText());
        assertEquals("2", t2.match("GET", "/files/a%2fb", false, null, false, IGNORE).sttText());
        assertNull(t2.match("GET", "/files/a/b", false, null, false, IGNORE));
    }

    @Test
    void idsQueryAndHost() {
        TargetList tl = TargetList.fromRows(rows(new Object[] {"STT", "METHOD", "PATH"},
                new Object[] {1L, "GET", "/order/12345/items"},
                new Object[] {2L, "GET", "/doc/3f2a9c1e-1111-2222-3333-444455556666"},
                new Object[] {3L, "GET", "/order/me/items"}), "", "");
        assertEquals("1", tl.match("GET", "/order/99/items", false, null, true, IGNORE).sttText());
        assertNull(tl.match("GET", "/order/99/items", false, null, false, IGNORE));
        assertEquals("2", tl.match("GET", "/doc/abc", false, null, true, IGNORE).sttText());
        assertEquals("3", tl.match("GET", "/order/me/items", false, null, true, IGNORE).sttText());
        TargetList q = TargetList.fromRows(rows(new Object[] {"STT", "METHOD", "PATH"},
                new Object[] {1L, "GET", "/search?type=user"}, new Object[] {2L, "GET", "/search?type=shop"},
                new Object[] {3L, "GET", "/search"}, new Object[] {4L, "GET", "https://admin.test/search"}), "", "");
        assertEquals("2", q.match("GET", "/search?type=shop&q=1", false, null, false, TargetList.QueryMode.VALUES).sttText());
        assertEquals("3", q.match("GET", "/search?q=1", false, null, false, TargetList.QueryMode.VALUES).sttText());
        assertEquals("4", q.match("GET", "/search", false, "admin.test:443", false, IGNORE).sttText());
        assertFalse("4".equals(q.match("GET", "/search", false, "www.test", false, IGNORE).sttText()));
    }

    @Test
    void nearMissAndDuplicates() {
        TargetList tl = TargetList.fromRows(rows(new Object[] {"STT", "METHOD", "PATH"},
                new Object[] {1L, "GET", "/v1/users/{id}"}, new Object[] {2L, "POST", "/orders"},
                new Object[] {3L, "POST", "/orders"}), "", "");
        ApiGroup g1 = group(1, "GET", "/v2/users/5", "a");
        ApiGroup g2 = group(2, "POST", "/api/orders", "a");
        NearMissIndex idx = new NearMissIndex(List.of(g1, g2));
        NearMissIndex.Suggestion s1 = idx.suggest(tl.entries().get(0));
        assertEquals(g1, s1.group());
        assertTrue(s1.note().contains("v1"));
        NearMissIndex.Suggestion s2 = idx.suggest(tl.entries().get(1));
        assertEquals(g2, s2.group());
        assertTrue(s2.note().contains("/api"));
        assertEquals(1, tl.duplicates().size());
        assertEquals("3", tl.duplicates().get(0)[0].sttText());
    }

    @Test
    void xlsxRoundTrip(@TempDir Path dir) throws Exception {
        byte[] data = Xlsx.write(List.of(new Xlsx.OutSheet("APIs", List.of("STT", "PROTOCOL", "PATH", "POSITION"),
                rows(new Object[] {1L, "GET", "/a/{id}", "x > y"}, new Object[] {2L, "POST", "/b", null}), new int[] {6, 9, 30, 30})));
        Path f = dir.resolve("list.xlsx");
        Files.write(f, data);
        assertEquals(List.of("APIs"), Xlsx.sheetNames(f));
        Xlsx.Sheet sheet = Xlsx.read(f, 0);
        TargetList tl = TargetList.fromRows(sheet.rows(), "list.xlsx", sheet.name());
        assertEquals(2, tl.size());
        assertEquals("x > y", tl.entries().get(0).position());
        assertEquals("1", tl.match("GET", "/a/9", false, null, false, IGNORE).sttText());
    }

    // ------------------------------------------------------------------ audit
    @Test
    void auditColours() {
        ApiGroup g = group(1, "GET", "/a", "b");
        g.setOrigMeta(200, 10);
        g.origResult().setStatus(200);
        Audit.recalc(g);
        assertEquals(AuditLevel.OK, g.auditLevel());
        assertEquals("API gốc (200)", g.auditSummary());
        RoleResult admin = new RoleResult("01_Admin", "Admin", 0, false, false, List.of());
        RoleResult guest = new RoleResult("10_Guest", "Guest", 9, false, true, List.of());
        RoleResult user = new RoleResult("02_User", "User", 1, false, false, List.of());
        g.setResults(List.of(admin, user, guest));
        Audit.recalc(g);
        assertEquals("Chưa Gửi (1 Gốc + 3 Role)", g.auditSummary());
        admin.setStatus(200);
        admin.setLength(10);
        user.setStatus(403);
        guest.setStatus(200);
        guest.setLength(10);
        Audit.recalc(g);
        assertEquals(AuditLevel.WARN, guest.auditLevel());
        assertEquals("INFO: Không đủ quyền (403)", user.auditBadge());
        assertEquals("INFO: 2 OK / 1 Lỗi (403)", g.auditSummary());
        user.setStatus(200);
        user.setLength(12);
        Audit.recalc(g);
        assertEquals("INFO: 2xx lệch Body (+2B)", user.auditBadge());
        assertEquals("INFO: Cả 3 Role 2xx (Lệch Body)", g.auditSummary());
        admin.setState(ResultState.ERROR);
        admin.setStatus(null);
        admin.setError("Connection refused by the remote server");
        Audit.recalc(g);
        assertEquals("INFO: Lỗi gọi API (Connection refused by the remot...)", admin.auditBadge());
        g.setPending(true);
        assertEquals(AuditLevel.PENDING, Audit.display(g));
        Audit.Verdict v = Audit.branch(List.of(g, group(2, "GET", "/x", "b")));
        assertEquals("INFO: 1/2 API có Role lỗi/lệch", v.summary());
    }

    // ------------------------------------------------------------------ roles
    @Test
    void rolesJsonTemplateAndScopes() {
        List<Role> t = Roles.template();
        assertEquals(10, t.size());
        assertTrue(t.get(9).strippedAuth());
        assertFalse(t.get(0).strippedAuth());
        assertEquals(RuleMode.COOKIE_SET, t.get(3).rules().get(0).mode());
        List<Role> back = Roles.fromJson(Roles.toJson(t));
        assertEquals(t, back);
        List<Role> flexible = Roles.fromJson("[{\"name\":\"A\",\"headers\":{\"X-A\":\"1\"}},"
                + "{\"headers\":[\"Cookie: [REMOVE]\", {\"name\":\"bad name\",\"value\":1}]}]");
        assertEquals("X-A", flexible.get(0).rules().get(0).name());
        assertEquals("Role_02", flexible.get(1).name());
        assertEquals(1, flexible.get(1).rules().size());
        assertEquals(RuleMode.REMOVE, flexible.get(1).rules().get(0).mode());
        assertEquals(3, Roles.parseBulkCookies("Cookie: a=1; b=[REMOVE]\nc=3").size());
        assertEquals(RuleMode.COOKIE_REMOVE, Roles.parseBulkCookies("b=[REMOVE]").get(0).mode());
        assertEquals(List.of("X-A", "X-B"), Roles.parseBulkHeaders("X-A: 1\nnot a header\nX-B: [REMOVE]")
                .stream().map(HeaderRule::name).toList());

        List<Role> roles = List.of(new Role("A", true, List.of()), new Role("B", false, List.of()), new Role("C", true, List.of()));
        List<BranchRule> rules = List.of(new BranchRule("/ > contact", Map.of("A", false, "B", true)),
                new BranchRule("/ > contact > button", Map.of("A", true)));
        assertEquals(List.of("A", "C"), names(RoleScopes.effective(roles, "/ > shop", rules, null)));
        assertEquals(List.of("B", "C"), names(RoleScopes.effective(roles, "/ > contact > x", rules, null)));
        assertEquals(List.of("A", "B", "C"), names(RoleScopes.effective(roles, "/ > contact > button", rules, null)));
        assertEquals(List.of("A", "B"), names(RoleScopes.effective(roles, "/ > contact > button", rules, Map.of("C", false))));
        assertEquals(List.of(0, 2), RoleScopes.effective(roles, "/ > shop", rules, null).stream().map(RoleScopes.Indexed::index).toList());

        ApiGroup g1 = group(1, "GET", "/a", "/ > contact > button");
        g1.setResults(List.of(new RoleResult("01_A", "A", 0, false, false, List.of()), new RoleResult("03_C", "C", 2, false, false, List.of())));
        RoleScopes.Preview p = RoleScopes.preview(roles, List.of(g1), List.of(), List.of("C"), false, RoleScopes.Scope.BRANCH,
                List.of("/ > contact"));
        assertEquals(new RoleScopes.Preview(1, 0, 1), p);
        assertEquals("/ > contact", RoleScopes.branchCounts(List.of(g1)).get(1).getKey());
    }

    static List<String> names(List<RoleScopes.Indexed> l) {
        return l.stream().map(i -> i.role().name()).toList();
    }

    // ------------------------------------------------------------------ config / copy
    @Test
    void configAndCopyFormat() {
        Config c = Config.fromMap(Map.of("threads", 500, "send_profile", "fast", "copy_format", " ",
                "target_query_mode", "VALUES", "copy_stt_fallback", "bogus", "capture_enabled", "yes"));
        assertEquals(64, c.threads);
        assertEquals(60, c.sendDelayMs);
        assertEquals(4, c.hostConcurrency);
        assertEquals("{stt}. {path}", c.copyFormat);
        assertEquals(Config.QueryModeOption.VALUES, c.targetQueryMode);
        assertEquals(Config.CopyFallback.NONE, c.copySttFallback);
        assertTrue(c.captureEnabled);
        assertEquals(c.toMap(), Config.fromMap(c.toMap()).toMap());
        assertEquals("200. / > contact > button \"send\"", CopyFormat.line("{stt}. {path}", 200, "/ > contact > button \"send\""));
        assertEquals("/ > contact", CopyFormat.line("{stt}. {path}", null, "/ > contact"));
        assertEquals("[7] GET GET /x - / > a", CopyFormat.line("[{stt}] {method} {endpoint} - {path}", 7, "/ > a", "GET", "", "", "GET /x"));
    }

    // ------------------------------------------------------------------ placement / hits
    @Test
    void placementBook() {
        PlacementBook b = new PlacementBook();
        b.ensure("k", "POST", "h", "/x?y=1");
        assertEquals("/x", b.get("k").path);
        b.live("k", 7, true);
        assertEquals(PlacementBook.State.PENDING, b.get("k").state());
        b.deleted("k", "/ > a", null);
        assertEquals("/ > a", b.get("k").place);
        assertFalse(b.get("k").placeConfirmed);
        assertEquals(PlacementBook.State.DELETED, b.get("k").state());
        b.setPlace("k", "/ > b");
        assertTrue(b.get("k").placeConfirmed);
        assertEquals(1, b.nextCopyNo("k"));
        assertEquals(2, b.nextCopyNo("k"));
        assertEquals(1, b.renamePrefix("/ > b", "/ > c"));
        b.event(PlacementBook.Action.MOVE, "k", 7, "POST", "/x?z", "/ > a", "/ > c", "n");
        PlacementBook copy = new PlacementBook();
        copy.load(b.toJson(), List.of());
        assertEquals("/ > c", copy.get("k").place);
        assertNull(copy.get("k").gid);
        assertEquals(1, copy.log().size());
        assertEquals("/x", copy.log().get(0).path());
        assertEquals(1, Decisions.logRows(copy, "k", "").size());
        assertEquals(0, Decisions.logRows(copy, "other", "").size());
        assertEquals(1, Decisions.placeRows(copy, 2, "").size());
        assertEquals(0, Decisions.placeRows(copy, 1, "").size());
        assertEquals(1, copy.deletedUnder("/").size());
    }

    @Test
    void hitLog() {
        TargetList tl = TargetList.fromRows(rows(new Object[] {"STT", "METHOD", "PATH"}, new Object[] {200L, "POST", "/v1/t"}), "f.xlsx", "S");
        HitLog h = new HitLog();
        h.reset(tl.signature());
        h.record(tl.entries().get(0), HitLog.Result.NEW, 1, "POST", "/v1/t", "h", "/ > a", "/ > a", "Proxy", "");
        h.record(tl.entries().get(0), HitLog.Result.DUP, 1, "POST", "/v1/t?x=2", "h", "/ > b", "/ > a", "Proxy", "");
        assertEquals("2 lần (1 mới · 1 gặp lại)", h.cell(tl.entries().get(0)));
        assertEquals(List.of("/ > b", "/ > a"), h.positions(tl.entries().get(0)));
        HitLog again = new HitLog();
        again.load(h.toJson(), tl.signature());
        assertEquals(2, again.count(tl.entries().get(0)));
        again.load(h.toJson(), "other|S|1");
        assertEquals(0, again.count(tl.entries().get(0)));
        assertEquals(2, Reports.hitRows(tl, h).size());
    }

    @Test
    void decisionsAndDupRows() {
        ApiGroup first = group(1, "POST", "/contact/send", "/ > a");
        ApiGroup dup = group(2, "POST", "/contact/send", "/ > b");
        first.setDupNo(1);
        dup.setDupNo(2);
        dup.setDupPending(true);
        dup.setPending(true);
        first.setDupTotal(2);
        dup.setDupTotal(2);
        assertEquals(Decisions.Kind.DUP, Decisions.kind(dup));
        String text = Decisions.text(dup, List.of(first, dup));
        assertTrue(text.contains("bản trùng #2") && text.contains("/ > a"), text);
        assertEquals("[⧉2/2]", Decisions.dupBadge(dup));
        assertEquals("⏳ API trùng #2 - giữ ở chỗ mới?", Decisions.pendingLabel(dup));
        List<Decisions.Row<ApiGroup>> rows = Decisions.dupRows(Map.of("k", List.of(dup, first)), false);
        assertEquals(List.of("#1 / 2", "#2 / 2"), rows.stream().map(r -> r.cells().get(1)).toList());
        dup.setHidden(true);
        assertEquals(List.of(dup), Decisions.dupRows(Map.of("k", List.of(dup, first)), true).stream().map(Decisions.Row::payload).toList());
    }

    // ------------------------------------------------------------------ exports
    @Test
    void workbookHasAllSheets() throws Exception {
        TargetList tl = TargetList.fromRows(rows(new Object[] {"STT", "METHOD", "PATH"}, new Object[] {12L, "POST", "/v1/transfer"},
                new Object[] {13L, "GET", "/nope"}), "f.xlsx", "S");
        ApiGroup g1 = group(1, "POST", "/v1/transfer", "Toppage > menu");
        g1.setTarget(tl.entries().get(0));
        ApiGroup g2 = group(2, "POST", "/v1/outside", "Toppage");
        List<List<Object>> cov = Reports.coverageRows(tl, List.of(g1, g2), "{stt}. {path}", true, null);
        assertEquals("12. Toppage > menu", cov.get(0).get(3));
        assertEquals("Chưa bắt", cov.get(1).get(4));
        assertEquals(1, Reports.outsideRows(List.of(g1, g2), null).size());
        PlacementBook book = new PlacementBook();
        book.ensure("k", "POST", "h", "/v1/transfer");
        book.event(PlacementBook.Action.CAPTURE, "k", 1, "POST", "/v1/transfer", null, "Toppage", "");
        byte[] data = Reports.workbook(List.of(g1, g2), tl, "{stt}. {path}", true, g -> "x", new HitLog(), book);
        int sheets = 0;
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (e.getName().startsWith("xl/worksheets/sheet")) {
                    sheets++;
                }
            }
        }
        assertEquals(7, sheets);
        String hist = Reports.history(List.of(), List.of(g1));
        assertTrue(hist.contains("API 1 [G01]  POST /v1/transfer"), hist);
        assertTrue(Reports.matrixJson(List.of(g1), List.of()).contains("\"api_label\": \"API 1\""));
    }

    @Test
    void jwtPreview() {
        String tok = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZSIsInJvbGUiOiJhZG1pbiJ9.sig";
        String p = Jwt.preview("Bearer " + tok);
        assertTrue(p.startsWith("JWT #1  [sub=alice, role=admin]"), p);
        assertNotNull(Jwt.preview("none"));
    }
}
