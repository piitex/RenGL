# TextEditingOptions

[Documentation](../README.md) › [Text editing](README.md)

`me.piitex.engine.ui.text.TextEditingOptions` holds the shared styling and behavior settings for editable text
widgets. [`TextFieldOverlay`](../overlays/text-field-overlay.md) and
[`TextAreaOverlay`](../overlays/text-area-overlay.md) (and so [`RichTextAreaOverlay`](../overlays/rich-text-area-overlay.md)) each own one, reachable with
`getOptions()`.

```java
TextEditingOptions options = field.getOptions();
options.setCaretShape(TextEditingOptions.CaretShape.BLOCK);
options.setPasteEnabled(false);
```

The widgets also have shortcut methods (`setPlaceholder`, `setTextColor`, `setCaretColor`, `setSelectionColor`,
`setMaxLength`) that forward to it.

## Settings

| Setting | Getter / setter | Default |
|---|---|---|
| Selection allowed | `isSelectionEnabled` / `setSelectionEnabled` | `true` |
| Copy/cut allowed | `isCopyEnabled` / `setCopyEnabled` | `true` |
| Paste allowed | `isPasteEnabled` / `setPasteEnabled` | `true` |
| Max length (codepoints) | `getMaxLength` / `setMaxLength` | `-1` (unlimited) |
| Read-only | `isReadOnly` / `setReadOnly` | `false` |
| Undo/redo enabled | `isUndoEnabled` / `setUndoEnabled` | `true` |
| Indent size | `getIndentSize` / `setIndentSize` | `0`: Tab moves focus. Above 0, Tab indents in a text area (up to 16) |
| Auto-indent | `isAutoIndent` / `setAutoIndent` | `false`: Enter keeps the line's indentation in a text area |
| Auto-close brackets | `isAutoCloseBrackets` / `setAutoCloseBrackets` | `false`: typing "(", "[", "{" or a quote adds its closer, typing a closer (and a ";" that follows a ")") goes over the one that is next, Backspace takes an empty pair, and typing an opener over a selection puts the pair round it. Strings and comments are left alone, a quote after a letter is an apostrophe, and Enter after a call leaves its ";" on the line |
| Undo steps kept per widget | `getUndoLimit` / `setUndoLimit` | `200`. A step stores only the part of the text that changed, so a big document with many steps stays small |
| Text color ([`Paint`](../styling/colors-and-paints.md)) | `getTextColor` / `setTextColor` | Black (themed) |
| Placeholder text | `getPlaceholder` / `setPlaceholder` | `""` |
| Placeholder color (`Paint`) | `getPlaceholderColor` / `setPlaceholderColor` | Light gray (themed) |
| Caret color | `getCaretColor` / `setCaretColor(Paint)` | Black (themed) |
| Caret shape | `getCaretShape` / `setCaretShape` | `LINE` |
| Caret width | `getCaretWidth` / `setCaretWidth` | `2` |
| Caret height ratio | `getCaretHeightRatio` / `setCaretHeightRatio` | `1.0` |
| Selection color | `getSelectionColor` / `setSelectionColor(Paint)` | Light blue, 55% alpha (themed) |

Notes:

- **Selection disabled** also prevents copy and cut, since there is never a selection to act on.
- **Max length** truncates typed or pasted text that would exceed it. It does not throw.
- The colors are *themed baselines*. A setter pins them against theme switches (`applyThemeXxx` is the
  theme-facing version). See [Theming](../styling/theming.md).

## Caret shapes

`TextEditingOptions.CaretShape`:

| Shape | Look |
|---|---|
| `LINE` | A thin vertical bar between characters, the conventional text cursor |
| `BLOCK` | A solid block over the glyph under the caret, terminal / insert-mode style |
| `UNDERSCORE` | A thin horizontal bar under the glyph under the caret |

`caretWidth` is the bar width for `LINE`, or thickness for `UNDERSCORE`. `BLOCK` always spans the glyph width.
`caretHeightRatio` (0-1) is the fraction of the line height the caret spans for `LINE` and `BLOCK`, anchored at the
top. `UNDERSCORE` always sits at the bottom.

```java
options.setCaretShape(TextEditingOptions.CaretShape.LINE);
options.setCaretWidth(3f);
options.setCaretHeightRatio(0.8f);
```

## Sharing one options object

An overlay owns one by default, but you can hand the **same instance** to several widgets. One edit then restyles
all of them.

```java
TextEditingOptions shared = new TextEditingOptions();
shared.setCaretShape(TextEditingOptions.CaretShape.BLOCK);
shared.setSelectionColor(new Color(1f, 0.8f, 0.2f, 0.5f));
shared.setMaxLength(64);

TextFieldOverlay first = new TextFieldOverlay(200, 28);
TextFieldOverlay second = new TextFieldOverlay(200, 28);
TextAreaOverlay notes = new TextAreaOverlay(300, 100);

first.setOptions(shared);
second.setOptions(shared);
notes.setOptions(shared);

shared.setPasteEnabled(false); // now affects all three
```

`setOptions(null)` is ignored. `setOptions` replaces the widget's options wholesale, so anything you had set through
the widget's own shortcut methods (placeholder, colors, max length) is now whatever the shared object holds. Configure
the shared object first, then assign it. Because the placeholder lives in the options too, widgets sharing one object
also share the same placeholder.

## Read-only and undo

```java
field.setReadOnly(true); // same as field.getOptions().setReadOnly(true)
```

A read-only widget still takes focus, shows a caret, and supports selection, copy, select-all and Enter
(`onSubmit`). Typing, paste, cut, backspace/delete and undo/redo do nothing. `setText` still works. To also stop
copying or selecting, use `setCopyEnabled(false)` / `setSelectionEnabled(false)`. `setEnabled(false)` locks the
whole widget.

Ctrl/Cmd+Z undoes and Ctrl/Cmd+Shift+Z (or Y) redoes. The flags and the step limit are options, so a shared
options object applies to every widget holding it. The history itself is per widget, and is reachable through
`getUndoHistory()` on the widget (for example to `clear()` it after loading a document). Turning undo off drops
the widget's history.
