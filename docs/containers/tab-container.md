# TabContainer

[Documentation](../README.md) › [Layouts](../layouts/README.md)

`me.piitex.engine.ui.containers.TabContainer` shows **a strip of tabs above (or below) a content area**, and displays the
content of the selected tab. Each tab pairs a title with one [`Element`](../core/element.md), usually a [`Layout`](../layouts/README.md)
or a [`ScrollContainer`](scroll-container.md).

```java
TabContainer tabs = new TabContainer(20, 20, 600, 400);
tabs.addTab("General", generalPage);
tabs.addTab("Advanced", advancedPage).setClosable(true);
tabs.onTabSelected(tab -> System.out.println("Now showing " + tab.getTitle()));
root.addElement(tabs);
```

## Content

- Each tab's content is a child of the container and is **resized to fill the area under the strip every frame**
  (inside [`setPadding`](../layouts/README.md#shared-settings)). Anything that lays itself out gets the room automatically.
- Only the selected tab's content is **enabled**. The others are disabled, which keeps their state (scroll position, typed
  text) but stops them drawing, updating and receiving input. Do not enable or disable a tab's content yourself. Select
  the tab instead.
- Content is drawn on top of the container's own panel. A layout used as a page is transparent by default (see
  [styling a layout](../layouts/README.md#styling-a-layout)), so it only draws a box inside the panel if you style it.
- Content that is a `Container` is set to clip its children.
- Use `addTab`/`removeTab`. `addElement` throws `UnsupportedOperationException`. `removeElement` on a tab's content removes
  the tab. The strip itself cannot be removed.

## Tabs

```java
Tab tab = tabs.addTab("Notes", notesPage); // at the end
tabs.addTab(0, "First", firstPage); // at an index

tab.setTitle("Notes*");
tab.setClosable(true); // shows a close button
tab.setEnabled(false); // dimmed, cannot be picked by the user
tab.setTooltip("Scratch notes"); // default: the full title, only if it was cut short

tabs.getTabs(); // read-only list, in display order
tabs.getTabCount();
tabs.removeTab(tab); // or removeTab(index)
```

The first tab added is selected automatically. Removing the selected tab selects the next enabled tab, else the previous.
A removed tab's content is left enabled, so you can use it elsewhere.

## Selecting

```java
tabs.selectTab(tab); // or selectTab(index)
tabs.getSelectedTab(); // null while there are no tabs
tabs.getSelectedIndex(); // -1 while there are no tabs
tabs.onTabSelected(t -> ...); // fires for every change, from the user or from code
```

The user clicks a tab, or clicks the strip and uses **Left / Right / Home / End**. Tab moves focus to the strip like any other
focusable element. A disabled tab cannot be picked by the user, but code can still select it.

## Closing

A closable tab shows a close button and also closes on a **middle-click**. Both go through two hooks:

```java
tabs.onTabCloseRequested(tab -> tabs.getTabCount() > 1); // return false to keep it open
tabs.onTabClosed(tab -> System.out.println("Closed " + tab.getTitle()));
```

`closeTab(tab)` does the same from code. `removeTab(tab)` skips both hooks, since it is the code asking. To ask the
user first, return `false` from `onTabCloseRequested`, show a [modal](modal-container.md), and call
`removeTab` if they agree.

## Position

```java
tabs.setTabPosition(TabContainer.TabPosition.BOTTOM); // TOP (default) or BOTTOM
```

## Overflow

When the tabs do not fit, they shrink down to `minTabWidth`, and long titles are cut with an ellipsis (the full title
shows as a tooltip). If they still do not fit, an **arrow button appears at each end** of the strip and the tabs scroll between them, by the
arrows or the mouse wheel. The arrows take their own room, so no tab is left half hidden behind the edge, and the
strip follows the selection (a dimmed arrow means there is nothing more that way).

## Styling

The container's own [`getStyling()`](../styling/styling.md) is the panel behind both strip and content. It is themed like a
[themed container](container.md), and the border sits around everything. The strip is styled through
`getTabStyle()`:

```java
TabStyle style = tabs.getTabStyle();
style.setFontSize(16f);
style.setIndicatorColor(Color.ORANGE);
style.setMaxTabWidth(160f);
```

| Setting | Meaning | Default |
|---|---|---|
| `fontSize` | Title size | `14` |
| `tabHeight` | Strip height. `0` is automatic (line height + `verticalPadding` twice) | `0` |
| `verticalPadding` / `horizontalPadding` | Space around a title | `9` / `14` |
| `minTabWidth` / `maxTabWidth` | Tab width limits | `72` / `220` |
| `spacing` | Gap between tabs | `2` |
| `cornerRadius` | Rounding of a tab's fill | `4` |
| `indicatorThickness` | The bar on the selected tab. `0` hides it | `2` |
| `closeButtonSize` | Hit area of the close button | `14` |
| `scrollButtonWidth` | Width of each overflow arrow button | `24` |
| `tabColor`, `hoverColor`, `selectedColor` | Tab fills | transparent, theme field hover, theme button |
| `textColor`, `selectedTextColor` | Title colors | theme text, theme text |
| `indicatorColor` | Selected-tab bar and focus outline | theme selection, solid |
| `dividerColor` | Line between strip and content | theme container border |

Colors follow the theme until you set them explicitly, which pins them (see [theming](../styling/theming.md)). Each is a
[`Paint`](../styling/colors-and-paints.md), so a tab, its title or the indicator can be a gradient. A disabled tab's
title is drawn at 40% opacity.

## Notes

- The strip's font size scales with a [`ResizableContainer`](resizable-container.md) parent, and the strip height
  follows it while `tabHeight` is automatic.
- `setPadding` insets the content only. The strip always spans the full inner width.
- `onMouseClick` fires on press, so a tab is selected on press, not release.
