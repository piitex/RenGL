# Styling

[Documentation](../README.md) › [Styling](README.md)

Every [`Element`](../core/element.md) owns a `Styling` object (`element.getStyling()`) holding its box
appearance:

| Property | Getter / setter | Default (fresh [`Element`](../core/element.md)) |
|---|---|---|
| Background color | `getBackgroundColor()` / `setBackgroundColor(Paint)` | Transparent |
| Hover color | `getHoverColor()` / `setHoverColor(Paint)` | Transparent |
| Border color | `getBorderColor()` / `setBorderColor(Paint)` | Transparent |
| Border thickness | `getBorderThickness()` / `setBorderThickness(float)` | `1` |
| Corner radius | `getCornerRadius()` / `setCornerRadius(float)` | `0` |
| Text hover color | `getTextHoverColor()` / `setTextHoverColor(Paint)` | `null` (text keeps its normal color) |

```java
panel.getStyling().setBackgroundColor(new Color(0.12f, 0.12f, 0.14f, 1f));
panel.getStyling().setHoverColor(new Color(0.16f, 0.16f, 0.18f, 1f));
panel.getStyling().setBorderColor(Color.WHITE);
panel.getStyling().setBorderThickness(2f);
panel.getStyling().setCornerRadius(12f);
```

## How it is drawn

Each frame, `Element.render` draws, in this order:

1. The **background fill**, at the element's bounds. While the element is hovered it uses the hover color,
   otherwise the background color. Both may be a [`Color`](colors-and-paints.md#color) or a [`LinearGradient`](colors-and-paints.md#gradients). Rounded by the corner radius.
2. The attached [`Background`](backgrounds.md) effect, if any.
3. The **border**, only if the thickness is above 0. It may be a `Color` or a `LinearGradient`, and follows the
   same corner radius.
4. Your `onRender` hook.
5. Anything the specific widget draws on top (text, image, ...).

Because hover switches the fill, set the hover color to the same value as the background when an element should
not react to the mouse (that is what themed containers do). A [`Container`](../containers/container.md) does this on
its own: its hover color follows its background until you call `setHoverColor`, so a panel you give only a
background color never flashes on hover. `setHoverFollowsBackground(true)` gives any element the same behavior.

## Defaults by type

A fresh [`Element`](../core/element.md) is fully transparent: transparent background and hover colors, and a 1pt
border in a transparent color. Most widgets change that:

| Type | Default look |
|---|---|
| [`Container`](../containers/container.md), layouts | Transparent, hover follows the background. `useTheme()` gives the themed panel look |
| [`TextOverlay`](../overlays/text-overlay.md), [`ImageOverlay`](../overlays/image-overlay.md), [`SeparatorOverlay`](../overlays/separator-overlay.md), [`LoadingSpinnerOverlay`](../overlays/loading-spinner-overlay.md) | Transparent, no border |
| [`ButtonOverlay`](../overlays/button-overlay.md), [`TextFieldOverlay`](../overlays/text-field-overlay.md), [`TextAreaOverlay`](../overlays/text-area-overlay.md), [`CheckBoxOverlay`](../overlays/checkbox-overlay.md), ... | Themed boxes |
| [`SpinnerOverlay`](../overlays/spinner-overlay.md) | Transparent outer box, themed field and buttons inside |

A container that only groups children is therefore invisible without any styling. To give one a look, set a
background or opt it into the theme:

```java
container.getStyling().setBackgroundColor(new Color(0.95f, 0.95f, 0.95f, 1f)); // hover follows it
container.useTheme(); // or: the theme's panel background, border and radius
```

## Pinning: how styling interacts with themes

Every `Styling` property is backed by an [`Overridable`](theming.md#the-pinning-rule). It works like this:

- A **theme** sets a property as a *baseline* (`applyThemeBackgroundColor`, ...). It applies only if you have not
  set that property yourself.
- **Your call** to a plain setter (`setBackgroundColor`, ...) always takes effect and **pins** the property, so
  later theme switches leave it alone.

```java
button.getStyling().setBackgroundColor(Color.RED); // pinned red; a theme switch will not change it
```

If you want an element to follow the theme again you have to construct a fresh one. There is no "unpin" method.
See [Theming](theming.md).

## Text hover color

```java
link.getStyling().setTextHoverColor(Color.BLUE); // text turns blue while hovered
link.getStyling().setTextHoverColor(null); // back to the normal text color
```

Widgets that draw text use this color for their text while hovered, independent of the hover *fill*. `null` (the
default) keeps the normal text color. It takes any [`Paint`](colors-and-paints.md), and an animated one advances like
the other styling paints. It is not themed, so a theme switch never changes it.

## Replacing the whole `Styling`

```java
Styling s = new Styling();
s.setBackgroundColor(Color.BLACK);
element.setStyling(s);
```

`setStyling` replaces the object wholesale, **including the defaults a widget's constructor set** (a
[`TextOverlay`](../overlays/text-overlay.md)'s transparent background, for example). Prefer mutating `getStyling()` in place.

## Opacity

Style colors carry their own alpha. For the whole element (background, border, content and children) use
[`setOpacity`/`fadeIn`/`fadeOut`](../animation/transitions.md).

```java
TextOverlay overlay = new TextOverlay("Caption", 16f, Color.WHITE);
overlay.getStyling().setBackgroundColor(new Color(0f, 0f, 0f, 0.5f)); // translucent fill only
overlay.setOpacity(0.5f); // everything, as one unit
```

## Rounded corners and clipping

A container with a corner radius clips its children to the **rounded** shape, so a child filling the container
(an image, a header strip) follows its rounded corners. The clip is the container's outer edge, so a child drawn
right up to the edge covers the border there; add padding to a layout to keep children inside the border.

## Method reference

| Method | Purpose |
|---|---|
| `getBackgroundColor` / `setBackgroundColor` | Fill at rest |
| `getHoverColor` / `setHoverColor` | Fill while hovered |
| `setHoverFollowsBackground` | Make the hover fill track the background (containers start this way) |
| `getTextHoverColor` / `setTextHoverColor` | Text color while hovered, or `null` |
| `getBorderColor` / `setBorderColor` | Border (plain [`Color`](colors-and-paints.md#color) only renders) |
| `getBorderThickness` / `setBorderThickness` | Border width in points |
| `getCornerRadius` / `setCornerRadius` | Corner rounding in points |
| `applyThemeXxx` | Theme-facing versions that do not pin (used inside `Themeable.applyTheme`) |
