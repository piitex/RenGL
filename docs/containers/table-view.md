# TableView

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.containers.TableView<T>` shows items in rows and columns under a header that stays at the top: an
archive's listing (Name, Size, Packed, Modified), a file browser's details view, a list of results. The user clicks a
header to sort by it, drags the edge between two headers to resize a column, and selects one row or several.

It is a [`ScrollContainer`](scroll-container.md), so the wheel and the scrollbars work the same way. When the columns
are wider than the table it scrolls sideways, and the header scrolls with the cells.

```java nocompile
TableView<Entry> table = new TableView<>(600, 400); // width, height
table.setPosition(8, 40);
table.addColumn("Name", Entry::name).setWidth(260);
table.addColumn("Size", e -> formatSize(e.size()))
        .setAlignment(TextAlign.RIGHT)
        .setComparator(Comparator.comparingLong(Entry::size)); // "1.5 MB" does not sort as text
table.addColumn("Modified", e -> DATE.format(e.modified()))
        .setComparator(Comparator.comparing(Entry::modified));
table.setItems(entries);
table.onActivate(entry -> open(entry));
panel.addElement(table);
```

`me.piitex.examples.demo.TableDemo` is a runnable file browser: it sorts, resizes, selects several rows, opens a row with
the OS, shows it in Finder, and lists whatever folder is [dropped on the window](../core/window.md#files-dropped-on-the-window).
`me.piitex.examples.demo.TableEditDemo` is an order sheet: a check box for each row (and one in the header for all of them),
cells that are typed into in place with `setEditable`, a price edited as a number but shown as money with `setEditText`,
and buttons that act on the ticked rows.

## Columns

`addColumn(title, text)` adds a column on the right and returns it, so it can be set up where it is added. `text` gives
a row's text in that column; null is shown as empty. It is read every frame for the rows on screen, so a change that does
not move rows needs no `refresh()`.

| `Column` method | What it does |
|---|---|
| `setWidth(float)` | The width in points, at least the minimum. Default 120 |
| `setMinWidth(float)` | The narrowest the user can make it. Default 40 |
| `setAlignment(TextAlign)` | `LEFT` (default), `RIGHT` for numbers, or `CENTER`. The header follows |
| `setComparator(Comparator<T>)` | How a click on the header orders the rows. See [Sorting](#sorting) |
| `setSortable(boolean)` | Whether a header click sorts by it. On by default |
| `setResizable(boolean)` | Whether the user can drag its right edge. On by default |
| `setEllipsized(boolean)` | Text too long for the cell ends with `…` (header titles too). On by default; off cuts it at the cell's edge |
| `setEllipsisInMiddle(boolean)` | Put the `…` in the middle so the end stays, such as a file name's extension. Off by default |
| `setIconProvider(item -> IconAsset)` | An icon before the text, such as `Feather.FOLDER`, or null for none on that row |
| `setIconColorProvider(item -> Color)` | The color of that icon, so a folder can be the accent; null, or null for a row, is the text color |
| `setEditable((item, text) -> ...)` | Lets the user [edit the cells](#editing-cells) of this column in place. Null, the default, is read-only |
| `setEditText(item -> String)` | What the field starts with, when that is not the text the cell shows |

`getColumns()` lists them left to right, and `removeColumn(column)` removes one. After the user has resized a column,
`column.getWidth()` has the new width, to save it. The **last column stretches** to fill the table when the columns
add up to less than its width.

## The model

| Method | What it does |
|---|---|
| `setItems(collection)` | The rows. The collection is kept, not copied, and the table sorts a copy of it, so it is never reordered. **Scrolls back to the top**, as when a folder is opened |
| `refresh()` | Reads the collection again, applying the sort. Call it after adding, removing or moving items in it. **Keeps the scroll position** (clamped if the table got shorter) |
| `getItems()` | The items in the order shown now, top to bottom |
| `setDimmed(item -> boolean)` | Rows drawn in the dim text color, such as hidden files |

A live list therefore stays current with a `refresh()` after each change. Items are told apart by `equals`: the selection is kept that way across `refresh()` and sorts.

## Sorting

A click on a header sorts by that column, ascending. Another click on the same header reverses it. An arrow in the
header shows the sorted column and its direction. Rows that compare equal keep their order.

A column with no comparator compares its **text**, ignoring case and counting a run of digits as the number it spells,
so `file2` comes before `file10`. Give a column that shows sizes or dates a comparator on the real value.

```java nocompile
table.sortBy(nameColumn, true);   // silent: no onSort
table.clearSort();                // back to the order setItems was given
table.onSort((column, ascending) -> prefs.saveSort(column.getTitle(), ascending)); // the user clicked a header
```

To keep some rows on top whatever the direction, such as folders before files, use a **sort group**. The rows it
matches stay above the others, each group sorted on its own, also when the column is sorted descending (a comparator
cannot do this, because the table reverses it for descending):

```java nocompile
table.setSortGroup(entry -> entry.isDirectory());   // null removes the grouping
```

Without a sort the groups still apply, and each keeps the order `setItems` gave. `getSortColumn()` and
`isSortAscending()` read the state.

## Selection

One row is selected at a time unless multi-select is on. A click selects a row, and a click below the last row clears
the selection. The arrow keys, Home, End, Page Up and Page Down move it, and Enter or a double-click opens the row.

```java nocompile
table.setMultiSelect(true);
table.onSelectionChange(items -> status.setText(items.size() + " selected"));
table.onSelect(item -> preview.show(item));         // the lead row changed; null when cleared
table.onActivate(item -> open(item));               // a double-click, or Enter on the lead row
List<Entry> chosen = table.getSelectedItems();      // top to bottom
```

With multi-select, the keys work like they do in [`TreeView`](tree-view.md#selecting-several-rows): Cmd-click (Ctrl-click
off macOS) adds or removes a row, Shift-click selects a range from the last clicked row, Shift with the arrow keys extends
it, and Cmd/Ctrl+A selects every row. `getSelected()` is the lead row, which the arrow keys move on from.

| Method | What it does |
|---|---|
| `getSelected()`, `getSelectedItems()`, `isSelected(item)` | What is selected |
| `setSelected(item)` | Selects only that row and scrolls to it; null or an item not in the table clears it |
| `setSelectedItems(items)` | Selects exactly these; items not in the table are skipped |
| `selectAll()`, `clearSelection()` | Every row, or none |

The setters are silent: `onSelect` and `onSelectionChange` fire only for the user's own changes. A `refresh()` that finds
a selected item gone drops it quietly.

## Check boxes

```java nocompile
table.setCheckBoxes(true);
table.onCheck((entry, on) -> updateTotal());
List<Entry> toExtract = table.getCheckedItems();   // top to bottom
```

A column of boxes before the others, with one in the header, chooses rows apart from selecting them (the files to extract, the
rows to delete). A click on a row's box, or Space on the lead row, ticks it; **ticking never changes the selection**. The
header's box ticks every row, or clears them all when every row is ticked, and shows a dash while some are. The boxes are 30
points wide and push the other columns right.

| Method | What it does |
|---|---|
| `setCheckBoxes(boolean)` | Shows or hides the boxes. Off by default |
| `isChecked(item)`, `getCheckedItems()` | What is ticked; the list is top to bottom |
| `setChecked(item, on)` | Ticks or clears one. An item not in the table is ignored |
| `setCheckedItems(items)`, `checkAll()`, `clearChecked()` | Tick exactly these, every row, or none |
| `onCheck((item, on) -> ...)` | The user ticked or cleared a box. The header's box fires it for each row it changes |

Ticks are kept by the items' `equals`, like the selection, and survive a sort. The setters are silent. A `refresh()` forgets the
ticks of items that are gone, and `setItems` starts with none ticked unless the same items come back.

## Editing cells

```java nocompile
table.addColumn("Name", Item::name).setEditable((item, text) -> item.rename(text));
table.addColumn("Qty", i -> i.qty() + " pcs")
        .setAlignment(TextAlign.RIGHT)
        .setEditText(i -> String.valueOf(i.qty()))              // type 12, not "12 pcs"
        .setEditable((item, text) -> item.setQty(Integer.parseInt(text.trim())));
