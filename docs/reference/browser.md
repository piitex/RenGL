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
| `static boolean open(Path path)` | Opens a file or folder with the program the OS has for it, as double-clicking it would. See [Files and folders](#files-and-folders). |
| `static boolean reveal(Path path)` | Shows a file or folder selected in Finder / Explorer. |

The class has no instances.

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
A local file or folder goes through `open(Path)` or `reveal(Path)` instead, which take a `Path` and not text.

## Files and folders

```java
Browser.open(extracted);    // the .txt in the text editor, the folder in Finder
Browser.reveal(extracted);  // "Show in Finder": the file selected inside its folder
```

Both return `false`, with a warning in the log, if the path does not exist.

`open(Path)` **refuses a program**: a file ending in `.exe`, `.bat`, `.cmd`, `.com`, `.msi`, `.scr`, `.ps1`, `.vbs`,
`.sh`, `.command`, `.jar`, `.lnk`, `.js` and a few more, and anything ending in `.app`. Opening one would run it, and the
path may have come out of an archive. A folder that merely has such a name is fine, except a `.app` bundle. `reveal` has
no such rule, since showing a file in the file manager does not run it.

| Platform | `open(Path)` | `reveal(Path)` |
|---|---|---|
| macOS | `open <path>` | `open -R <path>` |
| Windows | `explorer.exe <folder>` or `rundll32 url.dll,FileProtocolHandler <file>` | `explorer.exe /select, <path>` |
| Linux | `xdg-open <path>` | `xdg-open <parent folder>`: file managers have no common way to select a file |

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
