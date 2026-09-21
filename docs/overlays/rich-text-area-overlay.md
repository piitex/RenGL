# RichTextAreaOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.RichTextAreaOverlay` is the advanced [`TextAreaOverlay`](text-area-overlay.md).
Everything about the plain type still works identically (word wrap, selection, clipboard, scrolling,
multi-line editing). This subclass adds two optional features:

1. **A styler** that colors runs of text, without the engine hard-coding any syntax.
2. **A checker** that finds spelling (and, with a suitable checker, grammar) problems and underlines them.
   Right-clicking an underlined word offers corrections.

With neither set, it draws exactly like a [`TextAreaOverlay`](text-area-overlay.md).

```java
RichTextAreaOverlay chat = new RichTextAreaOverlay(20, 20, 400, 240);
chat.setText("She said \"hello there\" and *waved* at me.");
root.addElement(chat);
```

## Constructors

Same four as [`TextAreaOverlay`](text-area-overlay.md):

```java
new RichTextAreaOverlay(400, 240);
new RichTextAreaOverlay(20, 20, 400, 240);
new RichTextAreaOverlay(20, 20, 400, 240, 16f, Color.BLACK);
new RichTextAreaOverlay(20, 20, 400, 240, new File("font.ttf"), 16f, Color.BLACK);
```

## Coloring text with a `TextSpanStyler`

A `TextSpanStyler` is a function from the whole text to a list of colored `TextSpan`s. The engine has **no
built-in markdown or quote rules**. You decide the syntax.

```java nocompile
@FunctionalInterface
public interface TextSpanStyler {
    List<TextSpan> style(String text);
}

public record TextSpan(int start, int end, Paint color) {}
```

`start` is inclusive and `end` exclusive, as indexes into the whole text (the same indexing as
`String.substring`). A `null` color means "use the normal text color".

```java
Color quoteColor = new Color(0.40f, 0.75f, 0.95f, 1f);
Color emphasisColor = new Color(0.95f, 0.60f, 0.25f, 1f);

chat.setStyler(text -> {
    List<TextSpan> spans = new ArrayList<>();

    Matcher quotes = Pattern.compile("\"[^\"]*\"").matcher(text);
    while (quotes.find()) {
        spans.add(new TextSpan(quotes.start(), quotes.end(), quoteColor));
    }

    Matcher emphasis = Pattern.compile("\\*[^*]*\\*").matcher(text);
    while (emphasis.find()) {
        spans.add(new TextSpan(emphasis.start(), emphasis.end(), emphasisColor));
    }
    return spans;
});
```

(You need `java.util.regex.*` and `me.piitex.engine.ui.text.TextSpan` imported.)

Rules:

- The styler is re-run **only when the text has actually changed** since the last draw, not every frame.
  Keep it cheap. It runs on the render thread. A full spell-check pass or a network call does not belong here.
- Return spans **sorted by start and non-overlapping**. The overlay sorts by start for you, but overlapping
  spans give a rendering glitch rather than an error.
- Spans that cross a wrapped line boundary are split per line automatically.
- `setStyler(null)` removes coloring. `getStyler()` returns the current one.
- Coloring and spell-check underlines are independent. Both can be active at once.

## Spell checking

```java
chat.setChecker(HunspellChecker.english()); // throws IOException if the bundled dictionary is missing
```

That is all that is needed. How it behaves:

- **Debounced.** The text is sent to the checker `RichTextAreaOverlay.CHECK_DEBOUNCE_MS` (400 ms) after the
  last change, on a background thread. It never blocks the UI, and a word you are still typing is not
  flagged mid-word.
- **Underlines.** Each issue gets a squiggly underline: **red** for spelling, **blue** for grammar.
- **Stale results are hidden.** An underline is only drawn while the word under it is still exactly the
  text that was checked. After an edit shifts text around, affected underlines disappear until the next
  check finishes rather than landing on the wrong word.
- **Right-click a flagged word** to open a popup with up to 6 suggestions. Clicking one replaces the word
  and moves the caret after it. With no suggestions the popup shows a non-clickable "No suggestions" row. It
  closes on an outside click or Escape.

```java
TextChecker checker = chat.getChecker(); // null when spell checking is off
chat.setChecker(null); // turn it off
```

Everything else about checking (the [`TextChecker`](../text/spell-checking.md#writing-your-own-checker) interface, the bundled Hunspell dictionary, custom
dictionaries and other languages, plugging in a grammar engine) is on the
[Spell checking](../text/spell-checking.md) page.

## Example: chat transcript with colors and spell check

```java
RichTextAreaOverlay transcript = new RichTextAreaOverlay(20, 20, 420, 260);
transcript.setPlaceholder("Type your message...");

transcript.setStyler(text -> {
    List<TextSpan> spans = new ArrayList<>();
    Matcher m = Pattern.compile("\"[^\"]*\"").matcher(text);
    while (m.find()) {
        spans.add(new TextSpan(m.start(), m.end(), new Color(0.4f, 0.75f, 0.95f, 1f)));
    }
    return spans;
});

try {
    transcript.setChecker(HunspellChecker.english());
} catch (IOException e) {
    e.printStackTrace(); // the app still works, just without spell checking
}

transcript.onSubmit(message -> {
    System.out.println("Send: " + message);
    transcript.setText("");
});
root.addElement(transcript);
```

## Method reference

| Method | Description |
|---|---|
| `getStyler()` / `setStyler(TextSpanStyler)` | The color styler, or `null` |
| `getChecker()` / `setChecker(TextChecker)` | The spell/grammar checker, or `null` |
| `CHECK_DEBOUNCE_MS` | `400`, the idle time before a check starts |
| Inherited | Everything on [`TextAreaOverlay`](text-area-overlay.md) |

## Gotchas

- Do not replace the click listener with `onMouseClick(...)`. The overlay registers its own to open the
  suggestion popup on right-click, and replacing it disables that.
- `setText(...)` is silent (it fires no `onTextChanged`), but the checker still notices the new text on the
  next frame and re-checks it.
- Suggestions are computed when you right-click, not in the background, so a very slow custom checker would
  make the popup appear late.
