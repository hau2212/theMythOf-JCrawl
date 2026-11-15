package themythofcrawl.ui;

import java.awt.Component;

/** What the UI needs from Burp, so the panels can also run (and be screenshot-tested) outside Burp. */
public interface Host {
    Component requestViewer();

    Component responseViewer();

    /** Shows raw bytes in the two viewers (either may be null). */
    void showMessage(byte[] request, byte[] response, boolean secure, String host, int port);

    void log(String message);

    void error(String message);

    /** Burp edition / project name shown in the workspace panel. */
    String projectName();
}
