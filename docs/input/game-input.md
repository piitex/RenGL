# Game input

[Documentation](../README.md) › [Input](README.md)

Game code usually asks about input instead of waiting for events: is the jump key held, was fire pressed this
step, where is the mouse. Three types in `me.piitex.engine.input` answer that:

| Type | What it is |
|---|---|
| `Input` | The keyboard, mouse and [controllers](gamepad.md) as the game sees them. One per window: `window.getInput()` |
| `InputContext` | A set of named actions, such as "jump" or "move_left", each triggered by one or more bindings. Game code asks about actions, so players can rebind them |
| `Binding` | One key, mouse button, wheel direction or [controller](gamepad.md) button or stick direction, optionally with modifier keys held: Space, Ctrl+S, Left Mouse, Wheel Up, PAD_A |

```java nocompile
public class Level extends Scene {
    private final InputContext controls = new InputContext("gameplay")
            .bind("move_left", Binding.key(GLFW_KEY_A), Binding.key(GLFW_KEY_LEFT))
            .bind("move_right", Binding.key(GLFW_KEY_D), Binding.key(GLFW_KEY_RIGHT))
            .bind("jump", Binding.key(GLFW_KEY_SPACE), Binding.mouse(GLFW_MOUSE_BUTTON_RIGHT))
            .bind("pause", Binding.key(GLFW_KEY_ESCAPE));

    public Level() {
        addInputContext(controls); // receives input while this scene runs
    }

    @Override
    protected void fixedUpdate(float step) {
        velocityX = controls.getAxis("move_left", "move_right") * SPEED;
        if (controls.isJustPressed("jump") && onGround) velocityY = -JUMP_SPEED;
    }

    @Override
    protected void update(float delta) {
        if (controls.isJustPressed("pause")) getSceneManager().push(new PauseMenu());
    }
}
```

