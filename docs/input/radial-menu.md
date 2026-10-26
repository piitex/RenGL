# RadialMenu

[Documentation](../README.md) › [Input](README.md) › [Menus](menus.md)

`me.piitex.engine.ui.menu.RadialMenu` is a pie menu: the choices are wedges of a ring around the pointer, picked by the direction the pointer
moves rather than by aiming at a small row. It suits a game's quick actions (emotes, weapons, build tools) because the choice is the same short
flick from anywhere, and a player learns "up is the hammer".

```java
RadialMenu tools = new RadialMenu();
tools.addItem("Build", Feather.HOME, () -> mode(BUILD));
tools.addItem("Repair", Feather.TOOL, () -> mode(REPAIR));
tools.addItem("Demolish", Feather.TRASH, () -> mode(DEMOLISH)).setEnabled(canDemolish);
tools.show(window, MouseTracker.getX(), MouseTracker.getY());        // centered on the pointer
```

The first item is at the top and the rest follow clockwise, evenly spaced. `show(host, x, y)` takes any
[`PopupHost`](../core/window.md) (a window, or a screen in a 3D scene) and moves the ring inward when it would leave the room the host has.
It opens through the host's popup layer, so it paints over everything and closes on an outside click or Escape.

## Choosing

| Input | Result |
|---|---|
| Pointer over a wedge | The wedge lights up and its name shows in the hub |
| Click on a wedge | Runs its action and closes the menu |
| Click on the hub, or outside the ring | Closes it without choosing |
| Arrow keys | Move the lit wedge around the ring, skipping disabled items |
| Enter or Space | Picks the lit wedge |
| Digits 1 to 9 | Pick that item |
| Escape | Closes |

The pointer moving takes the lit wedge back from the keyboard. `pick(index)` does what a click does, `wedgeAt(x, y)` says which wedge a point is
over, and `getLitIndex()` which is lit now.

## Items and size

```java
RadialMenu.Item item = menu.addItem("Label", Feather.STAR, action);   // the icon is optional and tinted with the text color
item.setEnabled(false);              // drawn dim, ignores clicks and keys
menu.clearItems();
menu.setRadii(40, 120);              // hub radius, outer radius (36 and 110 by default)
menu.onClose(() -> resumeGame());    // however it closes
```

A menu can be shown again after it closes, and its items changed between times. Colors come from the theme: the wedges from the container
background, the lit wedge from the accent, the text from the theme's text color; `setWedgeColor`, `setLitColor` and `setTextColor` pin them.

There is no hold-to-open, release-to-pick mode yet: the menu is opened by your own code (a key press, say) and picked by a click or key.
