# Backgrounds

[Documentation](../README.md) › [Styling](README.md)

A **`Background`** is a decorative, optionally animated effect painted **behind an element's own content**. Attach
one with `Element.setBackground`:

```java
mainContainer.setBackground(new ConstellationBackground());
formPanel.setBackground(new DotGridBackground());
root.setBackground(new AnimatedDotBackground());
root.setBackground(new ElectricNodeBackground());
```

The same effects work behind the whole window with `Window.setBackground`. There the effect is drawn over the
window's flat background color, behind every container, at the window's size:

```java
window.setBackground(new ConstellationBackground());
```

## 3D behind the window

`Window.setBackground` takes any `Background`, so a live 3D scene can sit behind the whole window, with every container drawn
over it. [`Background3D`](../3d/background-3d.md) is that background:

```java nocompile
window.setBackground(new Background3D(window, scene));
```

It needs a window on OpenGL or Vulkan and is optional like the rest of the [3D layer](../3d/README.md).

## Why it is not a child element

A [`Layout`](../layouts/README.md) positions every child it has. A full-size decorative effect added as a real child would be caught up in
that positioning like any other child, which is almost never what you want. `setBackground` is **not** a
child, so a layout never touches it. The effect paints behind the layout's real children without disturbing the
layout at all.

## Draw order

For each element: flat fill (background color) → attached `Background` → border → your `onRender` hook →
widget content and children. Each frame the background is handed the element's current bounds to draw within.

## Built-in backgrounds

All in `me.piitex.engine.ui.background`.

### DotGridBackground

A tiled grid of small dots, the "infinite canvas" look of node editors and whiteboards. Static unless its paint is animated.

```java
DotGridBackground grid = new DotGridBackground();
grid.setSpacing(24f); // distance between dots (default 24)
grid.setDotRadius(1.5f); // default 1.5
grid.setDotColor(new Color(0.5f, 0.5f, 0.5f, 0.35f)); // default; any paint
panel.setBackground(grid);
```

### AnimatedDotBackground

A grid of dots that pulse, ripple and cycle through the colour wheel: the animated sibling of
`DotGridBackground`.

```java
AnimatedDotBackground dots = new AnimatedDotBackground();
dots.setHueSpan(0.15f); // calmer: one colour family instead of the full rainbow
dots.setSpeed(0.5f); // half speed
root.setBackground(dots);
```

Every frame each dot's radius and hue are computed from its position and a running clock. Nothing is stored per
dot, so resizing or moving the owner just works. Three waves are summed into one value that drives both size and hue:

- a **ripple** travelling outward from the centre,
- a **diagonal wave** sweeping across the grid,
- a **swirl**, a wave keyed to the angle around the centre so it appears to rotate.

| Setter | Default | Effect |
|---|---|---|
| `setSpacing(float)` | 26 | Distance between dots, in points (minimum 4). |
| `setMinRadius(float)` | 1 | Dot radius at the trough of a wave. |
| `setMaxRadius(float)` | 7 | Dot radius at the crest. Keep at or below half the spacing to avoid overlap. |
| `setSpeed(float)` | 1 | Animation speed multiplier. Negative runs the waves backwards. |
| `setIntensity(float)` | 1 | How strongly the waves drive size and hue, clamped to [0, 2]. |
| `setHueSpan(float)` | 1 | Fraction of the colour wheel swept, [0, 1]. 1 is the full rainbow. |
| `setWaveLength(float)` | 260 | Distance between wave crests. Smaller is busier, larger is slow and rolling. |
| `setSaturation(float)` | 0.85 | Colour saturation, [0, 1]. |
| `setBrightness(float)` | 1 | Colour brightness, [0, 1]. |
| `setAlpha(float)` | 0.85 | Dot opacity, [0, 1]. |

Each setter has a matching getter. Cost is one circle draw per dot, the same as `DotGridBackground`. The dots are
translucent, so a dark base color on the element (for example a dark theme) shows them off best.

### ConstellationBackground

Drifting dots that draw a fading line between any two that are closer than a link distance. The classic
particle-network effect.

```java
ConstellationBackground stars = new ConstellationBackground();
stars.setParticleCount(80); // default 80
stars.setSpeedRange(10f, 40f); // points/second (defaults 10..40)
stars.setParticleRadius(2f); // default 2
stars.setLinkDistance(120f); // default 120
stars.setLineThickness(1f); // default 1
stars.setParticleColor(new Color(1f, 1f, 1f, 0.8f));
stars.setLineColor(new Color(1f, 1f, 1f, 0.35f)); // alpha = opacity at zero distance

// RGB: the whole field cycles through the colour wheel. Same cycle length keeps dots and lines in sync.
stars.setParticleColor(new RainbowColor(6f, 0.8f, 1f, 0.9f));
stars.setLineColor(new RainbowColor(6f, 0.8f, 1f, 0.45f));

root.setBackground(stars);
```

Behavior:

- Particles **bounce** off the element's bounds (they do not wrap), and drawing is clipped to those bounds.
- Bounds are read live each frame, so resizing or moving the owner just works.
- Particles are seeded on the first update, once real bounds are known. `setParticleCount` and `setSpeedRange`
  invalidate them and reseed.
