# Upscaling

[Documentation](../README.md) › [3D](README.md)

A scene can be drawn at a fraction of the size it is shown at and enlarged, which is the cheapest way to make a heavy
scene run faster. **Render scale** does the drawing smaller; an **upscaler** does the enlarging, and a *temporal* one
does it from several frames at once, so the picture comes out sharper than any single smaller frame could be and its
edges are smooth without multisampling.

```java nocompile
scene.setRenderScale(0.67f);                  // draw 67% of the width and height: 45% of the pixels
scene.setUpscaler(Upscalers.builtIn());       // enlarge with the temporal upscaler that comes with the 3D layer
```

Both are off by default. With a render scale below 1 and no upscaler the scene is enlarged smoothly, which is soft.
With an upscaler and a render scale of 1 the upscaler works as anti-aliasing alone.

| Method | Description |
|---|---|
| `setRenderScale(float)` | The share of the viewport's width and height the scene is drawn at, 0.25 to 1 |
| `setUpscaler(UpscalerProvider)` | What enlarges the picture and builds it from frames before; `null` for none |
| `resetTemporalHistory()` | Forget the frames before: call it when the camera cuts or jumps to another place |

The bloom, ambient occlusion and colour grade work at the size the picture is shown at, after the enlargement, so they
stay crisp whatever the render scale is.

## The upscalers

`Upscalers` lists what there is.

| Method | Description |
|---|---|
| `builtIn()` | The temporal upscaler of the 3D layer. It needs no library and works on every GPU and both APIs |
| `available(device)` | The providers that work on a device, best first |
| `best(device)` | The best of them |
| `register(provider)` | Add your own; a provider is also found if it is listed in `META-INF/services/me.piitex.engine.render3d.upscale.UpscalerProvider` |

The built-in upscaler is the reference for what the others are given, and the fallback. It rebuilds each pixel from the
smaller samples around it (weighted by how close each was taken to the pixel, since the camera moves a fraction of a
pixel every frame), blends in the last picture along the motion vectors, and clamps what it keeps to the colours around
the pixel so that what changed does not leave a ghost. It is clearly sharper than a smooth enlargement of the same scene,
but it is a simple one: vendor upscalers do better on thin lines and fast motion.

Vendor upscalers (AMD FidelityFX, NVIDIA DLSS, Intel XeSS) come from a module of their own, so that nothing in the
3D layer depends on their libraries or licences. Today those are [FSR](#fsr) and [DLSS](#dlss).

## FSR

The `rengl-upscale` module has AMD FidelityFX Super Resolution on Vulkan: **FSR 4** on the GPUs that run it and **FSR 3** on any
other Vulkan GPU. Add the module and it is found by `Upscalers.available(device)`, or use it directly:

```java nocompile
scene.setRenderScale(0.67f);
scene.setUpscaler(new FsrProvider());         // me.piitex.engine.upscale.fsr
```

The module is only the binding: it carries none of AMD's code. The libraries come from the
[FidelityFX SDK](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK) (`Kits/FidelityFX/signedbin`): copy
`amd_fidelityfx_loader_vk.dll` and `amd_fidelityfx_upscaler_vk.dll` into one folder and name it with
`-Drengl.fsr.dir=<folder>` (or `RENGL_FSR_DIR`). Without them `FsrProvider.isAvailable` is false and
`FsrProvider.unavailableReason()` says why. The libraries are Windows-only.

| System property | Meaning |
|---|---|
| `rengl.fsr.dir` | The folder of the two libraries (also searched: the working directory, `natives`, `lib`, `java.library.path`) |
| `rengl.fsr.version` | Use only the version whose name contains this text, such as `3.1`; unset, the library picks its newest and the others are the fallback |
| `rengl.fsr.debug` | Turn on the SDK's checks of what it is given; its messages go to the log |

It works on the colour, depth and motion pictures the 3D layer already makes, as `UpscaleInputs` describes them, and needs a
GPU whose depth buffer is 32-bit float. FSR keeps its own history, so `previousOutput` is not used.

Which version runs is the SDK's decision: it lists what the GPU can run, newest first, and the module takes the first that
makes a context. It has been run with SDK 2.x on an NVIDIA RTX 4090, where the library offers FSR 3.1.6 and 2.3.4, and
FSR 3.1.6 ran. FSR 4 is offered on the GPUs that support it; the module asks for it the same way, but it has not been run
on one. With `-Drengl.vulkan.validation=true` the SDK's own storage images draw warnings about their format (the library's
shaders declare 32-bit formats for images it makes 16-bit); they come from inside the library. The window's Vulkan device
turns on `VK_KHR_compute_shader_derivatives` where the GPU has it, which the library's shaders use.

