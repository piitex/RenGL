# Custom rendering

[Documentation](../README.md) › [3D](README.md)

The [scene layer](scenes.md) is built on a small GPU abstraction that you can use directly when it isn't enough: a
particle field, a terrain, a post effect, anything you would write shaders for. You write one
[`Viewport3D.Content`](viewport-3d.md#content), and the same code and the same shader run on **OpenGL and Vulkan**.

This page is for people who know a little graphics programming. If you only need meshes, lights and models, use
[`Scene3D`](scenes.md).

All of it is in `me.piitex.engine.render3d`, and all of it runs on the render thread.

## The GPU device

A `GpuDevice` is the layer's view of the GPU. You never create one: a viewport hands you the window's device in
`Content.init`, `resize` and `render`, and the same device serves every viewport in the window.

| Method | Description |
|---|---|
| `api()` | `RenderAPI.OPENGL` or `RenderAPI.VULKAN`, for the rare case you need to know |
| `name()` | The GPU's name, for logs |
| `createBuffer(BufferType, ByteBuffer)` | A buffer with fixed contents, copied from the data's position to its limit |
| `createDynamicBuffer(BufferType, long size)` | A buffer you replace every frame with `update` |
| `createTexture(width, height, ByteBuffer rgba)` | RGBA8, tightly packed, **top row first**. Sampled with linear filtering and repeat wrapping |
| `createTexture(width, height, ByteBuffer rgba, boolean mipmaps)` | The same, and with `true` it also makes the smaller copies of the picture and filters between them (trilinear), so it doesn't shimmer when small on screen. A third more memory; `mipLevels()` tells how many there are |
| `createPipeline(PipelineDesc)` | Shaders plus fixed-function state |
| `createRenderTarget(width, height, boolean depth)` | An offscreen colour image, with a depth buffer if asked. A viewport makes the one you draw into; make your own for multi-pass effects |
| `createRenderTarget(width, height, boolean depth, int samples)` | The same with anti-aliasing: `2`, `4` or `8` samples per pixel (any other number rounds down to a power of two, and `maxSamples()` caps it). See [Anti-aliasing](#anti-aliasing) |
| `maxSamples()` | The most samples a target can have on this GPU, from 1 to 8 |
| `frameNumber()` | The window's frame being drawn, the same for every viewport that draws in it. Compare it with the last one you saw to do something once per frame, however many viewports show your content |
| `beginPass(RenderTarget, r, g, b, a)` | Starts drawing into a target, clearing it to the colour and the depth to the far plane |

`beginFrame()` and `endFrame()` also exist, but the viewport calls them around `Content.render`, so you don't.

`BufferType` is `VERTEX`, `INDEX` or `UNIFORM`. **Indices are 32-bit unsigned.**

Everything you create is yours to close: `GpuBuffer`, `GpuTexture`, `Pipeline` and `RenderTarget` all have `close()`.
Close them in `Content.dispose`, before the device goes.

### Buffers

```java nocompile
GpuBuffer vertices = device.createBuffer(BufferType.VERTEX, bytes);        // fixed
GpuBuffer globals = device.createDynamicBuffer(BufferType.UNIFORM, 16);    // changes every frame
globals.update(data);                                                      // inside render
```

`update` replaces the contents from the buffer's position to its limit (`flip()` the buffer first). It is only legal
inside `Content.render`, because only there is a frame active, and the data must not be larger than the buffer.
Dynamic buffers are kept per frame in flight, so **update every dynamic buffer every frame** you draw with it: a frame
that skips an update would see the older contents of its slot.

Allocate your `ByteBuffer`s with `MemoryUtil.memAlloc(size).order(ByteOrder.nativeOrder())` and free them with
`MemoryUtil.memFree`.

## Shaders

You write each shader **once, in GLSL 450** with Vulkan-style resource bindings:

```glsl
layout(set = 0, binding = 0) uniform Globals { mat4 mvp; };
layout(set = 0, binding = 1) uniform sampler2D albedo;
```

For Vulkan the engine compiles it to SPIR-V with shaderc. For OpenGL it turns the same SPIR-V into GLSL 330 with
SPIRV-Cross, rewriting the depth range for you. It also reflects the uniform blocks and samplers, so you declare each
only once. Inputs go in `layout(location = N)`, matching their order in the `VertexLayout`.

A shader that does not compile throws an exception with the compiler's message and the stage's name (`The fragment
shader does not compile: ...`). Create your pipelines in `init` so that a mistake shows up at start-up.

Rules the layer relies on:

- Uniform blocks use std140 layout, so a `vec3` takes 16 bytes. Prefer `vec4` and `mat4` members.
- Resources are `set = 0`, with the `binding` numbers you pass to `bindUniformBuffer` and `bindTexture`.
- There are no push constants.

### Pipelines

```java nocompile
Pipeline pipeline = device.createPipeline(PipelineDesc
        .of(vertexSource, fragmentSource, VertexLayout.of(VertexFormat.FLOAT3, VertexFormat.FLOAT2))
        .withBlend(true));
```

`PipelineDesc.of(...)` starts with depth testing **on**, back-face culling **on** and blending **off**. The `with...`
methods return a changed copy:

| Method | Effect |
|---|---|
| `withDepthTest(boolean)` | Compare against and write to the depth buffer. Needs a target with depth |
| `withCullBackFaces(boolean)` | Drop triangles that are clockwise on screen; counter-clockwise is the front |
| `withBlend(boolean)` | Alpha-blend the fragment colour over the target |
| `withFrontClockwise(boolean)` | Treat clockwise triangles as the front instead (default `false`). For geometry that was mirrored by a negative scale |

`VertexLayout.of(formats...)` describes one interleaved vertex buffer of 32-bit floats. `VertexFormat` is `FLOAT1` to
`FLOAT4`; the attribute's position in the list is its `layout(location = N)`, and `stride()` is computed.

## Drawing

`Content.render` begins a pass on the target it is given, binds, draws and ends the pass. `RenderPass` is
`AutoCloseable`, so `try`-with-resources ends it for you.

| `RenderPass` method | Description |
|---|---|
| `bindPipeline(Pipeline)` | Bind first |
| `bindVertexBuffer(GpuBuffer)`, `bindIndexBuffer(GpuBuffer)` | One vertex buffer, and optionally 32-bit indices |
| `bindUniformBuffer(binding, GpuBuffer)` | The uniform block at that `binding` |
| `bindTexture(binding, GpuTexture)` | The sampler at that `binding` |
| `draw(vertexCount)` / `drawIndexed(indexCount)` | Draw triangles |
| `end()` | Finishes the pass and submits it. The target is ready for the UI layer afterwards |

### Anti-aliasing

A target made with `samples` greater than 1 is drawn into a multisampled image, and the pass resolves it into the
ordinary image when it ends. You draw exactly as before; the same pipelines work with any sample count, and
`readPixels()` and the UI layer see the finished single-sample picture. `RenderTarget.samples()` says what you got.

```java nocompile
RenderTarget target = device.createRenderTarget(512, 512, true, 4);
try (RenderPass pass = device.beginPass(target, 0, 0, 0, 1)) {
    // draw
}   // resolved here
```

The viewport does this for its own target (see [`setSamples`](viewport-3d.md#anti-aliasing)); you only need it for
targets you make yourself.

### Conventions

The same projection matrices work on both APIs, because the layer hides the differences:

- **Clip space is +Y up with depth 0 (near) to 1 (far)** on both backends.
- Counter-clockwise triangles are the front faces.
- **Render targets have a different row order on each API:** OpenGL stores the bottom row first, Vulkan the top row
  first. `RenderTarget.isBottomLeftOrigin()` says which. The viewport corrects for it when it draws the picture, so you
  rarely care; `readPixels()` always returns the **top row first**, whatever the API.
- `RenderTarget.readPixels()` copies the image to the CPU as tightly packed RGBA8 and waits for the GPU. It is for
  screenshots and tests, not for every frame.

## A complete example

A triangle that spins, with a colour per corner. It shows buffers, a dynamic uniform updated every frame, a pipeline,
`resize` for the aspect ratio, and cleanup.

```java
import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.render3d.BufferType;
import me.piitex.engine.render3d.GpuBuffer;
import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.Pipeline;
import me.piitex.engine.render3d.PipelineDesc;
import me.piitex.engine.render3d.RenderPass;
import me.piitex.engine.render3d.RenderTarget;
import me.piitex.engine.render3d.VertexFormat;
import me.piitex.engine.render3d.VertexLayout;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.ui.containers.Container;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SpinningTriangle implements Viewport3D.Content {
    private static final String VERTEX = """
            #version 450
            layout(location = 0) in vec2 position;
            layout(location = 1) in vec3 color;
            layout(set = 0, binding = 0) uniform Globals { vec4 params; }; // x: angle in radians, y: aspect ratio
            layout(location = 0) out vec3 vColor;
            void main() {
                float c = cos(params.x), s = sin(params.x);
                vec2 p = vec2(position.x * c - position.y * s, position.x * s + position.y * c);
                gl_Position = vec4(p.x / params.y, p.y, 0.0, 1.0);
                vColor = color;
            }
            """;

    private static final String FRAGMENT = """
            #version 450
            layout(location = 0) in vec3 vColor;
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = vec4(vColor, 1.0);
            }
            """;

    private Pipeline pipeline;
    private GpuBuffer vertices;
    private GpuBuffer globals;
    private final ByteBuffer scratch = MemoryUtil.memAlloc(16).order(ByteOrder.nativeOrder());
    private float angle;
    private float aspect = 1f;

    @Override
    public void init(GpuDevice device) {
        float[] data = {
                 0.0f,  0.7f,  1f, 0f, 0f,   // x, y, r, g, b
                -0.7f, -0.5f,  0f, 1f, 0f,
                 0.7f, -0.5f,  0f, 0f, 1f,
        };
        ByteBuffer bytes = MemoryUtil.memAlloc(data.length * 4).order(ByteOrder.nativeOrder());
        bytes.asFloatBuffer().put(data);
        vertices = device.createBuffer(BufferType.VERTEX, bytes);
        MemoryUtil.memFree(bytes);

        globals = device.createDynamicBuffer(BufferType.UNIFORM, 16);
        pipeline = device.createPipeline(PipelineDesc
                .of(VERTEX, FRAGMENT, VertexLayout.of(VertexFormat.FLOAT2, VertexFormat.FLOAT3))
                .withDepthTest(false)
                .withCullBackFaces(false));
    }

    @Override
    public void resize(GpuDevice device, int width, int height) {
        aspect = (float) width / Math.max(1, height);
    }

    @Override
    public void render(GpuDevice device, RenderTarget target, float delta) {
        angle += delta;
        scratch.clear();
        scratch.putFloat(angle).putFloat(aspect).putFloat(0f).putFloat(0f).flip();
        globals.update(scratch);

        try (RenderPass pass = device.beginPass(target, 0.1f, 0.1f, 0.14f, 1f)) {
            pass.bindPipeline(pipeline);
            pass.bindUniformBuffer(0, globals);
            pass.bindVertexBuffer(vertices);
            pass.draw(3);
        }
    }

    @Override
    public void dispose(GpuDevice device) {
        pipeline.close();
        globals.close();
        vertices.close();
    }

    public static void main(String[] args) {
        Window window = new Window(new WindowOptions("Triangle").setDimensions(640, 400).setRenderer(RenderAPI.VULKAN));
        Container root = new Container(640, 400);
        window.addContainer(root);

        Viewport3D view = new Viewport3D(600, 360);
        view.setPosition(20, 20);
        view.setContent(new SpinningTriangle());
        root.addElement(view);

        new Engine().start(window);
    }
}
```

Run it with `-XstartOnFirstThread` on macOS. The same content works behind the window with
[`Background3D`](background-3d.md): `window.setBackground(new Background3D(window, new SpinningTriangle()))`.

## Limits

- Triangles only, with 32-bit indices, one vertex buffer per draw and no instancing.
- Buffers and textures are created with fixed settings: textures are RGBA8 with linear filtering and repeat wrapping,
  with mipmaps only if you ask for them; there is no way to pick a sampler (no anisotropic filtering, no clamping) or a
  texture format.
- On Vulkan, descriptor pools allow about 4096 sets per frame slot, so around **a thousand draws per frame** is the
  ceiling.
- Uploads wait for the GPU, so creating large buffers or textures in the middle of a frame stalls it. Create them in
  `init`.
- Both APIs are driven through one lowest-common-denominator interface, so there are no compute shaders, geometry
  shaders, or sampling a multisampled target in a shader (it is resolved first).

The reasons and the plans are in the [3D status document](../3d-status.md).
