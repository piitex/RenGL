# Vulkan and the optional 3D layer: status

A working document, not API documentation. It records what has been built for the Vulkan backend and the 3D layer,
what is known to be wrong or missing, and what comes next. Last updated 2026-10-01.

## Goals and decisions

| Decision | Detail |
|---|---|
| Vulkan for the 2D UI | Skiko 0.999.0-SNAPSHOT (a local build of JetBrains/skiko#1201) draws the UI through Vulkan. Pick it with `WindowOptions.setRenderer(RenderAPI.VULKAN)`. |
| 3D is optional | Basic apps and 2D games must not pay for it: no 3D initialisation, no cost when unused, no 3D types in `Window` or `Element`. The `render3d` package is only touched by code that asks for it. |
| Both OpenGL and Vulkan | The 3D layer runs on whichever API the window uses. The same GLSL source and the same scene code run on both. |
| Both presentation modes | An embedded `Viewport3D` element (done) and whole-window 3D behind the UI (not done, step 3). |
| macOS OpenGL fatal error | **Outline only, not implemented.** `RenderAPI.resolve` still warns and falls back to Metal. Implement only when asked. |

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
- Shaders are one GLSL 450 source. shaderc compiles it to SPIR-V for Vulkan. SPIRV-Cross turns the same SPIR-V into GLSL 330 for GL.
- Conventions the code relies on: clip space is +Y up with depth 0..1 on both backends. GL targets have a bottom-left origin (`isBottomLeftOrigin()`), Vulkan ones top-left. `readPixels()` returns the top row first.
- `Gpu3D` (package-private) makes one `GpuDevice` per window on the first draw of any viewport, and closes it when the window's renderer is disposed.

### `Viewport3D`

- An `Element` that is `Focusable`, `Draggable` and `Scrollable`. A `Content` renders into an offscreen target at
  `width * pixelScale * resolutionScale`. The target is wrapped as a Skia render target, snapshotted, and drawn like an image.
- An optional `InputListener` receives pointer, drag, scroll, key and focus events. Only the left button drags.
- A `Content` can be shared by several viewports.

### Scene layer (`render3d.scene`, `render3d.math`)

- `Scene3D` (root `Node3D`, `Camera`, up to 8 lights, ambient, background colour, update handler), `Node3D` (TRS, children, mesh and material), `Mesh` (cube, sphere, plane, computed normals), `Material` (colour, texture, specular, shininess, unlit, double-sided, blend, alpha cutoff), `Light` (directional, point), `OrbitControls`.
- `Model.load` reads glTF, GLB, OBJ, FBX and Collada through LWJGL Assimp. It is CPU only, so it is safe on a background thread.
- `SceneRenderer` (one per device): forward Lambert plus Blinn-Phong, opaque pass then blended nodes far to near, one dynamic uniform buffer per drawn node, mesh and texture caches evicted after 600 undrawn frames.

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
Run it with `-XstartOnFirstThread` on macOS. `-Drengl.demo.api=VULKAN|OPENGL` overrides the API (default Vulkan on macOS, OpenGL elsewhere).

## Verification so far

| Check | Result |
|---|---|
| Vulkan UI on macOS (Apple M5, MoltenVK) | Works. Validation layer clean apart from one layout warning that only the `rengl.vulkan.dump` debug aid causes. |
| 3D on Vulkan, real engine window | Two viewports sharing a device, UI over and behind, rounded corners, resize, second-window close. Clean. |
| 3D on GL 3.3 (macOS harness with `-Dskiko.macos.opengl.enabled=true`) | Visually identical to Vulkan, upright, Skia draws fine after the 3D pass. |
| `Background3DDemo` on Vulkan | A frame dump shows the 3D filling the window with the UI over it. The same validation layout error as the dump aid appears once after frame 90. Input forwarding was tested without a window by feeding the engine's `Input` press, move, wheel and key events: it produced hover, drag start, drag, wheel, drag end and key events as designed. Real mouse clicks were not driven. |
| `Scene3DDemo` | Default scene dumped as a frame on Vulkan and checked by eye. The file dialog, sliders, check boxes, drag and scroll were **not** driven. Not run on GL. |
| Windows, Linux | **Never run.** |
| Automated tests in the repo | **None** for the 3D layer. The harnesses live in a scratchpad. |

Useful switches: `-Drengl.vulkan.validation=true` (with `-Dorg.lwjgl.vulkan.libname=/usr/local/lib/libvulkan.1.dylib` on macOS),
`-Drengl.vulkan.dump=path.png` (writes frame 90), `-Dskiko.macos.opengl.enabled=true` for the GL harness.

## Known issues and limits

### Correctness and robustness

1. The primary window's renderer is never released at engine exit, so `Content.dispose` and the GPU device teardown do not run there. This is old behaviour.
2. GPU uploads happen on a mesh's or texture's first draw and wait for the queue to go idle, so a big model causes a visible stall.
3. A scene shown in several viewports runs its update handler once per viewport per frame, so animation runs too fast. Fix by giving the scene a frame counter, or by moving updates out of `render`.
4. Dynamic buffers are per frame slot. A frame that skips `update()` sees stale data.
5. Vulkan descriptor pools are 4096 sets x 4 buffers per frame slot, so about 1000 draws per frame is the ceiling. There is no instancing.
6. 32-bit indices only. No push constants.
7. A `Viewport3D` and a `Background3D` re-render every frame, even when nothing changed. A background costs a full-window offscreen target plus a GPU copy per frame.
8. The `rengl.vulkan.dump` aid causes one validation layout warning. This is cosmetic.

### Missing 3D features

No shadows, skybox, MSAA, mipmaps (textures shimmer at a distance), normal or metal-rough maps, skeletal animation or glTF animation (it is logged and ignored), picking or ray casting, or instancing.

### `Background3D` specifics

1. Not run on OpenGL: the engine's GL path is unreachable on macOS (it falls back to Metal), so only the shared `Viewport3D` code was GL-tested earlier.
2. Input is game input, so a UI container that has a click listener, or a draggable or scrollable element under the mouse, takes the event and the background does not see it. Give a full-window root container no listener.
3. `pointerMoved` fires while the mouse is over UI too and there is no `pointerExited`. There is no focus and no `charTyped`.
4. It is a `Background`, so in principle it can be set on an `Element` too (3D behind one panel). That is untested, and its input assumes the area is the window.

### Project-level

1. `skiko.version` is a local snapshot. Return to an official release once Skiko ships Vulkan. Windows and Linux builds of that Skiko were never made.
2. The macOS OpenGL fatal error is outlined but not implemented.
3. No docs page for the 3D layer in the user docs yet, and no in-repo tests.
4. The Vulkan UI path and the 3D layer have only run on one machine (Apple M5, MoltenVK).

## Next steps

In suggested order:

1. **Quick fixes:** update handler once per frame per scene (issue 3), a render-on-demand mode for viewports (issue 7), a docs page for the 3D layer, an in-repo smoke test.
2. **Ray picking** (screen to world ray, ray against bounds and triangles), so scenes can be clicked.
3. **Image quality:** MSAA, mipmaps.
4. **Content:** normal and metal-rough maps, shadows, a skybox, skeletal and glTF animation, instancing.
5. **Platforms:** build Skiko with Vulkan for Windows and Linux, run the demo there, and move off the snapshot version when an official release has it.
6. **Run the demos on OpenGL** on a platform where the engine really uses it (Windows or Linux), then the **macOS OpenGL fatal error**, when asked.
7. **Your own list of other engine issues and changes**, which has not been sent yet.