- Cost is **O(n²) per frame**, fine at the default 80 and up to a few hundred particles.

#### Mouse interaction

```java
stars.setMouseInteractionEnabled(true); // particles are pushed away from the cursor (default off)
stars.setRepelRadius(120f); // default 120
stars.setRepelStrength(600f); // points/second at zero distance; default 600
```

#### Obstacles

Particles can be nudged out of the way of containers, for example a draggable panel:

```java
stars.addObstacle(formPanel); // particles push out of the way as the panel moves
stars.removeObstacle(formPanel);
stars.clearObstacles();
List<Container> obstacles = stars.getObstacles(); // live list

stars.setObstacleRepelRadius(16f); // default 16, tight, so only the box's edge nudges them
stars.setObstacleRepelStrength(600f);
```

### ElectricNodeBackground

Glowing energy orbs that bounce around the element and shoot out crackling, forking arcs of lightning.

```java
ElectricNodeBackground electric = new ElectricNodeBackground();
electric.setNodeCount(3); // default 1
electric.setArcRate(5f); // average arcs per second, per node
root.setBackground(electric);
```

Where each arc goes depends on what is nearby:

- **Mouse**: with mouse interaction on and the cursor within the strike radius of a node, most arcs reach for
  the cursor.
- **Other nodes**: nodes within the link distance of each other often arc between the two.
- **Walls**: otherwise an arc shoots off in a random direction. An arc long enough to reach the edge strikes it, stays pinned
  there while the node moves, and throws a shower of sparks.

While an arc is alive it re-jitters its shape every ~45 ms and flickers, so it crackles instead of sitting there
as a static zig-zag. Nodes flash and swell when they fire or are struck.

```java
electric.setMouseInteractionEnabled(true); // default off
electric.setStrikeRadius(220f);

electric.discharge(); // fire a burst from every node at once, e.g. on a hit or a button press
```

| Setter | Default | Effect |
|---|---|---|
| `setNodeCount(int)` | 1 | Number of bouncing nodes. Reseeds. |
| `setNodeRadius(float)` | 9 | Radius of a node's bright core; the glow reaches about 5x this. |
| `setSpeedRange(float, float)` | 60, 140 | Node travel speed range, points/second. Reseeds. |
| `setArcRate(float)` | 5 | Average arcs per second, per node, at random intervals. 0 leaves only `discharge()`. |
| `setArcLengthRange(float, float)` | 70, 260 | Length range of free-direction arcs. |
| `setArcLifetime(float)` | 0.28 | Average seconds an arc is visible (each varies ±40%). |
| `setJaggedness(float)` | 1 | Zig-zag strength, [0, 3]. 0 draws straight beams. |
| `setBranchChance(float)` | 0.6 | Chance an arc forks into 1-3 thinner branches. |
| `setBoltThickness(float)` | 2 | Width of a bolt's bright core; the glow scales with it. |
| `setNodeLinkDistance(float)` | 260 | Nodes closer than this arc between each other. 0 disables. |
| `setGlowColor(Paint)` | light blue | Outer glow of bolts and nodes. Its alpha scales the whole glow. |
| `setCoreColor(Paint)` | near-white | Bright centre of bolts, nodes and sparks. |
| `setSparksEnabled(boolean)` | true | Sparks where an arc strikes a wall, node or the cursor. |
| `setMouseInteractionEnabled(boolean)` | false | Nodes arc toward the cursor when it is inside the element. |
| `setStrikeRadius(float)` | 220 | How close the cursor must be to a node to draw its arcs. |

