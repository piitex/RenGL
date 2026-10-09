# FormattedTextOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.FormattedTextOverlay` is **scrollable text built from headings, paragraphs, lists and code**,
parsed from raw marked-up source through a pluggable `TextFormatPreset`. It's the widget behind something like
a Terms of Service screen: mixed heading sizes and body text, wrapped and scrolled.

```java
FormattedTextOverlay tos = new FormattedTextOverlay(500, 360, new BracketFormatPreset());
tos.setSource("""
        [heading=1]TERMS AND CONDITIONS[/heading]
        By using this app you agree to the following.

        [heading=2]1. Definitions.[/heading]
        "License" shall mean ...
        """);
tos.useTheme();
root.addElement(tos);
```

## Constructors

| Constructor | Notes |
|---|---|
| `FormattedTextOverlay(double width, double height, TextFormatPreset preset)` | |
| `FormattedTextOverlay(double x, double y, double width, double height, TextFormatPreset preset)` | |

The preset can be switched later with `setPreset(...)`, which parses the current source again with it.

## How it's built

Internally this is a [`ScrollContainer`](../containers/scroll-container.md) holding one
[`TextFlowOverlay`](text-flow-overlay.md) per block the preset returns. Each block wraps and sizes exactly like
a plain `TextFlowOverlay`; the overlay stacks them by hand from each block's own height plus its
`spacingBefore`/`spacingAfter`, not through a [`Layout`](../layouts/README.md) — a Layout applies one uniform
gap to every child and has no way to give a heading more room than a paragraph.

```java
tos.setSource(newDocument); // re-parses and rebuilds every block; scroll position resets to the top
tos.setWidth(300);          // wraps the same blocks again, with no parsing
```

Calling `setSource` on every keystroke of a live edit is not the intended use. Parse once for a document that
changes occasionally, or once for a static one. Resizing is cheap: `setWidth` and `setHeight` wrap the blocks that
already exist, so a drag-resize can call them every frame.

## The TextFormatPreset interface

```java nocompile
@FunctionalInterface
public interface TextFormatPreset {
    List<FormattedBlock> parse(String source);
}

public record FormattedBlock(String text, TextStyle style, float spacingBefore, float spacingAfter,
                             float indent, String marker, Paint background, List<InlineRun> runs) {}

public record InlineRun(int start, int end, boolean bold, boolean italic, boolean code, String href) {}

public record TextStyle(float fontSize, boolean bold, boolean italic, Paint color) {}
```

A preset owns both the syntax **and** the resulting sizes, unlike
[`TextSpanStyler`](rich-text-area-overlay.md#coloring-text-with-a-textspanstyler) (which only recolors runs of
an already-fixed string, with no size or weight of its own). `TextStyle.color()` of `null` inherits the
destination's own theme color, the same convention `TextSpan` uses, and a color that is given is kept even when a
theme is applied. A block draws its text in one `TextStyle`; stretches inside it can be bold, italic, code or a link on top
of that through its `runs`.

A block can also be a **list item**: its `marker` (a bullet, or `1.`) is drawn at `indent`, and the text lines up beside it,
so a long item wraps under its own text and not under the bullet. A block with a `background` is a **code block**: the
paint is drawn behind it with a little padding round the text, and the line breaks in its text are kept. A preset that
makes plain paragraphs only uses the four-argument constructor, `new FormattedBlock(text, style, before, after)`.

An `InlineRun` marks `[start, end)` of the block's text: `bold` and `italic` are synthesized like `TextStyle`'s, `code` draws
in the monospace font on a faint chip, and a run with an `href` is drawn underlined in the theme's accent color and opens
that address when clicked. Runs may overlap (bold inside a link), and the stretches wrap and align together with the plain
text. Without `runs` a block is drawn exactly as before.

RenGL ships two presets:

### MarkdownFormatPreset

The part of Markdown that documentation needs:

- `#` to `######` line prefixes (the same cap CommonMark uses). A heading needs a space after its `#`s — a bare
  `"#tag"` stays plain text.
- A blank line separates paragraphs; everything else on non-blank lines joins the current paragraph.
- Bulleted lists (`- item`, `* item` or `+ item`) and numbered ones (`1. item`). A line indented under an item carries
  on its text, and an item indented two spaces or more sits one level in.
- Fenced code between lines of three backticks. Line breaks and spacing are kept, and the block is drawn on a faint
  background (`setCodeBackground`, or null for none) in the code style (`setCodeStyle`).
- A line of three or more `-`, `*` or `_` ends the paragraph before it and draws nothing.

Inline marks style the text inside a block:

| Mark | Result |
|---|---|
| `**bold**`, `__bold__` | bold |
| `*italic*`, `_italic_` | italic |
| `***both***` | bold and italic |
| `` `code` `` | monospace, with nothing inside interpreted |
| `[text](address)` | a link, with the text itself able to hold emphasis |
| `\*` | the mark as a plain character |

