# Rendering APIs

[Documentation](../README.md) › [Core](README.md)

Skia draws every window's UI. What Skia draws *onto* depends on the graphics API the window runs on. `RenderAPI`
(`me.piitex.engine.RenderAPI`) names the choices, and you pick one per window:

```java nocompile
Window window = new Window(new WindowOptions("My App")
        .setDimensions(1280, 720)
        .setRenderer(RenderAPI.VULKAN));
```

You can also change it while the window is open, with `window.getWindowOptions().setRenderer(...)`. The engine then
closes the OS window and opens it again on the new API; see [Switching the renderer](window.md#switching-the-renderer).

Most programs never set it. The default, `AUTO`, picks what each platform does best, and nothing else in the API
changes with the choice: the same containers, overlays and [`Renderer`](../reference/custom-elements.md#the-renderer)
calls draw the same on all of them.

## The choices

| `RenderAPI` | What it is | Where |
|---|---|---|
| `AUTO` | The platform default (below), or the `rengl.backend` system property if it is set | Everywhere |
| `OPENGL` | OpenGL 3.3 core (`SkikoRenderer`) | Windows and Linux. macOS windows have no OpenGL context, so asking for OpenGL there is a [fatal error](#when-a-request-cant-be-met) |
| `METAL` | A Metal layer on the window's view (`MetalSkikoRenderer`) | macOS |
| `VULKAN` | Vulkan 1.1 (`VulkanSkikoRenderer`; `VulkanContext` owns the instance, GPU, swapchain and synchronization) | Anywhere with a Vulkan driver. On macOS it runs on MoltenVK, which LWJGL bundles |

The platform default is **Metal on macOS and OpenGL on Windows and Linux**. Vulkan is always opt-in.

```java nocompile
Window window = new Window(new WindowOptions("My App").setDimensions(1280, 720));
RenderAPI used = window.getRenderer(); // what the window really got, after any fallback
```

## Choosing without changing code

Run any program with the `rengl.backend` system property to try an API. It only applies to `AUTO`, so a window that
asks for an API in code keeps it:

```text
-Drengl.backend=vulkan
```

Accepted values are `opengl`, `metal` and `vulkan`, in any case. An unknown value logs a warning and is ignored.

## When a request can't be met

Most requests that can't be met fall back to the platform default with a logged warning saying why. The one exception is OpenGL on macOS, which is a **fatal error**: a game written for OpenGL would otherwise quietly run on another API, so the engine stops with a message telling you to use Vulkan.

| Request | Fallback |
|---|---|
| `METAL` off macOS | OpenGL |
| `OPENGL` on macOS | **None: throws `IllegalStateException`** ("OpenGL is not available on macOS ... Use RenderAPI.VULKAN"). Use `VULKAN` (required for [3D](../3d/README.md)), or `AUTO` for Metal (2D only). This includes `-Drengl.backend=opengl` |
| `VULKAN` and the Skiko on the classpath has no Vulkan support | The platform default |
| `VULKAN` and the machine has no usable Vulkan GPU | The platform default |

Always read `Window.getRenderer()` if your program depends on the API. The log also prints `Render API: <name>` once
at start-up.

## Getting Vulkan working

Vulkan needs a Skiko build with Vulkan support. If the Skiko on the classpath has none, a `VULKAN` request logs
`the Skiko on the classpath (...) has no Vulkan support` and the window uses the platform default.

On **macOS** run with `-XstartOnFirstThread`, as for any window. The Vulkan loader is MoltenVK from the LWJGL natives,
so there is nothing to install. On **Windows and Linux** the system's Vulkan driver is used. The Vulkan backend has
only been run on macOS (Apple silicon) so far, see [Known limitations](../reference/known-limitations.md#rendering-and-3d).

## Debugging switches

System properties for the Vulkan backend:

| Property | Effect |
|---|---|
| `rengl.vulkan.validation=true` | Turns on the Khronos validation layer. On macOS also pass `-Dorg.lwjgl.vulkan.libname=/usr/local/lib/libvulkan.1.dylib`, because LWJGL otherwise loads MoltenVK directly and layers can't attach. If the layer is missing the engine logs that and carries on |
| `rengl.vulkan.gpu=<text>` | Picks the GPU whose name contains the text, instead of the engine's choice |
| `rengl.vulkan.dump=<file.png>` | Writes one frame (frame 90), read back from the GPU. It makes the validation layer report one image-layout warning afterwards, which is only the dump's doing |
| `rengl.vulkan.mvkLog=true` | Brings back MoltenVK's own logging, see below |

On macOS the engine tells MoltenVK to log only errors, because Skia's pipelines make it print a harmless "Metal does
not support disabling primitive restart" line over and over. Setting `MVK_CONFIG_LOG_LEVEL` yourself, or
`rengl.vulkan.mvkLog=true`, leaves MoltenVK's logging alone.

## What each API can do

The 2D UI looks the same everywhere. The one place the API matters is code that talks to the GPU itself, such as the
[3D layer](../3d/README.md), which shares Skia's GPU context to hand its pictures to the UI. Only the OpenGL and
Vulkan renderers allow that (they implement `GpuInterop`):

| | OpenGL | Vulkan | Metal | Snapshots (CPU) |
|---|---|---|---|---|
| 2D UI | Yes | Yes | Yes | Yes |
| [`Viewport3D`](../3d/viewport-3d.md), [`Background3D`](../3d/background-3d.md) | Yes | Yes | **No**, they warn once and draw nothing | No, they are left out |

So **on macOS, a program with 3D asks for Vulkan.** A program with only 2D can stay on the default.

## Where to go next

- How the frame loop uses the renderer: [Architecture](../architecture.md#rendering-backends).
- Optional 3D on OpenGL or Vulkan: [3D](../3d/README.md).
- Writing your own drawing code against the `Renderer`: [Writing custom elements](../reference/custom-elements.md).
