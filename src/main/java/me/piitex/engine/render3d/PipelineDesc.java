package me.piitex.engine.render3d;

import java.util.List;
import java.util.Objects;

/**
 * Everything that makes up a {@link Pipeline}: the shaders and the fixed-function state.
 *
 * @param vertexSource   GLSL 450 vertex shader; resources use {@code layout(set = 0, binding = N)}
 * @param fragmentSource GLSL 450 fragment shader
 * @param vertexLayout   the vertex buffer's attributes
 * @param depthTest      compare against and write to the depth buffer (needs a render target with depth)
 * @param cullBackFaces  drop triangles that are clockwise on screen; counter-clockwise is the front
 * @param blend          alpha-blend the fragment colour over the target
 * @param frontClockwise treat clockwise triangles as the front instead, for geometry that was mirrored
 * @param extraLayouts   more vertex buffers, read at buffer bindings 1, 2 and so on: per-instance data, or a second
 *                       stream of per-vertex data. Empty for a pipeline with one buffer
 * @param additive       with {@code blend}, add the fragment colour times its alpha to the target instead of mixing,
 *                       for light-like effects (fire, sparks, glows)
 * @param depthWrite     write to the depth buffer; {@code false} still tests against it. Blended geometry usually
 *                       turns this off so transparent pixels don't hide what is drawn behind them later
 */
public record PipelineDesc(String vertexSource, String fragmentSource, VertexLayout vertexLayout,
                           boolean depthTest, boolean cullBackFaces, boolean blend, boolean frontClockwise,
                           List<VertexLayout> extraLayouts, boolean additive, boolean depthWrite) {
    public PipelineDesc {
        Objects.requireNonNull(vertexSource);
        Objects.requireNonNull(fragmentSource);
        Objects.requireNonNull(vertexLayout);
        extraLayouts = extraLayouts == null ? List.of() : List.copyOf(extraLayouts);
    }

    public PipelineDesc(String vertexSource, String fragmentSource, VertexLayout vertexLayout,
                        boolean depthTest, boolean cullBackFaces, boolean blend, boolean frontClockwise,
                        List<VertexLayout> extraLayouts) {
        this(vertexSource, fragmentSource, vertexLayout, depthTest, cullBackFaces, blend, frontClockwise, extraLayouts, false, true);
    }

    public PipelineDesc(String vertexSource, String fragmentSource, VertexLayout vertexLayout,
                        boolean depthTest, boolean cullBackFaces, boolean blend, boolean frontClockwise) {
        this(vertexSource, fragmentSource, vertexLayout, depthTest, cullBackFaces, blend, frontClockwise, List.of());
    }

    public PipelineDesc(String vertexSource, String fragmentSource, VertexLayout vertexLayout,
                        boolean depthTest, boolean cullBackFaces, boolean blend) {
        this(vertexSource, fragmentSource, vertexLayout, depthTest, cullBackFaces, blend, false, List.of());
    }

    public static PipelineDesc of(String vertexSource, String fragmentSource, VertexLayout layout) {
        return new PipelineDesc(vertexSource, fragmentSource, layout, true, true, false);
    }

    public PipelineDesc withDepthTest(boolean depthTest) {
        return new PipelineDesc(vertexSource, fragmentSource, vertexLayout, depthTest, cullBackFaces, blend, frontClockwise, extraLayouts, additive, depthWrite);
    }

    public PipelineDesc withCullBackFaces(boolean cull) {
        return new PipelineDesc(vertexSource, fragmentSource, vertexLayout, depthTest, cull, blend, frontClockwise, extraLayouts, additive, depthWrite);
    }

    public PipelineDesc withBlend(boolean blend) {
        return new PipelineDesc(vertexSource, fragmentSource, vertexLayout, depthTest, cullBackFaces, blend, frontClockwise, extraLayouts, additive, depthWrite);
    }

    /**
     * The same pipeline blending additively; turns blending on too.
     */
    public PipelineDesc withAdditive(boolean additive) {
        return new PipelineDesc(vertexSource, fragmentSource, vertexLayout, depthTest, cullBackFaces, blend || additive, frontClockwise, extraLayouts, additive, depthWrite);
    }

    public PipelineDesc withDepthWrite(boolean depthWrite) {
        return new PipelineDesc(vertexSource, fragmentSource, vertexLayout, depthTest, cullBackFaces, blend, frontClockwise, extraLayouts, additive, depthWrite);
    }

    public PipelineDesc withFrontClockwise(boolean frontClockwise) {
        return new PipelineDesc(vertexSource, fragmentSource, vertexLayout, depthTest, cullBackFaces, blend, frontClockwise, extraLayouts, additive, depthWrite);
    }

    /**
     * The same pipeline reading further vertex buffers at bindings 1, 2 and so on.
     */
    public PipelineDesc withExtraLayouts(VertexLayout... layouts) {
        return new PipelineDesc(vertexSource, fragmentSource, vertexLayout, depthTest, cullBackFaces, blend, frontClockwise, List.of(layouts), additive, depthWrite);
    }
}
