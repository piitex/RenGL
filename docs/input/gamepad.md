# Gamepads

[Documentation](../README.md) › [Input](README.md)

RenGL reads game controllers (Xbox, PlayStation and Switch Pro pads, and the many that report as one) through GLFW, with no setup:
plug one in and it is there. A controller is another way to trigger the same [actions](game-input.md) a key does, so most games add
bindings and never poll a pad.

```java nocompile
InputContext controls = new InputContext("gameplay")
        .bind("jump", Binding.key(GLFW_KEY_SPACE), Binding.gamepad(Gamepad.Button.A))
        .bind("fire", Binding.mouse(GLFW_MOUSE_BUTTON_LEFT), Binding.gamepadTrigger(Gamepad.Axis.RIGHT_TRIGGER))
        .bind("move_left", Binding.key(GLFW_KEY_A), Binding.gamepadAxis(Gamepad.Axis.LEFT_X, false))
        .bind("move_right", Binding.key(GLFW_KEY_D), Binding.gamepadAxis(Gamepad.Axis.LEFT_X, true));
scene.addInputContext(controls);

// Scene.fixedUpdate
velocityX = controls.getAxis("move_left", "move_right") * speed;   // -1 to 1 on a stick, -1, 0 or 1 on keys
if (controls.isJustPressed("jump")) jump();
```

`me.piitex.examples.demo.GamepadDemo` is a controller tester that shows all of it: a diagram of the pad that lights up, the numbers for
sticks and triggers, a log of presses, and boxes that rebind actions by pressing a button.

## Bindings

| Binding | Triggered by |
|---|---|
| `Binding.gamepad(Gamepad.Button.A)` | A button of the pad. The D-pad is four buttons, and pressing a stick in is `LEFT_THUMB` or `RIGHT_THUMB` |
| `Binding.gamepadAxis(Gamepad.Axis.LEFT_X, false)` | A stick pushed one way: `false` is left (or up, on a `_Y` axis), `true` is right (or down) |
| `Binding.gamepadTrigger(Gamepad.Axis.RIGHT_TRIGGER)` | A trigger pulled |