A mark that never closes stays as written, as do asterisks with spaces round them (`2 * 3 * 4`) and underscores inside a
word (`snake_case`). Tables, quotes and pictures read as paragraphs.

A click on a link goes to `setLinkHandler(Consumer<String>)`. The default opens the address through
[`Browser.open`](../reference/browser.md), which only takes web and mail addresses; pass a handler to route links
inside your app instead, or null to make links inert.

```java
MarkdownFormatPreset preset = new MarkdownFormatPreset();
preset.setHeadingStyle(1, new TextStyle(30f, true, false, Color.WHITE));
preset.setParagraphSpacing(14f);
preset.setBodyStyle(new TextStyle(14f, false, false, Color.WHITE));
preset.setCodeBackground(new Color(0f, 0f, 0f, 0.3f));

FormattedTextOverlay doc = new FormattedTextOverlay(500, 360, preset);
doc.setSource("""
        # Title

        First paragraph.

        ## Section
        - one
        - two, which is long enough that it wraps under its own text and not under the bullet

        ```
        window.open();
        ```
        """);
```

### BracketFormatPreset

`[heading=N]...[/heading]` tags, with `N` unbounded (any level past 6 falls back to the level-6 style).
Everything outside a tag is paragraph text, split into blocks on blank lines. Reach for this one when porting
content already authored in a different toolkit's bracket-tag convention — it drops in unmodified.

```java
BracketFormatPreset preset = new BracketFormatPreset();
FormattedTextOverlay tos = new FormattedTextOverlay(500, 360, preset);
tos.setSource(rawTosText); // [heading=1]...[/heading], verbatim
```

### Default sizing (both presets)

| Level | Size | Weight |
|---|---|---|
| 1 | 28pt | bold |
| 2 | 24pt | bold |
| 3 | 20pt | bold |
| 4 | 17pt | bold |
| 5 | 15pt | bold |
| 6 | 14pt | bold |
| body | 14pt | regular |

Spacing: 20pt above a heading (0 if it opens the document), 6pt below a heading, 10pt between paragraphs.
Override any of it before calling `setSource`:

```java
preset.setHeadingStyle(2, TextStyle.of(18f)); // non-bold h2
preset.setBodyStyle(new TextStyle(15f, false, true, null)); // italic body
preset.setHeadingSpacingBefore(24f);
preset.setHeadingSpacingAfter(8f);
preset.setParagraphSpacing(12f); // also above and below a list or a code block
preset.setListItemSpacing(3f);
```

### Writing your own preset

Implement the interface directly for a different syntax, or a sizing scheme the two defaults don't cover:

```java
TextFormatPreset shouting = source -> {
    List<FormattedBlock> blocks = new ArrayList<>();
    for (String paragraph : source.split("\n\n")) {
        if (paragraph.isBlank()) continue;
        blocks.add(new FormattedBlock(paragraph.strip().toUpperCase(), TextStyle.of(16f), blocks.isEmpty() ? 0f : 12f, 0f));
    }
    return blocks;
};

FormattedTextOverlay yelling = new FormattedTextOverlay(400, 200, shouting);
```

Neither built-in preset is privileged; `FormattedTextOverlay` only ever calls `parse(String)`.

## Bold and italic without a second font file

`TextOverlay.setBold(boolean)`/`setItalic(boolean)` — inherited by every text overlay, including
`TextFlowOverlay` — synthesize weight and slant on whatever typeface is already loaded, via Skia's font
embolden/skew, rather than requiring a bold or italic cut of the font file. `FormattedTextOverlay` uses this
internally for every block's `TextStyle.bold()`/`TextStyle.italic()`. It doesn't look quite as clean as a real
bold font, but works with any typeface. See [TextOverlay](text-overlay.md#weight-and-slant-bold-and-italic).

## Method reference

| Method | Description |
|---|---|
| `getSource()/setSource(String)` | The raw marked-up text; setting re-parses and rebuilds every block |
| `getPreset()/setPreset(TextFormatPreset)` | The syntax; setting parses the current source again with it |
| `setLinkHandler(Consumer<String>)` | What a click on a link does; default opens it in the browser |
| Inherited | Everything on [`ScrollContainer`](../containers/scroll-container.md) — scroll speed, scrollbar style, `scrollTo`/`scrollBy`, theming |

## Gotchas

- One `TextFlowOverlay` per block means block count matters for a very long document; a multi-thousand-block
  document was not a design target.
- `setWidth(...)` and `setHeight(...)` wrap every block again, which costs a pass over the text but no parsing. A
  very long document resized every frame still pays for that pass.
- Bold, italic and code inside a block use the synthesized bold and slant, so they look a little rougher than a real
  bold or italic font. A link run is clicked through the block, so a block with a link takes clicks that would otherwise
  go to whatever is behind it.
