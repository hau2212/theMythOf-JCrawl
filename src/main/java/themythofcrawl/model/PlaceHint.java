package themythofcrawl.model;

/** A deleted API that came back: its remembered place, where it was just met, whether the old path was gone. */
public record PlaceHint(String old, String found, boolean oldMissing, int deleted) {
}
