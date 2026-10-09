# LinkOverlay

[Documentation](../README.md) › [Overlays](README.md)

`me.piitex.engine.ui.overlays.LinkOverlay` is underlined text that **opens a web address in the operating system's
default browser** when clicked. It extends [`TextOverlay`](text-overlay.md), so the font and text-color API is inherited.

```java
layout.addElement(new LinkOverlay("RenGL on GitHub", "https://github.com/piitex/RenGL"));
```

## Constructors

| Constructor | Notes |
|---|---|
| `LinkOverlay(String url)` | Shows the address itself |
| `LinkOverlay(String text, String url)` | Blue, 16pt |
| `LinkOverlay(String text, String url, float fontSize, Paint textColor)` | |

## Behaviour

- The cursor becomes a pointer on hover, and the text changes to `getStyling().setTextHoverColor(...)` while hovered, if you set one.
- The underline is drawn in the text color when that is a plain `Color`; with a gradient there is no underline.
- `setUrl(String)` changes the target, and `open()` opens it from code, as a click does. It returns `false` if the
  address was refused or no opener could be started.
- The overlay owns its click listener, like a button. Calling `onMouseClick(...)` replaces it and the link stops opening.

## Opening an address without a link

`me.piitex.engine.utils.Browser.open(String url)` does the same from anywhere, for a button or a menu item. See
[Browser](../reference/browser.md) for what it accepts and refuses:

```java
Browser.open("https://example.com/docs");
Browser.open("example.com");            // no scheme: treated as https://example.com
```

It runs `open` on macOS, `rundll32` on Windows and `xdg-open` on Linux, so it needs no AWT. It returns `true` once the
opener has been launched; it cannot tell whether the page then loaded.

Only `http`, `https` and `mailto` addresses are opened. Anything else (a `file:` path, a custom scheme) is logged and
refused, since the text of a link often comes from outside the application and the OS opener would otherwise launch
whatever it was given.
