A layout that pins each item to an edge of the space that is left, with the last one filling the rest.

## What it is for
- A whole screen: a menu bar docked to the top, a status bar to the bottom, a side panel to the left, and the main area filling the middle.

## How it works
- Each item has a Dock setting: Top, Bottom, Left, Right or Fill.
- Items are taken in order. Each one takes a strip along its edge from the space that is left, and the next one gets what is left of that.
- An item docked Fill takes everything that is left. Put it last.
- Top and Bottom items are stretched to the full width, and Left and Right items to the full height of what is left.

## Tips
- The order matters: a Top item added before a Left item runs the whole way across, and a Left item added first runs the whole way down.
