package me.piitex.engine.render3d.gl;

import me.piitex.engine.render3d.BufferType;
import me.piitex.engine.render3d.GpuBuffer;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;

final class GlBuffer implements GpuBuffer {
    private final BufferType type;
    private final boolean dynamic;
    private final long size;
    int id;

    GlBuffer(BufferType type, long size, ByteBuffer data, boolean dynamic) {
        this.type = type;
        this.size = size;
        this.dynamic = dynamic;
        id = glGenBuffers();
        int target = target(type);
        glBindBuffer(target, id);
        if (data != null) glBufferData(target, data, dynamic ? GL_DYNAMIC_DRAW : GL_STATIC_DRAW);
        else glBufferData(target, size, GL_DYNAMIC_DRAW);
        glBindBuffer(target, 0);
    }

    static int target(BufferType type) {
        return switch (type) {
            case VERTEX -> GL_ARRAY_BUFFER;
            case INDEX -> GL_ELEMENT_ARRAY_BUFFER;
            case UNIFORM -> GL_UNIFORM_BUFFER;
        };
    }

    @Override
    public BufferType type() {
        return type;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public void update(ByteBuffer data) {
        if (!dynamic) throw new IllegalStateException("This buffer was made with fixed contents");
        if (data.remaining() > size) throw new IllegalArgumentException("Data is larger than the buffer");
        int target = target(type);
        glBindBuffer(target, id);
        glBufferData(target, size, GL_DYNAMIC_DRAW); // orphan the old storage so the driver need not wait for it
        glBufferSubData(target, 0, data);
        glBindBuffer(target, 0);
    }

    @Override
    public void close() {
        if (id != 0) glDeleteBuffers(id);
        id = 0;
    }
}
