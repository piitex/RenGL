package me.piitex.engine.render3d;

public enum BufferType {
    VERTEX,
    INDEX,
    UNIFORM,
    /**
     * A buffer shaders read and write as a {@code buffer Name { ... }} block, which also holds indirect draw commands. Only
     * where {@link GpuDevice#supportsVirtualGeometry()}.
     */
    STORAGE
    ;
}
