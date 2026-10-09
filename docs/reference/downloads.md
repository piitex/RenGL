# Downloads

[Documentation](../README.md) › [Reference](README.md)

RenGL can download files over HTTP(S) on background threads and show the progress in several ways. The work is
split in two so you can mix them freely:

| Layer | Classes (package) | Job |
|---|---|---|
| Engine | `Download`, `DownloadManager`, `DownloadListener`, `DownloadState` (`me.piitex.engine.io.download`) | Do the transfer. No UI. |
| Display | `DownloadTextOverlay`, `DownloadBarContainer`, `DownloadListContainer`, `DownloadFormat` (`me.piitex.engine.ui.download`) | Show a `Download` or a `DownloadManager`. |

The displays only **read** the download once per frame, so nothing needs wiring and no callback ever has to reach
the render thread. If none of the displays fit, write your own from the getters (see below).

## Download

```java
Download d = new Download(URI.create("https://example.com/backend.zip"), dir.resolve("backend.zip"));
d.setExpectedSha256("9f86d0..."); // optional: verified before the file appears at its final path
d.setHeader("Authorization", "Bearer ...");
d.start();
```

- Bytes go to `backend.zip.part` and are moved to the target only when complete (and verified).
- `pause()` keeps the `.part` file, and `start()` resumes it with an HTTP `Range` request. If the server ignores
  `Range`, the file restarts from zero. `cancel()` deletes the partial file.
- A dropped connection is retried with backoff, resuming where it stopped (`setMaxRetries`, default 3; the count
  resets whenever an attempt made progress).
- `setExpectedSize(bytes)` gives a determinate bar when the server sends no `Content-Length`.
- `setDisplayName(name)` sets the name the displays show. It defaults to the target's file name.

### Speed limit

```java
d.setMaxBytesPerSecond(500_000);   // about 500 kB/s; 0 removes the cap
manager.setMaxBytesPerSecond(2_000_000);   // everything the manager runs, together
```

The cap holds on average, not as a ceiling on every instant: a transfer that has been idle gets its first read at once.
It takes effect immediately, even in the middle of a download, and a pause or cancel is never kept waiting by it. A
download's own cap and its manager's both apply; the slower wins. With several segments the cap is for all of them
together.

### Segments

```java
d.setSegments(4);   // up to 16 connections, each fetching its own part of the file
```

Some servers limit each connection, and several at once can use the whole link. The download first asks the server for
the first byte of the file with a `Range` request; if it answers `206` with the file's size, and the file is big enough to
give every connection at least 256 KiB, the file is cut into equal parts fetched together. If not, it quietly uses one
connection as before, so setting it is always safe.

Each part keeps its progress in its own file (`backend.zip.part`, `backend.zip.part.1`, ...). `pause()` keeps them,
`start()` carries on where each stopped, `cancel()` deletes them, and when all are complete they are joined into
the `.part` and checked and moved as usual. A part that fails is retried the whole way a single connection would be
(`setMaxRetries`); one that fails for good fails the download. A file you started with segments can be resumed with a
different number, or with none: the first part is always a plain prefix of the file.

### Proxy

```java
d.setProxy("proxy.corp.example", 8080);                 // this download
d.setProxyLogin("user", "secret");                      // if the proxy asks
Download.setDefaultProxy(ProxySelector.of(new InetSocketAddress("proxy.corp.example", 8080))); // all of them
```

By default a download uses the JVM's own proxy settings (`-Dhttps.proxyHost` and friends). `setProxy(ProxySelector)` takes
any selector, such as one that picks by address. Basic login to a proxy for an HTTPS address is switched off by Java
unless the program starts with `-Djdk.http.auth.tunneling.disabledSchemes=""`.

Getters, all safe from any thread: `getState()`, `getProgress()` (0..1), `getDownloadedBytes()`,
`getTotalBytes()` (-1 unknown), `isSizeKnown()`, `getBytesPerSecond()` (smoothed; 0 when stalled),
`getEtaSeconds()` (-1 unknown), `getError()`.

`DownloadListener` (`onStateChanged`, `onComplete`, `onFailed`) can be added any number of times with
`addListener`, and taken off with `removeListener`. **Its callbacks
run on the worker thread**, so set a flag and act on it from an `onUpdate` hook instead of touching UI elements.

## DownloadManager

