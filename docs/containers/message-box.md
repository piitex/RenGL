# MessageBox

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.containers.MessageBox` is one-line dialogs over a [`ModalContainer`](modal-container.md): tell the user something,
ask yes or no, ask for a line of text, or ask which of a few buttons.

```java
MessageBox.alert(window, "Saved", "Your game was saved to slot 2.");

MessageBox.confirm(window, "Delete save?", "This cannot be undone.", "Delete", "Keep",
        yes -> { if (yes) delete(slot); });

MessageBox.prompt(window, "Rename", "New name for the city:", city.name(),
        name -> { if (name != null) city.rename(name); });

MessageBox.choose(window, "Unsaved changes", "Save before closing?", List.of("Save", "Discard", "Cancel"),
        index -> handle(index));
```

Each shows the dialog at once and returns the `ModalContainer`, in case it needs to be closed from code or restyled. The callback runs once,
when the dialog closes.

| Call | Buttons, left to right | Callback gets |
|---|---|---|
| `alert(window, title, message[, onClose])` | OK | nothing; `onClose` runs however it is closed |
| `confirm(window, title, message, result)` | Cancel, OK | `true` for OK, `false` for Cancel or a dismissal |
| `confirm(window, title, message, yes, no, result)` | `no`, `yes` (your labels) | the same |
| `prompt(window, title, message, initial, result)` | Cancel, OK | the text for OK (or Enter in the field), `null` otherwise |
| `choose(window, title, message, buttons, result)` | yours | the index pressed, or -1 for a dismissal |

Pressing Escape or clicking outside the dialog **dismisses** it, which counts as the "no" answer in the last column. The primary action is
the rightmost button.

The dialog is a themed 400 point wide [`VerticalLayout`](../layouts/vertical-layout.md): the title (bold), the message (wrapped), the text
field for `prompt`, and a row of buttons at the right. It grows to fit the message. Being a modal it blocks the rest of the UI until closed,
and a dialog opened from a callback stacks on top. `ModalContainer#getContent()` is the panel, if you need to add to it.

For different contents (a form, an image), build a `ModalContainer` yourself.

## Notes

- `prompt` puts the keyboard in the field with the initial text in it; Enter confirms.
- A confirm for a destructive action reads best with the action named on the button ("Delete" and "Keep") rather than OK and Cancel.
