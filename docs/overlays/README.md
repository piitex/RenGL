# Overlay catalog

[Documentation](../README.md) › Overlays

Every widget in RenGL is an [`Overlay`](../core/overlay.md) (an [`Element`](../core/element.md)), except
[`SpinnerOverlay`](spinner-overlay.md), which is a layout. They all live in `me.piitex.engine.ui.overlays`. Each has its own page.

## Text

| Widget | What it is |
|---|---|
| [`TextOverlay`](text-overlay.md) | A single line of static text, with an optional gradient fill |
| [`TextFieldOverlay`](text-field-overlay.md) | A single-line editable text box with selection and clipboard |
| [`TextFlowOverlay`](text-flow-overlay.md) | Read-only wrapped text in a fixed-width box, with alignment and an optional max height that scrolls |
| [`TextAreaOverlay`](text-area-overlay.md) | A multi-line editable box with word wrap and its own scrollbar |
| [`RichTextAreaOverlay`](rich-text-area-overlay.md) | A [`TextAreaOverlay`](text-area-overlay.md) plus app-defined colored runs and offline spell checking |
| [`FormattedTextOverlay`](formatted-text-overlay.md) | Scrollable headings-and-paragraphs text parsed from Markdown or bracket-tag source via a pluggable `TextFormatPreset` |

## Controls

