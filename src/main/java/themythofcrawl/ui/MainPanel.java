package themythofcrawl.ui;

import java.awt.BorderLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;

import themythofcrawl.app.Engine;
import themythofcrawl.store.Workspaces;

/** Placeholder until the full UI (phase 3). */
public final class MainPanel {
    private final JPanel root = new JPanel(new BorderLayout());

    public MainPanel(Engine engine, Workspaces workspaces, UiHost host, String projectId) {
        root.add(new JLabel("theMythOfCrawl - đang dựng giao diện"), BorderLayout.CENTER);
    }

    public JPanel root() {
        return root;
    }

    public void dispose() {
    }
}
