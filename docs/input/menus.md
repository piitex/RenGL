# Menus

[Documentation](../README.md) › [Input](README.md)

Three pieces share one menu model (`me.piitex.engine.ui.menu`):

| Class | What it is |
|---|---|
| `MenuItem` | One row: an action, a check item, a submenu, or a separator |
| `PopupMenu` | A floating list of items shown at any point in the window |
| `MenuBarOverlay` | A strip of titles ("File", "Edit", ...), each opening a `PopupMenu` |

A **context menu** is a `PopupMenu` attached to an element with `setContextMenu`. It opens on right-click.

```java
MenuBarOverlay bar = new MenuBarOverlay(1000); // width; height defaults to 28
bar.addMenu("File",
        new MenuItem("Open...", this::open),
        MenuItem.submenu("Open Recent", new MenuItem("notes.md", () -> load("notes.md"))),
        MenuItem.separator(),
        new MenuItem("Exit", () -> System.exit(0)));
root.addElement(bar);

panel.setContextMenu(new PopupMenu(
        new MenuItem("Refresh", this::refresh),
        new MenuItem("Delete", this::delete)));
```

See `MenuDemo` in `me.piitex.examples.demo` for a runnable example.

## MenuItem

```java
new MenuItem("Save", () -> save()); // action
MenuItem.check("Word wrap", false, on -> wrap(on)); // toggles a tick; callback gets the new state
MenuItem.submenu("Create", itemA, itemB); // cascading submenu, any depth
MenuItem.separator(); // divider line
```

```java
item.setShortcut("Ctrl+S"); // text shown right-aligned. DISPLAY ONLY, it does not bind a key
item.setEnabled(false); // dimmed; ignores hover and clicks
item.setChecked(true); // silent, no callback
item.setLabel("Save as...");
```

Choosing an action or check item **closes the whole menu first**, then runs the callback. The callback can therefore
open a [`ModalContainer`](../containers/modal-container.md) or another menu.

## PopupMenu

```java
PopupMenu menu = new PopupMenu(itemA, itemB);
menu.add(item).addSeparator().add("Label", runnable); // chainable

menu.show(window, x, y); // top-left corner at a window point
menu.show(element, x, y); // the same, but the menu then follows element
menu.showAtMouse(window); // at the pointer
menu.showBelow(moreButton); // under an element, left edges aligned; follows it
menu.close();
boolean open = menu.isOpen();
```

The menu is pulled back inside the window if it would overflow the right or bottom edge. It is a plain object, not an
`Element`, so build it once and show it as often as you like. Showing it does nothing if it has no items.

Refresh state just before each show with `onOpen`:

```java
menu.onOpen(m -> pasteItem.setEnabled(!Clipboard.paste().isEmpty()));
menu.onClose(() -> System.out.println("closed")); // any route: choice, outside click, Escape
```

`getItems()` returns the live list. `onOpen` may also `clear()` and rebuild it.

The menu is shown through `Window.showPopup`, so it paints above everything, is not clipped by a
[`ScrollContainer`](../containers/scroll-container.md), and only one popup is open at a time. A press outside it
closes it, and that press is consumed rather than reaching whatever is underneath.

