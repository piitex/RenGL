# Containers

[Documentation](../README.md) › Containers

A container holds child elements and is itself an [`Element`](../core/element.md). Layouts, scroll containers,
split containers, modals and tree views are all containers.

| Page | What it covers |
|---|---|
| [Container](container.md) | Adding children, draw order, clipping, `moveTo`, dragging, drag bounds, collision, theming |
| [ScrollContainer](scroll-container.md) | Clipped content scrolled by wheel, scrollbar thumb or code |
| [ResizableContainer](resizable-container.md) | Follows the window size, scales its children and text, min/max limits, drag-to-resize corners |
| [ModalContainer](modal-container.md) | Dialogs with a dimmed backdrop that block the rest of the UI, close animations, limiting to part of the window |
| [ReorderableGrid](reorderable-grid.md) | A scrolling grid of items the user rearranges by dragging, with drop targets |
| [TreeView](tree-view.md) | A scrolling tree of items with expand arrows, single or multiple selection, check boxes, renaming in place and drag to move |
| [TableView](table-view.md) | A scrolling table with a fixed header: sortable, resizable columns and single or multiple selection |

Containers that position their children automatically are covered under [Layouts](../layouts/README.md), including
[SplitContainer](split-container.md) and [TabContainer](tab-container.md).
