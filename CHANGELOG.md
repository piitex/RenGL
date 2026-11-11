# Changelog

## Unreleased

- `rengl-physx`: NVIDIA PhysX 5 as the solver behind `PhysicsWorld`, switched on with `PhysX.install()` or `world.setBackend(new PhysXBackend())`. Bodies, joints, characters, queries and listeners stay as they are. See [PhysX](docs/3d/physx.md).
- `PhysicsBackend` and `PhysicsBridge` in `rengl-3d`, the interface a solver other than the built-in one plugs into.

## 0.1.0

First public release.

- Window, UI toolkit, input and 2D game framework (`rengl-core`).
- Optional modules: audio, video, web views, Vulkan, 3D (including ray tracing), upscaling bindings (FSR, DLSS, MetalFX), spell checking, tile maps, entities, charts, downloads and UI files.
- `RenGL`, the all-in-one artifact that pulls in the modules above except the vendor upscalers, and `rengl-bom` for matching versions.
- `rengl-packager`, which turns an app into a Windows, macOS or Linux installer.
- On Maven Central as `io.github.piitex`. Natives jars for Windows, Linux and macOS (x86-64 and arm64) are published with `RenGL`.
- Released under the RenGL Open-Source-Game License 1.0. See [LICENSE](LICENSE).

Known gaps are listed in [known limitations](docs/reference/known-limitations.md), and the configurations nobody has run yet are tracked in the issue tracker.
