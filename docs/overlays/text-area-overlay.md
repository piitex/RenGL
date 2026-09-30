# TextAreaOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.TextAreaOverlay` is a **multi-line editable text box**: the sibling of
[`TextFieldOverlay`](text-field-overlay.md), sharing its selection, clipboard shortcuts and
[`TextEditingOptions`](../text/text-editing-options.md). What is different is entirely about having more than one
line: **word wrap**, **vertical scrolling** with a built-in scrollbar, **Up/Down** navigation, and **Enter
inserts a newline**.

```java
TextAreaOverlay notes = new TextAreaOverlay(20, 20, 320, 180);
notes.setPlaceholder("Notes...");
notes.onTextChanged(text -> System.out.println(text.length() + " characters"));
root.addElement(notes);
```

You do **not** need to wrap it in a [`ScrollContainer`](../containers/scroll-container.md). Scrolling is built in.

For syntax coloring and spell checking, use the subclass
[`RichTextAreaOverlay`](rich-text-area-overlay.md).

## Constructors

| Constructor | Notes |
|---|---|
| `TextAreaOverlay(double width, double height)` | At `0, 0`, 16pt, default font |
| `TextAreaOverlay(double x, double y, double width, double height)` | |
| `TextAreaOverlay(x, y, width, height, float fontSize, Paint textColor)` | |
| `TextAreaOverlay(x, y, width, height, File fontFile, float fontSize, Paint textColor)` | Custom font |

The `textColor` argument is a themed baseline. Use `setTextColor(...)` to pin a color.

## Text

```java
area.setText("Line one\nLine two"); // newlines are real line breaks; cursor moves to the end; no callback
String s = area.getText();
area.setMaxLength(500); // codepoints; -1 (default) = unlimited
```

Pasted newlines are preserved (CRLF and CR are normalized to `\n`).

## Callbacks

| Method | Fires |
|---|---|
| `onTextChanged(Consumer<String>)` | On each user edit with the new text |
| `onSubmit(Consumer<String>)` | On **Ctrl/Cmd + Enter** (plain Enter inserts a newline) |
| `onFocusChanged(Consumer<Boolean>)` | On focus gain/loss |

## Keyboard and mouse

| Input | Effect |
|---|---|
| Typing | Inserts characters |
| Enter | Inserts a newline |
| Ctrl/Cmd + Enter | Fires `onSubmit` |
| Backspace / Delete | Deletes selection or one character |
| Left / Right | Moves the caret by one character |
| Up / Down | Moves one visual line, trying to keep the same horizontal position |
| Home / End | Start / end of the **visual line** |
| **Shift** + movement | Extends the selection |
| Ctrl/Cmd + **A / C / X / V** | Select all / copy / cut / paste |
| Ctrl/Cmd + **Z** | Undo |
| Ctrl/Cmd + **Shift+Z** or **Y** | Redo |
| Click | Places the caret |
| Click and drag | Selects a range |
| Double-click | Selects the word (or run of whitespace) |
| **Triple-click** | Selects **all** the text |
| Mouse wheel / trackpad | Scrolls vertically. At the top or bottom (or when the text fits), the wheel passes on to the enclosing [`ScrollContainer`](../containers/scroll-container.md) |
| Drag the scrollbar thumb | Scrolls |

## Layout

```java
area.setWordWrap(true); // default; long lines wrap to the box width
area.setLineSpacing(4f); // extra points between lines (default 4)
area.setPaddingX(8f); // horizontal inset (default 8)
area.setPaddingY(6f); // vertical inset (default 6)
area.setFontSize(18f);
```

With word wrap **off**, an over-long line is clipped at the box edge. Horizontal scrolling is not
implemented, so leave word wrap on unless you have a reason not to.

## Scrolling

```java
area.setScrollSpeed(40f); // points per unit of wheel offset (default 40)
area.setScrollbarVisible(true); // default true; also only drawn when there is something to scroll

area.scrollBy(60); // relative, clamped to [0, maxScroll]
area.scrollTo(0); // back to the top
double y = area.getScrollOffsetY();
double max = area.getMaxScrollY();

ScrollBarStyle bar = area.getScrollBarStyle(); // same class ScrollContainer uses
bar.setThickness(6f);
bar.setThumbColor(Color.GRAY);
```

The scrollbar is drawn as an overlay on the right edge (no reserved gutter), so long text can run under it.
Give the text a little right padding with `setPaddingX` if that matters.

## Selection and inspection

```java
area.hasSelection();
area.getSelectedText();
area.clearSelection();
area.isFocused();
```

## Appearance

Identical to [`TextFieldOverlay`](text-field-overlay.md#appearance) and backed by the same
[`TextEditingOptions`](../text/text-editing-options.md):

```java
area.getStyling().setCornerRadius(6f);
area.setCaretColor(Color.BLUE);
area.setSelectionColor(new Color(1f, 0.85f, 0.3f, 0.5f));
area.getOptions().setCaretShape(TextEditingOptions.CaretShape.UNDERSCORE);
area.getOptions().setCopyEnabled(false);
```

## Examples

### A read-only viewer

```java
TextAreaOverlay viewer = new TextAreaOverlay(20, 20, 400, 200);
viewer.setText("This text cannot be changed.\nBut it can be selected and scrolled.");
viewer.setReadOnly(true);
```

Selection, copy and scrolling still work. `setText` still works too, so the app can keep updating it.

### A chat input that sends on Ctrl/Cmd+Enter

```java
TextAreaOverlay input = new TextAreaOverlay(20, 300, 500, 90);
input.setPlaceholder("Type a message. Ctrl+Enter to send.");
input.onSubmit(message -> {
    System.out.println("Sending: " + message);
    input.setText("");
});
```

## Cleanup

`dispose()` releases the native font.

## Method reference

| Group | Methods |
|---|---|
| Text | `getText`, `setText`, `getMaxLength`, `setMaxLength` |
| Read-only / undo | `isReadOnly/setReadOnly`, `isUndoEnabled/setUndoEnabled`, `getUndoHistory`, `undo()`, `redo()` (the same as the keys; see the text field page for why an app might call them) |
| Look | `getOptions/setOptions`, `getTextColor/setTextColor`, `getPlaceholder/setPlaceholder`, `getPlaceholderColor/setPlaceholderColor`, `getCaretColor/setCaretColor`, `getSelectionColor/setSelectionColor`, `getFontSize/setFontSize`, `setFontFile` |
| Layout | `isWordWrap/setWordWrap`, `getLineSpacing/setLineSpacing`, `getPaddingX/setPaddingX`, `getPaddingY/setPaddingY` |
| Scrolling | `getScrollSpeed/setScrollSpeed`, `isScrollbarVisible/setScrollbarVisible`, `getScrollBarStyle/setScrollBarStyle`, `getScrollOffsetY`, `getMaxScrollY`, `scrollBy`, `scrollTo` |
| Selection | `hasSelection`, `getSelectedText`, `clearSelection` |
| Callbacks | `onTextChanged`, `onSubmit`, `onFocusChanged` |
| Subclass hooks (protected) | `drawLineText`, `indexAtPoint`, `replaceRange`, `getCursorIndex` |
| Lifecycle | `dispose` |
