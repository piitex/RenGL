# ModalContainer

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.containers.ModalContainer` is a dialog: a dimmed full-window backdrop with a **content panel**
centered on it, drawn above everything else. While a modal is showing, clicks, hover, scrolling and Tab
traversal stay inside it. A modal can also be [limited to part of the window](#limiting-the-modal-to-part-of-the-window),
leaving the rest usable.

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

If you make the panel draggable (`modal.getContent().setDraggable(true)`), the user can move it, and it keeps the
offset it was dragged to from the center, window resizes included.

## Showing and closing

```java
modal.show(window); // fades in, blocks the UI behind it
modal.close(); // plays the close animation, if any; no-op if it is not showing
modal.closeImmediately(); // removes it now, skipping the close animation
boolean up = modal.isShowing();
```

It closes when:

- you call `close()` or `closeImmediately()`,
- the user clicks the backdrop (if `setDismissOnBackdropClick(true)`, the default),
- the user presses Escape (if `setDismissOnEscape(true)`, the default).

Only **one modal at a time**. Showing a second closes the first, and any open popup too. Showing a modal
clears keyboard focus.

A dropdown opened from inside the modal still works. Its popup layers above the modal.

## Close animations

By default a modal disappears the moment it closes. A close animation plays first instead, on `close()`, a
backdrop click and Escape. The modal keeps blocking the rest of the UI until the animation finishes.

```java
modal.setFadeOutSeconds(0.2f); // shorthand for setCloseAnimation(CloseAnimation.fade(0.2f)); 0 = none

// The panel shrinks toward a point in the window while everything fades, like a folder
// closing back into its icon. (targetX, targetY) is in window coordinates.
modal.setCloseAnimation(ModalContainer.CloseAnimation.shrink(0.25f, iconX, iconY, 0.2f));

modal.setCloseAnimation(null); // back to closing instantly
```

| Built-in | What it does |
|---|---|
| `CloseAnimation.fade(seconds)` | The whole modal, backdrop included, fades out |
| `CloseAnimation.shrink(seconds, targetX, targetY, endScale)` | The panel [scales](../core/element.md#scale) to `endScale` around (`targetX`, `targetY`) while everything fades out |

`CloseAnimation` is a functional interface, so a custom one is a lambda. It receives the modal and a `Runnable`
that it **must** call once when it is done, which removes the modal:

```java
modal.setCloseAnimation((m, onFinished) -> {
    m.getContent().playTransition(new ScaleTransition(1f, 1.1f, 0.15f, Easing.EASE_OUT, null));
    m.fadeOut(0.15f, onFinished);
});
```

Calling `close()` again while the animation runs does nothing. `closeImmediately()` (and `window.closeModal()`)
skips it. The next `show` resets the opacity and scale an animation left behind.

## Limiting the modal to part of the window

```java
// Cover only the content area, leaving a 240pt sidebar on the left usable.
modal.setArea(240, 0, 1040, 720); // x, y, width, height in window coordinates
modal.show(window);

modal.clearArea(); // back to covering the whole window
```

With an area set, the backdrop is drawn only there, the panel is centered in it, and only presses inside it are
blocked, so the rest of the window keeps working. Set it before `show` and again whenever the layout changes (for
example from [`window.onResize`](../core/window.md#resize-event)).

## Options

```java
modal.setDismissOnBackdropClick(false); // force the user to use a button
modal.setDismissOnEscape(false);
modal.setBackdropColor(new Color(0f, 0f, 0f, 0.7f)); // default: 55% black
modal.setFadeSeconds(0.25f); // fade-in; 0 = instant, default 0.15
modal.setFadeOutSeconds(0.15f); // fade-out on close; 0 = instant, the default
modal.onClose(() -> System.out.println("closed")); // fires on every close route
```

| Method | Default |
|---|---|
| `setBackdropColor(Color)` | `Color(0, 0, 0, 0.55)` |
| `setDismissOnBackdropClick(boolean)` | `true` |
| `setDismissOnEscape(boolean)` | `true` |
| `setFadeSeconds(float)` | `0.15` |
| `setCloseAnimation(CloseAnimation)` / `setFadeOutSeconds(float)` | none, closes instantly |
| `setArea(x, y, width, height)` / `clearArea()` | the whole window |
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

- The panel is centered using `window.getWindowOptions()`, whose width and height follow the real window size.
- The modal registers a click handler on itself that closes it when the press lands outside the panel. Presses
  on the panel, or on children without a click listener of their own, reach that handler too (clicks go up to
  the nearest listener), but they never close the modal. Do not replace it with your own `onMouseClick`.
- Under the hood `show` calls `Window.showModal`, and `close` calls `Window.closeModal` (after the close
  animation, if there is one). See [Window](../core/window.md#modals).
