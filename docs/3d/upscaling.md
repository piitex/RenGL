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

| System property | Meaning |
|---|---|
| `rengl.dlss.dir` | The folder of the libraries (also searched: the working directory, `natives`, `lib`, `java.library.path`) |
| `rengl.dlss` | `false` turns DLSS off |
| `rengl.dlss.log` | Show Streamline's own log (otherwise it is at debug level) |

DLSS has to be known when the window's Vulkan device is made, because it wants extensions and features on it
(`VK_NVX_binary_import`, `VK_NVX_image_view_handle`, `VK_KHR_push_descriptor`, and the Vulkan 1.2 features
`timelineSemaphore`, `descriptorIndexing` and `bufferDeviceAddress`). Streamline is started at that moment, only if its libraries
are there, and the device is made with what it reports ([Extensions for libraries](#extensions-for-libraries) below). It is stopped
again just before the device is destroyed.

It reads the same colour, depth and motion pictures as FSR and writes a storage image. The render size picks DLSS's mode (DLAA at
full size, then Quality, Balanced, Performance and Ultra Performance at about 67%, 58%, 50% and 33%); a size in between is given
the nearest mode. It has been run on an RTX 4090 with Streamline 2.14.16, in a still scene and with a panning camera, at render
scales from 33% to 100%, with the jitter and motion conventions of the table below, and was closer to the full-size picture than a
smooth enlargement each time. Validation draws one notice at the first frame about the layout of DLSS's own
images; it comes from inside the library.

## Extensions for libraries

A library that has to be told about the Vulkan device when it is made (DLSS is one) lists a `VulkanExtensionProvider` in
`META-INF/services/me.piitex.engine.gl.renderer.vulkan.VulkanExtensionProvider`. When the window's instance and device are made, each
provider is asked for instance extensions, device extensions and features of Vulkan 1.2 and 1.3 (by name, such as `timelineSemaphore`);
those the GPU has are turned on and the others left out. `deviceCreated()` and `deviceDestroying()` tell it when to start and
to let go of what it made on the device. A provider that is missing or throws is skipped.

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

`jitterPhases(renderWidth, displayWidth)` says how long the jitter sequence is; the default is the `8 × (display ÷ render)²`
that AMD recommends. An upscaler is closed when the scene's upscaler is changed or the scene is disposed.
