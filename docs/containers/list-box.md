# ListBox

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.containers.ListBox<T>` is a scrolling list of items, one per row, where the user picks one or several: a
list of save slots, categories in a settings page, mods to enable. It is what a [`TableView`](table-view.md) with one column and
no header would be.

```java
ListBox<Save> saves = new ListBox<>(240, 300);
saves.setLabelProvider(Save::name);
saves.setItems(allSaves);
saves.onSelect(save -> preview(save));
saves.onActivate(save -> load(save));       // double-click, or Enter
root.addElement(saves);
```

Only the rows on screen are drawn, so a list of thousands costs no more than a short one. Like `TableView` it follows the theme
the way a [`Container`](container.md) that `useTheme()`s does, and it is a [`ScrollContainer`](scroll-container.md), so the wheel and
the scrollbar work as everywhere else.

## Items

```java
saves.setItems(list);          // copies the collection; scrolls back to the top
saves.addItem(save);           // at the end
saves.removeItem(save);        // by equals; returns whether it was there
saves.clear();
saves.refresh();               // re-applies the filter, keeps the scroll position
List<Save> shown = saves.getItems();       // after the filter
List<Save> all = saves.getAllItems();      // including hidden ones
```

The list shows items in the order given. `setLabelProvider` turns an item into the row's text (`String.valueOf` by default).
`setIconProvider` puts an icon, tinted with the text color, before it, and `setDimmed` draws some rows in the dim color.
`setEmptyText("No saves yet")` fills the empty list.

### Filtering

```java
search.onTextChanged(text -> saves.setFilter(save -> save.name().toLowerCase().contains(text.toLowerCase())));
```

`setFilter` hides the items the predicate rejects without removing them (`null` shows all). Hidden items drop out of the
selection.

## Selection

One row is selected at a time unless `setMultiSelect(true)`. A click selects a row, and a click below the last one clears the
selection. The arrow keys, Home, End and Page Up and Down move it.

```java
saves.setMultiSelect(true);
saves.onSelectionChange(chosen -> status.setText(chosen.size() + " selected"));
saves.onSelect(save -> preview.show(save));    // the lead row changed; null when cleared
```

With multi-select, Ctrl-click (Cmd on macOS) adds or removes a row, Shift-click selects a range from the last clicked row, Shift
with the arrows extends it, and Ctrl/Cmd+A selects every row.

| Method | What it does |
|---|---|
| `getSelected()`, `getSelectedIndex()`, `getSelectedItems()`, `isSelected(item)` | What is selected |
| `setSelected(item)`, `setSelectedIndex(i)` | Select only that row and scroll to it; null or an unknown item clears |
| `setSelectedItems(items)` | Selects exactly these; items not shown are skipped |
| `selectAll()`, `clearSelection()` | Every row, or none |
| `scrollToIndex(i)`, `scrollToItem(item)` | Scrolls the least that brings the row into view |

The setters are silent: `onSelect` and `onSelectionChange` fire only for the user's own changes.

## Opening, keys and type-ahead

`onActivate` fires for a double-click and for Enter on the lead row. Typing the start of a row's text jumps to it (typing the same
letter again moves to the next row that starts with it). `onKey` sees every key before the list does, to claim ones like Delete:

```java
saves.onKey((key, mods) -> {
    if (key == GLFW.GLFW_KEY_DELETE) { deleteSelected(); return true; }
    return false;
});
```

A [context menu](../input/menus.md) set with `setContextMenu` opens on a right-click after selecting the row under the pointer
(unless it is already part of the selection).

## Look

```java
saves.setRowHeight(30);          // default 26
saves.setStriped(true);          // every other row slightly darker
saves.setRowCornerRadius(4);     // rounded highlights
saves.setIconSize(18);
saves.setFontSize(16);
saves.setSelectionColor(Color.BLUE);   // each of these pins the color against theme switches
saves.setTextColor(Color.WHITE);
```

### Drawing rows yourself

```java
saves.setRowRenderer((renderer, save, x, y, width, height, selected, hovered) -> {
    renderer.drawText(save.name(), x + 8, y + 4, font, Color.WHITE);
    renderer.drawText(save.date(), x + 8, y + 22, smallFont, Color.GRAY);
});
saves.setRowHeight(44);
```

The renderer is given each visible row's box in window coordinates, already clipped to the row, with the selection and hover
highlight drawn underneath.

### Controls in rows

`setRowWidget` puts a live control in every row, such as a switch, a slider or a button. `create` makes one and `bind` points it
at a row's item:

```java
mods.setRowWidget(() -> new ToggleSwitchOverlay(40, 22), (toggle, mod) -> {
    toggle.setOn(mod.enabled());
    toggle.onToggle(mod::setEnabled);
});
```

The control keeps its own size and sits at the right end of the row, with the icon and label to its left.
`setRowWidgetFill(true)` stretches it over the whole row instead, and the list then draws no label. Use that for a row built
from a `Container` of several controls (a name, a slider and a button, say).

- Only the rows on screen get a control. As the list scrolls, the same controls move to the rows coming into view and `bind`
  runs again with the new item. So `bind` must set everything that depends on the item, and running it twice must do no harm.
  `refresh()` binds every control on screen again.
- A control the user is holding down, or has the keyboard in, keeps its item until they let go, even if its row scrolls away.
- Pressing a control selects its row. A double-click on a control doesn't fire `onActivate`.
- The list stays one Tab stop. F2, or Enter when there is no `onActivate`, moves the keyboard into the selected row's control,
  and Escape gives it back. `setRowWidgetTabStops(true)` puts the controls in the Tab order instead.
- `getRowWidget(item)` gives the control showing that item now (null if its row is off screen). `focusRowWidget(item)` scrolls to
  the row and moves the keyboard into its control.
- The label is still what type-ahead and screen readers use.

`me.piitex.examples.demo.ListWidgetsDemo` shows both kinds: a mod list with a switch per row, and a player list whose rows hold a
slider and a Kick button. [TableView](table-view.md) has the same thing per column.

## Notes

- Call `dispose()` when permanently discarding a list: it owns a native font.
- Items are compared with `equals`, so give item types a real `equals` (a `record` has one) if you rebuild the list and want the
  selection kept.
- `refresh()` after changing something a filter or label depends on; the label itself is read every frame.
