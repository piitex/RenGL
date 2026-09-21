# TextFieldOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.TextFieldOverlay` is a **single-line editable text box**. It clips its content to
its box and scrolls horizontally when the text overflows. It supports selection, clipboard shortcuts,
placeholder text, a prefix, and right alignment.

```java
TextFieldOverlay name = new TextFieldOverlay(200, 28);
name.setPlaceholder("Your name...");
name.onTextChanged(text -> System.out.println("typed: " + text));
name.onSubmit(text -> System.out.println("enter pressed: " + text));
layout.addElement(name);
```

## Constructors

| Constructor | Notes |
|---|---|
| `TextFieldOverlay(double width, double height)` | At `0, 0`, 16pt, default font |
| `TextFieldOverlay(double x, double y, double width, double height)` | |
| `TextFieldOverlay(x, y, width, height, float fontSize, Paint textColor)` | |
| `TextFieldOverlay(x, y, width, height, File fontFile, float fontSize, Paint textColor)` | Custom font |

The constructor's `textColor` is a themed baseline. Call `setTextColor(...)` to pin a color.

## Text

```java
field.setText("hello"); // replaces the text, cursor to the end; does NOT fire onTextChanged
String s = field.getText();
field.setMaxLength(20); // codepoints; -1 (default) = unlimited
```

`setText` is silent, like every value setter in the toolkit. Only edits from the user fire `onTextChanged`.

## Callbacks

Each holds one listener. Calling it again replaces the previous one.

| Method | Fires |
|---|---|
| `onTextChanged(Consumer<String>)` | On each user edit (typing, paste, cut, backspace, delete) with the new text |
| `onSubmit(Consumer<String>)` | When **Enter** is pressed while focused, with the current text |
| `onFocusChanged(Consumer<Boolean>)` | With `true`/`false` when the field gains or loses focus |

`onMouseClick` is free for your own use here. Selection uses the drag interface, not the click listener.

## Keyboard and mouse

| Input | Effect |
|---|---|
| Typing | Inserts characters (already resolved for keyboard layout and dead keys) |
| Backspace / Delete | Deletes selection, or the character before/after the caret |
| Left / Right | Moves the caret one character; collapses a selection |
| Home / End | Start / end of the text |
| **Shift** + movement | Extends the selection |
| Ctrl/Cmd + **A** | Select all |
| Ctrl/Cmd + **C** / **X** / **V** | Copy / cut / paste |
| Ctrl/Cmd + **Z** | Undo |
| Ctrl/Cmd + **Shift+Z** or **Y** | Redo |
| Enter | Fires `onSubmit` |
| Click | Places the caret |
| Click and drag | Selects a range |
| Double-click | Selects the word (or run of spaces) under the pointer |
| Triple-click | Selects the whole field |

Ctrl **or** Cmd works on every platform, so a Ctrl press out of habit on macOS still works. Pasted newlines
are collapsed to spaces, since this is a single-line field. For multi-line text, use
[`TextAreaOverlay`](text-area-overlay.md).

## Placeholder, prefix and alignment

```java
field.setPlaceholder("Search..."); // shown in the placeholder color when empty
field.setPlaceholderColor(Color.GRAY);

// Permanent text at the left edge, visible whether or not there is content.
field.setPrefix("$");

// Text sits against the right edge while it is short enough to fit.
field.setRightAligned(true);
```

Combined, a prefix and right alignment give the "label on the left, value on the right" look that
`SpinnerOverlay.setLabel` uses. The prefix is drawn in the placeholder color.

## Password mode

```java
TextFieldOverlay pw = new TextFieldOverlay(200, 28);
pw.setPassword(true); // false by default
```

Every character is drawn as `*`. `getText()` still returns the real text, and typing, paste, selection and
caret movement work as normal. Copy and cut are disabled so the text can't be lifted off the clipboard, and
double-click selects the whole field rather than a word. Characters outside the BMP (e.g. emoji) show as two
stars.

## Read-only mode

```java
field.setReadOnly(true); // false by default; stored in the shared TextEditingOptions
```

