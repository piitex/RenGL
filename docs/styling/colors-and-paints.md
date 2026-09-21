# Colors and paints

[Documentation](../README.md) › [Styling](README.md)

Anywhere the API draws a fill, it accepts a **`Paint`**. `Paint` is a marker interface with three families of
implementations:

```text
Paint
 ├─ Color                       a solid RGBA color
 ├─ LinearGradient              two colors blended across the element
 │   └─ ScrollingGradient       a gradient that slides over time (also an AnimatedPaint)
 └─ AnimatedPaint               a Paint with per-frame state (interface)
```

## Color

`me.piitex.engine.ui.color.Color` is an immutable RGBA color with float components in `[0, 1]`.

```java
Color red = new Color(1f, 0f, 0f, 1f); // r, g, b, a
Color translucent = new Color(0f, 0f, 0f, 0.5f); // 50% black
Color gray = new Color(0.5f); // see the note below
```

> **`new Color(float v)` sets all four channels, alpha included, to `v`.** `new Color(1f)` is opaque white,
> but `new Color(0.5f)` is mid-gray at **50% opacity**. Use the four-argument constructor when you want an opaque gray.

```java
Color red = new Color(1f, 0f, 0f, 1f);
float r = red.getR(); // getG(), getB(), getA()
int argb = red.toARGB();
```

There is no hex-string constructor. Convert hex values yourself:

```java
static Color hex(int rgb) {
    return new Color(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1f);
}
Color brand = hex(0x3B82F6);
```

### Named colors

`Color` defines the standard CSS/X11 color keywords as constants (`Color.CORNFLOWER_BLUE`,
`Color.DARK_SLATE_GRAY`, `Color.DEEP_SKY_BLUE`, ...), plus `Color.BLACK`, `Color.WHITE`, `Color.RED`, `Color.BLUE`
and `Color.TRANSPARENT`.

Three older names **do not match the CSS values** and were left alone so existing code does not change:

| Constant | Actual value |
|---|---|
| `Color.GREEN` | Pure bright green (`0, 1, 0`). The CSS "green" shade is `Color.LIME` here |
| `Color.LIGHT_GRAY` | An engine-specific light gray |
| `Color.DARK_GRAY` | `0.3, 0.3, 0.3` |

## Gradients

```java
LinearGradient gradient = new LinearGradient(Color.BLUE, Color.GREEN);
```

A `LinearGradient` runs from its start color to its end color across the drawn area (top-left to bottom-right
for a box; across the text's own bounds for text).

Where a gradient works:

| Use | Works? |
|---|---|
| Element background and hover color (`styling.setBackgroundColor(gradient)`) | Yes |
| Text color (`TextOverlay.setTextColor`, and other text widgets) | Yes |
| Window background (`window.setBackgroundColor`) | Yes |
| **Border color** | **No.** A gradient border is silently not drawn. Only a plain `Color` border renders |
| Scrollbar colors | No. Plain `Color` only |

### Scrolling (animated) gradients

```java
ScrollingGradient flow = new ScrollingGradient(Color.BLUE, Color.GREEN, 50f); // speed in px/second along the gradient axis

title.setTextColor(flow);
window.setBackgroundColor(new ScrollingGradient(Color.BLUE, Color.BLACK, 500f));
```

The scroll offset is mutable per-instance state, advanced once per frame by the element that owns it. **Give each
element its own instance.** Sharing one instance between two elements makes them scroll in lockstep and advance
the offset twice per frame.

## AnimatedPaint

```java
public interface AnimatedPaint extends Paint {
    void advance(float deltaSeconds);
}
```

An `AnimatedPaint` is a paint with per-frame state. The engine calls `advance(delta)` once per frame on any
`AnimatedPaint` it finds in an element's styling colors, a text color, or the window background. `ScrollingGradient`
is the built-in one.

The renderer itself only knows how to draw `Color` and `LinearGradient`, so a new `AnimatedPaint` is only useful if
it *is* one of those, or if you read its state yourself while drawing (for example in an `onRender` hook):

```java
public class Pulse implements AnimatedPaint {
    private float time;

    @Override
    public void advance(float deltaSeconds) {
        time += deltaSeconds;
    }

    public Color current() {
        float a = 0.5f + 0.5f * (float) Math.sin(time * 3);
        return new Color(1f, 0.3f, 0.3f, a);
    }
}
```

```java
Pulse pulse = new Pulse();
element.getStyling().setBackgroundColor(pulse); // advanced every frame by the element...
element.onRender(event -> // ...and drawn by you
        event.getRenderer().drawBorder((float) element.getX(), (float) element.getY(),
                (float) element.getWidth(), (float) element.getHeight(), pulse.current(), 3f, 0f));
```

Keep `advance` cheap. It runs every frame for every element that uses the paint. (A plain `Pulse` stored as a
background is not drawn by the built-in fill, since it is neither a `Color` nor a `LinearGradient`. Only your
`onRender` code draws it.)

## Paint vs. Color in the API

| Takes | Where |
|---|---|
| `Paint` | Styling background/hover/border colors, text colors, placeholder color, window background |
| `Color` only | Caret color, selection color, scrollbar colors, slider/progress/spinner/checkbox/radio parts, tooltip colors, `Renderer.drawQuad/drawBorder/drawLine/drawCircle` |

If a setter's parameter type is `Color`, a gradient is not accepted there.
