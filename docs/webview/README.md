# Web views

[Documentation](../README.md) › Web views

> Needs the `rengl-webview` [module](../modules.md). Runs on **Windows** (WebView2) and, through an installed Chrome,
> Edge, Chromium or Brave, on **Windows, macOS and Linux**. Engines that need no installed browser, **WKWebView** (macOS)
> and **WebKitGTK** (Linux), are written but **have never been run**, so they are opt-in. See [Engines](#engines).

A `WebViewOverlay` shows a web page inside the element's bounds. Put it in a container like any other element. It is
meant for two jobs: a **login portal** inside your own UI, where an account service's page has to hand your program a
result, and an **embedded browser** pane.

```java
WebViewOverlay web = new WebViewOverlay("https://example.com", 900, 600);
container.addElement(web);
```

Clicking it gives it the keyboard. After that typing, the mouse (hover, every button, drags), the wheel and an input
method (Chinese, Japanese, Korean) all reach the page, and it can show the cursor the page asks for. Tab moves between
the page's own fields; click outside it to leave. The `WebViewDemo` in `rengl-examples` shows both jobs side by side.

| Class | What it is |
|---|---|
| `WebViewOverlay` | The element. Navigation, script, listeners, `dispose()` |
| `WebViewOptions` | How a view starts: drawing mode, picture quality, profile folder, allowed hosts, permissions, the script bridge |
| `LoginFlow` | Reading a sign-in redirect, `state` and PKCE helpers |
| `SystemBrowserLogin` | Sign-in through the user's own browser, for providers that refuse embedded ones |
| `WebViews` | `isAvailable()`, `describe()` and `resolve()`: whether this machine can show pages, which engine, and why not |
| `WebViewBackend` | One browser engine as the overlay uses it. Implement it for another engine |

## Engines

Four engines are built in, chosen with `WebViewOptions.setEngine`. The default, `AUTO`, uses WebView2 where it is
available and an installed browser otherwise. **`AUTO` never picks `WKWEBVIEW` or `WEBKITGTK`**: they have not been run
on a real machine, so a program asks for them by name until someone has.

| | `WEBVIEW2` | `CHROMIUM` | `WKWEBVIEW` | `WEBKITGTK` |
|---|---|---|---|---|
| Systems | Windows 10 and 11 | Windows, macOS, Linux | macOS | Linux (X11 or Wayland) |
| Needs | The Evergreen WebView2 runtime, which Windows 11 and current Windows 10 have and Edge keeps up to date | Google Chrome, Microsoft Edge, Chromium, Brave or Vivaldi, a current version (run against Edge 156; the headless mode and the DevTools calls it uses are recent) | Nothing: WebKit is part of macOS (10.13 or newer for snapshots) | WebKitGTK 4.1 or 4.0 (GTK 3) from the distribution, and a graphical session |
| How | The runtime's library, called through Java's foreign-function API | The browser is started headless and driven over its DevTools protocol; no native code of ours | The Objective-C runtime and AppKit, called through the foreign-function API | GTK, GLib, Cairo and WebKitGTK, called through the foreign-function API |
| `NATIVE` mode | Yes | No: a request for it is drawn as `PIXELS` | No: drawn as `PIXELS` | No: drawn as `PIXELS` |
| Downloads | The runtime's default | Refused | Refused | Refused |
| Run on a real machine | Windows | Windows (Edge 156) | **No** | **No** |

On a machine with neither, `WebViews.isAvailable()` is false, `WebViews.describe()` says why and what to install, and an
overlay goes to `State.FAILED` and calls its `onFailed` listener instead of throwing. `WebViews.resolve(options)` says
which engine would run.

The `CHROMIUM` engine looks for a browser in the usual places (`Program Files` on Windows, `/Applications` on macOS,
the `PATH` and `/usr/bin` on Linux). To name one, use `WebViewOptions.setBrowserPath` or the `RENGL_BROWSER`
environment variable. It runs the browser with a temporary profile that is deleted on close, so it never touches the
user's own browser or their signed-in sessions.

A machine with none of these (a Linux box without WebKitGTK or a Chromium-based browser) can still use
`SystemBrowserLogin` for sign-in, which needs nothing, and for the rest tell the user what to install (`describe()`).

Run with `--enable-native-access=ALL-UNNAMED` when WebView2, WKWebView or WebKitGTK is used, like the other modules that
use the foreign-function API.

### WKWebView and WebKitGTK

```java
WebViewOptions options = new WebViewOptions().setEngine(WebViewOptions.Engine.WKWEBVIEW);   // macOS
WebViewOptions options = new WebViewOptions().setEngine(WebViewOptions.Engine.WEBKITGTK);   // Linux
```

Both are the system's own WebKit, so an application ships nothing and needs no browser on the machine. Both draw
`PIXELS` only. They share their logic (`webkit.WebKitBackend`: navigation rules, picture pacing, the page's address and
title, the cursor, how keys and text reach the page) and differ only in the calls into the toolkit, so most of what is
said about the other engines above holds for them too: `allowHost`, `onRedirect`, the script bridge, permissions all
refused by default, a fresh profile that keeps nothing.

What is different:

- **Pictures are lossless snapshots**, so `Quality` makes no difference. On a Retina display the picture has twice the
  pixels of the element and is scaled down to it, which makes the page sharper.
- **A page's own `<select>` lists, dialogs and context menus** are not shown (a script dialog is dismissed: an alert is
  closed, a confirm answers no, leaving a page is allowed).
