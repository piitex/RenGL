# Layout files

[Documentation](../README.md) › [Containers](README.md) › Layout files

A **layout file** holds a tree of containers and elements as JSON, so a screen can be designed in the RenGL Editor and
loaded by the app. `UiFile` (in `me.piitex.engine.ui.file`) reads and writes them.

```java
Container ui = UiFile.load("layouts/menu.json");
window.addContainer(ui);
UiFile.find(ui, "play", ButtonOverlay.class).onAction(this::startGame);
```

`load` makes the real containers and elements, so everything else in the engine works on them: themes, transitions,
click hooks. An element is found by the name it has in the file. The first match, looking at the root and then through
what it holds in order, wins. `find` with a class throws if there is no such element or it is another kind.

The root is a container of the size it was designed at. To follow the window, resize it from `Window.onResize`;
elements with an [anchor](../core/element.md#anchoring) keep their place as it changes:

```java
window.onResize((width, height) -> { ui.setWidth(width); ui.setHeight(height); });
```

## The format

```json
{
  "format": 1,
  "root": {
    "kind": "vertical", "name": "Menu", "width": 1280, "height": 720, "spacing": 12, "alignment": "CENTER",
    "children": [
      { "kind": "text", "name": "title", "text": "My Game", "fontSize": 48 },
      { "kind": "button", "name": "play", "text": "Play", "width": 160, "height": 40, "cornerRadius": 6 },
      { "kind": "button", "name": "quit", "text": "Quit", "width": 160, "height": 40,
        "anchor": "BOTTOM_RIGHT", "offsetX": -16, "offsetY": -16 }
    ]
  }
}
```

Each element is a `kind`, a `name` and its properties; a container also has `children`. What is missing takes its
default. A property that follows the theme until it is set, such as `background`, `border`, `borderWidth`,
`cornerRadius` or a text `textColor`, is only in the file when it was set, so a theme change still reaches the rest.
Colors are `[r, g, b, a]` from 0 to 1, or `"#RRGGBB"` / `"#RRGGBBAA"`.

### Styling

Every element has the same style properties, and each kind adds its own:

| Property | Holds |
|---|---|
| `background`, `border` | A [paint](../styling/colors-and-paints.md#paints-in-files): a color, a gradient (`{ "type": "gradient", "colors": [...], "angle": 90 }`), a gradient that moves (add `"scroll": 60`), or a rainbow |
| `hoverBackground` | The background while the mouse is over the element. Buttons, fields, dropdowns, pickers and containers have it |
| `effect` | A [background effect](../styling/backgrounds.md#backgrounds-in-files) behind the element: `{ "type": "constellation", "particleCount": 80 }` or a picture `{ "type": "image", "image": "assets/sky.png" }` |
| `panel` | `true` on a container to take its background, border and corners from the app's [theme](../styling/theming.md), as a panel does. Anything else set on it still wins |
| `borderWidth`, `cornerRadius`, `opacity`, `tooltip` | A number, a number from 0 to 1, text |
| `textColor` | A paint, for text and buttons, fields, areas |
| `textHover`, `textAlign` | A button's text color while hovered, and where text sits in its box (`"CENTER_LEFT"`...), left out until set |

A property that follows the theme is only in the file once it is set, so a `panel` that sets no `background` takes the
theme's, gradient and effect included.

| Kind | Class |
|---|---|
| `container`, `scroll`, `resizable` | `Container`, `ScrollContainer`, `ResizableContainer` |
| `tabs` | `TabContainer`: each child is a tab's content, titled by its `tabTitle` |
| `hsplit`, `vsplit` | `SplitContainer` side by side, or stacked: two children, the panes |
| `vertical`, `horizontal`, `stack`, `grid` | `VerticalLayout`, `HorizontalLayout`, `StackLayout`, `GridLayout` |
| `flow`, `dock` | `FlowLayout`, `DockLayout`: each child of a `dock` has a `dock` edge |
| `text`, `textflow`, `button` | `TextOverlay`, `TextFlowOverlay` (wrapping text), `ButtonOverlay` |
| `image`, `icon` | `ImageOverlay`, `IconOverlay` (an icon is one of the bundled Feather icons, by file name: `"arrow-right"`) |
| `textfield`, `textarea`, `number` | `TextFieldOverlay`, `TextAreaOverlay`, `SpinnerOverlay` |
| `checkbox`, `radio`, `toggle` | `CheckBoxOverlay`, `RadioButtonOverlay`, `ToggleSwitchOverlay` |
| `slider`, `percent`, `dropdown` | `SliderOverlay`, `PercentSliderOverlay`, `DropdownOverlay` |
| `colorpicker`, `datepicker` | `ColorPickerOverlay`, `DateTimePickerOverlay` (`date` as `"2026-10-03"`, `time` as `"14:30"`) |
| `progress`, `spinner`, `separator`, `vseparator` | `ProgressBarOverlay`, `LoadingSpinnerOverlay`, `SeparatorOverlay` |

A few kinds ask something of what is in them or of themselves:

- A child of `tabs` has a `tabTitle` (its name when none is set), and the tabs set its size.
- A `hsplit` or `vsplit` holds at most two children, the first and second pane, and sets their sizes.
- A child of `dock` is docked `TOP` unless it has a `dock` of `BOTTOM`, `LEFT`, `RIGHT` or `FILL`.
- `radio` buttons with the same `group` name choose one between them. After loading, `UiFile.find(ui, "small",
  RadioButtonOverlay.class).getGroup()` is the `RadioGroup`, to listen to with `onChange`.
- A `number` field is one element: the text field and buttons inside it aren't children.

`TreeView`, `ReorderableGrid` and `ModalContainer` are filled and shown from code, so they have no kind.

`UiKinds.get("button").getProperties()` lists every property of a kind, with its type, so an editor can build a field
for each. A kind of your own is added with `UiKinds.register`.

Image paths are relative to the folder `load` is given (`UiFile.load(path, base)`), the working folder for
`load(String)`. An image that can't be read shows nothing, and its path is kept when the file is saved again. A kind
this version doesn't know loads as an empty container that is written back as it was, so opening and saving a file from
a newer version loses nothing.

## Documentation for an editor

Every property of a kind has a tooltip, `UiProperty.getTooltip()`, that says what it does in a sentence. `UiDocs` goes
further: it has the documentation of each kind as Markdown, to show beside an element in an editor.

```java
UiKind button = UiKinds.get("button");
String summary = UiDocs.summary(button);   // "A button the player clicks."
String page = UiDocs.page(button);         // its name, what it is for and how it behaves, then its settings
String every = UiDocs.commonPage();        // what every element has: the place, the size, the style
```

A page has two parts. The first is written by hand and comes with the engine: what the kind is for, how it works and what
to watch for. The second is made from the kind's properties, with each one's label, its saved name, its tooltip and what
it can be set to, so it can't fall out of step with them. The properties every kind has are left out of a kind's page and
explained once on the common page. Show a page with a [`FormattedTextOverlay`](../overlays/formatted-text-overlay.md) and a
`MarkdownFormatPreset`. A kind of your own gets the second part by itself, and the first from
`UiDocs.register("mykind", markdown)`.

A tooltip that is left off a property in `UiKinds` is filled in from a table of what each name means, so the editor can
show one for every property. A test fails when a kind has a property with none.
