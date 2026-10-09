# RadioButtonOverlay and RadioGroup

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.RadioButtonOverlay` is a round selector that is **mutually exclusive within a
`RadioGroup`**. Like the checkbox it draws no label, so pair each button with a [`TextOverlay`](text-overlay.md).

`RadioGroup` is not an element. It only coordinates which member is selected, so you never add it to a
container.

```java
RadioGroup plan = new RadioGroup();
plan.onChange(button -> System.out.println("selected " + button.getId()));

RadioButtonOverlay free = new RadioButtonOverlay(16);
RadioButtonOverlay pro = new RadioButtonOverlay(16);
plan.add(free);
plan.add(pro);
```

Exclusivity only exists inside a group. A button that belongs to no group just selects itself when clicked, and
only `setSelected(false)` deselects it.

## Constructors

| Constructor | Result |
|---|---|
| `RadioButtonOverlay()` | Unselected 18 × 18 circle |
| `RadioButtonOverlay(double size)` | Unselected `size` × `size` circle |

The corner radius is `size / 2` (that is what makes it a circle), so do not change it. `followTheme()` keeps it.

## Selecting

```java
button.setSelected(true); // silent: deselects the others but does NOT fire onChange
plan.setSelected(pro); // the same from the group; null clears the selection
button.select(); // what a click runs: selects it and fires the group's onChange
boolean chosen = button.isSelected();
RadioGroup g = button.getGroup(); // null if not in a group
```

Use `setSelected` to set a default or to sync the buttons to your model, and only user choices reach `onChange`.
`button.setSelected(false)` on the group's selected member leaves the group with no selection.
`group.setSelected(b)` ignores a button that is not a member.

## RadioGroup

| Method | Description |
|---|---|
| `add(RadioButtonOverlay)` | Adds a member and links it back to the group (no-op if already a member). A button in another group leaves it first. A selected button becomes the selection if the group has none, and is deselected otherwise |
| `remove(RadioButtonOverlay)` | Removes it. It becomes ungrouped. If it was selected, the group has no selection |
| `getSelected()` | The selected member, or `null` |
| `setSelected(RadioButtonOverlay)` | Silent select; `null` clears. Ignores non-members |
| `getButtons()` | Members in the order they were added |
| `onChange(Consumer<RadioButtonOverlay>)` | Fired with the newly selected button when a click or `select()` changes the selection |

`onChange` gives you the *button*, which carries no label, so keep your own mapping from button to meaning:

```java
Map<RadioButtonOverlay, String> names = new HashMap<>();
RadioGroup group = new RadioGroup();
group.onChange(b -> System.out.println("Plan: " + names.get(b)));
```

## Look

The ring uses the same field tokens as a checkbox. The inner dot uses the theme's [accent](../styling/theming.md#the-accent).

```java
button.setDotColor(Color.BLUE); // pins the dot color
button.getStyling().setBorderColor(Color.BLUE);
```

> **Do not call `onMouseClick` on a radio button.** The constructor registers an internal handler that selects
> it.

## Example: labeled options

```java
static HorizontalLayout option(RadioGroup group, Map<RadioButtonOverlay, String> names, String label) {
    HorizontalLayout row = new HorizontalLayout(150, 20);
    row.setSpacing(8);
    row.setAlignment(Layout.Alignment.CENTER_LEFT);

    RadioButtonOverlay button = new RadioButtonOverlay(16);
    group.add(button);
    names.put(button, label);
    row.addElement(button);
    row.addElement(new TextOverlay(label, 14f, Color.BLACK));
    return row;
}
```

and then

```java
Map<RadioButtonOverlay, String> names = new HashMap<>();
RadioGroup group = new RadioGroup();

VerticalLayout options = new VerticalLayout(20, 20, 160, 90);
options.setSpacing(6);
options.addElement(option(group, names, "Free"));
options.addElement(option(group, names, "Pro"));
options.addElement(option(group, names, "Enterprise"));

group.onChange(b -> System.out.println("Plan: " + names.get(b)));
root.addElement(options);
```

## Method reference

| Method | Description |
|---|---|
| `select()` | Select this button (fires group `onChange`) |
| `setSelected(boolean)` | Silent select or deselect |
| `isSelected()` | Whether it is the selected one |
| `getGroup()` | Its `RadioGroup`, or `null` |
| `getDotColor()` / `setDotColor(Paint)` | Inner dot color or gradient; setting pins it |
