package themythofcrawl.util;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/** Folder names that are valid on Windows, macOS and Linux. */
public final class Names {
    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private Names() {
    }

    /**
     * {@code dir/name} for a user-chosen name. If the file system cannot encode the name (e.g. Burp on
     * Linux with a non-UTF-8 locale and Vietnamese letters) the accent-free form is used instead.
     */
    public static Path child(Path dir, String name) {
        String safe = safeFolderName(name);
        try {
            return dir.resolve(safe);
        } catch (InvalidPathException e) {
            return dir.resolve(safeFolderName(ascii(safe)));
        }
    }

    /** "Dự án" -> "Du an" */
    public static String ascii(String s) {
        String t = Normalizer.normalize(s.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        StringBuilder sb = new StringBuilder();
        for (char c : t.toCharArray()) {
            sb.append(c < 0x80 ? c : '_');
        }
        return sb.toString();
    }

    /** "Dự án: A/B?" -> "Dự án_ A_B_" ; never empty, never a reserved device name, at most 80 chars. */
    public static String safeFolderName(String raw) {
        String s = raw == null ? "" : raw.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || "<>:\"/\\|?*".indexOf(c) >= 0 ? '_' : c);
        }
        String out = sb.toString();
        while (out.endsWith(".") || out.endsWith(" ")) {
            out = out.substring(0, out.length() - 1);
        }
        if (out.length() > 80) {
            out = out.substring(0, 80).trim();
        }
        if (out.isEmpty()) {
            out = "workspace";
        }
        String base = out.contains(".") ? out.substring(0, out.indexOf('.')) : out;
        if (RESERVED.contains(base.toUpperCase(Locale.ROOT))) {
            out = "_" + out;
        }
        return out;
    }
}
