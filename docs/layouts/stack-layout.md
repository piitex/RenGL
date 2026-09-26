# StackLayout

[Documentation](../README.md) › [Layouts](README.md)

`me.piitex.engine.ui.layout.StackLayout` **layers** its children in the same area instead of placing them
beside each other. The first child added is drawn at the back, and each later child is drawn over it. Use it for
a badge on an icon, a spinner over the content it is waiting on, or text centered on an image.

Each child is placed independently inside the layout's **padded** bounds:

`getAlignment()` decides where (any of the nine positions, default `TOP_LEFT`). `getSpacing()` has no effect here.

```java
StackLayout stack = new StackLayout(20, 20, 200, 36);
stack.setAlignment(Layout.Alignment.CENTER);

LoadingSpinnerOverlay spinner = new LoadingSpinnerOverlay(20);
stack.addElement(new ButtonOverlay("Loading...", 200, 36)); // back
stack.addElement(spinner); // front

// Pin just the spinner to the right end; the button stays centered.
stack.setChildAlignment(spinner, Layout.Alignment.CENTER_RIGHT);

root.addElement(stack);
```

## Constructors

| Constructor | Notes |
|---|---|
| `StackLayout(double width, double height)` | Positioned at `0, 0` |
| `StackLayout(double x, double y, double width, double height)` | |

## Per-child alignment

```java
stack.setChildAlignment(child, Layout.Alignment.TOP_RIGHT);
```

Overrides the placement for one child. Passing `null` clears the override, so the child follows the layout's own
alignment again. This is how you pin a badge to the top-right corner while the rest stay centered:

```java
StackLayout avatar = new StackLayout(64, 64);
avatar.setAlignment(Layout.Alignment.CENTER);

ImageOverlay photo = new ImageOverlay("src/main/resources/demo.png", 64, 64);
IconOverlay badge = new IconOverlay(Feather.CHECK_CIRCLE, 18, 18);
badge.setTint(Color.GREEN);

avatar.addElement(photo);
avatar.addElement(badge);
avatar.setChildAlignment(badge, Layout.Alignment.TOP_RIGHT); // top-right
```

## Behavior notes

- Children keep their own size. A child larger than the layout is aligned by its top-left corner and overflows.
- `setSizeToContent(true)` makes the layout as big as its largest child on each axis, plus padding.
- Click routing goes to the topmost child under the cursor. The front layer wins where children overlap.
- Positions are recomputed every frame.
