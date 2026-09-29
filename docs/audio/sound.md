# Sound

[Documentation](../README.md) › [Audio](README.md) › Sound

A `Sound` is a short clip that is decoded once and kept in memory. It starts with no delay and can play many times
at once, so use it for clicks, footsteps, explosions and voice lines. For anything long, use [`Music`](music.md).

```java
Sound explosion = Sound.load("sfx/explosion.ogg");
explosion.play();                 // full volume
explosion.play(0.5f);             // half volume
explosion.play().pitch(0.6f);     // lower and slower, chained on the returned Playback
```

Playing the same `Sound` again does not stop the earlier playback. They overlap.

## Loading

| Method | Description |
|---|---|
| `Sound.load(String path)` | From a file or classpath resource |
| `Sound.load(AssetManager, String name)` | Through an [`AssetManager`](../reference/utilities.md#assetmanager), so it can come from a `.pak` |
| `Sound.tone(float hz, float seconds)` | A generated sine tone. Useful as a placeholder while you wait for real assets |
| `Sound.fromSamples(name, short[] samples, channels, sampleRate)` | Samples the game made itself, such as synthesized effects: 16-bit, 1 or 2 channels, interleaved |

Loading decodes the whole file, so do it up front (a loading screen, or [`Audio.preload`](README.md)) and not in the
middle of gameplay. It can be done on a worker thread with [`Scheduler.async`](../core/scheduler.md).

## Playing

| Method | Description |
|---|---|
| `play()` | Once, at full volume |
| `play(float volume)` | Once, at the given volume |
| `loop()` | Repeats until you stop the returned [`Playback`](playback.md) |
| `playAt(x, y)` / `playAt(x, y, z)` / `playAt(x, y, z, volume)` | Plays once at a position in the world |
| `loopAt(x, y, z)` | Repeats at a position in the world |
| `channel(AudioChannel)` | Sets the channel it plays on (default `SFX`). Returns the sound, so it chains onto `load` |
| `getDuration()` | Length in seconds |
| `close()` | Stops every playback of it and frees its memory |

```java
Sound click = Sound.load("sfx/click.ogg").channel(AudioChannel.UI);
Sound rain = Sound.load("sfx/rain.ogg");

Playback storm = rain.loop();
// ...
storm.stop(2f); // fade out over two seconds
```

## Placing it in the world

`playAt` starts a sound already positioned, so it is heard from that direction and fades with distance. See
[3D audio](spatial-audio.md).

```java
Sound growl = Sound.load("sfx/growl.ogg");
growl.playAt(enemy.x, enemy.y);            // 2D game
growl.playAt(enemy.x, enemy.y, enemy.z);   // 3D game
Playback fire = Sound.load("sfx/fire.ogg").loopAt(camp.x, camp.y, camp.z);
```

Use mono files for positioned sounds where you can: a stereo file is placed as one point too, but it keeps its own
left/right spread. For a 2D game, [`Audio.hearIn2D`](spatial-audio.md#2d-games) sets the hearing up in one call.

## Variety and limits

A sound played often, such as a gunshot or a hit, sounds mechanical when every copy is identical, and turns into a
roar when fifty play in the same frame. Set these once, after loading:

```java
Sound hit = Sound.load("sfx/hit.ogg")
        .pitchVariation(0.08f)   // each play up to 8% higher or lower, at random
        .minGap(0.05f)           // a play less than 0.05 s after the last one is skipped
        .maxPlaying(4);          // and so is one while 4 copies are still sounding
```

| Method | Default | Effect |
|---|---|---|
| `pitchVariation(amount)` | 0 | Each play gets a pitch from `1 - amount` to `1 + amount`. The speed changes with it, so higher copies are a little shorter |
| `minGap(seconds)` | 0 | A play that comes sooner than this after the last one that played is skipped, in real time, so it holds in slow motion |
| `maxPlaying(count)` | no limit | A play is skipped while this many copies are still sounding |
| `getPlayingCount()` | | How many copies are sounding now |

A skipped play returns a `Playback` that is already [finished](playback.md#finished-playbacks-are-safe), so chained
calls on it are harmless. Loops are never skipped or varied, but they count towards `maxPlaying` while they play.

The limits belong to the sound, so every play of it shares them. When some plays must never be skipped, such as the
player's own gun among a crowd of enemies firing the same sound, make a second `Sound` from the same file or samples
and limit only that one.

## Loop points

`loop()` repeats the whole clip by default. `loopPoints(start, end)` (seconds) changes what repeats: the sound plays
from the beginning, and each time it reaches `end` it jumps back to `start`. A start above zero gives an intro that
plays once before the looped part. `loopFrom(start)` is the same with the end at the end of the file.

```java
Sound engine = Sound.load("sfx/engine.ogg").loopPoints(0.4f, 2.1f); // rev-up intro, then a steady hum
Playback hum = engine.loop();
```

Set loop points right after loading, before the sound is first played, because OpenAL does not allow changing a
buffer that a playing sound is using. They only affect `loop()`, never a single `play()`. They use OpenAL Soft's
loop-points extension, which the engine's bundled OpenAL provides. On a build without it a warning is logged and the
whole clip loops. An empty or out-of-range region throws `IllegalArgumentException`.

## Cached shortcuts

If you do not need to hold the object, `Audio.play("sfx/click.ogg")` loads, caches and plays in one call. A sound
you got from `Audio.sound(path)` belongs to the cache: release it with `Audio.unload(path)`, not `close()`.

## Gotchas

- **Close what you load.** A `Sound` you created with `Sound.load` is yours: call `close()` when done, or it lives
  until the engine shuts down. Playing a closed sound throws `IllegalStateException`.
- **Source limit.** OpenAL only allows a limited number of simultaneous sounds (typically 256, fewer on some
  platforms). Past that a play is dropped, a warning is logged, and the returned `Playback` is already
  finished.
- **MP3 files carry a little silence at the start** (the encoder's delay), so loop points in an MP3 can drift by a few
  milliseconds. Use `.ogg` or `.wav` for sample-tight loops.
