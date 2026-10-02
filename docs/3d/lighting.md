# Lighting

[Documentation](../README.md) › [3D](README.md)

What lights a [`Scene3D`](scenes.md): its [lights](scenes.md#light), the ambient light, shadows, and the skybox behind
everything. Lighting is the same for classic and [physically based](scenes.md#material) materials; they differ in how a
surface answers the light, not in what the light is.

## How lights add up

A scene uses its first 8 lights. Each is **directional** (the sun: parallel rays from one direction, no position), a
**point** light (a bulb: a position, fading to nothing at its range) or a **spot** light (a point light that only shines
inside a cone, like a torch or a hearth opening onto a room). The surface receives the **ambient**
light from every side, plus each light's share by the angle between the surface and the light, and a highlight.

- A light's `intensity` is "how bright a white surface facing it comes out": a white light of `1` lights a white surface
  facing it to full white, and `0.9` (the default sun) a little under that. Several lights add.
- Colours are the screen colours you pick everywhere else; the scene converts them to linear light to add them up and
  back to show them, so two half-bright lights make a surface look as bright as one full one should.
- Nothing is clamped or tone-mapped: a surface lit by more than `1` in total simply shows white.
- `scene.setAmbient(color, intensity)` is the light nothing blocks. It is what keeps the dark side of an object from being
  black, and what a [skybox](#skybox) does not provide: the sky is a picture, not a light, so match the ambient light and
  the lights to it by eye.
- Metals reflect the lights, not their surroundings. There is no image-based lighting yet, so a fully metallic material
  under only ambient light looks dark, and shows its colour in the highlights of the lights. Keep a light or two in a
  scene with metal.

## Spot lights

```java nocompile
Light torch = Light.spot(new Vec3(0, 6, 0), new Vec3(0, -1, 0),        // position, the way the cone points
        new Color(1f, 0.8f, 0.5f, 1f), 3f, 20f,                        // colour, intensity, range
        15f, 30f);                                                     // inner and outer half-angle, in degrees
scene.lights().add(torch);
```

Inside the **inner** angle the light is at full strength; it fades smoothly to nothing at the **outer** angle (at most
85 degrees) and fades with distance to nothing at `range`, like a point light. `setDirection`, `setCone(inner, outer)`,
`setVector` (the position), `setRange` and the rest can be changed every frame.

## Shadows

One directional or spot light can make the scene's shadows. Turn it on, and nodes cast and receive shadows by default:

```java nocompile
Light sun = Light.directional(new Vec3(-0.5f, -1f, -0.35f), Color.WHITE, 1.1f).setCastShadow(true);
scene.lights().clear();
scene.lights().add(sun);

scene.setShadowDistance(40f);       // shadows are drawn this far from the camera
scene.setShadowMapSize(2048);       // sharpness
lamp.setCastShadow(false);          // a node (a Node3D) that throws no shadow
```

| Setting | Default | Description |
|---|---|---|
| `Light.setCastShadow(boolean)` | `false` | The first directional or spot light (among the first 8) that has this on casts the scene's shadows. Point lights can't |
| `Scene3D.setShadowDistance(float)` | `60` | How far from the camera a directional light's shadows are drawn, in scene units, and never past the camera's far plane. Shorter means sharper shadows near the camera. A spot light's shadows cover its whole cone out to its range |
| `Scene3D.setShadowMapSize(int)` | `2048` | The width and height of the shadow picture, `256` to `8192`. Each doubling quadruples the memory (about 32 MB at 2048) and the cost |
| `Scene3D.setShadowBias(float)` | `1` | Scales the offset that stops a surface shadowing itself. Raise it if a lit surface shows stripes; lower it if shadows float away from what casts them |
| `Node3D.setCastShadow(boolean)` | `true` | Whether the node's mesh throws a shadow |
| `Node3D.setReceiveShadow(boolean)` | `true` | Whether shadows fall on it. Turn it off for something that should stay evenly lit |

How it works, so you know what to expect:

- Each frame the scene first draws the world's depth as the light sees it into a shadow picture, then the main picture
  looks each surface up in it. The picture covers the part of the camera's view between the near plane and the shadow
  distance, and its position snaps to the picture's own pixels, so shadows don't crawl as the camera moves.
- Edges are softened over a few pixels of the shadow picture. A bigger `setShadowMapSize` or a shorter distance makes
  them crisper; there is no variable-width soft shadow.
- [Skinned](animation.md) and [instanced](instancing.md) meshes cast shadows in their current pose. A cut-out material
  (`setAlphaCutoff`) casts a shadow with its holes. A **blended** (see-through) material casts none.
- A shadow can only fall on surfaces inside the part of the view it covers. The sun itself has no position, so a tall
  object far from the camera still shadows what is near it.
- A spot light's shadow picture is a view down its cone, so the cone's width is spread over the picture: a narrow cone
  gives sharper shadows than a wide one, and a cone near 85 degrees is coarse. Only one light casts shadows.
- Shadows from the ambient light and from point lights don't exist; a point light's lit side shows no shadows. To light a
  room from a fire with shadows, a spot light pointing into the room does the job.
- Shadows work in every viewport and on both OpenGL and Vulkan.

## Fog

```java nocompile
scene.setFog(new Color(0.75f, 0.6f, 0.8f, 1f), 0.02f);          // colour, density
scene.setFog(new Color(0.75f, 0.6f, 0.8f, 1f), 0.03f, 0.25f);   // thicker low down, like mist
```

Distant things fade into the fog colour: the strength is `1 - exp(-(distance * density)^2)`, so nothing close up and
thick far away. A density of `0.02` hazes things 40 units away; `0` (the default) is no fog. The third argument is how
fast the fog thins with height (0 is the same everywhere). Match the fog colour to the sky at the horizon so the world
melts into it: fog is not applied to the [skybox](#skybox). Additive surfaces fade out in fog instead of turning the fog's
colour.

## Skybox

A skybox is the sky behind everything: it turns with the camera but never moves with it, so it looks infinitely far away.
It is drawn instead of the scene's plain background colour.

```java nocompile
scene.setSkybox(Skybox.gradient(
        new Color(0.2f, 0.4f, 0.9f, 1f),       // straight up
        new Color(0.85f, 0.9f, 0.95f, 1f),     // at the horizon
        new Color(0.25f, 0.22f, 0.2f, 1f)));   // straight down

scene.setSkybox(Skybox.cube(right, left, top, bottom, front, back));   // six TextureData
scene.setSkybox(Skybox.equirect(TextureData.load(Path.of("sky.png"))));
scene.setSkybox(null);                                                  // back to the plain colour
```

| Kind | Made with | Notes |
|---|---|---|
| Gradient | `Skybox.gradient(top, horizon, bottom)` | Three colours blended by the height of the view direction. No pictures, no memory |
| Cube map | `Skybox.cube(+X, -X, +Y, -Y, +Z, -Z)` | Six square pictures of the same size, the faces of a cube around the camera, in the order of the usual *right, left, top, bottom, front, back* skybox files. Mipmapped |
| Panorama | `Skybox.equirect(TextureData)` | One wide picture, the middle row at the horizon and the top row straight up, usually twice as wide as high. The centre of the picture is the direction the camera looks along `-Z` |

`Skybox.setBrightness(float)` scales the sky (`1` as drawn). One skybox can be set on any number of scenes. The pictures
are uploaded when first drawn and freed some time after the scene stops using them, like any texture.

## Gotchas

- **The sky doesn't light the scene.** Set the ambient light and the lights to match it.
- **Only the first 8 lights count,** and the shadow-casting one must be among them, or it is ignored.
- **Shadow memory adds up:** the shadow picture is one per scene and device, shared by its viewports, but a size of 4096 or more
  is hundreds of megabytes of GPU memory on top of the picture itself. 2048 is sharp enough for most scenes.
