package me.piitex.engine.render3d;

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
 */
public record PipelineDesc(String vertexSource, String fragmentSource, VertexLayout vertexLayout,
                           boolean depthTest, boolean cullBackFaces, boolean blend) {
    public PipelineDesc {
        Objects.requireNonNull(vertexSource);
        Objects.requireNonNull(fragmentSource);
        Objects.requireNonNull(vertexLayout);
    }

    public static PipelineDesc of(String vertexSource, String fragmentSource, VertexLayout layout) {
        return new PipelineDesc(vertexSource, fragmentSource, layout, true, true, false);
    }

    public PipelineDesc withDepthTest(boolean depthTest) {
        return new PipelineDesc(vertexSource, fragmentSource, vertexLayout, depthTest, cullBackFaces, blend);
    }

    public PipelineDesc withCullBackFaces(boolean cull) {
        return new PipelineDesc(vertexSource, fragmentSource, vertexLayout, depthTest, cull, blend);
    }

    public PipelineDesc withBlend(boolean blend) {
        return new PipelineDesc(vertexSource, fragmentSource, vertexLayout, depthTest, cullBackFaces, blend);
    }
}
