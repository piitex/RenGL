# CheckBoxOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.CheckBoxOverlay` is a square box that toggles between **checked** and
**unchecked** when clicked. It draws no label. Put a [`TextOverlay`](text-overlay.md) next to it (see the example below).

```java
CheckBoxOverlay subscribe = new CheckBoxOverlay(18);
subscribe.onToggle(checked -> System.out.println("subscribed: " + checked));
layout.addElement(subscribe);
```

## Constructors

| Constructor | Result |
|---|---|
| `CheckBoxOverlay()` | Unchecked 20 × 20 box |
| `CheckBoxOverlay(double size)` | Unchecked `size` × `size` box |
| `CheckBoxOverlay(double size, boolean checked)` | Initial state chosen |

## State

```java
boolean on = box.isChecked();
box.setChecked(true); // silent: does NOT fire onToggle
box.toggle(); // flips and fires onToggle (this is what a click runs)
```

```java
box.onToggle(checked -> System.out.println(checked)); // fired with the new state on every click toggle
```

`onToggle` holds one listener. Calling it again replaces it.

> **Do not call `onMouseClick` on a checkbox.** The constructor registers an internal click handler that
> toggles the state, and replacing it breaks the widget.

## Look

The box uses the same field tokens as a text field (background, hover, border, corner radius). The
checkmark uses the theme text color.

```java
box.setCheckColor(Color.GREEN); // pins the mark color
box.setCheckThickness(3f); // stroke width of the mark
box.getStyling().setCornerRadius(4f);
box.getStyling().setBorderColor(Color.BLUE);
```

## Example: a checkbox with a label

[`HorizontalLayout`](../layouts/horizontal-layout.md) with `CENTER` cross-axis alignment keeps the label vertically centered against the box.

```java
HorizontalLayout row = new HorizontalLayout(220, 24);
row.setSpacing(8);
row.setAlignment(Layout.Alignment.CENTER_LEFT);

CheckBoxOverlay box = new CheckBoxOverlay(18, true);
TextOverlay label = new TextOverlay("Remember me", 14f, Color.BLACK);

// Make the label clickable too, the way HTML labels are:
label.setCursorMode(CursorMode.POINTER);
label.onMouseClick(e -> box.toggle());
label.setClickOnRelease(true); // toggle on release, as the box itself does

row.addElement(box);
row.addElement(label);
root.addElement(row);
```

## Method reference

| Method | Description |
|---|---|
| `isChecked()` | Current state |
| `setChecked(boolean)` | Silent set |
| `toggle()` | Flip and fire `onToggle` |
| `onToggle(Consumer<Boolean>)` | Callback for user toggles |
| `getCheckColor()` / `setCheckColor(Color)` | Mark color; setting pins it |
| `getCheckThickness()` / `setCheckThickness(float)` | Mark stroke width |