A read-only field still takes focus, shows a caret, and supports selection, copy, Ctrl/Cmd+A and Enter
(`onSubmit`). Typing, paste, cut, backspace/delete and undo/redo do nothing. `setText` still works, so the
app can keep updating what is shown.

## Undo and redo

Ctrl/Cmd+Z undoes and Ctrl/Cmd+Shift+Z (or Ctrl/Cmd+Y) redoes. A run of typing, backspacing or forward-deleting
is one undo step, as long as the pauses stay under a second and the caret doesn't move. Paste, cut and
replacing a selection are each their own step. `setText` discards the history.

```java
field.setUndoEnabled(false); // on by default; turning it off also clears the history
field.getOptions().setUndoLimit(500); // default 200 steps
field.getUndoHistory().clear(); // e.g. after loading a new document
```

## Selection and inspection

```java
field.hasSelection();
field.getSelectedText();
field.clearSelection();
field.isFocused();
```

## Appearance

All of the text-editing styling lives in a shared [`TextEditingOptions`](../text/text-editing-options.md) object. The convenience methods on
the field forward to it.

```java
field.setCaretColor(Color.RED);
field.setSelectionColor(new Color(1f, 0.8f, 0.2f, 0.5f));
field.setPadding(10f); // horizontal inset between the box edge and the text
field.getStyling().setCornerRadius(4f);
field.getStyling().setBorderColor(Color.BLUE);

TextEditingOptions options = field.getOptions();
options.setCaretShape(TextEditingOptions.CaretShape.BLOCK);
options.setPasteEnabled(false); // read-only-ish: block pasting
```

See [TextEditingOptions](../text/text-editing-options.md) for every knob, and how to **share one options
object across several fields** so one edit restyles all of them.

## Focus and Tab order

A text field takes focus when clicked and is a **Tab stop** by default (`setFocusTraversable(true)` is set in the
constructor).

```java
window.requestFocus(field); // focus it from code
field.setTabIndex(1); // explicit Tab order
```

## Examples

### A search box with a submit handler

```java
TextFieldOverlay search = new TextFieldOverlay(260, 30);
search.setPlaceholder("Search...");
search.setTooltip("Press Enter to search");
search.onSubmit(query -> System.out.println("Searching for: " + query));
```

### A limited numeric-looking field

```java
TextFieldOverlay code = new TextFieldOverlay(120, 30);
code.setMaxLength(6);
code.setRightAligned(true);
code.setPrefix("#");
code.onTextChanged(text -> {
    if (!text.matches("[0-9]*")) {
        code.setText(text.replaceAll("[^0-9]", ""));
    }
});
```

`setText` does not fire `onTextChanged`, so the filter above cannot loop.

### A form with several fields

```java
VerticalLayout form = new VerticalLayout(40, 40, 320, 200);
form.setSpacing(10);
form.useTheme();

TextFieldOverlay first = new TextFieldOverlay(240, 28);
first.setPlaceholder("First name");
TextFieldOverlay last = new TextFieldOverlay(240, 28);
last.setPlaceholder("Last name");
form.addElement(first);
form.addElement(last);
root.addElement(form);
// Tab moves first -> last; clicking anywhere else clears focus.
```

## Cleanup

`dispose()` releases the native font when the field is permanently discarded.

## Method reference

| Group | Methods |
|---|---|
| Text | `getText`, `setText`, `getMaxLength`, `setMaxLength` |
| Look | `getOptions/setOptions`, `getTextColor/setTextColor`, `getPlaceholder/setPlaceholder`, `getPlaceholderColor/setPlaceholderColor`, `getCaretColor/setCaretColor`, `getSelectionColor/setSelectionColor`, `getPadding/setPadding`, `getFontSize/setFontSize`, `setFontFile` |
| Layout | `getPrefix/setPrefix`, `isRightAligned/setRightAligned` |
| Password | `isPassword/setPassword` |
| Read-only | `isReadOnly/setReadOnly` |
| Undo | `isUndoEnabled/setUndoEnabled`, `getUndoHistory` |
| Selection | `hasSelection`, `getSelectedText`, `clearSelection` |
| State | `isFocused` |
| Callbacks | `onTextChanged`, `onSubmit`, `onFocusChanged` |
| Lifecycle | `dispose` |
