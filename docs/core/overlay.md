# Overlay
[Documentation](../README.md) › [Core](README.md)

`me.piitex.engine.ui.overlays.Overlay` is the abstract base class of every widget. It adds nothing of its
own:

```java
public abstract class Overlay extends Element {}
```

The name marks the layer of the class hierarchy that draws something specific (text, an image, a button)
on top of the plain rectangle that [`Element`](element.md) already provides. Everything on the
[`Element`](element.md) page applies to every overlay.

## The overlay family

```text
Element
 └─ Overlay
     ├─ TextOverlay
     │   ├─ ButtonOverlay
     │   └─ TextFlowOverlay
     ├─ TextFieldOverlay
     ├─ TextAreaOverlay
     │   └─ RichTextAreaOverlay
     ├─ CheckBoxOverlay
     ├─ RadioButtonOverlay
     ├─ ToggleSwitchOverlay
     ├─ DropdownOverlay<T>
     ├─ SliderOverlay
     │   └─ PercentSliderOverlay
     ├─ ColorPickerOverlay
     ├─ DateTimePickerOverlay
     ├─ ProgressBarOverlay
     ├─ LoadingSpinnerOverlay
     ├─ SeparatorOverlay
     ├─ MenuBarOverlay
     └─ ImageOverlay
         └─ IconOverlay

Container
 ├─ Layout
 │   └─ HorizontalLayout ─── SpinnerOverlay      (a composite; it is a layout)
 └─ ScrollContainer ─── FormattedTextOverlay     (scrolls its own content)
```

[`SpinnerOverlay`](../overlays/spinner-overlay.md) is called an overlay by name but is a [`HorizontalLayout`](../layouts/horizontal-layout.md) (a [`Container`](../containers/container.md)) that wraps a
text field and two buttons. See [SpinnerOverlay](../overlays/spinner-overlay.md). Likewise
[`FormattedTextOverlay`](../overlays/formatted-text-overlay.md) is a [`ScrollContainer`](../containers/scroll-container.md).

The full catalog with one-line descriptions is at [Overlay catalog](../overlays/README.md).

## Patterns shared by every widget
**Theming.** Most widgets implement [`Themeable`](../styling/theming.md) and register with [`ThemeManager`](../styling/theming.md) in their constructor, so
they pick up the current theme and follow later switches. Any property you set explicitly (a color, a
radius) is *pinned* and left alone by future theme switches. See [Theming](../styling/theming.md).

**The text color you pass to a constructor is only a baseline.** Text-bearing widgets ([`TextOverlay`](../overlays/text-overlay.md),
[`ButtonOverlay`](../overlays/button-overlay.md), [`TextFieldOverlay`](../overlays/text-field-overlay.md), [`TextAreaOverlay`](../overlays/text-area-overlay.md), ...) accept a [`Paint`](../styling/colors-and-paints.md) (a solid [`Color`](../styling/colors-and-paints.md#color) or a gradient),
but they are themed, and registering with `ThemeManager` immediately replaces that color with the current
theme's text color. To keep a specific color, call `setTextColor(...)` after construction. That call *pins* it,
and it then survives theme switches:

```java
TextOverlay warning = new TextOverlay("Careful!", 16f, Color.RED);
warning.setTextColor(Color.RED); // without this line the theme's text color wins
```

**Value setters do not fire callbacks.** `setValue`, `setChecked`, `setSelected`, `setText` and friends change state
silently. Only *user* actions fire `onValueChange`, `onToggle`, `onSelect` and `onTextChanged`. This avoids feedback loops
when you sync UI to your model.

**Built-in click handlers.** `ButtonOverlay`, [`CheckBoxOverlay`](../overlays/checkbox-overlay.md), [`RadioButtonOverlay`](../overlays/radio-button-overlay.md), [`DropdownOverlay`](../overlays/dropdown-overlay.md),
[`ToggleSwitchOverlay`](../overlays/toggle-switch-overlay.md), [`ColorPickerOverlay`](../overlays/color-picker-overlay.md), [`DateTimePickerOverlay`](../overlays/date-time-picker-overlay.md),
[`MenuBarOverlay`](../input/menus.md) and [`RichTextAreaOverlay`](../overlays/rich-text-area-overlay.md) register their own `onMouseClick` handler. Do not replace it. Use the widget's
own callback.

**`dispose()`.** Widgets that own a native Skia `Font` (`TextOverlay` and its subclass `ButtonOverlay`,
`TextFieldOverlay`, `TextAreaOverlay` and its subclass `RichTextAreaOverlay`, and [`SliderOverlay`](../overlays/slider-overlay.md)) have
`dispose()`. Call it when permanently discarding one. The shared `Typeface` is not closed.

**Focus.** `TextFieldOverlay`, `TextAreaOverlay` and `DropdownOverlay` implement [`Focusable`](../input/input-and-focus.md#keyboard-focus), and
`TextFieldOverlay`/`TextAreaOverlay`/`DropdownOverlay` are Tab stops by default.

## Writing your own overlay
Extend `Overlay`, override `render` (call `super.render` first so the background, border and `onRender`
hook run), and optionally `update`, `applyTheme` and the input interfaces. A full walkthrough with a working
example is in [Writing custom elements](../reference/custom-elements.md).
