# Accessibility

[Documentation](../README.md) › [Input](README.md)

What RenGL does today for someone who cannot use a mouse, or cannot see the screen:

- **Keyboard use.** Tab reaches every button, check box, radio button, link, slider, text field and drop-down. Space
  and Enter use the focused control. A ring shows where focus is.
- **Names and roles.** Each element can say what it is (button, slider, check box) and what it is called, in a form
  an accessibility API can read. This is in `rengl-core`.
- **Self-voicing.** With the `rengl-accessibility` module on the classpath, the window speaks: each control as Tab lands
  on it, new values as the arrows change them, toasts, and anything you pass to `window.announce(...)`.

- **Screen readers (Windows).** NVDA, JAWS and Narrator read the window through UI Automation: its controls, names,
  roles and values, where focus is, and what changes. They can press, toggle, select, and set sliders and text. See
  [Screen readers](#screen-readers).

On macOS and Linux there is no screen reader connection yet, so a window is one blank rectangle to VoiceOver or Orca;
self-voicing is what works there. See [What is missing](#what-is-missing).

## Turning on speech

Depend on `rengl-accessibility` (it is in `RenGL` too), then:

```java
Window window = new Window(options);
Accessibility.enable(window);        // the system's voice
```

Tab now reads each control aloud, and Shift+Tab goes back. A game usually puts this behind a setting:

```java
SpeechHost speech = Accessibility.enable(window);
speech.setEnabled(settings.screenReader);     // later: speech.setEnabled(true / false)
speech.getSpeaker().setRate(3);               // -10 slowest, 0 normal, 10 fastest
Accessibility.disable(window);                // remove it and release the voice
```

The voice is SAPI on Windows (through a PowerShell process kept running, started on the first word), `say` on macOS,
and `spd-say`, `espeak-ng` or `espeak` on Linux. Pass your own `Speaker` to `Accessibility.enable(window, speaker)` to
use a recorded voice or a cloud service instead.

## Screen readers

`Accessibility.enable(window)` also connects the window to Windows' accessibility system (`UiaHost`, UI Automation).
Nothing is done until a screen reader asks; then the tree is copied every quarter of a second, and at once when focus or
a value changes. Self-voicing stays quiet while a screen reader is running, so nothing is said twice.

```java
Accessibility.enable(window);              // both
Accessibility.enableScreenReader(window);  // only the screen reader connection (null off Windows)
```

A screen reader's press, toggle, select and set-value act as the user's own would: the control's change listeners run
(`onAction`, `onToggle`, `onValueChange`, `onTextChanged`). `window.announce(...)` and toasts reach it as notifications.
The window's controls report these UI Automation patterns: Invoke (buttons, links), Toggle (check boxes, switches),
Value (text fields and areas, drop-downs), RangeValue (sliders, progress bars) and SelectionItem (radio buttons).

How it was checked: a Windows UI Automation client (the interface NVDA and Narrator are built on) walked the tree, used
each pattern, and received the focus, value and notification events. NVDA, JAWS and Narrator themselves have not been run
against it, so expect rough edges in how they phrase things.

## Saying things

```java
window.announce("Game saved");              // after what is being said
window.announce("Low health", true);        // cuts in
```

`ToastManager` does this for every toast, and cuts in for an error. Subtitles, a score, "Wave 3 begins": anything that
shows up on its own and a person who cannot see it would miss.

## Naming controls

A button's name is its text. A check box, radio button, slider or switch has none, because the words beside it are a
separate element. Give it one:

```java
CheckBoxOverlay fullscreen = new CheckBoxOverlay(20);
fullscreen.setAccessibleName("Fullscreen");

TextOverlay label = new TextOverlay("Music volume", 16f, Color.BLACK);
slider.setLabelledBy(label);                  // reads "Music volume, slider, 40" and follows the label's text
```

An icon-only button needs a name the same way: `closeButton.setAccessibleName("Close")`. A text field falls back to its
placeholder. `setAccessibleDescription("...")` adds help read after the rest, and `setAccessibleHidden(true)` leaves
decoration (a divider, a background image) out.

`getAccessibleRole()` / `setAccessibleRole(...)` say what an element is; the built-in controls set their own. A
container that is a labelled panel can be given `AccessibleRole.GROUP` and a name. Override `defaultAccessibleRole()`,
`defaultAccessibleName()`, `getAccessibleValue()` and `getAccessibleChecked()` in your own controls, and call
`notifyAccessibleChange()` when the keyboard changes the control's value so the new one is spoken.

What is read: the name, the role, the value, the state, then the description: "Fullscreen, check box, not checked",
"Player name, edit text, Alice", "Volume, slider, 50". A password field says nothing of what is typed. To translate
the words, or change them:

```java
speech.getNarrator().setWord("role.button", "Schaltfläche");
speech.getNarrator().setWord("state.disabled", "nicht verfügbar");
```

`Narrator` documents the keys.

## The tree

`AccessibilityTree.snapshot(window)` reads the window into a tree of `AccessibleNode`s, each with the element, role, name,
description, value, checked state, whether it is disabled or focused, its bounds and its children. Layout boxes with no
role or name are left out and their children kept; with a popup or modal open the tree holds only that. This is the
shape an operating system's accessibility API wants, and what a native backend will be built from.

## Keyboard use

Tab and Shift+Tab walk the controls in the order they were added (see [Tab order](input-and-focus.md#tab-order)), and
scroll a `ScrollContainer` (`scrollIntoView`) so the focused control is never out of sight. A gamepad's bumpers do the
same, and its A button is Enter. These controls leave the rest of the keyboard to the game and
do not take focus from a click, so putting one on a HUD does not break WASD; see
[Controls that leave the keyboard to the game](input-and-focus.md#controls-that-leave-the-keyboard-to-the-game).

A `Window` can have a host of its own (`window.setAccessibilityHost(...)`): the `AccessibilityHost` interface is told when
focus moves, when a value changes and when something is announced. `SpeechHost` is one; a connection to a platform
accessibility API would be another.

## What is missing

- **A connection to screen readers on macOS and Linux.** NSAccessibility and AT-SPI, or AccessKit to cover all three.
  The tree and the host interface above are what it would be built on. Until then, someone who uses VoiceOver or Orca
  hears nothing from a RenGL window unless speech is turned on. On Windows the connection exists but was checked with
  UI Automation client code, not with the screen readers themselves, and it does not describe the rows of lists, trees
  and tables yet.
- **Controls that are not reachable by keyboard yet.** The accessible roles and names cover text, buttons, links, check
  boxes, radio buttons, switches, sliders, text fields and areas, drop-downs, combo boxes, spinners, progress bars and
  images. The containers that hold lists (`ListBox`, `TreeView`, `TableView`, `TabContainer`, `AccordionContainer`) already
  take the keyboard, but do not yet describe their rows, tabs or sections to the tree. Knobs, range sliders, color and
  date pickers and the game widgets are reachable but unnamed.
- **Typing is not echoed**, and moving the caret in a text field is not read.
- **A gamepad's D-pad** does not move between controls (the bumpers do).
- **macOS and Linux speech** was written from the tools' documentation and has not been run. Windows was.
