# DropdownOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.DropdownOverlay<T>` is a closed box showing the current choice. Clicking it opens
a floating list of `T` items above everything else, the same role as JavaFX's `ComboBox`/`ChoiceBox`.

```java
DropdownOverlay<String> country = new DropdownOverlay<>(200, 28);
country.setPlaceholder("Country...");
for (String c : new String[]{"Canada", "France", "Germany", "Japan"}) {
    country.addItem(c);
}
country.onSelect(c -> System.out.println("Selected: " + c));
layout.addElement(country);
```

The list is shown through `Window.showPopup`, so it paints over sibling elements and containers, is **not clipped**
by a parent [`ScrollContainer`](../containers/scroll-container.md), and closes on an outside click or Escape. It follows the
dropdown if the dropdown moves while open, and closes if the dropdown is removed, disabled or scrolled out of view.

The constructor is `DropdownOverlay(double width, double height)`. The height is also the height of each row in the open list.

## Items and selection

```java
dropdown.addItem("One");
dropdown.setItems(List.of("A", "B", "C")); // replaces everything and clears the selection

List<String> items = dropdown.getItems(); // unmodifiable view

dropdown.setSelected("B"); // silent; unknown value or null clears the selection
dropdown.setSelectedIndex(2); // silent; out of range clears it
String chosen = dropdown.getSelected(); // or null
int index = dropdown.getSelectedIndex();
```

`setSelected`, `setSelectedIndex` and `setItems` never fire `onSelect`. Only the **user** choosing an item does.

```java
dropdown.onSelect(item -> System.out.println(item)); // click OR keyboard choice
```

## Items that are not strings

`DropdownOverlay` is generic. Give it a label provider that turns each item into text (the default is
`String.valueOf`).

```java
public record Language(String code, String name) {}
```

```java
DropdownOverlay<Language> lang = new DropdownOverlay<>(220, 28);
lang.setLabelProvider(Language::name);
lang.addItem(new Language("en", "English"));
lang.addItem(new Language("fr", "Français"));
lang.onSelect(l -> System.out.println("code = " + l.code()));
```

## Options

```java
dropdown.setPlaceholder("Pick one..."); // shown while nothing is selected
dropdown.setMaxVisibleRows(6); // rows before the list scrolls (default 8)
dropdown.setFontSize(16f);
dropdown.setTextColor(Color.BLUE); // pins the text color
```

If there is not enough room below, the list opens **upward**.

## Opening and closing from code

```java
dropdown.open(); // no-op with no items or no window
dropdown.close();
dropdown.toggleOpen();
boolean open = dropdown.isOpen();
```

## Keyboard

The dropdown is [`Focusable`](../input/input-and-focus.md#keyboard-focus) and a Tab stop.

| Key (while focused) | Effect |
|---|---|
| Enter / Space | Opens or closes the list |
| Up / Down | Moves the selection by one (fires `onSelect`) |

The mouse wheel scrolls the open list when it has more rows than `maxVisibleRows`.

## Look

The box uses the text-field theme tokens, and the open list reuses the dropdown's own styling, with the theme
selection color marking the selected row. Only plain [`Color`](../styling/colors-and-paints.md#color) backgrounds and borders are honored for the list.

```java
dropdown.getStyling().setBackgroundColor(new Color(0.12f, 0.12f, 0.14f, 1f));
dropdown.getStyling().setCornerRadius(6f);
```

> **Do not call `onMouseClick` on a dropdown.** The constructor registers an internal handler that toggles the
> list, and replacing it breaks the widget.

## Method reference

| Group | Methods |
|---|---|
| Items | `addItem`, `setItems`, `getItems` |
| Selection | `getSelected`, `getSelectedIndex`, `setSelected`, `setSelectedIndex`, `onSelect` |
| Display | `setLabelProvider`, `setPlaceholder`, `setMaxVisibleRows`, `setFontSize`, `getTextColor/setTextColor` |
| List | `isOpen`, `open`, `close`, `toggleOpen` |
