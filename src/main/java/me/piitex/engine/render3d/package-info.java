/**
 * The optional 3D layer. Nothing in the core engine ({@code Window}, {@code Element}, the frame loop) refers to this
 * package, so apps and 2D games that never touch it pay nothing for it.
 * <p>
 * {@link me.piitex.engine.render3d.GpuDevice} is a small backend-agnostic graphics API (buffers, textures, pipelines,
 * render targets, render passes) with an OpenGL implementation ({@code render3d.gl}) and a Vulkan one
 * ({@code render3d.vk}). Shaders are written once, as Vulkan-flavoured GLSL 450; see
 * {@link me.piitex.engine.render3d.ShaderCompiler}.
 */
package me.piitex.engine.render3d;
