package themythofcrawl.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Times shown in the UI and the audit logs. */
public final class Clock {
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss");

    private Clock() {
    }

    public static String hms() {
        return LocalDateTime.now().format(HMS);
    }

    public static String stamp() {
        return LocalDateTime.now().format(STAMP);
    }
}
