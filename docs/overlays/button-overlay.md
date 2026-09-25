# ButtonOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.ButtonOverlay` is a clickable, themed button. It extends
[`TextOverlay`](text-overlay.md), so the label, font and text-color API is inherited. What it changes:

- It has an explicit **width and height** (a box), instead of sizing to its text.
- The label is **centered** in the box.
- Its default look is an opaque, themed box (background, hover color, border, corner radius).
- The mouse cursor becomes a **pointer** on hover.
- It has an `onAction` callback.

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

`onAction` fires on **press** of any mouse button. Only one action is held. Calling `onAction` again replaces
it.

> **Do not call `onMouseClick` on a button.** The constructor registers an internal click handler that fires
> `onAction`. Replacing it silently breaks the button.

If you need to know *which* mouse button, you cannot get it from `onAction`. Use a plain [`Element`](../core/element.md) or
[`TextOverlay`](text-overlay.md) with its own `onMouseClick`, or handle it in a custom overlay.

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

## Disabling

```java
button.setEnabled(false); // hides it and removes it from hit-testing
```

There is no "greyed out but visible" state built in. If you want one, style it yourself and ignore clicks
in your `onAction`:

```java
boolean[] busy = {false};
button.onAction(() -> {
    if (busy[0]) return;
    busy[0] = true;
    button.setText("Working...");
});
```

## Example: a row of buttons

```java
HorizontalLayout actions = new HorizontalLayout(20, 20, 340, 40);
actions.setSpacing(10);
actions.setAlignment(Layout.Alignment.CENTER_LEFT);
actions.getStyling().setBackgroundColor(Color.TRANSPARENT);
actions.getStyling().setHoverColor(Color.TRANSPARENT);
actions.getStyling().setBorderThickness(0f);

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
| Inherited from [`TextOverlay`](text-overlay.md) | `getText/setText`, `getFontSize/setFontSize`, `setFontFile`, `getTextColor/setTextColor`, `dispose` |
| Inherited from [`Element`](../core/element.md) | `getStyling`, `setTooltip`, `setEnabled`, `fadeIn/fadeOut`, ... |
