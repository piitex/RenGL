# HotkeyOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.HotkeyOverlay` is a box that shows a key binding and learns a new one: the "Jump: Space" row of a controls
screen. Clicking it says "Press a key...", and the next key the player presses, with the modifiers held, becomes the binding.

```java
HotkeyOverlay jump = new HotkeyOverlay(160, 32);
jump.setBinding(Binding.key(GLFW.GLFW_KEY_SPACE));
jump.onBindingChange(b -> settings.setJump(b));

// or edit an action of an InputContext directly, with a warning when the key is taken
HotkeyOverlay fire = new HotkeyOverlay(160, 32);
fire.bindTo(gameplay, "fire");
```

It produces a [`Binding`](../input/game-input.md), the engine's key, mouse button or wheel direction with modifiers, so the result goes
straight into an `InputContext` and is saved with it.

## While it listens

| Press | Result |
|---|---|
| A key | Becomes the binding, with Ctrl, Shift, Alt and Super if held |
| Only a modifier | Keeps listening and shows the combination so far ("Ctrl+...") |
| Escape | Cancels; the old binding stays |
| Backspace or Delete | Unbinds it (`onBindingChange(null)`), unless `setAllowClear(false)` |
| A left click, or clicking elsewhere | Cancels |

Tab and Enter are captured like any other key, so a player can bind them. Enter or Space on the focused box starts listening.

## Settings

```java
hotkey.setAllowMouse(true);       // also learn middle, right and side buttons, and the wheel (default off)
hotkey.setAllowModifiers(false);  // keep the key alone, for games that use Shift and Ctrl on their own
hotkey.setAllowClear(false);      // an action that must always have a key
hotkey.setEmptyText("None");
hotkey.setListenText("Press a key or Esc");
hotkey.onListenStart(() -> others.forEach(HotkeyOverlay::stopListening));   // one box listening at a time
```

## Controllers

```java nocompile
HotkeyOverlay keyBox = new HotkeyOverlay(150, 28);
keyBox.bindTo(controls, "jump");              // edits the first key or mouse binding of the action
HotkeyOverlay padBox = new HotkeyOverlay(150, 28);
padBox.bindToGamepad(controls, "jump");       // edits the first controller binding, and learns only the pad
```

`setAllowGamepad(true)` also learns [controller](../input/gamepad.md) buttons, and sticks and triggers pushed one way, as `Binding.gamepad` and
`gamepadAxis` bindings. The press is taken before the interface sees it, so B and the D-pad can be bound too. `setGamepadOnly(true)` makes a box for the
pad alone: it ignores keys, the mouse and the wheel except Escape (cancel) and Backspace or Delete (unbind), and `bindToGamepad` sets it up for an action.
The text while listening is "Press a key or button..." or "Press a button...".

Controller bindings are named for the pad that is plugged in ("Cross" on a PlayStation pad, "A" on an Xbox one) or for `setLayout(layout)`. With a
keyboard box and a pad box on the same action, each edits only its own kind and leaves the other's binding where it is. A box edits the first binding
of its kind, and adds one at the end if the action has none.

## Conflicts

```java
hotkey.setConflictCheck(binding -> taken.contains(binding) ? "Already used by " + owner(binding) : null);
```

Return `null` to accept a binding or the reason to refuse it. The reason shows in red for a couple of seconds and the box keeps listening.

`bindTo(context, action)` does this for an [`InputContext`](../input/game-input.md): the box shows and edits the action's **first** key, mouse or wheel binding (the
others are left alone), and refuses a binding another action of the context already uses with "In use by <action>". It replaces any check and
listener set before.

## Look

Themed like a text field; the accent marks the box while it listens. `setFontSize`, `setTextColor` and `setAccentColor` change it. Call
`dispose()` when permanently discarding one.
