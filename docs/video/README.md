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

The container and codec are recognised from the file's bytes, not its extension. There are two routes, and the
player picks one for you:

**The system decoder (macOS).** Any file macOS can play, which includes **H.264 and HEVC in MP4 and MOV** with
**AAC** sound, plays through Apple's AVFoundation. The video is decoded by the machine's hardware video engine, so
the engine contains no H.264 or AAC decoder of its own, and the licence for those codecs is the operating system's.
Nothing is installed or shipped: the engine calls the system frameworks directly. A video the file marks as turned (a
phone held upright) is shown upright, and its width and height are the turned ones.

**The engine's own readers.** These work on every platform and need nothing from the system:

| | Supported |
|---|---|
| Container | **AVI**, including files over 4 GB (OpenDML, split into `RIFF AVIX` segments); **MP4 / MOV**, plain or fragmented, with the index at either end of the file |
| Video | **Motion JPEG** (`MJPG` in AVI, `jpeg` in MP4), **uncompressed RGB** (AVI only; 24 and 32-bit, with alpha) |
| Audio | **PCM**: 8, 16, 24 and 32-bit integer, or 32-bit float (in MP4: `sowt`, `twos`, `in24`, `in32`, `fl32`, `ipcm`). Mono and stereo; extra channels are dropped |

A file the engine can read itself always uses its own reader, so it plays the same everywhere. Anything else uses
the system decoder where there is one. **On Windows and Linux there is no system decoder yet**, so there only the
table above plays. A file that cannot play fails to open with an `UnsupportedOperationException` that names the
codec and gives an FFmpeg command that converts it:

```bash
ffmpeg -i in.mkv -c:v mjpeg -q:v 2 -c:a pcm_s16le out.avi        # Motion JPEG, near lossless (out.mp4 also works)
ffmpeg -i frames/%04d.png -r 30 -c:v rawvideo -pix_fmt bgr24 -c:a pcm_s16le out.avi  # uncompressed
```

Motion JPEG and uncompressed video store every frame whole, which is what makes them quick to seek and cheap to
decode, and why they suit pre-rendered animation. They are not small files; a game that ships on Windows or Linux
should ship those, or convert its videos once at build time.

### Speed of the system decoder

Measured on an Apple M-series MacBook Air, decoding H.264 and copying each frame into the engine's pixel format
(the work the decode thread does; playback needs 1x):

| Video | Decode speed | CPU used by the engine's process |
|---|---|---|
| 1080p, 14 Mbit/s | about 32x real time (~950 frames/s) | 2 to 5% of one core at normal speed |
| 4K, 57 Mbit/s | about 10x real time (~290 frames/s) | 7 to 10% of one core at normal speed |

The decoding itself runs on the video engine, not the CPU. The costs that remain are one copy of each frame out of
the decoder's buffer (about 8 MB at 1080p, 33 MB at 4K) and uploading it as an image. A seek restarts the decoder,
which took about 25 ms at 1080p and 85 ms at 4K.

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
element with the normal element `setOpacity`. Removing a video from its container pauses it, but **call `dispose()` when you are done with it**: the decode thread and
the file stay open until you do.

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

A looping video wraps without a break. The decode thread carries on from the end of the file into the next pass while the
last second is still playing, so the soundtrack never stops and the first frame of the next pass is already waiting.
`getPosition()` starts again from 0 as the new pass is reached, and `onLoop` fires at that moment. A seek still restarts
the sound, because the old queue is no use at the new place: the picture and sound resume once the decoder has the first
part ready. Turning looping off in the middle of a pass drops what was queued past the end and seeks to where it is, so
that one call can leave a short gap.

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
