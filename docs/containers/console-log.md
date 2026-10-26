# ConsoleLog

[Documentation](../README.md) › [Containers](README.md)

`me.piitex.engine.ui.containers.ConsoleLog` is a scrolling log of text with a command line under it: a game's developer console, a chat window,
a build log.

```java
ConsoleLog console = new ConsoleLog(520, 260);
console.onCommand(line -> {
    console.info("Ran " + line);
    run(line);
});
console.setCompleter(prefix -> commands.stream().filter(c -> c.startsWith(prefix)).toList());
console.warn("Low on memory");
console.chat("Ana", "anyone seen the key?", Color.CYAN);
```

## Writing

| Call | Line |
|---|---|
| `println(text)`, `info(text)` | Plain, in the text color |
| `debug(text)` | Dimmed |
| `warn(text)`, `error(text)` | Yellowish and red; `setLevelColor(level, paint)` changes them |
| `print(text, color)` | In a color of your own |
| `chat(sender, message, senderColor)` | `sender: message`, the sender colored (the accent when `null`) |

It can be called from any thread. Long lines wrap to the width (a word wider than the row is broken), and the oldest lines go past
`setMaxLines` (1000 by default). `setMinLevel(Level.WARN)` hides quieter lines without discarding them. `setShowTimestamps(true)` starts each line
with its time, in the format of `setTimestampFormat`. `clear()` empties it; `getText()` returns what is kept.

## Scrolling

The log sticks to the newest line. The wheel, Page Up and Page Down scroll back; while scrolled back, new lines do **not** pull the view away.
Ctrl+End or `scrollToBottom()` returns to the newest, and `isAtBottom()` says whether it is following.

## The command line

The field under the log is a text field (`getInput()` returns it).

| Key | What happens |
|---|---|
| Enter | The trimmed text goes to `onCommand`, is echoed as `> command` (`setEchoCommands(false)` turns that off), and the field empties |
| Up, Down | Walk through the commands already entered, back to the text you had typed; repeats of the last command are not added |
| Tab | With a completer: one match fills it in; several fill in what they share and are listed in the log |

`setInputVisible(false)` hides the field for a read-only log. `focusInput()` gives it the keyboard (a click on the log does too), and
`setPlaceholder(text)` changes the hint in the empty field.

## Look

It follows the theme like a container that [uses the theme](container.md). `setTextColor`, `setAccentColor` (the echo and chat senders) and
`setFontSize` change it. Call `dispose()` when permanently discarding one.

Text is not selectable or copyable by the player yet; read it from code with `getText()`.
