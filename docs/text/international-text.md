# International text and input methods

[Documentation](../README.md) › [Text editing](README.md) › International text

The engine draws and edits text in any script, and works with the operating system's input method (IME), the thing that
lets an ordinary keyboard type Chinese, Japanese, Korean and the other languages with more characters than keys.

## Drawing

Text made only of Latin letters (everything below U+0300) is drawn as it always was. Anything else goes through Skia's
shaper, which:

- **falls back to other fonts** for every glyph your typeface lacks, so Chinese, Japanese, Korean, emoji and the rest
  show up without you shipping a font for them;
- **reorders right-to-left text**, so Arabic and Hebrew read the right way round, even mixed with Latin or digits;
- **shapes scripts whose letters change with their neighbours**: Arabic joining, Thai stacked marks, Devanagari and
  other Indic conjuncts.

This applies everywhere text is drawn or measured: `TextOverlay`, `ButtonOverlay`, the text fields and areas, menus, tables,
trees, tooltips and `Renderer.drawText`. A shaped line is cached, so repeated measuring of the same text is cheap.

Fallback fonts come from the operating system. Windows and macOS have fonts for every major script. **A Linux install
needs one**, such as Noto Sans CJK; without it those glyphs draw as boxes. A font you load yourself is still used first.

## Editing

`TextFieldOverlay` and `TextAreaOverlay`:

- The caret, selection and click-to-position follow the shaped text, so they land on the right letter in Arabic and
  Thai as well as Latin.
- Left and Right move by whole characters: an accented letter written as two code points, a Thai consonant with its
  vowel mark, or an emoji, counts as one step. In right-to-left text the left arrow goes on through the text.
- Backspace removes one code point (so the last combining mark goes first); Delete removes a whole character.

## Input methods

While a text field or area has focus the engine turns the system's IME on, tells it where the caret is so its candidate list opens beside the text, and shows the text being composed.

- The composition appears at the caret, underlined. The clause being converted gets a thicker underline.
- In a `TextFieldOverlay` the composition pushes the text after the caret along. In a `TextAreaOverlay` it is drawn over a patch of the background, so the lines below do not reflow on every keystroke.
- The composition is not part of the text: `getText()`, change listeners, undo and `maxLength` see the characters only once they are committed.
- The IME is off when nothing, or something that is not a text field, has focus, so a game that reads keys as controls never gets a composition window. It is also off in password fields and read-only boxes.
- Losing focus cancels any composition.

Nothing needs enabling. For a widget of your own, implement the new `Focusable` methods:

| Method | Meaning |
|---|---|
| `acceptsTextInput()` | True if the IME should be on while this has focus. False by default |
| `onPreedit(ImePreedit)` | The composition changed: its text, the input method's caret, and the clause being converted. An empty text means it ended |
| `imeCaretRect()` | `{x, y, width, height}` of the caret in window coordinates, for the candidate list. `null` puts it under the widget |

The committed text still arrives through `onCharTyped`, as it does for any typing.

## Limits

- The GLFW preedit API is only as good as the platform behind it. It was exercised on Windows. macOS and Linux
  (IBus, fcitx) use the same code but have not been run with a real input method.
- Per-key behaviour differs between systems: on some, an IME takes Enter or Space for itself while composing, and the engine never sees them.
- Mixed-direction text (an Arabic sentence with an English word in it) draws correctly, but the caret and selection are not split into the several pieces they would be in a full bidirectional editor: the arrow keys follow the text's overall direction, and a selection across a direction change is drawn as one block.
- `RichTextAreaOverlay` shares `TextAreaOverlay`'s composition display, which draws the composition in the plain text colour, not in a span's.
- Wrapping breaks Chinese and Japanese between characters and Thai between words (using the JDK's line-break rules, which have no Thai dictionary on every runtime, so long Thai runs may wrap between characters).
