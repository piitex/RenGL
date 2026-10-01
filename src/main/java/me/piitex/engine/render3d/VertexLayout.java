package me.piitex.engine.render3d;

import java.util.ArrayList;
import java.util.List;

/**
 * How one vertex buffer is laid out: interleaved attributes of 32-bit floats. The attribute's position in the list is
 * its {@code layout(location = N)} in the vertex shader.
 */
public final class VertexLayout {
    public record Attribute(int location, VertexFormat format, int offset) {
    }

    private final List<Attribute> attributes;
    private final int stride;

    private VertexLayout(List<Attribute> attributes, int stride) {
        this.attributes = List.copyOf(attributes);
        this.stride = stride;
    }

    public List<Attribute> attributes() {
        return attributes;
    }

    public int stride() {
        return stride;
    }

    public static VertexLayout of(VertexFormat... formats) {
        List<Attribute> list = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < formats.length; i++) {
            list.add(new Attribute(i, formats[i], offset));
            offset += formats[i].byteSize();
        }
        return new VertexLayout(list, offset);
    }
}
