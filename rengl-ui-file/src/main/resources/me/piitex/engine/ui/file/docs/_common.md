Everything on a page is an element, whether it is a button or a layout, and every element has these settings. What each kind adds is on its own page.

## Where an element goes
- In a container, an element is placed by hand with X and Y, measured from the container's top left corner.
- In a layout, the layout decides. X and Y are not shown, and they would be overwritten every frame if they were.
- An anchor keeps an element in the same place as its container changes size. Anchor it to a corner or an edge, and the offsets are how far it is from there. Anchors work in a container, not in a layout. To keep a layout in place, anchor the layout.
- A tab container, a split container and a dock layout ask each element they hold for something of its own: a Tab Title, or a Dock edge.

## Size
- An element has a width and a height, except text, which works its size out from what it says and how big it is.
- A container in a tab container or a split container is sized by it, and one in a dock layout is stretched along its edge.

## Style and the theme
- Background, Border Color, Border Width and Corner Radius follow the app's theme until you change them. Once you set one, it stays as you set it, even if the theme changes. The Reset button next to it hands it back to the theme.
- A Background, a Border Color and a text color can be a color, a gradient, a gradient that moves, or a rainbow that cycles through the colors.
- Background Effect draws a picture, dots or moving shapes behind the element's contents.
- A container with Themed Panel takes all of its look from the theme, like a panel does.

## Tooltip
The text shown beside the mouse when it rests on the element for a moment.

## Names
- Every element has a name, shown in the hierarchy. Code finds an element by its name, such as `UiFile.find(ui, "play", ButtonOverlay.class)`, so give the ones you need a name that means something.
- On the root, the Controller is the class that has the methods the events name. The editor can make one for you.
