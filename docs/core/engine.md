# Engine
[Documentation](../README.md) › [Core](README.md)

`me.piitex.engine.Engine` is the entry point that turns [`Window`](window.md) objects into real, running windows.

```java nocompile
public void start(Window window);  // opens the first window and runs until it closes
public void open(Window window);   // opens another window, from any thread
public List<Window> getWindows();  // the windows open now
public void setPacingWindow(Window window); // the window whose vsync and max FPS pace the loop (null: the first)
public static Engine getRunning(); // the running engine, or null
```

## What `start` does
1. Creates the GLFW window at the size and title from the window's [`WindowOptions`](window.md#windowoptions).
   The window is created **hidden**.
2. Attaches the clipboard and the mouse-cursor bridges to the window.
3. Creates the graphics context:
   - **macOS:** a Metal layer on the window's view.
   - **Windows/Linux:** an OpenGL 3.3 core context.
   - **Any platform, if the window asks for [`RenderAPI.VULKAN`](rendering-apis.md):** a
     Vulkan instance, GPU and swapchain. If that can't be had, the platform default is used and a warning says why.
4. Starts the **render thread** (see [Architecture](../architecture.md#two-threads)).
5. Loops on the main thread, delivering GLFW events and servicing the clipboard and cursor, until the
   window is closed.
6. Shows the window once the first frame has been drawn, so you never see a blank or garbage frame.
7. Plays the [RenGL splash screen](#the-splash-screen) over the first window before your application appears.

**`start` blocks** until the user closes the window. Build the window's UI before calling it; other windows can be
[opened](#several-windows) at any time.

```java
Window window = new Window(new WindowOptions("My App").setDimensions(1280, 720));
// ... add containers and elements ...
new Engine().start(window);
// Nothing after this line runs until the window is closed.
```

## The splash screen
Unless you choose otherwise, an application opens with the RenGL splash: an animated mark (two hexagons that draw
themselves around an R), the RenGL wordmark with a light sweeping across it, and the tagline, over a slowly drifting
star field. It plays for about two and a half seconds on the first window, then fades out over your application. It is
drawn with the engine's own renderer, so it looks the same on OpenGL, Vulkan and Metal.

While a splash holds the application, its containers and scenes are not updated or drawn, `onUpdate` hooks
do not fire, and clicks and keys are dropped. Your first frame is the first one after the splash lets go, so scenes
start from their beginning and nothing plays out unseen. Time-based work that does not belong to a window, such as
the [scheduler](scheduler.md), keeps running meanwhile. Other windows opened with `open` get no splash.

The splash is on by default. Two [`WindowOptions`](window.md#windowoptions) settings change it, and the engine reads them
once, when `start` opens the window:

```java nocompile
new WindowOptions("My App").setSplashEnabled(false);       // no splash: your first frame is the window's first frame
new WindowOptions("My App").setSplash(new MySplash());     // your own splash instead of RenGL's
new WindowOptions("My App").setSplash(null);               // back to the RenGL splash
```

A test that starts a real window should switch the splash off, or it waits the extra two and a half seconds for the
application to appear.

### Loading under the splash
Some work is slow the first time it is needed: building a 3D scene's pipelines, creating a DLSS or FSR upscaler (which
loads the vendor's library), compiling shaders. It normally happens in the application's first frame, which stalls
right after the splash ends. A `WarmUp` moves it under the splash instead:

```java nocompile
Viewport3D viewport = new Viewport3D(1280, 720);
viewport.setContent(sceneContent);                       // a Scene3D with its upscaler and ray tracing already set
WindowOptions options = new WindowOptions("My App")
        .addWarmUp(viewport.warmUp())                    // the 3D layer's own, for a viewport
        .addWarmUp(WarmUp.of("Save games", window -> index.rebuild())); // or any work of your own
```

The engine draws the splash first, then runs the warm-ups one after another on the render thread, one `step` per
frame. The application stays held until the last is done, so the splash lasts as long as the loading does (and never less
than its usual two and a half seconds). The RenGL splash shows it: its backdrop keeps moving, the bar stops short of full,
and the footer says `LOADING 3D SCENE`.

```java nocompile
public interface WarmUp {
    String getName();                                     // shown by the splash
    default void prepare();                               // work that needs no graphics, on a worker thread, from the start
    boolean step(Window window, Renderer renderer);       // one slice of the work on the render thread; true when finished
    default void onApplicationStarted(Window window);     // let go of what only the warm-up kept alive
    static WarmUp of(String name, Consumer<Window> work); // all of the work in one call
    static WarmUp inBackground(String name, Runnable work); // all of the work on the worker thread
}
```

#### Keeping the splash moving
The splash is drawn by the same thread that does the render-thread work, so a slow step stops it. Everything below
shortens, moves or places that stop:

- **Background work first.** `prepare()` (and `WarmUp.inBackground`) runs on a worker thread as soon as `start` is called,
  before the window even opens, so it overlaps with the time the graphics API takes to start. Anything that needs no
  graphics context belongs there; the Audio device is the usual one: `WarmUp.inBackground("Audio", Audio::isAvailable)`
  opens it before the first sound, instead of in a frame. A warm-up's steps wait for its `prepare`, and the splash goes on
  meanwhile.
- **The steps wait for the introduction.** They start only after the splash has been on screen for `setWarmUpDelay`
  (2 seconds by default), so a stall lands on a settled picture and not in the middle of the animation. `0` starts them at
  once, which is faster and makes the introduction stutter.
- **Small slices.** Slices that are quick share a frame (up to 8 ms of steps), so many small warm-ups do not take a frame
  each; one that is slow ends the frame's work. `Viewport3D.warmUp()` makes the device, the content and the first frame in
  separate steps.
- **The application's first frames happen behind the splash.** See [the hand-over](#the-hand-over).
- **The part that cannot be split.** A call into a driver or a vendor's library that takes seconds, such as DLSS creating
  its feature the first time it runs (about 3 seconds for Ray Reconstruction on an RTX 4090), stops the splash for as long
  as it takes. It runs once, after the introduction, and the application then finds it done.

Start the program with `-Drengl.trace.frames=true` to see where the long frames of the start-up go: it logs every frame
over 40 ms (`-Drengl.trace.frames.ms=N` changes that) with the time of each part, such as `warm-up 'Main menu scene'=3233ms`,
and notes when the hand-over starts and ends.

- **Failure.** A warm-up that throws is logged and dropped, and the rest still run; the application starts as it would have
  without it. `setWarmUpTimeLimit` (30 seconds by default) abandons the warm-up that is running and those after it, with a
  warning, so one that never finishes cannot hold the application back for good.
- **Only with a splash.** Warm-ups run only while the first window has a splash. With `setSplashEnabled(false)` they are
  skipped and the work happens when the application needs it, as before.
- **`Viewport3D.warmUp()`** makes the window's 3D device, initialises the viewport's content and draws it a frame or more
  into the viewport's own target (the picture is not shown). That builds the content's pipelines and acceleration
  structures and makes its upscaler. It keeps drawing while the content `needsFrames()`, so a `Scene3D.preload` finishes
  under the splash too. Set the content before the engine starts.
- **A hidden viewport that keeps a scene alive.** If the application shows the scene in a different view than the one that
  was warmed (a `Background3D`, say), the warm-up's viewport is the scene's only user until that view has drawn. Closing
  it earlier (a timer, say) frees the scene's pipelines and its upscaler, and the application builds them again in its
  first frame. Close it in `onApplicationStarted`, which is called when the application has drawn its first frames.
- **What it cannot cover.** The Vulkan device itself, and with it starting Streamline for DLSS, is made before any
  renderer exists, while the window is still hidden, so no splash can be drawn over it.
- **Your own splash** reads `window.getLoading()` (`isDone()`, `getProgress()` and `getCurrent()`, the name of the work
  running) to wait for the warm-ups and to show them. A splash that finishes while they are still running leaves the
  window cleared to its colour (black without a splash) until they are done.

#### The hand-over
The application's first frames are its longest: it lays itself out, compiles the shaders its widgets use and uploads its
images. If they came when the splash let go, they would land in the middle of its fade and freeze it. So the engine runs
them first, with the splash still drawn over the application, standing still: until three frames in a row are quick (or
three seconds have passed), the splash's `update` and the application's `onUpdate` hooks and scenes get a delta of one
millisecond, and input is still dropped. Then time runs again, the splash fades and the warm-ups are told
`onApplicationStarted`. The splash needs to do nothing for this; it should only stay (nearly) opaque until it stops
holding the application.

### Your own splash
Implement `me.piitex.engine.Splash` and pass an instance to `setSplash`. The engine calls it on the render thread, every
frame while it is active:

| Method | What it does |
|---|---|
| `onStart(Window)` | Optional. Called once before the first frame; `window.getRenderer()` says which graphics API draws it, and `window.getLoading()` how far the [warm-ups](#loading-under-the-splash) have got |
| `update(float delta)` | Advances the splash by the seconds since the last frame |
| `isFinished()` | Checked after `update`. When true the splash is disposed and never called again |
| `isHoldingApp()` | While true the application is held (see above) and the window is cleared to `getClearColor()` |
| `render(Renderer, int width, int height)` | Draws the splash on top of everything, in logical pixels with the origin at the top left. Match every `pushAlpha`, `pushScale` and `pushClip` with its pop |
| `getClearColor()` | Optional. What the window is cleared to while the application is held; black by default |
| `dispose()` | Optional. Frees images and fonts. Also called if the window closes before the splash finishes |

A splash that stops holding the application but is not finished yet is drawn over the running application, which is how
a fade-out works. One that holds until a background load finishes just returns `!loaded` from `isHoldingApp`. An
instance plays once, so give each window its own. A splash that throws is logged and dropped, and the application
carries on.

```java nocompile
class LoadingSplash implements Splash {
    private final AtomicBoolean loaded = new AtomicBoolean();
    private float fade; // seconds since loading finished

    LoadingSplash() {
        Scheduler.async(() -> { loadEverything(); loaded.set(true); });
    }

    public void update(float delta)   { if (loaded.get()) fade += delta; }
    public boolean isHoldingApp()     { return !loaded.get(); }
    public boolean isFinished()       { return fade >= 0.3f; }

    public void render(Renderer renderer, int width, int height) {
        renderer.pushAlpha(1f - Math.min(fade / 0.3f, 1f));
        renderer.drawQuad(0, 0, width, height, Color.BLACK, 0);
        renderer.drawText("Loading...", width / 2f, height / 2f, Font.sized(24f), Color.WHITE, TextAlign.CENTER);
        renderer.popAlpha();
    }
}
```

`Splash` has no setting for how long it lasts; the splash decides, by when `isHoldingApp` and `isFinished` turn true.

### Several splashes in a row
A publisher or a framework may need its own splash before yours, or before RenGL's. `Splash.sequence` plays splashes one
after another as a single splash. `new RenGLSplash()` is public, so list it where you want it:

```java nocompile
new WindowOptions("My App").setSplash(Splash.sequence(
        new PublisherSplash(),   // each plays in turn
        new FrameworkSplash(),
        new RenGLSplash()));     // RenGL's is part of the sequence only if it is listed
```

- Each splash gets its own `onStart`, runs until its `isFinished`, and is disposed after.
- **One dissolves into the next.** A splash that stops holding the application is fading out, so the next one starts at that
  moment and plays under the fade, with the fading one drawn on top. The window never goes blank between them. A splash
  that holds the application until it is finished is followed by the next one when it ends. Only one fades at a time.
- The application is held through every splash but the last, so it never shows between two. The last one decides for
  itself, so it can fade out over the application.
- The window is cleared to the `getClearColor()` of the splash that is playing.
- A splash that throws is logged and skipped, and the rest still play.
- If the window closes early, the playing splash and those that never played are disposed.
- An instance plays once, so list each only once. A sequence is itself a `Splash`, so it can sit inside another.

## Files the OS asks the app to open
Double-clicking a `.zip` that is associated with your app, "Open With", or dropping a file on its Dock icon asks the
running (or starting) app to open it. Windows and Linux start the program with the paths as `main(String[])`
arguments. macOS passes no arguments: it sends an Apple event. One hook covers both:

```java nocompile
public static void main(String[] args) {
    Engine.onOpenFiles(paths -> archive.open(paths.getFirst())); // render thread
    Engine.openFilesFromArguments(args);                         // Windows and Linux; harmless on macOS
    new Engine().start(window);
}
```

`onOpenFiles` and `openFilesFromArguments` are static, so they work before the engine exists. A request that arrives
before the listener is set (the OS can ask while the window is still opening) is kept and delivered on the first frame
after it is set. `openFilesFromArguments` keeps only the arguments that name an existing file or folder, so options
such as `--verbose` are ignored. On macOS the engine adds the receiving method to GLFW's application delegate before GLFW
starts, because Finder's event for a double-clicked file arrives while the app is starting. It ignores the
launch command's own words (`java -jar app.jar` reports `app.jar` as a file to open). Registering the file type with the
OS is part of [packaging](../reference/packaging.md).

## The system's light or dark setting

`Engine.getSystemAppearance()` says whether the OS is set to light or dark (`SystemAppearance.Mode.LIGHT` or `DARK`), so
the app can start in the look the user chose. `Engine.onSystemAppearanceChange(listener)` is called on the render thread
when the user switches the setting while the app runs; it is not called for the appearance the app starts under.

```java nocompile
boolean dark = Engine.getSystemAppearance() == SystemAppearance.Mode.DARK;
ThemeManager.setCurrent(dark ? new DarkTheme() : new LightTheme());
Engine.onSystemAppearanceChange(mode ->
        ThemeManager.setCurrent(mode == SystemAppearance.Mode.DARK ? new DarkTheme() : new LightTheme()));
```

It is read with `defaults read -g AppleInterfaceStyle` on macOS, the `AppsUseLightTheme` registry value on Windows and
`gsettings` on Linux (GNOME's `color-scheme`, else a GTK theme with "dark" in its name); a setting that cannot be read
is `LIGHT`. There is no change notification the three share, so a listener makes a background thread read it every two
seconds. Without a listener `getSystemAppearance()` runs the command each call (tens of milliseconds): call it once.
Only the macOS command was run; the Windows and Linux readings are checked against sample output.

## What the render thread does every frame
In order: apply a pending resize, process input, advance an animated window background, run
[`Scheduler`](scheduler.md) tasks and queued menu and file-dialog callbacks, run the [scenes'](../game/scenes.md)
fixed steps and updates on the [game clock](../game/game-time.md), call `update(delta)`
on every enabled top-level container, update tooltips, clear and draw the window background, draw every
enabled top-level container, draw the tooltip, and present. See [Architecture](../architecture.md#one-frame)
for the full list.

## Resizing
When the user resizes the OS window, the engine recreates its drawing surface and keeps drawing. The
window's contents are **not** re-laid-out automatically. A [`ResizableContainer`](../containers/resizable-container.md)
follows the window on its own; for anything else, resize it yourself from
[`window.onResize`](window.md#resize-event). [`WindowOptions`](window.md#windowoptions)' `getWidth()`/`getHeight()`
always hold the current size.

## Several windows
One engine can drive any number of windows. The first is the one given to `start`; open more with
`engine.open(window)` or `window.open()`, from any thread, before or after `start`. Each window has its own
containers, [scenes](../game/scenes.md), input, focus, popups and tooltips.

```java nocompile
Window game = new Window(new WindowOptions("Game").setDimensions(960, 540));
game.getSceneManager().set(EntityScene.load("scenes/level1.json"));
game.onClose(() -> System.out.println("game window closed"));
game.open(); // opens on the next pass of the main loop and shows once its first frame is drawn
```

- **Closing.** Closing a window (its close button or `window.close()`) closes only it, and its `onClose` runs.
  Closing the first window closes every other window too and ends the engine; `start` then returns.
- **Reopening.** A closed window can be opened again with `open()`. Its containers and scenes are where they were
  left.
- **Changing the graphics API.** `WindowOptions.setRenderer` on an open window makes the engine close and reopen its OS
  window on the new API, the first window included. `onClose` does not run and `start` does not return. See
  [Switching the renderer](window.md#switching-the-renderer).
- **One render thread.** Every window is updated and drawn on the same render thread, one after another each frame,
  so a slow window slows the others.
- **One game clock.** [Game time](../game/game-time.md) is shared: pausing it or changing its time scale affects the
  scenes of every window.
- **Frame rate.** One window paces the loop with its vsync and max-FPS settings: the first, unless
  `engine.setPacingWindow(window)` picks another. Only that window waits for the display; the others present as
  soon as they are drawn, at its rate, since several windows each waiting would slow or unsettle the frame rate.
  Their own vsync and max-FPS settings are ignored until they pace. An editor makes the game window it opens on
  Play the pacing window, so the game runs at its own settings; when that window closes, the first paces again.
- **Per-window helpers.** [`MouseTracker`](../input/input-and-focus.md#reading-the-mouse-directly) answers for the window
  being processed, and cursor changes go to the window they come from.

Create a single `Engine` per process; `start` refuses to run a second one. `me.piitex.examples.demo.MultiWindowDemo`
opens game windows from a button.
