package themythofcrawl.core;

/** Colour of an API / role row. */
public enum AuditLevel {
    NONE,      // not sent yet
    OK,        // green: every role 2xx, same body
    INFO,      // yellow: a role failed or differs
    WARN,      // orange: stripped-auth role got 2xx while others were denied (possible bypass)
    ERROR,     // red: 5xx / connection errors
    REDIRECT,  // blue: 3xx, or captured but not sent
    PENDING;   // grey: place in the tree not confirmed yet

    public boolean flagged() {
        return this == INFO || this == WARN || this == ERROR;
    }
}
