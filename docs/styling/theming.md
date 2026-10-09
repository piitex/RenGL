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
  `panel.useTheme(false)` goes back: the container returns to the plain look it had before and later theme changes pass it
  by, and `panel.isUsingTheme()` says which it is. In a [layout file](../containers/layout-files.md) this is the `panel`
  property, `"panel": true`.
- A themed panel takes the theme's panel **effect** too (`Theme.getContainerEffect()`), each panel making its own.
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
3. **Pins can be undone.** `element.followTheme()` releases every pin on an element, its `Styling` and its own
   colors (text, fill, caret, ...), and applies the current theme at once. `getStyling().resetBackgroundColor()`
   and the other `resetXxx` methods undo one property. In your own widgets, `Overridable.clearExplicit()` releases
   a pin and puts back the last value the theme gave. Override `followTheme()` to clear your own properties, and
   call `super.followTheme()`.
4. **The window background follows the same rule.** `Window` registers itself, so its background tracks
   `Theme.getWindowBackground()` by default, and `Window.setBackgroundColor` pins it just like any other themed
   property. A pinned window background survives later theme switches. `Window.followThemeBackground()` unpins it
   and applies the current theme at once.

### Restyling a hand-styled element

A color given through a setter is pinned, so a theme switch leaves it alone. For an element styled by hand (a badge,
a toolbar band, a colored label), `element.onTheme(callback)` runs the callback now and again on every
`ThemeManager.setCurrent`, so it sets its colors for the new theme and nothing needs rebuilding:

```java nocompile
badge.onTheme(theme -> {
    badge.getStyling().setBackgroundColor(theme.getAccentTint());
    badge.setTextColor(theme.getText());
});
```

Callbacks run in the order they were added, live as long as the element does, and do not stop the element's own themed
look. For a plain panel, [`useTheme()`](../containers/container.md) already does this with the container tokens.

## The tokens

Every token is a getter on `Theme`. A widget picks the tokens it needs.