`show(element, x, y)` and `showBelow` [anchor](../core/window.md#following-an-element) the menu to the element: it moves
with it and closes if the element is removed, disabled or scrolled out of view. `show(window, x, y)` and `showAtMouse`
stay where they opened.

## Context menus

```java
element.setContextMenu(menu); // null removes it
```

Works on every element, containers included. A right-click on a child with no menu of its own falls through to the
nearest ancestor that has one, and the deepest one wins. The menu opens at the pointer and follows the element that owns it. Right-clicking a `TextFieldOverlay` still focuses it as usual.
The click listener (`onMouseClick`) also still fires, with `getButton()` set to the right button.

## MenuBarOverlay

```java
MenuBarOverlay bar = new MenuBarOverlay(width); // or (width, height)
PopupMenu file = bar.addMenu("File", items...); // returns the PopupMenu, or pass your own
bar.addMenu("Edit", existingPopupMenu);
```

Click a title to open its menu. Click it again, click away, or press Escape to close. While one is open, hovering across
the bar swaps to the neighbouring menu without clicking, and Left/Right does the same from the keyboard (see below).

```java
bar.open(1); // open the second menu from code
bar.closeMenus();
int open = bar.getOpenIndex(); // -1 when closed
bar.setFontSize(14f);
bar.setTextColor(Color.WHITE); // pins the color against theme switches
```

The bar is drawn from the theme's container tokens (background and border) and text color, and marks the open or
hovered title with the theme's selection color. Place it at the top of the root container, as wide as the window.

> **Do not call `onMouseClick` on the bar.** The constructor registers its own handler to open the menus.

## Native menu bar on macOS

On macOS the menu bar lives at the top of the screen, not inside the window. `MenuBarOverlay` therefore uses the
**system menu bar by default** there. Your menus are inserted between the app menu (About, Hide, Quit) and the Window
menu. Nothing changes in your code: the same `addMenu`, `MenuItem` actions, check items and submenus work.

```java
bar.setUseNativeMenuBar(false); // draw it inside the window on macOS too
boolean nativeNow = bar.isNative();
```

While native, the overlay draws nothing, takes no clicks and reports a height of `0`. A layout that reads its
height therefore closes the gap. On Windows and Linux the bar is always drawn in the window.

| Behaviour in the system menu bar | Detail |
|---|---|
| Callbacks | Run on the render thread, like every other UI callback. The click arrives on the main thread and is queued for the next frame. |
| State changes | Enabling or disabling an item, flipping a check item, or adding and removing items is picked up automatically within a frame. |
| Shortcuts | `setShortcut("Ctrl+S")` becomes a real **Cmd+S** key equivalent. `Ctrl`, `Cmd`, `Command`, `Meta` and `Super` all mean Command, `Alt` and `Option` mean Option, and `Shift` is Shift. |
| Look | Follows the OS appearance. The engine `Theme`, `setFontSize` and the color setters do not apply. |
| `onOpen` | **Not called.** The native menu cannot run code as it opens. Change item state when the underlying state changes instead. |
| App menu name | It shows the process name (for example "java" when launched from an IDE) until the app is packaged. |

Things to watch:

- A shortcut such as Cmd+C / Cmd+V on a menu item is handled by the menu **before** the key reaches the window, so
  it takes priority over `TextFieldOverlay`'s own clipboard handling. If you add Copy and Paste items, make
  their actions do the job (or leave those shortcuts off).
- A shortcut with a named key (`"F5"`, `"Delete"`, `"Enter"`) has no native key equivalent. The item works by click only.
- Only one native menu bar exists per app, so with several windows the system menu bar shows the bar of the window that has the focus, and changes as the user clicks from one window to another. It keeps the last one shown while the app is in the background. Two `MenuBarOverlay`s in the same window: the first one that runs is shown.
- The interop is in `me.piitex.engine.gl.NativeMenuBar` (the same Objective-C approach `MetalContext` uses).

## Keyboard

While a menu is open it takes every key press, including Tab.

| Key | Effect |
|---|---|
| Up / Down | Move through the rows, skipping separators and disabled items, wrapping at the ends |
| Right | Open the highlighted submenu. In a menu bar with no submenu to enter, switch to the next menu |
| Left | Close the current submenu. In a menu bar at the top level, switch to the previous menu |
| Enter / Space | Choose the highlighted row (or open its submenu) |
| Escape | Close one submenu level, then the menu |

Moving the mouse hands control back to hover.

## Mouse behaviour

Highlights follow the pointer immediately. A submenu opens after the pointer rests on its row for a moment, and an open
submenu closes only after the pointer has rested on a different row in its parent for a moment. That gives the
pointer time to cross neighbouring rows on the way into the submenu. A submenu that would run off the right edge opens to the
left of its parent instead.

## Look

Colors come from the active [`Theme`](../styling/theming.md) each time the menu opens, so a theme switch applies the next
time it is shown: the field background and border, the theme text color, the placeholder color for disabled items and
shortcuts, and the selection color for the highlight. Pin any of them on a `PopupMenu`:

```java
menu.setBackgroundColor(new Color(0.12f, 0.12f, 0.14f, 1f));
menu.setHoverColor(...); menu.setTextColor(...); menu.setDisabledTextColor(...); menu.setBorderColor(...);
menu.setFontSize(16f); menu.setItemHeight(30f); menu.setMinWidth(200f);
```

Pass null to a color setter to go back to the theme. Each takes any [`Paint`](../styling/colors-and-paints.md), so a panel or
highlight can be a gradient. The menu bar's `setTextColor` is a paint too.

## Limitations

- **Shortcuts are labels only** in the in-window menus. `setShortcut` draws text but registers no key binding. (In the
  [macOS system menu bar](#native-menu-bar-on-macos) they are real key equivalents.)
- **A menu taller than the window is clamped to the top edge but does not scroll.**
- **No icons** in rows, and no radio-style groups. Use a check item per option.
- **A menu bar has no Alt/F10 activation** or per-title mnemonics.
- **One popup at a time.** Opening a dropdown list closes an open menu, and the reverse.

## Method reference

| Class | Methods |
|---|---|
| `MenuItem` | `new MenuItem(label, action)`, `check`, `submenu`, `separator`, `get/setLabel`, `get/setShortcut`, `is/setEnabled`, `is/setChecked`, `get/setAction`, `fire`, `getChildren`, `hasSubmenu`, `isSeparator`, `isCheckable` |
| `PopupMenu` | `add`, `addSeparator`, `getItems`, `clear`, `show`, `showAtMouse`, `showBelow`, `close`, `isOpen`, `onOpen`, `onClose`, `get/setFontSize`, `get/setItemHeight`, `get/setMinWidth`, `setBackgroundColor`, `setHoverColor`, `setTextColor`, `setDisabledTextColor`, `setBorderColor` |
| `MenuBarOverlay` | `addMenu`, `getMenus`, `open`, `closeMenus`, `getOpenIndex`, `get/setFontSize`, `get/setTextColor`, `is/setUseNativeMenuBar`, `isNative` |
| `Element` | `getContextMenu`, `setContextMenu` |
