# InventoryGridOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.InventoryGridOverlay<T>` is a grid of item slots that the player moves things between: a backpack, a chest, an
equipment panel, a hotbar, a shop's stock.

```java
InventoryGridOverlay<Item> bag = new InventoryGridOverlay<>(6, 4, 48);     // columns, rows, slot size
bag.setImageProvider(Item::sprite);
bag.setMaxStack(item -> item.stackSize());
bag.setTooltipProvider(Item::description);
bag.add(potion, 5);

InventoryGridOverlay<Item> chest = new InventoryGridOverlay<>(6, 3, 48);
bag.link(chest);                                  // items can be dragged between the two
bag.onUse(slot -> use(bag.getItem(slot)));        // right-click, double-click or Enter
```

The grid holds up to a slot's stack limit of one kind of item in each slot. The items are your own objects of type `T`, told apart by
`equals`; the grid keeps no other rules, so the game decides what an item is.

## Contents

| Call | What it does |
|---|---|
| `getItem(slot)`, `getCount(slot)`, `isEmpty(slot)` | Read a slot; slots are numbered left to right, then down |
| `setSlot(slot, item, count)` / `clearSlot(slot)` | The game sets a slot, ignoring locks, filters and the stack limit |
| `add(item, count)` | Tops up stacks of the same item first, then uses empty slots; skips locked slots and ones that refuse it. Returns how many **did not fit** |
| `remove(item, count)` | Takes from the last slots first; returns how many were taken |
| `countOf(item)`, `indexOf(item)`, `firstEmpty()` | Searching |
| `setGrid(columns, rows)` | Resizes; slots keep their numbers, so growing keeps every item and shrinking drops the ones cut off |

`onSlotChange(slot -> ...)` is called for each slot whose contents change, however they changed, so a game model can follow.

## Moving

The player presses a slot and drags: the whole stack, or half of it with **Ctrl** held. Dropped on:

- an **empty slot**: the items move there;
- a slot of the **same item**: they join it, up to the stack limit, and the rest stay;
- a **different item**: the two swap, when the whole stack was dragged and both slots accept what they are given.

A drop that is not allowed leaves everything as it was, and the slot under the pointer is tinted green or red while dragging. **Shift-click** sends
a stack to a linked grid. A drop outside every grid goes to `onDropOutside((slot, item, count) -> ...)`, which returns true when it took the
items (threw them into the world, say); without a handler it is a cancelled drag.

The same moves are available to code: `move(slot, targetGrid, targetSlot, count)` and `quickMove(slot)`.

```java
bag.link(chest);       // both ways; between any number of grids
bag.unlink(chest);
```

Dragging between grids needs them linked.

## Rules

```java
bag.setLocked(11, true);                                   // a slot not yet bought: no use, no drops, add() skips it
bag.setSlotFilter(0, item -> item.isHelmet());             // an equipment slot
bag.setFilter(item -> !item.isQuestItem());                // the whole grid
bag.setMaxStack(item -> item.stackSize());                 // 99 for every item by default
```

## Selecting and using

A click selects a slot (`onSelect`). A right-click, a double-click or Enter calls `onUse(slot)`, never for an empty or locked slot. The arrow keys
move the selection when the grid has the keyboard (Tab to it).

## Look

```java
bag.setImageProvider(item -> item.sprite());       // a Skia Image, drawn full color
bag.setIconProvider(item -> item.featherIcon());   // or a vector icon, tinted with the text color
bag.setLabelProvider(Item::name);                  // the fallback: text cut to fit the slot
bag.setBorderProvider(item -> rarityColor(item));  // the slot frame by item; null for the plain one
bag.setTooltipProvider(Item::description);
bag.setGap(6);
bag.setPadding(8);
```

Counts above 1 are drawn at the corner of the slot. The held stack follows the pointer, drawn over the grid, so a grid inside a clipping scroll
container clips it at the container's edge. Colors come from the theme: the slots from the field background and border, the selection from the
accent. Call `dispose()` when permanently discarding one.
