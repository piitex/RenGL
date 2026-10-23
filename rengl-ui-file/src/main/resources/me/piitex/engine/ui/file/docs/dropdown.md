A box that opens a list of choices when it is clicked.

## What it is for
- Choosing one thing out of many: a language, a resolution, a country. It takes less room than radio buttons.

## How it works
- Items are the choices. In the editor, separate them with |. A layout file holds them as a list.
- Selected Item is the number of the chosen item, counting from 0, or -1 for none.
- Placeholder is what the box says while nothing is chosen.
- On Select is the name of a method that runs when an item is picked, and is given the item.

## Tips
- Text Color, Font Size and Text Position change how the chosen item is drawn.
