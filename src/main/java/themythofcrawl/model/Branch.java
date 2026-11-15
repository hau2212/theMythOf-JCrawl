package themythofcrawl.model;

import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;

/** A named branch (Toppage > menu > button "Search"); APIs captured while it is the destination go here. */
public final class Branch {
    private final int id;
    private volatile String name;
    private final String created;

    public Branch(int id, String name, String created) {
        this.id = id;
        String clean = Text.nz(name).trim();
        this.name = clean.isEmpty() ? "Chức năng " + id : (clean.contains(">") ? Hierarchy.normalize(clean) : clean);
        this.created = created == null ? Clock.hms() : created;
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void rename(String n) {
        String clean = Text.nz(n).trim();
        name = clean.isEmpty() ? "Chức năng " + id : (clean.contains(">") ? Hierarchy.normalize(clean) : clean);
    }

    public String created() {
        return created;
    }

    public String label() {
        return Text.featureLabel(id, name);
    }
}
