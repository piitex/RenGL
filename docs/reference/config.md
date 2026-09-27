# Config

[Documentation](../README.md) › [Reference](README.md)

`me.piitex.engine.config.Config` reads and writes settings files. One file can mix flat properties
(`engine-vsync=true`) and nested blocks, and everything is read by dotted path. It has no dependencies.

```java
Config cfg = Config.loadOrEmpty(Path.of("engine.cfg"));

cfg.setDefault("engine-vsync", true); // seed anything that is missing
boolean vsync = cfg.getBoolean("engine-vsync", true);
String item = cfg.getString("entry.item"); // nested value, dotted path
int max = cfg.getInt("entry.limits.max", 5); // 5 if missing or not a number

cfg.set("window.width", 1920);
cfg.save(Path.of("engine.cfg"));
```

## File format

```text
# A comment
engine-vsync=true            # flat key=value
window.width=1280            # a dotted key is the same as a "window:" block holding "width"

entry:                       # nested block, indented with spaces
  item: "value"
  limits:
    max: 10
    ratio: 1.5

tags:                        # list
  - one
  - "two words"
sizes: [1, 2, 3]             # inline list
```

Both separators work on any line: `key=value` and `key: value`. The first `=` or `:` on the line splits key from
value, so later ones belong to the value (`url=http://host:80` is fine).

### Values

| Written as | Read as |
|---|---|
| `true` / `false` (any case) | `Boolean` |
| `42`, `-7` | `Long` |
| `1.5`, `2e3` | `Double` |
| `"text"` | String. Escapes: `\n` `\t` `\r` `\"` `\\` |
| `'text'` | String, literal (write `''` for a single quote) |
| anything else | String, trimmed |

Quote a value when it would otherwise be read as another type or contains a `#` after whitespace:
`version: "1.0"` stays text, `version: 1.0` is a number.

### Comments

A full-line `#` is a comment. Inside a line, `#` starts a comment only when whitespace comes before it, so
`color=#ff0000` keeps its value. After a colon, `key: #fff` is a comment, so write `key: "#fff"`.

### Nesting rules

- Indent with **spaces**. Tabs are rejected.
- Every line in a block must use the same indent. A line that matches no open block is an error.
- A `key:` with nothing after it opens a block: a section if indented keys follow, a list if `- item` lines follow.
  With nothing indented under it, it is an empty section.
- Lists hold scalars only (strings, booleans, numbers). Lists of blocks and lists inside lists are not supported and
  throw a `ConfigException`.

## Reading

Every getter takes a dotted path. Typed getters take an optional default that is returned when the key is missing or cannot be
converted (a string such as `"12"` still converts with `getInt`).

| Method | Description |
|---|---|
| `get(path)` | Raw value (`String`, `Boolean`, `Long`, `Double`, `List`, or a section map), or `null` |
| `has(path)` | Whether a value exists |
| `getString(path)` / `getString(path, def)` | Text. The one-argument form returns `null` if missing |
| `getInt` / `getLong` / `getDouble` / `getBoolean` `(path, def)` | Typed value or the default |
| `getInt` / `getLong` / `getDouble` / `getBoolean` `(path)` | Boxed value (`Integer`, `Long`, `Double`, `Boolean`), or `null` if missing or not convertible |
| `getList(path)` | List of values. A single scalar becomes a one-element list, a missing key an empty list |
| `getStringList(path)` | Same list with every element as text |
| `getSection(path)` | `Optional<Config>`: a **live view** of a nested block. Writes through it change the parent |
| `getKeys(deep)` | Top-level key names, or with `true` the dotted path of every value |

Paths cannot be empty and cannot start or end with `.` or contain `..`. An invalid path throws
`IllegalArgumentException`.

## Writing

| Method | Description |
|---|---|
| `set(path, value)` | Sets a value and creates the sections on the way. Takes `String`, `Boolean`, `Number` or a `List` of those. `null` removes the entry. Returns the config, so calls chain |
| `setDefault(path, value)` | Same, but only when nothing exists at `path` |
| `createSection(path)` | Ensures a section exists and returns a live view of it |
| `remove(path)` | Deletes a value or a whole section |

```java
Config window = cfg.createSection("window");
window.set("width", 1280).set("height", 720); // writes window.width / window.height
```

## Loading and saving

| Method | Description |
|---|---|
| `Config.parse(String)` | Parses text. Throws `ConfigException` on a syntax error |
| `Config.load(Path)` | Reads a file. Throws `NoSuchFileException` if it does not exist |
| `Config.loadOrEmpty(Path)` | Reads a file, or gives an empty config when it does not exist yet |
| `Config.loadOrCreate(Path, defaults)` | First-run helper: loads (or starts empty), lets the `defaults` lambda fill in what is missing, and saves if the file was absent or something was added |
| `isEmpty()` | True if nothing is set (for example after `loadOrEmpty` on a missing file) |
| `save()` | Saves to the file the config was loaded from or last saved to. Throws `IllegalStateException` for a config with no file (`new Config()`) |
| `getSourcePath()` / `setSourcePath(Path)` | The file `save()` writes to |
| `save(Path)` | Writes the file, and makes it the target of later `save()` calls. It writes a temporary file first and swaps it in, so a crash cannot leave a half-written config. Parent directories are created |
| `toString()` | The text `save` would write |

To load from mounted `.pak` files as well as loose files, use `AssetManager.loadConfig` / `saveConfig`. See
[AssetManager](utilities.md#assetmanager).

### First run

To tell whether a config exists, check `Files.exists(path)` before loading, or call `isEmpty()` after `loadOrEmpty`.
Most of the time you do not need to: `loadOrCreate` handles both a first run and a later version that adds settings.

```java
Config cfg = Config.loadOrCreate(Path.of("engine.cfg"), c -> {
    c.setDefault("engine-vsync", true); // only applied where the key is missing
    c.setDefault("window.width", 1280);
});
```

On a first run the file is written with the defaults. On later runs the user's values are kept, and only keys they do
not have yet are added.

`ConfigException` is unchecked and its message names the line: `Config line 3: inconsistent indentation`.

**Saving rewrites the file from the tree.** Comments, blank lines and the original layout are not kept. Top-level
values are written as `key=value`, nested ones as `key: value`, and dotted keys come back as blocks. A typical
pattern is to keep hand-written defaults in code with `setDefault` and let `save` produce the file.

`Config` is not thread-safe. Load and save it from one thread, or synchronize access yourself.

Key bindings are stored in a config by [`InputContext.save` and `load`](../input/game-input.md#saving-bindings).

## Gotchas

- **Stringly-typed round trips are safe:** `set("v", "true")` is written as `"true"` in quotes, so it reads back as
  text, not a boolean.
- **`Integer` becomes `Long`:** `set("n", 5)` reads back as a `Long`. `getInt` narrows it for you.
- **Section views are live:** the `Config` from `getSection` shares data with its parent, so edits show up in both.
  Call `save` on the root to write the whole file. Saving a view writes only that section.
