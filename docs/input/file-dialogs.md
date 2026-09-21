# File dialogs

[Documentation](../README.md) › [Input](README.md) › File dialogs

`me.piitex.engine.input.FileDialog` opens the operating system's own open, save and choose-folder dialogs, for
importing from and exporting to the file system. It only chooses paths. Reading and writing the file is up to you.

```java
FileDialog.open()
        .filter("Images", "png", "jpg")
        .onSelect(path -> loadImage(path))
        .show();
```

## Kinds

| Factory | Picks |
|---|---|
| `FileDialog.open()` | One existing file |
| `FileDialog.openMultiple()` | One or more existing files |
| `FileDialog.save()` | A destination. The file may not exist yet |
| `FileDialog.pickFolder()` | A folder |

## Options

All methods return the dialog, so they chain. Call `show()` last.

| Method | Effect |
|---|---|
| `filter(name, ext...)` | Adds a row to the file-type drop-down. Extensions have no dot: `filter("Images", "png", "jpg")`. No filters means every file. Ignored by `pickFolder` |
| `defaultPath(Path)` | The folder the dialog opens in. Should exist |
| `defaultName(String)` | Suggested file name in a `save` dialog |
| `onSelect(Consumer<Path>)` | Called with the chosen path. For `openMultiple` it is called once per file |
| `onSelectAll(Consumer<List<Path>>)` | Called once with every chosen path |
| `onCancel(Runnable)` | The user closed the dialog without choosing |
| `onError(Consumer<String>)` | The dialog could not be shown. Without it the failure is only logged |
| `show()` | Queues the dialog and returns immediately |

## Threading

`show()` never blocks, and it is safe from any thread. The dialog itself opens on the main thread (macOS requires
that). The window underneath can't be used while it is open, but rendering continues. The callbacks run on the
**render thread**, like every other UI callback, so you can change elements from them directly.

Dialogs requested while one is open queue up and open one after another. `FileDialog.isShowing()` tells you
whether one is currently open.

## Import and export

```java
// Import a text file into a text area.
importButton.onAction(() -> FileDialog.open()
        .filter("Text files", "txt", "md")
        .onSelect(path -> {
            try {
                editor.setText(Files.readString(path));
            } catch (IOException e) {
                status.setText("Could not read " + path.getFileName());
            }
        })
        .show());

// Export it back out.
exportButton.onAction(() -> FileDialog.save()
        .filter("Text files", "txt")
        .defaultName("notes.txt")
        .onSelect(path -> {
            try {
                Files.writeString(path, editor.getText());
            } catch (IOException e) {
                status.setText("Could not write " + path.getFileName());
            }
        })
        .show());
```

The full version, with cancel and error handling, is `FileDialogDemo`.

## Limitations

See [known limitations](../reference/known-limitations.md#file-dialogs). The main ones: Linux needs GTK 3 or the
desktop portal, and there is no dialog title or in-engine file browser.
