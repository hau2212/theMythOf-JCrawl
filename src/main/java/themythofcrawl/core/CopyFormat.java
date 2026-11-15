package themythofcrawl.core;

import java.util.regex.Pattern;

/** 'STT. path' line of an API, e.g. '{stt}. {path}' -> '200. / > contact > button "send"'. */
public final class CopyFormat {
    private static final Pattern STT_TOKEN = Pattern.compile("\\{stt\\}[\\s.\\-:)\\]]*");

    private CopyFormat() {
    }

    /** Without an STT the '{stt}.' part (and the separator after it) disappears. */
    public static String line(String template, Object stt, String path, String method, String url, String api,
                              String endpoint) {
        String t = Text.blank(template) ? "{stt}. {path}" : template;
        if (Text.blank(stt)) {
            t = STT_TOKEN.matcher(t).replaceAll("");
        }
        return t.replace("{stt}", Text.nz(stt)).replace("{path}", Text.nz(path)).replace("{method}", Text.nz(method))
                .replace("{url}", Text.nz(url)).replace("{api}", Text.nz(api)).replace("{endpoint}", Text.nz(endpoint))
                .trim();
    }

    public static String line(String template, Object stt, String path) {
        return line(template, stt, path, "", "", "", "");
    }
}