- **Typed characters arrive as key presses carrying the character** (so the page sees `keydown` and the text goes in
  through the system's own input path, whatever the keyboard layout), and keys that type nothing (Enter, arrows,
  Backspace) as key events. Ctrl or Cmd with A, C, X, V, Z or Y runs that editing command on the page directly.
- **Subframes follow `allowHost` too.** A page that embeds a frame from another host (a captcha) has that frame refused.
- **Only `http`, `https`, `about:blank`, `data:` and `blob:` addresses load**; `mailto:` and an app's own scheme never
  do (the application's `onRedirect` still sees them first).
- **macOS:** a profile folder cannot be named. A view with no profile keeps nothing (a non-persistent store); with
  `setProfile` it gets a store keyed by the folder's path on macOS 14 and newer, and the app's shared default store
  before that. The work is queued onto the **main thread**, so the main thread's run loop has to be running, which a
  program with a window has (GLFW's event polling is it, with `-XstartOnFirstThread`); see [Lifetime](#lifetime).
- **Linux:** one thread runs GTK for every view in the process. Input method pre-edit text cannot be fed to WebKitGTK, so
  `composition` does nothing there; committed text arrives as typed characters. Accelerated compositing is switched off
  for the process (`WEBKIT_DISABLE_COMPOSITING_MODE`), since pictures are painted by the web process and no window shows them.
  GTK 4 builds (`webkitgtk-6.0`) and WPE are not supported.

#### Written blind: what has not been checked

These two engines were written without a Mac or a Linux desktop, from the platforms' documentation and WebKit's source,
the same way the Linux video decoder was. **No line of the code that calls AppKit, GTK or WebKit has run.** What *has*
been checked, on Windows:

- It compiles, and the shared logic (`webkit/`) runs under 33 tests against a fake toolkit: navigation rules, picture
  pacing, address/title/history reporting, key routing, the bridge, closing.
- The key and mouse tables (`MacKeys`, `GdkKeys`, `UsLayout`, `KeyRouting`) are unit tested.
- The Objective-C classes and the GTK callbacks are Java methods behind C function pointers, and the foreign-function API
  checks each against its descriptor when the pointer is made: that is tested, with the selectors' argument counts and
  type encodings.
- The scripts the two engines inject (JSON wrapper, `chrome.webview` bridge) are plain JavaScript and run in Edge under test.
- The self-check program (below) runs all of its 19 steps green against the Chromium and WebView2 engines, which proves
  the check itself (it found three mistakes of its own that way).

What is **untested**, in the order to look at it if a step fails on a real machine:

*macOS*

1. **Loading and the main thread.** The framework paths, `dispatch_async_f` with `_dispatch_main_q`, and whether anything
   runs the main thread's loop. Nothing happens, and the view never becomes `READY`, if the main thread is not pumped.
2. **The classes defined at run time** (`RenGLWebViewDelegate`, `RenGLWebViewWindow`) and the **block ABI** (a "global"
   block built by hand: its flags, its descriptor size 32, the pointer to `_NSConcreteGlobalBlock`). A wrong block would
   crash in WebKit the first time it calls a completion handler.
3. **Whether the view paints at all.** It sits in a borderless, fully transparent, click-through window ordered behind
   everything at the screen's bottom-left corner. If WebKit decides an invisible window is occluded, snapshots come back
   blank or never come; the fallbacks to try are a window at a visible position, or alpha 0.01.
4. **The snapshot to pixels path**: `takeSnapshotWithConfiguration:`, `NSImage` to `CGImage` to a BGRA bitmap (colour space,
   `kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little`).
5. **Input.** Events are handed straight to the view (`mouseDown:`, `keyDown:` ...) and it is assumed `WKWebView`
   implements those; if clicks do nothing, deliver through `[window sendEvent:]` instead. Scroll goes through a
   CoreGraphics event and relies on the window's corner being the screen's corner so that its position is read right;
   the sign of horizontal scrolling is a guess. `insertText:` and `setMarkedText:` are assumed to exist on the view.
6. **Focus.** The window answers `isKeyWindow` itself and posts the key notifications so the page believes it has the
   keyboard while the engine's own window really has it. If the page shows no caret or `:focus` style, this is why.
7. Navigation callbacks (redirects, `window.open`, a link with a target), the `WKUserScript` bridge and its
   `frameInfo.request` source check, camera/microphone refusal (macOS 12 and newer), the macOS 14 persistent store.

*Linux*

1. **Library names.** `libwebkit2gtk-4.1.so.0`, then `libwebkit2gtk-4.0.so.37`, with GTK 3. A distribution that ships
   only GTK 4 WebKit has no engine here.
2. **GTK on its own thread** with a display (`gtk_init_check`), and sharing the process with GLFW's own connection. A
   program that also uses GTK itself (some file dialogs) would fight over the default main context.
3. **The web process.** WebKitGTK sandboxes it with bubblewrap, which fails in a container without user namespaces;
   `WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1` turns the sandbox off and is deliberately not set for you.
4. **Snapshots** of a view in a `GtkOffscreenWindow`, and the Cairo surface format (ARGB32 and RGB24 are both handled).
5. **The `GdkEvent` structures**, whose field offsets are written out by hand (public ABI, 64-bit only), the references
   the events hold on their window and device, `gtk_widget_event` as the way in, smooth-scroll delta signs, click
   counting (left to WebKit) and the focus event.
6. The shapes of the signal handlers (`decide-policy`, `create`, `context-menu` with its event argument,
   `permission-request`, `script-dialog`), the variadic call that makes a persistent data manager, and
   `webkit_web_view_evaluate_javascript` (2.40) against the older `run_javascript`.

#### Running the check

A person with a Mac or a Linux desktop can find out in a minute. Nothing touches the real mouse or keyboard; input is
handed to the engine directly, and a picture of what it drew is saved.

```bash
./mvnw -pl rengl-webview -am install -DskipTests
./mvnw -pl rengl-webview dependency:build-classpath -Dmdep.outputFile=webview-cp.txt
```

```bash
java --enable-native-access=ALL-UNNAMED -cp "rengl-webview/target/classes:$(cat webview-cp.txt)" me.piitex.engine.webview.WebViewSelfCheck
```

It picks `WKWEBVIEW` on a Mac and `WEBKITGTK` on Linux (`--engine chromium|webview2|auto` for the others; `--online`
also loads `https://example.com/`; `--out folder` for the pictures). It prints one `PASS`/`FAIL` line per step: start,
page and title, a lossless picture with the right colours, script results, click, typing, Backspace/arrows/select-all,
shifted characters, hover cursor, wheel, right button, double click, the bridge both ways, a resize, a second view at the
same time, `window.open`, the picture rate on an animated page, an idle page going quiet, and closing. Exit code 0 means
all passed, 1 some failed, 2 the engine is not available. Send the output and the `.png` files back.

The same contract the other engines pass is a test class for each (`WKWebViewBackendTest`, `WebKitGtkBackendTest`), run
by `./mvnw -pl rengl-webview -am test` and skipped where the engine is absent (on a Mac the test JVM runs with
`-XstartOnFirstThread` and the test lends the main thread to the engine while it waits). `WebViewDemo --wkwebview` and
`--webkitgtk` try them in a real window.

Once a step list passes on a real machine, the engine can be made the answer of `AUTO` on that system.

## Two ways to draw

Chosen with `WebViewOptions.setMode`.

| | `PIXELS` (default) | `NATIVE` |
|---|---|---|
| How | The browser draws off screen; the engine draws its pictures like an image | The browser draws itself in a child window over yours |
| Speed | 10 to 25 pictures a second: fine for forms and pages, not smooth for scrolling, poor for video or animation | The screen's own speed |
| Layering | An ordinary element: under a menu, clipped by a scroll container, faded, rounded corners | Always on top of everything the engine draws, square, no clipping or fading |
| Good for | **Login portals**, dialogs, anything that overlaps your UI | **A browser pane** |

Pixel mode takes a picture with `CapturePreview`, which costs 40 ms or more whatever the size, so it only takes them
while something is changing: back to back while the page moves (lossy, JPEG), once more losslessly (PNG) when it
settles, then a cheap check a couple of times a second. `Quality.CRISP` makes every picture lossless and `Quality.SMOOTH`
every picture lossy. A page that is not drawn (removed from its container, or its container hidden) stops costing
anything.

Native mode needs the window's handle (`Window.getNativeHandle()`), so the element has to be in an open window. Hiding
it hides the child window. It cannot be partly covered by an element of yours. Only WebView2 can draw it; with the
other engine (`CHROMIUM`, `WKWEBVIEW`, `WEBKITGTK`) a native view is drawn as `PIXELS` and a line in the log says so.

## A login portal

A portal is a page you do not control that has to give your program a result. Sign-in with OAuth 2.0 / OpenID Connect
or SAML always ends the same way: the provider redirects to an address you chose, with a code or token in it. A web view
can **stop at that address without loading it**, so no web server is needed.

```java
String state = LoginFlow.randomState();
String verifier = LoginFlow.pkceVerifier();

WebViewOverlay login = new WebViewOverlay(new WebViewOptions().allowHost("login.example.com"), 420, 560);
LoginFlow.embedded(login, "mygame://signed-in", authorizeUrl(state, LoginFlow.pkceChallenge(verifier)), result -> {
    if (result.isError() || !result.stateMatches(state)) return;
    exchangeForToken(result.get("code"), verifier);   // your call to the provider's token endpoint
    login.dispose();
});
container.addElement(login);
```

What makes it safe, and what is yours to do:

- **The defaults are the safe ones.** A view keeps nothing once it closes (a fresh profile folder, deleted afterwards),
  asks the user for nothing (every permission request is refused unless `allow(...)` names it), shows no browser menu, no
  developer tools and no script dialogs, and the page cannot call into your code (the script bridge is off).
- **Restrict where it can go** with `allowHost`. A navigation to any other host is refused and reported to `onBlocked`.
  Matching is by host name (`example.com` allows `login.example.com`, not `evilexample.com`; text before an `@` is not the
  host), and `file:` pages are never shown. Your own redirect address (`onRedirect`) is checked first, so it needs no
  allowance.
- **Show the user the real host** (`getUrl()`, `onUrlChanged`), so they can tell the portal is genuine. A web view has no
  address bar of its own.
- **Use the code flow with PKCE and check `state`.** `LoginFlow` makes both and compares in constant time. Do not use
  the implicit flow.
- **A new window does not open.** `window.open` and links meant for a new tab load in the same view (or nowhere, with
  `setNewWindows(BLOCK)`), and `onNewWindow` hears of them. A sign-in that depends on a popup talking to the page that
  opened it will not work; use the redirect form.
- **Some providers refuse embedded browsers**, Google among them, and block the sign-in. For those use
  `SystemBrowserLogin`: it opens the user's own browser, listens on a loopback address for the redirect, and completes
  `result()`. It needs the code flow, because a fragment never reaches a server.
- **Downloads are not intercepted yet.** WebView2's own default behaviour applies to a page that starts one. Allow only
  hosts you trust.

To keep a user signed in between runs, `setProfile(folder)`. Views that share a folder share their cookies and logins.

## Talking to the page

```java
web.evaluate("document.title", json -> ...);     // the result is JSON; a string comes back quoted
web.loadHtml("<h1>Hello</h1>");                   // a page of your own
web.onUrlChanged(url -> ...);  web.onTitleChanged(...);  web.onLoading(busy -> ...);
web.setNavigationFilter(url -> !url.contains("logout"));   // runs on the browser's thread: keep it quick
```

With `setScriptBridge(true)` the page can send messages (`chrome.webview.postMessage(obj)`, received by
`onMessage` as JSON) and receive them (`web.postMessage(json)`, heard with
`chrome.webview.addEventListener('message', ...)`). Messages from a host `allowHost` does not allow are dropped. Leave it
off for a page you do not own.

All listeners run on the render thread, in the frame after the event; only `setNavigationFilter` runs on the browser's
own thread.

## Lifetime

The browser starts at the first update after the element is in a window, with a thread and a browser process of its own,
and keeps them until `dispose()`. Removing the overlay from its container hides it and pauses pictures but keeps the
page; letting it go without `dispose()` leaves the browser running until the program exits. Commands given before it is
ready (`load`, ...) are carried out when it is.

With `WKWEBVIEW` the browser's work is queued onto the macOS **main thread** and runs only while that thread's run loop
runs. A game's window loop is that (GLFW, with `-XstartOnFirstThread`). A program with no loop, such as a unit test run
under `-XstartOnFirstThread`, calls `WKWebViewBackend.pumpMainThread(millis)` from the main thread while it waits, or
the view never starts. Without `-XstartOnFirstThread` the JVM's launcher already runs the loop.

## Input for your own elements

Showing someone else's interface needs more of the mouse than a click, so core has `PointerInput`
([input and focus](../input/input-and-focus.md#pointerinput)): hover with no button held, every button, a drag that keeps
going outside the element, and the wheel with its position and modifiers. `WebViewOverlay` is built on it, and so can
your own element (a remote desktop, an emulator).

## Limits

- The engines that need no installed browser, WKWebView and WebKitGTK, exist but have never been run (see
  [Engines](#written-blind-what-has-not-been-checked)), so `AUTO` on macOS and Linux still needs an installed
  Chromium-based browser. The engines are behind `WebViewBackend`, so another can be added without changing the overlay.
- Pixel mode with WebView2 is 10 to 25 pictures a second (see above); with the `CHROMIUM` engine the browser sends a
  picture each time the page changes (its frame rate has not been measured). Pictures are of the visible part of the
  page at the element's size.
- `CHROMIUM` keeps the browser's debugging port open on the loopback address while a view lives. Another program on the
  same machine that finds the port can drive the browser, and so read what a signed-in page holds. A sign-in that has to
  withstand a hostile program on the user's machine should use `SystemBrowserLogin`.
- `CHROMIUM` on Linux: a browser installed as a snap cannot read the temporary profile folder, so the plain package is
  preferred and a snap is the last choice. Run as root (a container), the browser's sandbox is turned off.
- `CHROMIUM`: the page's own `<select>` lists and date pickers draw as part of the picture; the browser's context menu
  does not exist; file pickers and downloads are refused.
- The input method's candidate window opens at the bottom left of the element, not at the page's caret.
- Pixel mode may not show the browser's own popup windows (a `<select>` list, a date picker), and shows no context menu.
- On a display scaled above 100% the page is told its pixel ratio is 1, so it is laid out at the element's pixel size
  rather than sharper. (Written from the specification; not run on a scaled display.)
- Native mode: after a click in the page, the engine is told the element has the keyboard through WebView2's focus
  events, and clicking another element hands the keyboard back. (Not driven with a real mouse.)
- The runtime's `CreateWebViewEnvironmentWithOptionsInternal` export is what Microsoft's loader calls, but it is not a
  documented interface. If a future runtime changes it, `WebViews.isAvailable()` stays true and the view fails to start
  with a message; the answer is a loader-based start.

## Troubleshooting

| Seen | Cause |
|---|---|
| `State.FAILED` with "runtime is not installed" | Install the Evergreen WebView2 runtime |
| `State.FAILED` with "No Chrome, Edge, Chromium or Brave browser was found" | Install one, or name it with `setBrowserPath` / `RENGL_BROWSER` |
| `State.FAILED` with "The browser exited at start" | The browser refused its command line: an old version, or on Linux a snap or a missing library |
| `State.FAILED` with "did not open its debugging port" | The browser started but did not answer within 25 seconds; try it by hand with `--headless=new --remote-debugging-port=0` |
| `State.FAILED` with a COM error code | The runtime would not start; `getFailure()` has the code |
| `WKWEBVIEW`: the view stays in `State.STARTING` forever | The main thread's run loop is not running (see [Lifetime](#lifetime)) |
| `WKWEBVIEW`: `READY`, but every picture is blank, or none comes | WebKit treats the invisible window as hidden; see the list of what is untested |
| `WEBKITGTK`: "WebKitGTK ... is not installed" | Install `libwebkit2gtk-4.1-0` (Debian, Ubuntu) or `webkit2gtk4.1` (Fedora); the GTK 4 build is not used |
| `WEBKITGTK`: "There is no graphical session" | `DISPLAY` and `WAYLAND_DISPLAY` are both unset; a headless server needs a virtual display such as `xvfb-run` |
| `WEBKITGTK`: `READY` but the page never loads, or "web process" errors in the console | The web process sandbox cannot start (a container without user namespaces); see the list of what is untested |
| A blank element in `NATIVE` mode | The element is not in an open window yet, or its page has not loaded: check `getState()` |
| A sign-in page that says "this browser is not secure" | The provider refuses embedded browsers; use `SystemBrowserLogin` |
| Pages look soft while scrolling | Expected in `AUTO` quality; they sharpen when the page settles |