Scenes can still use [`onKey`](../game/scenes.md#keyboard-input) for input as events. Both can be used in the same
scene.

## Just pressed, in steps and frames

A press lasts an instant, but a game reads input from two places: `fixedUpdate`, which may run several times in a
frame or not at all, and `update`, which runs once a frame. The "just" queries follow where they are called from:

| Called from | `isJustPressed`, `isJustReleased`, `isRepeated`, mouse and wheel deltas mean |
|---|---|
| `fixedUpdate` | Since the previous fixed step. A press shows in exactly one step, the first one after it |
| Anywhere else: `update`, `render`, `onKey`, an element callback | During this frame. A press shows in exactly one frame |

So a jump read in `fixedUpdate` is never missed when the frame rate is higher than the tick rate, and never happens
twice when a slow frame runs several steps. A key pressed and let go within one frame or step is just pressed and
just released at once, and never down.

Held time works the same way. `getHeldTime` counts the current step or frame, in seconds of
[game time](../game/game-time.md): in `fixedUpdate` it goes up by exactly one step each step, which keeps charge
attacks and long jumps the same on every machine. It stands still while game time is paused and is 0 when the key
isn't held.

## Actions

| Method | Returns |
|---|---|
| `isDown(action)` | True while the action is held by any of its bindings |
| `isJustPressed(action)` | True if it was pressed. Holding a second binding while the first is down doesn't press it again |
| `getPressCount(action)` | How many times it was pressed in that step or frame: 0 if not pressed, more than 1 for a wheel turned several notches |
| `isJustReleased(action)` | True if it was let go, including by the context [stopping being active](#which-context-gets-a-press) |
| `isRepeated(action)` | True if the OS repeated a held key of the action, as it does after a key is held for a moment. For menus, move on `isJustPressed(a) \|\| isRepeated(a)` |
| `getHeldTime(action)` | Seconds held, as above |
| `getValue(action)` | How strongly it is pressed, 0 to 1. Keys and buttons give 0 or 1; a [stick or trigger](gamepad.md#bindings) gives how far it is pushed |
| `getAxis(negative, positive)` | `getValue(positive) - getValue(negative)`, from -1 to 1: `getAxis("move_left", "move_right")` |
| `getVector(left, right, up, down)` | A `Vec2` from four actions, with y growing downwards like the screen, and never longer than 1, so diagonals aren't faster |

`bind(action, defaults...)` adds an action. Its bindings start as those defaults. Asking about an action the
context doesn't have throws, with the list of actions it does have, so a typo shows at once.

Action names use letters, digits, `_` and `-`, because they become keys when [saved](#saving-bindings).

## Bindings

| Binding | Triggered by |
|---|---|
| `Binding.key(GLFW_KEY_SPACE)` | The key, whatever modifiers are held. A player holding Shift to run can still jump |
| `Binding.key(GLFW_KEY_S, GLFW_MOD_CONTROL)` | The key pressed while the modifiers are held: Ctrl+S |
| `Binding.mouse(GLFW_MOUSE_BUTTON_LEFT)`, `Binding.mouse(button, mods)` | A mouse button, 0 to 7 |
| `Binding.wheel(Binding.WHEEL_UP)`, `wheel(direction, mods)` | A wheel direction: `WHEEL_UP`, `WHEEL_DOWN`, `WHEEL_LEFT`, `WHEEL_RIGHT` |
| `Binding.gamepad(Gamepad.Button.A)`, `gamepadAxis(axis, positive)`, `gamepadTrigger(axis)` | A controller button, a stick pushed one way, a trigger pulled. See [Gamepads](gamepad.md) |

When a press matches several bindings in a context, the ones requiring the most modifiers win. With both S and
Ctrl+S bound, Ctrl+S triggers only the second. S pressed with Ctrl held, and no Ctrl+S binding, still triggers
the first. A chord's action is released when its key is, whatever happens to the modifiers.

**Wheel bindings** press and release their action at once, so they only show as just pressed and just released,
never as held. Wheel travel adds up to whole notches before it counts, so a trackpad triggers them at about the
rate of a wheel. Several notches in one frame or step are several presses: `isJustPressed` is still true once, and `getPressCount(action)` says how many.

**Key codes are positions, not letters.** GLFW names keys by where they are on a US keyboard, so `GLFW_KEY_W` is
the key above S on every layout, which keeps WASD in place on a French keyboard, where that key says Z.
`binding.getDisplayName()` gives the name to show a player: what the key prints on their own layout ("Z"), and
modifiers as their platform calls them ("Cmd+S" on macOS, "Ctrl+S" elsewhere). Mouse buttons show as "Left Mouse",
"Mouse 4", and the wheel as "Wheel Up".

| Method | What it does |
|---|---|
| `getBindings(action)`, `getDefaultBindings(action)` | The current and the default bindings |
| `setBindings(action, bindings...)` | Replaces the bindings. A key held through an old binding still releases the action when let go |
| `addBinding(action, b)`, `removeBinding(action, b)` | Adds or removes one |
| `resetBindings(action)`, `resetBindings()` | Puts the defaults back |
| `getActionsBoundTo(binding)` | The actions using a binding, for handling a conflict when a player rebinds |

## Which context gets a press

A context receives input while it is **active**: it has been added to the window's `Input` or to a scene that is
running, and it is enabled. Active contexts are ordered like what they belong to:

1. The window's own contexts, from `input.addContext(context)`, the last added first. Use them for controls that
   work everywhere, such as a screenshot or fullscreen key, or a debug console.
2. Then each running scene's contexts, from the top of the stack down. A scene's own contexts go last added first.

A press goes to the **first active context that binds it**, and to no other. Only that context sees it held until
it is released. So a menu that binds Enter to "confirm" takes Enter from the game below that binds it to "interact",
while keys the menu doesn't bind go on to the game.

A **blocking** context, `setBlocking(true)`, takes every press, bound or not, and the contexts below it stop being
active. Use it for a dialogue box or menu over a game that keeps running
([`setPausesBelow(false)`](../game/scenes.md)): the game's world keeps moving, but the player can't.

When a context stops being active, because its scene was paused or removed, it was disabled, or a blocking context
came above it, everything held in it is released, and its actions report `isJustReleased`. It doesn't see keys that
are already down when it becomes active again, until they are pressed anew. This is what stops presses from leaking
between scenes:

- The Escape that opens a pause menu belongs to the game's context, so the menu doesn't see it and close again.
- The Escape that closes the menu belongs to the menu's context, so the game doesn't see it when it resumes in the
  same frame and open the menu again.
- A key held when a dialogue opens stops moving the player, instead of staying held.

`setEnabled(false)` turns a context off without removing it, for example during a cutscene. `isActive()` tells
whether a context is receiving input, and `input.getActiveContexts()` lists them in the order a press is offered.

A context can belong to several scenes, such as shared menu controls. Changes to what is active take effect at the
next input and at the start of the next frame.

## The raw keyboard and mouse

`Input` also answers for keys and buttons directly, for games that don't need actions and for debugging tools:

| Method | Returns |
|---|---|
| `isKeyDown(key)`, `isKeyJustPressed(key)`, `isKeyJustReleased(key)`, `isKeyRepeated(key)`, `getKeyHeldTime(key)` | As for actions, for a `GLFW_KEY_*` code |
| `isMouseDown(button)`, `isMouseJustPressed(button)`, `isMouseJustReleased(button)`, `getMouseHeldTime(button)` | As for actions, for a `GLFW_MOUSE_BUTTON_*` |
| `getMouseX()`, `getMouseY()` | The mouse position in window points, the space [`camera.screenToWorld`](../graphics/camera.md) takes. It keeps following the mouse outside the window while a button is held, and is -1 before the mouse first comes over the window |
| `getMouseDeltaX()`, `getMouseDeltaY()` | How far the mouse moved in this frame or step. Coming back into the window isn't a movement |
| `getScrollX()`, `getScrollY()` | How far the wheel turned in this frame or step. Up and right are positive |
| `isMouseInWindow()` | Whether the mouse is over the window |
| `getTypedText()` | The text typed in this frame that no text field or other focused element took, resolved against the keyboard layout (Shift, dead keys, accents, other scripts). Usually empty. For a chat line or console you draw yourself |
| `isAnyJustPressed()` | Whether any key or mouse button was pressed, for "press any key" |
| `getModifiers()` | The modifier keys held, as `GLFW_MOD_*` bits |

Contexts don't affect the raw state: it sees every key and button that reaches the game.

## Locking the mouse

A game aimed with the mouse can lock it to the window with `window.setCursorLocked(true)`, so a click near the edge
never lands on another application. The OS cursor is hidden and stays in the window however far the mouse moves;
switching away with the keyboard (Cmd+Tab, Alt+Tab) frees it until the window has focus again. A locked mouse has no
place on screen, and `getMouseX()` keeps counting past the edges, so the game draws its own pointer and moves it by
`getMouseDeltaX()` and `getMouseDeltaY()`. Where the platform supports raw mouse motion, the deltas come without the
OS's pointer acceleration.

The interface still reacts to the mouse where `Input` says it is, so free it for menus, and put the OS cursor where
the game's pointer was:

```java
window.setCursorLocked(false);
window.setCursorPosition(pointerX, pointerY);
```

Pausing when `window.isFocused()` turns false is a good habit for a locked game: the player has gone elsewhere.

## What reaches the game

The interface gets input first, as it always has, and the game only sees what it leaves:

| Input | Reaches the game unless |
|---|---|
| Key pres or repeat | A text field or other [focused element](input-and-focus.md#keyboard-focus) has the keyboard, or a popup or modal is open. A scene's `onKey` that consumes the key keeps it from every context, but not from the raw state |
| Mouse press | It lands on an element that listens for clicks (a button, a checkbox, anything with `onMouseClick`), gives focus to a text field, opens a context menu or starts dragging a panel; or a popup or modal is open |
| Wheel | It scrolls something, such as a scroll area or text area; or a popup or modal is open |
| Typed text | No element has [keyboard focus](input-and-focus.md#keyboard-focus), so there is nothing to type into: `Input.getTypedText()` |
| Releases | Always reach it, so nothing is left held down |

A click on an element that ignores clicks, such as a label, an image or a plain panel, counts as game input, so the
world can be clicked through text drawn over it. A [`Background3D`](../3d/background-3d.md) is driven by exactly this:
it turns the game input into drag, wheel, pointer and key events for its camera controls. To keep clicks on a HUD panel from the game, give the panel a click
listener that does nothing: `panel.onMouseClick(e -> {})`.

**Tab** moves [keyboard focus](input-and-focus.md#tab-order) while an element has focus or a popup or modal is
open. Otherwise the game gets it first, and it moves focus only if no scene's `onKey` consumed it and no context
binds it. So a game can bind Tab to its map and still have Tab work in its forms.

## Rebindings

`input.listenForBinding(callback)` hands the next key, mouse button or wheel turn to the callback as a `Binding`,
for the "press a key" prompt of a controls menu:

```java nocompile
bindButton.onAction(() -> {
    bindButton.setText("Press a key...");
    getWindow().getInput().listenForBinding(binding -> {
        if (!binding.equals(Binding.key(GLFW_KEY_ESCAPE))) {   // Escape cancels
            for (String other : controls.getActionsBoundTo(binding)) controls.removeBinding(other, binding);
            controls.setBindings("jump", binding);
            saveControls();
        }
        bindButton.setText(bindingsText("jump"));
    });
});
```

- The prompt gets input before anything else, the interface included, so every key can be bound, Tab and Escape too.
  The press it takes goes nowhere else, and the character that key types is dropped.
- A modifier key waits. Held while another key or a button is pressed, it becomes part of that binding (Ctrl+S).
  Pressed and let go on its own, it is the binding (Left Ctrl).
- A click is a mouse binding too, even on a button. Offer Escape, or a timeout, as the way out.
- `cancelListening()` stops waiting without calling the callback. `isListening()` tells whether a prompt is waiting.

## Saving bindings

`context.save(config, path)` stores the bindings a player changed in a [`Config`](../reference/config.md), and
`context.load(config, path)` reads them back:

```java nocompile
Config settings = Config.loadOrEmpty(Path.of("settings.cfg"));
controls.load(settings, "controls.gameplay");   // after the actions are bound, before play

// after a change in the controls menu
controls.save(settings, "controls.gameplay");
settings.save(Path.of("settings.cfg"));
```

```text
controls:
  gameplay:
    jump:
      - W
      - MOUSE_RIGHT
    fire: []
    zoom_in:
      - "CTRL+WHEEL_UP"
```

- Only actions whose bindings differ from their defaults are written, and resetting an action removes its entry.
  A game update that changes a default therefore reaches every player who hadn't rebound that action. When nothing
  differs, the section (and any parent it leaves empty) is removed.
- An empty list unbinds the action. An action missing from the file gets its defaults.
- A binding that can't be read is skipped with a warning in the log; entries for actions the context doesn't have
  are left alone. A single value (`jump: SPACE`) reads as a list of one.
- The text form is `Binding.toString()`, which `Binding.parse` reads: modifiers `SHIFT`, `CTRL`, `ALT`, `SUPER`
  joined with `+`, then a key name (`SPACE`, `A`, `F1`, `LEFT_SHIFT`, `KP_4`, `GRAVE_ACCENT`: the `GLFW_KEY_` names),
  `MOUSE_LEFT`, `MOUSE_RIGHT`, `MOUSE_MIDDLE`, `MOUSE_4` to `MOUSE_8`, or `WHEEL_UP`, `WHEEL_DOWN`, `WHEEL_LEFT`,
  `WHEEL_RIGHT`. Case and spaces don't matter, and `CONTROL`, `CMD`, `META` and `OPTION` are accepted too.

## Things to know

- **Render thread only,** like the rest of the game code. Every scene hook and element callback runs there.
- **No gamepads yet.** Actions are shaped for them: `getValue`, `getAxis` and `getVector` will take analog sticks and
  triggers when gamepad bindings are added.
- **Presses queued in the same frame as a scene change** go to the contexts that were active when they were
  handled. A key pressed in the same frame as the one that opens a menu can still reach the game for that frame.
- **While game time is paused** with `GameTime.setPaused`, no fixed steps run, so a press made during the pause shows
  in the first step after it. Pausing with a scene, such as a pause menu, doesn't have this, since the menu's context
  takes the presses.

`InputDemo` in `me.piitex.examples.demo` shows all of this: movement, dash, a charged shot using held time, a
dialogue box with a blocking context, Tab bound to a grid, a raw input panel on a window context (F1), and a controls
menu that rebinds any action and saves to `saves/input-demo.cfg`. `SpriteDemo` uses a context for its camera and
movement controls.
