# Modules

[Documentation](README.md)

RenGL is published as a set of Maven artifacts, so a project depends on what it uses and nothing else. A program
that only needs a window and UI does not carry OpenAL, Opus, Vulkan, Lucene, assimp or the rest. Every module keeps
the package names it always had (`me.piitex.engine.*`), so moving between the modules and the one-jar `RenGL` below
changes the `pom.xml` and no code.

All artifacts have the group id `io.github.piitex` and the same version.

| Artifact | What it adds | Third-party libraries it brings | Natives |
|---|---|---|---|
| **`rengl-core`** | Everything a program needs: `Engine`, windows, the UI toolkit, layouts, theming, input, the 2D game framework, the OpenGL and Metal renderers | LWJGL (core, GLFW, OpenGL, stb, NFD), Skiko, Kotlin runtime, log4j API | LWJGL core, GLFW, OpenGL, stb, NFD, and Skiko |
| `rengl-audio` | [Audio](audio/README.md): `Sound`, `Music`, channels, spatial sound | LWJGL OpenAL and Opus, jlayer (MP3) | OpenAL, Opus |
| `rengl-video` | [Video](video/README.md): `VideoOverlay` and the decoders | none (needs `rengl-audio`) | none |
| `rengl-webview` | [Web views](webview/README.md): `WebViewOverlay`, login-portal helpers; WebView2 on Windows, an installed Chrome/Edge/Chromium on Windows, macOS and Linux; WKWebView (macOS) and WebKitGTK (Linux) opt-in, never run | none (the system's WebView2 runtime, or an installed Chromium-based browser; WKWebView and WebKitGTK need nothing shipped) | none |
| `rengl-vulkan` | The Vulkan renderer for `RenderAPI.VULKAN` | LWJGL Vulkan | MoltenVK on macOS only |
| `rengl-3d` | The optional [3D layer](3d/README.md) (needs `rengl-vulkan`) | LWJGL assimp, shaderc, spvc, VMA | those four |
| `rengl-upscale` | Vendor [upscalers and frame generation](3d/upscaling.md) for the 3D layer (AMD FSR and NVIDIA DLSS on Windows, Apple MetalFX on a Mac). Carries no vendor binaries and is left out of `RenGL` | none (needs the vendors' libraries at run time, except MetalFX, which is part of macOS) | none |
| `rengl-spellcheck` | `HunspellChecker` and the en_US dictionary for [spell checking](text/spell-checking.md) | Lucene analysis | none |
| `rengl-tilemap` | [Tile maps](graphics/tile-maps.md) | LWJGL zstd | zstd |
| `rengl-entity` | [Entities and components](game/entities.md) | none | none |
| `rengl-charts` | [Charts](charts/README.md) | none | none |
| `rengl-downloads` | `DownloadManager` and the download UI ([downloads](reference/downloads.md)) | none | none |
| `rengl-ui-file` | UI files: a screen described in a file ([layout files](containers/layout-files.md)) | none | none |
| `rengl-examples` | The demos. Not meant as a dependency | none | none |
| **`RenGL`** | Every module above except the examples and `rengl-upscale`, in one dependency, as before the split | all of them | all of them |

## Using them

The simplest choice is the whole engine, as before:

```xml
<dependency>
    <groupId>io.github.piitex</groupId>
    <artifactId>RenGL</artifactId>
    <version>0.1.0</version>
</dependency>
```

To take less, import the BOM once so the modules share a version, then list the ones you use:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.piitex</groupId>
            <artifactId>rengl-bom</artifactId>
            <version>0.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.github.piitex</groupId>
        <artifactId>rengl-core</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.piitex</groupId>
        <artifactId>rengl-audio</artifactId>
    </dependency>
</dependencies>
```

What to add for common programs:

| You are making | Depend on |
|---|---|
| A tool or app with a UI | `rengl-core` |
| An app with sound | `rengl-core`, `rengl-audio` |
| A video player | `rengl-core`, `rengl-audio`, `rengl-video` |
| A login portal or an embedded browser | `rengl-core`, `rengl-webview` |
| A 2D game with tile maps and entities | `rengl-core`, `rengl-tilemap`, `rengl-entity` (and `rengl-audio` for sound) |
| A text editor with spell checking | `rengl-core`, `rengl-spellcheck` |
| A 3D program | `rengl-core`, `rengl-3d` (it brings `rengl-vulkan`) |
| A program on Vulkan, without 3D | `rengl-core`, `rengl-vulkan` |
| A program that wants everything | `RenGL` |

Some things the split changes at run time:

- **Vulkan.** A window that asks for `RenderAPI.VULKAN` (or runs with `-Drengl.backend=vulkan`) without `rengl-vulkan` on
  the classpath logs `Vulkan was requested, but the rengl-vulkan module is not on the classpath ... Using OPENGL
  instead.` and opens on the platform default, the same fallback as a machine with no Vulkan GPU. On macOS, where
  there is no OpenGL, that default is Metal.
- **Spell checking.** `HunspellChecker` is in `rengl-spellcheck`, in the same package as before. Without that module the
  class does not exist, so only code that uses it needs the dependency.
- **Audio shutdown.** `Audio` registers itself with `Engine.addShutdownHook(Runnable)`, which the engine runs after
  the windows close. Other modules can use the same hook to release what they own.
- **Same package, several jars.** `me.piitex.engine.ui.text` (`rengl-core` and `rengl-spellcheck`) and `ui.overlays`
  (`rengl-core` and `rengl-video`) are split across jars. That is fine on the
  classpath.

## Native libraries

Native code is the biggest part of the weight, and each module brings only its own.

**While developing**, the natives for the machine that builds are picked automatically (the `lwjgl-natives-<os>-<arch>`
profiles in the parent pom), and the `rengl-core` Skiko runtime is `optional`: an app declares the one it needs, or uses
the natives jar below.

**For another platform**, each module that has natives publishes a small jar with the classifier `natives-<platform>`
(`natives-windows`, `natives-linux`, `natives-macos`, `natives-macos-arm64`, ...) holding only those files. Depend on
it for each module you use that has natives. `RenGL` has one too, holding the natives of every module:

```xml
<dependency>
    <groupId>io.github.piitex</groupId>
    <artifactId>RenGL</artifactId>
    <version>0.1.0</version>
    <classifier>natives-windows</classifier>
</dependency>
```

`rengl-core`'s natives jar includes the Skiko runtime, so it is the one every program needs.

### Building the natives jars

All six platforms in one build (no other platform profile may be active with it; in IntelliJ tick only `natives-bundle-all`
in the Maven tool window, then run `package` or `install`):

```bash
./mvnw -DskipTests -Dnatives.platform=all package
```

Or one platform per command. `-DskipAutoNatives` stops the host's own platform from also being selected, and the
platform goes in `natives.platform`:

```bash
./mvnw -DskipAutoNatives -Dnatives.platform=windows-amd64 package
```

The values are `linux-amd64`, `linux-aarch64`, `macos-x86_64`, `macos-aarch64`, `windows-amd64` and `windows-aarch64`.
This only repackages already-published native artifacts, so all six can be built from one machine.

On Windows `rengl-core`'s jar holds 6 DLLs (one of them Skiko), `rengl-audio`'s 2, `rengl-3d`'s 5 and `rengl-tilemap`'s
1; the `RenGL` jar holds all 14. (Before the split the one jar held far more, most of them for libraries the engine
never used.)

## Running the examples

`rengl-examples` holds the demos. They load their sample files by relative path from the repository root, so run them
with the working directory set to the repository root (`EntityDemo`, and `TreeDemo` through it, are the exception and expect
`rengl-examples`):

```bash
./mvnw -pl rengl-examples -am package -DskipTests
```

then run a demo class from your IDE with that as the working directory (`Demo`, `AudioDemo`, `Scene3DDemo`, ...). On
macOS add `-XstartOnFirstThread`. The 3D demos choose their API with `-Drengl.demo.api=VULKAN` or `OPENGL`, not
`-Drengl.backend`.

## Keeping the core small

`rengl-core` must not depend on another module, or on a library another module owns. A Maven Enforcer rule in its
`pom.xml` fails the build if OpenAL, Opus, jlayer, Vulkan, assimp, shaderc, spvc, VMA, zstd, Lucene or any
`io.github.piitex` module appears among its dependencies, directly or through another one.

A feature that needs a library most programs don't have belongs in its own module. The core finds such a module
without depending on it: the Vulkan renderer registers a `RenderBackend` with `java.util.ServiceLoader`, and audio uses
`Engine.addShutdownHook`.
