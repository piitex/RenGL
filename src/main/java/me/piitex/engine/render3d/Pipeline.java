package me.piitex.engine.render3d;

/**
 * Compiled shaders plus fixed-function state, made with {@link GpuDevice#createPipeline}.
 */
public interface Pipeline extends AutoCloseable {
    @Override
    void close();
}
