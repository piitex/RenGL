# Changelog

## Unreleased

- Keyboard use and accessibility. `ButtonOverlay`, `CheckBoxOverlay`, `RadioButtonOverlay`, `LinkOverlay` and `SliderOverlay` are now Tab stops: Space or Enter (a gamepad's A) uses them, the arrows, Home and End move a slider, and a focus ring shows where focus is when Tab brought it there. They do not take focus from a click and leave every other key to the game, so a button on a HUD does not break WASD (`Activatable`, and `Focusable.grabsKeyboard()` and `handlesKey()`). Tab scrolls a `ScrollContainer` so the focused control is in view (`ScrollContainer.scrollIntoView`). Apps that took Tab for their own use are unaffected while nothing has focus; one with buttons now gets those as Tab stops.
- Every element has an accessible role, name, description and value (`Element.setAccessibleName`, `setLabelledBy`, `setAccessibleRole`, `setAccessibleHidden`), and `AccessibilityTree.snapshot(window)` reads a window into a tree of `AccessibleNode`s. `Window.setAccessibilityHost` takes an `AccessibilityHost` that hears focus moves, value changes and `Window.announce(...)`; toasts announce themselves.
- `rengl-accessibility`: self-voicing, `Accessibility.enable(window)` speaks each control as Tab lands on it, through SAPI on Windows, `say` on macOS and `spd-say` or `espeak-ng` on Linux. Not a connection to NVDA, JAWS or VoiceOver. See [Accessibility](docs/input/accessibility.md).

## 0.1.1

- The natives are now `rengl-natives-<platform>` artifacts (`rengl-natives-windows`, `-windows-arm64`, `-linux`, `-linux-arm64`, `-macos`, `-macos-arm64`): a jar with the Skiko runtime and a pom that depends on LWJGL's natives on Maven Central. The per-module and `RenGL` `natives-<platform>` classifier jars are gone, which cuts the Central upload from about 260 MB to about 60 MB. Depend on `rengl-natives-<platform>` where you depended on `RenGL:natives-<platform>`. See [Modules](docs/modules.md#native-libraries).
- `rengl-physx`: NVIDIA PhysX 5 as the solver behind `PhysicsWorld`, switched on with `PhysX.install()` or `world.setBackend(new PhysXBackend())`. Bodies, joints, characters, queries and listeners stay as they are. See [PhysX](docs/3d/physx.md).
- `PhysicsBackend` and `PhysicsBridge` in `rengl-3d`, the interface a solver other than the built-in one plugs into.
- Rich text areas no longer run past the edge on a line that needs shaping (non-Latin text mixed with Latin): every piece of such a line is now shaped, so the pieces add up to the width the line was measured at.
- `PhysicsDemo` and `FastBodiesDemo` use the PhysX backend when it is available.

## 0.1.0

First public release.

- Window, UI toolkit, input and 2D game framework (`rengl-core`).
- Optional modules: audio, video, web views, Vulkan, 3D (including ray tracing), upscaling bindings (FSR, DLSS, MetalFX), spell checking, tile maps, entities, charts, downloads and UI files.
- `RenGL`, the all-in-one artifact that pulls in the modules above except the vendor upscalers, and `rengl-bom` for matching versions.
- `rengl-packager`, which turns an app into a Windows, macOS or Linux installer.
- On Maven Central as `io.github.piitex`. Natives jars for Windows, Linux and macOS (x86-64 and arm64) are published with `RenGL`.
- Released under the RenGL Open-Source-Game License 1.0. See [LICENSE](LICENSE).

Known gaps are listed in [known limitations](docs/reference/known-limitations.md), and the configurations nobody has run yet are tracked in the issue tracker.
