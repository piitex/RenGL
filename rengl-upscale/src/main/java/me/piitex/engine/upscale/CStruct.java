package me.piitex.engine.upscale;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A C struct being filled in native memory, field by field, each at the alignment a C compiler gives it (a 64-bit target).
 * The memory is zeroed, so padding and fields that are not written are zero.
 */
public final class CStruct {
    private final MemorySegment segment;
    private long position;

    public CStruct(Arena arena, long capacity) {
        segment = arena.allocate(capacity, 8);
    }

    private void align(long to) {
        position = (position + to - 1) & -to;
    }

    public CStruct u32(int value) {
        align(4);
        segment.set(JAVA_INT, position, value);
        position += 4;
        return this;
    }

    public CStruct f32(float value) {
        align(4);
        segment.set(JAVA_FLOAT, position, value);
        position += 4;
        return this;
    }

    public CStruct u64(long value) {
        align(8);
        segment.set(JAVA_LONG, position, value);
        position += 8;
        return this;
    }

    public CStruct ptr(MemorySegment value) {
        align(8);
        segment.set(ADDRESS, position, value);
        position += 8;
        return this;
    }

    /**
     * A handle or address held in a pointer-sized field.
     */
    public CStruct ptr(long address) {
        return ptr(MemorySegment.ofAddress(address));
    }

    public CStruct bool(boolean value) {
        segment.set(JAVA_BYTE, position, (byte) (value ? 1 : 0));
        position += 1;
        return this;
    }

    /**
     * Copies {@code bytes} of a struct that is already in memory into this one, as a nested struct taken by value.
     */
    public CStruct raw(MemorySegment from, long bytes) {
        align(8);
        MemorySegment.copy(from, 0, segment, position, bytes);
        position += bytes;
        return this;
    }

    /**
     * Leaves the next {@code bytes} zero, as a field or a nested struct that is not used.
     */
    public CStruct skip(long bytes) {
        position += bytes;
        return this;
    }

    /**
     * The end of a nested struct or of the struct itself: its size is a multiple of its widest field's alignment.
     */
    public CStruct end() {
        align(8);
        return this;
    }

    /**
     * An {@code ffxApiHeader}: the structure type and the pointer to the next structure of the chain.
     */
    public CStruct header(long type) {
        return u64(type).ptr(MemorySegment.NULL);
    }

    /**
     * Chains {@code next} after this structure, whose first field is its header.
     */
    public CStruct chain(CStruct next) {
        segment.set(ADDRESS, 8, next.segment);
        return this;
    }

    /**
     * Where the next field would go.
     */
    public long position() {
        return position;
    }

    /**
     * The size of the struct as written so far, rounded up to its alignment.
     */
    public long size() {
        return (position + 7) & -8L;
    }

    public MemorySegment segment() {
        return segment;
    }
}
