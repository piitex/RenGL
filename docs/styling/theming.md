# Theming

[Documentation](../README.md) › [Styling](README.md)

The theme system lets you switch the whole UI between looks (light, dark, or your own) at runtime.

| Type | Role |
|---|---|
| `Theme` | An interface of design tokens (colors, corner radius, border thickness) |
| `BaseTheme` | A mutable `Theme` implementation. Defaults are the light theme |
| `LightTheme`, `DarkTheme`, `CrimsonTheme` | The built-in themes (`CrimsonTheme` is black and red) |
| `ThemeManager` | The global registry. `setCurrent(theme)` re-styles everything on screen |
| `Themeable` | Implemented by anything that takes its look from a theme |
| `Overridable<T>` | The "pinning" primitive behind every themed property |

All are in `me.piitex.engine.ui.theme`.

## Switching themes

```java
ThemeManager.setCurrent(new DarkTheme()); // everything registered restyles immediately
ThemeManager.setCurrent(new LightTheme());
ThemeManager.setCurrent(null); // resets to a fresh LightTheme

Theme active = ThemeManager.getCurrent();
```

Call it before building your UI to start dark, or any time afterwards (for example from a button) to switch live.

```java
ButtonOverlay toggle = new ButtonOverlay("Toggle theme", 150, 36);
toggle.onAction(() -> {
    boolean dark = ThemeManager.getCurrent().getButtonBackground().getR() < 0.5f;
    ThemeManager.setCurrent(dark ? new LightTheme() : new DarkTheme());
});
```

## What follows the theme

- **Widgets** register themselves in their constructor: [`TextOverlay`](../overlays/text-overlay.md), [`ButtonOverlay`](../overlays/button-overlay.md), [`TextFieldOverlay`](../overlays/text-field-overlay.md),
  [`TextAreaOverlay`](../overlays/text-area-overlay.md), [`CheckBoxOverlay`](../overlays/checkbox-overlay.md), [`RadioButtonOverlay`](../overlays/radio-button-overlay.md), [`DropdownOverlay`](../overlays/dropdown-overlay.md), [`SliderOverlay`](../overlays/slider-overlay.md),
  [`ProgressBarOverlay`](../overlays/progress-bar-overlay.md), [`LoadingSpinnerOverlay`](../overlays/loading-spinner-overlay.md), [`SeparatorOverlay`](../overlays/separator-overlay.md), [`SpinnerOverlay`](../overlays/spinner-overlay.md) (through its parts),
  and the divider in [`SplitContainer`](../containers/split-container.md). [`ImageOverlay`](../overlays/image-overlay.md) and [`IconOverlay`](../overlays/icon-overlay.md) do not.
- **[`Window`](../core/window.md)** registers itself, so its background follows `Theme.getWindowBackground()`.
- **Containers do not register on their own.** Opt in on the ones that should look like themed panels:

  ```java
  panel.useTheme();
  ```

  Most containers only group children and stay transparent or whatever you set. Layouts, [`ScrollContainer`](../containers/scroll-container.md)
  (which also themes its scrollbar) and [`ModalContainer`](../containers/modal-container.md) panels are containers, so the same rule applies.
- Tooltips read the tooltip tokens from the current theme every time they draw.

## The pinning rule

> **A theme is a baseline, never the champion.** Anything you set explicitly is pinned and a theme switch
> will not overwrite it.

Each themed property is an `Overridable`:

- `set(...)`, which is what every public setter calls, **always applies and pins** the value.
- `applyTheme(...)`, which is what the theme system calls, applies only if nothing has pinned the value.

```java
ButtonOverlay a = new ButtonOverlay("A", 100, 30);
ButtonOverlay b = new ButtonOverlay("B", 100, 30);

b.getStyling().setBackgroundColor(Color.RED); // pinned

ThemeManager.setCurrent(new DarkTheme());
// a: dark button background (followed the theme)
// b: still red (pinned)
```

Practical consequences:

1. **Constructor colors are baselines.** `new TextOverlay("x", 16f, Color.RED)` is themed to the theme's text
   color immediately. Call `setTextColor(Color.RED)` to pin red.
2. **Call `useTheme()` before customizing.** A property you customize afterwards is pinned. That is the intent.
3. **Built-in elements have no unpin.** To make an element follow the theme again, use a new element. In your own
   widgets, `Overridable.clearExplicit()` releases a pin: the property follows the theme again from the next
   `applyTheme`, and keeps its current value until then.
4. **The window background follows the same rule.** `Window` registers itself, so its background tracks
   `Theme.getWindowBackground()` by default, and `Window.setBackgroundColor` pins it just like any other themed
   property. A pinned window background survives later theme switches. `Window.followThemeBackground()` unpins it
   and applies the current theme at once.

## The tokens

Every token is a getter on `Theme`. A widget picks the tokens it needs.

