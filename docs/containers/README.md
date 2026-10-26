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
| [ListBox](list-box.md) | A scrolling list of items to pick one or several from, with filtering, type-ahead and custom rows |
| [AccordionContainer](accordion-container.md) | Sections with a title bar over a body that folds away, optionally one open at a time |
| [ToastManager](toast-manager.md) | Short messages that slide into a corner of the window and fade away on their own |
| [MessageBox](message-box.md) | One-line `alert`, `confirm`, `prompt` and `choose` dialogs over a modal |
| [ToolBar](tool-bar.md) | A row of flat icon and text buttons, dividers and any other widget |
| [StatusBar](status-bar.md) | The strip along the bottom: a message at the left and readouts at the sides |

Containers that position their children automatically are covered under [Layouts](../layouts/README.md), including
[SplitContainer](split-container.md) and [TabContainer](tab-container.md).

`me.piitex.examples.demo.WidgetsDemo` shows a `ListBox`, a combo box, a range slider, an accordion, a tool bar, a status bar, toasts and message boxes in one window.
