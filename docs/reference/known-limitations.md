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
- **No FPS counter.** The engine does not expose the measured frame rate or frame time.
- **The OS can open the window smaller than requested** (for example a 720-tall window on a small screen).
  `WindowOptions` always holds the real size, and the engine logs a warning, but containers keep their size unless
  they are a [`ResizableContainer`](../containers/resizable-container.md).
- **Contents do not reflow on resize,** except inside a `ResizableContainer`. Other containers keep their size.
  Resize them yourself from [`window.onResize`](../core/window.md#resize-event) if you want them to follow the window.
- **One engine, one window per process.** Several engine helpers are static.
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
- **Layouts do not size themselves** to their children, and positions are rewritten every frame. A child of a
  layout cannot be positioned or dragged by hand, because the layout puts it back.
- **[`SplitContainer`](../containers/split-container.md) holds exactly two panes** (nest for more).
- **[`ScrollContainer`](../containers/scroll-container.md) measures only direct children** for its scroll range, so a layout inside it needs an
  explicit content-height.
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
  text area, dropdown and slider. Tooltip text has a fixed size, and icons and images scale as pictures (an
  `IconOverlay` is rasterized once, so a big enlargement looks soft).
- **No visible grip by default.** Only the cursor changes. `setShowResizeHandles(true)` draws the zones.

## Styling

- **Gradient borders are not drawn.** A gradient border color is silently skipped. Only a plain [`Color`](../styling/colors-and-paints.md#color) border
  renders.
- **Some parts accept only `Color`, not [`Paint`](../styling/colors-and-paints.md):** caret and selection colors, scrollbars, slider/progress/
  checkbox/radio parts, tooltip colors.
- **No unpin on elements.** Once an element's themed property is set explicitly it is pinned for the life of that
  element. The window background is the exception: `Window.followThemeBackground()` unpins it.
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

- **`onMouseClick` fires on press, not release,** for any mouse button. Only one listener runs per press: the
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
- **The resize cursors come from GLFW's standard cursor set.** They showed correctly on macOS. A platform or GLFW
  build without one falls back to the arrow, and the handles still work.

## Not implemented yet

- **Size measurement.** Elements have no preferred size, so a layout cannot size itself to its children, and a
  `ScrollContainer` cannot measure a layout inside it.
- **Reflow other than `ResizableContainer`.** There are no anchors or percent sizing for ordinary containers.

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
- **[`IconOverlay`](../overlays/icon-overlay.md) is rasterized once** at its construction size. Resizing it later stretches the raster.
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
