# Shaders and post effects

[Documentation](../README.md) › [Graphics](README.md)

A `ShaderEffect` (`me.piitex.engine.graphics`) is a shader written in SkSL, Skia's shading language, with the values of
its uniforms. It runs the same on every backend: Metal, OpenGL, Vulkan, and the CPU renderer that
[snapshots](../core/snapshots.md) use. The engine only runs shaders; the effects themselves are yours to write.

These are 2D, Skia-side effects. Shaders for the optional [3D layer](../3d/README.md) are a different thing: GLSL 450,
run on the GPU by OpenGL or Vulkan, see [Custom rendering](../3d/custom-rendering.md#shaders).

There are two ways to use one:

| Use | How | The shader's `coord` |
|---|---|---|
| Fill a rectangle with it: procedural backgrounds, water, glows, bars | `renderer.drawShader(effect, x, y, w, h, cornerRadius)` | From (0, 0) at the rectangle's top-left to (w, h) |
| Pass drawing through it: post-processing, such as a color grade, a distortion, a CRT look | `renderer.pushEffect(effect, x, y, w, h)` … `renderer.popEffect()`, or `element.setEffect(effect)` | Where the drawing is, in the space it was drawn in |

## Writing a shader

SkSL is close to GLSL. A shader is a `main` function that returns the color of one point, with `half4` for colors and
`float2` for positions:

```java nocompile
ShaderEffect stripes = ShaderEffect.compile("""
        uniform float time;
        uniform half4 color;
        half4 main(float2 coord) {
            float band = step(0.5, fract((coord.x + coord.y + time * 40) / 24));
            return color * (0.6 + 0.4 * band);
        }
        """);
stripes.set("color", Color.ORANGE);

// every frame
stripes.set("time", (float) GameTime.getTime());
renderer.drawShader(stripes, 20, 20, 300, 16, 8f); // a rounded bar
```

`ShaderEffect.load(path)` reads a shader from a file; `.sksl` is the usual extension. A shader that doesn't compile
throws `IllegalArgumentException` with the compiler's message, which gives the line and marks the mistake.

Colors are **premultiplied**: red, green and blue are already multiplied by alpha. Return `half4(rgb * a, a)` for a
translucent color. `content.eval` and textures return premultiplied colors too, so scaling all four channels, or only
the first three of an opaque color, is always safe.

## Uniforms

Set uniforms with `set(name, values...)`. Values stay set until changed, so set a time uniform every frame and the
rest once. `set` returns the effect, so calls chain.

| SkSL type | Set with |
|---|---|
| `float`, `float2`, `float3`, `float4` (and `half` types) | `set(name, floats...)`, as many values as the type holds |
| `float2x2`, `float3x3`, `float4x4` | `set(name, floats...)`, column by column |
| `float weights[4]`, arrays of any of the above | `set(name, floats...)`, every element in order |
| `int` to `int4` | `set(name, ints...)` |
| `half4` or `float4` used as a color | `set(name, Color)`: red, green, blue, alpha, not premultiplied |
| `shader` | `set(name, Texture)`; see below |

`set` checks the name and the number of values against the shader's declarations. A mistake throws an exception listing
the uniforms the shader declares, since Skia itself would ignore it. `has(name)` tells whether a uniform is declared,
so one piece of code can feed several shaders. `getUniforms()` lists them all.

## Textures

A `uniform shader` other than `content` reads a [`Texture`](sprites.md#textures):

```java nocompile
ShaderEffect dissolve = ShaderEffect.compile("""
        uniform shader picture;
        uniform shader noise;
        uniform float amount;
        half4 main(float2 coord) {
            half4 color = picture.eval(coord);
            return noise.eval(coord).r < amount ? half4(0) : color;
        }
        """);
dissolve.set("picture", heroTexture).set("noise", noiseTexture).set("amount", 0.3f);
renderer.drawShader(dissolve, x, y, heroTexture.getWidth(), heroTexture.getHeight(), 0f);
```

- `eval(p)` takes `p` in the texture's own pixels. Scale `coord` to stretch a texture over a rectangle of another size.
- Past the texture's edge it repeats the edge pixels.
- It blends neighbouring pixels or keeps hard pixel edges, as `Texture.isSmooth()` says when `set` is called. Set it
  again after changing that.
- `set(name, (Texture) null)` unsets it. An unset texture reads as transparent.

## Post effects

`pushEffect` collects everything drawn until the matching `popEffect` in an offscreen layer. The shader then receives
that layer as `uniform shader content`, which it must declare. `content.eval(coord)` reads the drawing at the point
being colored:

```java nocompile
ShaderEffect vignette = ShaderEffect.compile("""
        uniform shader content;
        uniform float2 size;
        half4 main(float2 coord) {
            half4 color = content.eval(coord);
            float edge = distance(coord / size, float2(0.5));
            return half4(color.rgb * (1 - edge * edge * 1.4), color.a);
        }
        """);

// Scene.render
vignette.set("size", width, height);
renderer.pushEffect(vignette, 0, 0, width, height);
camera.begin(renderer);
batch.draw(renderer, camera.getVisibleBounds());
camera.end(renderer);
renderer.popEffect();          // what comes after, like a HUD or a minimap, is drawn normally
```

- **Coordinates:** `coord` is in the space `pushEffect` was called in: window points for the call above, even on a
  retina screen. Inside a [`Camera`](camera.md)'s `begin`/`end`, it is world coordinates.
- **Distortion:** read `content` at another point to move pixels, for heat haze, waves or a lens. Outside the pushed
  rectangle, `content` is transparent. Clamp the point to stay inside: `content.eval(clamp(p, float2(0.5), size - 0.5))`.
- **Clipping:** the result is drawn only inside the rectangle. Drawing outside it between push and pop is cut off.
- **Nesting:** effects nest, and each is applied to what the inner ones produced.
- **Cost:** each effect is an extra offscreen pass over its rectangle. A few full-window effects a frame are fine on
  any GPU; the CPU renderer is slower with them.
- An effect that is null, closed or has no `content` leaves the drawing as it is.

### On an element

`Element.setEffect(effect)` does the same for an element and its children. Given to a
[scene](../game/scenes.md)'s container, it covers the scene's world and its UI together:

```java nocompile
getContainer().setEffect(crt); // the whole scene, HUD included
```

See [Element › Shader effect](../core/element.md#shader-effect).

## Things to know

- **Render thread only,** like the rest of drawing. Set uniforms in `update` or `render`.
- **Patterns fixed in the world:** `drawShader`'s `coord` starts at the rectangle's corner, wherever that is. A fill
  that covers the camera's view moves with the view, so a pattern that should stay put in the world takes the corner
  as a uniform and adds it: `coord + origin`, with `set("origin", view.x(), view.y())`, as `SpriteDemo`'s sea does.
- **Closing:** a renderer never closes an effect. `close()` it when the scene that uses it exits, or leave it to the
  garbage collector.
- **Custom renderers:** a `Renderer` that doesn't override `drawShader`, `pushEffect` and `popEffect` draws nothing for
  `drawShader` and leaves post effects out, so it keeps working.
- **SkSL reference:** Skia's documentation covers the language, its built-in functions, and what it doesn't have
  compared to GLSL: no texture2D (use `eval`), no loops with a bound unknown when compiling, and no discard (return
  `half4(0)`).

`SpriteDemo` in `me.piitex.examples.demo` uses both. The sea around its map is drawn with `drawShader` inside the camera,
so the waves turn and zoom with the world, and F2 switches between three post effects: night, underwater and an old
monitor. The shaders are the `.sksl` files in `src/main/resources/demo/shaders`.
