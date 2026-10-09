# Layouts

[Documentation](../README.md) › Layouts

A **layout** is a [`Container`](../containers/container.md) that positions its own children automatically,
so you do not set each child's `x`/`y` by hand. Everything else about a container (styling, theming, click
and update hooks, transitions, dragging) works exactly as it does on a plain [`Container`](../containers/container.md).

| Layout | Arranges children | Page |
|---|---|---|
| [`VerticalLayout`](vertical-layout.md) | in a single column, top to bottom | [vertical-layout.md](vertical-layout.md) |
| [`HorizontalLayout`](horizontal-layout.md) | in a single row, left to right | [horizontal-layout.md](horizontal-layout.md) |
| [`GridLayout`](grid-layout.md) | in a table with a fixed column count | [grid-layout.md](grid-layout.md) |
| [`StackLayout`](stack-layout.md) | layered on top of one another | [stack-layout.md](stack-layout.md) |
| [`FlowLayout`](flow-layout.md) | in rows that wrap when one is full | [flow-layout.md](flow-layout.md) |
| [`DockLayout`](dock-layout.md) | pinned to the edges of the space that is left, the last one filling it | [dock-layout.md](dock-layout.md) |
| [`SplitContainer`](../containers/split-container.md) | two resizable panes with a draggable divider | [split-container.md](../containers/split-container.md) |
| [`TabContainer`](../containers/tab-container.md) | a tab strip that switches between pages of content | [tab-container.md](../containers/tab-container.md) |

All of them live in `me.piitex.engine.ui.layout`.

## How positioning works