## FSR frame generation

AMD's **frame generation** (FSR 3, and FSR 4 on the GPUs that have it) shows a second frame between each two the engine draws,
made from the scene's depth and motion and the two pictures. Unlike NVIDIA's it runs on any Vulkan GPU, and it is only started
when asked for:

- Run with `-Drengl.fsr.framegen=true` and copy `amd_fidelityfx_framegeneration_vk.dll` from the FidelityFX SDK's
  `Kits/FidelityFX/signedbin` next to the other two. Without it (or without the property) nothing changes.
- The scene is drawn through the FSR upscaler (`FsrProvider`; at render scale 1 it is anti-aliasing), which hands over its depth
  and motion; the 3D layer also gives it the camera's position and axes. The 3D viewport has to fill the window, since the
  generated frame is made from the finished window picture: the library puts the depth and motion at the top left of it. A view
  of another size is not given to frame generation (the log says so once) and frames are shown as drawn. Whatever else is drawn in
  the window is interpolated along with the scene.
- The library paces the presents itself, with threads of its own. It has been run with vsync off; vsync on (which holds the scene to
  half the display's rate) was not tried.

```java nocompile
scene.setUpscaler(new FsrProvider());
FsrFrameGeneration.setEnabled(true);                      // on by default once the swapchain is the library's
FsrFrameGeneration.State state = FsrFrameGeneration.state();   // frames shown since the swapchain was made, frames drawn
```

| System property | Meaning |
|---|---|
| `rengl.fsr.framegen` | `true` turns frame generation on for the window |
| `rengl.fsr.framegen.on` | `false` starts with it switched off |
| `rengl.fsr.framegen.debug` | The library marks the frames it generates with coloured bars at the sides |

What the engine does for it, with no work from the application: the window's device is made with three more queues (the library
generates, presents and acquires images on queues of its own) and with timeline semaphores; the window's swapchain is the
library's, which gives the engine images of its own to draw into and copies each to the real swapchain, so the engine leaves the
window's image in `SHADER_READ_ONLY_OPTIMAL` and not `PRESENT_SRC_KHR` before presenting
(`SwapchainHooks.presentLayout()`); after the upscaler has run the depth, motion, jitter and camera are handed over, and the
swapchain calls back during the present to have the generated frame recorded. When a frame did not draw the scene it is
presented as it is. Frame generation is taken down while the swapchain is rebuilt (the window was resized or vsync changed) and
made again for the next frame. Where the library will not make its swapchain the window falls back to Vulkan's own and
`FsrFrameGeneration.unavailableReason()` says why. It is meant for one window.

`FsrFrameGenGpuTest` runs it on an RTX 4090 in a visible window (it skips elsewhere): with frame generation on, the library
presented two frames for each one drawn; turned off, one for one; turned on again, and after the window was resized, two for one. With the
debug bars on, frames captured from the window showed the bars on some and not on others, and the ones with bars were a clean,
plausible picture between the drawn ones. The validation layer reports a few things from inside the library: its composition
shader writes three components to a four-component storage image, and at device destruction it has not freed its composition pipeline, some descriptor sets and
the semaphores of its real swapchain's images (13 objects, with or without frame generation switched on). **Not tried:** FSR 4's
frame generation (it needs an AMD Radeon RX 9000), generating with the DLSS upscaler or the built-in one, a view that
does not fill the window, HDR, more than one window.

