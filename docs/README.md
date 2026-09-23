# RenGL Documentation

RenGL is a Java window toolkit built on GLFW, Skia (through Skiko), and Metal on macOS. It draws its
own widgets instead of wrapping a native widget set, and it has no CSS engine. Everything is styled from Java.

The API keeps the same four-level shape as RenEngine:

```text
Engine ──drives──▶ Window ──holds──▶ Container ──holds──▶ Element / Overlay
                                        ▲
                       Layout, ScrollContainer, SplitContainer and
                       ModalContainer are all just Containers
```

If you are new, read [Getting started](getting-started.md) and then [Architecture](architecture.md).

## How the docs are organized

| Section | What it covers |
|---|---|
| [Getting started](getting-started.md) | Requirements, building, running, your first window |
| [Architecture](architecture.md) | Threads, the frame loop, coordinates, how events reach elements |
| **[Core](core/README.md)** | Engine, Window, Element, Overlay |
| [Engine](core/engine.md) | Starting the engine and what it does every frame |
| [Window](core/window.md) | `Window`, `WindowOptions`, popups, modals, focus |
| [Element](core/element.md) | The base class of everything drawable: position, events, tooltips, transitions |
| [Overlay](core/overlay.md) | The base class of every widget, and how to write your own |
| [Snapshots](core/snapshots.md) | Capture a container or the window as an image (PNG, or an `ImageOverlay`) |
| [Scheduler](core/scheduler.md) | Timers, `runLater`, and background work with results delivered on the render thread |
| **[Containers](containers/README.md)** | Container, ScrollContainer, ResizableContainer, ModalContainer |
| [Container](containers/container.md) | Holding children, dragging, collision, clipping |
| [ScrollContainer](containers/scroll-container.md) | Scrollable, clipped content |
| [ResizableContainer](containers/resizable-container.md) | Follows the window, scales its contents, drag-to-resize |
| [ModalContainer](containers/modal-container.md) | Dialogs that block the rest of the UI |
| **[Layouts](layouts/README.md)** | Automatic positioning |
| [Layouts overview](layouts/README.md) | How automatic positioning works |
| [VerticalLayout](layouts/vertical-layout.md) · [HorizontalLayout](layouts/horizontal-layout.md) · [GridLayout](layouts/grid-layout.md) · [StackLayout](layouts/stack-layout.md) · [SplitContainer](containers/split-container.md) · [TabContainer](containers/tab-container.md) | One page per layout |
| **[Overlays](overlays/README.md)** (widgets) | Every widget, one page each |
| [Overlay catalog](overlays/README.md) | Every widget at a glance |
| Text | [TextOverlay](overlays/text-overlay.md) · [TextFlowOverlay](overlays/text-flow-overlay.md) · [TextFieldOverlay](overlays/text-field-overlay.md) · [TextAreaOverlay](overlays/text-area-overlay.md) · [RichTextAreaOverlay](overlays/rich-text-area-overlay.md) · [FormattedTextOverlay](overlays/formatted-text-overlay.md) |
| Controls | [ButtonOverlay](overlays/button-overlay.md) · [CheckBoxOverlay](overlays/checkbox-overlay.md) · [RadioButtonOverlay](overlays/radio-button-overlay.md) · [DropdownOverlay](overlays/dropdown-overlay.md) · [SliderOverlay](overlays/slider-overlay.md) · [SpinnerOverlay](overlays/spinner-overlay.md) · [ToggleSwitchOverlay](overlays/toggle-switch-overlay.md) · [ColorPickerOverlay](overlays/color-picker-overlay.md) · [DateTimePickerOverlay](overlays/date-time-picker-overlay.md) |
| Feedback | [ProgressBarOverlay](overlays/progress-bar-overlay.md) · [LoadingSpinnerOverlay](overlays/loading-spinner-overlay.md) · [SeparatorOverlay](overlays/separator-overlay.md) |
| Images | [ImageOverlay](overlays/image-overlay.md) · [IconOverlay](overlays/icon-overlay.md) |
| **[Audio](audio/README.md)** | Sound effects, streamed music, volume channels |
| [Sound](audio/sound.md) | Short effects held in memory |
| [Music](audio/music.md) | Streamed tracks, looping, crossfades |
| [Playback](audio/playback.md) | Volume, pitch, pan, pause, fades and callbacks on a playing sound |
| [3D audio](audio/spatial-audio.md) | Listener, positioned sounds, distance falloff, Doppler |
| **[Styling](styling/README.md)** | Colors, box styling, themes, backgrounds, fonts, images |
| [Colors and paints](styling/colors-and-paints.md) | `Color`, gradients, animated paints |
| [Styling](styling/styling.md) | Background, border, hover, and corner radius on every element |
| [Theming](styling/theming.md) | `Theme`, `ThemeManager`, and the "pinned" rule |
| [Backgrounds](styling/backgrounds.md) | Constellation and dot-grid effects behind an element |
| [Fonts](styling/fonts.md) | `Font` and custom fonts |
| [Images and icons](styling/images-and-icons.md) | `ImageLoader`, `IconCache`, the bundled Feather set |
| **[Animation](animation/transitions.md)** | `Transition`, fades, easing, custom transitions |
| **[Input](input/README.md)** | Clicks, drags, scrolling, focus, cursors, tooltips, menus |
| [Input and focus](input/input-and-focus.md) | Clicks, drags, scrolling, keyboard focus, cursors, clipboard |
| [Tooltips](input/tooltips.md) | Hover bubbles |
| [File dialogs](input/file-dialogs.md) | Native open, save and choose-folder dialogs |
| **[Text editing](text/README.md)** | Shared text options and spell checking |
| [TextEditingOptions](text/text-editing-options.md) | Shared knobs for text fields and areas |
| [Spell checking](text/spell-checking.md) | Offline spell checking in [`RichTextAreaOverlay`](overlays/rich-text-area-overlay.md) |
| **[Reference](reference/README.md)** | Custom elements, utilities, known limitations |
| [Writing custom elements](reference/custom-elements.md) | Subclassing [`Element`](core/element.md) / [`Overlay`](core/overlay.md) |
| [Utilities](reference/utilities.md) | `FormID`, `AssetManager`, the AI-disclosure annotations |
| [AppEnvironment](reference/app-environment.md) | Dev vs release file locations, chosen automatically |
| [Downloads](reference/downloads.md) | Background HTTP downloads with progress, speed and ETA displays |
| [Config](reference/config.md) | Settings files with flat and nested values |
| [Known limitations](reference/known-limitations.md) | Things that are not implemented yet |

