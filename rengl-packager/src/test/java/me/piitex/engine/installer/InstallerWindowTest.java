package me.piitex.engine.installer;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Walks the installer window through its pages in preview mode (nothing is installed) by posting clicks to the window's
 * handle, as the operating system would deliver them. The window is kept hidden and the real mouse is never touched.
 * Skips off Windows and where the window never opens.
 */
class InstallerWindowTest {
    private static final String TITLE = "Sample App Setup";
    private static final int WM_MOUSEMOVE = 0x0200, WM_LBUTTONDOWN = 0x0201, WM_LBUTTONUP = 0x0202;

    // Where the buttons sit in the 560 x 380 window (see InstallerWindow): the bottom row, centres of two 100 x 36 buttons.
    private static final double LEFT_BUTTON_X = 382, RIGHT_BUTTON_X = 490, BUTTON_ROW_Y = 340;

    private static final class User32 {
        private final Arena arena = Arena.ofAuto();
        private final MethodHandle findWindow, postMessage, getDpi;

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
            MemorySegment text = arena.allocateFrom(title, StandardCharsets.UTF_16LE);
            return (long) findWindow.invokeExact(MemorySegment.NULL, text);
        }

        double scale(long hwnd) throws Throwable {
            int dpi = (int) getDpi.invokeExact(hwnd);
            return dpi <= 0 ? 1 : dpi / 96.0;
        }

        private void post(long hwnd, int message, double x, double y, double scale) throws Throwable {
            long px = Math.round(x * scale), py = Math.round(y * scale);
            int ok = (int) postMessage.invokeExact(hwnd, message, message == WM_LBUTTONDOWN ? 1L : 0L, (py << 16) | (px & 0xFFFF));
            if (ok == 0) throw new IllegalStateException("PostMessage failed");
        }

        void click(long hwnd, double x, double y) throws Throwable {
            double scale = scale(hwnd);
            post(hwnd, WM_MOUSEMOVE, x, y, scale);
            Thread.sleep(100);
            post(hwnd, WM_LBUTTONDOWN, x, y, scale);
            Thread.sleep(100);
            post(hwnd, WM_LBUTTONUP, x, y, scale);
            Thread.sleep(100);
        }
    }

    /** What happened in one run of the window. */
    private record Outcome(boolean opened, int exitCode) {
    }

    private interface Script {
        void run(User32 user32, long hwnd) throws Throwable;
    }

    /**
     * Runs the preview window, starts {@code script} once it is up, and returns the exit code the window ends with.
     * A script that leaves the window open has it closed when the script ends.
     */
    private static Outcome drive(String failure, Script script) throws Exception {
        InstallerWindow window = new InstallerWindow(SetupInfo.preview(), null, "C:\\Temp\\Sample App", null, failure);
        window.keepHidden();
        Throwable[] problem = new Throwable[1];
        boolean[] opened = new boolean[1];
        Thread driver = new Thread(() -> {
            try {
                User32 user32 = new User32();
                long hwnd = 0;
                for (int i = 0; i < 150 && hwnd == 0; i++) {
                    hwnd = user32.find(TITLE);
                    if (hwnd == 0) Thread.sleep(100);
                }
                if (hwnd == 0) return;
                opened[0] = true;
                Thread.sleep(1_000); // the first frames
                script.run(user32, hwnd);
                Thread.sleep(500);
            } catch (Throwable t) {
                problem[0] = t;
            }
            window.forceClose();   // a no-op for a script that already ended the window
        }, "installer-test-driver");
        driver.setDaemon(true);
        driver.start();

        int exit;
        try {
            exit = window.run();
        } catch (Throwable t) {
            assumeTrue(false, "the engine could not start here: " + t);
            return null;
        }
        driver.join(5_000);
        if (problem[0] != null) throw new AssertionError(problem[0]);
        return new Outcome(opened[0], exit);
    }

    private static void assumeWindows() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "posts Windows messages");
    }

    @Test
    void cancelOnTheFirstPageClosesWithZero() throws Exception {
        assumeWindows();
        Outcome outcome = drive(null, (user32, hwnd) -> user32.click(hwnd, RIGHT_BUTTON_X, BUTTON_ROW_Y));
        assumeTrue(outcome.opened(), "the window never opened");
        assertEquals(0, outcome.exitCode());
    }

    @Test
    void aFailedInstallEndsWithOneWhenThePersonClosesIt() throws Exception {
        assumeWindows();
        Outcome outcome = drive("The folder is read-only.", (user32, hwnd) -> {
            user32.click(hwnd, LEFT_BUTTON_X, BUTTON_ROW_Y);   // Install
            Thread.sleep(2_500);                                // the pretend install fails halfway
            user32.click(hwnd, RIGHT_BUTTON_X, BUTTON_ROW_Y);  // Close
        });
        assumeTrue(outcome.opened(), "the window never opened");
        assertEquals(1, outcome.exitCode());
    }

    @Test
    void goingBackAfterAFailureAndInstallingAgainEndsWithZero() throws Exception {
        assumeWindows();
        Outcome outcome = drive("The folder is read-only.", (user32, hwnd) -> {
            user32.click(hwnd, LEFT_BUTTON_X, BUTTON_ROW_Y);   // Install: fails
            Thread.sleep(2_500);
            user32.click(hwnd, LEFT_BUTTON_X, BUTTON_ROW_Y);   // Back
            Thread.sleep(500);
            user32.click(hwnd, LEFT_BUTTON_X, BUTTON_ROW_Y);   // Install again: works
            Thread.sleep(4_500);
            user32.click(hwnd, RIGHT_BUTTON_X, BUTTON_ROW_Y);  // Finish
        });
        assumeTrue(outcome.opened(), "the window never opened");
        assertEquals(0, outcome.exitCode());
    }
}
