# Video

[Documentation](../README.md) › Video

RenGL plays video files in the UI. A `VideoOverlay` is an element like any other: give it a file and a size, add it to
a container, and it plays.

```java
VideoOverlay intro = new VideoOverlay("assets/intro.avi", 1280, 720);
intro.getPlayer().onFinish(() -> startGame());
container.addElement(intro);
```

Files are **streamed from disk** by a background decode thread. Only about a second of decoded pictures and sound is
held in memory, and seeking jumps straight to the right place in the file, so a video of tens of gigabytes opens
instantly and costs no more memory than a short clip.

| Class | What it is |
|---|---|
| `VideoOverlay` | The element. Draws the picture, fitted into its bounds |
| `VideoPlayer` | The playback engine behind it: `overlay.getPlayer()`. Use it directly to draw video yourself |

## Supported files

The container and codec are recognised from the file's bytes, not its extension.

| | Supported |
|---|---|
| Container | **AVI**, including files over 4 GB (OpenDML, split into `RIFF AVIX` segments) |
| Video | **Motion JPEG** (`MJPG`), **uncompressed RGB** (24 and 32-bit, with alpha) |
| Audio | **PCM**: 8, 16, 24 and 32-bit integer, or 32-bit float. Mono and stereo; extra channels are dropped |

An unsupported file fails to open with an `UnsupportedOperationException` that names the codec.

Both video formats store every frame whole, which is what makes them quick to seek and cheap to decode, and why
they suit pre-rendered animation: render an image sequence, encode it as Motion JPEG (high quality) or uncompressed
(lossless; a 32-bit file keeps its alpha channel), and play it. They are not small files. **H.264, H.265, VP9, AV1 and AAC are not
supported**: the engine avoids patent-encumbered codecs (see [known limitations](../reference/known-limitations.md#video)),
and a compact open codec (VP8/WebM) has not been written yet. Video with a soundtrack in any other format plays
without sound.

Encode a file for RenGL with FFmpeg:

```bash
ffmpeg -i in.mov -c:v mjpeg -q:v 2 -c:a pcm_s16le out.avi        # Motion JPEG, near lossless
ffmpeg -i frames/%04d.png -r 30 -c:v rawvideo -pix_fmt bgr24 -c:a pcm_s16le out.avi  # uncompressed
```

## VideoOverlay

```java
VideoOverlay video = new VideoOverlay("assets/cutscene.avi");          // the video's own size
VideoOverlay video = new VideoOverlay("assets/cutscene.avi", 800, 450); // fitted into 800x450
```

It starts playing at once and the container advances it every frame (an element that is not updated stays on its
first frame).

| Method | Does |
|---|---|
| `play()`, `pause()`, `stop()` | `play()` after the end starts over; `stop()` pauses on the first frame |
| `toggle()` | Pause a playing video, play a paused one: wire it to a click |
| `setFit(Fit)` | `CONTAIN` (default): whole picture, bars in the background colour. `COVER`: fill and crop. `STRETCH`: fill and distort |
| `getPlayer()` | Everything below |
| `dispose()` | Stops decoding, silences the sound, closes the file |

The background is black by default, and shows in the bars of `CONTAIN`. Change it with `getStyling()`; fade the
element with the normal element `setOpacity`. **Call `dispose()` when you remove a video**: the decode thread and the
file stay open until you do.

## VideoPlayer

| Method | Does |
|---|---|
| `seek(seconds)`, `setProgress(0..1)` | Jump. The picture changes once that frame is decoded |
| `getPosition()`, `getDuration()`, `getProgress()` | Where it is |
| `setLoopCount(n)`, `setLooping(b)` | `1` plays once (the default), `3` three times, `0` forever |
| `setSpeed(s)` | 0.1 to 4. The sound changes pitch with it |
| `setVolume(v)`, `setMuted(b)`, `setChannel(c)` | Soundtrack volume, and the [audio channel](../audio/README.md) it plays on (`SFX` by default) |
| `onFinish(r)`, `onLoop(r)`, `onError(c)` | Callbacks, run on the render thread |
| `isPlaying()`, `isFinished()`, `isBuffering()` | State |
| `getInfo()` | `MediaInfo`: size, frame rate, duration, codecs |

```java
VideoPlayer p = video.getPlayer();
p.setLooping(true);
p.setVolume(0.6f);
p.setProgress(0.5f);   // jump to the middle
```

## Sync and timing

While the soundtrack plays, the sound card's own position is the clock, so picture and sound stay locked together.
A video without sound (or with the audio device unavailable) runs on the time passed to `update`, like an
[animated GIF](../styling/images-and-icons.md), so it follows a scene's time scale. If the disk or CPU cannot keep up,
late pictures are dropped to stay with the sound, and a silent video waits (`isBuffering()`).

## Using VideoPlayer without the overlay

```java
VideoPlayer video = VideoPlayer.open("assets/intro.avi");
// every frame, on the render thread:
video.update(delta);
renderer.drawImage(video.getFrame(), x, y, w, h, 0f, 1f);   // draw it, don't keep it
// when done:
video.close();
```

`getFrame()` is a Skia image that is replaced as playback moves on. A demo is in `examples/demo/VideoDemo`.
