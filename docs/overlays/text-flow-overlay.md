# TextFlowOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.TextFlowOverlay` is **read-only text that flows within a fixed-width box**: it
word-wraps, can be aligned, and sizes itself to fit. It is a label for paragraphs, and the display-only
counterpart of [`TextAreaOverlay`](text-area-overlay.md) (same word wrap and line spacing, but no caret,
selection or focus).

It extends [`TextOverlay`](text-overlay.md), so font, size, text color, gradients and theming all work exactly
as they do there, including `setTextColor(...)` pinning the color against theme switches.

```java
TextFlowOverlay blurb = new TextFlowOverlay("A long paragraph that wraps to the box width...", 360);
blurb.setPadding(10);
blurb.getStyling().setBackgroundColor(new Color(0.2f, 0.2f, 0.22f, 1f));
blurb.getStyling().setCornerRadius(6f);
panel.addElement(blurb);
```

## Constructors

| Constructor | Notes |
|---|---|
| `TextFlowOverlay(String text, double width)` | 16pt, black, default font |
| `TextFlowOverlay(String text, double width, float fontSize, Paint textColor)` | Default font |
| `TextFlowOverlay(String text, double width, File fontFile, float fontSize, Paint textColor)` | Custom font, cached by path |

`width` is the box width. The height is computed (see [Height](#height-and-max-height)).

Like `TextOverlay`, the background is **transparent with no border** by default. Set one through
`getStyling()` if you want a visible box.

## Wrapping

- Text is greedily word-wrapped to the width, minus padding (and minus the scrollbar while it is showing).
- `\n` forces a line break; a blank line is a paragraph break.
- A single word wider than the box is broken mid-word rather than overflowing.
- Changing the text, font, size, width, padding or line spacing re-wraps automatically.

## Styled runs

`setRuns(List<InlineRun>)` makes stretches of the text bold, italic, monospace or a link. The runs index into the
current text, are measured in their own fonts when wrapping, and wrap and align together with the plain text.
Setting the text again drops them, so set them after the text.

```java
TextFlowOverlay note = new TextFlowOverlay("Read the guide for more.", 300);
note.setRuns(List.of(new InlineRun(9, 14, false, false, false, "https://example.com/guide")));
note.setLinkHandler(address -> openInApp(address)); // default: Browser.open
```

A link run is drawn in the theme accent, underlined, and shows the pointer cursor when hovered. An element with no link
runs takes no clicks of its own, so it does not stop a click from reaching what is behind it. Most code gets runs
from [`FormattedTextOverlay`](formatted-text-overlay.md) and `MarkdownFormatPreset` rather than building them by hand.

## Alignment, spacing and padding

```java
blurb.setTextAlign(TextAlign.CENTER); // me.piitex.engine.ui.text.TextAlign: LEFT (default), CENTER, RIGHT
blurb.setLineSpacing(4f); // extra px between lines, default 2
blurb.setPadding(10f); // inset on all four sides, default 0
```

There is no justified alignment.

## Height and max height

By default the height **tracks the wrapped text**, so a [`Layout`](../layouts/README.md) spaces the overlay
correctly.

| Setting | Behavior |
|---|---|
| default (`autoHeight` on, no max) | Grows to fit all the text |
| `setMaxHeight(h)` | Grows up to `h`, then stops and **scrolls** |
| `setAutoHeight(false)` + `setHeight(h)` | Fixed height; overflowing text scrolls |
| `setMaxLines(n)` | Cuts the text at `n` lines, ending the last in "…". Nothing is left to scroll |

```java
blurb.setMaxHeight(120); // 0 (default) = no cap
```

## Scrolling

When the text is taller than the box, it scrolls by mouse wheel (via [`Scrollable`](../input/input-and-focus.md))
and by dragging the scrollbar thumb. The thumb is only drawn when there is something to scroll. It uses the
same [`ScrollBarStyle`](../containers/scroll-container.md) as `ScrollContainer` and `TextAreaOverlay`, and
follows the theme's scroll colors as a baseline.

```java
blurb.setScrollbarVisible(false); // wheel scrolling still works
blurb.setScrollSpeed(60f); // points per wheel unit, default 40
blurb.getScrollBarStyle().setThickness(8f);
blurb.scrollTo(0); // programmatic scrolling, clamped
```

## Method reference

| Method | Description |
|---|---|
| `getTextAlign()/setTextAlign(TextAlign)` | `LEFT`, `CENTER`, `RIGHT` |
| `getLineSpacing()/setLineSpacing(float)` | Extra gap between lines |
| `getPadding()/setPadding(float)` | Inset inside the box |
| `getMaxLines()/setMaxLines(int)` | Line cap with ellipsis; 0 = none |
| `isAutoHeight()/setAutoHeight(boolean)` | Height follows the text |
| `getMaxHeight()/setMaxHeight(float)` | Height cap before scrolling; 0 = none |
| `isScrollbarVisible()/setScrollbarVisible(boolean)` | Thumb drawn/draggable |
| `getScrollSpeed()/setScrollSpeed(float)` | Wheel speed |
| `getScrollBarStyle()` | Thumb/track colors and geometry |
| `getMaxScrollY()`, `scrollBy(double)`, `scrollTo(double)` | Programmatic scrolling |
| `getRuns()/setRuns(List<InlineRun>)` | Bold, italic, code and link stretches within the text |
| `setLinkHandler(Consumer<String>)` | What a click on a link run does |

Everything else (`setText`, `setFontSize`, `setFontFile`, `setTextColor`, `setBold`/`setItalic`, `dispose()`,
...) is inherited from [`TextOverlay`](text-overlay.md). Call `dispose()` when permanently discarding the
overlay.

For a document mixing multiple heading sizes and body text in one scrollable block (a whole `TextFlowOverlay`
only ever draws at one size), see [`FormattedTextOverlay`](formatted-text-overlay.md), which stacks several of
these, one per heading/paragraph, built from Markdown or bracket-tag source.
