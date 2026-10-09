# Colors and paints

[Documentation](../README.md) › [Styling](README.md)

Anywhere the API draws a fill, it accepts a **`Paint`**. `Paint` is a marker interface with three families of
implementations:

```text
Paint
 ├─ Color                       a solid RGBA color
 │   └─ RainbowColor            a Color that cycles through the colour wheel ("RGB")
 ├─ LinearGradient              two colors blended across the element
 │   └─ ScrollingGradient       a gradient that slides over time (also an AnimatedPaint)
 └─ AnimatedPaint               a Paint with per-frame state (interface)
```

## Color

`me.piitex.engine.ui.color.Color` is an immutable RGBA color with float components in `[0, 1]` (except the
[`RainbowColor`](#rainbowcolor-rgb-cycling) subclass, which changes over time).

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

From hue, saturation and brightness (hue is in turns and wraps: 0 red, 1/3 green, 2/3 blue):

```java
Color orange = Color.fromHSV(0.08f, 1f, 1f, 1f);
```

The same color, more or less see-through:

```java
Color half = orange.withAlpha(0.5f);      // alpha set to 0.5
Color fading = half.withOpacity(0.5f);    // alpha multiplied by 0.5, so 0.25 here
```

`withOpacity` is for fading something in or out while keeping how see-through it already was. To fade everything
drawn for a while, [`Renderer.pushAlpha`](../reference/custom-elements.md#the-renderer) saves passing the opacity to
every color.

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

### RainbowColor (RGB cycling)

`RainbowColor` is a `Color` subclass whose hue cycles around the colour wheel by itself. Because it **is** a `Color`,
it works everywhere a color is accepted. That includes backgrounds, borders, text, the parts in the table
below, background effects, and direct `Renderer` calls:

```java
RainbowColor rgb = new RainbowColor(); // full cycle every 6 seconds
title.setTextColor(rgb);
panel.getStyling().setBorderColor(rgb);

ElectricNodeBackground electric = new ElectricNodeBackground();
electric.setGlowColor(rgb);
electric.setCoreColor(new RainbowColor(6f, 0.15f, 1f, 1f)); // pale version, same hue
```

Share one instance freely. Unlike `ScrollingGradient`, it is driven by a clock rather than `advance()`, so using it
on many elements does not make it cycle faster. All `RainbowColor`s run off the same start time, so two with the
same cycle duration and hue offset always show the same colour, even if they were created at different times.

| Constructor / setter | Default | Effect |
|---|---|---|
| `new RainbowColor()` | | Full saturation and brightness, opaque, 6 s cycle |
| `new RainbowColor(cycleSeconds)` | | Same, with your own cycle length |
| `new RainbowColor(cycleSeconds, saturation, brightness, alpha)` | | Everything at once |
| `setCycleDuration(float)` | 6 | Seconds per trip around the wheel. Negative runs backwards, 0 freezes. Changing it never jumps |
| `setSaturation(float)` | 1 | [0, 1]. Lower is pastel |
| `setBrightness(float)` | 1 | [0, 1] |
| `setAlpha(float)` | 1 | [0, 1]; what `getA()` returns |
| `setHueOffset(float)` | 0 | Fixed shift in turns: 0.5 is the complementary colour, 1/3 a triad |
| `getHue()` | | Current hue in turns, [0, 1) |
| `snapshot()` | | A plain, frozen `Color` of the current value |

Code that copies the channels once, for example `new Color(rgb.getR(), ...)` at setup time, gets a fixed colour.
Pass the `RainbowColor` itself wherever you want it to keep cycling.

## Gradients

```java
LinearGradient gradient = new LinearGradient(Color.BLUE, Color.GREEN);
LinearGradient down = new LinearGradient(Color.BLUE, Color.GREEN, 90f); // top to bottom
LinearGradient three = new LinearGradient(List.of(Color.RED, Color.YELLOW, Color.GREEN), 0f); // left to right
```

A `LinearGradient` blends from its first color to its last, through any others spaced evenly, along a line.

- **No angle** (`new LinearGradient(start, end)`) runs corner to corner, from the top-left to the bottom-right of a
  box, and across the text's own bounds for text. This is how a gradient has always drawn.
- **An angle**, in degrees, sets the direction: 0 is left to right, 90 top to bottom, 180 right to left and 270 bottom
  to top. The line goes through the middle of the box and is as long as the box is in that direction, so the first color
  is exactly at one edge or corner and the last at the opposite one, whatever the box's shape.
  `getAngle()` is `Float.NaN` for none, and `hasAngle()` tells them apart.

Where a gradient works:

| Use | Works? |
|---|---|
| Element background and hover color (`styling.setBackgroundColor(gradient)`) | Yes |
| Text color (`TextOverlay.setTextColor`, and other text widgets) | Yes |
| Window background (`window.setBackgroundColor`) | Yes |
| Border color (`styling.setBorderColor(gradient)`) | Yes |
| Caret and selection colors, scrollbar colors, slider, progress bar, checkbox, radio and loading spinner parts | Yes |
| Tooltip background, border and text (theme tokens) | Yes |
| Toggle switch, tab strip, popup menu and menu bar, dropdown, date and color picker text, tree view, separator, modal backdrop, download bar | Yes |
| Dot, constellation and electric-arc backgrounds | Yes |

### Scrolling (animated) gradients

```java
ScrollingGradient flow = new ScrollingGradient(Color.BLUE, Color.GREEN, 50f); // speed in px/second along the gradient axis
ScrollingGradient sideways = new ScrollingGradient(Color.BLUE, Color.GREEN, 0f, 50f); // angle 0, then the speed
ScrollingGradient rainbow = new ScrollingGradient(List.of(Color.RED, Color.GREEN, Color.BLUE), 45f, 80f);

title.setTextColor(flow);
window.setBackgroundColor(new ScrollingGradient(Color.BLUE, Color.BLACK, 500f));
```

The speed is along the gradient's own line, which its angle sets, and a negative speed goes the other way.
The scroll offset is mutable per-instance state, advanced once per frame by the element that owns it. **Give each
element its own instance.** Sharing one instance between two elements makes them scroll in lockstep and advance
the offset twice per frame.

### Paints in files

[Layout files](../containers/layout-files.md) and [theme files](theming.md#theme-files) save a paint with
`PaintCodec`, in these shapes. A solid color is written as a color always was, so older files still read.

```json
"background": "#9E1221"
"background": { "type": "gradient", "colors": ["#9E1221", "#2A0A10"], "angle": 90 }
"background": { "type": "gradient", "colors": ["#101020", "#2A1040", "#101020"], "angle": 0, "scroll": 60 }
"background": { "type": "rainbow", "seconds": 6, "saturation": 0.8, "brightness": 1, "alpha": 1 }
```

`angle` is left out for a corner-to-corner gradient, and `scroll` is the speed of one that moves. A paint of another
kind, such as your own `AnimatedPaint`, can't be written and is left out of a file.

## AnimatedPaint

```java
public interface AnimatedPaint extends Paint {
    void advance(float deltaSeconds);
    default Paint current() { return this; }
}
```

An `AnimatedPaint` is a paint with per-frame state. The engine calls `advance(delta)` once per frame on any
`AnimatedPaint` it finds in an element's styling colors, a text color, or the window background. `ScrollingGradient`
is the built-in one.

The engine advances an `AnimatedPaint` once per element that uses it, so an instance shared by several elements
advances several times per frame. That is why `ScrollingGradient` should not be shared. `RainbowColor` is not an
`AnimatedPaint`: it reads a clock, so sharing it is fine.

The renderer only knows how to draw a `Color` and a `LinearGradient`, so a custom `AnimatedPaint` says which one it
is right now by overriding `current()`. Every fill that takes a paint calls it each time it draws: backgrounds,
borders, text, the window background and the parts of the widgets. The default returns the paint itself, which is
drawn only if it already is a `Color` or a gradient, as a `ScrollingGradient` is.

```java
public class Pulse implements AnimatedPaint {
    private float time;

    @Override
    public void advance(float deltaSeconds) {
        time += deltaSeconds;
    }

    @Override
    public Paint current() {
        float a = 0.5f + 0.5f * (float) Math.sin(time * 3);
        return new Color(1f, 0.3f, 0.3f, a);
    }
}
```

```java
element.getStyling().setBackgroundColor(new Pulse()); // advanced and drawn by the engine
```

Keep `advance` and `current` cheap. They run every frame for every element that uses the paint. `current()` may
return a new object each time, and may return null to draw nothing. `AnimatedPaint.resolve(paint)` does the same
lookup in your own `onRender` code.

## Paint vs. Color in the API

| Takes | Where |
|---|---|
| `Paint` | Styling background/hover/border colors, text colors, placeholder color, window background, caret and selection colors, scrollbar colors, slider, progress bar, checkbox, radio, toggle switch and loading spinner parts, tab strip colors, popup menu and menu bar colors, dropdown, date picker, color picker and tree view text and selection, modal backdrop, separator, download bar fills, the dot, constellation and electric-arc backgrounds, tooltip theme tokens, and the `Paint` overloads of `Renderer.drawQuad/drawBorder/drawLine/drawCircle/drawPolyline` |
| `Color` only | Things that are a color by nature or are tinted, not filled: the value of a `ColorPickerOverlay`, an image or icon `tint`, spelling and grammar underlines, line-number text, progress bar stripes, and 3D materials and lights |

If a setter's parameter type is `Color`, a gradient is not accepted there. A `RainbowColor` is, since it is a `Color`.

How a gradient lies on the parts that take one:

- **A box** (a scrollbar's track and thumb, a slider's track, fill and thumb, a progress bar's fill, a caret, a
  selection) is filled as that box on its own, so the gradient runs across the part, not across the whole control.
  The same goes for the slider's round thumb and a radio's dot, which fill their own square.
- **A line** (the slider's ticks, the check mark) runs along the stroke, from its first end to its second, whatever
  its direction. The gradient's own angle is not used.
- **The loading spinner** runs the gradient across the whole spinner, and each dot takes the color at its place.
  A `ScrollingGradient` is sampled where it starts, so it doesn't move there.
- **A `ScrollingGradient`** on any other of these parts scrolls, advanced every frame by the control that owns it, so
  give each control its own instance.
- **A toggle switch's track** blends its off and on colors as the knob slides. Two plain colors are mixed channel by
  channel. With a gradient, the on paint is drawn over the off paint at the opacity the knob has travelled.
- **A row highlight** (a tree view's hover and selection, a date picker's hover) is the paint drawn at part of its
  strength, so a gradient fades as a whole.
- **A background effect's dots and lines** each take the paint on their own: a gradient in a dot fills that dot's
  square, along a line it runs from end to end. A bolt in an electric-arc background takes a gradient along its whole
  length.
