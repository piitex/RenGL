package me.piitex.engine.webview;

import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.webview.macos.WKWebViewBackend;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * A check a person can run on a Mac or a Linux desktop to see whether a web view engine works there, with no window and
 * no game: it starts the engine, loads a page of its own, and drives the page the way the overlay does (click, type,
 * scroll, hover, resize, script, bridge), printing one line per step and saving a picture of what the engine drew. Nothing
 * touches the real mouse or keyboard: all input is handed to the engine directly.
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -cp rengl-webview.jar:rengl-core.jar:(its dependencies) \
 *      me.piitex.engine.webview.WebViewSelfCheck [--engine wkwebview|webkitgtk|chromium|webview2|auto] [--online] [--out folder]
 * </pre>
 * The default engine is the system's own WebKit on a Mac or a Linux desktop. {@code --online} also loads a real page
 * ({@code https://example.com/}). Exit code 0: every step passed; 1: some failed; 2: the engine is not available here.
 * <p>
 * On a Mac the engine's work runs on the main thread. This check runs its steps on another thread and lends the main one to
 * the engine, which works whether or not the JVM was started with {@code -XstartOnFirstThread}.
 */
public final class WebViewSelfCheck {
    private static final String PAGE = """
            <!doctype html><meta charset=utf-8><title>Self check</title>
            <body style="margin:0;background:#fff">
            <input id=f style="position:absolute;left:20px;top:20px;width:200px;height:30px;font-size:18px">
            <button id=b style="position:absolute;left:20px;top:80px;width:100px;height:40px"
                    onclick="window.clicks=(window.clicks||0)+1">Go</button>
            <div id=h style="position:absolute;left:300px;top:20px;width:100px;height:100px;background:#d22;cursor:pointer"
                 onmouseover="window.hovered=true"></div>
            <div style="position:absolute;left:0;top:200px;height:3000px;width:10px"></div>
            <script>
              window.keys=[]; addEventListener('keydown', e => keys.push(e.key));
              window.wheels=0; addEventListener('wheel', e => wheels++);
              window.rights=0; addEventListener('contextmenu', e => { rights++; e.preventDefault(); });
              window.dbl=0; addEventListener('dblclick', e => dbl++);
              chrome.webview && chrome.webview.addEventListener('message', e => chrome.webview.postMessage({echo: e.data}));
            </script>
            """;

    private static final String ANIMATED = """
            <!doctype html><meta charset=utf-8><title>Animated</title>
            <body style="margin:0"><div id=d style="width:100%;height:100%;position:absolute"></div>
            <script>
              var n = 0;
              (function tick() { document.getElementById('d').style.background = 'hsl(' + (n++ * 7 % 360) + ',80%,50%)'; requestAnimationFrame(tick); })();
            </script>
            """;

    private final WebViewOptions options;
    private final File outFolder;
    private final boolean online;
    private final int width = 640, height = 400;

    private final CopyOnWriteArrayList<WebViewBackend.Frame> frames = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> urls = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CursorMode> cursors = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> title = new AtomicReference<>();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private volatile int completions;
    private volatile boolean ready;
    private WebViewBackend backend;
    private int passed, failed;
    private int pageMark;

    private WebViewSelfCheck(WebViewOptions options, File outFolder, boolean online) {
        this.options = options;
        this.outFolder = outFolder;
        this.online = online;
    }

    private interface Step {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        String engineName = "";
        boolean online = false;
        File out = new File(".");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--engine" -> engineName = i + 1 < args.length ? args[++i] : "";
                case "--online" -> online = true;
                case "--out" -> out = new File(i + 1 < args.length ? args[++i] : ".");
                default -> {
                    System.err.println("Unknown argument " + args[i]);
                    System.exit(64);
                }
            }
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        WebViewOptions.Engine engine = switch (engineName.toLowerCase(Locale.ROOT)) {
            case "wkwebview" -> WebViewOptions.Engine.WKWEBVIEW;
            case "webkitgtk" -> WebViewOptions.Engine.WEBKITGTK;
            case "chromium" -> WebViewOptions.Engine.CHROMIUM;
            case "webview2" -> WebViewOptions.Engine.WEBVIEW2;
            case "auto" -> WebViewOptions.Engine.AUTO;
            default -> os.contains("mac") ? WebViewOptions.Engine.WKWEBVIEW
                    : os.contains("linux") ? WebViewOptions.Engine.WEBKITGTK : WebViewOptions.Engine.AUTO;
        };
        WebViewOptions options = new WebViewOptions().setEngine(engine).setScriptBridge(true);

        System.out.println("RenGL web view self check");
        System.out.println("  system:   " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        System.out.println("  java:     " + System.getProperty("java.version"));
        System.out.println("  engine:   " + engine + " -> " + WebViews.resolve(options));
        System.out.println("  describe: " + WebViews.describe(options));
        if (os.contains("linux")) {
            System.out.println("  display:  DISPLAY=" + System.getenv("DISPLAY") + " WAYLAND_DISPLAY=" + System.getenv("WAYLAND_DISPLAY"));
        }
        if (!WebViews.isAvailable(options)) {
            System.out.println("RESULT: the engine is not available here");
            System.exit(2);
        }
        out.mkdirs();

        WebViewSelfCheck check = new WebViewSelfCheck(options, out, online);
        int[] code = new int[1];
        Thread worker = new Thread(() -> code[0] = check.runAll(), "self-check");
        worker.setDaemon(true);
        worker.start();
        // on a Mac the engine's work is queued onto the main thread: run its loop while the steps run elsewhere
        while (worker.isAlive()) WKWebViewBackend.pumpMainThread(25);
        System.exit(code[0]);
    }

    // ------------------------------------------------------------------------------------------------ steps

    private int runAll() {
        step("starts", () -> {
            backend = WebViews.create(options, new WebViewBackend.Listener() {
                @Override
                public void onReady() {
                    ready = true;
                }

                @Override
                public void onFrame(WebViewBackend.Frame frame) {
                    frames.add(frame);
                }

                @Override
                public void onNavigationCompleted(String url, boolean success, int status) {
                    if (success) completions++;
                }

                @Override
                public void onTitleChanged(String t) {
                    title.set(t);
                }

                @Override
                public void onUrlChanged(String url) {
                    urls.add(url);
                }

                @Override
                public void onMessage(String json) {
                    messages.add(json);
                }

                @Override
                public void onCursor(CursorMode mode) {
                    cursors.add(mode == null ? CursorMode.DEFAULT : mode);
                }

                @Override
                public void onFailed(String reason) {
                    failure.set(reason);
                }
            });
            backend.resize(width, height);
            backend.keepAlive();
            until(() -> ready || failure.get() != null, 30, "the engine to become ready");
            if (failure.get() != null) throw new IllegalStateException("failed to start: " + failure.get());
        });
        if (backend == null || !ready) return finish();

        step("loads a page of its own and reports its title", () -> {
            int before = completions;
            pageMark = frames.size();
            backend.loadHtml(PAGE);
            until(() -> completions > before, 20, "the navigation to complete");
            until(() -> "Self check".equals(title.get()), 5, "the title \"Self check\" (got " + title.get() + ")");
        });
        step("draws the page (a lossless picture, red box and white background where the page put them)", () -> {
            until(() -> frames.stream().skip(pageMark).anyMatch(f -> f.crisp() && f.width() % width == 0), 15, "a lossless picture of the page");
            WebViewBackend.Frame f = frames.stream().skip(pageMark).filter(x -> x.crisp() && x.width() % width == 0).reduce((a, b) -> b).orElseThrow();
            BufferedImage img = image(f);
            save(img, "page");
            int scale = Math.max(1, img.getWidth() / width);
            check(img.getWidth() == width * scale && img.getHeight() == height * scale,
                    "picture is " + img.getWidth() + "x" + img.getHeight() + ", expected " + width + "x" + height + " times a whole display scale");
            int red = img.getRGB(350 * scale, 70 * scale) & 0xFFFFFF, white = img.getRGB(600 * scale, 300 * scale) & 0xFFFFFF;
            check(near(red, 0xDD2222), "the box at (350,70) is " + Integer.toHexString(red) + ", expected about dd2222");
            check(white == 0xFFFFFF, "the background at (600,300) is " + Integer.toHexString(white) + ", expected ffffff");
            System.out.println("          (picture " + img.getWidth() + "x" + img.getHeight() + ", " + (f.encoded() ? "encoded" : "raw BGRA") + ")");
        });
        step("script results come back as JSON", () -> {
            expect("42", eval("6*7"));
            expect("\"a\"", eval("'a'"));
            expect("{\"x\":[1,2]}", eval("({x:[1,2]})"));
            String thrown = eval("throw new Error('x')"); // no result, or JSON null: engines differ
            check(thrown == null || thrown.equals("null"), "a throwing script should give no result, got " + thrown);
        });
        step("a click reaches the page", () -> {
            backend.setFocused(true);
            backend.pointerMove(60, 100, 0, 0);
            backend.pointerButton(60, 100, 0, true, 0);
            backend.pointerButton(60, 100, 0, false, 0);
            untilEval("window.clicks||0", "1", "the button to hear one click");
        });
        step("typing reaches the field (one character per key, none doubled)", () -> {
            click(100, 35);
            type("hi");
            untilEval("document.getElementById('f').value", "\"hi\"", "the field to hold \"hi\"");
            check(eval("keys.join()").contains("h"), "the page should have seen a keydown for h: " + eval("keys.join()"));
        });
        step("Backspace and the arrow keys work, and select all then delete empties the field", () -> {
            key(GLFW.GLFW_KEY_BACKSPACE, 0);
            untilEval("document.getElementById('f').value", "\"h\"", "Backspace to remove one character");
            type("ello");
            untilEval("document.getElementById('f').value", "\"hello\"", "typing to continue");
            key(GLFW.GLFW_KEY_LEFT, 0);
            type("X");
            untilEval("document.getElementById('f').value", "\"hellXo\"", "the caret to have moved left before X");
            backend.key(GLFW.GLFW_KEY_A, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER);
            backend.key(GLFW.GLFW_KEY_A, GLFW.GLFW_RELEASE, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER);
            key(GLFW.GLFW_KEY_BACKSPACE, 0);
            untilEval("document.getElementById('f').value", "\"\"", "select all then Backspace to empty the field");
        });
        step("shifted characters and punctuation arrive as typed", () -> {
            click(100, 35);
            type("A!?");
            untilEval("document.getElementById('f').value", "\"A!?\"", "the field to hold A!?");
        });
        step("hover shows the page's cursor", () -> {
            backend.pointerMove(350, 70, 0, 0);
            untilEval("!!window.hovered", "true", "mouseover to fire");
            until(() -> cursors.contains(CursorMode.POINTER), 10, "the pointer cursor");
        });
        step("the wheel scrolls the page", () -> {
            backend.pointerScroll(300, 200, 0, -3, 0);
            until(() -> {
                String y = eval("window.scrollY");
                return y != null && Double.parseDouble(y) > 50;
            }, 10, "scrollY to grow");
            check(Integer.parseInt(eval("wheels")) >= 1, "the page should have seen a wheel event");
        });
        step("the right button reaches the page's context-menu event", () -> {
            backend.pointerButton(350, 70, 1, true, 0);
            backend.pointerButton(350, 70, 1, false, 0);
            untilEval("window.rights", "1", "a contextmenu event");
        });
        step("two quick clicks are a double click", () -> {
            eval("window.dbl = 0; 0");
            sleep(600); // so earlier clicks in the same place do not count
            for (int i = 0; i < 2; i++) {
                backend.pointerButton(350, 70, 0, true, 0);
                backend.pointerButton(350, 70, 0, false, 0);
            }
            untilEval("window.dbl", "1", "a dblclick event");
        });
        step("the message bridge works both ways", () -> {
            int before = messages.size();
            backend.postMessage("{\"n\":7}");
            until(() -> messages.contains("{\"echo\":{\"n\":7}}"), 10, "the page to echo the message back (heard " + messages.subList(Math.min(before, messages.size()), messages.size()) + ")");
        });
        step("a resize gives a picture of the new size", () -> {
            int scale = Math.max(1, latestOfSize(-1).width() / width); // 2 on a Retina display
            int mark = frames.size();
            backend.resize(320, 200);
            until(() -> frames.stream().skip(mark).anyMatch(f -> f.width() == 320 * scale && f.height() == 200 * scale), 15,
                    "a picture " + (320 * scale) + "x" + (200 * scale));
            save(image(frames.stream().skip(mark).filter(f -> f.width() == 320 * scale).findFirst().orElseThrow()), "resized");
            backend.resize(width, height);
        });
        step("a second view at the same time refuses an address outside its allowed hosts", this::secondView);
        step("window.open does not open a window and nothing fails", () -> {
            eval("setTimeout(function(){ window.open('about:blank'); }, 10); 0");
            sleep(800);
            check(failure.get() == null, "failure: " + failure.get());
        });
        step("an animated page: picture rate", this::measureRate);
        step("an idle page stops sending pictures", () -> {
            backend.loadHtml("<title>idle</title><body style='background:#fff'>still");
            sleep(1500);
            int before = frames.size();
            sleep(2000);
            check(frames.size() - before <= 1, "an idle page sent " + (frames.size() - before) + " pictures in 2 s");
        });
        if (online) {
            step("loads https://example.com/ (real navigation, address and title)", () -> {
                int before = completions;
                backend.navigate("https://example.com/");
                until(() -> completions > before, 30, "the page to load");
                until(() -> "Example Domain".equals(title.get()), 10, "the title \"Example Domain\" (got " + title.get() + ")");
                until(() -> urls.contains("https://example.com/"), 5, "the address to be reported");
                save(image(latestOfSize(-1)), "online");
            });
        }
        step("closes", () -> {
            backend.close();
            sleep(500);
            check(failure.get() == null, "failure: " + failure.get());
        });
        return finish();
    }

    private int finish() {
        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.out.println("Please send this whole output and the .png files written to " + outFolder.getAbsolutePath());
        }
        return failed == 0 ? 0 : 1;
    }

    private void secondView() throws Exception {
        WebViewOptions second = new WebViewOptions().setEngine(options.getEngine()).allowHost("example.com");
        CopyOnWriteArrayList<String> refused = new CopyOnWriteArrayList<>();
        CompletableFuture<Boolean> up = new CompletableFuture<>();
        WebViewBackend other = WebViews.create(second, new WebViewBackend.Listener() {
            @Override
            public void onReady() {
                up.complete(true);
            }

            @Override
            public void onNavigationBlocked(String url) {
                refused.add(url);
            }

            @Override
            public void onFailed(String reason) {
                up.completeExceptionally(new IllegalStateException(reason));
            }
        });
        try {
            other.resize(200, 100);
            other.keepAlive();
            up.get(30, TimeUnit.SECONDS);
            other.navigate("https://evil.example.org/");
            until(() -> refused.contains("https://evil.example.org/"), 5, "the navigation to be reported as blocked");
            check(failure.get() == null, "the first view failed meanwhile: " + failure.get());
            expect("42", eval("6*7")); // and the first view still works
        } finally {
            other.close();
        }
    }

    private void measureRate() throws Exception {
        int before = completions;
        backend.loadHtml(ANIMATED);
        until(() -> completions > before, 20, "the animated page to load");
        sleep(500);
        int start = frames.size();
        long t0 = System.nanoTime();
        while (System.nanoTime() - t0 < 3_000_000_000L) {
            backend.keepAlive();
            sleep(40);
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        double fps = (frames.size() - start) / seconds;
        System.out.printf(Locale.ROOT, "          (%.1f pictures a second at %dx%d)%n", fps, width, height);
        check(fps >= 3, "fewer than 3 pictures a second: " + fps);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void step(String name, Step step) {
        long t0 = System.nanoTime();
        try {
            step.run();
            passed++;
            System.out.printf(Locale.ROOT, "  PASS  %s (%d ms)%n", name, (System.nanoTime() - t0) / 1_000_000);
        } catch (Throwable t) {
            failed++;
            String why = t.getMessage() == null ? t.toString() : t.getMessage();
            System.out.printf(Locale.ROOT, "  FAIL  %s%n          %s%n", name, why);
            if (!(t instanceof AssertionError)) t.printStackTrace(System.out);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(String expected, String actual) {
        check(expected.equals(actual), "expected " + expected + " but got " + actual);
    }

    private static boolean near(int rgb, int wanted) {
        return Math.abs(((rgb >> 16) & 0xFF) - ((wanted >> 16) & 0xFF)) < 24 && Math.abs(((rgb >> 8) & 0xFF) - ((wanted >> 8) & 0xFF)) < 24
                && Math.abs((rgb & 0xFF) - (wanted & 0xFF)) < 24;
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private void until(BooleanSupplier condition, int seconds, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            backend.keepAlive();
            if (condition.getAsBoolean()) return;
            Thread.sleep(40);
        }
        if (!condition.getAsBoolean()) throw new AssertionError("timed out after " + seconds + " s waiting for " + what);
    }

    private void untilEval(String js, String expected, String what) throws InterruptedException {
        String[] last = new String[1];
        try {
            until(() -> {
                last[0] = eval(js);
                return expected.equals(last[0]);
            }, 10, what);
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "; the page says " + last[0]);
        }
    }

    private String eval(String js) {
        CompletableFuture<String> result = new CompletableFuture<>();
        backend.execute(js, json -> result.complete(json));
        try {
            return result.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private void click(double x, double y) {
        backend.pointerButton(x, y, 0, true, 0);
        backend.pointerButton(x, y, 0, false, 0);
    }

    /** Typing as the overlay delivers it: a key press, the character, the key release. */
    private void type(String text) throws InterruptedException {
        for (char c : text.toCharArray()) {
            int key = Character.isLetter(c) ? GLFW.GLFW_KEY_A + (Character.toLowerCase(c) - 'a') : c;
            backend.key(key, GLFW.GLFW_PRESS, Character.isUpperCase(c) ? GLFW.GLFW_MOD_SHIFT : 0);
            backend.text(c);
            backend.key(key, GLFW.GLFW_RELEASE, 0);
            sleep(15);
        }
    }

    private void key(int key, int mods) throws InterruptedException {
        backend.key(key, GLFW.GLFW_PRESS, mods);
        backend.key(key, GLFW.GLFW_RELEASE, mods);
        sleep(30);
    }

    /** The newest picture that is {@code logicalWidth} points wide (or a whole multiple, on a scaled display); any when negative. */
    private WebViewBackend.Frame latestOfSize(int logicalWidth) {
        List<WebViewBackend.Frame> copy = List.copyOf(frames);
        for (int i = copy.size() - 1; i >= 0; i--) {
            WebViewBackend.Frame f = copy.get(i);
            if (logicalWidth < 0 || (f.width() % logicalWidth == 0 && f.width() > 0)) return f;
        }
        throw new AssertionError("no picture " + logicalWidth + " wide among " + copy.size());
    }

    private static BufferedImage image(WebViewBackend.Frame frame) throws IOException {
        if (frame.encoded()) return ImageIO.read(new java.io.ByteArrayInputStream(frame.data()));
        BufferedImage img = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_ARGB_PRE);
        byte[] d = frame.data();
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                int i = (y * frame.width() + x) * 4;
                img.setRGB(x, y, (d[i + 3] & 0xFF) << 24 | (d[i + 2] & 0xFF) << 16 | (d[i + 1] & 0xFF) << 8 | (d[i] & 0xFF));
            }
        }
        return img;
    }

    private void save(BufferedImage img, String name) {
        try {
            File file = new File(outFolder, "webview-selfcheck-" + options.getEngine().name().toLowerCase(Locale.ROOT) + "-" + name + ".png");
            ImageIO.write(img, "png", file);
            System.out.println("          (saved " + file.getAbsolutePath() + ")");
        } catch (IOException | RuntimeException e) {
            System.out.println("          (could not save a picture: " + e + ")");
        }
    }
}
