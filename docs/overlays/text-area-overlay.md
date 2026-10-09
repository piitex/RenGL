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
| Tab / Shift+Tab | Indent / outdent, only with an [indent size](#as-a-code-editor); otherwise Tab moves focus |
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

With word wrap **off**, a line wider than the box scrolls sideways: with the horizontal wheel or trackpad
movement, when the caret moves out of view, or by dragging the thin bar along the bottom. `getMaxScrollX`,
`getScrollOffsetX`, `scrollToX` and `scrollByX` do it from code.

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

The scrollbar's lane (its thickness plus margin) is kept free on the right whenever `isScrollbarVisible()` is true, so
text wraps before it and never runs underneath. On top of that lane the text keeps a gap before the bar, which by
default is `getPaddingX()`, the same as the space at the left edge. Set it separately:

```java
area.setScrollbarPadding(14f); // space between the text and the scrollbar, in points
area.setScrollbarPadding(null); // back to following setPaddingX (the default)
```

The padding applies to `RichTextAreaOverlay` as well, which extends this class. Without a visible scrollbar the
text keeps `getPaddingX()` from the right edge.

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

## As a code editor

A few settings turn the box into a place to write code. They are all off by default.

```java
RichTextAreaOverlay code = new RichTextAreaOverlay(0, 0, 640, 480);
code.setTypeface(Font.monospace());       // letters of one width: Menlo, Consolas, DejaVu Sans Mono...
code.getOptions().setIndentSize(4);       // Tab indents; Shift+Tab takes a level off
code.getOptions().setAutoIndent(true);    // Enter keeps the indentation; a new level after "{"
code.getOptions().setAutoCloseBrackets(true); // "(", "[", "{" and quotes bring their closers; typing one goes over it; Backspace takes both; with text selected they go round it
code.setParenCloser((text, at) -> ");");  // optional: what goes after "(" (null for nothing), here with a semicolon
code.setLineNumbers(true);                // a column of numbers down the left
code.setStyler(text -> mySpans(text));    // colors, see RichTextAreaOverlay
```

- **Tab.** Once the indent size is above 0 the box uses Tab itself, so it no longer moves focus away
  (`wantsTab()`; see [input and focus](../input/input-and-focus.md)). With nothing selected, Tab adds spaces up to the next
  multiple of the indent size. With a selection, every line it touches gets one level. **Shift+Tab** takes a level off
  those lines, or the caret's line. Blank lines are left blank, and the whole thing is one undo step.
- **Auto-indent.** Enter starts the new line at the indentation of the one before it, one level more if that line ends
  with `{`. Pressing it between `{` and `}` puts the `}` on a line of its own and the caret on the line between. A `}`
  typed on a line of only spaces takes one level off the line.
- **Line numbers.** `setLineNumbers(true)` reserves a column that is as wide as the highest number needs. The numbers
  are of the *text's* lines: a long line that wraps keeps one number. The caret's number is drawn in the text color.
  `setLineNumberColor(Color)` changes the others.
- **Marked lines.** `setLineMarks(Map<Integer, Color>)` washes whole lines in a color, such as the lines a compiler
  complained about. The keys count from 1.
- **Highlighted text.** `setHighlights(List<int[]> ranges, Color)` washes stretches of the text, each `{start, end}` as indexes
  with the end not included, behind the text and under the selection, such as every match of a search. They do not move
  when the text is edited, so set them again after an edit. An empty list or null clears them.
- **Window shortcuts.** A focused box is sent every key, so a shortcut the window polls, such as Ctrl+S, never sees it.
  `setKeyFilter((key, mods) -> ...)` is offered each key press first; return true for a key you used and the box ignores
  it. Releases aren't offered.
- **Positions.** `getLineCount()`, `getCursorLine()` and `getCursorColumn()` (all from 1), and `goToLine(n)`, which puts
  the caret at the start of a line and scrolls it into the middle of the box when it wasn't in view.
- **Editing from code.** `getCaretIndex()` and `setCaretIndex(i)` read and place the caret by its index into the text.
  `replaceText(start, end, text)` replaces a range as if it were selected and typed over, with the caret after it, as one
  step to undo, and fires `onTextChanged`; a read-only box ignores it. This is what taking a suggestion from a list of
  completions does.
- **Where things are.** `getLineAt(x, y)` is the line of the text, from 1, under a point in the window, for a click on it.
  `getCaretBounds()` is the caret's `{x, y, height}` in the window, for showing a list or a tip beside it.

A tab character is not laid out in tab stops: the box shows it as a single narrow glyph. Turn tabs in loaded code into
spaces before `setText`.

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
| Layout | `isWordWrap/setWordWrap`, `getLineSpacing/setLineSpacing`, `getPaddingX/setPaddingX`, `getPaddingY/setPaddingY`, `getScrollbarPadding/setScrollbarPadding` |
| Scrolling | `getScrollSpeed/setScrollSpeed`, `isScrollbarVisible/setScrollbarVisible`, `getScrollBarStyle/setScrollBarStyle`, `getScrollOffsetY`, `getMaxScrollY`, `scrollBy`, `scrollTo`, `getScrollOffsetX`, `getMaxScrollX`, `scrollByX`, `scrollToX` |
| Selection | `hasSelection`, `getSelectedText`, `clearSelection` |
| Callbacks | `onTextChanged`, `onSubmit`, `onFocusChanged` |
| Code | `setTypeface`, `getOptions().setIndentSize/setAutoIndent/setAutoCloseBrackets`, `setParenCloser`, `isLineNumbers/setLineNumbers`, `setLineNumberColor`, `setLineMarks`, `setHighlights`, `getHighlightCount`, `getLineCount`, `getCursorLine`, `getCursorColumn`, `goToLine`, `wantsTab`, `setKeyFilter`, `getCaretIndex/setCaretIndex`, `setSelection`, `replaceText`, `getLineAt`, `getCaretBounds` |
| Subclass hooks (protected) | `drawLineText`, `indexAtPoint`, `replaceRange`, `getCursorIndex` |
| Lifecycle | `dispose` |