Each setter has a matching getter. For a different mood, change the two colors, or pass a
[`RainbowColor`](colors-and-paints.md#rainbowcolor-rgb-cycling) for a cycling RGB glow. A gradient runs along each bolt
from where it starts to where it ends. For example, a purple glow
`new Color(0.7f, 0.35f, 1f, 1f)` with a pale pink core, or a red glow with a yellow-white core. The renderer has no blur, so
the glow is made of layered strokes (drawn with `Renderer.drawPolyline`) and stacked translucent circles. Cost is a
few polylines and circles per node per frame, independent of element size. A dark base color shows it off best.

### ImageBackground

A picture behind an element or the window: the static background.

```java
ImageBackground sky = new ImageBackground(new File("assets/sky.png"));
sky.setFit(ImageBackground.Fit.COVER);
window.setBackground(sky);
```

| Fit | The picture |
|---|---|
| `COVER` (default) | Fills the space, keeping its proportions and cutting off what doesn't fit |
| `CONTAIN` | Shows all of it, keeping its proportions, with the leftover space on two sides empty |
| `STRETCH` | Fills the space and shows all of it, squeezed or stretched to fit |
| `TILE` | Repeats at its own size from the top-left |

`setPath(String)` names a picture without reading it and `resolveFrom(Path base)` reads it from a folder. If it can't be
read the background draws nothing, `isMissing()` is true and the path is kept. Opacity and fades work as for the others.

### Fading a background

All built-in backgrounds extend `AbstractBackground`, which gives them their own enabled and opacity state, independent of the element
they are attached to:

```java
stars.fadeOut(1f); // fades the background only
stars.fadeIn(1f, () -> System.out.println("visible"));
stars.setOpacity(0.5f); // cancels any running fade
stars.setEnabled(false); // stop rendering and updating entirely
```

`fadeOut` leaves the background enabled (particles keep simulating while invisible). Use `setEnabled(false)`
to stop the work.

## Backgrounds in files

A layout file or a theme file can name a background and its settings, so an effect is data an editor changes. The
kinds and their ids are in `BackgroundKinds`:

| `type` | Class |
|---|---|
| `dotgrid` | `DotGridBackground` |
| `animateddots` | `AnimatedDotBackground` |
| `constellation` | `ConstellationBackground` |
| `electric` | `ElectricNodeBackground` |
| `image` | `ImageBackground` |

```json
{ "type": "constellation", "particleCount": 120, "linkDistance": 90, "particleColor": "#FFFFFFCC" }
{ "type": "image", "image": "assets/sky.png", "fit": "COVER", "opacity": 0.8 }
```

Each setting is named as the setter is, without `set`, except where noted in `BackgroundKinds.get(id).getSettings()`,
which lists every setting of a kind with its label, type and range, so a tool can build a field for each. A setting
the file leaves out starts as it always does. A color setting is any [paint](colors-and-paints.md#paints-in-files): a color, a gradient
or a [`RainbowColor`](colors-and-paints.md#rainbowcolor-rgb-cycling) written as `{ "type": "rainbow", ... }`. A picture's path is read from the project's folder.

```java
Background made = BackgroundKinds.read(json, projectFolder);   // json: the parsed object
Map<String, Object> settings = BackgroundKinds.write(made);    // null for a background of your own class
Background same = BackgroundKinds.apply(made, settings, projectFolder); // changes only what differs
```

`apply` is for a tool that shows an effect while it is edited: it changes the settings that differ on the background
that is there, so changing a color doesn't start a particle field over.

In a layout file the element's `effect` property holds these settings, and in a theme file `windowEffect` and
`containerEffect` do. A background of another class, such as one you wrote, is left out of a file.

## Writing your own background

Implement `Background` directly for a simple effect, or extend `AbstractBackground` to get enable/opacity/fade for
free.

```java
public class StripesBackground extends AbstractBackground {
    private float offset;

    @Override
    protected void updateEffect(float delta, float x, float y, float width, float height) {
        offset = (offset + 30f * delta) % 20f; // slide 30 points per second
    }

    @Override
    protected void renderEffect(Renderer renderer, float x, float y, float width, float height) {
        Color stripe = new Color(1f, 1f, 1f, 0.06f);
        for (float sx = x - 20f + offset; sx < x + width; sx += 20f) {
            renderer.drawLine(sx, y, sx + 40f, y + height, stripe, 6f);
        }
    }
}
```

`AbstractBackground.render` and `update` are `final`. They do the enable/opacity/fade bookkeeping and then call
your `renderEffect` / `updateEffect`. Bounds arrive as parameters on every call, so one instance can be reused on any
element.

The plain `Background` interface has `render(renderer, x, y, width, height)` and a default no-op
`update(delta, x, y, width, height)`. In `render`, x/y are the owner's position in the space it is being drawn in (relative
to its container). In `update` they are its position **in the window**, for effects that compare against window-space
things such as the mouse.

## Method reference

| Class | Members |
|---|---|
| `Background` | `render(...)`, `update(...)` (default no-op) |
| `AbstractBackground` | `isEnabled/setEnabled`, `getOpacity/setOpacity`, `fadeIn`, `fadeOut` (each with an optional `Runnable` callback), `renderEffect` and `updateEffect` (to implement) |
| `DotGridBackground` | `getSpacing/setSpacing`, `getDotRadius/setDotRadius`, `getDotColor/setDotColor` |
| `AnimatedDotBackground` | spacing, min/max radius, speed, intensity, hue span, wave length, saturation, brightness, alpha (all listed above) |
| `ElectricNodeBackground` | node count, radius, speed range, arc rate, arc length range, arc lifetime, jaggedness, branch chance, bolt thickness, node link distance, glow/core colors, sparks, mouse strike, `discharge()` (all listed above) |
| `ConstellationBackground` | particle count, speed range, radius, link distance, line thickness, colors, mouse repel, obstacles (all listed above) |
| `ImageBackground` | `getPath/setPath`, `resolveFrom(Path)`, `getImage/setImage`, `isMissing`, `getFit/setFit` |
| `BackgroundKinds` | `all()`, `get(id)`, `of(background)`, `read(json, base)`, `write(background)`, `apply(existing, json, base)`, `resolve(background, base)` |
| [`Element`](../core/element.md) | `getBackground()`, `setBackground(Background)`, `isBackgroundSet()`, `resetBackground()`, `applyThemeBackground(Background)`, `applyThemeEffect(Map, Path)` |
| [`Window`](../core/window.md#background-effects) | `getBackground()`, `setBackground(Background)` |
