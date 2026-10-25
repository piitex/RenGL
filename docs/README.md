# RenGL Documentation

RenGL is a Java window toolkit built on GLFW and Skia (through Skiko), drawing with Metal on macOS and OpenGL on
Windows and Linux, or Vulkan if you ask for it. It draws its own widgets instead of wrapping a native widget set, and
it has no CSS engine. Everything is styled from Java. An optional [3D layer](3d/README.md) adds OpenGL and Vulkan 3D to
the same windows; programs that don't use it never pay for it.

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
| [Modules](modules.md) | The Maven artifacts, what each one brings, and which to depend on |
| [Vulkan and 3D status](3d-status.md) | Progress, known issues and next steps for the Vulkan backend and the optional 3D layer |
| **[Core](core/README.md)** | Engine, Window, Element, Overlay |
| [Engine](core/engine.md) | Starting the engine and what it does every frame |
| [Window](core/window.md) | `Window`, `WindowOptions`, popups, modals, focus |
| [Element](core/element.md) | The base class of everything drawable: position, events, tooltips, scale, transitions |
| [Overlay](core/overlay.md) | The base class of every widget, and how to write your own |
| [Snapshots](core/snapshots.md) | Capture a container or the window as an image (PNG, or an `ImageOverlay`) |
| [Rendering APIs](core/rendering-apis.md) | `RenderAPI`: OpenGL, Metal or Vulkan per window, fallbacks, and the Vulkan debugging switches |
| [Scheduler](core/scheduler.md) | Timers, `runLater`, and background work with results delivered on the render thread |
| [Loading](core/loading.md) | `Loader`: background loading in named steps, with progress for a loading screen |
| **[Containers](containers/README.md)** | Container, ScrollContainer, ResizableContainer, ModalContainer, ReorderableGrid, TreeView |
| [Container](containers/container.md) | Holding children, dragging, collision, clipping |
| [ScrollContainer](containers/scroll-container.md) | Scrollable, clipped content |
| [ResizableContainer](containers/resizable-container.md) | Follows the window, scales its contents, drag-to-resize |
| [ModalContainer](containers/modal-container.md) | Dialogs that block the rest of the UI |
| [ReorderableGrid](containers/reorderable-grid.md) | Drag-to-rearrange grid of items |
| [TreeView](containers/tree-view.md) | A tree of items, like an editor's hierarchy or a file browser |
| [TableView](containers/table-view.md) | Rows and columns with a fixed header, click-to-sort and resizable columns, check boxes and cells that edit in place |
| **[Layouts](layouts/README.md)** | Automatic positioning |
| [Layouts overview](layouts/README.md) | How automatic positioning works |
| [Layout files](containers/layout-files.md) | Load a screen designed in the editor from JSON |
| [VerticalLayout](layouts/vertical-layout.md) · [HorizontalLayout](layouts/horizontal-layout.md) · [GridLayout](layouts/grid-layout.md) · [StackLayout](layouts/stack-layout.md) · [FlowLayout](layouts/flow-layout.md) · [DockLayout](layouts/dock-layout.md) · [SplitContainer](containers/split-container.md) · [TabContainer](containers/tab-container.md) | One page per layout |
| **[Overlays](overlays/README.md)** (widgets) | Every widget, one page each |
| [Overlay catalog](overlays/README.md) | Every widget at a glance |
| Text | [TextOverlay](overlays/text-overlay.md) · [TextFlowOverlay](overlays/text-flow-overlay.md) · [TextFieldOverlay](overlays/text-field-overlay.md) · [TextAreaOverlay](overlays/text-area-overlay.md) · [RichTextAreaOverlay](overlays/rich-text-area-overlay.md) · [FormattedTextOverlay](overlays/formatted-text-overlay.md) |
| Controls | [ButtonOverlay](overlays/button-overlay.md) · [LinkOverlay](overlays/link-overlay.md) · [CheckBoxOverlay](overlays/checkbox-overlay.md) · [RadioButtonOverlay](overlays/radio-button-overlay.md) · [DropdownOverlay](overlays/dropdown-overlay.md) · [SliderOverlay](overlays/slider-overlay.md) · [SpinnerOverlay](overlays/spinner-overlay.md) · [ToggleSwitchOverlay](overlays/toggle-switch-overlay.md) · [ColorPickerOverlay](overlays/color-picker-overlay.md) · [DateTimePickerOverlay](overlays/date-time-picker-overlay.md) |
| Feedback | [ProgressBarOverlay](overlays/progress-bar-overlay.md) · [LoadingSpinnerOverlay](overlays/loading-spinner-overlay.md) · [SeparatorOverlay](overlays/separator-overlay.md) |
| Images | [ImageOverlay](overlays/image-overlay.md) · [IconOverlay](overlays/icon-overlay.md) |
| **[Charts](charts/README.md)** | Line, bar, scatter, pie and donut charts drawn by the engine, with live data |
| [Line chart](charts/line-chart.md) | `LineChartOverlay`: lines, smooth curves, filled areas, gaps, a log axis, a time axis |
| [Bar chart](charts/bar-chart.md) | `BarChartOverlay`: grouped, stacked and horizontal bars, on a log axis if you like |
| [Scatter chart](charts/scatter-chart.md) | `ScatterChartOverlay`: points and bubbles on two numeric axes |
| [Pie chart](charts/pie-chart.md) | `PieChartOverlay`: pies and donuts |
| **[Game](game/README.md)** | Scenes and the game loop |
| [Scenes](game/scenes.md) | `Scene` and `SceneManager`: menus, gameplay, pause menus, and moving between them |
| [Entities and scene files](game/entities.md) | `EntityScene`, entities, components and `@Property`: levels as JSON files that load into running scenes |
| [Game time](game/game-time.md) | Fixed-step updates, time scale, pausing, smooth drawing between steps |
| [Scene transitions](game/transitions.md) | Fades, slides, wipes and custom effects when the scenes change |
| [Geometry and raycasts](game/geometry.md) | `Vec2`, `Rect`, `Circle`, `Polygon`: overlap tests, raycasts, pushing apart, moving and sliding among walls, and number, angle and animation helpers |
| [Spatial grid](game/spatial-grid.md) | `SpatialGrid`: find the things near a place quickly: in an area, within a radius, the nearest |
| [Random numbers](game/random.md) | `Rng`: ranges, odds, picks, weighted picks and points in a circle, from a seed |
| [Events](game/events.md) | `EventBus`: tell other parts of the game what happened without knowing about them |
| [Object pools](game/pooling.md) | `Pool`: reuse bullets, particles and other short-lived objects instead of creating new ones |
| **[Graphics](graphics/README.md)** | Drawing a 2D game world |
| [Camera](graphics/camera.md) | `Camera`: position, zoom, rotation, viewports, following, shake, and mouse to world coordinates |
| [Sprites](graphics/sprites.md) | `Texture`, `TextureRegion`, `Sprite`, and `SpriteBatch` for drawing many in order and in few calls |
| [Texture atlases](graphics/atlases.md) | `TextureAtlas`: packed sprite sheets from TexturePacker, Aseprite and similar tools |
| [Tile maps](graphics/tile-maps.md) | `TileMap`: levels from the Tiled map editor: drawing, collision shapes, objects and properties |
| [Sprite animation](graphics/sprite-animation.md) | `Animation` and `AnimationPlayer`: frame animation, loop modes, frame events |
| [Particles](graphics/particles.md) | `ParticleSystem`, `ParticleEffect` and `ParticleEmitter`: sparks, smoke and debris, in bursts or a steady stream |
| [Shaders and post effects](graphics/shaders.md) | `ShaderEffect`: SkSL shaders to fill shapes or post-process a scene or element |
| [Debug drawing](graphics/debug-drawing.md) | `DebugDraw`: hit boxes, rays and labels drawn in world space |
| **[3D](3d/README.md)** (optional) | Showing 3D with OpenGL or Vulkan inside the UI |
| [Viewport3D](3d/viewport-3d.md) | The element that shows 3D in a rectangle of the UI: `Content`, input, resolution, lifetime |
| [Background3D](3d/background-3d.md) | 3D behind the whole window, with the UI drawn over it |
| [Scenes](3d/scenes.md) | `Scene3D`, `Node3D`, `Mesh`, `Material` (classic and PBR), `Light`, `Camera`, `OrbitControls`, picking and the math types |
| [Scene files](3d/scene-files.md) | `Scene3DFile`: a scene built in the editor, saved as JSON and loaded by the game |
| [Lighting](3d/lighting.md) | How lights add up, shadows, and the skybox |
| [Models](3d/models.md) | `Model.load`: glTF, GLB, OBJ, FBX and Collada files |
| [Animation](3d/animation.md) | Clips, `AnimationPlayer` and skinned meshes |
| [Physics](3d/physics.md) | Rigid bodies: gravity, forces, inertia, collisions, friction and bounce, raycasts and triggers, mesh and hull colliders, joints, a character controller |
| [Instancing](3d/instancing.md) | Thousands of copies of one mesh in a single draw |
| [Custom rendering](3d/custom-rendering.md) | Your own 3D with `GpuDevice`, pipelines and one GLSL shader for both APIs |
| **[Audio](audio/README.md)** | Sound effects, streamed music, volume channels |
| [Sound](audio/sound.md) | Short effects held in memory, with random pitch and limits on how often they play |
| [Music](audio/music.md) | Streamed tracks, looping, crossfades |
| [Playback](audio/playback.md) | Volume, pitch, pan, pause, fades and callbacks on a playing sound |
| [3D audio](audio/spatial-audio.md) | Listener, positioned sounds, distance falloff, Doppler, and 2D hearing in one call |
| **[Video](video/README.md)** | `VideoOverlay` and `VideoPlayer`: streamed playback of large files with synced sound |
| **[Styling](styling/README.md)** | Colors, box styling, themes, backgrounds, fonts, images |
| [Colors and paints](styling/colors-and-paints.md) | `Color`, `RainbowColor` (RGB cycling), gradients, animated paints |
| [Styling](styling/styling.md) | Background, border, hover, and corner radius on every element |
| [Theming](styling/theming.md) | `Theme`, `ThemeManager`, and the "pinned" rule |
| [Backgrounds](styling/backgrounds.md) | Constellation, electric-node, and dot-grid effects behind an element |
| [Fonts](styling/fonts.md) | `Font` and custom fonts |
| [Images and icons](styling/images-and-icons.md) | `ImageLoader`, `IconCache`, the bundled Feather set |
| **[Animation](animation/transitions.md)** | `Transition`, fades, scaling, easing, custom transitions |
| [Tweens and sequences](animation/tweens-and-sequences.md) | `Tween` any value, and `Sequence` steps: "wait 2 seconds, then..." |
| **[Input](input/README.md)** | Clicks, drags, scrolling, focus, cursors, tooltips, menus, game controls |
| [Input and focus](input/input-and-focus.md) | Clicks, drags, scrolling, keyboard focus, cursors, clipboard |
| [Game input](input/game-input.md) | Polled keys and mouse, named actions and contexts, rebinding, saved key bindings |
| [Tooltips](input/tooltips.md) | Hover bubbles |
| [File dialogs](input/file-dialogs.md) | Native open, save and choose-folder dialogs |
| [Menus](input/menus.md) | Menu bar, popup menus and right-click context menus |
| **[Text editing](text/README.md)** | Shared text options and spell checking |
| [TextEditingOptions](text/text-editing-options.md) | Shared knobs for text fields and areas |
| [Spell checking](text/spell-checking.md) | Offline spell checking in [`RichTextAreaOverlay`](overlays/rich-text-area-overlay.md) |
| **[Reference](reference/README.md)** | Custom elements, utilities, known limitations |
| [Writing custom elements](reference/custom-elements.md) | Subclassing [`Element`](core/element.md) / [`Overlay`](core/overlay.md) |
| [Utilities](reference/utilities.md) | `FormID`, `AssetManager`, `FileUtil`, `ProcessUtil`, the AI-disclosure annotations |
| [Browser](reference/browser.md) | Open a web address in the OS default browser, or open and reveal a file or folder, with `Browser` |
| [Packaging](reference/packaging.md) | Ship an app for Windows, macOS and Linux: the RenGL packager (no jpackage, builds every platform and a Windows setup.exe or Linux .deb from one machine), or jpackage for .msi and .dmg |
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
  explicit setter, and a later theme switch will not overwrite them. `followTheme()` undoes that. See
  [Theming](styling/theming.md).
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
