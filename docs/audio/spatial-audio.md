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

## Cones

A sound that faces somewhere, such as a speaker, a siren or a person shouting, is louder in front than behind. Give
the playback a direction and a cone:

```java
Playback horn = Sound.load("sfx/horn.ogg").loopAt(x, y, z)
        .direction(0, 0, -1)       // which way it faces
        .cone(60f, 200f, 0.2f);    // full volume inside 60 degrees, 20% outside 200, fading between
```

| Method | Meaning |
|---|---|
| `direction(x, y, z)` / `direction(x, y)` | The way it faces, as a vector. Its length doesn't matter. The zero vector, the default, sounds the same all round |
| `cone(inner, outer, outerGain)` | Whole-cone widths in degrees (360 is all round) and the volume outside the outer cone, 0 to 1 |

The cone does nothing until a direction is set, and only a positioned playback has one. Update the direction as the
thing turns, as you do the position.

## Occlusion

`playback.occlusion(amount)` muffles a sound as if something solid stood between it and the listener: 0 is a clear
path, 1 is behind a thick wall, where it is quiet and dull.

```java
boolean blocked = world.raycastBlocked(listenerPos, growlPos);   // your own line-of-sight test
growl.occlusion(blocked ? 0.8f : 0f);
```

The engine has no idea what your walls are, so you decide the amount, from a ray test or from the room the two are
in, and set it when it changes. It cuts the volume and the high frequencies of the direct sound, and dulls the
sound's [reverb](#reverb) less than that, since a room still rings behind a wall.

## Reverb

A world reverb gives every sound the echo of a room:

```java
Audio.setReverb(Reverb.cave());        // from now on, sounds in the world ring like a cave
Audio.setReverb(null);                 // dry again
```

| Preset | Sound |
|---|---|
| `Reverb.room()` | A medium room, the effect's own default |
| `Reverb.bathroom()` | Hard and bright, ringing quickly |
| `Reverb.hall()` | A big hall with a long, smooth tail |
| `Reverb.cave()` | Stone all round: a long, dark echo |
| `Reverb.outdoors()` | Open space: a thin, quiet tail |
| `Reverb.padded()` | A dead room: the tail dies almost at once |

Adjust a preset with its setters, which chain: `Reverb.cave().decayTime(5f).gain(0.5f)`. They are `density`,
`diffusion`, `gain`, `gainHF`, `decayTime`, `decayHFRatio`, `reflections(gain, delay)`, `late(gain, delay)`,
`airAbsorption` and `roomRolloff`, each clamped to the range OpenAL allows. A `Reverb` is a plain value: changing one
after it was set does nothing until it is set again.

There is **one** reverb at a time for the whole world, so moving between rooms means calling `setReverb` with another
preset. Each playback chooses how much of itself goes in:

```java
growl.reverb(0.5f);   // half as wet
ui.reverb(0f);        // dry
```

By default a positioned playback sends everything and a flat or panned one (interface sounds, music) sends nothing, so
the menu doesn't echo. `Audio.isReverbSupported()` says whether the sound system has the effects extension; OpenAL
Soft, which the engine ships, does. Without it `setReverb` and `occlusion` do nothing.

## Gotchas

- **Mono files position best.** A stereo file is placed as a stereo pair around the point.
- **Only the listener and positioned playbacks use these units.** Panned and flat playbacks never fade with distance.
- **Positions are not tracked for you.** A playback stays where you last put it. Update it as the thing moves.
- **Occlusion is not worked out for you.** Nothing in the engine knows where your walls are; `occlusion` only applies
  the amount you give it.
- **One reverb for the world.** It is not tied to the listener's position. Switch it as the player changes room.
- **`hearIn2D` turns the listener** to face the plane with y down the screen. Calling `setOrientation` afterwards
  undoes that, so a 2D game shouldn't need to.