## Conventions used in these docs

- **Coordinates** are logical points with the origin at the **top-left**. `x`/`y` on an element is its
  top-left corner. HiDPI scaling is handled inside the renderer, so you never deal with pixels.
- **Callbacks run on the render thread.** `onAction`, `onTextChanged`, `onUpdate` and the rest all fire
  from the frame loop. Keep them short, and do not block in them.
- **One listener per hook.** `onMouseClick`, `onRender`, `onUpdate` and most `onXxx` methods hold a single
  listener. Calling one again *replaces* the previous listener. Widgets such as [`ButtonOverlay`](overlays/button-overlay.md) register
  their own click handler, so read the "Gotchas" section of a widget's page before replacing one.
- **Setters that "pin".** Colors and geometry that a theme can drive are *pinned* the moment you call an
  explicit setter, and a later theme switch will not overwrite them. See [Theming](styling/theming.md).
- **Code samples** assume the imports below unless a sample shows its own. Snippets that use a bare
  `window` or `root` refer to the [`Window`](core/window.md) and full-window [`Container`](containers/container.md) from the
  [first program](getting-started.md#your-first-window).

```java
import me.piitex.engine.*;
import me.piitex.engine.ui.*;
import me.piitex.engine.ui.color.*;
import me.piitex.engine.ui.layout.*;
import me.piitex.engine.ui.overlays.*;
```
