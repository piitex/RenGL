# ToastManager

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.containers.ToastManager` shows short messages in a corner of the window that stay a few seconds and fade away on
their own: "Game saved", "Download finished", "Could not reach the server". Nothing has to be laid out or removed; ask for one and
forget it.

```java
ToastManager toasts = ToastManager.of(window);
toasts.success("Game saved");
toasts.error("Upload failed", "The server did not answer.");     // a title and a message
toasts.show(new ToastManager.Toast("Item removed")
        .setDuration(6f)
        .setAction("Undo", () -> restore(item)));
```

`ToastManager.of(window)` returns the window's manager, adding it to the window the first time. It keeps itself above every other
container (a dialog included) while a toast is showing, and takes the pointer only where a toast is, so the rest of the window
behaves as if it were not there. It can be called from any thread; toasts are laid out and drawn on the render thread.

## Toasts

| Call | Level |
|---|---|
| `info(message)`, `success(message)`, `warning(message)`, `error(message)` | The level sets the color of the stripe on the toast's edge |
| `info(title, message)` and the same for the other three | The same with a bold title line |
| `show(String message)`, `show(message, level)` | |
| `show(Toast)` | A toast you built, to set the duration, an action or a callback |

A `Toast` is built with `new ToastManager.Toast(message)` and its setters, which return it:

```java
new ToastManager.Toast("Level 5 reached")
        .setTitle("Congratulations")
        .setLevel(ToastManager.Level.SUCCESS)
        .setDuration(5f)                       // seconds after sliding in; 0 or less keeps it until clicked
        .setAction("View", this::openStats)    // a label at the end, run when the toast is clicked
        .onDismiss(() -> log("toast gone"));   // by any route
```

`show` returns the toast; call `dismiss()` on it to start it fading at once. A toast slides in, waits (the timer is **paused while the
pointer is over it**), and fades out. Clicking one runs its action, if any, and dismisses it. `toasts.clear()` removes every toast at
once without the fade, and `toasts.getToasts()` lists them.

## Where and how many

```java
toasts.setPosition(ToastManager.Position.TOP_CENTER);   // default BOTTOM_RIGHT
toasts.setMaxVisible(3);                                // the rest wait their turn; default 5
toasts.setToastWidth(400);                              // default 340; less on a narrower window
toasts.setMargin(24);                                   // from the window's edge; default 16
```

Toasts stack away from the corner, newest nearest it. Positions are `TOP_LEFT`, `TOP_CENTER`, `TOP_RIGHT`, `BOTTOM_LEFT`,
`BOTTOM_CENTER` and `BOTTOM_RIGHT`.

## Look

The toasts follow the theme (container background and border, text color, accent). To restyle:

```java
toasts.setBackgroundPaint(new Color(0.1f, 0.1f, 0.12f, 0.95f));   // pins it against theme switches
toasts.setTextPaint(Color.WHITE);
toasts.setAccentPaint(Color.BLUE);     // the INFO stripe and nothing else; the other levels have fixed colors
toasts.setFontSize(16);
toasts.setCornerRadius(10);
```

## Notes

- A toast that is a message to read slowly, or one the user must act on, is a [`MessageBox`](message-box.md), not a toast.
- Toasts are for one window. In a program with several windows, call `ToastManager.of(window)` on the right one.
- The manager is an ordinary container of the window, found again by `of(window)`, so closing the window frees it. `dispose()` frees its fonts.
