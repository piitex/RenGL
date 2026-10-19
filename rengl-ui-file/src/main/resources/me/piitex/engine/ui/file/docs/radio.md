A round button that is one of a set where only one can be chosen.

## What it is for
- A choice of one thing out of a few: difficulty, size, a payment method.

## How it works
- Radio buttons with the same Group name choose one between them. Choosing one un-chooses the other in its group.
- Selected is whether this one is chosen. Dot Color follows the theme until you set it.
- After loading, the group is found from code with `getGroup()` on any of its buttons, and listened to with `onChange`.

## Tips
- Put a Text element beside each in a horizontal layout for its label.
- For options that can be on at the same time, use Check Boxes. For a long list, use a Dropdown.
