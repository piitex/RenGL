# Writing custom elements

[Documentation](../README.md) › [Reference](README.md)

Everything on screen is an [`Element`](../core/element.md). To draw something the built-in widgets do not cover,
extend [`Overlay`](../core/overlay.md) (or [`Element`](../core/element.md) directly, or [`Container`](../containers/container.md) if it holds children), override the frame hooks, and use the
`Renderer`.

## The minimum

```java
public class Dot extends Overlay {
    private final Color color;

    public Dot(double size, Color color) {
        setWidth(size);
        setHeight(size);
        this.color = color;
        // A fresh Element is transparent, so only the shape drawn below shows.
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer); // background, border, then your onRender hook
        float r = (float) (getWidth() / 2);
        renderer.drawCircle((float) getX() + r, (float) getY() + r, r, color);
    }
}
```

Add it like any other widget: `layout.addElement(new Dot(16, Color.RED))`.

**Always call `super.render(renderer)` first** so the styling, an attached [`Background`](../styling/backgrounds.md), and the user's `onRender`
hook still run. Then draw your own content on top.

## The frame hooks

| Method | When | Do |
|---|---|---|
| `render(Renderer)` | Every frame, if enabled | Draw. Do not change state here |
| `update(float delta)` | Every frame, if enabled | Advance animation and state. Call `super.update(delta)` first so animated paints, backgrounds, transitions and `onUpdate` still run |
| `renderWithTransitions(Renderer)` | The engine's entry point | Applies opacity and transitions around `render`. Do not override it |

`render` must be cheap and allocation-light: it runs every frame. Never call `Renderer` methods outside `render`,
`onRender` and `Transition.render`. They are only valid on the render thread between `begin()` and `end()`.

## The Renderer

All coordinates are logical points, top-left origin.

