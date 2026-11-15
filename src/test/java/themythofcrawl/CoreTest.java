package themythofcrawl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.capture.CaptureService;
import themythofcrawl.capture.CaptureSettings;
import themythofcrawl.excel.TargetList;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.model.CapturedApi;
import themythofcrawl.model.HierarchyPath;
import themythofcrawl.util.Json;
import themythofcrawl.util.Names;
import themythofcrawl.workspace.Workspace;
import themythofcrawl.workspace.WorkspaceManager;
import themythofcrawl.workspace.WorkspaceStore;

class CoreTest {
    @TempDir
    Path tmp;

    static final byte[] REQ = ("POST /v1/transfer?x=1 HTTP/1.1\r\nHost: api.example.test\r\nContent-Length: 2\r\n\r\n{}")
            .getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] RESP = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}"
            .getBytes(StandardCharsets.ISO_8859_1);

    // ------------------------------------------------------------------ util
    @Test
    void jsonRoundTripKeepsUnicodeAndNesting() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Đăng nhập \"x\" \\ /");
        m.put("list", List.of(1L, 2.5, true, "a"));
        m.put("nil", null);
        Map<String, Object> back = Json.parseObject(Json.pretty(m));
        assertEquals(m, back);
    }

    @Test
    void folderNamesAreSafeOnWindows() {
        assertEquals("Dự án_ A_B_", Names.safeFolderName("Dự án: A/B?"));
        assertEquals("_CON", Names.safeFolderName("CON"));
        assertEquals("workspace", Names.safeFolderName("  ..  "));
        assertEquals("Temporary Project", Names.safeFolderName("Temporary Project"));
    }

    @Test
    void hierarchyPaths() {
        assertEquals("/ > contact > button \"search\"", HierarchyPath.normalize("/>contact >  button \"search\" "));
        assertTrue(HierarchyPath.startsWith("Toppage > menu > b", "toppage > MENU"));
        assertFalse(HierarchyPath.startsWith("Toppage", "Toppage > menu"));
    }

    // ------------------------------------------------------------------ Excel
    static List<List<Object>> demoRows() {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(Arrays.asList("STT", "PROTOCOL", "PATH", "POSITION"));
        rows.add(Arrays.asList(1L, "GET", "/apt/test"));
        rows.add(Arrays.asList(2L, "POST", "/apt/test"));
        rows.add(Arrays.asList(5L, "GET", "/apt/test/{id}/me/{session}"));
        rows.add(Arrays.asList(6L, "post", "/apt/test/[id]/me/(sid)"));
        rows.add(Arrays.asList(7L, "", "https://x.test/files/<f>.json?v=1", "Toppage > contact > button \"search\""));
        return rows;
    }

    @Test
    void anyBracketStyleIsAWildcardSegment() {
        TargetList t = TargetList.fromRows(demoRows(), "demo.xlsx", "Sheet1");
        assertEquals(List.of("STT", "PROTOCOL", "PATH", "POSITION"), t.headers());
        assertEquals("1", t.match("GET", "/apt/test/", false).stt);
        assertEquals("5", t.match("GET", "/apt/test/42/me/s3ss10n?x=1", false).stt);
        assertEquals("6", t.match("POST", "/APT/TEST/42/me/abc", false).stt);
        assertEquals("7", t.match("PATCH", "/files/report.json", false).stt);
        assertNull(t.match("PATCH", "/apt/test", false));
        assertNull(t.match("GET", "/apt/test/42/me", false));
        assertNull(t.match("GET", "/api/v1/apt/test", false));
        assertEquals("1", t.match("GET", "/api/v1/apt/test", true).stt);
        assertEquals("Toppage > contact > button \"search\"", t.entries().get(4).position);
        TargetList back = TargetList.fromJson(t.toJson());
        assertEquals("5", back.match("GET", "/apt/test/1/me/2", false).stt);
    }

    @Test
    void headersInVietnameseOrNoHeaderAtAll() {
        List<List<Object>> vi = new ArrayList<>();
        vi.add(Arrays.asList("Số thứ tự", "Phương thức", "Đường dẫn", "Vị trí"));
        vi.add(Arrays.asList(1L, "GET, PUT", "/a/b"));
        assertEquals(List.of("GET", "PUT"), TargetList.fromRows(vi, "", "").entries().get(0).methods);
        List<List<Object>> none = new ArrayList<>();
        none.add(Arrays.asList(1L, "DELETE", "/x/{id}", "/ > c"));
        TargetList t = TargetList.fromRows(none, "", "");
        assertEquals("/x/{id}", t.entries().get(0).path);
        assertEquals("/ > c", t.entries().get(0).position);
    }

    @Test
    void literalRowsBeatTemplates() {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(Arrays.asList("STT", "METHOD", "PATH"));
        rows.add(Arrays.asList(1L, "GET", "/u/{id}"));
        rows.add(Arrays.asList(2L, "GET", "/u/me"));
        TargetList t = TargetList.fromRows(rows, "", "");
        assertEquals("2", t.match("GET", "/u/me", false).stt);
        assertEquals("1", t.match("GET", "/u/42", false).stt);
    }

    /** Workbook laid out like Excel writes it: shared strings, numeric cells, relationship-mapped sheet. */
    static byte[] excelLikeWorkbook(List<List<Object>> rows) throws IOException {
        List<String> strings = new ArrayList<>();
        StringBuilder sheet = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            List<Object> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                Object v = row.get(c);
                if (v == null) {
                    continue;
                }
                String ref = (char) ('A' + c) + Integer.toString(r + 1);
                if (v instanceof Long n) {
                    sheet.append("<c r=\"").append(ref).append("\"><v>").append(n).append("</v></c>");
                } else {
                    strings.add(v.toString());
                    sheet.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(strings.size() - 1)
                            .append("</v></c>");
                }
            }
            sheet.append("</row>");
        }
        String ns = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
        String rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        StringBuilder sst = new StringBuilder("<sst " + ns + ">");
        for (String s : strings) {
            sst.append("<si><t>").append(s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                    .append("</t></si>");
        }
        sst.append("</sst>");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(buf)) {
            entry(z, "xl/workbook.xml", "<workbook " + ns + " xmlns:r=\"" + rel + "\"><sheets>"
                    + "<sheet name=\"APIs\" sheetId=\"1\" r:id=\"rId9\"/></sheets></workbook>");
            entry(z, "xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/"
                    + "package/2006/relationships\"><Relationship Id=\"rId9\" Type=\"" + rel
                    + "/worksheet\" Target=\"worksheets/data.xml\"/></Relationships>");
            entry(z, "xl/sharedStrings.xml", sst.toString());
            entry(z, "xl/worksheets/data.xml", "<worksheet " + ns + "><sheetData>" + sheet + "</sheetData></worksheet>");
        }
        return buf.toByteArray();
    }

    static void entry(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    @Test
    void readsExcelSharedStringsAndNumbers() throws IOException {
        Path f = tmp.resolve("demo.xlsx");
        Files.write(f, excelLikeWorkbook(demoRows()));
        assertEquals(List.of("APIs"), Xlsx.sheetNames(f));
        Xlsx.Sheet s = Xlsx.read(f, 0);
        assertEquals("APIs", s.name());
        assertEquals(List.of(1L, "GET", "/apt/test"), s.rows().get(1));
        TargetList t = TargetList.fromRows(s.rows(), "demo.xlsx", s.name());
        assertEquals(5, t.size());
        assertEquals(List.of("ANY"), t.entries().get(4).methods);
    }

    @Test
    void writtenWorkbookReadsBack() throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(Arrays.asList(1, "GET", "/a?<b>&c", null, "Vị trí"));
        byte[] data = Xlsx.write(List.of(new Xlsx.OutSheet("Độ phủ", List.of("STT", "M", "P", "X", "Pos"),
                rows, new int[] {6, 8, 30, 5, 20})));
        Path f = tmp.resolve("out.xlsx");
        Files.write(f, data);
        Xlsx.Sheet s = Xlsx.read(f, 0);
        assertEquals("Độ phủ", s.name());
        assertEquals(Arrays.asList("STT", "M", "P", "X", "Pos"), s.rows().get(0));
        assertEquals(Arrays.asList(1L, "GET", "/a?<b>&c", null, "Vị trí"), s.rows().get(1));
    }

    @Test
    void doctypeIsRejected() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(buf)) {
            entry(z, "xl/workbook.xml", "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY a SYSTEM \"file:///etc/passwd\">]>"
                    + "<workbook>&a;</workbook>");
        }
        Path f = tmp.resolve("bad.xlsx");
        Files.write(f, buf.toByteArray());
        assertThrows(IOException.class, () -> Xlsx.read(f, 0));
    }

    // ------------------------------------------------------------------ workspaces
    @Test
    void defaultRootSitsInTheProjectFolder() {
        Path base = Paths.get("D:", "ANTIGRAVITY_PROJECT", "mythOfcrawl", "theMythOfCrawl_java").toAbsolutePath();
        assertEquals(base.resolve("theMythOfCrawl_workspace"),
                WorkspaceStore.defaultRoot(base.resolve("theMythOfCrawl.jar").toString()));
        assertEquals(base.resolve("theMythOfCrawl_workspace"),
                WorkspaceStore.defaultRoot(base.resolve("build").resolve("libs").resolve("theMythOfCrawl.jar").toString()));
    }

    @Test
    void workspaceSurvivesReopenWithRequestsBranchesSettingsAndExcelList() throws IOException {
        WorkspaceStore store = new WorkspaceStore(tmp.resolve(WorkspaceStore.ROOT_NAME));
        List<String> errors = new ArrayList<>();
        WorkspaceManager mgr = new WorkspaceManager(store, errors::add);
        Workspace w = mgr.openOrCreate("Temporary Project", "Dự án A");
        w.putSettings(CaptureSettings.defaults().with("captureEnabled", true).with("inScopeOnly", false).toMap());
        TargetList targets = TargetList.fromRows(demoRows(), "demo.xlsx", "Sheet1");
        w.setTargetsJson(targets.toJson());
        w.setActiveBranch("/ > contact > button \"search\"");
        CaptureService capture = new CaptureService(mgr, () -> targets, errors::add);
        CaptureService.Result r = capture.capture(new CaptureService.Message("POST", true, "api.example.test", 443,
                "/v1/transfer?x=1", REQ, RESP, "Proxy", true), true);
        assertNotNull(r.api(), r.skipReason());
        mgr.shutdown();

        // a new session (Burp restarted / project switched back)
        WorkspaceManager again = new WorkspaceManager(store, errors::add);
        Workspace back = again.openOrCreate("Temporary Project", "Dự án A");
        assertEquals(1, back.apiCount());
        CapturedApi api = back.apis().get(0);
        assertEquals("/v1/transfer?x=1", api.path);
        assertEquals("/ > contact > button \"search\"", api.branch);
        assertEquals(200, api.origStatus);
        assertArrayEquals(REQ, store.readMessage(back, api.id, "request"));
        assertArrayEquals(RESP, store.readMessage(back, api.id, "response"));
        assertTrue(new CaptureSettings(back.settings()).captureEnabled);
        assertEquals(5, TargetList.fromJson(back.targetsJson()).size());
        assertEquals(2, back.reserveId());
        assertEquals(List.of("Temporary Project"), store.projects());
        assertEquals(List.of("Dự án A"), store.workspaces("Temporary Project"));
        again.shutdown();
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void severalProjectsAndWorkspacesAreListedAndDeletable() throws IOException {
        WorkspaceStore store = new WorkspaceStore(tmp.resolve(WorkspaceStore.ROOT_NAME));
        store.create("Pro project", "Login");
        store.create("Pro project", "Payment");
        Workspace temp = store.create("Temporary Project", "Default");
        assertEquals(List.of("Pro project", "Temporary Project"), store.projects());
        assertEquals(List.of("Login", "Payment"), store.workspaces("Pro project"));
        assertThrows(IOException.class, () -> store.create("Pro project", "Login"));
        store.delete(temp);
        assertEquals(List.of(), store.workspaces("Temporary Project"));
    }

    // ------------------------------------------------------------------ capture filters
    @Test
    void captureFiltersDedupAndExcelOnlyMode() throws IOException {
        WorkspaceStore store = new WorkspaceStore(tmp.resolve(WorkspaceStore.ROOT_NAME));
        WorkspaceManager mgr = new WorkspaceManager(store, e -> { });
        Workspace w = mgr.openOrCreate("P", "W");
        w.putSettings(CaptureSettings.defaults().with("captureEnabled", true).toMap());
        TargetList[] list = {null};
        CaptureService c = new CaptureService(mgr, () -> list[0], e -> { });
        java.util.function.BiFunction<String, String, CaptureService.Result> cap = (m, p) ->
                c.capture(new CaptureService.Message(m, true, "h", 443, p, REQ, RESP, "Proxy", true), true);

        assertNotNull(cap.apply("GET", "/api/users").api());
        assertEquals("trùng endpoint đã bắt", cap.apply("GET", "/api/users?page=2").skipReason());
        assertEquals("file tĩnh", cap.apply("GET", "/static/app.js").skipReason());
        assertEquals("path không khớp Regex", cap.apply("GET", "/index.html").skipReason());
        assertEquals("bỏ qua OPTIONS", cap.apply("OPTIONS", "/api/x").skipReason());
        CaptureService.Result outOfScope = c.capture(new CaptureService.Message("GET", true, "h", 443, "/api/o", REQ,
                RESP, "Proxy", false), true);
        assertEquals("ngoài scope", outOfScope.skipReason());
        assertNotNull(c.capture(new CaptureService.Message("GET", true, "h", 443, "/index.html", REQ, RESP,
                "Manual", false), false).api());   // manual capture bypasses filters

        list[0] = TargetList.fromRows(demoRows(), "demo.xlsx", "Sheet1");
        w.putSettings(new CaptureSettings(w.settings()).with("targetOnly", true).toMap());
        assertEquals("không có trong danh sách Excel", cap.apply("GET", "/api/elsewhere").skipReason());
        CapturedApi listed = cap.apply("PATCH", "/files/a.json").api();   // matches STT 7 (any method)
        assertNotNull(listed);
        assertEquals("7", listed.targetStt);
        assertEquals("Toppage > contact > button \"search\"", listed.branch);   // filed by POSITION
        assertNotNull(cap.apply("GET", "/apt/test").api());   // regex no longer applies in Excel mode
        mgr.shutdown();
    }
}
