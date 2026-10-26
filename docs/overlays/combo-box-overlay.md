# ComboBoxOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.ComboBoxOverlay<T>` is a text field with a list of suggestions under it: the user types, the list narrows to the
items that match, and picking one fills the field. Unlike a [`DropdownOverlay`](dropdown-overlay.md), which only lets the user choose from its
items, a combo box also takes text that is **not** in the list, so it suits a search box, a command line with completions, or a "city" field
that knows the common ones.

```java
ComboBoxOverlay<String> city = new ComboBoxOverlay<>(240, 32);
city.setItems(List.of("Amsterdam", "Berlin", "Bern", "Boston"));
city.setPlaceholder("City");
city.onItemSelect(name -> weather.show(name));
layout.addElement(city);
```

It **is a** [`TextFieldOverlay`](text-field-overlay.md), so everything a field does (placeholder, max length, `onTextChanged`, `onSubmit`, undo, a
password mask) works the same. The list opens as the user types, on Down, or on a click on the arrow at the right end, and is shown through the
window's popup layer like a dropdown's: it paints over its neighbours, is not clipped by a scroll container, opens upward when there is no room
below, and closes on an outside click or Escape.

## Using it

| Key or click | What happens |
|---|---|
| Typing | Opens the list, narrowed to the matches; closes it when nothing matches or the only match is exactly what is typed |
| Down | Opens the list (all items if the text matches nothing) with the first row highlighted; then moves the highlight |
| Up | Moves the highlight up; past the first row it leaves none highlighted |
| Enter | Picks the highlighted row, or, with none highlighted, closes the list and submits the typed text (`onSubmit`) |
| Click on a row | Picks it and gives the keyboard back to the field |
| Wheel | Scrolls a long list |
| Click on the arrow | Opens the list of every item |

## Items

```java
city.setItems(list);             // replaces the items; the text is left alone
city.addItem("Chicago");
city.setLabelProvider(City::name);    // how an item becomes text (default String.valueOf)
city.setMatcher((item, typed) -> item.toLowerCase().startsWith(typed.toLowerCase()));
city.setMaxVisibleRows(6);       // before the list scrolls; default 8
city.setOpenOnFocus(true);       // show every item as soon as the field is clicked; default off
```

The default matcher keeps items whose text **contains** what was typed, ignoring case. Replace it with `startsWith`, or anything else, such as a
fuzzy score; the typed text passed to it is never empty.

## Selection

```java
city.onItemSelect(item -> {});   // the user picked a row; not fired for text typed in full or by setSelectedItem
T item = city.getSelectedItem(); // the picked item while the text still equals its label, else the item whose label equals the text, else null
city.setSelectedItem(item);      // fills the field with its label; silent; null empties it
String typed = city.getText();   // always the field's text, which may be none of the items
```

`getSelectedItem()` returning `null` is how you tell free text from a known item.

## Look

The field is themed like a `TextFieldOverlay`; the list uses the field's own colors (background, hover, border), with its **selection color** marking the
highlighted row. Pin them with `getStyling()` and `setTextColor` as for a field. Call `dispose()` when permanently discarding one.
