# TreeView

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.containers.TreeView<T>` shows items in a tree, one row each, indented under their parent with
an arrow to open and close them: an editor's hierarchy panel, or a file browser. The user selects rows (one, or
several), ticks check boxes, opens rows, renames them in place and drags them to new places.

It is a [`ScrollContainer`](scroll-container.md), so the wheel and the scrollbar work the same way.

```java nocompile
TreeView<Entity> tree = new TreeView<>(260, 500, Entity::getChildren); // width, height, what is under an item
tree.setPosition(8, 40);
tree.setLabelProvider(Entity::getName);
tree.setRoots(scene.getEntities());
tree.onSelect(entity -> inspector.show(entity));
panel.addElement(tree);
```

`me.piitex.examples.demo.TreeDemo` is a runnable hierarchy panel over the [entity demo](../game/entities.md)'s scene.

## The model

The tree does not keep the items. It asks the function given to the constructor what is under each item, starting
from the roots, and draws the rows itself, so a tree of thousands of items costs no more than the rows on screen.

| Method | What it does |
|---|---|
| `setRoots(collection)` | The top-level items. The collection is kept, not copied, so a live list such as `scene.getEntities()` stays current |
| `refresh()` | Reads the model again. Call it after adding, removing, moving or renaming items |
| `setLabelProvider(item -> text)` | The row's text. `String.valueOf` by default |
| `setDimmed(item -> boolean)` | Items drawn in the dim text color, such as inactive entities |
| `setIconProvider(item -> IconAsset)` | An icon before each name, such as `Feather.FOLDER`, or null for none on that item |
| `setIconColorProvider(item -> Color)` | The icon's color. The text color when unset or null |
| `setParentProvider(item -> parent)` | How to find an item's parent, for `reveal`. Without it, `reveal` searches from the roots |
| `getVisibleItems()` | The items in the rows shown now, top to bottom |

The children function may return null or an empty collection for an item with no children. Such an item has no
arrow.

Items are told apart by `equals`: the open items and the selection are remembered that way across `refresh()`. An
`Entity` uses identity, and a `Path` its value, so a file tree keeps its open folders when it lists the disk again.

## Opening and closing

The arrow opens and closes a row, and so do Left and Right on the keyboard.

```java nocompile
tree.expand(item); tree.collapse(item); tree.setExpanded(item, true);
boolean open = tree.isExpanded(item);
tree.expandAll(); // every item that has children
tree.collapseAll(); // only the roots show
tree.reveal(item); // opens every item above it and scrolls to it; false if it is not in the tree
```

Closing an item that has the selection inside it selects the item itself.

## Selection

One row is selected at a time unless [multi-select](#selecting-several-rows) is on. The user selects with a click, a
right-click, or the arrow keys, and clicking below the last row clears the selection.

```java nocompile
tree.onSelect(item -> ...); // the user changed it; null when cleared
tree.setSelected(item); // silent; opens the items above it and scrolls to it. null clears it
Entity selected = tree.getSelected();
tree.onActivate(item -> ...); // a double-click, or Enter on the selected row
```

A double-click on an item with children also opens or closes it, before `onActivate` fires. Turn that off with
`tree.setToggleOnDoubleClick(false)`, such as when a double-click renames.

`onSelect` fires only for the user's own changes. (With multi-select it fires when the *lead* row changes; see below.) `setSelected` and `refresh()` do not fire it; if a refresh finds
the selected item gone (removed, or inside a closed item), the selection is cleared quietly.

## Selecting several rows

```java nocompile
tree.setMultiSelect(true);
tree.onSelectionChange(items -> status.setText(items.size() + " selected")); // every selected item, top to bottom
List<Path> chosen = tree.getSelectedItems();
```

| User action | Result |
|---|---|
| Click | Selects that row only |
| Cmd-click (Ctrl-click off macOS) | Adds the row to the selection, or removes it if it was selected |
| Shift-click | Selects every row from the last clicked one (the *anchor*) to this one, replacing the rest |
| Shift + Cmd/Ctrl-click | The same range, added to what is selected |
| Shift + Up, Down, Home, End, Page Up, Page Down | Extends the range from the anchor |
| Cmd/Ctrl + A | Selects every row shown |
| Right-click | A row that is in the selection keeps it, so a context menu acts on all of them; any other row becomes the only selection |

`getSelected()` is the **lead**: the row clicked or moved to last, which the arrow keys move on from.
`onSelect` fires when the lead changes, and `onSelectionChange` when any row joins or leaves the selection. Neither
fires for the setters below or for `refresh()`.

| Method | What it does |
|---|---|
| `getSelectedItems()` | Every selected item, in the order the rows are shown; empty if none |
| `isSelected(item)` | Whether one is in the selection |
| `setSelectedItems(items)` | Selects exactly these, opening the items above them; items not in the tree are skipped |
| `selectAll()` | Every row shown (not the ones inside closed items) |
| `clearSelection()` | Nothing selected |

Pressing a row that is one of several keeps them all selected until the button is released, so they can be
dragged together: if a dragged row is in the selection, `onDrop` gets the pressed row, and the whole selection is
`getSelectedItems()`. Releasing without dragging selects only that row. Closing an item that has selected rows inside
it removes them from the selection and selects the closed item. `refresh()` forgets selected items that are gone.

## Check boxes

```java nocompile
tree.setCheckBoxes(true);
tree.setCascadeChecks(true);              // ticking a folder ticks everything under it
tree.onCheck((item, on) -> updateTotal());
List<Path> toExtract = tree.getCheckedItems();
```

A box before each row's icon and name chooses items apart from selecting them: a click on the box, or Space on the
lead row, ticks it, and checking never changes the selection. Checked items are remembered by `equals` and include
ones inside closed items.

| Method | What it does |
|---|---|
| `setCheckBoxes(boolean)` | Shows or hides the boxes. Off by default |
| `setCascadeChecks(boolean)` | Ticking or clearing an item with children does the same to everything under it. Off by default |
| `isChecked(item)`, `getCheckedItems()` | What is checked, in the order it was checked |
| `setChecked(item, on)` | Ticks or clears one (and what is under it, with cascade). Does not fire `onCheck` |
| `clearChecked()` | Clears every box |
| `onCheck((item, on) -> ...)` | The user ticked or cleared a box. An item ticked only because a parent was is not reported on its own |

There is no half-ticked state: a folder shows ticked only if it was ticked itself, so a folder whose children were
ticked one by one shows empty. Items are not forgotten when removed from the model; call `clearChecked()` when you load
something new into the tree.

## Renaming in place

```java nocompile
tree.setToggleOnDoubleClick(false); // so double-clicking a parent only renames it
tree.onActivate(tree::startRename); // double-click renames
tree.onRename((entity, name) -> {
    entity.setName(name);
    tree.refresh();
});
```

`startRename(item)` puts a text field over the item's name with the name selected. Enter or a click elsewhere
finishes it, and Escape cancels. `onRename` fires only if the name changed. `isRenaming()` says whether a rename is
in progress.

## Dragging rows

```java nocompile
tree.setDragEnabled(true);
tree.onDrop(drop -> {
    if (drop.parent() == null) scene.add(drop.item(), drop.index());
    else drop.parent().addChild(drop.item(), drop.index());
    tree.refresh();
});
tree.setCanDrop((item, newParent) -> newParent == null || !isLocked(newParent)); // optional
```

Where a row lands depends on where the pointer is over the row under it:

| Pointer over | The row goes |
|---|---|
| The top quarter of a row | Just before it, beside it |
| The bottom quarter | Just after it, or first inside it if it is open |
| The middle | Inside it, at the end |
| Below the last row | At the end of the top level |
| Above the first row | At the start of the top level |

A line shows a place between rows, and a box shows the row it would go into. Holding a dragged row near the top or
bottom edge scrolls the tree.

A `Drop` is `(item, parent, index)`, with a null parent for the top level. `index` is the gap the row was dropped
into, counted among the parent's children as they are now, the dragged item included if it is already there. That
is how `Entity.addChild(child, index)` and `EntityScene.add(entity, index)` count, so the index can be passed straight
to them.

The tree never drops an item into itself or its own children, and a drop that would leave the item where it is
reports nothing. It never changes the model either: the listener makes the move and calls `refresh()`.

## Keyboard

Clicking the tree gives it the keyboard, and so does Tab.

| Key | Effect |
|---|---|
| Up / Down | Select the row above or below |
| Home / End, Page Up / Page Down | Jump to the first or last row, or by a page |
| Right | Open the selected row, or step into its first child if it is open |
| Left | Close the selected row, or step out to its parent if it is closed |
| Enter | `onActivate` |

`onKey` gets every key first, for keys the tree has no use for:

```java nocompile
tree.onKey((key, mods) -> {
    if (key == GLFW_KEY_DELETE && tree.getSelected() != null) {
        tree.getSelected().destroy();
        tree.refresh();
        return true; // used
    }
    if (key == GLFW_KEY_F2 && tree.getSelected() != null) {
        tree.startRename(tree.getSelected());
        return true;
    }
    return false;
});
```

## Context menu

A right-click selects the row under the pointer (or clears the selection below the last row) before the
[context menu](../input/menus.md#context-menus) opens, so the menu acts on `getSelected()`:

```java nocompile
PopupMenu menu = new PopupMenu(new MenuItem("Rename", () -> tree.startRename(tree.getSelected())));
menu.onOpen(m -> m.getItems().getFirst().setEnabled(tree.getSelected() != null));
tree.setContextMenu(menu);
```

## Look

The tree follows the [theme](../styling/theming.md) as soon as it is made, like a container that
[uses the theme](container.md): the container background and border, the theme's text color for the rows, its
placeholder color for dimmed items, and the theme's [accent tint](../styling/theming.md#the-accent) for the selected row. The selection is drawn fainter
while the tree does not have the keyboard.

```java nocompile
tree.setRowHeight(22f); // default 24
tree.setIndent(14f); // per level; the arrow takes the same width. Default 16
tree.setFontSize(14f); // default 15
tree.setIconSize(16f); // default 14
tree.setEllipsize(false); // a row's text too long for the tree ends with … by default; off lets the edge cut it
tree.setRowCornerRadius(4f); // rounds the selected and hovered rows. Default 0
tree.setTextColor(Color.WHITE); tree.setDimTextColor(Color.GRAY); tree.setSelectionColor(...); // pin against the theme; any paint
tree.dispose(); // frees the font
```

## Gotchas

- `onMouseClick` on the tree is safe to use: the tree's own right-click row selection runs first, then your handler.
- A row's text that reaches the tree's right edge ends with an ellipsis (`setEllipsize(false)` cuts it instead); the tree does not scroll sideways.
- Multi-select is off by default, so a tree that reads `getSelected()` behaves as before. A context menu that acts on
  `getSelected()` should read `getSelectedItems()` once multi-select is on.
- Checked items outlive the model; see [Check boxes](#check-boxes).
