# Utilities

[Documentation](../README.md) › [Reference](README.md)

Small supporting classes in `me.piitex.engine.utils`, `me.piitex.engine.io` and related packages.

`Pool` and `Poolable`, also in `me.piitex.engine.utils`, have their own page: [Object pools](../game/pooling.md).

## FormID

Every [`Element`](../core/element.md) gets a unique **`FormID`** at construction, keyed by its concrete class.

```java
TextOverlay a = new TextOverlay("a");
TextOverlay b = new TextOverlay("b");

FormID id = a.getId();
System.out.println(id); // e.g. "ELEMENT:TextOverlay#1" (toString = getLabel())
System.out.println(id.getLabel()); // human-readable
System.out.println(id.toHexString()); // e.g. "0x01000001", the packed numeric value
System.out.println(id.equals(b.getId())); // false
```

A `FormID` has two faces:

- A **packed 64-bit value** for fast equality and hashing: namespace (bits 32-39), category (bits 24-31), local id
  (bits 0-23).
- A **label** such as `ELEMENT:TextOverlay#1`, using the class's simple name, for logs and debuggers.

| Method | Description |
|---|---|
| `getLabel()` | Human-readable label |
| `toHexString()` | Zero-padded hex of the packed value |
| `rawValue()` | The packed `long` |
| `getNamespace()`, `getCategory()`, `getLocalId()` | The three fields |
| `equals` / `hashCode` | Based on the packed value |
| `toString()` | Same as `getLabel()` |

Limits: up to 16,777,215 local ids per category, and 255 categories (distinct concrete classes) per namespace.

**Do not persist FormIDs or compare them across runs.** Category codes are assigned in the order classes are first
seen, so the same class can get a different code on another run.

You do not construct a `FormID`. Only `FormIDRegistry` mints them.

### FormIDRegistry and Namespace

`FormIDRegistry` mints unique ids for one `Namespace`:

```java
record Goblin() {}

FormIDRegistry registry = new FormIDRegistry(Namespace.GAME_LOGIC);
FormID enemy1 = registry.generate(Goblin.class); // fully-qualified name is the key, simple name the label
FormID enemy2 = registry.generate("Goblin"); // or by a plain string key
```

`Namespace` is an enum, the single source of truth for top-level id namespaces: `ELEMENT`, `OVERLAY`,
`GAME_LOGIC`. Each is one byte, and duplicates are rejected when the class loads. Add a constant to
`Namespace` when you introduce a new kind of id-bearing object. `Element.ENGINE_REGISTRY` is the registry the UI
uses.

## AssetManager

`me.piitex.engine.io.AssetManager` loads asset files from **mounted `.pak` (zip) files first, then from a loose
directory**. That gives release builds packed assets and development builds plain files, with the same lookup code.

```java
AssetManager assets = new AssetManager("src/main/resources"); // fallback directory for loose files

FileSystem pack = assets.mountPack("build/assets.pak"); // optional; a .pak is a zip file
String shader = assets.loadAssetAsString("shaders/fill.glsl");
ByteBuffer data = assets.loadAssetAsBuffer("models/ship.obj"); // direct buffer, for LWJGL native calls

assets.unmountPack(pack);
assets.unmountAll();
assets.cleanup();
```

| Method | Description |
|---|---|
| `AssetManager(String fallbackDirectory)` | Directory for loose assets |
| `mountPack(String path)` | Mounts a `.pak` and returns its `FileSystem`. Throws `IOException` |
| `unmountPack(FileSystem)` | Unmounts one pack |
| `unmountAll()` | Unmounts every pack |
| `loadAssetAsString(String name)` | Reads an asset as text (for example shader source) |
| `loadAssetAsBuffer(String name)` | Reads into a **direct** `ByteBuffer` (one extra byte of capacity, flipped to the data length) |
| `loadConfig(String name)` | Loads a [`Config`](config.md). Checks the loose file **first**, then packs (see below) |
| `loadConfigOrEmpty(String name)` | Same, but an empty `Config` when the file does not exist yet |
| `saveConfig(String name, Config)` | Writes the config as a loose file under the fallback directory |
| `cleanup()` | Closes all mounted packs |

Lookup rules:

- A leading `/` or `\` in the asset name is stripped.
- **Mounted packs are searched first**, in mount order, then the fallback directory.
- A missing asset throws `RuntimeException("Could not load asset: ...")` caused by a `FileNotFoundException` that
  names both places searched.

Configs are the one exception to that order. A pack is read-only, so `saveConfig` always writes to the fallback
directory, and `loadConfig` checks that directory **before** the packs so a saved copy overrides the pack's defaults.
A config name that escapes the fallback directory (`../x.cfg`) is rejected with `IllegalArgumentException`.

```java
Config cfg = assets.loadConfigOrEmpty("config/engine.cfg"); // pack defaults, or the user's saved copy
cfg.setDefault("engine-vsync", true);
assets.saveConfig("config/engine.cfg", cfg);
```

The UI widgets ([`ImageOverlay`](../overlays/image-overlay.md), [`Font`](../styling/fonts.md), ...) take plain file paths and do not go through `AssetManager`.
Use `AssetManager` for your own data.

## FileUtil and Platform

`me.piitex.engine.utils.FileUtil` returns the directories an application usually needs, the same way on every OS.
All results are absolute `Path`s.

```java
Path home = FileUtil.getHome(); // /Users/you, C:\Users\you, /home/you
Path cwd = FileUtil.getWorkingDirectory(); // where the process was started
Path app = FileUtil.getApplicationDirectory(); // where the jar (or target/classes) lives

