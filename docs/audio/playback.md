# Playback

[Documentation](../README.md) › [Audio](README.md) › Playback

Every `play`, `loop` and `Audio.play` call returns a `Playback`: one running instance of a [`Sound`](sound.md) or
[`Music`](music.md). Use it to adjust or stop that instance. For fire-and-forget effects, ignore it.

```java
Playback engine = Sound.load("sfx/engine.ogg").loop();
engine.volume(0.6f);
// as the player accelerates
engine.pitch(1f + speed / 100f);
// when they stop
engine.stop(0.5f);
```

Setters return the playback, so they chain:

```java
Audio.play("sfx/explosion.ogg").volume(0.8f).pitch(0.7f).pan(-0.5f);
```

## Methods

| Method | Description |
|---|---|
| `volume(float)` / `getVolume()` | This playback's own volume, 0 is silent and 1 is full. Multiplied by its channel and the master volume |
| `pitch(float)` | Speed and pitch together, 1 is normal, 2 is an octave up. Clamped to 0.1 - 4 |
| `pan(float)` | -1 (left) to 1 (right), 0 is centered. Mono and stereo |
| `position(x, y)` / `position(x, y, z)` | Place it in the world. See [3D audio](spatial-audio.md). Replaces `pan` |
| `velocity(x, y, z)` | How fast it moves, for the Doppler effect |
| `attenuation(ref, max, rolloff)` | How it fades with distance |
| `isPositional()` | Whether it has a world position |
| `loop(boolean)` / `isLooping()` | Repeat forever |
| `pause()` / `resume()` | Keep the position |
| `stop()` | Stop immediately |
| `stop(float seconds)` | Fade to silence over `seconds`, then stop |
| `fadeTo(float volume, float seconds)` | Fade this playback's volume |
| `onFinished(Runnable)` | Runs on the render thread when the playback ends, for any reason. One listener: calling it again replaces it |
| `isPlaying()` | Audible: not paused and not finished |
| `isPaused()`, `isFinished()` | State |
| `getChannel()` | The [`AudioChannel`](README.md) it plays on |

## Panning

Pan is the simple 2-speaker version. For a sound with a place in the world, use [`position`](spatial-audio.md) instead; a
playback is either panned or positioned, and calling `pan` on a positioned playback removes the position.

`pan` works on mono and stereo sounds. A mono sound is placed between the speakers. A stereo sound keeps its own
left/right image and the whole image is moved toward the chosen side, so at -1 both channels come out of the left
speaker, and loudness stays about the same on the way there.

```java
Audio.play("sfx/footstep.ogg").pan(enemyX / screenWidth * 2f - 1f); // pan by screen position
```

## Finished playbacks are safe

A playback is *finished* once it ends, is stopped, or could not start (no audio device, too many sounds playing).
A finished playback ignores every call, so you never need to check before calling a method, and `isFinished()` is
the only way to tell.

```java
Playback hum = null;
toggle.onAction(() -> {
    if (hum != null && hum.isPlaying()) hum.stop(0.3f);
    else hum = Sound.load("sfx/hum.ogg").loop();
});
```

`onFinished` on an already-finished playback runs its callback on the next frame.

## Chaining sounds

```java
Audio.play("voice/intro.ogg", AudioChannel.VOICE)
     .onFinished(() -> Audio.play("voice/reply.ogg", AudioChannel.VOICE));
```

## Gotchas

- **`volume` cancels a running fade.** Setting the volume by hand while `fadeTo` or `stop(seconds)` is running stops that fade
  (a `stop(seconds)` fade-out then no longer ends the playback).
- **Fades are stepped about every 15 ms,** which is smooth for volume but not sample-accurate.
- **A fade-in cannot be requested on an already running playback without a brief full-volume moment.** Use
  `Music.play(fadeInSeconds)` or `Music.loop(fadeInSeconds)`, which start silent.
