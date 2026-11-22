package themythofcrawl.project;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/**
 * V_11.0 "mã mời": everything a member needs to join (project id / name, team sheet link + passcode, their name,
 * role and accounts, the Excel list). Contains the sheet link and passcode: send it privately to that person.
 */
public record Invite(String projectId, String projectName, String sheetUrl, String sheetPass, String member, ProjectConfig.Role role,
                     List<String> accounts, String targetsJson, long createdMs) {

    public static final String PREFIX = "JCRAWL-MOI:";

    public String encode() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1);
        m.put("id", projectId);
        m.put("name", projectName);
        m.put("url", sheetUrl);
        m.put("pass", sheetPass);
        m.put("member", member);
        m.put("role", role.name());
        m.put("accounts", new ArrayList<>(accounts));
        if (targetsJson != null) {
            m.put("targets", targetsJson);
        }
        m.put("at", createdMs);
        byte[] raw = Json.write(m).getBytes(StandardCharsets.UTF_8);
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(raw);
        d.finish();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            bo.write(buf, 0, d.deflate(buf));
        }
        d.end();
        byte[] z = bo.toByteArray();
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(z);
    }

    public static Invite decode(String code) {
        String t = Text.nz(code).trim().replaceAll("\\s+", "");
        if (!t.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Không phải mã mời JCrawl (phải bắt đầu bằng " + PREFIX + ")");
        }
        try {
            byte[] z = Base64.getUrlDecoder().decode(t.substring(PREFIX.length()));
            Inflater inf = new Inflater();
            inf.setInput(z);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                bo.write(buf, 0, n);
            }
            inf.end();
            Map<String, Object> m = Json.parseObject(bo.toString(StandardCharsets.UTF_8));
            List<String> acc = new ArrayList<>();
            for (Object o : Json.list(m, "accounts")) {
                acc.add(String.valueOf(o));
            }
            ProjectConfig.Role r;
            try {
                r = ProjectConfig.Role.valueOf(Json.str(m, "role", "MEMBER"));
            } catch (RuntimeException e) {
                r = ProjectConfig.Role.MEMBER;
            }
            Object tj = m.get("targets");
            return new Invite(Json.str(m, "id", ""), Json.str(m, "name", ""), Json.str(m, "url", ""), Json.str(m, "pass", ""),
                    Json.str(m, "member", ""), r, acc, tj instanceof String s ? s : null, Json.num(m, "at", 0));
        } catch (RuntimeException | java.util.zip.DataFormatException e) {
            throw new IllegalArgumentException("Mã mời bị lỗi hoặc thiếu ký tự: copy lại toàn bộ mã", e);
        }
    }
}
