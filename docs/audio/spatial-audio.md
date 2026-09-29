# 3D audio

[Documentation](../README.md) › [Audio](README.md) › 3D audio

A sound with a position in the world is heard from that direction and gets quieter with distance, relative to a
**listener** that stands for the player. It works for 3D games and, using the plane `z = 0`, for 2D ones.

```java
Audio.listener().setPosition(player.x, player.y);   // every frame
Audio.playAt("sfx/growl.ogg", enemy.x, enemy.y);    // heard from the enemy's direction
```

## The listener

`Audio.listener()` returns the one listener. Move it each frame to follow the camera or player, for example from an
`onUpdate` hook.

```java
AudioListener ears = Audio.listener();
ears.setPosition(camera.x, camera.y, camera.z);
ears.setOrientation(fx, fy, fz,   // the direction it faces
                    0, 1, 0);      // which way is up
ears.setVelocity(vx, vy, vz);      // only for the Doppler effect
```

The defaults are the OpenGL camera convention: the listener is at the origin facing down **-z**, with **+y up**
and **+x to the right**. Values set before audio starts are applied when it does.

| Method | Description |
|---|---|
| `setPosition(x, y)` / `setPosition(x, y, z)` | Where it is |
| `setOrientation(fx, fy, fz, ux, uy, uz)` | Facing direction and up direction. They must not be parallel |
| `setVelocity(x, y, z)` | Speed in world units per second, for Doppler |
| `getX()`, `getY()`, `getZ()` | Current position |

## Placing sounds

```java
Sound.load("sfx/growl.ogg").playAt(x, y, z);   // Sound.playAt / loopAt
Audio.playAt("sfx/growl.ogg", x, y);           // cached, 2D
Audio.play("sfx/steps.ogg").position(x, y, z); // or on any Playback
```

`playAt` and `loopAt` start the sound already in place. Calling `.position(...)` on a playback that has already
started works too, but the sound plays for a moment at the listener before it moves, which can be audible on short
sounds. Prefer `playAt`.

A positioned sound can keep moving: call `playback.position(x, y, z)` again each frame.

```java
Playback engine = Sound.load("sfx/engine.ogg").loopAt(car.x, car.y, car.z);
Scheduler.every(1f / 30f, () -> engine.position(car.x, car.y, car.z));
```

A playback is either **positioned** or **panned**, never both. `pan` on a positioned playback removes its position.
Use `pan` for interface sounds and `position` for things in the world.

## Distance falloff

By default a positioned sound is at full volume at 1 unit from the listener and falls off like real sound (1/distance).
`OpenAL` has no idea what your units mean, so tune it to your world:

```java
Playback p = Sound.load("sfx/campfire.ogg").loopAt(x, y, z)
        .attenuation(2f, 30f, 1f);   // full volume within 2 units, silent by 30
```

| `attenuation` argument | Meaning |
|---|---|
| `referenceDistance` | Full volume at and inside this distance. Default 1 |
| `maxDistance` | The distance where fading stops (and, for the linear models, reaches silence). Default unlimited |
| `rolloff` | How fast it fades: 1 is the natural rate, 0 disables fading, 2 is twice as fast. Default 1 |

`Audio.setDistanceModel` picks the curve for the whole world:

| `DistanceModel` | Behaviour |
|---|---|
| `INVERSE_CLAMPED` | Realistic falloff. **Default** |
| `LINEAR_CLAMPED` | A straight line from full volume at the reference distance to silence at the max distance. The easiest to tune |
| `EXPONENT_CLAMPED` | A power curve set by the rolloff |
| `INVERSE`, `LINEAR`, `EXPONENT` | The same without clamping (louder than full volume closer than the reference distance) |
| `NONE` | No fading, direction only |

`Audio.setDefaultAttenuation(ref, max, rolloff)` sets the attenuation every positioned playback starts with, so it
needn't be set on each one. Playbacks already playing keep theirs.

## 2D games

Units matter: with the default falloff and positions in pixels, a sound 300 px away is at 1/300 of its volume, which
is nearly silent. And with the listener on the plane, a sound a few pixels to the right comes entirely out of the
right speaker. `Audio.hearIn2D` sets up both in one call:

```java
Audio.hearIn2D(150f, 1500f);                                  // once: full volume within 150 units, silent at 1500

Audio.listener().setPosition(camera.getX(), camera.getY());   // each frame: the ears follow the view
explosion.playAt(x, y);                                       // where it happens
hit.playAt(x, y, 0f, 0.5f);                                   // at half volume
```

It does three things:

- **Linear falloff.** The distance model becomes `LINEAR_CLAMPED` and the default attenuation is set, so a sound is at
  full volume within the first distance of the listener and fades evenly to silence at the second.
- **Gentle panning.** The listener hovers half the silent distance in front of the plane, facing it with y down the
  screen, so a sound a little to one side comes a little from that side, and one far to the side mostly from it.
- **Far sounds are skipped.** A one-shot started beyond the silent distance doesn't play at all, costs no audio source,
  and doesn't count towards the sound's [`minGap`](sound.md#variety-and-limits). Loops still start, to be heard when
  the listener comes near.

Distances are in whatever units the positions are in: world units if you pass world positions, pixels if pixels.

## Doppler

Moving sources change pitch as they pass the listener. Give sources and the listener a velocity, and the effect is
automatic:

```java
Playback siren = Sound.load("sfx/siren.ogg").loopAt(x, y, z);
siren.velocity(vx, vy, vz);
```

| Method | Description |
|---|---|
| `Audio.setDopplerFactor(f)` | Scales the effect. 1 is realistic (the default), 0 turns it off |
| `Audio.setSpeedOfSound(v)` | The speed of sound in your world units per second. The default, 343.3, suits metres |

It only acts when a velocity is set, so it costs nothing otherwise. In a pixel-based game either leave velocities
unset, set the factor to 0, or set the speed of sound to a pixel value such as 34330.

## Gotchas

- **Mono files position best.** A stereo file is placed as a stereo pair around the point.
- **Only the listener and positioned playbacks use these units.** Panned and flat playbacks never fade with distance.
- **Positions are not tracked for you.** A playback stays where you last put it. Update it as the thing moves.
- **`hearIn2D` turns the listener** to face the plane with y down the screen. Calling `setOrientation` afterwards
  undoes that, so a 2D game shouldn't need to.