| Method | Draws |
|---|---|
| `drawQuad(x, y, w, h, Color, cornerRadius)` | Filled (rounded) rectangle |
| `drawBorder(x, y, w, h, Color, thickness, cornerRadius)` | Stroked outline |
| `drawLine(x0, y0, x1, y1, Color, thickness)` | Line segment |
| `drawPolyline(float[] points, pointCount, Color, thickness)` | Connected open line through packed `x0, y0, x1, y1, ...` points, with round joins and caps (no notches or doubled alpha at the joints) |
| `drawCircle(x, y, radius, Color)` | Filled circle centered at (x, y) |
| `drawText(text, x, y, Font, Paint)` | One line of text; (x, y) is its top-left |
| `drawGradient(x, y, w, h, LinearGradient, cornerRadius)` | Gradient-filled rectangle. Pass the *original* gradient object so a [`ScrollingGradient`](../styling/colors-and-paints.md#scrolling-animated-gradients) keeps scrolling |
| `drawImage(Image, x, y, w, h, cornerRadius, opacity[, Color tint])` | Scaled image |
| `pushClip(x, y, w, h)` / `popClip()` | Clip subsequent drawing to a rectangle |
| `pushClip(x, y, w, h, cornerRadius)` / `popClip()` | Clip subsequent drawing to a rounded rectangle |
| `pushTranslate(dx, dy)` / `popTranslate()` | Shift subsequent drawing by (dx, dy) |
| `pushScale(scale, pivotX, pivotY)` / `popScale()` | Scale subsequent drawing around (pivotX, pivotY), which stays in place. This is what [`Element.setScale`](../core/element.md#scale) uses |
| `pushOpacityLayer(x, y, w, h, opacity)` / `popOpacityLayer()` | Composite everything drawn in between as one unit at that opacity |

Rules:

- A `null` color is a **no-op**, not an error, so you do not need to null-check before drawing.
- Every `pushClip` needs exactly one `popClip`, every `pushOpacityLayer` exactly one `popOpacityLayer`, and the same
  for `pushTranslate`/`popTranslate` and `pushScale`/`popScale`. Pushes nest. An unmatched push corrupts the rest
  of the frame.
- `drawQuad`/`drawBorder`/`drawLine`/`drawCircle` take a solid [`Color`](../styling/colors-and-paints.md#color). `drawText`, `drawGradient` and
  `drawGradientBorder` take gradients. `drawQuad` and `drawBorder` also have overloads that take any `Paint` and pick
  the right call, which is handy for drawing a styling color that may be either.

## Drawing text

Text needs a Skia `Font`. Build it from the cached `Typeface`, keep it as a field, and close it when done. Do not
create one per frame.

The engine's own font cache is also called `Font` (`me.piitex.engine.ui.text.Font`), so only one of the two can hold
the simple name. The engine keeps its own, as it does internally, and writes Skia's out in full.

```java
import me.piitex.engine.ui.text.Font;

public class Label extends Overlay {
    private final String text;
    private org.jetbrains.skia.Font font;

    public Label(String text) {
        this.text = text;
        font = new org.jetbrains.skia.Font(Font.getDefault(), 16f);
        setWidth(font.measureTextWidth(text));
        setHeight(font.getMetrics().getHeight());
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);
        renderer.drawText(text, (float) getX(), (float) getY(), font, Color.BLACK);
    }

    public void dispose() {
        font.close(); // the shared Typeface is not closed
    }
}
```

For most text needs, use or extend [`TextOverlay`](../overlays/text-overlay.md), which already handles fonts, sizing and theming.

## Handling input

- **Clicks:** `onMouseClick(...)`. Fine for a custom widget that is not going to be subclassed. Or override behavior
  by registering it in your constructor.
- **Hover:** read `isHovered()` in `render`.
- **Cursor:** `setCursorMode(CursorMode.POINTER)`.
- **Dragging, scrolling and keyboard:** implement [`Draggable`](../input/input-and-focus.md#dragging), [`Scrollable`](../input/input-and-focus.md#scrolling) and [`Focusable`](../input/input-and-focus.md#keyboard-focus). See
  [Input and focus](../input/input-and-focus.md).
- **Font scaling:** implement `FontScalable` (`getFontSizes()` / `setFontSizes(float[])`, one entry per font the element draws with) so a [`ResizableContainer`](../containers/resizable-container.md) can scale its text.

## Coordinates in a custom element

- **Draw at your own `getX()`/`getY()`.** A container translates the canvas to its own position before drawing its
  children, so those relative values are already in the right space. Do not add the parent's position.
- **Mouse positions are window coordinates**: the arguments of `onDragStart`/`onDragUpdate`, `MouseClickEvent`, and
  `MouseTracker`. To compare one with your own geometry (a thumb, a caret position), convert it with
  `toLocalX(x)`/`toLocalY(y)`, or use `getAbsoluteX()`/`getAbsoluteY()` in your maths. `contains(px, py)` also takes a
  window point.
- A floating popup you show with `Window.showPopup` is a top-level container, so position it in window
  coordinates: use `getAbsoluteX()`/`getAbsoluteY()` of the element it belongs to. Pass that element as the
  [anchor](../core/window.md#following-an-element) (`showPopup(popup, this, onDismiss)`) so the popup moves with it.

## A complete example: a toggle switch

An animated on/off switch with a themed look, a callback, and a smooth thumb slide driven by delta time.

```java
public class ToggleSwitchOverlay extends Overlay implements Themeable {
    private boolean on;
    private float knob; // 0 = left (off), 1 = right (on); animated
    private Consumer<Boolean> onToggle;

    private final Overridable<Color> offColor = new Overridable<>(Color.LIGHT_GRAY);
    private final Overridable<Color> onColor = new Overridable<>(Color.BLUE);

    public ToggleSwitchOverlay() {
        setWidth(44);
        setHeight(24);
        setCursorMode(CursorMode.POINTER);
        setFocusTraversable(false);

        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);

        onMouseClick(event -> toggle());
        ThemeManager.register(this); // last: applies the current theme
    }

    public boolean isOn() {
        return on;
    }

    /** Silent, like every value setter in the toolkit. */
    public void setOn(boolean on) {
        this.on = on;
        this.knob = on ? 1f : 0f;
    }

    public void toggle() {
        on = !on;
        if (onToggle != null) onToggle.accept(on);
    }

    public void onToggle(Consumer<Boolean> onToggle) {
        this.onToggle = onToggle;
    }

    public void setOnColor(Color color) {
        onColor.set(color); // pins it against theme switches
    }

    @Override
    public void applyTheme(Theme theme) {
        offColor.applyTheme(theme.getScrollTrack());
        onColor.applyTheme(theme.getButtonBorder());
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        float target = on ? 1f : 0f;
        // Move toward the target at 8 "units" per second, frame-rate independent.
        float step = 8f * delta;
        knob = knob < target ? Math.min(target, knob + step) : Math.max(target, knob - step);
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);

        float x = (float) getX(), y = (float) getY();
        float w = (float) getWidth(), h = (float) getHeight();

        Color track = on ? onColor.get() : offColor.get();
        renderer.drawQuad(x, y, w, h, track, h / 2f);

        float pad = 3f;
        float r = h / 2f - pad;
        float cx = x + pad + r + knob * (w - 2 * (pad + r));
        renderer.drawCircle(cx, y + h / 2f, r, Color.WHITE);
    }
}
```

Use it like any other widget:

```java
ToggleSwitchOverlay wifi = new ToggleSwitchOverlay();
wifi.onToggle(enabled -> System.out.println("wifi: " + enabled));
wifi.setTooltip("Toggle wifi");
layout.addElement(wifi);
```

Things this example does right, and worth copying:

1. It keeps its own box transparent, even under a theme, since it draws its own shape.
2. It seeds the styling with `applyThemeXxx`, and stores themed colors in [`Overridable`](../styling/theming.md#the-pinning-rule), so a public setter
   pins and a theme switch respects the pin.
3. It registers with [`ThemeManager`](../styling/theming.md) **last** in the constructor, after its fields exist.
4. Its value setter is silent, and only a user click fires the callback.
5. Animation uses `update(delta)`, not the frame count.

## Custom containers

Extend [`Container`](../containers/container.md) (or [`Layout`](../layouts/README.md) for automatic positioning) when your element has children. For a layout, implement
`layoutChildren()`:

```java
public class DiagonalLayout extends Layout {
    public DiagonalLayout(double x, double y, double width, double height) {
        super(x, y, width, height);
    }

    @Override
    protected void layoutChildren() {
        double offset = getPadding();
        for (Element child : getElements().values()) {
            child.setX(getX() + offset);
            child.setY(getY() + offset);
            offset += 20 + getSpacing();
        }
    }
}
```

`Layout` calls `layoutChildren()` on every `addElement` and once per frame in `update`.

## Checklist

- [ ] Call `super.render(renderer)` / `super.update(delta)`.
- [ ] Keep the element's own box transparent (the default) if you draw the whole shape yourself.
- [ ] Do not allocate in `render` (fonts, lists, paints). Cache them.
- [ ] Do not change state in `render`.
- [ ] Register with [`ThemeManager`](../styling/theming.md) at the end of the constructor if the widget is themed, and use [`Overridable`](../styling/theming.md#the-pinning-rule).
- [ ] Keep value setters silent and let only user actions fire callbacks.
- [ ] Give any native resource (a `Font`) a `dispose()` method.
- [ ] Do not replace a built-in widget's click handler from a subclass. Extend behavior in overridable methods.
