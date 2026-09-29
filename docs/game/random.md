# Random numbers

[Documentation](../README.md) › [Game](README.md)

`Rng` (`me.piitex.engine.math`) is a `java.util.Random` with the picks games make all the time.

```java nocompile
Rng rng = new Rng(seed);                     // the same seed gives the same game

float speed = rng.range(200f, 400f);         // instead of 200f + random.nextFloat() * 200f
int damage = rng.range(3, 8);                // 3 to 8, both included
float lifetime = rng.vary(1.5f, 0.2f);       // 1.5 give or take 20%: 1.2 to 1.8
if (rng.chance(0.1f)) dropLoot();            // one time in ten
Enemy target = rng.pick(enemies);
int tier = rng.weighted(70f, 25f, 5f);       // 0 seven times in ten, 1 a quarter of the time, 2 rarely
Vec2 spawn = center.add(rng.inCircle(300f)); // anywhere in the circle, evenly
```

| Method | Picks |
|---|---|
| `range(min, max)` | A `float` from `min` up to `max`. Equal ends give that number |
| `range(int min, int max)` | A whole number from `min` to `max`, **both included**: `range(1, 6)` rolls a die. Careful: `range(0, 360)` is whole degrees, `range(0f, 360f)` isn't |
| `vary(value, fraction)` | `value` give or take `fraction` of itself |
| `chance(probability)` | True with that probability. 0 is never, 1 always |
| `sign()` | 1 or -1 |
| `angle()` | Degrees from 0 up to 360 |
| `direction()` | A direction of length 1, every way equally likely |
| `inCircle(radius)`, `onCircle(radius)` | A point in the circle around (0, 0), evenly spread over its area, or on its outline |
| `gaussian(mean, deviation)` | A bell curve: two thirds of picks land within `deviation` of `mean` |
| `pick(a, b, c)`, `pick(list)` | One of them, equally likely |
| `weighted(weights...)` | The index of one weight, as likely as its share of the total. Weights of 0 or less are never picked |
| `shuffle(list)` | Shuffles the list in place |

`inCircle` spreads its points evenly. Picking a random angle and a random distance up to the radius bunches them in
the middle, which is a common mistake.

## Seeds and replays

An `Rng` made with a seed gives the same numbers in the same order every run. Keep one for everything that must replay
the same way, such as a level generator or a whole simulation, and draw from it in the same order each time.
Things that don't matter to the game, such as the sparkle on a pickup, can use a second `Rng` so adding one doesn't
shift everything else.

`range(min, max)` draws exactly what `min + nextFloat() * (max - min)` does, so switching a seeded game over to it
keeps its numbers, apart from float rounding in the last digit when `max` is written as `min + something`.

Because it is a `Random`, an `Rng` can be passed anywhere one is taken, such as a
[`ParticleSystem`](../graphics/particles.md) that picks its bursts' values from your seed. Like `Random`, it is safe to
share between threads, but a game that must replay should use it from one.
