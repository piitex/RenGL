# Ray tracing

[Documentation](../README.md) › [3D](README.md)

On a GPU that can trace rays, a scene can have five things **traced** in place of the usual approximations:

| Setting | Replaces | What it gives |
|---|---|---|
| [`setRayTracedShadows`](#shadows) | Shadow maps | Exact hard shadows at any distance: no map to size, no cascades, no bias |
| [`setRayTracedReflections`](#reflections) | The sky's reflection on smooth surfaces | A polished floor, a metal ball or a window shows the objects around it, lit and textured |
| [`setRayTracedAmbientOcclusion`](#ambient-occlusion) | Occlusion read from the depth picture | Darkening in corners and under objects from what is really there, off screen or thin included |
| [`setRayTracedGlobalIllumination`](#global-illumination) | The flat or sky ambient light on lit surfaces | Light that bounces: a red wall tints the floor beside it, a glowing box lights what is around it, a roof shuts the sky out |
| [`setPathTracing`](#path-tracing) | One bounce of global illumination | The same with the rest of the way: light goes round several corners, so a room is lit through a doorway and shadowed corners are not black |

```java nocompile
scene.setRayTracedShadows(true);
scene.setRayTracedReflections(true);
scene.setAmbientOcclusion(1.5f).setRayTracedAmbientOcclusion(true);
scene.setRayTracedGlobalIllumination(true);
scene.setPathTracing(true);                    // instead of the one above, for several bounces
```

All are off by default, and a scene that asks for one where the GPU can not do it keeps what it had (maps, the sky, the depth
picture, the flat ambient light), so the settings are safe to leave on. `isUsingRayTracedShadows()`,
`isUsingRayTracedReflections()`, `isUsingRayTracedAmbientOcclusion()`, `isUsingRayTracedGlobalIllumination()` and
`isUsingPathTracing()` say which one the last frame used.

## What you need

- **Vulkan** on a GPU with ray queries: NVIDIA RTX, AMD RDNA 2 and later, Intel Arc. They are the Khronos extensions
  `VK_KHR_acceleration_structure` and `VK_KHR_ray_query`, so every vendor's driver provides them the same way. OpenGL has
  nothing like it.
- Nothing to install and no vendor code: the 3D layer asks for the extensions when the window's device is made, and a GPU
  that lacks them is made exactly as before. `-Drengl.3d.raytracing=false` leaves them off on a GPU that has them.

## Shadows

A ray goes from the point being shaded to the light, and the point is in shadow if the ray hits something on the way. There is
no map to size, no cascades, no bias to tune, no halo or shimmer at the edge and no limit on how far away or how small a shadow
can be.

| Method | Description |
|---|---|
| `setRayTracedShadows(boolean)` | Trace the shadows of every light that has them (`light.setCastShadow(true)`), in place of maps |
| `isUsingRayTracedShadows()` | Whether the last frame was drawn that way: asked for, and the GPU can trace rays |

| | Shadow maps | Ray traced |
|---|---|---|
| Edge | Soft, filtered (`ShadowFilter`), as wide as the map's texels allow | Hard and exact |
| Reach | `setShadowDistance`, or the light's range, and as sharp as the cascades make it | Anywhere in the scene |
| Lights with shadows | Up to `Scene3D.MAX_SHADOW_LIGHTS` | Every light that has them, up to `Scene3D.MAX_LIGHTS` |
| Cost | Drawing the casters once per view, which grows with the scene | One ray for each lit pixel and each shadow light, which grows with the screen |
| Quality, filter, bias, softness, distance, cascades | Used | Ignored |

What casts a shadow: nodes with `setCastShadow(true)` (the default) that are opaque, drawn as triangles, and not skinned or
blend-shaped, with every instance of an [instanced](instancing.md) node. What does not, yet, casts none (it still receives
them): a **skinned** or **blend-shaped** node, because its shape is not its mesh's; a **see-through** one; and a material with
an **alpha cutoff** (leaves, fences), whose holes a ray can not tell from solid. Shadow-map shadows keep all of these.

## Reflections

```java nocompile
scene.setRayTracedReflections(true)
        .setRayTracedReflectionRoughness(0.3f)   // the roughest surface that reflects by ray
        .setRayTracedReflectionDistance(500f);   // how far a reflection ray looks
floor.setMaterial(new Material(Color.WHITE).setMetallic(1f).setRoughness(0.05f));
```

Every pixel of a physically based (see [scenes](scenes.md)) surface sends a ray along its reflection. What the ray hits is shaded where it
landed: the material's colour and **base colour texture** (the level of the texture picked by how far away it is), its
emissive colour, and light from the scene's lights, **shadowed by rays** wherever a light has shadows, plus the sky or the flat
ambient light. A ray that hits nothing shows what the sky would have, so the traced reflection joins the old one seamlessly
at the horizon.

| Method | Description |
|---|---|
| `setRayTracedReflections(boolean)` | Reflect what is really there on the smooth parts of PBR surfaces |
| `isUsingRayTracedReflections()` | Whether the last frame did |
| `setRayTracedReflectionRoughness(float)` | The roughness up to which a surface reflects by ray (0.3 by default); between half this and this it fades back to the sky |
| `setRayTracedReflectionDistance(float)` | How far a reflection ray looks (500 by default); past it the sky shows |

Only the smooth part of a material reflects by ray, because one ray can not blur a reflection the way a rough surface does.
Mirror-like surfaces (roughness under about 0.1) are sharp and clean. A surface that is a little rough scatters its rays, a
different way at each pixel, so the reflection is slightly grainy at its edges; a [temporal upscaler](upscaling.md) averages the
grain away over frames. Rougher surfaces keep the sky's blurred reflection, as before.

What a reflection shows is what ray traced shadows show, plus every node that does not cast a shadow: opaque nodes drawn as
triangles, not skinned or blend-shaped, see-through or alpha-cut ones. It does **not** show a reflection in a reflection (a ray
is one bounce), normal maps, metallic-roughness, occlusion or emissive textures, the second set of texture coordinates, fog, or
the lights of the [light grid](lighting.md) (the lights every pixel tests are all there). At most 32 different base colour
textures are shown in one frame; a material beyond that shows in its colour alone.

## Ambient occlusion

```java nocompile
scene.setAmbientOcclusion(1.5f)            // the strength, as ever
        .setAmbientOcclusionRadius(1.0f)   // how far a blocker darkens
        .setRayTracedAmbientOcclusion(true);
```

The [screen-space occlusion](scenes.md) reads a depth picture: it can not see what is off the screen or behind
something, and it darkens a surface because of something far in front of it. With rays, each pixel (at half size, then blurred
the same way) sends eight rays over the hemisphere above its surface as far as the radius, and counts the ones that hit, the
nearer the hit the more. Nothing is darkened by what is merely in front of it in the picture, and a thin post, a ledge out of
sight or a wall behind the camera counts like anything else. It takes its strength and radius from the same two settings, so
turning it on changes nothing else, and nothing shows until the strength is above 0.

| Method | Description |
|---|---|
| `setRayTracedAmbientOcclusion(boolean)` | Work the occlusion out by rays |
| `isUsingRayTracedAmbientOcclusion()` | Whether the last frame did: asked for, strength above 0, and the GPU can trace rays |

The same nodes are traced as for reflections. Skinned and blend-shaped nodes are darkened like any other but do not darken what
is around them.

## Global illumination

```java nocompile
scene.setRayTracedGlobalIllumination(true)
        .setRayTracedGlobalIlluminationRays(1)         // rays per pixel, 1 to 8
        .setRayTracedGlobalIlluminationDistance(100f)  // how far a ray looks for something to bounce off
        .setRayTracedGlobalIlluminationStrength(1f);   // how much of the indirect light is added
```

A lit surface gets light from the lights, and until now the rest of its light was one flat colour (`setAmbient`) or what the
sky gives a surface facing that way (`setEnvironmentLighting`), the same everywhere. With global illumination each pixel sends
rays over the hemisphere above its surface, shades what they land on (its colour and texture, the glow, the scene's lights
with their shadows traced, as for [reflections](#reflections)) and takes the average as the light the surface receives from
around it. Where a ray finds nothing it gets the sky, or the flat ambient light, so an open floor is lit exactly as it was.

- **Colour bleeds.** A red wall in the sun tints the white floor beside it; a glowing box (`Material.setEmissive`) lights its
  surroundings; a green carpet gives a white ceiling a green cast.
- **The sky is shut out where it can not get in.** A floor under a roof, or inside a room, gets the light that reaches it
  through the openings and bounces off the rest, not the whole sky. It does what ambient occlusion tries to do, so with
  global illumination on you will want the occlusion strength lower or off.
- **Only the diffuse light changes.** The specular light of a surface (the sky's shine, and [reflections](#reflections)) is as
  before, and so are the lights themselves.

| Method | Description |
|---|---|
| `setRayTracedGlobalIllumination(boolean)` | Light surfaces with what bounces off the others |
| `isUsingRayTracedGlobalIllumination()` | Whether the last frame did: asked for, and the GPU can trace rays |
| `setRayTracedGlobalIlluminationRays(int)` | Rays per pixel, 1 to 8 (1 by default); they cost in proportion |
| `setRayTracedGlobalIlluminationDistance(float)` | How far a ray looks (100 by default); past it the sky shows |
| `setRayTracedGlobalIlluminationStrength(float)` | 1 is the light that is there (the default), 0 none, above 1 more |

It turns on the offscreen drawing the effects use (like bloom and occlusion), and it needs the same GPU as the other ray traced
effects. It is not free: one ray and its shading, plus one more ray to see how much of the sky reaches what it hit, for each ray of
each pixel, and the filter below.

### The noise and the filter

One ray per pixel is a rough guess at the average, so the picture the rays make is noisy, and a filter makes it smooth again
before it is used:

1. **Over time.** Each pixel is found where it was last frame, and a share of what it traced is blended into what the
   last frames had there: one over the number of frames it holds, down to a tenth, so a pixel that has just come into view takes
   its first frames as they come and one that has been there a while settles. A surface that is not what the pixel showed
   (something moved into or out of view) starts again.
2. **Over the neighbours.** Three passes of a wavelet filter spread the light up to 14 pixels around each pixel, never across
   an edge: a neighbour counts less the farther its surface is from the plane of the pixel's own.

Smooth, broad light (the sun on a wall, the sky, a bright room) is clean after a frame or two. Two things show what the
filter is doing:

- **The light follows a moving light or object a few frames late**, about as long as ten frames, as a trail. Moving the camera
  does not do this: history is found by where each pixel was.
- **A small, bright thing is hit by few rays and its light is blotchy**, and it lights a surface as faintly banded patches
  far from it. More rays per pixel smooth it (4 does, at 4 times the cost of the tracing); a light that stands for it (a point
  light, or `Material.setGlow` which makes the surface one, see [lighting](lighting.md)) is cleaner and cheaper.

What bounces light is what reflections show: opaque nodes drawn as triangles, not skinned, blend-shaped, see-through or
alpha-cut ones. Those still receive it (a skinned character is lit by the room), but do not bounce any. Surfaces drawn
see-through, lines, and unlit materials receive none. A surface at the very edge of an object, where the picture's pixel belongs
to what is behind it, takes the light of the neighbour that matches, and keeps the sky's where none does.

The sky reaches the thing a ray landed on only as far as it sees it (one more ray), and the light that would arrive there after more
bounces is a fixed share (15 percent) where it does not. A room lit by a window is therefore lit by the sun and the sky through it, once
bounced, and a little more; it is darker than a full simulation would make it.

## Path tracing

```java nocompile
scene.setPathTracing(true)
        .setPathTracingBounces(3)                      // surfaces a ray can bounce off, 1 to 8
        .setRayTracedGlobalIlluminationRays(1)         // paths per pixel, as for global illumination
        .setRayTracedGlobalIlluminationDistance(100f)
        .setRayTracedGlobalIlluminationStrength(1f);
```

Global illumination looks one surface away. Path tracing follows each ray on: from every surface it lands on, a new ray leaves
in a direction chosen with more of them near its normal, and the light it finds is what that surface gives of the light it
receives (its colour). At each surface the scene's lights are counted, shadowed by rays, and its glow, so:

- **Light goes round corners.** A room with a lamp in one corner is lit all over: the far wall gets what the near wall got
  from the lamp, and so on, as many surfaces as the bounces allow. With one bounce the far side of such a room is dark; with
  three or four most of the light is there. A doorway lights a room beyond it, and shadowed corners are not black.
- **The sky counts where a path sees it.** A path that finds nothing adds the sky (or the flat ambient light) and ends, so an
  open floor is lit as ever and a floor under a roof gets what the openings let in. There is no stand-in for light that would
  come after more bounces: after the last bounce a ray only looks for the sky, so the light is the real light of that many
  bounces, no more, and a scene looks darker with 1 or 2 than with global illumination, whose 15 percent stands in for the rest.
- **Light is not made.** A path ends by chance after the third surface with the chance of the light it still carries, and the
  ones that go on count for the ones that ended, so the average stays right. A white world under a white sky stays nearly as
  bright under a roof as in the open.

| Method | Description |
|---|---|
| `setPathTracing(boolean)` | Trace paths in place of the one bounce of global illumination (it replaces it; both on is path tracing) |
| `isUsingPathTracing()` | Whether the last frame did: asked for, and the GPU can trace rays |
| `setPathTracingBounces(int)` | The surfaces a ray can bounce off, 1 to 8 (3 by default); each adds a ray per path and a fainter share of light |

It takes the settings of global illumination for the rest: paths per pixel (`setRayTracedGlobalIlluminationRays`), how far a
ray looks and the strength. It draws and reads the picture in the same way, so what applies to global illumination (diffuse light
only, the offscreen drawing, what bounces and what receives) applies here. Its cost is the paths' rays: the bounces plus one, and
the lights' shadow rays at every surface.

The picture is noisier than global illumination's, so its filter is stricter. Once a pixel has settled it takes a twentieth of
each frame's own light (global illumination takes a tenth), so a still view is cleaner and a moving light is followed later,
after about twenty frames. The spatial passes also weigh a neighbour by how much its brightness differs from the pixel's, in
steps of the noise there, so a bright patch keeps its edge while the grain is averaged away.

It works under a temporal upscaler, with the picture drawn at the render size and the paths traced there.

### DLSS Ray Reconstruction

Where the upscaler is [DLSS with the Ray Reconstruction libraries](upscaling.md#dlss-ray-reconstruction) the scene does not filter
the paths itself. NVIDIA's denoiser is built for exactly this picture, and does the enlarging at the same time, so the scene gives
it the light as the rays found it (one noisy path per pixel, nothing averaged) and what it needs to tell the surfaces apart, and
takes back the clean, display-size picture:

- the **diffuse albedo**: the colour each surface reflects diffusely (a metal has none), so the denoiser can tell the light from the
  colour it is seen on;
- the **specular albedo**: how much of the light each surface reflects like a mirror, from the same fit of the split-sum integral the
  sky's reflection uses;
- the **normal** in world space and the **roughness** in its alpha channel.

They are drawn at the render size, with the jitter of the scene, in three more passes over the solid objects in view (the
material's own shader writes them, so textures, normal maps, metallic-roughness maps and classic materials are all in); the sky is
zero. The depth and motion are the ones every temporal upscaler gets.

| Method | Description |
|---|---|
| `setRayReconstruction(boolean)` | Let an upscaler that can denoise path tracing do it (on by default); off, the scene's own filter runs and DLSS only enlarges |
| `isUsingRayReconstruction()` | Whether the last frame's paths were cleaned by the upscaler |

It needs a temporal upscaler whose `denoisesPathTracing()` is true, and path tracing on; with any other upscaler, with the setting
off, or where the GPU can not trace rays, the scene's own filter is used as before. Switching between them restarts the history.

## The demo

`me.piitex.examples.demo.RayTracingDemo` (in `rengl-examples`) is a white room, open on the side the camera looks in from, with a red
wall, a green one, a polished floor, a mirror ball, a tall box, a spinning cube, a lamp in the ceiling and an orange orb that circles
inside with a light of its own. The panel has a preset (rasterized, shadows, shadows with reflections and occlusion, one bounce of
global illumination, path tracing) and a check box for each effect, sliders for the path bounces and the rays per pixel, the upscaler
and render scale of [the upscaling demo](upscaling.md#the-demo), a check box for Ray Reconstruction, one for the ceiling lamp and one to
freeze the motion, and the camera either sweeps in front of the room or is orbited with the mouse. Under them are the frame rate and the
sizes drawn and shown, which effects the last frame really used, and what cleaned up the noise: DLSS Ray Reconstruction, the engine's own
filter, or nothing where nothing is noisy. Things worth trying:

- the mirror ball and the floor go from black and the sky to the room as reflections are switched on;
- with path tracing the red and green walls tint the box, the floor and the ceiling beside them, which one bounce only half does;
- turn the lamp off: the room is lit by the orb alone, by light that went round the walls, and it follows the orb about a few frames
  late (the denoiser averages over frames);
- at 33% or 50% with DLSS, switch Ray Reconstruction off and on to compare it with the engine's filter, and the upscalers with each other.

It starts with path tracing and the best upscaler there is. Run it from the repository folder; the libraries of the vendors are found
as for the upscaling demo, and the extra options choose where it starts:

```
--enable-native-access=ALL-UNNAMED
-Drengl.dlss.dir=C:/path/to/dlss          (sl.*.dll, nvngx_*.dll; add sl.dlss_d.dll and nvngx_dlssd.dll for Ray Reconstruction)
-Drengl.demo.preset=0                     (0 rasterized ... 4 path tracing)
-Drengl.demo.upscaler=builtin             (smooth, builtin, fsr or dlss)
-Drengl.demo.reconstruction=false         (start without Ray Reconstruction)
```

It draws with Vulkan, without vsync. On a GPU without ray queries it says so and draws the room without the rays.

## How it works

The 3D layer turns each mesh into a **bottom-level acceleration structure** (once, when it first has to be traced, and freed
when the scene stops using it) and, every frame, places them all in a **top-level acceleration structure** with the transform of
each node and instance. The scene's fragment shader (shadows, reflections) and the occlusion pass hold that structure and shoot
their rays with a `rayQueryEXT`. Moving nodes cost nothing extra: the top level is rebuilt from their current transforms. The
rebuild is one copy of the structure for each frame in flight, so the frame being drawn never reads one that is being rebuilt.

Each placement carries a ray mask: shadow rays see the nodes that cast shadows, reflection, occlusion and global illumination
rays see every solid node.

Global illumination draws the solid objects in view one more time before the scene, with the scene's camera, into a half float
picture whose shader traces the rays and writes the light a surface receives (and its depth); the post pass filters that picture
(with the packed depth picture occlusion uses, drawn for it) and the scene's own pass then reads the result at each pixel in place
of the flat or sky ambient light.

What a reflection reads where it hits is in a table the top level keeps next to it, one record for each placement: the
address of the mesh's vertices and indices (the shader reads them through a buffer reference, so a hit's normal and texture
coordinates are interpolated from its triangle), the material's colour and parameters, and the place of its texture in an
array of up to 32 that is bound with the frame (picked per pixel, which the ray tracing devices allow). That is the
"bindless" part of hit shading: nothing is bound per draw for it.

## Using it yourself

The same pieces are on `GpuDevice` for [custom rendering](custom-rendering.md): `supportsRayTracing()`,
`createRayGeometry(vertices, vertexCount, floatsPerVertex, indices)` for a mesh, `createRayScene(capacity)` and
`RayScene.update(instances)` for a world of `RayInstance`s, and `RenderPass.bindAccelerationStructure(binding, scene)` to give
one to a shader, which declares it with `layout(set = 0, binding = N) uniform accelerationStructureEXT world;`. A shader that
uses `GL_EXT_ray_query` has to be `#version 460` and only compiles on Vulkan.

A `RayInstance` also takes a ray `mask` (a ray only hits an instance whose mask shares a bit with its cull mask) and up to 20
floats of `data` of your own. `RayScene.dataAddress()` is the GPU address of a table with one 96 byte record for each
instance, in order (the address of its geometry's vertices, the address of its indices, then the data), and
`RayGeometry.vertexAddress()` / `indexAddress()` are the addresses of one mesh's; a shader with `GL_EXT_buffer_reference` reads a
hit through them, the `gl_InstanceCustomIndexEXT` of the hit being its place in the table.
`RenderPass.bindTextures(binding, textures)` binds an array of samplers.

```glsl nocompile
#version 460
#extension GL_EXT_ray_query : require
layout(set = 0, binding = 0) uniform accelerationStructureEXT world;
// ...
rayQueryEXT query;
rayQueryInitializeEXT(query, world, gl_RayFlagsOpaqueEXT, 0xFF, origin, 0.001, direction, 1000.0);
while (rayQueryProceedEXT(query)) { }
bool hit = rayQueryGetIntersectionTypeEXT(query, true) != gl_RayQueryCommittedIntersectionNoneEXT;
```

## Not done yet

Soft shadows from a light with a size, reflections of reflections, rough reflections that blur (they need many paths and a
filter for the specular light, which path tracing does not have yet: only the diffuse light is traced), light picked straight
from bright surfaces and lights so a small one is not noisy, shadows and reflections of skinned and alpha-cut nodes, a specular
hit distance for Ray Reconstruction (it is not given one, so it follows a reflection by the surface's own motion), and a denoiser
for other vendors (NRD) are what come next.
