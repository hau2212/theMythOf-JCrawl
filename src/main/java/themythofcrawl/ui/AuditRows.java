package themythofcrawl.ui;

import themythofcrawl.core.AuditLevel;

/** A table model whose rows have an audit colour and an optional branch accent. */
public interface AuditRows {
    AuditLevel levelAt(int modelRow);

    default Object accentAt(int modelRow) {
        return null;
    }
}
