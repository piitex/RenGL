# Ray tracing

[Documentation](../README.md) › [3D](README.md)

On a GPU that can trace rays, a scene's shadows can be **traced** instead of drawn into shadow maps. A ray goes from the
point being shaded to the light, and the point is in shadow if the ray hits something on the way. There is no map to size,
no cascades, no bias to tune, no halo or shimmer at the edge and no limit on how far away or how small a shadow can be.

```java nocompile
scene.setRayTracedShadows(true);
```

It is off by default, and a scene that asks for it where the GPU can not do it keeps drawing shadow maps, so the setting is
safe to leave on. `scene.isUsingRayTracedShadows()` says which one the last frame used.

| Method | Description |
|---|---|
| `setRayTracedShadows(boolean)` | Trace the shadows of every light that has them (`light.setCastShadow(true)`), in place of maps |
| `isUsingRayTracedShadows()` | Whether the last frame was drawn that way: asked for, and the GPU can trace rays |

## What you need

- **Vulkan** on a GPU with ray queries: NVIDIA RTX, AMD RDNA 2 and later, Intel Arc. They are the Khronos extensions
  `VK_KHR_acceleration_structure` and `VK_KHR_ray_query`, so every vendor's driver provides them the same way. OpenGL has
  nothing like it.
- Nothing to install and no vendor code: the 3D layer asks for the extensions when the window's device is made, and a GPU
  that lacks them is made exactly as before. `-Drengl.3d.raytracing=false` leaves them off on a GPU that has them.

## What it does

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

## How it works

The 3D layer turns each mesh into a **bottom-level acceleration structure** (once, when it first casts a ray traced shadow, and
freed when the scene stops using it) and, every frame, places them all in a **top-level acceleration structure** with the
transform of each node and instance. The scene's fragment shader holds that structure and, for each pixel a light reaches,
shoots one ray at the light with a `rayQueryEXT`. Moving nodes cost nothing extra: the top level is rebuilt from their current
transforms. The rebuild is one copy of the structure for each frame in flight, so the frame being drawn never reads one that
is being rebuilt.

## Using it yourself

The same pieces are on `GpuDevice` for [custom rendering](custom-rendering.md): `supportsRayTracing()`,
`createRayGeometry(vertices, vertexCount, floatsPerVertex, indices)` for a mesh, `createRayScene(capacity)` and
`RayScene.update(instances)` for a world of `RayInstance`s, and `RenderPass.bindAccelerationStructure(binding, scene)` to give
one to a shader, which declares it with `layout(set = 0, binding = N) uniform accelerationStructureEXT world;`. A shader that
uses `GL_EXT_ray_query` has to be `#version 460` and only compiles on Vulkan.

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

Soft shadows from a light with a size, ray traced reflections, ambient occlusion and global illumination, shadows from skinned
and alpha-cut nodes, and a denoiser, are what come next. They need what this much does not: a way for a ray that hit something to
read its material (the vertices and textures of what it hit), and noise removal for the rays that do not all go the same way.
