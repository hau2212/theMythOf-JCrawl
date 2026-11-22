package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** V_9.3: one concept = one name. Old names must not come back in UI strings (see Terms). */
class V93TermsTest {
    private static final Map<String, Pattern> BANNED = Map.ofEntries(
            Map.entry("Role -> vai trò", Pattern.compile("\\bRoles?\\b")),
            Map.entry("Group -> API / nhánh", Pattern.compile("\\bGroups?\\b")),
            Map.entry("Sub-folder -> nhánh", Pattern.compile("[Ss]ub-?folder")),
            Map.entry("Acc Crawl -> tài khoản crawl", Pattern.compile("Acc Crawl")),
            Map.entry("API gốc -> tài khoản crawl", Pattern.compile("API (GỐC|gốc|Gốc)")),
            Map.entry("Send/Replay/Forward -> Gửi", Pattern.compile("\\b(Send|Replay|Forward)\\b")),
            Map.entry("Composer -> Sửa & gửi", Pattern.compile("Composer")),
            Map.entry("Workbench -> Chặn & gửi lại", Pattern.compile("Workbench")),
            Map.entry("Lineage -> Các lần bắt", Pattern.compile("Lineage")),
            Map.entry("Spotlight -> Tìm", Pattern.compile("Spotlight")),
            Map.entry("OK:/INFO:/WARN: prefix", Pattern.compile("^(OK|INFO|WARN|ERROR): ")));
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern KEY_LIKE = Pattern.compile("^[A-Za-z0-9_.\\-/]*$");

    @Test
    void noOldNamesInUiStrings() throws Exception {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main/java/themythofcrawl/ui"))) {
            s.filter(p -> p.toString().endsWith(".java") && !p.endsWith("Terms.java")).forEach(files::add);
        }
        files.add(Path.of("src/main/java/themythofcrawl/core/Audit.java"));
        files.add(Path.of("src/main/java/themythofcrawl/core/AccessMatrix.java"));
        List<String> hits = new ArrayList<>();
        for (Path f : files) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).trim();
                if (l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") || l.startsWith("import")
                        || l.contains("putClientProperty") || l.contains("X-JCrawl")) {
                    continue;
                }
                Matcher m = LITERAL.matcher(lines.get(i));
                while (m.find()) {
                    String s = m.group(1);
                    if (KEY_LIKE.matcher(s).matches()) {
                        continue;   // action keys / ids, never shown
                    }
                    for (Map.Entry<String, Pattern> e : BANNED.entrySet()) {
                        if (e.getValue().matcher(s).find()) {
                            hits.add(f.getFileName() + ":" + (i + 1) + " [" + e.getKey() + "] " + s);
                        }
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "Old names in UI strings (use Terms):\n" + String.join("\n", hits));
    }
}
