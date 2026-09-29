# Known limitations

[Documentation](../README.md) › [Reference](README.md)

Things that are not implemented, or that behave in a way that may surprise you. Each is verified against the
source. If you hit something not listed here, please add it.

## Window and engine

- **`WindowOptions` are read once,** at window creation. Changing them later does nothing, except `setVsync` and
  `setMaxFPS`. To change a running window use the [`Window` methods](../core/window.md#window-styles-at-runtime)
  (`setStyle`, `setSize`, `setTitle`, `setFullscreen`, ...) instead.
- **On macOS `setIcon` / `setIconPath` set the Dock icon, not a window icon**, because macOS windows have none. A packaged app's bundle icon is still what Finder shows.
- **`setFullscreen`, `setWindowed` and `setHandleFocus` are interpretations.** Fullscreen covers the primary monitor,
  `setWindowed(false)` removes the window border (both together give borderless fullscreen), and `setHandleFocus(true)`
  clears keyboard focus when the OS window loses focus. Fullscreen has not been tried with the macOS Metal path.
- **Vsync on ignores `setMaxFPS`.** The frame rate follows the display. The one exception is a fallback: if a frame
  finishes in under 2 ms with vsync on (a minimized or hidden window that gets no drawable), `maxFPS` is applied so the
  loop does not spin.
- **The frame limiter finishes each frame by spinning** for its last ~2 ms, because OS sleeps wake up late. That
  costs a little CPU with vsync off.
- **The OS can open the window smaller than requested** (for example a 720-tall window on a small screen).
  `WindowOptions` always holds the real size, and the engine logs a warning, but containers keep their size unless
  they are a [`ResizableContainer`](../containers/resizable-container.md).
- **Contents do not reflow on resize,** except inside a `ResizableContainer`. Other containers keep their size.
  Resize them yourself from [`window.onResize`](../core/window.md#resize-event) if you want them to follow the window.
  An [anchored](../core/element.md#anchoring) element keeps its place in a container that is resized, but not its size.
- **One engine per process.** It can drive [several windows](../core/engine.md#several-windows), but they share one
  render thread and one game clock, and one window's vsync and max-FPS settings pace them all (the first, or the one given to
  `Engine.setPacingWindow`).
- **The macOS system menu bar has one owner.** With several windows each showing a native
  [menu bar](../input/menus.md), the first one that runs owns the system menu, whichever window has focus.
- **No stop method on `Engine`.** `Engine.start` returns when the OS window closes. Call `window.close()` to close
  it from code.
- **Callbacks run on the render thread.** To change the UI from another thread, post the work with
  [`Scheduler.runLater`](../core/scheduler.md), which runs it on the render thread at the start of the next frame.

## Containers and layouts

- **`getX()`/`getY()` are relative to the container,** and mouse positions (drag callbacks, click events,
  `MouseTracker`) are window coordinates. Convert with `toLocalX`/`toLocalY` or `getAbsoluteX`/`getAbsoluteY`. Reading
  `getAbsoluteX()` walks up the containers each call, so it is not free in a hot loop.
- **`removeElement` leaves gaps** in the index space.
- **The topmost container under the mouse takes the click, even where it is empty.** A transparent full-window
  container drawn above another container swallows every press meant for the one below. Add full-window roots first.
  This includes [scenes](../game/scenes.md): the elements of a scene under an overlay scene can't be clicked.
- **Layout positions are rewritten every frame.** A child of a layout cannot be positioned or dragged by hand,
  because the layout puts it back.
- **Sizing to content is per layout.** Only layouts measure their children. A plain `Container` has no
  content size, so one inside a layout counts as whatever size it was given.
- **[`SplitContainer`](../containers/split-container.md) holds exactly two panes** (nest for more).
- **[`ScrollContainer`](../containers/scroll-container.md) measures only direct children** for its scroll range, so a layout inside it
  needs `setSizeToContent`, or an explicit height.
- **Scrollbars overlay the content** on the right/bottom edge instead of reserving a gutter, in both
  `ScrollContainer` and [`TextAreaOverlay`](../overlays/text-area-overlay.md).

## ResizableContainer

- **The handles sit on the container's own border.** A full-window container has them at the window's corners, next to the
  OS window border, where they are hard to hit. Put the handles on a visible panel instead.
- **A handle takes the mouse ahead of any child under it,** so a clickable child inside the grip strip
  (10 points by default) cannot be clicked there.
- **A parent layout fights top and left handles** and moving by dragging, because it re-places the container every
  frame. Only the bottom and right handles behave naturally there.
- **Only a top-level container follows the window.** A nested one is scaled only if its parent is a
  `ResizableContainer`, not if the parent is a plain container or layout.
- **Following the window is proportional.** Once min/max clamps the size, the position keeps scaling, so the two can drift
  apart until the window returns to the reference size.
- **Layout spacing and padding are not scaled,** only sizes and positions. Text scales by the smaller of the width and
  height factors, so it never overflows, but it can look small in one direction.
- **Only some elements scale their text.** Those that implement `FontScalable`: text, button, text flow, text field,
  text area, dropdown and slider. Tooltip text has a fixed size, and images scale as pictures (an
  `IconOverlay` re-renders its SVG once the resize settles, so it is soft only during the resize).
- **No visible grip by default.** Only the cursor changes. `setShowResizeHandles(true)` draws the zones.

## Styling

- **Some parts accept only `Color`, not [`Paint`](../styling/colors-and-paints.md):** caret and selection colors, scrollbars, slider/progress/
  checkbox/radio parts, tooltip colors.
- **Constructor colors on themed widgets are baselines.** The theme's text color replaces them. Use `setTextColor`.
- **A custom [`AnimatedPaint`](../styling/colors-and-paints.md#animatedpaint) is not drawn by the built-in fill.** Only `Color` and [`LinearGradient`](../styling/colors-and-paints.md#gradients) are.

## Text

- **[`TextOverlay`](../overlays/text-overlay.md) is one line,** with no wrapping and no newline handling.
- **[`TextAreaOverlay`](../overlays/text-area-overlay.md) with word wrap off does not scroll horizontally.** Long lines are clipped at the edge.
- **Undo is snapshot-based.** Each step stores the whole text, so a very large `TextAreaOverlay` document with
  many steps costs memory. Lower `TextEditingOptions.setUndoLimit` if that matters. Typing a run of characters
  is one step, but there is no word-boundary splitting inside a run.
- **[`SpinnerOverlay`](../overlays/spinner-overlay.md) is a layout.** It is not an [`Overlay`](../core/overlay.md) subclass, so it does not appear in `instanceof Overlay`
  checks.

## Spell checking

- **Only `en_US` is bundled.** Other languages need their own Hunspell dictionary files
  (`HunspellChecker.fromStreams`).
- **No grammar engine is bundled.** Only the [`TextChecker`](../text/spell-checking.md#writing-your-own-checker) interface for plugging one in.
- **Underline colors are fixed** (red spelling, blue grammar).
- **The user dictionary is in-memory only,** and the suggestion popup has no "Add to dictionary" or "Ignore" row yet.
- **Checks are not incremental.** The whole text is re-checked after each pause in typing, so very large texts cost
  more.
- **`TextIssue.message()` is not displayed anywhere yet.**
- **One checker per overlay,** and one shared background thread across all overlays.

## Input

- **`onMouseClick` fires on press, not release,** for any mouse button, unless the element
  [clicks on release](../core/element.md#onmouseclick) as buttons do. Only one listener runs per press: the
  topmost element's, or the nearest ancestor's if the topmost has none. There is no way to let a press reach
  both a child and its container.
- **One listener per hook.** `onMouseClick`, `onUpdate`, `onRender` and most `onXxx` callbacks replace the previous
  listener rather than adding to it.
- **`Element.getZ()` is reserved** and unused by rendering or hit-testing.
- **A drag keeps the cursor shape of the dragged element** for its whole duration, including when it is a text field
  selecting text outside its own box.
- **An element with a click listener can never be used to drag its container.** Passing a press up to a draggable
  ancestor stops at the first element that listens for clicks, so a clickable title bar on a draggable panel
  does not drag it. Put the click handling on a smaller child (an icon or button) so the rest of the bar still drags.
- **Re-entering the window relies on `WindowOptions` being current.** After macOS drops the "cursor entered" event
  following a live resize, a mouse move that lands inside the window is what re-enables hover and clicks.
- **No gamepad support yet.** Game input covers the keyboard and mouse. See [Game input](../input/game-input.md).
- **Key names follow the keyboard layout only for keys that print a character.** Other keys use fixed English names
  ("Left Shift", "Page Up"). The layout is read when the window opens and each time it gains focus.
- **Several wheel notches in one frame count as one press** of a wheel binding. `Input.getScrollY()` has the full
  amount.
- **The resize cursors come from GLFW's standard cursor set.** They showed correctly on macOS. A platform or GLFW
  build without one falls back to the arrow, and the handles still work.

## Graphics

- **Rotated frames in packed sprite sheets aren't supported.** Turn rotation off in the packer; loading a sheet with
  rotated frames fails with a message saying so.
- **Smooth sprites turned at an angle can leave a few see-through pixels between them in snapshots.** Only the CPU
  renderer that [snapshots](../core/snapshots.md) use does this, and only for sprites of a smooth texture drawn
  turned. On screen, and for pixel-art textures, sprites side by side never leave gaps.
- **Shaders are SkSL only.** GLSL, HLSL and Metal shaders can't be loaded directly; SkSL is close to GLSL, so most
  fragment shaders port by renaming the entry point and replacing texture reads with `eval`.
- **Camera bounds hold the upright view.** A turned or shaking [camera](../graphics/camera.md#moving-it) can show a
  little past `setBounds`, so draw the level a few units larger if that must never show.
- **Tile maps are orthogonal only.** [`TileMap`](../graphics/tile-maps.md#loading) reads Tiled's square and
  rectangular grids, not isometric, staggered or hexagonal maps, nor layers compressed with zstd or images embedded
  in the file. Layer blend modes are ignored and text objects aren't drawn.
- **Collision response is `Slide` only.** [`Slide.move`](../game/geometry.md#pushing-apart-and-sliding) moves one
  shape among solid ones. There are no rigid bodies: nothing bounces, pushes other movers, stacks or rotates on
  impact.
- **Particles don't collide** with anything, and have no emitter that emits steadily over time: emit a few each
  frame for a trail.
- **Post effects on a turned camera are resampled.** Inside a turned `Camera`, `pushEffect`'s layer is drawn upright
  and turned afterwards, so its content is slightly softer than without the effect. Push effects outside the camera,
  in window coordinates, to avoid it.

## Not implemented yet

- **Stretching children.** A layout can size itself to its children but cannot size children to itself (fill the
  width, share leftover space). Only `SplitContainer` and `TabContainer` size their panes.
- **Reflow other than `ResizableContainer`.** [Anchors](../core/element.md#anchoring) keep an element in place in
  an ordinary container, but nothing sizes it by percent of the container.

## File dialogs

- **Native dialogs only,** so their look, and the extra controls they offer, come from the OS. There is no in-engine
  file browser and no dialog title or parent-window option.
- **The dialog is modal to the whole app.** The engine keeps rendering under it, but the main loop is busy until it
  closes, so other queued dialogs, the clipboard and cursor changes wait for it.
- **Linux needs GTK 3 or the desktop portal** installed. Without one, `onError` fires instead.
- **Paths only.** `FileDialog` never reads or writes the file, and the OS may not add the filter's extension to a
  save name the user typed without one.

## Assets

- **Decoded images and typefaces are cached forever.** There is no eviction, so loading very many unique large
  images will grow memory use.
- **A resized [`IconOverlay`](../overlays/icon-overlay.md) is stretched until the size settles** (0.12 seconds), then
  re-rendered. `setScale` and scale transitions only stretch it, because they don't change its size.
- **Feather icons are black** until you tint them.

## Audio

- **No AAC (`.m4a`, `.aac`).** It is patent-encumbered, so it is left out on purpose. Convert to Ogg Vorbis or Opus.
  Apple Lossless and other exotic formats are not supported either.
- **Mono and stereo only.** Files with more channels are rejected, except Opus, which is mixed down to stereo.
  FLAC is limited to 24 bits per sample and is converted to 16-bit.
- **Positioning is point-based.** There are no sound cones, occlusion by walls, or reverb, and OpenAL's
  distance units are unitless: choose your own scale and set the [attenuation](../audio/spatial-audio.md#distance-falloff) to match.
- **No seeking or playback position.** A playback can be paused and resumed but not moved to a time.
- **MP3 loops are not sample-tight.** MP3 has encoder delay and no sample index, so use Ogg, Opus or FLAC where a loop must be seamless.
- **Audio output cannot be chosen.** OpenAL uses the system default device, and it is not re-opened if that changes.
