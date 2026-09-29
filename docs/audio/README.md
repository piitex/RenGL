# Audio

[Documentation](../README.md) › Audio

RenGL plays sound through OpenAL (LWJGL). Everything is in `me.piitex.engine.audio` and needs no setup: the audio
device opens the first time something plays, and the engine closes it when the window closes.

```java
import me.piitex.engine.audio.*;

Audio.play("sfx/click.ogg");                  // a one-shot sound effect
Audio.playMusic("music/theme.ogg");           // looping background music
Audio.setVolume(AudioChannel.MUSIC, 0.5f);    // a volume slider
```

| Page | What it covers |
|---|---|
| [Sound](sound.md) | Short effects held in memory, played instantly and many times at once |
| [Music](music.md) | Long tracks streamed from disk, looping and crossfades |
| [Playback](playback.md) | The handle returned by every `play`: volume, pitch, pan, pause, fades, callbacks |
| [3D audio](spatial-audio.md) | The listener, positioning sounds in the world, distance falloff, Doppler |

## The four things to know

**1. `Audio` is the shortcut, `Sound` and `Music` are the real objects.** `Audio.play(path)` loads the file on first
use, caches it by path and plays it. Hold a [`Sound`](sound.md) or [`Music`](music.md) yourself when you want to
control its lifetime or channel.

**2. Every play call returns a [`Playback`](playback.md).** Keep it to change or stop that one instance, or ignore it
for fire-and-forget effects. Setters chain: `Audio.play("boom.ogg").volume(0.8f).pitch(0.7f)`.

**3. Volume is layered.** What you hear is the playback's own volume × its channel's volume × the master volume.

| Channel | Used for |
|---|---|
| `AudioChannel.SFX` | Effects. The default for a `Sound` |
| `AudioChannel.MUSIC` | Every `Music` track |
| `AudioChannel.VOICE` | Spoken dialogue |
| `AudioChannel.UI` | Clicks and hovers |

```java
Audio.setMasterVolume(0.8f);
Audio.setVolume(AudioChannel.MUSIC, 0.4f);   // applies to music that is already playing
Audio.setMuted(true);                        // silences everything, remembers the volumes
```

**4. Audio never throws because of the machine.** If OpenAL cannot start (no output device, missing driver),
a warning is logged once, `Audio.isAvailable()` returns `false`, and every playback is silent. Your game keeps
running. Bad *files* do throw (see below).

## Files

| | |
|---|---|
| Formats | `.ogg` (Vorbis), `.opus`, `.flac`, `.mp3` and `.wav` (8, 16, 24 and 32-bit PCM, and 32-bit float). Mono or stereo (Opus with more channels is mixed down to stereo) |
| Lookup | The path is tried on disk first, then on the classpath. `Sound.load` and `Music.load` also accept an [`AssetManager`](../reference/utilities.md#assetmanager) so audio can come from a `.pak` |
| Detection | By file content, not extension |
| Errors | `UncheckedIOException` if the file is missing, `IllegalArgumentException` if the format is unsupported or corrupt |

| Format | Best for | Decoded by |
|---|---|---|
| Ogg Vorbis (`.ogg`) | Music and effects. The safe default | stb_vorbis |
| Opus (`.opus`, or Opus in `.ogg`) | Voice and music at small sizes. Always plays at 48 kHz | libopus (through LWJGL) |
| FLAC (`.flac`) | Lossless masters. 8 to 24-bit, converted to 16-bit | RenGL's own decoder |
| MP3 (`.mp3`) | Files you already have | [JLayer](https://github.com/umjammer/jlayer) (LGPL) |
| WAV (`.wav`) | Tiny effects. Uncompressed | RenGL |

Every format here is royalty-free or out of patent. **AAC (`.m4a`, `.aac`) is deliberately not supported**, because
its patents make it a licensing risk for a commercial product. Convert to Ogg Vorbis or Opus, for example
`ffmpeg -i song.m4a -c:a libvorbis -q:a 5 song.ogg`.

Ogg Vorbis, Opus and FLAC seek exactly, which matters for [loop points](music.md#loop-points). MP3 loops are not
sample-tight.

## Playing UI sounds

Put a `Sound` in a field and play it from any callback:

```java
Sound click = Sound.load("sfx/click.ogg").channel(AudioChannel.UI);

ButtonOverlay play = new ButtonOverlay("Play", 120, 32);
play.onAction(() -> {
    click.play();
    startGame();
});
```

## Threading

Every method is safe to call from any thread. `onFinished` callbacks run on the render thread, like every other
callback in the engine, so they may touch the UI. Fades and music streaming are advanced by a daemon thread
("Audio-Thread") that wakes about every 15 ms.

## Shutdown

`Engine.start` calls `Audio.shutdown()` when the window closes. It stops everything, frees cached sounds and closes
the device. Only call it yourself if you use audio without an `Engine`. Audio can be used again afterwards.

## Try it

[`AudioDemo`](../../src/main/java/me/piitex/examples/demo/AudioDemo.java) needs no audio files (it generates tones).
Pass an `.ogg`, `.mp3` or `.wav` path as the first argument to try music crossfades.

## Audio reference

| Method | Description |
|---|---|
| `Audio.play(path)` / `play(path, volume)` / `play(path, channel)` | Play a cached sound effect once. Returns a [`Playback`](playback.md) |
| `Audio.playAt(path, x, y)` / `playAt(path, x, y, z)` | Play at a position in the world. See [3D audio](spatial-audio.md) |
| `Audio.listener()` | The [listener](spatial-audio.md#the-listener) |
| `Audio.hearIn2D(fullVolumeDistance, silentDistance)` | Set up positioned sounds for a [2D game](spatial-audio.md#2d-games) in one call |
| `Audio.setDistanceModel(model)` / `setDefaultAttenuation(ref, max, rolloff)` / `setDopplerFactor(f)` / `setSpeedOfSound(v)` | World-wide [3D settings](spatial-audio.md) |
| `Audio.playMusic(path)` / `playMusic(path, fadeSeconds)` | Loop a track, crossfading from the current one. Does nothing if that track is already playing |
| `Audio.stopMusic()` / `stopMusic(fadeSeconds)` | Fade out and stop the background music |
| `Audio.getMusic()` | The current music `Playback`, or `null` |
| `Audio.sound(path)` / `Audio.music(path)` | The cached [`Sound`](sound.md) or [`Music`](music.md) for a path, for example to set loop points |
| `Audio.preload(paths...)` | Decode sounds now so their first play has no delay |
| `Audio.unload(path)` | Stop and free a cached sound or track |
| `Audio.setVolume(channel, v)` / `getVolume(channel)` | Channel volume, 0 to 1 |
| `Audio.setMasterVolume(v)` / `getMasterVolume()` | Master volume, 0 to 1 |
| `Audio.setMuted(bool)` / `isMuted()` | Mute without losing volumes |
| `Audio.pauseAll()` / `resumeAll()` / `stopAll()` | Act on everything playing |
| `Audio.isAvailable()` | Whether the audio device opened |
| `Audio.shutdown()` | Stop everything and close the device |