```

A **double-click** on a cell of an editable column, or **F2** on the selected row (its first editable column), puts a text field
over the cell with its text selected, in the cell's alignment. **Enter**, or a click anywhere else, ends the edit and calls the
column's listener with the row's item and the new text, if the text changed; **Escape** cancels. Double-clicking a cell that is
not editable still opens the row through `onActivate`, and Enter on the row still does.

The table does **not change the model**: the listener does, and the cell shows the new text from the next frame. Call
`refresh()` too when the column sorts, so the row moves. Text that cannot be used (a quantity that is not a number) is the
listener's to refuse: ignore it, and the cell keeps what it showed.

| Method | What it does |
|---|---|
| `startEdit(item, column)` | Starts an edit, such as from a context menu. Does nothing unless the column is editable and the item is in the table. Ends an edit already open, keeping its text |
| `isEditing()`, `getEditingItem()` | Whether a cell is being edited, and which row |
| `commitEdit()`, `cancelEdit()` | End the edit as Enter or Escape would |

The field follows its row through a sort, and an edit ends without reporting when its row goes away (`refresh()` without it),
when `setItems` brings new rows, when its column is removed or when the check boxes are switched on or off.

## Keys and context menu

`onKey((key, mods) -> ...)` gets every key first, for keys the table has no use for (Delete, say); return true to use
it up. Clicking the table gives it the keyboard, and so does Tab.

A right-click on a row selects it before the [context menu](../input/menus.md#context-menus) opens, unless the row is
already part of a multi-selection, which then stays, so the menu can act on `getSelectedItems()`:

```java nocompile
table.setContextMenu(new PopupMenu(
        new MenuItem("Open", () -> Browser.open(table.getSelected().path())),
        new MenuItem("Show in Finder", () -> Browser.reveal(table.getSelected().path()))));
