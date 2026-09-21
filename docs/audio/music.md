# Music

[Documentation](../README.md) › [Audio](README.md) › Music

A `Music` track is decoded a little at a time while it plays, so a ten-minute `.ogg` only costs its compressed size
in memory and starts without a long decode. Music always plays on the `MUSIC` [channel](README.md).

## Background music

For "the game's current music", use `Audio.playMusic`. It loops, caches, and crossfades:

```java
Audio.playMusic("music/menu.ogg");
// later, on a scene change: menu fades out over 1 s while the battle theme fades in
Audio.playMusic("music/battle.ogg");
// or a custom crossfade length; 0 switches instantly
Audio.playMusic("music/victory.ogg", 3f);

Audio.stopMusic(2f); // fade out and stop
```

Calling `playMusic` with the track that is already playing does nothing, so you can call it from every screen's
setup code without restarting the song.

## Using Music directly

```java
Music theme = Music.load("music/theme.ogg");

Playback p = theme.loop(2f);   // loops, fading in over 2 seconds
theme.play();                  // once, from the start
theme.play(2f);                // once, fading in
```

| Method | Description |
|---|---|
| `Music.load(String path)` | From a file or classpath resource. Only the compressed file is read |
| `Music.load(AssetManager, String name)` | Through an [`AssetManager`](../reference/utilities.md#assetmanager) |
| `play()` / `play(fadeInSeconds)` | Plays once |
| `loop()` / `loop(fadeInSeconds)` | Repeats until stopped |
| `close()` | Stops every playback of the track |

Each call starts an independent [`Playback`](playback.md), so two calls play the track twice at once. Use the returned
playback to pause, fade or stop it:

```java
Playback song = theme.loop();
pauseButton.onAction(() -> { if (song.isPaused()) song.resume(); else song.pause(); });
```

## Loop points

Music loops back to the start by default. Set loop points to keep an intro out of the repeat:

```java
Music theme = Music.load("music/battle.ogg")
        .loopPoints(12.5f, 96.0f);   // play the intro (0-12.5s) once, then repeat 12.5s-96s
theme.loop();

Music.load("music/menu.ogg").loopFrom(8f);   // repeat from 8s to the end of the file
```

`loopPoints(start, end)` takes seconds; an `end` of 0 means the end of the file. Loop points belong to the `Music`,
so set them before calling `loop()`; playbacks that are already running keep the points they started with. They are
applied to the samples, so the repeat is seamless with no gap or click, as long as the file itself is cut cleanly at
those points. An invalid region throws `IllegalArgumentException`.

`Audio.playMusic` uses the track's loop points too. `Audio.music(path)` gives you the cached `Music` to configure first:

```java
Audio.music("music/battle.ogg").loopPoints(12.5f, 96f);
Audio.playMusic("music/battle.ogg");
```

## Ducking under dialogue

Fade the music channel's playback down while a voice line plays, then back up:

```java
Playback music = Audio.getMusic();
if (music != null) music.fadeTo(0.3f, 0.3f);
Audio.play("voice/line1.ogg", AudioChannel.VOICE)
     .onFinished(() -> { if (music != null) music.fadeTo(1f, 0.5f); });
```

## Gotchas

- **Prefer Ogg.** `.wav` music is fully decoded into memory when loaded (a 3-minute stereo track is about 30 MB).
- **MP3 loop points are approximate.** MP3 files have a short silent lead-in from the encoder, and jumping back in an
  MP3 decodes forward from the start of the file (fast for normal tracks, slow for very long ones with a late loop start).
- **Bad files fail at `load`,** not at play time.
