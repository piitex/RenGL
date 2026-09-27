# Core

[Documentation](../README.md) › Core

The classes every program touches, in the order you meet them.

| Page | What it covers |
|---|---|
| [Engine](engine.md) | `Engine.start`, the render thread, and what happens every frame |
| [Window and WindowOptions](window.md) | Top-level containers, the window background, focus, popups and modals |
| [Element](element.md) | Position, size, events, tooltips, context menus, styling, scale and transitions. Works on everything drawable |
| [Overlay](overlay.md) | The widget base class, the overlay family, and patterns every widget shares |
| [Snapshots](snapshots.md) | Capture a container or the window as an image, for example save slot thumbnails |
| [Scheduler](scheduler.md) | Timers, `runLater`, and background work with results delivered on the render thread |
| [Loading](loading.md) | `Loader`: background loading in named steps, with progress for a loading screen |

Read them in that order: an [`Engine`](engine.md) runs a [`Window`](window.md), which holds [`Container`](../containers/container.md)s, which hold [`Element`](element.md)s.
For the big picture see [Architecture](../architecture.md).

Related: [Containers](../containers/README.md), [Layouts](../layouts/README.md), [Overlay catalog](../overlays/README.md).
