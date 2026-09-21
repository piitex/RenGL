# Backgrounds

[Documentation](../README.md) › [Styling](README.md)

A **`Background`** is a decorative, optionally animated effect painted **behind an element's own content**. Attach
one with `Element.setBackground`:

```java
mainContainer.setBackground(new ConstellationBackground());
formPanel.setBackground(new DotGridBackground());
root.setBackground(new AnimatedDotBackground());
```

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

A tiled grid of small dots, the "infinite canvas" look of node editors and whiteboards. Fully static.

```java
DotGridBackground grid = new DotGridBackground();
grid.setSpacing(24f); // distance between dots (default 24)
grid.setDotRadius(1.5f); // default 1.5
grid.setDotColor(new Color(0.5f, 0.5f, 0.5f, 0.35f)); // default
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
| `ConstellationBackground` | particle count, speed range, radius, link distance, line thickness, colors, mouse repel, obstacles (all listed above) |
| [`Element`](../core/element.md) | `getBackground()`, `setBackground(Background)` |
