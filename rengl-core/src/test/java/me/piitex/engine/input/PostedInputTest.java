package me.piitex.engine.input;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opens a real (hidden) window and feeds it a click, a check box toggle and typing as Windows messages posted to its
 * handle, which is how the operating system delivers real input to GLFW. Nothing touches the real mouse or keyboard,
 * so it is safe to run while someone uses the machine. It skips off Windows and where the window never opens.
 */
class PostedInputTest {
    private static final String TITLE = "RenGL posted input test";

    private static final int WM_CHAR = 0x0102, WM_MOUSEMOVE = 0x0200, WM_LBUTTONDOWN = 0x0201, WM_LBUTTONUP = 0x0202;

    private final AtomicInteger frames = new AtomicInteger();
    private final AtomicInteger clicks = new AtomicInteger();
    private volatile Boolean checked;
    private volatile String typed = "";

    /**
     * The handful of user32 calls the test needs.
     */
    private static final class User32 {
        private final MethodHandle findWindow, postMessage, getDpi;
        private final Arena arena = Arena.ofAuto();

        User32() {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = SymbolLookup.libraryLookup("user32", arena);
            findWindow = linker.downcallHandle(lookup.find("FindWindowW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            postMessage = linker.downcallHandle(lookup.find("PostMessageW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
            getDpi = linker.downcallHandle(lookup.find("GetDpiForWindow").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
        }

        long find(String title) throws Throwable {
            MemorySegment text = arena.allocateFrom(title, java.nio.charset.StandardCharsets.UTF_16LE);
            return (long) findWindow.invokeExact(MemorySegment.NULL, text);
        }

        void post(long hwnd, int message, long wParam, long lParam) throws Throwable {
            int ok = (int) postMessage.invokeExact(hwnd, message, wParam, lParam);
            assertNotEquals(0, ok, "PostMessage failed for " + Integer.toHexString(message));
        }

        double scale(long hwnd) throws Throwable {
            int dpi = (int) getDpi.invokeExact(hwnd);
            return dpi <= 0 ? 1 : dpi / 96.0;
        }

        void mouse(long hwnd, int message, double x, double y, double scale) throws Throwable {
            long px = Math.round(x * scale), py = Math.round(y * scale);
            post(hwnd, message, message == WM_LBUTTONDOWN ? 1 : 0, (py << 16) | (px & 0xFFFF));
        }

        void click(long hwnd, double x, double y, double scale) throws Throwable {
            mouse(hwnd, WM_MOUSEMOVE, x, y, scale);
            pause();
            mouse(hwnd, WM_LBUTTONDOWN, x, y, scale);
            pause();
            mouse(hwnd, WM_LBUTTONUP, x, y, scale);
            pause();
        }

        void type(long hwnd, String text) throws Throwable {
            for (char c : text.toCharArray()) {
                post(hwnd, WM_CHAR, c, 1);
                pause();
            }
        }
    }

    private static void pause() throws InterruptedException {
        Thread.sleep(80);
    }

    @Test
    void aClickAToggleAndTypingPostedToTheWindowReachTheElements() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "posts Windows messages");

        Window window = new Window(new WindowOptions(TITLE).setDimensions(400, 220).setVsync(false));
        window.hide();
        Container root = new Container(400, 220);
        root.onUpdate(_ -> frames.incrementAndGet());

        ButtonOverlay button = new ButtonOverlay("Go", 100, 30);
        button.setPosition(20, 20);
        button.onMouseClick(_ -> clicks.incrementAndGet());
        root.addElement(button);

        CheckBoxOverlay box = new CheckBoxOverlay(24);
        box.setPosition(20, 70);
        box.onToggle(value -> checked = value);
        root.addElement(box);

        TextFieldOverlay field = new TextFieldOverlay(20, 110, 200, 30);
        field.onTextChanged(text -> typed = text);
        root.addElement(field);
        window.addContainer(root);

        Throwable[] failure = new Throwable[1];
        boolean[] drove = new boolean[1];
        Thread driver = new Thread(() -> {
            try {
                User32 user32 = new User32();
                long hwnd = 0;
                for (int i = 0; i < 150 && hwnd == 0; i++) {
                    hwnd = user32.find(TITLE);
                    if (hwnd == 0) Thread.sleep(100);
                }
                for (int i = 0; i < 150 && frames.get() < 10; i++) Thread.sleep(100);
                if (hwnd == 0 || frames.get() < 10) return; // no window or no frames: no display, skip
                drove[0] = true;
                double scale = user32.scale(hwnd);

                user32.click(hwnd, button.getAbsoluteX() + 50, button.getAbsoluteY() + 15, scale);
                user32.click(hwnd, box.getAbsoluteX() + 12, box.getAbsoluteY() + 12, scale);
                user32.click(hwnd, field.getAbsoluteX() + 100, field.getAbsoluteY() + 15, scale);
                user32.type(hwnd, "hi");
                Thread.sleep(400); // a few frames to drain the queue
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                window.close();
            }
        }, "posted-input-driver");
        driver.setDaemon(true);
        driver.start();

        try {
            new Engine().start(window);
        } catch (Throwable t) {
            assumeTrue(false, "the engine could not start here: " + t);
        } finally {
            driver.join(5_000);
        }

        assumeTrue(drove[0], "the window never opened");
        if (failure[0] != null) fail(failure[0]);
        assertEquals(1, clicks.get(), "the button heard one click");
        assertEquals(Boolean.TRUE, checked, "the check box toggled on");
        assertEquals("hi", typed, "the text field got the typed characters");
    }
}
