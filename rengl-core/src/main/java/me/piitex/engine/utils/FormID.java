package me.piitex.engine.utils;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * An identifier with two faces: a packed numeric value for fast equality/hashing
 * (namespace + category + local ID, bit-packed into one long), and a human-readable
 * {@link #getLabel() label} such as {@code "ELEMENT:Button#42"} for anything a person
 * reads, including logs, debuggers, and crash reports.
 * <p>
 * The two use different granularities. The packed numeric form uses a class's fully
 * qualified name as its category key (see {@link FormIDRegistry}) so it stays
 * collision-safe across two classes with the same simple name in different packages.
 * The label uses the short simple name instead, which keeps a log line readable rather
 * than printing {@code me.piitex.engine.ui.overlays.TextOverlay} every time. Keeping
 * them as two separate fields serves both collision-safety and readability without
 * compromising either.
 * <p>
 * localId is 24 bits, allowing up to 16,777,215 values per category. That leaves room
 * well beyond a million without producing a number too long to read at a glance. A
 * category needing more range than that would require revisiting this width.
 * <p>
 * Instances are minted only by {@link FormIDRegistry}. {@link #create} below is
 * package-private to enforce that, so there is no public way to construct a FormID that
 * bypasses the registry's counters.
 */
@GeneratedClass(
        prompter = "piitex",
        model = "Gemini 3.1",
        reviewed = true
)
public final class FormID {
    private final long id;
    private final String label;

    private FormID(int namespace, int category, int localId, String label) {
        this.id = ((long) (namespace & 0xFF) << 32)
                | ((long) (category & 0xFF) << 24)
                | (localId & 0xFFFFFF);
        this.label = label;
    }

    /**
     * Package-private on purpose: only {@link FormIDRegistry} should ever call this,
     * so that every FormID that exists came from an actual registry counter.
     */
    static FormID create(int namespace, int category, int localId, String label) {
        return new FormID(namespace, category, localId, label);
    }

    /**
     * The packed numeric value: namespace in bits 32-39, category in bits 24-31, and local ID in bits 0-23. Use this for fast equality, hashing, and storage, and {@link #getLabel()} for anything a person needs to read.
     */
    public long rawValue() {
        return id;
    }

    public int getNamespace() {
        return (int) ((id >>> 32) & 0xFF);
    }

    public int getCategory() {
        return (int) ((id >>> 24) & 0xFF);
    }

    public int getLocalId() {
        return (int) (id & 0xFFFFFF);
    }

    /**
     * A human-readable form such as {@code "ELEMENT:Button#42"}, made up of the namespace name, the class's simple name rather than its fully qualified one, and a decimal local ID.
     */
    public String getLabel() {
        return label;
    }

    /**
     * The packed numeric value as zero-padded hex, such as {@code "0x0100002A"}, for low-level and debugging contexts that need the raw bits rather than {@link #getLabel()}.
     */
    public String toHexString() {
        return String.format("0x%010X", id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FormID formID = (FormID) o;
        return id == formID.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    /**
     * Returns {@link #getLabel()}. The human-readable form is the default for anything printing a FormID, such as a log line or a debugger's toString. Use {@link #toHexString()} for the raw packed bits.
     */
    @Override
    public String toString() {
        return label;
    }
}