```

## Look

The table follows the [theme](../styling/theming.md) as soon as it is made, like a container that
[uses the theme](container.md): the container background and border, the theme's text color for the rows and the header,
its placeholder color for dimmed rows, and the theme's [accent tint](../styling/theming.md#the-accent) for selected rows. The selection is drawn fainter while
the table does not have the keyboard.

```java nocompile
table.setRowHeight(22f);       // default 24
table.setHeaderHeight(30f);    // default 28
table.setFontSize(14f);        // default 15
table.setIconSize(16f);        // default 14
table.setStriped(true);        // every other row slightly darker
table.setRowCornerRadius(4f);  // rounds the selected and hovered rows
table.setTextColor(Color.WHITE); table.setDimTextColor(Color.GRAY); table.setSelectionColor(...); // pin against the theme
table.setHeaderTextColor(table.getDimTextColor()); // quieter column titles; null follows the theme again
table.dispose();               // frees the font
```

## Gotchas

- `onMouseClick` on the table is safe to use: the table's own right-click row selection runs first, then your handler.
- Text that does not fit its cell ends with an ellipsis. Resize the column, give it a wider default, or turn `setEllipsisInMiddle` on to keep a file extension.
- Rows cannot be dragged or reordered by the user. For a hierarchy use [`TreeView`](tree-view.md).
- Cell editing is a text field: there is no drop-down, number spinner or check box cell editor, and Tab does not move on to the
  next cell. A check box column is the table-wide [boxes](#check-boxes), not one per column.
- A double-click on an editable cell edits it instead of calling `onActivate`.
- One `equals` per item: two entries that are equal are one selection. Use records or types with identity or a unique
  key.
- The boxes are one column for the whole table. For a tree of items, see `TreeView`'s [check boxes](tree-view.md#check-boxes),
  which can tick everything under a folder.
