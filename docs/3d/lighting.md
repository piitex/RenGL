# Lighting

[Documentation](../README.md) › [3D](README.md)

What lights a [`Scene3D`](scenes.md): its [lights](scenes.md#light), the ambient light, shadows, and the skybox behind
everything. Lighting is the same for classic and [physically based](scenes.md#material) materials; they differ in how a
surface answers the light, not in what the light is.

## How lights add up

A scene shades up to 64 lights at once (`Scene3D.MAX_LIGHTS`). Each is **directional** (the sun: parallel rays from one direction, no position), a
**point** light (a bulb: a position, fading to nothing at its range) or a **spot** light (a point light that only shines
inside a cone, like a torch or a hearth opening onto a room). The surface receives the **ambient**
light from every side, plus each light's share by the angle between the surface and the light, and a highlight.

- A light's `intensity` is "how bright a white surface facing it comes out": a white light of `1` lights a white surface
  facing it to full white, and `0.9` (the default sun) a little under that. Several lights add.
- Colours are the screen colours you pick everywhere else; the scene converts them to linear light to add them up and
  back to show them, so two half-bright lights make a surface look as bright as one full one should.
- Nothing is clamped or tone-mapped: a surface lit by more than `1` in total simply shows white.
- `scene.setAmbient(color, intensity)` is the light nothing blocks. It is what keeps the dark side of an object from being
  black. A [skybox](#skybox) is only a picture unless you turn on [light from the sky](#light-from-the-sky); then it
  lights the scene in place of the flat ambient light.
- Without light from the sky, metals reflect the lights, not their surroundings: a fully metallic material under only
  ambient light looks dark, and shows its colour in the highlights of the lights. With it, they reflect the sky.

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

## Blinking lights

A `LightAnimation` makes lights and glowing surfaces blink, pulse or flicker. A `LightPattern` says how bright they
are at each moment, from `0` (off) to `1` (full strength), and the animation applies it to the lights and materials it
was given, so a lamp and the bulb you see in it stay in step:

```java nocompile
Light lamp = Light.point(new Vec3(2, 3, 0), Color.RED, 6f, 8f);
scene.lights().add(lamp);
Material bulb = new Material(Color.BLACK).setEmissive(Color.RED, 4f);
lampNode.setMaterial(bulb);

scene.animate(LightAnimation.of(LightPattern.blink(1f)).light(lamp).glow(bulb));
```

Whatever strength the light or the material has when you add it is its **full strength**; the animation scales it
between nothing and that. So set the intensity and the emissive first, and add them before the animation runs. A
material with no emissive glow is left alone, and so is everything the animation was not given.

| Pattern | What it does |
|---|---|
| `LightPattern.steady()` | Always full |
| `blink(period)` | On for half the period and off for half, starting on: a status LED |
| `blink(onSeconds, offSeconds)` | The same with unequal times: a quick flash and a long wait |
| `pulse(period)` | Rises smoothly to full and back, starting off: something breathing |
| `flicker(rate, depth, seed)` | `rate` times a second picks a new brightness between `1 - depth` and 1: a tube on a bad supply |
| `faulty(period, seed)` | Steady, with a burst of fast stutters in about one slot of `period` seconds in three: a dying neon sign |
| `sequence(step, levels...)` | Steps through the levels, `step` seconds each, and loops: `sequence(0.25f, 1, 0, 1, 0, 0, 0)` is two flashes and a pause |

A pattern is a function of time, so your own is a lambda: `LightPattern heartbeat = time -> ...` returning 0 to 1. The
random-looking ones are worked out from the time and the seed, so the same moment always gives the same brightness;
give lights different seeds so they do not flicker together.

| Method | What it does |
|---|---|
| `light(Light)` / `glow(Material)` | Add a light's intensity, or a material's emissive glow. One animation can drive several of each |
| `setMinimum(share)` | The lowest brightness as a share of full: `0.1` leaves a dim LED instead of a dark one |
| `setSpeed(factor)` | `2` runs the pattern twice as fast |
| `setTime(seconds)` | Where in the pattern it starts; give each animation a different one to put them out of step |
| `setPlaying(boolean)` | Pause and resume. A paused animation leaves the lights where they are |
| `getLevel()` | The brightness right now, from the minimum up to 1, to drive a sound or a sprite |

Things to know:

- **Added to a scene** with `scene.animate(animation)` it advances once per frame, before the update handler, and a
  viewport that only draws when something changes keeps drawing while an animation plays. `scene.stopAnimating`
  removes it. Without a scene, call `animation.update(delta)` yourself each frame.
- **A light at `0` costs nothing**: a dark light takes no room in the [64](#gotchas), so a blinking light is not
  counted while it is off.
- **A light at `0` casts no shadow** either: the scene skips its shadow views while it is off. How blinking a
  shadow-casting light affects frame time has not been measured.
- **Not saved in scene files.** The animation changes the lights and materials directly, so build it in code after
  loading, and do not also change those lights by hand.
- **Emissive glow is not light.** The bulb shines on screen but does not light the wall; that is the lamp's job, which is
  why the animation drives both. Glow does feed [bloom](#bloom), so a bright blinking bulb blooms.

## Shadows

Any light can cast shadows: the sun, a spot light or a point light, and several at once. Give a light a quality preset
and nodes cast and receive shadows by default:

```java nocompile
Light sun  = Light.directional(new Vec3(-0.5f, -1f, -0.35f), Color.WHITE, 1.1f)
        .setShadows(Shadows.of(ShadowQuality.HIGH).cascades(3).distance(80f));   // sharp near, far-reaching
Light fire = Light.point(new Vec3(2, 1, 0), new Color(1f, 0.6f, 0.3f, 1f), 3f, 12f)
        .setShadows(ShadowQuality.MEDIUM);                                       // a bulb that shadows all round
Light lamp = Light.spot(new Vec3(0, 5, 0), new Vec3(0, -1, 0), Color.WHITE, 3f, 20f, 20f, 35f)
        .setShadows(Shadows.of(ShadowQuality.LOW).bias(1.5f).softness(2f));      // a preset with changes
scene.lights().clear();
scene.lights().add(sun);
scene.lights().add(fire);
scene.lights().add(lamp);

lampNode.setCastShadow(false);      // a node (a Node3D) that throws no shadow
```

`light.setCastShadow(true)` still works and means "use the scene's settings" (see below). A light without `setShadows`
or `setCastShadow(true)` casts none.

### Presets

| `ShadowQuality` | Picture size | Edge | Use it for |
|---|---|---|---|
| `LOW` | 512 | 3x3 blur | small or distant lights |
| `MEDIUM` | 1024 | 3x3 blur | most lights, and point lights |
| `HIGH` | 2048 | 3x3 blur | a sun or a main spot light |
| `ULTRA` | 4096 | 5x5 blur | one hero light; a single picture is 64 MB |

`Shadows.of(preset)` starts from a preset, and these overrides change one thing each (every method returns a changed copy):

| `Shadows` method | Default | Description |
|---|---|---|
| `size(int)` | the preset's | Width and height of one shadow picture in pixels, `256` to `8192` |
| `bias(float)` | the scene's (`1`) | Scales the offset that stops a surface shadowing itself. Raise it if a lit surface shows stripes; lower it if shadows float away from what casts them |
| `softness(float)` | the scene's (`1`) | How wide the soft edge is, as a multiple of the default: `0.25` to `4`. Above about `2` the filter pattern starts to show unless the picture is large |
| `distance(float)` | the scene's (`60`) | Directional lights: how far from the camera the shadows reach, in scene units, and never past the camera's far plane |
| `cascades(int)` | `1` | Directional lights: split the view into `1` to `4` pictures, see [Cascades](#cascades) |
| `filter(ShadowFilter)` | the preset's | How the edge is worked out, see [Custom filters and falloffs](#custom-filters-and-falloffs) |

A value you don't set comes from the scene, so these still work as the defaults for every light:
`Scene3D.setShadowMapSize(int)` (`2048`, what a light with `setCastShadow(true)` gets; point lights get `1024`),
`setShadowDistance(float)`, `setShadowBias(float)` and `setShadowSoftness(float)`. The nodes have their own switches:

| Setting | Default | Description |
|---|---|---|
| `Node3D.setCastShadow(boolean)` | `true` | Whether the node's mesh throws a shadow |
| `Node3D.setReceiveShadow(boolean)` | `true` | Whether shadows fall on it. Turn it off for something that should stay evenly lit |

### Which lights get shadows

Every light of a scene can cast shadows: up to **8** (`Scene3D.MAX_SHADOW_LIGHTS`, the same as the most lights a scene uses).
Directional lights come first, then the point and spot lights nearest the camera. A point or spot light whose reach is
wholly outside the camera's view is skipped for that frame. Each light is a pass over the casters, so eight of them cost
about eight times one; keep the shadows of the lights that matter and leave `setCastShadow` off on the rest.

All shadow pictures live in one shared **atlas** texture, and a light gets a tile of it per view: a directional light one
tile per cascade, a spot light one, and a point light six (one per side of a cube around it). The atlas is only as large
as the lights need, up to `Scene3D.setShadowAtlasSize(int)` (`4096`, range `1024` to `8192`). If the tiles don't fit in
that, the biggest are drawn at half size until they do, and the scene logs a warning once. `Scene3D.getStats()` reports
`shadowViews()` and the atlas's width and height, so you can see what your lights cost.

### Cascades

A sun lights the whole view, but one shadow picture spread over 80 units is blurry up close. Split the view into
cascades and each part gets its own picture: a near one that is small and sharp, and farther ones that reach out.

```java nocompile
sun.setShadows(Shadows.of(ShadowQuality.MEDIUM).cascades(3).distance(80f));
```

- The split is between even and logarithmic (near cascades are small), and each cascade's picture snaps to its own pixels, so
  shadows don't crawl as the camera moves.
- Each cascade draws the shadow casters again, so 3 cascades cost about 3 times the shadow drawing. Casters that don't touch
  a cascade's area are skipped.
- Past `distance` there are no shadows. One cascade is the same as the single picture earlier versions drew.

### How it works, so you know what to expect

- Each frame the scene first draws the world's depth as each light sees it into its tiles, then the main picture looks each
  surface up in them.
- Edges are softened over a few pixels of the tile. A bigger picture, a shorter distance or more cascades makes them
  crisper, and `softness` widens or narrows the edge. The width is the same everywhere: a shadow does not get softer the
  farther it falls from what casts it (no variable-width soft shadows).
- A node outside the camera's view still casts its shadow into it: [frustum culling](scenes.md#frustum-culling) only
  skips drawing it.
- [Skinned](animation.md) and [instanced](instancing.md) meshes cast shadows in their current pose. A cut-out material
  (`setAlphaCutoff`) casts a shadow with its holes. A **blended** (see-through) material casts none.
- A directional light's shadow can only fall on surfaces inside the part of the view it covers. The sun itself has no
  position, so a tall object far from the camera still shadows what is near it.
- A spot light's picture is a view down its cone, so the cone's width is spread over the picture: a narrow cone gives
  sharper shadows than a wide one, and a cone near 85 degrees is coarse.
- A point light's six pictures each cover a little more than a quarter-turn square, so there is no gap between sides. Its
  shadows reach as far as its `range`. They cost six tiles, so give a point light `MEDIUM` or `LOW` unless it is the star
  of the scene.
- Ambient light and light from the sky cast no shadows.
- Shadows work in every viewport and on both OpenGL and Vulkan.

### Custom filters and falloffs

Two pieces of the lighting are swappable: how a shadow's edge is filtered, and how a light fades with distance. Each is a
small GLSL function **body** you write; the scene's shader is built to hold exactly the ones its lights use, so an unused
one costs nothing, and the first frame that uses a new one compiles a program (a short stall).

```java nocompile
// A shadow edge from five lookups in a cross. In scope: uv, depth, texel, softness, and shadowLit(tap, depth).
ShadowFilter cross = ShadowFilter.custom("cross", """
        float s = shadowLit(uv, depth);
        s += shadowLit(uv + vec2(texel.x, 0.0) * softness, depth);
        s += shadowLit(uv - vec2(texel.x, 0.0) * softness, depth);
        s += shadowLit(uv + vec2(0.0, texel.y) * softness, depth);
        s += shadowLit(uv - vec2(0.0, texel.y) * softness, depth);
        return s * 0.2;
        """);
lamp.setShadows(Shadows.of(ShadowQuality.MEDIUM).filter(cross));

// A smooth fade; in scope: dist (distance from the light) and range.
LightFalloff smooth = LightFalloff.custom("smooth", "float t = clamp(1.0 - dist / range, 0.0, 1.0); return t * t * (3.0 - 2.0 * t);");
torch.setFalloff(smooth);
torch.setFalloff(LightFalloff.LINEAR);      // or a built-in one
```

| Built-in | What it does |
|---|---|
| `ShadowFilter.HARD` | One lookup per pixel: crisp, blocky edges, the cheapest |
| `ShadowFilter.PCF` | A bilinear-weighted 3x3 blur: the default |
| `ShadowFilter.SOFT` | A wider 5x5 blur |
| `LightFalloff.WINDOWED_INVERSE_SQUARE` | Fades smoothly to nothing at the range: the default |
| `LightFalloff.LINEAR` | A straight line from full strength to nothing at the range |
| `LightFalloff.NONE` | Never fades and ignores the range; for a light very far away |

- A filter's function returns how much light reaches the point, from `0` (shadow) to `1` (lit). Look the map up with
  `shadowLit(tap, depth)` only: it keeps the lookup inside the light's own tile so an edge never reads a neighbour's.
- The name is letters and digits and only labels the piece. Two custom pieces with the same GLSL are one.
- A body that doesn't compile is reported once in the log, and the lights that use it fall back to the built-in filter
  or falloff, so a typo costs you the look and not the frame.
- A directional light doesn't fade, so a falloff on it does nothing.

## Tone mapping and exposure

Lights add up: three lamps on one wall, a strong sun, a bright glow can light a surface to more than white, and a screen
can only show white. Without help the sum is cut off, and bright areas become flat patches with no detail in them.
`scene.setToneMapping(...)` bends the bright end of the range instead, and `scene.setExposure(...)` scales the light first:

```java nocompile
scene.setToneMapping(ToneMapping.ACES);   // NONE (the default), REINHARD, or ACES
scene.setExposure(1.3f);                  // 2 is one stop brighter, 0.5 one darker
```

| Mapping | Look |
|---|---|
| `NONE` | Colours are cut off at white: what a scene did before there were tone mappers. Nothing changes until you set one |
| `REINHARD` | `c / (1 + c)`: soft, never reaches white, a little flat in the middle tones |
| `ACES` | A fit of the film curve: more contrast, deeper shadows, highlights that roll off gently. Darkens the middle tones a little, so raise the exposure to 1.2 or 1.5 if the scene gets dim |

It applies to everything lit, fog included, and not to the skybox, which is drawn as its picture is. Exposure works with
`NONE` too, as a plain brightness. In a [scene file](scene-files.md) they are the world's `toneMapping` and `exposure`.

## Bloom

Bloom makes the brightest parts of the picture glow, the way a camera lens does: a halo round a lamp, a glint on metal,
the sun in a window.

```java nocompile
scene.setToneMapping(ToneMapping.ACES);
scene.setBloom(0.6f);            // strength, 0 (off, the default) to about 1
scene.setBloomThreshold(0.8f);   // how bright, as a share of white, a pixel must be to glow
scene.setBloomRadius(1f);        // how far the glow spreads
```

The scene is drawn in linear light into a 16-bit floating point target, so the threshold is judged before tone mapping and
a lamp eight times brighter than white glows more than one at white. The tone mapper and gamma are applied once, at the
end, to the picture and its glow together (gradient and cube skies included). Give a material an
[emissive](scenes.md#material) colour above 1 and it glows without any light on it.

- **Cost:** the scene draws into a target of its own and through a few full-screen passes at half and quarter size: a few
  percent of the frame and a few textures. The target is multisampled like the viewport, so edges stay smooth.
- **The radius is a share of the picture's height**, so the glow looks the same on a high-density display as on a plain
  one, and in a small viewport as a full-screen one.
- A radius above about 3 shows the blur's taps as faint copies of the light.
- In a [scene file](scene-files.md) they are the world's `bloom`, `bloomThreshold` and `bloomRadius`.

## Ambient occlusion, colour grade and lens effects

These finish the picture the way bloom does, in the same pass, and each is off by default. Together they are most of the
difference between a flat render and a moody one: grounded corners, teal shadows and magenta highlights, a little lens
fringing, dark edges.

```java nocompile
scene.setAmbientOcclusion(1f);            // 0 (off) to about 2: how dark creases and corners get
scene.setAmbientOcclusionRadius(0.5f);    // world units: how far one surface can darken another

scene.setSaturation(1.2f);                // 1 is as drawn, 0 is black and white
scene.setContrast(1.15f);                 // 1 is as drawn
scene.setShadowTint(new Color(0.55f, 0.85f, 1f, 1f));      // multiplies the dark parts; white changes nothing
scene.setHighlightTint(new Color(1f, 0.7f, 0.9f, 1f));     // multiplies the bright parts

scene.setChromaticAberration(0.003f);     // red and blue pulled apart toward the edges, as a share of the width
scene.setVignette(0.4f);                  // darker corners
```

- **Occlusion is screen-space.** It draws the solid objects a second time from the camera, stores their depth, and
  darkens each point by how much of the space above it is filled by other surfaces. That needs no setup, but it only
  knows what is on screen: it can't see behind an object, and a thin object in front of a far wall can leave a faint halo.
  Transparent surfaces and lines are not part of it.
- **It darkens everything**, direct light too, because it is applied to the finished picture. A strength of 0.5 to 1 reads
  as grounding; higher looks like a dirty lens. Set the radius to the size of the detail: 0.2 for the gaps between props,
  1 for walls and furniture. A radius smaller than a few pixels at the distance you look from does nothing.
- **The grade is applied after tone mapping**, to the picture as it will be seen. The tints multiply, so white is
  "no tint"; the shadow tint fades into the highlight tint with brightness. Only the colours' red, green and blue count.
- **The colour fringe** is zero in the middle and grows toward the edges; 0.003 is subtle, 0.01 is loud.
- **Cost:** any of these turns on the offscreen path bloom uses (a 16-bit target, resolved before the last pass). Occlusion
  adds the depth pass over the solid objects and three small passes at half size. The rest is a few more texture reads in
  the last pass.
- In a [scene file](scene-files.md) they are the world's `ambientOcclusion`, `ambientOcclusionRadius`, `saturation`,
  `contrast`, `shadowTint`, `highlightTint`, `chromaticAberration` and `vignette`.

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
scene.setSkybox(Skybox.equirectHdr(HdrTextureData.load(Path.of("sky.hdr"))));   // light with no upper limit
scene.setSkybox(null);                                                  // back to the plain colour
```

| Kind | Made with | Notes |
|---|---|---|
| Gradient | `Skybox.gradient(top, horizon, bottom)` | Three colours blended by the height of the view direction. No pictures, no memory |
| Cube map | `Skybox.cube(+X, -X, +Y, -Y, +Z, -Z)` | Six square pictures of the same size, the faces of a cube around the camera, in the order of the usual *right, left, top, bottom, front, back* skybox files. Mipmapped |
| Panorama | `Skybox.equirect(TextureData)` | One wide picture, the middle row at the horizon and the top row straight up, usually twice as wide as high. The centre of the picture is the direction the camera looks along `-Z` |

| HDR panorama | `Skybox.equirectHdr(HdrTextureData)` | The same panorama made of light with no upper limit: a Radiance `.hdr` file, as HDRI photographs of real skies come. The sun in it is really bright. Held as 16-bit floats, so it takes twice the memory of an 8-bit picture of the same size |

`Skybox.setBrightness(float)` scales the sky (`1` as drawn). One skybox can be set on any number of scenes. The pictures
are uploaded when first drawn and freed some time after the scene stops using them, like any texture.

### Light from the sky

By default the sky is only a picture. Turn on environment lighting and it becomes a light, the way the real sky is:

```java nocompile
scene.setSkybox(Skybox.equirect(TextureData.load(Path.of("sky.png"))));
scene.setEnvironmentLighting(1f);   // 1 is the sky as drawn, 0 (the default) is off
scene.setAmbient(Color.WHITE, 0f);  // no longer used where the sky lights the scene, but harmless
```

- **Diffuse:** each surface is lit softly by the colour of the sky it faces: blue from above on a clear day, warm from a
  sunset side. The sky is reduced to nine spherical-harmonic coefficients per colour once, on the CPU, when the sky is
  first used, so it costs a handful of multiplications per pixel.
- **Reflections:** [physically based](scenes.md#material) materials reflect the sky, blurred by their roughness. A polished
  metal sphere shows the sky around it; a rough one shows an average of it. The blur comes from the sky's mipmaps, and the
  strength of the reflection from a fit to the usual split-sum integral, so it is a good approximation and not a
  filtered environment map. Classic materials get the diffuse part only.
- **It replaces the flat ambient light** wherever it is on. Your [lights](scenes.md#light) still add their own light on
  top, and only they cast shadows: the sky lights every surface from every side whether or not something shades it.
- Works with every kind of skybox. The gradient is evaluated directly; a cube map or panorama is read from its
  mipmaps.
- The skybox pictures of the other kinds are ordinary 8-bit images, so a bright sun in one adds no more light than white
  does. Put the sun in as a directional light, or use an [HDR panorama](#hdr-skies).
- It is a **per-scene** setting and costs one more texture binding in the shader programs of the scenes that use it.

### HDR skies

An 8-bit sky can't hold a sun: white is as bright as it gets. A `.hdr` panorama can, so with it the sun lights the scene
and shines in glossy surfaces by itself:

```java nocompile
scene.setSkybox(Skybox.equirectHdr(HdrTextureData.load(Path.of("sky.hdr"))));
scene.setEnvironmentLighting(1f);
scene.setToneMapping(ToneMapping.ACES);   // brings the sun into range without whitening everything else
scene.setExposure(0.8f);
```

- Without a [tone mapper](#tone-mapping-and-exposure) the sky is cut off at white, as an 8-bit one is, so use one.
- The sun still doesn't cast shadows: add a directional light pointing the same way for that. To find which way, note that
  the middle of the panorama looks along -Z, a quarter of the way across (u = 0.75) along +X, and the edges along +Z; the
  top row is straight up. A real 1k photograph, checked on both Vulkan and OpenGL, puts its sun where this says.
  `HdrTextureData.sunDirection()` finds the brightest spot for you, so
  `Light.directional(sky.sunDirection().negate(), color, intensity)` lines the light up with the sun in the picture (a
  directional light's direction is the way it travels). `HdrSkyDemo` does exactly this.
- Only the Radiance `.hdr` format (RGBE) loads, flat or run-length coded; OpenEXR doesn't.
- In a [scene file](scene-files.md), choose a panorama whose path ends in `.hdr`.
- Needs a GPU that can sample 16-bit float textures, which every OpenGL 3.3 and Vulkan device can.

## Gotchas

- **The sky doesn't light the scene** unless you call [`setEnvironmentLighting`](#light-from-the-sky). Otherwise set the
  ambient light and the lights to match it.
- **At most 64 lights are shaded in a frame.** A scene with more still draws: each frame the directional lights are
  kept and then the point and spot lights whose reach is in the camera's view, nearest first, and the rest are left out
  (a warning is logged once). A light switched off (intensity `0`) takes no room when that choice has to be made, so
  blinking lights can be switched off instead of removed. Every pixel is tested against every light, with a cheap
  distance check that skips the full shading of lights that do not reach it, so lights with small ranges cost far less
  than lights with large ones: give each lamp the smallest `range` that looks right.
  Only up to 8 lights cast shadows, whichever of the 64 they are.
- **Shadows cost a pass per view:** a point light is six, a directional light one per cascade. Check
  `scene.getStats().shadowViews()` if a shadow is missing or the frame is slow.
- **Shadow memory adds up:** the shadow atlas is one per scene and device, shared by its viewports, and a 4096 square is
  64 MB of GPU memory. A point light is six tiles and each cascade one, so `ULTRA` on several lights fills it at once and
  the biggest tiles are halved. `HIGH` is sharp enough for most scenes.
