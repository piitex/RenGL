# ModalContainer

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.containers.ModalContainer` is a dialog: a dimmed full-window backdrop with a **content panel**
centered on it, drawn above everything else. While a modal is showing, clicks, hover, scrolling and Tab
traversal stay inside it.

```java
VerticalLayout panel = new VerticalLayout(0, 0, 320, 160);
panel.setSpacing(16);
panel.setPadding(20);
panel.setAlignment(Layout.Alignment.TOP_CENTER);
panel.useTheme();
panel.addElement(new TextOverlay("Are you sure?", 24f, Color.BLACK));

ModalContainer modal = new ModalContainer(panel);

ButtonOverlay close = new ButtonOverlay("Close", 100, 32);
close.onAction(modal::close);
panel.addElement(close);

ButtonOverlay open = new ButtonOverlay("Open modal", 150, 36);
open.onAction(() -> modal.show(window));
root.addElement(open);
```

## Constructors

| Constructor | Content panel |
|---|---|
| `ModalContainer(double width, double height)` | A new themed [`Container`](container.md) of that size. Add children through `getContent()` |
| `ModalContainer(Container content)` | Your own container, usually a [`VerticalLayout`](../layouts/vertical-layout.md). Call `content.useTheme()` yourself for the themed panel look |

With `ModalContainer(width, height)`, children are positioned relative to the panel's top-left corner. The modal
re-centers the panel, children included, every frame, so it stays centered through window resizes. Using a
layout as the content avoids positioning by hand.

## Showing and closing

```java
modal.show(window); // fades in, blocks the UI behind it
modal.close(); // no-op if it is not showing
boolean up = modal.isShowing();
```

It closes when:

- you call `close()`,
- the user clicks the backdrop (if `setDismissOnBackdropClick(true)`, the default),
- the user presses Escape (if `setDismissOnEscape(true)`, the default).

Only **one modal at a time**. Showing a second closes the first, and any open popup too. Showing a modal
clears keyboard focus.

A dropdown opened from inside the modal still works. Its popup layers above the modal.

## Options

```java
modal.setDismissOnBackdropClick(false); // force the user to use a button
modal.setDismissOnEscape(false);
modal.setBackdropColor(new Color(0f, 0f, 0f, 0.7f)); // default: 55% black
modal.setFadeSeconds(0.25f); // fade-in; 0 = instant, default 0.15
modal.onClose(() -> System.out.println("closed")); // fires on every close route
```

| Method | Default |
|---|---|
| `setBackdropColor(Color)` | `Color(0, 0, 0, 0.55)` |
| `setDismissOnBackdropClick(boolean)` | `true` |
| `setDismissOnEscape(boolean)` | `true` |
| `setFadeSeconds(float)` | `0.15` |
| `onClose(Runnable)` | none |
| `getContent()` | the panel |

## Example: confirm dialog with an action

```java
ModalContainer confirm = new ModalContainer(320, 140);
confirm.setDismissOnBackdropClick(false);

Container box = confirm.getContent();
TextOverlay question = new TextOverlay("Delete this item?", 20f, Color.BLACK);
question.setPosition(20, 20);
box.addElement(question);

ButtonOverlay yes = new ButtonOverlay("Delete", 120, 32);
yes.setPosition(20, 80);
yes.onAction(() -> {
    System.out.println("deleted");
    confirm.close();
});
box.addElement(yes);

ButtonOverlay no = new ButtonOverlay("Cancel", 120, 32);
no.setPosition(160, 80);
no.onAction(confirm::close);
box.addElement(no);
```

## Notes

- The panel is centered using `window.getWindowOptions()`, which holds the size the window was created with.
  See [Known limitations](../reference/known-limitations.md).
- The modal registers a click handler on its backdrop that closes it. Presses on the panel or its children
  never reach that handler.
- Under the hood `show` calls `Window.showModal`, and `close` calls `Window.closeModal`. See
  [Window](../core/window.md#modals).
