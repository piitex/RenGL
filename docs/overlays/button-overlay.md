# ButtonOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.ButtonOverlay` is a clickable, themed button. It extends
[`TextOverlay`](text-overlay.md), so the label, font and text-color API is inherited. What it changes:

- It has an explicit **width and height** (a box), instead of sizing to its text.
- The label is **centered** in the box.
- Its default look is an opaque, themed box (background, hover color, border, corner radius).
- The mouse cursor becomes a **pointer** on hover.
- It has an `onAction` callback.
- It can show an **icon** beside its label, be **disabled** (visible but faded), and **shorten** a label that is too long.

```java
ButtonOverlay submit = new ButtonOverlay("Submit", 150, 36);
submit.onAction(() -> System.out.println("Submitted"));
submit.setTooltip("Sends the form");
layout.addElement(submit);
```

## Constructors

| Constructor |
|---|
| `ButtonOverlay(String text, double width, double height)` |
| `ButtonOverlay(String text, float fontSize, Paint textColor, double width, double height)` |
| `ButtonOverlay(String text, File fontFile, float fontSize, Paint textColor, double width, double height)` |

## Reacting to clicks

```java
button.onAction(() -> System.out.println("clicked"));
```

`onAction` fires when the **left** button is **released** over the button, and only if the press began on it.
Moving off before letting go cancels the click, and other mouse buttons do nothing. Only one action is held.
Calling `onAction` again replaces it.

`onMouseClick` is safe to use on a button: `onAction` is fired by a handler of its own, and your listener runs after it
and does not replace it.

`onAction` does not give the click's position. For that, set `onMouseClick` on the button; it receives the event.
To react to the other mouse buttons, use a plain [`Element`](../core/element.md).

## Look and theme

The button follows the theme's button tokens (`buttonBackground`, `buttonHoverBackground`, `buttonBorder`,
corner radius, border thickness) and its label uses the theme text color.

```java
button.getStyling().setBackgroundColor(new Color(0.2f, 0.5f, 0.9f, 1f));
button.getStyling().setHoverColor(new Color(0.3f, 0.6f, 1f, 1f));
button.getStyling().setCornerRadius(18f);
button.setTextColor(Color.WHITE); // pin the label color
```

Anything you set explicitly is pinned against future theme switches. See [Theming](../styling/theming.md).
For an animated look you can also use a gradient background:

```java
button.getStyling().setBackgroundColor(new ScrollingGradient(Color.BLUE, Color.PURPLE, 80f));
button.getStyling().setBorderColor(new RainbowColor()); // RGB-cycling border
```

## Changing the label

```java
button.setText("Saving...");
button.setFontSize(18f);
```

Unlike a plain [`TextOverlay`](text-overlay.md), changing the text does **not** resize a button. Its box is whatever width and
height you gave it, and the label is re-centered.

## An icon beside the label

```java
ButtonOverlay add = new ButtonOverlay("Add files", 140, 36);
add.setIcon(Feather.PLUS);            // any IconAsset; the icon and label are centered as a pair
add.setIconAfterText(true);           // for a chevron that says a menu opens
add.setIconSize(18);                  // points; the default is 1.25 times the font size
add.setIconGap(8);                    // between icon and label; default 6

ButtonOverlay trash = new ButtonOverlay("", 36, 36);
trash.setIcon(Feather.TRASH_2);       // no label: the icon alone, centered
```

The icon is drawn in the label's color, so it follows the theme and `setTextColor`, and it fades with a disabled
button. It is part of the button: a click on it is a click on the button, so there is only one `onAction` to set.
`setIcon(null)` takes it away. `hasIcon()` and `getIconSize()` read it back.

## Disabled

```java
button.setDisabled(true);
button.setTooltip("Choose a folder first"); // still shows, so it can say why
button.onDisabledClick(() -> status.setText("Choose a folder first."));
```

A disabled button stays on screen, drawn faded (45% by default; `setDisabledOpacity(float)` changes it), with no hover
color and the "not allowed" cursor. A click does not fire `onAction`; it runs `onDisabledClick` if there is one, so the
click can explain why nothing happened. Fading is a layer of its own, separate from `setOpacity`, so a fade-in and a
disabled button combine. Every element can be disabled this way, not only buttons: see
[Disabled](../core/element.md#disabled).

This is not `setEnabled(false)`, which hides the button and removes it from hit-testing altogether.

## A label that does not fit

```java
button.setEllipsize(true);        // "quarterly-report-final-version-2.xlsx" becomes "quarterly-re…"
button.setEllipsizeMiddle(true);  // or "quarterl….xlsx", keeping the start and the end
```

The label is shortened to fit the box, minus a small margin and the icon. `getText()` still returns the whole string.
See [`TextOverlay`](text-overlay.md#text-that-does-not-fit).

## Example: a row of buttons

```java
HorizontalLayout actions = new HorizontalLayout(20, 20, 340, 40);
actions.setSpacing(10);
actions.setAlignment(Layout.Alignment.CENTER_LEFT);

ButtonOverlay ok = new ButtonOverlay("OK", 100, 32);
ok.onAction(() -> System.out.println("ok"));
ButtonOverlay cancel = new ButtonOverlay("Cancel", 100, 32);
cancel.onAction(() -> System.out.println("cancel"));

actions.addElement(ok);
actions.addElement(cancel);
root.addElement(actions);
```

## Method reference

| Method | Description |
|---|---|
| `onAction(Runnable)` | Click callback; replaces any previous one |
| `setDisabled(boolean)` / `isDisabled()` | Faded, no hover, "not allowed" cursor, no `onAction` |
| `onDisabledClick(Runnable)` | What a click does while disabled |
| `setDisabledOpacity(float)` / `getDisabledOpacity()` | How much of itself a disabled button draws (default `0.45`) |
| `setIcon(IconAsset)` / `hasIcon()` | An icon beside the label, in the label's color; `null` removes it |
| `setIconSize(double)` / `getIconSize()` | Icon width and height; `0` follows the font size |
| `setIconGap(double)`, `setIconAfterText(boolean)` | Space between icon and label; icon after the label |
| Inherited from [`TextOverlay`](text-overlay.md) | `getText/setText`, `getFontSize/setFontSize`, `setFontFile`, `getTextColor/setTextColor`, `dispose` |
| Inherited from [`Element`](../core/element.md) | `getStyling`, `setTooltip`, `setEnabled`, `fadeIn/fadeOut`, ... |
