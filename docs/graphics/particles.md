# Particles

[Documentation](../README.md) › [Graphics](README.md)

Sparks, smoke, dust, debris: many short-lived pictures that fly, fade and disappear. A `ParticleSystem`
(`me.piitex.engine.graphics`) moves them, reuses them once they die, and draws them all through a
[`SpriteBatch`](sprites.md), so thousands at a time are cheap.

```java nocompile
public class Level extends Scene {
    private final ParticleSystem particles = new ParticleSystem(5000);
    private final ParticleEffect sparks = new ParticleEffect(atlas.get("spark"))
            .speed(200f, 600f).lifetime(0.3f, 0.6f).size(8f, 14f).endScale(0.2f)
            .drag(0.9f).alignToVelocity(1f / 400f).color(Color.ORANGE);

    @Override
    protected void fixedUpdate(float step) {
        if (enemy.justDied) particles.burst(sparks, enemy.x, enemy.y, 40);
        particles.update(step);
    }

    @Override
    protected void render(Renderer renderer, float alpha) {
        camera.begin(renderer);
        particles.draw(renderer, camera.getVisibleBounds());
        camera.end(renderer);
    }
}
```

Particles are in whatever units they are drawn in: world units under a [`Camera`](camera.md), window points
without one.

## Recipes: ParticleEffect

A `ParticleEffect` is a recipe for a kind of particle: its picture and the range each of its values is picked from.
Set one up once and burst it wherever something happens. Every particle gets its own random pick from each range,
and a range with the same number at both ends always gives that number.

| Setter | What it picks | Default |
|---|---|---|
| `region(region)` | The picture | the one passed to the constructor |
| `speed(min, max)` | How fast it leaves, in units a second | 0: it stays where it appears |
| `lifetime(min, max)` | How many seconds it lives | 1 |
| `size(min, max)` | Its width when it appears. The height follows the picture's shape | the picture's own width |
| `endScale(scale)` | Its width when it dies, as a multiple of its starting width: 0.2 shrinks away, 4 spreads out | 1 |
| `rotation(min, max)` | How far it starts turned, in degrees: `rotation(0, 360)` for any way | 0 |
| `spin(min, max)` | How fast it turns, in degrees a second | 0 |
| `drag(drag)` | The share of its speed it loses each second, from 0 to 1 | 0 |
| `gravity(x, y)` | How fast its velocity changes, in units a second every second: `gravity(0, 400)` falls | none |
| `color(color)`, `color(start, end)` | The color the picture is multiplied with, or two it blends between over its life | white |
| `fade(easing)` | How it fades: its opacity is 1 minus the curve applied to the share of its life gone. `null` doesn't fade | `Easing.LINEAR` |
| `alignToVelocity(stretch)` | Points it the way it flies, and stretches it `1 + speed * stretch` times longer. `noAlign()` turns it off | off |
| `layer(layer)` | The layer it is drawn on | 0 |

Setters chain and can be called at any time. Each burst uses the recipe as it is then, so one recipe can be changed
between bursts.

| Method | Emits |
|---|---|
| `burst(effect, x, y, count)` | `count` particles flying out of (x, y) in every direction |
| `cone(effect, x, y, count, directionDegrees, spreadDegrees)` | `count` particles in a cone around a direction (0 is right, 90 down), `spreadDegrees` wide in all: sparks spraying back from a hit |
| `burst(effect, x, y, count, color)`, `cone(..., color)` | The same in another color, such as the color of whatever was hit |

## One particle at a time

`emit(region, x, y)` adds one particle, still and with every value at its default, and returns it so its setters
can be chained:

```java nocompile
// A puff of smoke behind a rocket, each frame
particles.emit(smoke, rocket.x, rocket.y)
        .velocity(-rocket.vx * 0.2f, -30f)
        .lifetime(1.2f).size(6f, 24f)
        .color(Color.WHITE, Color.GRAY)
        .fade(Easing.EASE_IN);
```

The setters are the recipe's, with single values instead of ranges: `velocity`, `gravity`, `drag`, `lifetime`,
`size(size)` or `size(start, end)`, `rotation`, `spin`, `color`, `fade`, `alignToVelocity` and `layer`. `kill()` ends
a particle's life early. The getters read where it is and how old it is.

The system reuses a particle once it dies, so don't keep a reference to one after the frame it was emitted in.

## Updating and drawing

| Method | What it does |
|---|---|
| `update(delta)` | Moves every particle on and reuses the ones whose life is over. Call it from `fixedUpdate` or `update`; particles run on whichever clock you use |
| `draw(renderer)`, `draw(renderer, visible)` | Draws them now, leaving out those outside `visible` when it isn't null |
| `addTo(batch)` | Adds them to a `SpriteBatch` of your own, to be drawn among your sprites by [layer](sprites.md) |
| `addTo(batch, layer)` | Adds only the particles on `layer`, for particles drawn under and over things that are not sprites |
| `clear()` | Removes them all, such as when a level restarts |
| `getCount()`, `getPooledCount()`, `getMaxParticles()` | How many are alive, how many are waiting to be reused, and the limit |

Newer particles draw over older ones on the same layer. Particles that share a texture atlas are drawn in a single
call, so put their pictures on one atlas.

Drawing under and over something that isn't a sprite, such as characters drawn by a shader, takes two layers:

```java nocompile
particles.emit(dust, x, y).layer(0);  // under the characters
particles.burst(sparks, x, y, 20);   // the sparks recipe has layer(1): over them

// render
particles.addTo(batch, 0);
batch.draw(renderer, visible);
drawCharacters(renderer);
particles.addTo(batch, 1);
batch.draw(renderer, visible);
```

## Things to know

- **The limit.** At most `maxParticles` are alive at once. Beyond it, `emit` returns a particle that is never drawn,
  so chained setters still work, and effects are simply thinner until some die.
- **Random numbers.** `new ParticleSystem(max, random)` picks the recipes' random values with your `Random`, such as
  a seeded one, so the same seed gives the same bursts. The plain constructor makes its own.
- **Not thread-safe.** Use it on the render thread, in scene hooks.
- A particle with a null region still moves and lives out its life, but draws nothing.