| Token | Used for |
|---|---|
| `getText()` | Text and labels of themed widgets, checkmark and radio dot |
| `getFieldBackground()` | Text field/area, dropdown, checkbox and radio box background |
| `getFieldHoverBackground()` | Same, while hovered |
| `getFieldBorder()` | Same, border; also slider ticks |
| `getPlaceholderText()` | Placeholder and prefix text, slider tick labels |
| `getCaret()` | Text caret |
| `getSelection()` | Text selection highlight, selected dropdown row |
| `getButtonBackground()` / `getButtonHoverBackground()` / `getButtonBorder()` | [`ButtonOverlay`](../overlays/button-overlay.md) |
| `getContainerBackground()` / `getContainerBorder()` | A container that called `useTheme()`; separator lines |
| `getScrollThumb()` / `getScrollTrack()` | Scrollbars; slider and progress fill/track; split divider; spinner dots |
| `getWindowBackground()` ([`Paint`](colors-and-paints.md)) | The window backdrop. May be a gradient |
| `getCornerRadius()` | Box rounding for themed controls (default `6`) |
| `getBorderThickness()` | Box border width for themed controls (default `1`) |
| `getTooltipBackground()` / `getTooltipBorder()` / `getTooltipText()` | Tooltip bubble |

## Making your own theme

Extend `BaseTheme` and set only the tokens you want to differ from the light defaults. That is how `DarkTheme`
is built.

```java
public class OceanTheme extends BaseTheme {
    public OceanTheme() {
        setText(Color.WHITE);
        setWindowBackground(new LinearGradient(new Color(0.02f, 0.10f, 0.22f, 1f), new Color(0.0f, 0.02f, 0.08f, 1f)));
        setContainerBackground(new Color(0.05f, 0.16f, 0.30f, 1f));
        setContainerBorder(new Color(0.20f, 0.45f, 0.70f, 1f));
        setFieldBackground(new Color(0.08f, 0.22f, 0.40f, 1f));
        setFieldHoverBackground(new Color(0.11f, 0.28f, 0.48f, 1f));
        setFieldBorder(new Color(0.25f, 0.55f, 0.85f, 1f));
        setButtonBackground(new Color(0.15f, 0.45f, 0.80f, 1f));
        setButtonHoverBackground(new Color(0.20f, 0.55f, 0.92f, 1f));
        setButtonBorder(new Color(0.40f, 0.70f, 1f, 1f));
        setCaret(Color.WHITE);
        setCornerRadius(10f);
    }
}
```

```java
ThemeManager.setCurrent(new OceanTheme());
```

You can also tweak an instance without subclassing:

```java
BaseTheme compact = new BaseTheme();
compact.setCornerRadius(2f);
compact.setBorderThickness(2f);
ThemeManager.setCurrent(compact);
```

`Theme` is a pure read interface. If you need tokens computed at runtime (from a config file, the OS, time of
day), implement `Theme` directly instead of extending `BaseTheme`.

## Making your own widget themeable

Implement `Themeable`, route every themed property through `Overridable` (or [`Styling`](styling.md)'s `applyThemeXxx`), and
register **at the end of your constructor**, after your fields exist:

```java
public class Badge extends Overlay implements Themeable {
    private final Overridable<Color> accent = new Overridable<>(Color.BLUE);

    public Badge(double width, double height) {
        setWidth(width);
        setHeight(height);
        ThemeManager.register(this); // applies the current theme immediately, so do this last
    }

    public void setAccent(Color color) {
        accent.set(color); // explicit: pins it
    }

    @Override
    public void applyTheme(Theme theme) {
        getStyling().applyThemeBackgroundColor(theme.getButtonBackground()); // baseline, not pinned
        accent.applyTheme(theme.getSelection()); // baseline, not pinned
    }

    @Override
    public void render(Renderer renderer) {
        super.render(renderer);
        renderer.drawCircle((float) (getX() + getWidth() / 2), (float) (getY() + getHeight() / 2),
                (float) Math.min(getWidth(), getHeight()) / 4f, accent.get());
    }
}
```

**Do not** call the public setters (for example `getStyling().setBackgroundColor`) from inside `applyTheme`, or from a
constructor that seeds a default. That would pin the value and permanently block the theme. Use the `applyThemeXxx`
methods.

**Beware subclassing a themed widget and adding fields.** [`TextOverlay`](../overlays/text-overlay.md)'s constructor registers `this`, and the
theme is applied immediately, calling your overridden `applyTheme` *before* your subclass's own fields have been
initialized. Guard against `null` fields inside `applyTheme`, or extend [`Overlay`](../core/overlay.md) as above.

`ThemeManager` holds only weak references. There is no unregister, and an unreferenced element is collected
normally.

## Method reference

| Class | Members |
|---|---|
| `ThemeManager` | `getCurrent()`, `setCurrent(Theme)`, `register(Themeable)` |
| `Themeable` | `applyTheme(Theme)` |
| `Overridable<T>` | `get()`, `set(T)` (pins), `applyTheme(T)` (respects pin), `clearExplicit()` (unpins), `isExplicit()` |
| [`Container`](../containers/container.md) | `useTheme()` |