Path settings = FileUtil.getAppData("MyGame"); // created if missing
Path saves = FileUtil.getLocalAppData("MyGame");
```

| Method | Description |
|---|---|
| `getHome()` | The user's home directory |
| `getWorkingDirectory()` | The directory the process was started from (`user.dir`) |
| `getApplicationDirectory()` | The folder holding the jar, or `target/classes` when run from an IDE or `mvn`. Falls back to the working directory if it cannot be found |
| `getAppData()` / `getAppData(name)` | Per-user roaming settings folder. The `name` form appends and creates a subfolder |
| `getLocalAppData()` / `getLocalAppData(name)` | Per-user machine-local data folder (caches, large saves) |
| `getTempDirectory()` | The system temp directory |

| | `getAppData` | `getLocalAppData` |
|---|---|---|
| Windows | `%APPDATA%` (falls back to `~/AppData/Roaming`) | `%LOCALAPPDATA%` (falls back to `~/AppData/Local`) |
| macOS | `~/Library/Application Support` | same |
| Linux | `$XDG_CONFIG_HOME`, else `~/.config` | `$XDG_DATA_HOME`, else `~/.local/share` |

The working directory and the application directory are often different: a game launched from a shortcut can have
any working directory. Use the application directory to find files installed next to the jar. The plain getters
never touch the disk. The `name` overloads throw `IOException` if the folder cannot be created, and
`IllegalArgumentException` if the name is blank or contains a path separator or `..`.

`Platform.getCurrent()` returns `WINDOWS`, `MACOS`, `LINUX` or `OTHER` if you need to branch on the OS yourself.

## ProcessUtil

`me.piitex.engine.utils.ProcessUtil` ends other processes the same way on Windows, macOS and Linux, through Java's
`ProcessHandle` rather than shelling out to `taskkill` or `kill`. It is meant for things like closing a helper
program your app launched.

```java
ProcessUtil.Result result = ProcessUtil.kill(pid, "helper"); // graceful only
ProcessUtil.Result forced = ProcessUtil.kill(pid, "helper", true); // escalate to a forced kill if needed

int killed = ProcessUtil.killByName("helper", true); // every matching process
List<ProcessHandle> running = ProcessUtil.findByName("helper");
boolean alive = ProcessUtil.isRunning(pid, "helper");
```

- **The PID call always checks the name first.** PIDs get reused by the OS, so a stale PID could point at an
  unrelated process. If the executable's name does not match, or cannot be read (a process owned by another user),
  nothing is killed.
- Names are the executable's **file name**, not the full command line. On Windows the match ignores case and the
  `.exe` is optional. Interpreted programs report their interpreter (`java`, `python3`).
- It asks the process to exit first and waits up to 3 seconds. With `force`, a process that is still running is
  then killed forcibly (`SIGKILL` / `TerminateProcess`).
- It never kills the running JVM or the PIDs the OS reserves (0 and 1).

| `Result` | Meaning |
|---|---|
| `TERMINATED` | Found, matched, and no longer running |
| `NOT_FOUND` | No process with that PID |
| `NAME_MISMATCH` | The PID exists but its name differs, or could not be read |
| `REFUSED` | The target is this JVM or a reserved PID |
| `FAILED` | It matched but survived (no permission, or `force` was not requested) |

`killByName` returns how many processes it terminated.

## AI-disclosure annotations

The project documents AI-assisted code in the source with four source-retention annotations in
`me.piitex.engine.utils.flags`. They have **no runtime effect** and are not present in compiled classes. Maintainers
are expected to use them for any AI-generated code they contribute (see the project README).

| Annotation | Target | Fields |
|---|---|---|
| `@GeneratedClass` | class | `prompter`, `model`, `reviewed` |
| `@GeneratedFragments` | class | `prompter`, `model`, `reviewed` (AI used for fixes or assistance, not a whole class) |
| `@GeneratedFunction` | method | `prompter`, `model`, `reviewed` |
| `@Verified` | class | `reviewer`, `accepted` |

```java
@GeneratedClass(prompter = "your name", model = "model you used", reviewed = true)
@Verified(reviewer = "Reviewed by...", accepted = true)
public class AIGeneratedClass {

    @GeneratedFunction(prompter = "your name", model = "model you used", reviewed = true)
    public void aiGeneratedCode() {
    }
}
```

`@Verified` is declared for classes, so place it on the class when marking one verified. Generated code must be
reviewed and verified before it is merged.

## SystemInfo

`SystemInfo` collects OS, CPU, GPU, memory and disk details, and the engine logs a summary at startup. It has public
final fields (`osName`, `cpuModel`, `gpuRenderer`, `totalMemoryMB`, ...) and `log()`. It is used internally by
[`Engine`](../core/engine.md). You would only use it to print your own diagnostics:

```java
SystemInfo info = SystemInfo.collect("Unknown GPU"); // pass the GPU string if you know it
info.log();
```

## Static bridges used by the engine

These are internal plumbing, listed so you know what they are when you see them in a stack trace:

| Class | Purpose |
|---|---|
| [`Clipboard`](../input/input-and-focus.md#clipboard) | Bridges the OS clipboard between the render thread and the GLFW main thread. See [Input](../input/input-and-focus.md#clipboard) |
| `CursorManager` | Bridges the OS mouse cursor shape the same way |
| [`MouseTracker`](../input/input-and-focus.md#reading-the-mouse-directly) | Last known mouse position |
| [`TooltipManager`](../input/tooltips.md) | Draws tooltips. See [Tooltips](../input/tooltips.md) |
| [`ThemeManager`](../styling/theming.md) | Theme registry. See [Theming](../styling/theming.md) |
| [`Font`](../styling/fonts.md), [`ImageLoader`](../styling/images-and-icons.md#imageloader), [`IconCache`](../styling/images-and-icons.md#iconcache) | Shared resource caches |
