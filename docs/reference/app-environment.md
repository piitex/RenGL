# AppEnvironment

[Documentation](../README.md) › [Reference](README.md)

`me.piitex.engine.io.AppEnvironment` answers "where do my files live?" the same way whether you run from the IDE or
from an installed build. There are no launch flags to pass and no `if (dev)` branches in your code: you ask for a
location by purpose, and the environment picks the folder.

```java
AppEnvironment env = AppEnvironment.create("chat-app");

Path logo = env.getAssetPath("logo.png"); // shipped, read-only
Path chars = env.getDirectory("characters"); // per-user, writable, created if missing
Config cfg = env.loadOrCreateConfig("settings.cfg", c -> c.setDefault("engine-vsync", true));
env.saveConfig("settings.cfg", cfg); // always saved to the data directory
```

## The two roots

| Root | Holds | DEV (IDE) | RELEASE (jar / jpackage) |
|---|---|---|---|
| **Data** (`getDataDirectory()`) | Configs, saves, downloads. Anything the app writes | `<working dir>/run` | Per-user app data + app name (see [`FileUtil.getAppData`](utilities.md#fileutil-and-platform)) |
| **Assets** (`getAssetDirectory()`) | Icons, default configs, anything you ship | `<working dir>/src/main/resources` | The folder holding the jar. With jpackage, the `app` folder next to the launcher |

The rule is: **read shipped files from assets, write everything to data.** An installed app usually cannot write to
its own folder (`Program Files`, a signed `.app` bundle), so nothing should ever be saved to the asset root.
Dev data lands in `run/` inside the project so testing never touches your real app data. Add `run/` to `.gitignore`.

## Choosing the dev directory

To keep development files in the project's `app/` folder:

```java
AppEnvironment env = AppEnvironment.builder("chat-app")
        .devDirectory(Path.of("app")) // <project>/app for both data and assets, DEV only
        .build();
```

Release builds ignore it and keep their normal locations. Relative paths resolve against the working directory.

| Builder method | Applies in | Effect |
|---|---|---|
| `devDirectory(dir)` | DEV | Data and assets both use `dir` |
| `devDataDirectory(dir)` / `devAssetDirectory(dir)` | DEV | Move one root only, for example assets in `src/main/resources` and data in `app/` |
| `dataDirectory(dir)` / `assetDirectory(dir)` | every mode | Fixed location, for example a portable build. Wins over the `dev...` methods |

Using one folder for both roots is fine: `loadConfig` looks for a saved copy first, then the shipped default, so both
resolve to the same file.

## How the mode is chosen

`AppEnvironment.detectMode()` decides in this order:

1. The system property `-Drengl.mode=dev` or `-Drengl.mode=release`, if set.
2. `RELEASE` if a jpackage launcher started the process (`jpackage.app-path` is set).
3. `RELEASE` if the engine was loaded from a jar file. Otherwise `DEV` (a folder of classes, as in the IDE or `mvn`).

So the IDE gives `DEV` and every packaged build gives `RELEASE` without you doing anything. Use the property when you
want to test release layout from the IDE.

## API

| Method | Description |
|---|---|
| `AppEnvironment.create(name)` | Detects the mode and uses the default locations. Throws `IOException` if the data directory cannot be created |
| `AppEnvironment.builder(name)` | Overrides, then `.build()`. `.mode(Mode)`; `.devDirectory(Path)`, `.devDataDirectory(Path)`, `.devAssetDirectory(Path)` change **DEV only**; `.dataDirectory(Path)`, `.assetDirectory(Path)` change **every mode** |
| `getMode()` / `isDev()` | `Mode.DEV` or `Mode.RELEASE` |
| `getDataDirectory()` / `getAssetDirectory()` | The two roots. The data directory always exists |
| `getDirectory(first, more...)` | A data subfolder, created if missing |
| `getDataPath(relative)` / `getAssetPath(relative)` | A path inside a root. Nothing is created or checked. Paths that escape the root throw `IllegalArgumentException` |
| `loadConfig(relative)` | The saved copy in the data directory, else the shipped default (loose file or mounted `.pak`), else an empty [`Config`](config.md) |
| `hasSavedConfig(relative)` | True if the user has a saved copy. False on a first run, even when a shipped default exists |
| `loadOrCreateConfig(relative, defaults)` | First-run helper: loads, applies the `defaults` lambda, and saves to the data directory if there was no saved copy or the defaults added something |
| `saveConfig(relative, config)` | Writes to the data directory |
| `getAssets()` | An [`AssetManager`](utilities.md#assetmanager) over the asset directory, for packs and direct buffers |

A config from `loadConfig` or `loadOrCreateConfig` is already bound to its data-directory file, even when it started from
a shipped default. Just call `config.save()` and you never have to rebuild the path.

Create one `AppEnvironment` at startup and keep it in a static field (or pass it around). It logs where it settled at
startup: `Environment 'chat-app' running in DEV mode (data: ..., assets: ...)`.

## First run and config defaults

`loadConfig` gives the user's file **wholesale** when one exists, so a setting you add in a later version would be
missing from old files. `loadOrCreateConfig` solves both that and the first run:

```java
Config cfg = env.loadOrCreateConfig("settings.cfg", c -> {
    c.setDefault("engine-vsync", true); // only applied where the key is missing
    c.setDefault("window.width", 1280);
});
```

- **First run:** there is no saved copy, so it starts from the shipped default (or empty), applies your defaults,
  and writes the file to the data directory.
- **Later runs:** the user's values are kept. Keys they do not have yet are added and the file is re-saved.
- **Only need to know if it is a first run?** Use `!env.hasSavedConfig("settings.cfg")`.

## Migrating from a flag-based setup

Replace the mode flags and per-mode `File` helpers with the environment:

| Before | After |
|---|---|
| `Main.app` / `Main.run` flags, `-app` argument | Delete. Detected automatically |
| `getExecutedDirectory()` + `"logo.png"` | `env.getAssetPath("logo.png")` |
| `getAppDirectory()` | `env.getDataDirectory()` |
| `new File(getAppDirectory(), "characters/")` + `mkdirs()` | `env.getDirectory("characters")` |
| `getBackendDirectory()` with `if (Main.run) ...` | `env.getDirectory("backend")` (or an asset path if it ships with the app) |
| `if (Main.app \|\| Main.run)` to skip updates in the IDE | `if (!env.isDev())` |

## Gotchas

- **Working directory matters in DEV.** The defaults and relative dev paths resolve against `user.dir`, which is the
  project root when you run from IntelliJ but not if you change the run configuration's working directory. Pass an
  absolute path to the builder to pin them.
- **Asset directory can be empty in RELEASE.** If you ship everything inside the jar, use the classpath (or a `.pak`
  through `getAssets()`) instead of loose files.
- **Testing the release layout:** run with `-Drengl.mode=release`. Data then goes to the real per-user folder.