- A layout sets each child's `x`/`y` **every frame**, relative to the layout (in `update`) and once immediately on every `addElement`.
  You do not call anything. Positioning a layout child by hand is pointless, because the layout overwrites it,
  and an [anchor](../core/element.md#anchoring) set on one is ignored. Anchor the layout itself instead.
- **Children keep their own size,** unless you ask otherwise: a [weight](#sharing-the-space-left-over) or a
  [relative size](../core/element.md#sizing-by-fraction) stretches a child. ([`SplitContainer`](../containers/split-container.md)
  and [`TabContainer`](../containers/tab-container.md) size their panes to fill the available space.)
- **A layout keeps the size you gave it,** unless you turn on sizing to content (below). Children that
  overflow just draw past the layout's box (and are clipped to it, since containers clip by default).
- Layouts nest freely. A [`HorizontalLayout`](horizontal-layout.md) inside a [`VerticalLayout`](vertical-layout.md) is repositioned along with its
  parent, and repositions its own children.
- `relayout()` recomputes positions immediately. Call it if you change a child's size and need the new
  positions before the next frame.

## Sharing the space left over

In a `HorizontalLayout` or `VerticalLayout`, `child.setLayoutWeight(weight)` gives a child a share of what is left along
the layout's own axis once the children without a weight and the spacing have their sizes. Weights 1 and 2 split it a
third and two thirds, whatever the children measured before.

```java
HorizontalLayout bar = new HorizontalLayout(800, 40);
bar.setSpacing(8);
bar.addElement(back);                    // keeps its own width
bar.addElement(title);                   // takes what is left
title.setLayoutWeight(1);
title.setRelativeHeight(1);              // and is as tall as the bar, across the layout's other axis
bar.addElement(menu);
```

- The share is worked out every frame, so it follows the layout as it is resized. A child that is a layout is sized
  first, then arranges its own children.
- Across the layout's axis, use a [relative size](../core/element.md#sizing-by-fraction): `setRelativeHeight(1)` fills a row.
- A weight wins over a relative size along the axis, and does nothing in a layout that sizes itself to its content
  on that axis, which would have no space left over. In a layout file it is `weight`.

## Shared settings

Every layout inherits these from `Layout`:

| Setting | Meaning | Default |
|---|---|---|
| `setSpacing(float)` | Gap between children along the layout's axis | `0` |
| `setPadding(float)` | Inset between the layout's edges and its children | `0` |
| `setAlignment(Alignment)` | Where the children sit within the layout (see below) | `TOP_LEFT` |
| `setSizeToContent(boolean)` / `setSizeToContent(width, height)` | Size the layout to its children (see below) | off |

`Layout.Alignment` is one setting with nine positions: `TOP_LEFT`, `TOP_CENTER`, `TOP_RIGHT`, `CENTER_LEFT`,
`CENTER`, `CENTER_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_CENTER`, `BOTTOM_RIGHT`.

- `VerticalLayout` / `HorizontalLayout`: places the whole block of children within the padded area.
- `GridLayout`: places each child within its cell.
- `StackLayout`: places each child within the layout.
- `FlowLayout`: places each row within the width, and the block of rows within the height.
- `DockLayout`: not used; each child is pinned to an edge instead.

## Sizing to content

`setSizeToContent(true)` makes a layout's width and height follow its children: `getContentWidth()` /
`getContentHeight()` (what the children need, spacing included) plus the padding on both sides. It is
kept up to date every frame, so adding, removing or resizing a child resizes the layout. Pass two flags
to fit only one axis. The common case is a column with a fixed width and a fitted height:

```java
VerticalLayout list = new VerticalLayout(280, 0);
list.setSpacing(6);
list.setSizeToContent(false, true); // width stays 280, height follows the rows
```

- Nested layouts that also size to content are measured first, so a column of fitted rows is right in one
  pass.
- The constructor size still matters for any axis left off.
- [`SplitContainer`](../containers/split-container.md) and [`TabContainer`](../containers/tab-container.md)
  fill their own size, so sizing them to content changes nothing. Sizing a pane of one to content also changes
  nothing, because the container sets the pane's size.
- `getContentWidth()` / `getContentHeight()` can be read at any time, whether or not the layout sizes to them.

## Styling a layout

A layout is an [`Element`](../core/element.md), and a fresh one is **transparent**: it only positions things and draws
nothing itself. To make it a visible panel, give it a background or opt it into the theme:

```java
// A themed panel:
layout.useTheme();

// Or your own colors:
layout.getStyling().setBackgroundColor(new Color(0.95f, 0.95f, 0.95f, 1f));
layout.getStyling().setCornerRadius(8f);
```

`useTheme()` opts the layout into the current [theme](../styling/theming.md) (panel background, border,
radius). This is what the demo does for its main form panel.

## A form built from nested layouts

```java
VerticalLayout form = new VerticalLayout(100, 100, 360, 260);
form.setSpacing(12);
form.setPadding(16);
form.useTheme();

HorizontalLayout nameRow = new HorizontalLayout(320, 28);
nameRow.setSpacing(8);
nameRow.addElement(new TextFieldOverlay(150, 28));
nameRow.addElement(new TextFieldOverlay(150, 28));

form.addElement(new TextOverlay("Name", 14f, Color.BLACK));
form.addElement(nameRow);
form.addElement(new ButtonOverlay("Save", 120, 32));

root.addElement(form);
```

## Choosing a layout

- A list of things, one per line: [`VerticalLayout`](vertical-layout.md).
- A toolbar or a checkbox next to its label: [`HorizontalLayout`](horizontal-layout.md).
- Label/field pairs that must line up in columns: [`GridLayout`](grid-layout.md) with two columns.
- A badge on an icon, or a spinner over a button: [`StackLayout`](stack-layout.md).
- Tags, chips or thumbnails that must wrap: [`FlowLayout`](flow-layout.md).
- A header, a side panel and a content area that fills the rest: [`DockLayout`](dock-layout.md).
- Resizable side-by-side or stacked panes: [`SplitContainer`](../containers/split-container.md).
- Several pages of content, one visible at a time: [`TabContainer`](../containers/tab-container.md).
- Content taller than its box: put a layout that sizes to content inside a [`ScrollContainer`](../containers/scroll-container.md).
