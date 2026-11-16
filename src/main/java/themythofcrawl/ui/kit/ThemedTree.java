package themythofcrawl.ui.kit;

import java.awt.Component;

import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreeModel;

/**
 * {@link JTree} that retains the extension's cell renderer, row height and interaction settings when
 * Burp (or a Swing Look &amp; Feel pass such as {@code applyThemeToComponent} / {@code updateUI})
 * attempts to replace them with a default renderer.
 */
public class ThemedTree extends JTree {
    /** Marker interface for custom tree/table renderers owned by this extension. */
    public interface ExtensionRenderer {
    }

    private final boolean ready;
    private TreeCellRenderer extensionRenderer;
    private int desiredRowHeight = -1;
    private boolean hasDesiredLargeModel;
    private boolean desiredLargeModel;
    private int desiredToggleClickCount = -1;
    private boolean updatingUi;

    public ThemedTree() {
        super();
        ready = true;
    }

    public ThemedTree(TreeModel model) {
        super(model);
        ready = true;
    }

    public ThemedTree(TreeModel model, TreeCellRenderer renderer) {
        super(model);
        ready = true;
        if (renderer != null) {
            setCellRenderer(renderer);
        }
    }

    public static boolean isExtensionRenderer(Object r) {
        if (r == null) {
            return false;
        }
        return r instanceof ExtensionRenderer || r.getClass().getPackageName().startsWith("themythofcrawl.");
    }

    public final TreeCellRenderer extensionRenderer() {
        return extensionRenderer;
    }

    @Override
    public void setCellRenderer(TreeCellRenderer x) {
        if (!ready) {
            super.setCellRenderer(x);
            return;
        }
        if (isExtensionRenderer(x)) {
            this.extensionRenderer = x;
            super.setCellRenderer(x);
        } else if (this.extensionRenderer != null) {
            super.setCellRenderer(this.extensionRenderer);
        } else {
            super.setCellRenderer(x);
        }
    }

    @Override
    public void setRowHeight(int rowHeight) {
        if (ready) {
            this.desiredRowHeight = rowHeight;
        }
        super.setRowHeight(rowHeight);
    }

    @Override
    public void setLargeModel(boolean newValue) {
        if (ready) {
            this.hasDesiredLargeModel = true;
            this.desiredLargeModel = newValue;
        }
        super.setLargeModel(newValue);
    }

    @Override
    public void setToggleClickCount(int clickCount) {
        if (ready) {
            this.desiredToggleClickCount = clickCount;
        }
        super.setToggleClickCount(clickCount);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (!ready || updatingUi) {
            return;
        }
        updatingUi = true;
        try {
            if (extensionRenderer != null) {
                if (extensionRenderer instanceof JComponent jc) {
                    jc.updateUI();
                } else if (extensionRenderer instanceof Component comp) {
                    SwingUtilities.updateComponentTreeUI(comp);
                }
                super.setCellRenderer(extensionRenderer);
            }
            if (desiredRowHeight > 0) {
                super.setRowHeight(desiredRowHeight);
            }
            if (hasDesiredLargeModel) {
                super.setLargeModel(desiredLargeModel);
            }
            if (desiredToggleClickCount >= 0) {
                super.setToggleClickCount(desiredToggleClickCount);
            }
        } finally {
            updatingUi = false;
        }
    }
}
