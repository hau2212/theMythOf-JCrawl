package themythofcrawl.ui;

import java.awt.Component;

import themythofcrawl.model.Service;

/** What the UI needs from Burp: message viewers and theming (a plain Swing version in tests). */
public interface UiHost {
    /** A request or response viewer (Burp's own editor inside Burp). */
    interface Viewer {
        Component component();

        void show(Service service, byte[] message);

        /** Edited request bytes, or null when not editable / not modified. */
        byte[] edited();
    }

    Viewer requestViewer(boolean editable);

    Viewer responseViewer();

    void applyTheme(Component c);

    /** Burp's project name (workspaces are grouped by it). */
    String projectName();
}
