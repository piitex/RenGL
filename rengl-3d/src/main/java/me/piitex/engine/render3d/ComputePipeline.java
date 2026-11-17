package me.piitex.engine.render3d;

/**
 * A compiled compute shader, made with {@link GpuDevice#createComputePipeline}.
 */
public interface ComputePipeline extends AutoCloseable {
    @Override
    void close();
}
