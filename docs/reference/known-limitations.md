# Known limitations

[Documentation](../README.md) › [Reference](README.md)

Things that are not implemented, or that behave in a way that may surprise you. Each is verified against the
source. If you hit something not listed here, please add it.

## Window and engine

- **Window options other than `setVsync` and `setMaxFPS` apply once,** at window creation. Changing them later does nothing.
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
  Resize them yourself in an `onUpdate` hook if you want them to follow the window.
- **One engine, one window per process.** Several engine helpers are static.
- **No public stop method.** `Engine.start` returns when the OS window closes.
- **Callbacks run on the render thread.** There is no built-in way to marshal work from another thread. Use a flag
  or queue read from an `onUpdate` hook.

## Containers and layouts

- **`getX()`/`getY()` are relative to the container,** and mouse positions (drag callbacks, click events,
  `MouseTracker`) are window coordinates. Convert with `toLocalX`/`toLocalY` or `getAbsoluteX`/`getAbsoluteY`. Reading
  `getAbsoluteX()` walks up the containers each call, so it is not free in a hot loop.
- **Popups are top-level.** A dropdown list or spelling menu is positioned in window coordinates, so it does not
  move with the element that opened it if that element moves while the popup is open.
- **`removeElement` leaves gaps** in the index space, and does not guard `SplitContainer`'s divider. Removing the
  divider breaks the split.
- **The topmost container under the mouse takes the click, even where it is empty.** A transparent full-window
  container drawn above another container swallows every press meant for the one below. Add full-window roots first.
- **Layouts do not size themselves** to their children, and positions are rewritten every frame. A child of a
  layout cannot be positioned or dragged by hand, because the layout puts it back.
- **[`SplitContainer`](../containers/split-container.md) holds exactly two panes** (nest for more).
- **[`ScrollContainer`](../containers/scroll-container.md) measures only direct children** for its scroll range, so a layout inside it needs an
  explicit content-height.
- **Scrollbars overlay the content** on the right/bottom edge instead of reserving a gutter, in both
  `ScrollContainer` and [`TextAreaOverlay`](../overlays/text-area-overlay.md).
- **Clipping is rectangular.** Children of a rounded container are clipped to its bounding rectangle, not to the
  rounded shape.

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
- **No unpin.** Once a themed property is set explicitly it is pinned for the life of that element. This
  includes the window background (`Window.setBackgroundColor`), which otherwise follows the theme.
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

- **`onMouseClick` fires on press, not release,** for any mouse button, and only for the topmost element (no bubbling).
- **One listener per hook.** `onMouseClick`, `onUpdate`, `onRender` and most `onXxx` callbacks replace the previous
  listener rather than adding to it.
- **[`RadioButtonOverlay`](../overlays/radio-button-overlay.md) has no silent select.** `select()` always fires the group's `onChange`.
- **`Element.getZ()` is reserved** and unused by rendering or hit-testing.
- **A drag keeps the cursor shape of the dragged element** for its whole duration, including when it is a text field
  selecting text outside its own box.
- **A press passes up only through containers.** A container that does not want the drag hands it to its parent. A
  non-container that is not `Draggable`, such as a button, keeps the press and nothing above it is dragged.
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
