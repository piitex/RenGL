package me.piitex.engine.webview.linux;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The one thread GTK runs on, shared by every web view in the process. GTK may be called from only one thread (the one
 * that initialised it and runs its main loop), and the default main context can only be run by one thread at a time, so
 * two views cannot each have a thread of their own. Work is queued with {@code g_idle_add}, which is safe from any
 * thread, and a timer on the loop ticks every view.
 */
final class GtkLoop {
    private static final Logger log = LogManager.getLogger("WebView");

    private static final ConcurrentLinkedQueue<Runnable> TASKS = new ConcurrentLinkedQueue<>();
    private static final Set<WebKitGtkBackend> VIEWS = ConcurrentHashMap.newKeySet();
    private static final CountDownLatch INITIALISED = new CountDownLatch(1);
    private static Thread thread;
    private static volatile String failure;
    private static MemorySegment idleStub, tickStub;

    private GtkLoop() {
    }

    /**
     * Starts the thread if it has not been, and waits for GTK to initialise.
     *
     * @throws IllegalStateException if GTK could not open a display
     */
    static void ensureStarted() {
        synchronized (GtkLoop.class) {
            if (thread == null) {
                createStubs();
                thread = new Thread(GtkLoop::run, "RenGL-GTK");
                thread.setDaemon(true);
                thread.start();
            }
        }
        try {
            if (!INITIALISED.await(15, TimeUnit.SECONDS) && failure == null) failure = "GTK did not start in time";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) throw new IllegalStateException(failure);
    }

    /** Makes the two C function pointers (idle and timer). Once. */
    static synchronized void createStubs() {
        if (idleStub != null) return;
        FunctionDescriptor sourceFunction = FunctionDescriptor.of(JAVA_INT, ADDRESS);
        idleStub = Gtk.stub(GtkLoop.class, "drain", sourceFunction);
        tickStub = Gtk.stub(GtkLoop.class, "tickAll", sourceFunction);
    }

    static void register(WebKitGtkBackend view) {
        VIEWS.add(view);
    }

    static void unregister(WebKitGtkBackend view) {
        VIEWS.remove(view);
    }

    /** Runs {@code task} on the GTK thread. Safe from any thread; does not block. */
    static void post(Runnable task) {
        TASKS.add(task);
        Gtk.i("g_idle_add", idleStub, MemorySegment.NULL);
    }

    private static void run() {
        try {
            // the page is snapshotted by the web process, which needs no compositor; the accelerated path has no window to draw into
            Gtk.i("setenv", "WEBKIT_DISABLE_COMPOSITING_MODE", "1", 0);
            if (Gtk.i("gtk_init_check", MemorySegment.NULL, MemorySegment.NULL) == 0) {
                failure = "GTK could not open a display";
                return;
            }
            Gtk.i("g_timeout_add", 40, tickStub, MemorySegment.NULL);
        } catch (Throwable t) {
            failure = "GTK could not start: " + t;
            log.warn("WebKitGTK: {}", failure);
            return;
        } finally {
            INITIALISED.countDown();
        }
        try {
            MemorySegment loop = Gtk.ptr("g_main_loop_new", MemorySegment.NULL, 0);
            Gtk.v("g_main_loop_run", loop);
        } catch (Throwable t) {
            log.error("The GTK loop failed", t);
        }
    }

    /** The idle function: runs what was queued. Returns 0 (G_SOURCE_REMOVE). */
    static int drain(MemorySegment data) {
        Runnable task;
        while ((task = TASKS.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                log.warn("A web view task failed: {}", t.toString());
            }
        }
        return 0;
    }

    /** The timer function: ticks every view. Returns 1 (G_SOURCE_CONTINUE). */
    static int tickAll(MemorySegment data) {
        for (WebKitGtkBackend view : VIEWS) {
            try {
                view.tickFromLoop();
            } catch (Throwable t) {
                log.warn("A web view tick failed: {}", t.toString());
            }
        }
        return 1;
    }
}
