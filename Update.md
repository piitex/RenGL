# Update
List of things that needs adding or changing.

## Found while building OpenZip (an archive utility on RenGL)

OpenZip keeps this list growing as it hits more. Items done are listed under **Done** with where to read about them;
what is left says why.

### Done

- **Files dropped on the window from the OS.** `Window.onFileDrop(Consumer<List<Path>>)`
  ([window.md](docs/core/window.md#files-dropped-on-the-window)). GLFW has no drag-hover event, so there is no
  "a drag is over the window" feedback; show a standing hint instead.
- **Opening the app with a file from the OS.** `Engine.onOpenFiles(Consumer<List<Path>>)` and
  `Engine.openFilesFromArguments(args)` ([engine.md](docs/core/engine.md#files-the-os-asks-the-app-to-open)). On macOS
  this was checked with a jpackage app and a `.zip` association: the file arrives as an Apple event, never in `args`,
  on cold start and while running. Windows and Linux pass arguments; that path is tested but not on those systems.
- **A table / multi-column list.** `TableView` ([table-view.md](docs/containers/table-view.md)): columns, click-to-sort
  headers, resizable widths, icons, multi-select, horizontal scroll. Demo: `TableDemo`.
- **Multi-select in `TreeView`.** `setMultiSelect`, `getSelectedItems()`, Shift/Cmd-click ranges
  ([tree-view.md](docs/containers/tree-view.md#selecting-several-rows)).
- **Open a file or folder with the OS, and reveal it.** `Browser.open(Path)` and `Browser.reveal(Path)`
  ([browser.md](docs/reference/browser.md#files-and-folders)). `open` refuses programs (`.exe`, `.sh`, `.app`, ...).
- **Per-row checkboxes in `TreeView`.** `setCheckBoxes`, `setCascadeChecks`, `getCheckedItems()`
  ([tree-view.md](docs/containers/tree-view.md#check-boxes)).
- **A packaging guide.** [packaging.md](docs/reference/packaging.md): jpackage flags, icon, version, file types, macOS
  signing and notarization. Only the macOS app-image and file-association part was run.

### Still wanted

- **A title and parent window for `FileDialog`.** The native dialog library (nativefiledialog-extended) has no title or
  prompt option, so this needs either an upstream change or direct AppKit / Win32 calls. A parent window can be passed,
  but its effect was not checked. Listed in [known limitations](docs/reference/known-limitations.md#file-dialogs).
- **Dragging rows out of the app into Finder/Explorer** (a native drag source), to extract by dragging.
- **Taskbar / Dock progress and a "done" notification** for long jobs that finish while the window is in the background.
- **Windows and Linux check of `onOpenFiles` and `Browser.reveal`.** Written from each platform's documented behavior and
  covered by unit tests of the commands, not run on those systems.

### Found while adding a text preview to OpenZip

- **Inline Markdown in `MarkdownFormatPreset`.** Bold, italic, inline code and links show as plain text (the marks are
  removed), and tables and quotes read as paragraphs. A README previewed in OpenZip loses its emphasis. A block has one
  `TextStyle`, so this needs runs of styled text inside a `FormattedBlock`, and clickable links (through
  `Browser.open`, which only takes http/https/mailto) if links are to work.
- **Done: open a file in the system's text editor.** `Browser.openAsText(Path)` ([browser.md](docs/reference/browser.md#files-and-folders)):
  `open -t` (macOS), `notepad.exe` (Windows), `gtk-launch` with the `text/plain` default from `xdg-mime` (Linux). Only the
  commands are unit tested here; none was started.

### Found while redoing OpenZip's UI (to CCA's standard)

All five are done.

- **An accent token in `Theme`.** `getAccent()` and `getAccentTint()`, also `setAccent` / `setAccentTint` on `BaseTheme` and
  `"accent"` / `"accentTint"` in a theme file ([theming.md](docs/styling/theming.md#the-accent)). Slider fill and thumb,
  progress fill, checkbox tick, radio dot and the toggle's on side follow the accent; the selected row of `TreeView` and
  `TableView` follows the tint. A control given its own color keeps it. Both are default methods, so an existing `Theme`
  implementation still compiles and gets the selection color. **This changes the default look:** sliders, progress bars,
  ticks and dots used to be gray or black in the light and dark themes and are now the accent blue (the crimson theme's
  were already red). Light's accent is a stronger blue than its old selection color, so its toggles are a little deeper.
- **A button with an icon and a label.** `ButtonOverlay.setIcon(IconAsset)`, with `setIconSize`, `setIconGap` and
  `setIconAfterText`; no label gives an icon-only button. The icon takes the label's color and is part of the button, so
  there is one click handler ([button-overlay.md](docs/overlays/button-overlay.md#an-icon-beside-the-label)).
- **A visible disabled state.** `ButtonOverlay.setDisabled(true)`: faded (`setDisabledOpacity`, 45% by default), no hover
  color, a "not allowed" cursor, `onAction` does not fire and `onDisabledClick(Runnable)` does, the tooltip still shows
  ([button-overlay.md](docs/overlays/button-overlay.md#disabled)). Every element has it now (`Element.setDisabled`), a
  disabled container disables what is inside it ([element.md](docs/core/element.md#disabled)).
- **Ellipsis for text that doesn't fit.** `TextOverlay.setMaxWidth(double)` (an overlay that sizes itself, capped),
  `setEllipsize(true)` (a box of a set width) and `setEllipsizeMiddle(true)` to keep a file name's extension; `ButtonOverlay`
  honors `setEllipsize` too. `TextFit.ellipsize(...)` does it for text you draw yourself
  ([text-overlay.md](docs/overlays/text-overlay.md#text-that-does-not-fit)). It also fixes a crash: the old cut in
  `TabContainer` could split an emoji in half and bring Skia down, so `TabContainer` now uses `TextFit`.
- **`insertElement(child, index)`.** Shifts the children from that index up by one instead of replacing the one there;
  `addElement(child, index)` still replaces. Works in layouts (they re-lay-out); a split and a tab container throw
  ([container.md](docs/containers/container.md#adding-children)).

### Found while finishing OpenZip (passwords, light theme, packaging)

- **Done: the system appearance and a change event.** `Engine.getSystemAppearance()` and
  `Engine.onSystemAppearanceChange(listener)` (on `Engine`, like `onOpenFiles`, not on `Window`: it is the OS's setting,
  not a window's) ([engine.md](docs/core/engine.md#the-systems-light-or-dark-setting)). Read with the same three commands
  OpenZip used, in the engine, so the app needs none. There is no shared change notification, so a listener polls every
  two seconds. Only the macOS command was run; Windows and Linux are tested against sample output.
- **Done: recoloring a whole UI in one go.** `Element.onTheme(theme -> ...)` runs now and on every
  `ThemeManager.setCurrent`, so a hand-styled element sets its colors again for the new theme with no rebuild
  ([theming.md](docs/styling/theming.md#restyling-a-hand-styled-element)). With the accent token and `Container.useTheme()`
  (panels already follow the container tokens) that covers it. I did not add a second panel style: say which panel
  looks (a sidebar, a toolbar band) you need as theme tokens and I will.
- **Partly done: a smaller dependency set.** The stray `junit:3.8.2` is gone: `jlayer` pulled it in at compile scope for its
  own tests, and the jar does not reference it, so the pom now excludes it. The rest is not done, because making
  dependencies `optional` changes what every consumer's classpath gets and the other projects built on RenGL may rely on
  them. What the engine's source never imports (so these are the candidates): `bgfx, egl, fmod, freetype, harfbuzz, hwloc,
  jawt, jemalloc, ktx, llvm, lmdb, lz4, meshoptimizer, mimalloc, msdfgen, nanovg, nuklear, odbc, opencl, opengles, openxr,
  par, renderdoc, rpmalloc, sdl, spng, tinyexr, tinyfd, xxhash, yoga, zstd` (jars and macOS natives). `lucene` is used by
  the spell checker. In use: `lwjgl, glfw, opengl, vulkan, openal, vma, spvc, shaderc, stb, nfd, assimp, opus`.
- **`jpackage` file types for double extensions.** The OS matches the last extension, so `.tar.gz` can't be registered
  on its own. Nothing to fix in RenGL; noted in [packaging/README.md](../OpenZip/packaging/README.md).

### Found while moving OpenZip to `TableView`, icon buttons and the accent token

All of the items above worked as documented. Small things the first real use turned up, all done:

- **`TableView` header and icon colors.** `setHeaderTextColor(Paint)` (null follows the theme again) and a per-column
  `Column.setIconColorProvider(item -> Color)` ([table-view.md](docs/containers/table-view.md)). The header band is still the
  text color at 8%.
- **Scroll position on `setItems`.** It goes back to the top, as when a folder is opened. `refresh()` keeps the offset
  (clamped). Both now said in table-view.md.
- **Folders first under a descending sort.** `setSortGroup(Predicate<T>)`: matching rows stay on top whatever the
  direction, each group sorted on its own; without a sort the groups still apply.
- **Hover and click on a `TextOverlay`.** `setHoverColor(Paint)` and `setUnderlineOnHover(boolean)`; with `setCursorMode`
  and `onMouseClick` that is a breadcrumb ([text-overlay.md](docs/overlays/text-overlay.md#a-link-or-a-breadcrumb)). The
  hover color was already `getStyling().setTextHoverColor`; this is the short way. No separate `LinkOverlay`.
