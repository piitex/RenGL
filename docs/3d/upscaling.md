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

Vendor upscalers (AMD FidelityFX, NVIDIA DLSS, Intel XeSS) are meant to come from a module of their own, so that nothing in the
3D layer depends on their libraries or licences.

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
