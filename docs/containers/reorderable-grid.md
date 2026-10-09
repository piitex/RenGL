# ReorderableGrid

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.containers.ReorderableGrid` is a scrolling grid of equally sized items that the user can
**rearrange by dragging**. Pressing an item and moving the mouse a few points lifts it; it then follows the cursor
while the other items slide aside to open a slot where it would land, and letting go drops it there. Holding a
lifted item near the top or bottom edge scrolls the grid.

It is a [`ScrollContainer`](scroll-container.md), so scrolling, the scrollbar and its styling work the same way.

```java
ReorderableGrid grid = new ReorderableGrid(620, 400, 140, 100, 12); // width, height, item width, item height, gap
grid.setPosition(20, 20);
grid.useTheme();

List<Container> cards = new ArrayList<>();
for (String name : List.of("Alpha", "Beta", "Gamma", "Delta", "Epsilon")) {
    Container card = new Container(140, 100);
    card.useTheme();
    TextOverlay label = new TextOverlay(name, 16f, Color.BLACK);
    label.setPosition(12, 10);
    card.addElement(label);
    cards.add(card);
}
grid.setItems(cards);

grid.onReorder(order -> saveOrder(order)); // once per drag that changed the order
grid.onItemClick(card -> open(card)); // press and release without dragging
root.addElement(grid);
```

Constructor: `ReorderableGrid(width, height, itemWidth, itemHeight, gap)`, positioned at `0, 0`. Every item is
`itemWidth` by `itemHeight`, and `gap` is the space between them in both directions.

## Items and layout

```java
grid.setItems(cards); // replaces every item and lays them out at once, in this order
List<Container> order = grid.getItems(); // current order, a drag in progress included
boolean dragging = grid.isReordering();
int slot = grid.slotIndexAt(mouseX, mouseY); // slot under a window point, or -1 with no items
```

- Items are `Container`s. They are laid out **left to right**, wrapping to as many columns as fit the grid's width.
- The grid **owns their position.** It moves each item to its slot itself, so an item's own `x`/`y` is ignored.
- Add and remove items with `setItems`, not `addElement`/`removeElement`. To change the order from code, call
  `setItems` with the new order.
- The grid only reports a new order. **Persisting it is up to you.**

## Free placement

By default the items are a packed list: drop one and the others shift along, so there are never gaps. Turn on free
placement and the grid becomes a set of fixed **slots**, some empty, and an item can go in whichever slot the user
likes.

```java
grid.setFreePlacement(true);
grid.setSlotCount(24);               // at least 24 slots, in whole rows. Default: enough to fill the visible area
grid.setItems(Arrays.asList(sword, null, null, shield)); // a list index is a slot, null is an empty slot
grid.onReorder(slots -> save(slots)); // every slot in order, null for an empty one
```

- Dropping on an **empty slot** moves the item there and leaves a gap behind.
- Dropping on an **occupied slot** swaps the two items. The item under the carried one drifts to the carried one's
  old slot while you hover, so the swap shows before you let go. Nothing else moves while an item is carried.
- `getSlots()` lists every slot with `null` for empty ones, `getItems()` only the items, and `slotOf(item)` is an
  item's slot.
- Drop targets, `onDropOnto` and `onDropOutside` work as they do without free placement.
- Set it before `setItems` if the list passed in has empty slots. Turning it off packs the items together in slot
  order. The grid scrolls over every slot, empty ones included.

## Clicks and drags

A press that never moves is a click, reported through `onItemClick`. It fires on **release**, not on press as
[`onMouseClick`](../core/element.md#onmouseclick) does, since a press might still turn into a drag. So do not give an
item its own click listener for its main action.

A child *inside* an item that has a click listener of its own (a delete button, a toggle) keeps its press: pressing
it never starts a drag or an item click. Pressing the scrollbar thumb scrolls as usual.

```java
grid.onLift((card, lifted) -> card.getStyling().setBorderThickness(lifted ? 2f : 1f)); // picked up / dropped
```

While an item is lifted it is drawn above the others and the cursor is `RESIZE_ALL`.

## Dropping onto other items

Some items can be **drop targets**, like a folder that other items can be dropped into.

```java
grid.setDropTargets((lifted, target) -> isFolder(target) && lifted != target);
grid.onTargetHighlight((target, armed) -> target.getStyling().setBorderColor(armed ? Color.BLUE : Color.LIGHT_GRAY));
grid.onDropOnto((item, folder) -> {
    moveIntoFolder(item, folder);
    grid.setItems(currentItems()); // the item stays in its slot until you rebuild
});
```

- Holding a lifted item over the **middle** of a target for a moment (a quarter of a second) *arms* it, and
  `onTargetHighlight(target, true)` fires. Releasing then calls `onDropOnto` instead of reordering.
- Over a target's **edges** the item reorders as usual: at once when carried past the target's center, or after a
  short pause if it lingers before reaching it, so approaching a target does not shove it aside.
- Moving away disarms it with `onTargetHighlight(target, false)`.
- Without `setDropTargets`, no item is a target.

## Dropping outside the grid

```java
grid.onOutsideChanged(outside -> trashZone.getStyling().setBackgroundColor(outside ? Color.RED : Color.GRAY));
grid.onDropOutside(item -> delete(item)); // released with the pointer outside; no reorder is reported
grid.setDropBounds(sidePanel); // judge "outside" against this element instead of the grid; null = the grid
```

- `onOutsideChanged(true)` fires when a lifted item's pointer leaves the bounds, and `false` when it comes back or
  the item is dropped.
- While outside with an `onDropOutside` listener set, the items inside stop shuffling.
- `setDropBounds` is for a grid that is only part of a larger panel, so that leaving the *panel* is what counts.

A lifted item stays clipped inside the grid. To show it carrying on with the pointer outside, draw a stand-in from
`onFollow`, which reports where the item's top-left corner would be in the window:

```java
grid.onFollow((item, x, y) -> ghost.setPosition(x, y));
```

## Callbacks

| Method | Fires |
|---|---|
| `onReorder(Consumer<List<Container>>)` | Once, when a drag ends with the item in a different slot. Receives the new order (with free placement, every slot, `null` for empty) |
| `onItemClick(Consumer<Container>)` | An item was pressed and released without being dragged |
| `onLift(BiConsumer<Container, Boolean>)` | `true` when an item is picked up, `false` when it is dropped |
| `setDropTargets(BiPredicate<Container, Container>)` | Asked with (lifted item, candidate) whether the candidate accepts the drop |
| `onTargetHighlight(BiConsumer<Container, Boolean>)` | A target was armed (`true`) or disarmed (`false`) |
| `onDropOnto(BiConsumer<Container, Container>)` | Released over an armed target, with (item, target) |
| `onOutsideChanged(Consumer<Boolean>)` | The lifted item's pointer left (`true`) or re-entered (`false`) the drop bounds |
| `onDropOutside(Consumer<Container>)` | Released outside the drop bounds |
| `onFollow(Follower)` | Every time the lifted item follows the pointer, with (item, windowX, windowY) |

Each holds one listener. Setting it again replaces the previous one.

## Gotchas

- All items share one size. Items of a different size overlap or leave gaps.
- Scrolling by holding a lifted item near an edge is vertical only.
- The column count comes from the grid's width, so a grid resized narrower rewraps its items.
- Only the left mouse button drags.
