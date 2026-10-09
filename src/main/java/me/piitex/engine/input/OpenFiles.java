package me.piitex.engine.input;

import me.piitex.engine.Engine;
import me.piitex.engine.gl.MacOpenFiles;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Files the operating system asks the application to open: a {@code .zip} double-clicked in Finder / Explorer when
 * the application is its registered program, "Open With", or a file dragged onto the application's Dock icon. See
 * {@link Engine#onOpenFiles}.
 * <p>
 * The OS does this differently per platform. Windows and Linux start the program with the paths as
 * {@code main(String[])} arguments, which {@link #fromArguments} turns into the same event. macOS sends the
 * running program an Apple event instead and passes no arguments at all; {@link MacOpenFiles} receives it.
 * <p>
 * Requests that arrive before a listener is set (the OS can ask while the window is still opening) are kept and
 * delivered when {@link #onOpenFiles} is called.
 */
public final class OpenFiles {
    private static final Logger log = LogManager.getLogger("Engine");
    private static final Object LOCK = new Object();
    private static final List<List<Path>> pending = new ArrayList<>(); // guarded by LOCK
    private static Consumer<List<Path>> listener;                       // guarded by LOCK

    private OpenFiles() {
    }

    /**
     * Sets the listener, replacing the last one; null removes it. Requests made while there was none are delivered
     * to the new listener on the next frame. The listener runs on the render thread.
     */
    public static void onOpenFiles(Consumer<List<Path>> newListener) {
        synchronized (LOCK) {
            listener = newListener;
        }
    }

    /**
     * Treats {@code main}'s arguments as files to open: the ones that name an existing file or folder are delivered
     * as one request, and the rest (options such as {@code --verbose}) are ignored. Does nothing if none qualify.
     * Safe from any thread, before or after the engine starts.
     */
    public static void fromArguments(String[] args) {
        if (args == null) return;
        List<Path> paths = new ArrayList<>();
        for (String arg : args) {
            if (arg == null || arg.isBlank() || arg.startsWith("-")) continue;
            try {
                Path path = Path.of(arg);
                if (Files.exists(path)) paths.add(path.toAbsolutePath());
            } catch (InvalidPathException ignored) {
                // not a path on this file system
            }
        }
        deliver(paths);
    }

    /**
     * Queues a request from the OS. Safe from any thread.
     */
    public static void deliver(List<Path> paths) {
        if (paths == null || paths.isEmpty()) return;
        synchronized (LOCK) {
            pending.add(List.copyOf(paths));
        }
    }

    /**
     * The paths that exist and are not part of the class path or module path. macOS reports the launch command's
     * own words ({@code DropProbe}, {@code app.jar}) as files to open, and those are not what the user opened.
     */
    public static List<Path> onlyRealFiles(List<Path> paths) {
        List<Path> own = new ArrayList<>();
        for (String key : new String[]{"java.class.path", "jdk.module.path"}) {
            String value = System.getProperty(key);
            if (value == null) continue;
            for (String entry : value.split(java.io.File.pathSeparator)) {
                try {
                    if (!entry.isBlank()) own.add(Path.of(entry).toAbsolutePath().normalize());
                } catch (InvalidPathException ignored) {
                    // not a path on this file system
                }
            }
        }
        List<Path> real = new ArrayList<>();
        for (Path path : paths) {
            Path absolute = path.toAbsolutePath().normalize();
            if (Files.exists(absolute) && !own.contains(absolute)) real.add(absolute);
        }
        return real;
    }

    /**
     * Main thread, once before the window system starts. Lets macOS pass files to the application.
     */
    public static void install() {
        MacOpenFiles.install();
    }

    /**
     * Render thread, once per frame. Hands the queued requests to the listener, if there is one.
     */
    public static void drain() {
        List<List<Path>> ready;
        Consumer<List<Path>> target;
        synchronized (LOCK) {
            if (listener == null || pending.isEmpty()) return;
            target = listener;
            ready = new ArrayList<>(pending);
            pending.clear();
        }
        for (List<Path> paths : ready) {
            try {
                target.accept(paths);
            } catch (Throwable t) {
                log.error("Open-files listener threw", t);
            }
        }
    }

    /**
     * For tests: forgets the listener and anything queued.
     */
    public static void reset() {
        synchronized (LOCK) {
            listener = null;
            pending.clear();
        }
    }
}
