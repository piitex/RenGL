package me.piitex.engine.render3d;

import java.util.ArrayList;
import java.util.List;

/**
 * How one vertex buffer is laid out: interleaved attributes of 32-bit floats. Each attribute has the
 * {@code layout(location = N)} it is read at in the vertex shader. A layout is either read once per vertex, or once per
 * instance of an instanced draw.
 */
public final class VertexLayout {
    /**
     * How often the GPU moves to the next element of the buffer.
     */
    public enum Rate {
        /**
         * Every vertex has its own element.
         */
        VERTEX,
        /**
         * Every instance has its own element, shared by all of its vertices: the way to give each copy of a mesh in an
         * instanced draw its own transform or colour.
         */
        INSTANCE
    }

    public record Attribute(int location, VertexFormat format, int offset) {
    }

    private final List<Attribute> attributes;
    private final int stride;
    private final Rate rate;

    private VertexLayout(List<Attribute> attributes, int stride, Rate rate) {
        this.attributes = List.copyOf(attributes);
        this.stride = stride;
        this.rate = rate;
    }

    public List<Attribute> attributes() {
        return attributes;
    }

    public int stride() {
        return stride;
    }

    public Rate rate() {
        return rate;
    }

    /**
     * Per-vertex attributes at locations 0, 1, 2 and so on.
     */
    public static VertexLayout of(VertexFormat... formats) {
        return of(0, Rate.VERTEX, formats);
    }

    /**
     * Attributes at consecutive locations from {@code firstLocation}, read at the given rate. A pipeline's first layout
     * is bound at buffer binding 0 and {@link PipelineDesc#extraLayouts()} at 1, 2 and so on; the locations of all of
     * them must not overlap.
     */
    public static VertexLayout of(int firstLocation, Rate rate, VertexFormat... formats) {
        List<Attribute> list = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < formats.length; i++) {
            list.add(new Attribute(firstLocation + i, formats[i], offset));
            offset += formats[i].byteSize();
        }
        return new VertexLayout(list, offset, rate);
    }
}
