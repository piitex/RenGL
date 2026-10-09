# Images and icons

[Documentation](../README.md) › [Styling](README.md)

Five small classes back [`ImageOverlay`](../overlays/image-overlay.md) and
[`IconOverlay`](../overlays/icon-overlay.md). You rarely call them directly, but they explain the loading behavior
and errors.

## ImageLoader

`me.piitex.engine.ui.image.ImageLoader` decodes an image file once and shares the decoded `ImageLoader` among every
overlay that uses the same file.

```java
Image image = ImageLoader.load("src/main/resources/demo.png"); // File or String
```

- The key is the file's **absolute path**, so relative paths work.
- The format is detected from the file's **bytes**, not its extension. PNG, JPEG, WEBP, GIF, BMP, ICO and WBMP
  all go through the same call, limited by which codecs the Skia build includes.
- Errors: `UncheckedIOException` if the file cannot be read, `IllegalArgumentException` if it is not a decodable
  image.
- The cache holds up to **256 MiB** of decoded pixels (`ImageLoader.setCacheLimit(bytes)`; `getCachedBytes()` says how
  much it holds). Past that, the pictures used longest ago are let go and decoded again if loaded again. A letting go
  does not close the image, so an overlay still showing it keeps working and its memory is freed when nothing draws it.
  A fixed set of assets that fits stays cached for good. `evict(file)` and `clearCache()` drop pictures by hand.

An image drawn at a size other than its own is sampled smoothly, blending neighbouring pixels. For pixel art that
should keep hard edges when scaled, draw it as a [sprite](../graphics/sprites.md) from a texture with
`setSmooth(false)`.

## Animated images and GIFs as video

`ImageLoader.load` on a GIF gives its **first frame** as a plain `Image`. To play it, use
`me.piitex.engine.ui.image.AnimatedImage` (the frames) and `ImagePlayer` (the playback controls). An
[`ImageOverlay`](../overlays/image-overlay.md#animated-gifs) does both for you, so most code never needs these directly.

```java
AnimatedImage gif = AnimatedImage.load("assets/intro.gif"); // File, String, Path or bytes
gif.getFrameCount();     // 24
gif.getDuration();       // seconds for one pass
gif.getLoopCount();      // what the file asks for; 0 = forever

ImagePlayer player = new ImagePlayer(gif); // already playing
player.setLoopCount(1);                    // or setLooping(false)
player.onFinish(() -> startGame());
player.onLoop(() -> System.out.println("again"));

// every frame, with the scene's delta so it follows game time and pauses with the game:
player.update(delta);
renderer.drawImage(player.getFrame(), x, y, w, h, 0f, 1f);
```

| `ImagePlayer` | |
|---|---|
| `play()` / `pause()` / `stop()` / `rewind()` | `play()` after a play-once animation ended starts it again; `stop()` pauses and goes to frame 0 |
| `seek(seconds)` / `seekFrame(i)` / `setProgress(0..1)` | Jump without changing play/pause |
| `getPosition()` / `getProgress()` / `getFrameIndex()` / `getDuration()` | Where playback is |
| `setLoopCount(n)` / `setLooping(bool)` | 1 plays once, `n` plays `n` times, 0 loops forever |
| `setSpeed(f)` | 1 is normal, 2 is double, 0.5 is half |
| `onFinish(...)` / `onLoop(...)` | `onFinish` fires only for limited loops |

Notes:

- Frames are fully composited, following the file's own disposal rules, so `getFrame()` is always the whole picture.
- Frames with a delay under 20 ms (including the 0 that many old files carry) show for 100 ms, matching browsers.
- A short animation is decoded once and replays from memory; one whose frames total more than
  `AnimatedImage.CACHE_LIMIT_BYTES` (64 MiB) is decoded as it plays, which is cheap in order but costs a re-decode on a backwards seek.
- The `Image` from `getFrame()` belongs to the `AnimatedImage` and may be closed when a later frame is requested:
  draw it right away. For frames that stay valid, `gif.toAnimation(PlayMode.LOOP)` decodes every frame into
  its own texture and returns a sprite [`Animation`](../graphics/sprite-animation.md), at the cost of holding them all in memory.
- Audio is not part of a GIF. Not thread-safe: use it on the render thread. `close()` frees the decoder early.

## IconCache

`me.piitex.engine.ui.image.IconCache` rasterizes an **SVG** into an `Image` using Skia's built-in SVG renderer.
No third-party SVG library is needed.

```java
Image fromFile = IconCache.load(new File("assets/logo.svg"), 64, 64);
Image fromClasspath = IconCache.loadResource("icons/feather/alert-triangle.svg", 24, 24);
```

An SVG has no pixel size of its own, so it is re-rendered at whatever size is requested. The cache key is
**the source and the size**. The same icon at two sizes is rasterized (and cached) twice. The icon cache holds up to 64 MiB of pixels
(`IconCache.setCacheLimit`) and lets go of the ones used longest ago, as the image cache does.

`IconCache.rasterizeFile` and `IconCache.rasterizeResource` render without caching the result. You own the returned
`Image` and should close it when nothing draws it any more. This is what a resized
[`IconOverlay`](../overlays/icon-overlay.md#changing-size) uses, since caching every size it passes through would grow without bound.

`loadResource` resolves the path through the classloader, so it works for icons inside your own jar or any jar on
the classpath.

## IconAsset

A record that names a classpath SVG:

```java nocompile
public record IconAsset(String resourcePath) {}
```

```java
IconAsset logo = new IconAsset("icons/mine/logo.svg");
IconOverlay icon = new IconOverlay(logo, 32, 32);
```

## Feather

`me.piitex.engine.ui.icons.Feather` is the built-in icon set: about 290 constants (`Feather.ACTIVITY`,
`Feather.ALERT_TRIANGLE`, `Feather.CHECK`, `Feather.SETTINGS`, `Feather.X`, ...), each an `IconAsset` pointing at
a bundled SVG. Feather is MIT-licensed (Copyright (c) 2013-2023 Cole Bemis), and the license text is bundled on the
classpath at `icons/feather/LICENSE`.

Icons ship as `stroke="currentColor"`, which the rasterizer resolves to **black**. Recolor with a tint:

```java
IconOverlay check = new IconOverlay(Feather.CHECK_CIRCLE, 20, 20);
check.setTint(Color.GREEN);
```

To browse the set, read the constants in `Feather`, or list `src/main/resources/icons/feather/`. A constant is the
file name in SCREAMING_SNAKE_CASE (`arrow-up-circle.svg` is `Feather.ARROW_UP_CIRCLE`).

## Using an icon pack of your own

1. Put the SVGs on the classpath (for example `src/main/resources/icons/mine/`).
2. Wrap each path in an `IconAsset`.
3. Construct [`IconOverlay`](../overlays/icon-overlay.md)s from them and tint to taste.

```java
public final class Icons {
    private Icons() {}
    public static final IconAsset HOME = new IconAsset("icons/mine/home.svg");
    public static final IconAsset USER = new IconAsset("icons/mine/user.svg");
}
```

Check the license of any pack you ship.

## Errors you may see

| Situation | Result |
|---|---|
| Image file missing/unreadable | `UncheckedIOException` |
| File is not an image | `IllegalArgumentException("Not a decodable image file: ...")` |
| Classpath icon missing | An exception from `loadResource` naming the path |
| Font file invalid | `IllegalArgumentException` from `Font.load` |

All are thrown when the widget is constructed (or when you change the file), not later during drawing.