## DLSS

The same module has NVIDIA **DLSS** super resolution on Vulkan, for GeForce RTX cards, through NVIDIA's
[Streamline](https://github.com/NVIDIA-RTX/Streamline) libraries (Windows only). It is found by `Upscalers.available(device)`,
ahead of FSR, or used directly:

```java nocompile
scene.setRenderScale(0.67f);
scene.setUpscaler(new DlssProvider());        // me.piitex.engine.upscale.dlss
```

The module carries none of NVIDIA's code. From the Streamline SDK's `bin/x64` folder copy `sl.interposer.dll`, `sl.common.dll`,
`sl.dlss.dll` and `nvngx_dlss.dll` (and `NvLowLatencyVk.dll`, which Streamline loads and logs an error without) into one folder and
name it with `-Drengl.dlss.dir=<folder>` (or `RENGL_DLSS_DIR`). Without them `DlssProvider.isAvailable` is false and
`DlssProvider.unavailableReason()` says why; nothing is started and nothing is slowed.
Frame generation, below, wants four more libraries, and Ray Reconstruction two.

| System property | Meaning |
|---|---|
| `rengl.dlss.dir` | The folder of the libraries (also searched: the working directory, `natives`, `lib`, `java.library.path`) |
| `rengl.dlss` | `false` turns DLSS off |
| `rengl.dlss.log` | Show Streamline's own log (otherwise it is at debug level) |
| `rengl.dlss.framegen` | `true` starts Streamline with frame generation (see below) |
| `rengl.dlss.framegen.on` | `false` starts with frame generation loaded but switched off |
| `rengl.dlss.rr` | `false` does not load Ray Reconstruction even where its libraries are there (see below) |

DLSS has to be known when the window's Vulkan device is made, because it wants extensions and features on it
(`VK_NVX_binary_import`, `VK_NVX_image_view_handle`, `VK_KHR_push_descriptor`, and the Vulkan 1.2 features
`timelineSemaphore`, `descriptorIndexing` and `bufferDeviceAddress`). Streamline is started at that moment, only if its libraries
are there, and the device is made with what it reports ([Extensions for libraries](#extensions-for-libraries) below). It is stopped
again just before the device is destroyed.

It reads the same colour, depth and motion pictures as FSR and writes a storage image. The render size picks DLSS's mode (DLAA at
full size, then Quality, Balanced, Performance and Ultra Performance at about 67%, 58%, 50% and 33%); a size in between is given
the nearest mode. DLSS does not take every size, though: Ultra Performance takes one width (a third of the display's) and the other
modes 50% to 100%, and anything between comes back as a black picture. So `DlssProvider.supportedRenderScale` moves the scale to the
nearest width DLSS says it takes (`slDLSSGetOptimalSettings`), and the scene draws at that: 40% of a 1280 x 720 display is drawn at
427 x 240, 45% at 640 x 360. A settings screen can ask the provider for the size that will be drawn. It has been run on an RTX 4090
with Streamline 2.14.16, in a still scene and with a panning camera, at render
scales from 25% to 100% (no black picture at any whole percent), with the jitter and motion conventions of the table below, and was closer to the full-size picture than a
smooth enlargement each time. Validation draws one notice at the first frame about the layout of DLSS's own
images; it comes from inside the library.

## DLSS frame generation

NVIDIA **frame generation** (DLSS 3 and up) shows a second frame between each two the engine draws: the GPU makes it from the
scene's depth and motion, so the picture updates about twice as often as the scene is drawn. It needs more than DLSS
super resolution does, so it is only started when asked for:

- Run with `-Drengl.dlss.framegen=true` and copy `sl.dlss_g.dll`, `nvngx_dlssg.dll`, `sl.reflex.dll` and `sl.pcl.dll` from the
  Streamline SDK's `bin/x64` into the folder of the DLSS libraries. Without them (or without the property) nothing changes.
- The GPU has to be a GeForce RTX 40 or newer (RTX 50 can make more than one frame, see `State.maxFramesToGenerate()`), and on
  Windows hardware-accelerated GPU scheduling has to be on. `DlssFrameGeneration.unavailableReason()` says what is missing.
- The scene is drawn through a DLSS upscaler (`DlssProvider`; at render scale 1 that is DLAA), which hands over its depth and
  motion. The 3D viewport should fill the window: everything drawn around it is interpolated along with it.
- Turn vsync off: Streamline does not support it with frame generation on Vulkan.

```java nocompile
scene.setUpscaler(new DlssProvider());
DlssFrameGeneration.setEnabled(true);                    // on by default once the libraries are loaded
DlssFrameGeneration.State state = DlssFrameGeneration.state();   // active? how many frames were shown for the last one drawn?
```

What the engine does for it, with no work from the application: the window's device is made with the extra queues Streamline
asks for and the extensions it lists (low latency needs `VK_KHR_present_id` as well); the window's surface and swapchain are made
through Streamline's `sl.interposer.dll`, which then presents the real and the generated frames from its own queue; every frame is
bracketed with Reflex's sleep and latency markers (frame generation refuses to run without Reflex); the back buffer is told to
Streamline in its layout before each present; and frame generation is switched off while the swapchain is rebuilt (the window
was resized). It is meant for one window.

`DlssFrameGenGpuTest` runs it on an RTX 4090 in a visible window (it skips elsewhere): with frame generation on, Streamline
reported two frames shown for each drawn one with status ok; turned off, one for one; turned on again, and again after the
window was resized, two for one. **Not checked by eye:** that the generated frames look right (the test counts them), and only
the single generated frame of an RTX 40 card was tried. Streamline's own first-use notices from the validation layer (layouts of
its internal images, a semaphore it reuses, Reflex's sleep against its present thread) are not ours.

## DLSS Ray Reconstruction

**Ray Reconstruction** is NVIDIA's denoiser for [path tracing](ray-tracing.md#path-tracing), trained on the noisy light of a few
paths per pixel and running in the place of DLSS super resolution: one pass cleans up the noise and enlarges the picture. Copy
`sl.dlss_d.dll` and `nvngx_dlssd.dll` from the Streamline SDK's `bin/x64` into the folder of the DLSS libraries; they are loaded
with Streamline whenever they are there (`-Drengl.dlss.rr=false` leaves them out), and cost nothing until a path traced scene is
drawn through DLSS. Without them, or on a GPU that does not support it, `Upscaler.denoisesPathTracing()` is false and a path traced
scene uses its own filter, with DLSS only enlarging.

```java nocompile
scene.setRenderScale(0.5f);
scene.setUpscaler(new DlssProvider());
scene.setPathTracing(true).setPathTracingBounces(3);     // setRayReconstruction(true) is the default
// scene.isUsingRayReconstruction() is true once a frame was cleaned by DLSS
```

What the scene hands to DLSS is described with [path tracing](ray-tracing.md#dlss-ray-reconstruction): the noisy light, the diffuse
and specular albedo and the normals with roughness. DLSS is told the camera's view matrix (so it can turn the world-space normals
into the view's) and uses the same depth, motion and jitter as for super resolution, at the same render sizes and with the same
mode picked from the ratio of the sizes. When a frame is path traced and cleaned by DLSS, Streamline's resources for super
resolution are released and Ray Reconstruction's made, and back again when the scene stops; each switch drops the history.

`DlssRayReconstructionGpuTest` runs it on an RTX 4090 with Streamline 2.14.16 at half size: the picture shows the red wall's light
on the floor beside it, is as bright as the scene's own filter's, follows a panning camera, and the scene reports what it did.
`RayReconstructionGuidesGpuTest` checks the guide pictures without DLSS (a floor is white, dull and faces up, a wall red, a smooth
metal ball has almost no diffuse colour and a strong specular one, the sky none). **Not checked:** a long run, a scene full of
textures and normal maps, and the image quality by eye beyond a simple scene. The specular hit distance and the reflection motion
vectors that Ray Reconstruction can use for mirrors are not given, so a reflection that moves differently from its surface may smear.

## The demo

`me.piitex.examples.demo.UpscalingDemo` (in `rengl-examples`) flies a camera through a yard that is hard on a smaller picture
(thin fences, a ground of hard-edged tiles, spinning rotors, glowing orbs) with a panel to choose the upscaler (smooth
enlargement, built-in, FSR, DLSS), the render scale (the vendors' presets or any size), the number of shadow-casting crates, the camera
(fly-through or orbit), and to freeze the motion. The numbers show the size drawn and shown, the frames drawn and the frames shown.
The vendors' libraries are not in the repository: it lists the ones it cannot find, and which of them to name.

Run it from the repository folder with the folders of the libraries, and for frame generation say which one to start (it is
started with the window, so it is chosen at launch; each wants its own upscaler, which the demo begins with):

```
--enable-native-access=ALL-UNNAMED
-Drengl.dlss.dir=C:/path/to/dlss          (sl.*.dll, nvngx_*.dll, NvLowLatencyVk.dll)
-Drengl.fsr.dir=C:/path/to/fsr            (amd_fidelityfx_*_vk.dll)
-Drengl.demo.framegen=dlss                (or fsr, or leave it out)
```

The window draws with Vulkan without vsync, and its 3D view fills it. DLSS frame generation only runs while the window has
focus.

`RayTracingDemo` has the same upscaler and render scale controls over a [ray traced and path traced room](ray-tracing.md#the-demo),
where the upscaler also decides who cleans up the noise (with DLSS, Ray Reconstruction; otherwise the engine's own filter).

## Extensions for libraries

A library that has to be told about the Vulkan device when it is made (DLSS is one) lists a `VulkanExtensionProvider` in
`META-INF/services/me.piitex.engine.gl.renderer.vulkan.VulkanExtensionProvider`. When the window's instance and device are made, each
provider is asked for instance extensions, device extensions and features of Vulkan 1.2 and 1.3 (by name, such as `timelineSemaphore`);
those the GPU has are turned on and the others left out. `deviceCreated()` and `deviceDestroying()` tell it when to start and
to let go of what it made on the device. A provider that is missing or throws is skipped.

A library that sits in the swapchain (frame generation does) can also ask for queues of the engine's family besides the engine's
(`extraQueues()`; the library gets queues 1 and up, as `DeviceInfo.queueCount()` says), make the window's surface itself
(`createWindowSurface`, Windows), take over the five swapchain calls (`swapchainHooks()`), hear the back buffer before each present
(`beforePresent`) and be told at each point of a window frame (`frameEvent`: the frame starts, drawing starts and ends, the present
starts and ends, the swapchain is about to be rebuilt). A swapchain made of the library's own images may want them left in another
layout than `PRESENT_SRC_KHR` (`SwapchainHooks.presentLayout()`). Only one provider's swapchain is used, so one frame generation at a time.

## Vulkan work of your own

An upscaler that calls a library on the GPU needs the device's raw handles and a command buffer to record into. `VkGpuDevice`
has them:

| Method | Description |
|---|---|
| `vkInstance()`, `vkPhysicalDevice()`, `vkDevice()`, `deviceProcAddr()` | The window's Vulkan objects, shared with Skia |
| `imageOf(GpuTexture)` | A `VkImageInfo`: the `VkImage`, its view, format, aspect, size, usage, and the layout a pass leaves it in |
| `record(commands)` | Records into a command buffer of your own and submits it in order with the passes before and after |
| `createStorageTarget(w, h)` | A 16-bit float target a compute shader can write; an upscaler asks for it with `writesStorageImage()` |

`UpscaleInputs.color()`, `depth()` and `motion()` are textures of that device, and `imageOf` gives the handles behind them.
`VulkanInteropGpuTest` is a small example that writes the output this way.

## What drawing for an upscaler changes

A temporal upscaler needs the scene to be drawn a particular way, and the 3D layer does it when one is set:

- **The camera is jittered.** Every frame the projection is moved by a fraction of a pixel along a Halton sequence
  (`JitterSequence`), so each frame samples the scene at a slightly different place.
  Culling, shadows and the light grid use the camera without the jitter.
- **Multisampling is not used** for the scene. The viewport's `setSamples` has no effect while a temporal upscaler is on.
- **A second pass draws the solid objects** into a *velocity* picture: how far each pixel moved since the last frame, and
  its depth.
- **Textures are sharpened** by a negative level-of-detail bias, so they are sampled as finely as the picture will be shown
  and not as the smaller one is drawn.

The cost is the second pass over the solid objects and the upscale itself.

### Limits

- Skinned meshes and meshes with blend shapes show the camera's motion and not their own: a fast-moving character can
  ghost. An object's own motion is known for ordinary nodes and for instanced ones that move as a whole.
- The sky is drawn without the jitter; its own motion is the camera's turning.
- Blended (see-through) surfaces are not in the velocity picture, so they take the motion of what is behind them.
- An upscaler runs once for every place a scene is drawn; a scene shown by two viewports has two histories.

## Writing an upscaler

An `Upscaler` is made by an `UpscalerProvider` and gets an `UpscaleInputs` every frame: the colour (linear light, at the
render size), the depth and motion (null for a spatial upscaler), the output to write (the display size, 16-bit
floating point), last frame's output, and the jitter, the sizes, the camera's planes and field of view, and whether to drop
the history.

```java nocompile
public final class MyUpscaler implements Upscaler {
    public String name() { return "Mine"; }
    public boolean isTemporal() { return true; }                      // false: colour only, no jitter, depth or motion
    public void upscale(UpscaleInputs in) {
        // read in.color(), in.depth(), in.motion(); draw into in.output() with in.device()
    }
    public void close() { }
}
```

The conventions, which are the ones FidelityFX and DLSS expect, for an image whose row 0 is the top (Vulkan; on OpenGL
`bottomLeftOrigin()` is true and the rows run up):

| Input | Convention |
|---|---|
| Depth | `depth().red`: 0 at the near plane, 1 at the far plane, not reversed; a hair under 1 where the sky is |
| Motion | `motion().rg` in render pixels: the previous position minus this one, +x right and +y toward higher rows |
| Jitter | `jitterX`, `jitterY` in render pixels, -0.5 to 0.5, +x right and +y down the picture. A pixel holds the scene at its centre *minus* the jitter |
| Colour | Linear, unclamped (a sun is far above 1), 16-bit float |

An upscaler that can also denoise [path tracing](ray-tracing.md#dlss-ray-reconstruction) returns true from `denoisesPathTracing()`
(it may change from frame to frame). A path traced frame then arrives with `hasGuides()` true: `color()` holds one noisy path per
pixel, nothing filtered, and `diffuseAlbedo()`, `specularAlbedo()` (linear, 0 to 1) and `normalRoughness()` (a world normal in
red, green and blue, the roughness in alpha) go with it, at the render size, drawn with the same jitter, and `worldToView()` is the
camera's view matrix. The guides are null otherwise.

`jitterPhases(renderWidth, displayWidth)` says how long the jitter sequence is; the default is the `8 × (display ÷ render)²`
that AMD recommends. An upscaler is closed when the scene's upscaler is changed or the scene is disposed.
