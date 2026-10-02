# Vulkan and the optional 3D layer: status

A working document, not API documentation. It records what has been built for the Vulkan backend and the 3D layer,
what is known to be wrong or missing, and what comes next. Last updated 2026-10-01. The API itself is documented in
[3D](3d/README.md) and [Rendering APIs](core/rendering-apis.md).

## Goals and decisions

| Decision | Detail |
|---|---|
| Vulkan for the 2D UI | Skiko 0.999.0-SNAPSHOT (a local build of JetBrains/skiko#1201) draws the UI through Vulkan. Pick it with `WindowOptions.setRenderer(RenderAPI.VULKAN)`. |
| 3D is optional | Basic apps and 2D games must not pay for it: no 3D initialisation, no cost when unused, no 3D types in `Window` or `Element`. The `render3d` package is only touched by code that asks for it. |
| Both OpenGL and Vulkan | The 3D layer runs on whichever API the window uses. The same GLSL source and the same scene code run on both. |
| Both presentation modes | An embedded `Viewport3D` element and whole-window 3D behind the UI (`Background3D`). Both done. |
| macOS OpenGL fatal error | **Done.** `RenderAPI.resolve` throws `IllegalStateException` (and logs fatal) when OpenGL is asked for on macOS, telling the developer to use `RenderAPI.VULKAN`. |

## Build order and status

| Step | What | State | Commit |
|---|---|---|---|
| 0 | Vulkan backend for the 2D UI, `RenderAPI`, fallback with a warning when Skiko has no Vulkan, MoltenVK warning silenced | Done | `574d086`, `10a97f7`, `845af2a`, `2946d14` |
| 1 | Spike: wrap an external Vulkan image in Skia | Done (spike code was never in the repo) | none |
| 2 | GPU abstraction for GL and Vulkan, first triangle | Done | `a5577be` |
| 3 | Background presenter: whole-window 3D behind the UI (`Background3D`) | Done, Vulkan only tested | see below |
| 4 | `Viewport3D` element | Done | `c1574f9` |
| 5 | Scene layer: meshes, materials, lights, camera, glTF/OBJ | Done | `013130e` |
| 5b | `Scene3DDemo` | Done | `b9e7804` |
| 6 | Ray picking, MSAA, mipmaps, `clicked` input event | Done | `944270f` |
| 7 | In-repo tests: JUnit, picking and model/animation tests, a GPU smoke test (Vulkan and GL, skips without a GPU) | Done | `c84b88c` and later |
| 8 | Instance stream and instancing, PBR with normal, metal-rough, occlusion and emissive maps, shadows, skybox, skinning and animation, anisotropic filtering, picking BVH, batched Vulkan uploads, growing descriptor pools | Done | `b254254` |
| 9 | macOS OpenGL fatal error, renderer release when the engine stops | Done | `432bf97` |
| 10 | Spot lights with shadows, fog, additive blending, blended surfaces no longer write depth, allocation-free bulk updates of `Instances` | Done | see `git log` |

Step 3 was skipped on purpose at first ("I don't need a background mode right this second"), then built afterwards.

## What exists

### Vulkan backend for the UI

- `VulkanContext` owns the instance, device, queue and swapchain. `VulkanSkikoRenderer` draws Skia into the swapchain
  image each frame. Skia leaves the image in `COLOR_ATTACHMENT_OPTIMAL`; `endFrame()` moves it to the present layout.
- `RenderAPI` (`AUTO`, `OPENGL`, `METAL`, `VULKAN`). If the Skiko jar has no `makeVulkan`, the engine logs a warning and
  falls back instead of crashing with `NoSuchMethodError`.
- macOS runs Vulkan through MoltenVK. Metal's "primitive restart" warnings are silenced through `VK_EXT_layer_settings`.

### `GpuInterop` (the only core hook the 3D layer needs)

`gl.renderer.GpuInterop` gives GPU code what it needs to share Skia's context: `gpuApi()`, `skiaContext()`,
`vulkanContext()`, `pixelScale()` and `onDispose(Runnable)`. Only `SkikoRenderer` (OpenGL) and `VulkanSkikoRenderer`
implement it. Metal and the CPU snapshot renderer do not, so a `Viewport3D` there warns once and draws nothing.

### GPU abstraction (`render3d`, `render3d.gl`, `render3d.vk`)

- Interfaces: `GpuDevice`, `GpuBuffer`, `GpuTexture`, `Pipeline` and `PipelineDesc`, `RenderTarget`, `RenderPass`,
  `VertexLayout`, `ShaderCompiler`. Implemented by `GlDevice` (GL 3.3 core) and `VkGpuDevice` (Vulkan 1.1, VMA, a frame ring of 2).
- **MSAA:** `createRenderTarget(w, h, depth, samples)` and `maxSamples()` (1 to 8). A multisampled target keeps a single-sample
  colour image that the pass resolves into, so Skia, `readPixels` and everything else still see one sample per pixel. On Vulkan
  the render pass has a resolve attachment (render passes are made per sample count and depth) and pipelines are built per
  render pass, so `PipelineDesc` has no sample count. On GL a second framebuffer holds multisampled renderbuffers and `end()`
  blits them into the texture's framebuffer. `Viewport3D` and `Background3D` default to 4 samples (`setSamples`).
- **Mipmaps:** `createTexture(w, h, rgba, mipmaps)`. Vulkan blits level to level (`vkCmdBlitImage`) and falls back to no mips if the
  format can't blit; GL uses `glGenerateMipmap`. The scene layer turns them on for material textures. **Anisotropic filtering** is on
  at 8x (or the GPU's limit; `rengl.3d.anisotropy` overrides): a Vulkan sampler feature that `VulkanContext` already enables, and
  `GL_EXT_texture_filter_anisotropic` on GL. `maxAnisotropy()` reports it.
- **Instancing:** `VertexLayout.Rate.INSTANCE`, extra vertex buffers (`PipelineDesc.extraLayouts`, `bindVertexBuffer(binding, buffer)`)
  and `draw`/`drawIndexed(count, instances, firstInstance)`. Vulkan has this natively; GL 3.3 has no base instance, so the pass
  moves the instance attribute pointers `firstInstance` elements in at each draw.
- **Cube textures** (`createCubeTexture`) and **sampled render targets** (`createSampledTarget`, `RenderTarget.texture()`). A sampled
  Vulkan target uses its own render pass that leaves the colour in `SHADER_READ_ONLY_OPTIMAL`; its texture uses a clamped nearest or
  linear sampler. The scene's shadow map is one, with depth packed into the colour channels.
- **Vulkan frame machinery:** uploads made in a frame are recorded into one command buffer that is submitted ahead of the next pass (no
  `vkQueueWaitIdle` per mesh); descriptor pools grow when full, so there is no draw ceiling; a dynamic buffer whose frame slot missed
  an update is brought up to date from the slot that has the latest data before it is read.
- `PipelineDesc.frontClockwise` (the scene uses it for mirrored nodes). The 6-argument constructor still exists.
- Shaders are one GLSL 450 source. shaderc compiles it to SPIR-V for Vulkan. SPIRV-Cross turns the same SPIR-V into GLSL 330 for GL.
- Conventions the code relies on: clip space is +Y up with depth 0..1 on both backends. GL targets have a bottom-left origin (`isBottomLeftOrigin()`), Vulkan ones top-left. `readPixels()` returns the top row first.
- `Gpu3D` (package-private) makes one `GpuDevice` per window on the first draw of any viewport, and closes it when the window's renderer is disposed.

### `Viewport3D`

- An `Element` that is `Focusable`, `Draggable` and `Scrollable`. A `Content` renders into an offscreen target at
  `width * pixelScale * resolutionScale`. The target is wrapped as a Skia render target, snapshotted, and drawn like an image.
- An optional `InputListener` receives pointer, drag, scroll, key and focus events. Only the left button drags.
- A `Content` can be shared by several viewports.
- `InputListener.clicked(x, y)` fires after `dragEnded` when the mouse never left a 4 point radius of the press. `Background3D` does the same from the game `Input`.

### Scene layer (`render3d.scene`, `render3d.math`)

- **Picking:** `Camera.screenRay`, `Scene3D.pick(x, y, w, h)`, `pick(Ray)`, `pickAll(Ray)`, `Node3D.setPickable`. A box test in scene space, then a per-mesh BVH (built on first pick, median split on centroids, leaves of up to 4 triangles) with Moller-Trumbore in the mesh's own space (so non-uniform scale works). Instanced nodes are tested per instance (`Hit.instance()`); skinned meshes are posed on the CPU and searched without a stored BVH. Back faces are skipped unless the material is double-sided, matching what is drawn.
- `Scene3D` (root `Node3D`, `Camera`, up to 8 lights, ambient, background colour or `Skybox`, shadow settings, animation players, update handler), `Node3D` (TRS, children, mesh and material, instances, skin, shadow flags), `Mesh` (cube, sphere, plane, computed normals, optional skinning data), `Material` (classic: colour, texture, specular, shininess; PBR: metallic, roughness, normal, metallic-roughness, occlusion and emissive maps; unlit, double-sided, blend, alpha cutoff), `Light` (directional, point, spot; one directional or spot light can cast shadows), `Skybox` (gradient, cube, equirect), `Instances`, `Skin`, `AnimationClip`, `AnimationPlayer`, `OrbitControls`.
- `Model.load` reads glTF, GLB, OBJ, FBX and Collada through LWJGL Assimp. It is CPU only, so it is safe on a background thread. glTF materials load as PBR with their maps; bones become a `Skin` and `Mesh.setSkinning` data (4 bones a vertex, 256 a mesh); animations become `AnimationClip`s (`Model.animations()`, `Model.player()`).
- `SceneRenderer` (one per device): a forward renderer. Each frame it collects the visible nodes, sorts the opaque ones by pipeline, material and mesh, and writes one 176-byte record per node or instance into an instance-rate vertex buffer (model matrix, normal matrix, tint, material parameters, flags); adjacent draws with the same mesh and material merge into one instanced call. One shader pair covers classic Blinn-Phong, PBR (GGX, Smith, Schlick; ambient as a diffuse plus a rough specular term, no IBL) and unlit, chosen per record, with normal mapping from screen-space derivatives. A shadow-casting directional light adds a depth pass into a sampled target first: an orthographic fit around a bounding sphere of the camera's frustum slice up to the shadow distance, snapped to texels, packed RGBA8 depth, 3x3 PCF with a normal offset and a slope-scaled bias. The skybox draws as a full-screen triangle. Per-render-call resource sets (frame block, instance buffer, joint buffers) stop a scene shown in two viewports from overwriting its own data within a frame. Mesh and texture caches are evicted after 600 undrawn frames.

### `Background3D` (whole-window 3D)

- A `Background` (the existing window-background hook), so `Window` and the engine loop did not change:
  `window.setBackground(new Background3D(window, scene))`. It wraps a full-window `Viewport3D`, so it renders the same way
  (offscreen target at window size times `pixelScale`, drawn by Skia as an image) and takes the same `Content`, which means a scene works in a viewport or behind the window.
- Engine order is clear, window background (the 3D), then every container, so the UI always draws over it.
- Input comes from the window's game `Input`, i.e. what the UI did not take: a press on empty space or on a panel with no click listener drags the
  listener (left button), the wheel scrolls, mouse movement and unhandled keys are forwarded. Buttons, sliders and text fields keep their own clicks.
- `setEnabled(false)` hides it and stops rendering. `setResolutionScale`, `setContent`, `setInputListener`, `close()`.
- Demo: `me.piitex.examples.demo.Background3DDemo`.

### Demo

`me.piitex.examples.demo.Scene3DDemo`: a main viewport (floor, textured cube, specular sphere, glass sphere, loaded model, orbiting point light),
a second small viewport with its own scene, sliders and check boxes for the lights, spin and glass, and a file dialog to load a model.
Run it with `-XstartOnFirstThread` on macOS. `-Drengl.demo.api=VULKAN|OPENGL` overrides the API (default Vulkan on macOS, OpenGL elsewhere; OPENGL on macOS is now the fatal error).

## Verification so far

| Check | Result |
|---|---|
| Vulkan UI on macOS (Apple M5, MoltenVK) | Works. Validation layer clean apart from one layout warning that only the `rengl.vulkan.dump` debug aid causes. |
| 3D on Vulkan, real engine window | Two viewports sharing a device, UI over and behind, rounded corners, resize, second-window close. Clean. |
| 3D on GL 3.3 (macOS harness with `-Dskiko.macos.opengl.enabled=true`) | Visually identical to Vulkan, upright, Skia draws fine after the 3D pass. |
| `Background3DDemo` on Vulkan | A frame dump shows the 3D filling the window with the UI over it. The same validation layout error as the dump aid appears once after frame 90. Input forwarding was tested without a window by feeding the engine's `Input` press, move, wheel and key events: it produced hover, drag start, drag, wheel, drag end and key events as designed. Real mouse clicks were not driven. |
| `Scene3DDemo` | Default scene dumped as a frame on Vulkan and checked by eye. The file dialog, sliders, check boxes, drag and scroll were **not** driven. Not run on GL. |
| Update handler once per frame, `setContinuous(false)` | Vulkan, validation layer on: two viewports sharing a scene ran the handler 120 times in 2 s at 60 fps (once per frame); a non-continuous viewport drew 0 times while idle and once after `requestRender()`, and its picture stayed on screen. No validation errors. Not run on GL, and the input-triggered redraw path was not driven with a real mouse |
| Picking | 22 headless checks (no GPU): cube and scaled+rotated box distances against a brute-force march, culled and double-sided from inside, a mirrored node, a sphere under a scaled parent, `setPickable`, visibility, `pickAll` order, `screenRay` at the centre and edges, the far clip, `Mat4.inverse`. Plus, on a real window, picking at each object's projected centre hit that object and the rendered pixel there had its colour |
| MSAA, mipmaps | Vulkan (validation layer on, 0 errors) and GL 3.3, both on the Apple M5: a triangle rendered at 1, 2, 4, 8 and 16 (clamped to 8) samples gives 0 gray edge pixels at 1 and 129 to 278 above that; a 1-pixel checker at a grazing angle has a standard deviation of 40.8 without mips and 0.0 with. Zoomed crops show smooth sphere edges at 4 samples and stair-steps at 1. Skia composited the GL result correctly (upright, rounded corners) |
| `clicked` | Viewport: click, drag, drag-and-return and a 5 point move. `Background3D` through the engine's `Input`: the same cases. Not driven with a real mouse |
| Mirrored nodes | A cube with a negative X scale draws solid on both APIs |
| Windows, Linux | **Never run.** |
| Automated tests in the repo | `./mvnw test` runs 68 tests: `PickingTest` (11, picking and the math under it), `ModelAnimationTest` (10: a generated skinned, animated PBR glTF loads with its maps, skin, joints and clip; playback, fades, step and linear keys; skinned picking in the animated pose; instance picking; BVH against brute force over 300 random rays; the copied skeleton), `RenderAPITest` (4, including the macOS fatal error) and `GpuSmokeTest`, which opens a hidden window per API and checks MSAA, mipmaps, picking against rendered pixels, a mirrored node, **and a feature scene** (the pillar's shadow darkens the floor, the sky colour fills the top, three instances show their own tints, an emissive cube glows, a PBR sphere takes its colour), **a spot-light scene** (the cone lights the floor, outside it gets only ambient light, a box shadows the floor) **and an effects scene** (a near surface is barely fogged, a far one is the fog colour, an additive quad adds to the sky instead of covering it). The GPU tests skip when the machine has no usable GPU or the API (OpenGL on macOS). `mvn package` runs them too; pass `-DskipTests` on a build machine without a GPU if a driver crash is a worry |
| Visual checks of the new features | On Vulkan (validation layer on: 0 errors) and on GL 3.3 through the harness: PBR spheres from dielectric to metal, soft shadows landing in the same place on both APIs (the shadow lookup flips Y by `isBottomLeftOrigin`), emissive, 120 tinted instances, the three skybox kinds (sampled pixels are identical on both APIs), a normal-mapped dome lit from the upper right on both (this caught a tangent-frame bug that depended on the screen's Y direction), and a skinned bar bent 60 degrees by its animation with its shadow following the pose. The skinned and animated model is checked by pixels in the unit tests only through CPU picking; the GPU bend was checked by eye |
| Spot light, fog, additive | Vulkan and GL 3.3 through the harness: the spot cone, its soft edge and the box's shadow look the same on both; an additive red quad turns the green sky orange and a fogged blue cube magenta, a far cube fades to the fog colour. Checked by pixels in the smoke test on Vulkan, by eye on GL |
| Code samples in the docs | The complete samples in [3D](3d/README.md), [Models](3d/models.md) and [Custom rendering](3d/custom-rendering.md) were compiled against the engine. The first-scene and spinning-triangle samples were run on Vulkan and dumped as frames. The snippets in [Lighting](3d/lighting.md), [Instancing](3d/instancing.md) and [Animation](3d/animation.md) were compiled against the engine but not run |

Useful switches: `-Drengl.vulkan.validation=true` (with `-Dorg.lwjgl.vulkan.libname=/usr/local/lib/libvulkan.1.dylib` on macOS),
`-Drengl.vulkan.dump=path.png` (writes frame 90), `-Dskiko.macos.opengl.enabled=true` for the GL harness.

## Known issues and limits

### Correctness and robustness

1. Fixed in this stage: the primary window's renderer was never released at exit; every window's renderer, and with it
   `Content.dispose` and the 3D device, is now released when the engine stops (the validation layer is clean at teardown).
2. Fixed on Vulkan: uploads wait on the GPU per mesh. They are now recorded into the frame and submitted ahead of the next pass. GL
   uploads are the driver's.
3. Fixed on Vulkan: a frame that skipped a dynamic buffer's `update()` saw two-frames-old data. It now sees the latest.
4. Fixed: the Vulkan descriptor pools capped a frame at about 1000 draws. They grow, and the scene needs far fewer sets because
   draws that share a material merge.
5. 32-bit indices only, 16 vertex attributes at most, no push constants.
6. A `Viewport3D` and a `Background3D` re-render every frame by default. `setContinuous(false)` draws only on request, but the content must call `requestRender()` after changing a scene from code (input to the listener counts as a request). An animation player advances only when the viewport draws.
7. The `rengl.vulkan.dump` aid causes one validation layout warning. This is cosmetic.
8. Found and fixed in this stage: `TextureData.decode` copied the decoded pixels with a relative `ByteBuffer.get`, which moved the buffer position, so `stbi_image_free` (which frees `address + position`) freed the wrong pointer and corrupted LWJGL's heap. Any model with embedded textures, or a few images in a row, crashed the JVM. Absolute `get(0, bytes)` fixes it. Window icons were not affected.
9. Found and fixed in this stage: a scene shown by two viewports wrote its frame uniform data twice in one frame into the same buffer, so the first viewport could draw with the second's projection. The scene renderer now keeps a resource set per render call within a frame.

### Missing 3D features

No image-based lighting or reflections, no point-light shadows (a spot light's shadow is the stand-in) or variable-width soft shadows, no morph targets, no frustum
culling, no post-processing, no support for glTF cubic-spline animation (the keys are used linearly), no texture transforms or second UV
sets. Meshes are limited to 256 bones.

### `Background3D` specifics

1. Not run on OpenGL: the engine's GL path is unreachable on macOS (asking for it is now a fatal error), so only the shared `Viewport3D` code was GL-tested, through the harness.
2. Input is game input, so a UI container that has a click listener, or a draggable or scrollable element under the mouse, takes the event and the background does not see it. Give a full-window root container no listener.
3. `pointerMoved` fires while the mouse is over UI too and there is no `pointerExited`. There is no focus and no `charTyped`.
4. It is a `Background`, so in principle it can be set on an `Element` too (3D behind one panel). That is untested, and its input assumes the area is the window.

### Project-level

1. `skiko.version` is a local snapshot. Return to an official release once Skiko ships Vulkan. Windows and Linux builds of that Skiko were never made.
2. The Vulkan UI path and the 3D layer have only run on one machine (Apple M5, MoltenVK). Windows and Linux are untested, and so is the OpenGL path of the engine itself (not just the harness).
3. The GPU tests need a window, so on a headless build machine they skip. Nothing here has been run on CI.

## Next steps

In suggested order:

1. **Platforms:** build Skiko with Vulkan for Windows and Linux, run the demos and `./mvnw test` there (the GPU tests exercise OpenGL where it is real), and move off the snapshot version when an official release has it. This is the one piece that needs the other machines.
2. **Use it:** run `Scene3DDemo` and `Background3DDemo` with a real mouse and keyboard; nothing has driven the click, hover or drag paths of the demos by hand.
3. **Later ideas:** image-based lighting from the skybox, point-light (cube) shadows, morph targets, frustum culling, and the demos showing the new features (they were not edited, because `Scene3DDemo` has uncommitted changes).
4. **Your own list of other engine issues and changes**, which has not been sent yet.
