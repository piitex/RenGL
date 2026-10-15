# RenGL

RenGL is a Java toolkit for building desktop apps and 2D games with a UI that looks like you designed it.
It opens a window with GLFW and draws everything with Skia (through Skiko): Metal on macOS, OpenGL on Windows and Linux,
and Vulkan if you ask for it. Widgets are drawn by RenGL itself rather than wrapped from a native widget set, and there
is no CSS engine. Everything is styled from Java.

It started as a rewrite of my JavaFX toolkit, RenEngine, and grew into more than that: a widget library, a 2D game
framework, audio and video playback, and an optional 3D layer.

**Mission**: RenGL brings creativity and design to everyday applications. Boring, simplistic designs don't have to be
the normal.

<p align="center">
  <img src="docs/images/hero.gif" alt="A glass-style RenGL UI over a flowing shader background, with live sliders, toggles and charts" width="100%">
</p>

## See it move

**Styled from Java.** Everything above is RenGL: a custom SkSL shader as the background, translucent panels with gradient
borders, gradient text, and the stock widgets.

**A 2D game toolkit.** Thousands of pooled, batched particles, a shader-drawn sky and a scrolling city, all in a plain
window with no game engine on top.

<p align="center">
  <img src="docs/images/fireworks.gif" alt="Fireworks made of thousands of particles over a city skyline" width="100%">
</p>

**Optional 3D.** Physically based materials, image-based sky lighting, shadows, fog and dynamic lights, rendered with
Vulkan or OpenGL and shown inside the same UI. Programs that don't use it never pay for it.

<p align="center">
  <img src="docs/images/studio3d.gif" alt="A row of metal spheres and a row of glossy spheres lit by a sunset sky and three orbiting neon lights" width="100%">
</p>

```java
public class Hello {
    static void main() {
        Window window = new Window(new WindowOptions("Hello RenGL").setDimensions(1280, 720));

        Container root = new Container(1280, 720);
        window.addContainer(root);

        TextOverlay title = new TextOverlay("Hello, RenGL!", 48f, Color.BLACK);
        title.setPosition(50, 50);
        root.addElement(title);

        new Engine().start(window); // blocks until the window closes
    }
}
```

## What's in it

| | |
|---|---|
| **UI toolkit** | Text, text fields and areas, a rich text editor with offline spell checking, buttons, sliders, dropdowns, color and date pickers, trees, tabs, menus, tooltips, modals, scroll and split containers, and more. [Every widget](docs/overlays/README.md) has its own page. |
| **Charts** | Line, bar, pie and donut [charts](docs/charts/README.md) drawn by the engine: live data that eases into place, tooltips, a legend that hides series, and the theme's colors. |
| **Layouts** | Vertical, horizontal, grid, stack, flow and dock layouts, with relative sizing and weights. A screen can also be loaded from JSON. |
| **Resizable and draggable containers** | Containers that follow the window, scale their contents and fonts, and can be dragged or resized by the user. |
| **Styling** | Backgrounds, borders, gradients, animated paints, themes, custom fonts, SVG icons, and animated backgrounds behind elements. |
| **Animation** | Transitions, tweens and sequences. |
| **2D game framework** | Scenes and transitions, entities and components loaded from scene files, a fixed-step game loop, cameras, sprites and atlases, Tiled tile maps, sprite animation, particles, SkSL shaders and post effects, geometry and raycasts, spatial grids, pools, event bus and debug drawing. |
| **Audio** | OpenAL playback of Ogg Vorbis, Opus, FLAC, MP3 and WAV, with channels, fades, streamed music and 3D positioned sound. |
| **Video** | Streamed playback of large files with synced sound. |
| **3D (optional)** | PBR materials, shadows, glTF/OBJ/FBX models, skeletal animation, instancing and a built-in physics engine, shown in a viewport or behind the whole UI. Programs that don't use it never pay for it. |
| **App plumbing** | Multiple windows, file dialogs, a scheduler, background loading, downloads, config files, an input action map with rebinding, and an asset manager. |

## Requirements

- **JDK 25**
- **Maven** (a wrapper is included: `./mvnw`)
- A GPU that supports **Metal** (macOS) or **OpenGL 3.3** (Windows, Linux). Vulkan 1.1 is an optional alternative.

Native libraries (LWJGL and Skiko) are selected automatically for Windows, Linux and macOS on x86-64 and arm64.

## Building and running

```bash
./mvnw compile
```

Run any class that calls `Engine.start`. On macOS the JVM needs `-XstartOnFirstThread`, since windowing has to happen on
the first thread:

```text
-XstartOnFirstThread --enable-native-access=ALL-UNNAMED
```

The [demos](src/main/java/me/piitex/examples/demo) are the quickest way to see what it can do. Start with `Demo`, then try
`FireworksDemo`, `Studio3DDemo`, `GameLoopDemo`, `SpriteDemo`, `ResizableContainerDemo`, `Scene3DDemo`, `HdrSkyDemo`, `PhysicsDemo` or `VideoDemo`.

## Documentation

Everything is documented in [docs](docs/README.md), one page per class or feature, with runnable snippets.

- [Getting started](docs/getting-started.md): your first window
- [Architecture](docs/architecture.md): threads, the frame loop, coordinates and how events reach elements
- [Known limitations](docs/reference/known-limitations.md): what isn't done yet

## The shape of the API

```text
Engine ──drives──▶ Window ──holds──▶ Container ──holds──▶ Element / Overlay
                                        ▲
                       Layout, ScrollContainer, SplitContainer and
                       ModalContainer are all just Containers
```

- `Window` is an OS window. `WindowOptions` holds its settings and stays live: calling `setWidth`, `setTitle`,
  `setFullscreen`, `setMaximized` or even `setRenderer` on a running window changes the window.
- Every window holds `Container`s, and a container holds elements. A layout is just a container that positions its
  children.
- Widgets are `Overlay`s, a kind of `Element`. Write your own by subclassing either.
- Callbacks run on the render thread. Keep them short.

## RenGL vs RenEngine

RenEngine was built on JavaFX, which restricts a lot of styling to CSS. That frustration is why RenGL exists. RenGL
trades some of JavaFX's heavier components for full control over how everything looks, plus things
JavaFX doesn't offer: draggable, resizable and reorderable containers, animated backgrounds, and a game and 3D
toolkit built on the same windows. The class structure (Window, Container, Layout, Element) will feel familiar if you
used RenEngine, except that a layout is now a subclass of `Container` and there is no `Application` or `FXLoad` class.

## Project status

RenGL is actively developed and used for my own apps and games. It is not on Maven Central yet, and the API can still
change. Development happens mostly on macOS (Metal), so Windows and Linux are less tested, and Vulkan has only been run
on macOS. The video player decodes H.264, HEVC and AAC on macOS only. Elsewhere it plays the formats RenGL decodes
itself. See [known limitations](docs/reference/known-limitations.md) for the full list.

There is a test suite of several hundred tests, run with `./mvnw test`.

## AI transparency

OpenGL, Skia and Metal are hard to learn, and AI was used to speed up development: for functions, ideas and structures.
Everything is manually tested and validated.

AI use is documented in the source code. Four annotations in `me.piitex.engine.utils.flags` (`@GeneratedClass`,
`@GeneratedFragments`, `@GeneratedFunction` and `@Verified`) mark where it was used and whether a person reviewed it.
For any future maintainer, the same rules apply: AI-generated content must be reviewed and verified before it can be
merged, and if generated code is not accepted it needs to be reviewed again.