Runs at most N downloads at once and starts the next as each finishes:

```java
DownloadManager manager = new DownloadManager(2);
manager.add(url1, dir.resolve("a.zip"));
manager.add(url2, dir.resolve("b.zip"));
manager.getTotalBytesPerSecond(); manager.getOverallProgress(); manager.isBusy();
manager.pauseAll(); manager.resume(download); manager.clearFinished();
manager.cancelAll(); // cancels everything not already finished
manager.remove(download); // stops tracking it, cancelling it first if it is still running
manager.setMaxConcurrent(4); // change the limit later
```

Use `manager.resume(d)` rather than `d.start()` for a managed download so the limit is respected. `getDownloads()`
returns a snapshot in the order added, and `getActiveCount()` how many are running.

## Displays

**One line of text** (a status bar, a footer):

```java
layout.addElement(new DownloadTextOverlay(download));
// backend.zip 45% - 18.0 MB / 40.0 MB - 3.2 MB/s - ETA 6s

DownloadTextOverlay line = new DownloadTextOverlay(download, 14f, Color.GRAY); // font size and color
line.setShowName(false); // drop the leading file name
line.setFormatter(d -> "Fetching " + Math.round(d.getProgress() * 100) + "%"); // your own text; null = built-in
```

**A card** with the name, a progress bar, a status line and **Pause / Resume / Cancel** buttons. The bar sweeps
while the size is unknown. While downloading it is a moving green and light-gray striped bar (`card.setStripesEnabled(false)` for
a solid one, `card.setStripesAnimated(false)` to freeze them, `card.getStripeStyle()` for color, width and speed); it settles to solid green when completed and turns red or orange when failed or paused. Pause keeps the partial
file, Resume continues it, Cancel deletes it and offers Retry:

```java
DownloadBarContainer card = new DownloadBarContainer(download, 380);
card.getFormat().setShowEta(false); // choose which parts the status line shows
card.setShowControls(false); // hide the buttons
card.setIconButtons(true); // pause, play, retry and cross icons instead of the words
card.setManager(manager); // if managed: Resume/Retry go back through the queue
card.getBar().getStyling().setCornerRadius(5f);
card.setActiveColor(Color.BLUE); // fill while downloading, under the stripes (default green); any paint
card.setStripeColor(Color.WHITE); // stripe color (default light gray)
card.setDoneColor(Color.GREEN); card.setFailedColor(Color.RED); card.setPausedColor(Color.ORANGE);
```

A file name too long for the card ends with an ellipsis in the middle, so its extension stays.

`setStripeStyle(style)` swaps in a shared [`StripeStyle`](../overlays/progress-bar-overlay.md) so several cards stripe
in step. `setPadding` and `setSpacing` resize the card's rows.

**A live list** bound to a manager: a card per download plus a summary line, added and removed automatically.
Put it in a `ScrollContainer`; it sizes its own height to fit:

```java
DownloadListContainer list = new DownloadListContainer(manager, 400);
list.setShowSummary(false); // hide the summary line (default shown)
list.setCardCustomizer(card -> card.setShowControls(false)); // runs once per new card
scroll.addElement(list);
```

`DownloadListContainer(manager, width, height)` gives it a fixed height instead; `setAutoHeight(false)` does the
same for an existing one.

`DownloadFormat` controls the status text (percent, size, speed, ETA, separator) and has static helpers
`bytes()`, `speed()` and `duration()` for your own text.

A complete example is `me.piitex.examples.demo.DownloadDemo`, which downloads a llama.cpp release build into
`run/downloads/`.

## Building your own display

Poll a `Download` from an `update` override or an `onUpdate` hook, and set your own widgets from the getters:

```java
bar.setProgress(d.getProgress());
label.setText(DownloadFormat.bytes(d.getDownloadedBytes()) + " of " + DownloadFormat.bytes(d.getTotalBytes()));
```

## Limitations

- HTTP(S) only. A download is a single connection unless you ask for [segments](#segments), and then only where the
  server answers `Range` requests.
- The bandwidth limit is an average. It paces reads, so a very low limit with a fast link looks stop-and-go in the
  speed readout.
- A proxy needing anything but Basic login (NTLM, Kerberos) is not set up by `setProxyLogin`; supply your own
  `ProxySelector` and set the JVM's authenticator.