| Widget | What it is |
|---|---|
| [`ButtonOverlay`](button-overlay.md) | A clickable, themed button with a centered label |
| [`LinkOverlay`](link-overlay.md) | Underlined text that opens a web address in the default browser |
| [`CheckBoxOverlay`](checkbox-overlay.md) | A square box that toggles between checked and unchecked |
| [`RadioButtonOverlay`](radio-button-overlay.md) + [`RadioGroup`](radio-button-overlay.md#radiogroup) | Mutually exclusive circular selectors |
| [`DropdownOverlay<T>`](dropdown-overlay.md) | A closed box that opens a floating list of choices |
| [`SliderOverlay`](slider-overlay.md) / [`PercentSliderOverlay`](slider-overlay.md#percentslideroverlay) | A draggable numeric slider with ticks, labels and snapping |
| [`RangeSliderOverlay`](range-slider-overlay.md) | A slider with two thumbs that picks a from-to range |
| [`ComboBoxOverlay<T>`](combo-box-overlay.md) | A text field with filtered suggestions under it; takes free text too |
| [`SpinnerOverlay`](spinner-overlay.md) | A numeric field with up/down buttons (a composite layout) |
| [`ToggleSwitchOverlay`](toggle-switch-overlay.md) | An on/off switch with a sliding knob |
| [`ColorPickerOverlay`](color-picker-overlay.md) | A swatch that opens a picker: color square, hue and opacity bars, hex field, presets |
| [`DateTimePickerOverlay`](date-time-picker-overlay.md) | A date, time, or date-and-time chosen from a floating calendar and clock |
| [`MenuBarOverlay`](../input/menus.md) | A strip of titles that each open a popup menu (see also `PopupMenu` and context menus) |

## Game widgets

| Widget | What it is |
|---|---|
| [`KnobOverlay`](knob-overlay.md) | A round dial turned by dragging up and down |
| [`GaugeOverlay`](gauge-overlay.md) | A round read-out with an arc or needle, colored zones and eased movement |
| [`JoystickOverlay`](joystick-overlay.md) | An on-screen thumb stick read as two axes, with a dead zone and 4 or 8 way snapping |
| [`MinimapOverlay`](minimap-overlay.md) | A small map of a larger world with markers, a camera outline, zoom and click to navigate |
| [`HotkeyOverlay`](hotkey-overlay.md) | A box that shows a key binding and learns a new one, with conflict warnings |
| [`InventoryGridOverlay<T>`](inventory-grid-overlay.md) | A grid of item slots with stacks, drag between grids, locks and per-slot filters |
| [`RadialMenu`](../input/radial-menu.md) | A pie menu of wedges around the pointer |

`me.piitex.examples.demo.GameWidgetsDemo` shows all of them, with a `ConsoleLog`, in one window.

## Feedback and decoration

| Widget | What it is |
|---|---|
| [`ProgressBarOverlay`](progress-bar-overlay.md) | A bar that fills as work completes, or sweeps when the amount is unknown |
| [`LoadingSpinnerOverlay`](loading-spinner-overlay.md) | A ring of dots that chases around a circle |
| [`SeparatorOverlay`](separator-overlay.md) | A thin dividing line, horizontal or vertical |
| [`SpacerOverlay`](spacer-overlay.md) | Empty space in a row or column: flexible, or a fixed gap |

Widgets that are containers, such as [`ListBox`](../containers/list-box.md), [`AccordionContainer`](../containers/accordion-container.md),
[`ToolBar`](../containers/tool-bar.md), [`StatusBar`](../containers/status-bar.md), [`ToastManager`](../containers/toast-manager.md) and
[`MessageBox`](../containers/message-box.md) and [`ConsoleLog`](../containers/console-log.md), are in the [containers](../containers/README.md) catalog.

## Charts

| Widget | What it is |
|---|---|
| [`LineChartOverlay`](../charts/line-chart.md) | Lines through values over categories, with smooth curves and filled areas |
| [`BarChartOverlay`](../charts/bar-chart.md) | Grouped, stacked or horizontal bars |
| [`ScatterChartOverlay`](../charts/scatter-chart.md) | Points and bubbles by two numbers, on linear, log or time axes |
| [`PieChartOverlay`](../charts/pie-chart.md) | A pie or a donut, with the total in the middle |

They live in `me.piitex.engine.ui.charts`. See [Charts](../charts/README.md).

## Images

| Widget | What it is |
|---|---|
| [`ImageOverlay`](image-overlay.md) | An image loaded from disk (PNG, JPEG, WEBP, GIF, BMP, ICO), with opacity, rounding and tint |
| [`IconOverlay`](icon-overlay.md) | An SVG rasterized to an image, including the bundled Feather icon set |

## Common questions

**Which widgets follow the theme?** Every widget except [`ImageOverlay`](image-overlay.md) and [`IconOverlay`](icon-overlay.md) registers with the
theme system. A plain [`Container`](../containers/container.md) does too, once you opt in with `useTheme()`. See
[Theming](../styling/theming.md).

**Which widgets can take keyboard focus?** [`TextFieldOverlay`](text-field-overlay.md), [`TextAreaOverlay`](text-area-overlay.md) (and its rich subclass),
[`DropdownOverlay`](dropdown-overlay.md), [`ComboBoxOverlay`](combo-box-overlay.md) (as a text field), [`RangeSliderOverlay`](range-slider-overlay.md), [`ToggleSwitchOverlay`](toggle-switch-overlay.md), [`ColorPickerOverlay`](color-picker-overlay.md) and
[`DateTimePickerOverlay`](date-time-picker-overlay.md). [`SpinnerOverlay`](spinner-overlay.md) gets focus through its inner text field. See
[Input and focus](../input/input-and-focus.md).

**Which widgets have their own click handler?** [`ButtonOverlay`](button-overlay.md), [`CheckBoxOverlay`](checkbox-overlay.md), [`RadioButtonOverlay`](radio-button-overlay.md),
`DropdownOverlay`, `ToggleSwitchOverlay`, `ColorPickerOverlay`, `DateTimePickerOverlay` and [`RichTextAreaOverlay`](rich-text-area-overlay.md). They keep that handler apart from `onMouseClick`, so setting your own does not replace it; the widget's own callback is the usual way to react.

**Which widgets need `dispose()`?** Those that own a native `Font`: [`TextOverlay`](text-overlay.md), `ButtonOverlay`,
`TextFieldOverlay` (and `ComboBoxOverlay`), `TextAreaOverlay`, `RichTextAreaOverlay`, [`SliderOverlay`](slider-overlay.md) and `RangeSliderOverlay`. Call it when permanently
discarding one.

## One of each

```java
VerticalLayout panel = new VerticalLayout(40, 40, 360, 640);
panel.setSpacing(12);
panel.setPadding(16);
panel.useTheme();

panel.addElement(new TextOverlay("A label", 16f, Color.BLACK));
panel.addElement(new TextFieldOverlay(200, 28));
panel.addElement(new ButtonOverlay("Button", 120, 32));
panel.addElement(new CheckBoxOverlay(18));
panel.addElement(new RadioButtonOverlay(16));
panel.addElement(new PercentSliderOverlay(200, 20));
panel.addElement(new SpinnerOverlay(150, 32, 0, 100, 10));
panel.addElement(new ProgressBarOverlay(200, 10));
panel.addElement(new LoadingSpinnerOverlay(28));
panel.addElement(new SeparatorOverlay(300));

DropdownOverlay<String> choice = new DropdownOverlay<>(200, 28);
choice.addItem("One");
choice.addItem("Two");
panel.addElement(choice);

root.addElement(panel);
```