| Token | Used for |
|---|---|
| `getText()` | Text and labels of themed widgets |
| `getFieldBackground()` | Text field/area, dropdown, checkbox and radio box background |
| `getFieldHoverBackground()` | Same, while hovered |
| `getFieldBorder()` | Same, border; also slider ticks |
| `getPlaceholderText()` | Placeholder and prefix text, slider tick labels |
| `getCaret()` | Text caret |
| `getSelection()` | Text selection highlight, selected dropdown row |
| `getAccent()` | The app's accent: the fill and thumb of a slider, a progress bar's fill, a checkbox's tick, a radio's dot, the on side of a toggle |
| `getAccentTint()` | The accent as a faint wash: the selected row of a [`TreeView`](../containers/tree-view.md) and a [`TableView`](../containers/table-view.md) |
| `getButtonBackground()` / `getButtonHoverBackground()` / `getButtonBorder()` | [`ButtonOverlay`](../overlays/button-overlay.md) |
| `getContainerBackground()` ([`Paint`](colors-and-paints.md)) / `getContainerBorder()` | A container that called `useTheme()`; separator lines. The background may be a gradient |
| `getContainerEffect()` | An [effect](backgrounds.md#backgrounds-in-files) behind a themed container, as settings (a `Map`), or `null` |
| `getScrollThumb()` / `getScrollTrack()` | Scrollbars; slider and progress track; split divider; spinner dots |
| `getWindowBackground()` ([`Paint`](colors-and-paints.md)) | The window backdrop. May be a gradient, a scrolling one or a rainbow |
| `getWindowEffect()` | An [effect](backgrounds.md#backgrounds-in-files) drawn over the backdrop, as settings (a `Map`), or `null` |
| `getCornerRadius()` | Box rounding for themed controls (default `6`) |
| `getBorderThickness()` | Box border width for themed controls (default `1`) |
| `getTooltipBackground()` / `getTooltipBorder()` / `getTooltipText()` ([`Paint`](colors-and-paints.md)) | Tooltip bubble. Each may be a gradient, a scrolling one or a rainbow |

### The accent

`getAccent()` is one place to set the color of everything that shows "on", "filled" or "chosen". Change it and the
sliders, progress bars, check marks, radio dots, toggles and the selected row of a tree or a table all follow, unless
one was given a color of its own with its setter (that one stays).

```java
BaseTheme brand = new DarkTheme();
brand.setAccent(new Color(0.20f, 0.70f, 0.35f, 1f));      // slider fill, progress, tick, dot, toggle
brand.setAccentTint(new Color(0.20f, 0.70f, 0.35f, 0.25f)); // the selected row
ThemeManager.setCurrent(brand);
```

The light theme's accent is a medium blue and the dark theme's a lighter one; the crimson theme's is its red. A `Theme`
written before the accent existed does not need to change: `getAccent()` and `getAccentTint()` are default methods that
give the selection color, at full opacity for the accent.

In a theme file they are `"accent"` and `"accentTint"`. To use the accent in a widget of your own, apply
`theme.getAccent()` as its baseline in `applyTheme`, as the example below does.

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

## Effects and gradients in a theme

The window and panels can have more than a flat color. `setWindowBackground` and `setContainerBackground` take any
[`Paint`](colors-and-paints.md): a gradient, a scrolling one, a rainbow. `setWindowEffect` and `setContainerEffect` take the
settings of a [background effect](backgrounds.md#backgrounds-in-files), which is a `Map` so a theme stays plain data:

```java
BaseTheme night = new DarkTheme();
night.setWindowBackground(new ScrollingGradient(
        List.of(new Color(0.04f, 0.04f, 0.12f, 1f), new Color(0.16f, 0.06f, 0.28f, 1f)), 90f, 40f));
night.setWindowEffect(Map.of("type", "constellation", "particleCount", 90));
night.setContainerBackground(new LinearGradient(new Color(0.12f, 0.12f, 0.2f, 1f), new Color(0.08f, 0.08f, 0.14f, 1f), 90f));
ThemeManager.setCurrent(night);
```

An effect is made for each window or panel from the settings, as it has state of its own, and a later `setCurrent` changes
the one that is showing instead of starting it over. An effect an app sets itself (`window.setBackground`,
`element.setBackground`) is pinned and a theme leaves it alone. A picture an effect names is looked for in
`Theme.assetBase()`: the working folder, or for `ThemeFile.load` the folder of the file.

A theme that implements `Theme` itself gets `null` effects and the working folder from the defaults.

## Theme files

`ThemeFile` reads and writes a theme as JSON, so an app's look is data. `ThemeManager.setCurrent(ThemeFile.load("theme.json"))`
before the window is made is all an app needs. The [RenGL Editor](../../README.md) edits this file.

```json
{
  "format": 1,
  "base": "dark",
  "buttonBackground": "#9E1221",
  "cornerRadius": 4,
  "windowBackground": { "type": "gradient", "colors": ["#101020", "#2A1040"], "angle": 90, "scroll": 30 },
  "windowEffect": { "type": "constellation", "particleCount": 90 },
  "containerBackground": "#1B1B24"
}
```

A file need only say what differs from its `base`, `"light"`, `"dark"` or `"crimson"`. Colors are `"#RRGGBB"`, `"#RRGGBBAA"` or
`[r, g, b, a]`. `windowBackground`, `containerBackground`, `tooltipBackground`, `tooltipBorder` and `tooltipText` are [paints](colors-and-paints.md#paints-in-files); `windowEffect`
and `containerEffect` are effect settings, or `null` for none. `ThemeFile.save` writes every setting, so a saved file stands
on its own, and `ThemeFile.tokens()` lists the settings with their labels, groups, types and a tooltip that says what each changes, for a tool.

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
        accent.applyTheme(theme.getAccent()); // baseline, not pinned
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

`ThemeManager` holds only weak references, so an unreferenced element is collected normally. `ThemeManager.unregister`
stops a themeable being re-themed, which is what `Container.useTheme(false)` does.

## Method reference

| Class | Members |
|---|---|
| `ThemeManager` | `getCurrent()`, `setCurrent(Theme)`, `register(Themeable)`, `unregister(Themeable)`, `using(Theme, Supplier)` |
| `Themeable` | `applyTheme(Theme)` |
| `Overridable<T>` | `get()`, `set(T)` (pins), `applyTheme(T)` (respects pin), `clearExplicit()` (unpins), `isExplicit()` |
| [`Container`](../containers/container.md) | `useTheme()`, `useTheme(boolean)`, `isUsingTheme()` |
| [`Element`](../core/element.md) | `applyThemeBackground`, `applyThemeEffect`, `resetBackground`, `isBackgroundSet` |
| `ThemeFile` | `load`, `parse`, `save`, `toJson`, `tokens()`, `builtIn(id)`, `builtIns()`, `copy(Theme)` |
