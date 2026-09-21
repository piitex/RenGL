# SpinnerOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.SpinnerOverlay` is a **numeric input**: a text field with a stacked up/down button
pair beside it, the same role as JavaFX's `Spinner`.

Unlike the other overlays it is a **composite**. It extends [`HorizontalLayout`](../layouts/horizontal-layout.md) and contains a
[`TextFieldOverlay`](text-field-overlay.md) and a [`VerticalLayout`](../layouts/vertical-layout.md) of two [`ButtonOverlay`](button-overlay.md)s, so it inherits their theming, hover
cursors, focus traversal and text editing (selection, clipboard, and so on).

```java
SpinnerOverlay age = new SpinnerOverlay(150, 32, 0, 120, 25); // width, height, min, max, initial
age.setLabel("Age");
age.onValueChange(v -> System.out.println("Age: " + v));
layout.addElement(age);
```

## Constructors

| Constructor | Notes |
|---|---|
| `SpinnerOverlay(double width, double height)` | 0-100 whole numbers, starts at 0 |
| `SpinnerOverlay(double width, double height, double min, double max, double initialValue)` | |
| `SpinnerOverlay(double x, double y, double width, double height, double min, double max, double initialValue)` | |

The buttons are `max(16, 0.8 * height)` wide, and the field takes the rest.

## How the value changes

| Input | Effect |
|---|---|
| Up / down **buttons** | One `step` per click |
| **Up / Down arrow keys** while the field is focused | One step (hold **Shift** for 10 steps) |
| **Mouse wheel**, only while the field is focused | One step per notch. Scrolling past a spinner inside a [`ScrollContainer`](../containers/scroll-container.md) does not change it by accident |
| **Typing** | Parsed, clamped and re-formatted when the field loses focus or **Enter** is pressed. Text that does not parse reverts to the last valid value |

## Value, range and step

```java
double v = spinner.getValue();
spinner.setValue(42); // clamped to [min, max], rounded to decimals; silent (no callback)

spinner.setMin(0);
spinner.setMax(500); // both re-clamp the current value

spinner.setStep(0.5); // amount per click/key/notch; also sets decimals to match (1 here)
spinner.setDecimals(2); // explicit decimals (then setStep no longer changes them)

spinner.onValueChange(value -> System.out.println(value)); // fires on user changes only
```

`onValueChange` holds one listener. Non-positive steps are ignored.

## Label and placeholder

```java
spinner.setLabel("Age"); // "Age ........ 25": label at the far left, value at the far right
spinner.setPlaceholder("Age 25"); // hint text shown while the spinner is empty
spinner.clear(); // shows the placeholder (needs a placeholder set); silent
boolean empty = spinner.isEmpty();
```

- The label is a permanent prefix in the lighter placeholder color, and it stays visible as the value changes.
- While **empty**, `getValue()` still returns the last value. That is the value the first button click, arrow key
  or wheel notch will fill in.
- Committing blank text also makes the spinner empty, but only if a placeholder is set.

## Reaching the inner field

```java
TextFieldOverlay field = spinner.getField();
field.setPadding(12f);
field.setTooltip("Enter your age");
```

The field's own `onTextChanged` and `onSubmit` hooks are free for your use.

## Example: a price entry

```java
SpinnerOverlay price = new SpinnerOverlay(180, 32, 0, 1000, 9.99);
price.setStep(0.01);
price.setDecimals(2);
price.getField().setPrefix("$");
price.onValueChange(v -> System.out.printf("Price: %.2f%n", v));
layout.addElement(price);
```

## Method reference

| Group | Methods |
|---|---|
| Value | `getValue`, `setValue`, `onValueChange` |
| Range | `getMin/setMin`, `getMax/setMax`, `getStep/setStep`, `getDecimals/setDecimals` |
| Text | `setLabel`, `getPlaceholder/setPlaceholder`, `isEmpty`, `clear` |
| Parts | `getField()` |
| Inherited | Everything from [`HorizontalLayout`](../layouts/horizontal-layout.md) / [`Container`](../containers/container.md) / [`Element`](../core/element.md) |

## Notes

- Because it is a [`HorizontalLayout`](../layouts/horizontal-layout.md), styling it styles the outer box. The default outer box is transparent
  with no border, and the field and buttons draw their own themed boxes.
- `getWidth()` is the whole spinner (field plus buttons).