Controller bindings have no keyboard modifiers, and they save and load with the rest as `PAD_A`, `PAD_DPAD_UP`, `PAD_LEFT_STICK_LEFT`,
`PAD_RIGHT_TRIGGER` and so on (see [`Binding.toString`](game-input.md#bindings)). An action can mix keys and buttons freely: it is held while any
of them is, and presses once however many are.

**Sticks and triggers are analog.** A stick binding counts as pressed once the stick passes a press point (half way by default,
`Gamepads.setPressPoint`), which suits menus and `isJustPressed`, but `getValue(action)` follows the stick all the way from 0 to 1, so
`getAxis` and `getVector` give smooth movement. With several bindings held the strongest value wins, so a player on a keyboard still
moves at full speed.

## Which pad

Any connected pad triggers an action. For one pad per player give each player's context its own slot, and it ignores the others:

```java nocompile
InputContext player1 = new InputContext("p1").bind("jump", Binding.gamepad(Gamepad.Button.A)).setGamepad(0);
InputContext player2 = new InputContext("p2").bind("jump", Binding.gamepad(Gamepad.Button.A)).setGamepad(1);
```

A slot is the order the OS found the pad in, 0 to 15, and stays with a pad while it is plugged in. A context for one pad does not take presses
from the others, so they go on to the contexts below it.

## Polling a pad

`window.getInput().getGamepad()` is the first pad that is plugged in (or slot 0, not connected, if there is none), `getGamepad(index)` a slot, and
`getGamepads()` all of them. A slot with nothing in it still gives a `Gamepad` that reads as untouched, so there is nothing to null check.

| Method | What it gives |
|---|---|
| `isConnected()`, `getName()`, `getGuid()`, `getIndex()` | What and where the pad is |
| `isDown(button)`, `isJustPressed(button)`, `isJustReleased(button)`, `getHeldTime(button)` | Buttons, with the same just-pressed rule as [keys](game-input.md#just-pressed-in-steps-and-frames) |
| `getLeftStick()`, `getRightStick()`, `getStick(stick)` | A `Vec2`, at most 1 long |
| `getLeftTrigger()`, `getRightTrigger()` | 0 (released) to 1 |
| `getAxis(axis)`, `getRawAxis(axis)` | One axis with the dead zone out, and as the hardware reports it |
| `getLayout()` | `XBOX`, `PLAYSTATION`, `NINTENDO` or `GENERIC`, for naming buttons |

**Y grows downwards**, like the screen and `InputContext.getVector`: pushing a stick up gives a negative y. (`JoystickOverlay`, the on-screen stick, is the
other way round, up positive, so a demo that shows a pad on one needs a sign flip.)

Plugging in and removing are told to a listener:

```java nocompile
input.addGamepadListener(new Gamepad.Listener() {
    @Override public void connected(Gamepad pad) { toasts.show("Controller connected: " + pad.getName()); }
    @Override public void disconnected(Gamepad pad) { pauseGame(); }
});
```

A pad that is already plugged in when the window opens is announced in the window's first frame. Removing a pad lets go of everything it held,
so no action is left pressed. A button held when a pad is plugged in is held, not pressed.

## Dead zones

Sticks do not rest at exactly 0. RenGL takes a circular dead zone out of each stick (20% of its travel by default) and stretches the rest so the
edge still reads 1, so a pad on a table reads exactly 0 and no movement starts with a jump. Triggers have a small one (5%).

```java
Gamepads.setStickDeadZone(0.15f);    // 0 to 0.9; a worn stick needs more
Gamepads.setTriggerDeadZone(0.05f);
Gamepads.setPressPoint(0.5f);        // how far a stick or trigger must go to press a binding made on it
```

`getRawAxis` ignores all of this. A settings screen can offer the dead zone as a slider while the player watches the raw numbers.

## What counts as game input

The interface comes first, as with the keyboard. While a popup or modal is open or an element has keyboard focus, the pad belongs to it:

| Pad | Does |
|---|---|
| D-pad, left stick | The arrow keys, repeating while held |
| A | Enter |
| B | Escape: closes a popup or modal |
| Left and right bumper | Shift+Tab and Tab: previous and next field |

So menus, dropdown lists, popup and [radial](radial-menu.md) menus, sliders and text fields can be used from the pad. Meanwhile the game sees nothing:
buttons pressed then do not show in `Gamepad` or in any context, and the sticks read 0 (raw values do not change). A release always reaches the game, so a
button held when a menu opens is not stuck. The interface only takes the pad where it already has the keyboard: with nothing open or focused, the pad is
the game's, and nothing moves a highlight over plain buttons yet.

A window that does not have the OS focus sees an untouched pad too, and what is held when it comes back is held, not pressed. Turn controllers off for
a program that never uses them with `Gamepads.setEnabled(false)`.

## Rebinding

`input.listenForGamepad(callback)` hands the next button or stick direction pressed to the callback as a `Binding`, before the interface sees it, so B and
the D-pad can be bound too. Keys and the mouse are not affected. (`input.listenForBinding` takes the next input from any device, pads included.) Mostly
you use [`HotkeyOverlay`](../overlays/hotkey-overlay.md#controllers), which does this for a controls screen:

```java nocompile
HotkeyOverlay keyBox = new HotkeyOverlay(150, 28);
keyBox.bindTo(controls, "jump");              // edits the first key or mouse binding
HotkeyOverlay padBox = new HotkeyOverlay(150, 28);
padBox.bindToGamepad(controls, "jump");       // edits the first controller binding, and learns only the pad
```

Name inputs for the pad the player has. The layouts differ in names and, on a Switch pad, in which button is where:

```java
binding.getDisplayName(Gamepad.Layout.PLAYSTATION);    // "Cross" for Binding.gamepad(Gamepad.Button.A)
binding.getDisplayName(pad.getLayout());               // as the connected pad labels it
```

| Binding | Xbox | PlayStation | Switch |
|---|---|---|---|
| `Button.A`, the bottom button | A | Cross | B |
| `Button.B`, the right button | B | Circle | A |
| `Button.BACK`, `Button.START` | View, Menu | Share, Options | -, + |
| `LEFT_BUMPER`, left trigger | LB, LT | L1, L2 | L, ZL |

`Gamepad.Button` names buttons by position, the layout of an Xbox pad, whatever the pad: `A` is always the bottom one.

## Which pads work

GLFW has a built-in database of controller mappings (SDL_GameControllerDB), which covers the common Xbox, PlayStation and Switch pads. A pad that is not in it, or an arcade stick or a wheel, appears in the log as "has no gamepad mapping, so it is ignored". Add a mapping
in SDL's text form with `Gamepads.loadMappings(text)` or `Gamepads.loadMappings(path)` (a `gamecontrollerdb.txt` works), before the pad is plugged in
or by plugging it in again. Flight sticks and wheels have no mapping and are not read at all.

## Not available

- **Rumble.** GLFW has no way to drive a controller's motors, so the engine cannot either.
- **Motion, touchpad, adaptive triggers and the lightbar.** The same: GLFW exposes only buttons and six axes.
- **Moving a highlight over buttons.** The pad drives a popup, a dropdown or a field that already has the keyboard, but nothing yet walks from one plain
  button to the next with the D-pad.

Controller input is read on the main thread about a hundred times a second, and each window takes the latest reading at the start of its frame. So a button
pressed and let go again within one frame (about 16 ms at 60 frames per second) can be missed; a real press lasts several times that.
