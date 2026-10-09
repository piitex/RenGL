# Browser

[Documentation](../README.md) › [Reference](README.md)

`me.piitex.engine.utils.Browser` opens a web address in the operating system's **default browser**. It needs no AWT
and no setup, so it works from a button, a menu item or any other event. For text that does this when clicked, use
[`LinkOverlay`](../overlays/link-overlay.md).

```java
Browser.open("https://example.com/docs");
Browser.open("example.com");                 // no scheme: treated as https://example.com
Browser.open("mailto:support@example.com");  // opens the default mail client

button.onMouseClick(event -> Browser.open("https://github.com/piitex/RenGL"));
```

## API

| Member | Description |
|---|---|
| `static boolean open(String url)` | Opens `url` in the default browser. Returns `true` if the OS opener was launched, `false` if the address was refused or there is no opener. |

That is the whole API; the class has no instances.

## What it accepts

Only `http`, `https` and `mailto` addresses are opened.

| Input | Result |
|---|---|
| `https://example.com/a?b=c` | Opened as given |
| `example.com`, `example.com/docs` | No `:` anywhere, so `https://` is put in front |
| `mailto:someone@example.com` | Opened (a `mailto` address needs no host) |
| `HTTP://Example.com` | Opened; the scheme is not case sensitive |
| Leading or trailing spaces | Trimmed |
| `file:///etc/passwd`, `javascript:...`, `ftp://...`, a custom scheme | Refused, logged as a warning |
| `https://` with no host, `null`, an empty string, text that is not a valid URI | Refused |

The scheme check is there because the text of a link often comes from outside the application, such as a save file, a
chat message or a web response. Handing that to the OS opener unchecked could launch a program or open a local file.
If you need to open a local file or folder, use the platform's own facilities rather than this class.

## How it opens the browser

| Platform | Command |
|---|---|
| macOS | `open <url>` |
| Windows | `rundll32 url.dll,FileProtocolHandler <url>` |
| Linux | `xdg-open <url>` |
| Anything else | Nothing is launched; `open` returns `false` |

The command is started and not waited for, so `open` returns straight away and never blocks the render thread.
Because of that, `true` only means the opener started. It cannot know whether a browser then loaded the page, for
example when `xdg-open` is missing a handler on a minimal Linux install. A headless server has no browser to open.

## Things to know

- Every refusal and launch failure is logged on the `Engine` logger at warning level, with the reason, so a link that
  does nothing can be diagnosed from the log.
- Nothing is opened until `open` is called. Constructing a `LinkOverlay` or setting its URL does not touch the browser.
- Do not pass an address the user typed without showing it to them first. The page that opens is outside your
  application.
