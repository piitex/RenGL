package me.piitex.engine.access.uia;

import me.piitex.engine.Window;
import me.piitex.engine.access.AccessibilityHost;
import me.piitex.engine.access.AccessibleRole;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.scheduler.Task;
import me.piitex.engine.ui.Element;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Lets Windows screen readers (NVDA, JAWS, Narrator) read a RenGL window, through UI Automation, the interface they use
 * for every other program. The window answers Windows' request for its accessibility object, and from then on tells the
 * screen reader what controls it holds, what each is called and what can be done with it, and says when focus moves
 * or a value changes.
 * <p>
 * Nothing is done until a screen reader asks. Then the tree is copied every quarter of a second (and at once when focus
 * or a value changes), and the questions that come in from the system's own threads are answered from the copy.
 * Pressing, toggling and setting a value are handed to the render thread.
 * <p>
 * Windows only; see {@link #isSupported()}. On any other system {@code Accessibility.enable} leaves this out.
 */
public final class UiaHost implements AccessibilityHost, UiaCom.Owner {
    private static final Logger log = LogManager.getLogger("Engine");
    private static final int WM_NCDESTROY = 0x0082;

    private static Boolean supported;

    /** Windows that have this hook, so the window procedure finds its host. */
    private static final Map<Long, UiaHost> HOSTS = new ConcurrentHashMap<>();
    /** The procedure each hooked window had before, kept until the window is destroyed so messages still get through. */
    private static final Map<Long, Long> PREVIOUS = new ConcurrentHashMap<>();
    private static MemorySegment stub;

    private final Window window;
    private final UiaFrame.Ids ids = new UiaFrame.Ids();
    private final UiaCom.Obj root;
    private final Task timer;
    private volatile UiaFrame frame = UiaFrame.EMPTY;
    private volatile boolean connected;
    private volatile boolean closed;
    private volatile long handle;
    private volatile boolean hooking;
    /**
     * The objects handed to Windows when raising events. The system may come back to one after the call returns, so they
     * are kept until their control leaves the tree, not released straight away.
     */
    private final Map<Integer, UiaCom.Obj> raised = new ConcurrentHashMap<>();

    /**
     * Whether this system can do it: Windows, with UI Automation's library and the Java foreign function interface.
     */
    public static synchronized boolean isSupported() {
        if (supported == null) {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
            boolean ok = false;
            if (windows) {
                try {
                    UiaCom.init();
                    ok = true;
                } catch (Throwable t) {
                    log.warn("UI Automation could not be loaded: screen readers will not see the window", t);
                }
            }
            supported = ok;
        }
        return supported;
    }

    /**
     * Whether a screen reader is running right now: NVDA, JAWS and Narrator all announce themselves to Windows. False
     * where {@link #isSupported()} is.
     */
    public static boolean screenReaderRunning() {
        return isSupported() && Uia.screenReaderRunning();
    }

    /**
     * Prepares {@code window}. It hooks the native window once that exists, so this can be made before the engine starts.
     */
    public UiaHost(Window window) {
        if (!isSupported()) throw new UnsupportedOperationException("UI Automation needs Windows");
        this.window = window;
        this.root = new UiaCom.Obj(this, 0);
        this.timer = Scheduler.every(0.25f, this::tick);
        tick();
    }

    /** Whether a screen reader has asked about this window yet. */
    public boolean isConnected() {
        return connected;
    }

    // ---- the window procedure ----------------------------------------------------------------------------------

    /** Render thread. */
    private void tick() {
        if (closed) {
            timer.cancel();
            return;
        }
        if (!hooking) {
            long hwnd = window.getNativeHandle();
            if (hwnd != 0) {
                hooking = true;
                handle = hwnd;
                window.runOnMainThread(() -> hook(hwnd));
            }
        }
        if (connected) refresh();
    }

    /** Main thread. */
    private void hook(long hwnd) {
        if (closed) return;
        try {
            synchronized (UiaHost.class) {
                if (stub == null) {
                    FunctionDescriptor descriptor = FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
                    stub = Linker.nativeLinker().upcallStub(MethodHandles.lookup().findStatic(UiaHost.class, "windowProc",
                            descriptor.toMethodType()), descriptor, Arena.global());
                }
            }
            HOSTS.put(hwnd, this);
            long previous = (long) Uia.SET_WINDOW_LONG_PTR.invoke(hwnd, Uia.GWLP_WNDPROC, stub.address());
            PREVIOUS.put(hwnd, previous);
        } catch (Throwable t) {
            HOSTS.remove(hwnd);
            log.warn("Could not hook the window for screen readers", t);
        }
    }

    /** Called by Windows for every message to a hooked window, on the main thread. */
    private static long windowProc(long hwnd, int message, long wParam, long lParam) {
        Long previous = PREVIOUS.get(hwnd);
        try {
            UiaHost host = HOSTS.get(hwnd);
            if (host != null && !host.closed && message == Uia.WM_GETOBJECT && (int) lParam == Uia.UIA_ROOT_OBJECT_ID) {
                return host.answer(hwnd, wParam, lParam);
            }
            if (message == WM_NCDESTROY) {
                HOSTS.remove(hwnd);
                PREVIOUS.remove(hwnd);
            }
        } catch (Throwable t) {
            log.debug("UI Automation could not answer", t);
        }
        try {
            return (long) Uia.CALL_WINDOW_PROC.invoke(previous == null ? 0L : previous, hwnd, message, wParam, lParam);
        } catch (Throwable t) {
            return 0;
        }
    }

    private long answer(long hwnd, long wParam, long lParam) throws Throwable {
        connected = true;
        if (frame == UiaFrame.EMPTY) {
            // The first question: have something to answer with, waiting for the render thread for a moment if need be
            CountDownLatch built = new CountDownLatch(1);
            Scheduler.runLater(() -> {
                refresh();
                built.countDown();
            });
            built.await(300, TimeUnit.MILLISECONDS);
        }
        return (long) Uia.RETURN_RAW_ELEMENT_PROVIDER.invoke(hwnd, wParam, lParam, root.simple());
    }

    // ---- UiaCom.Owner ------------------------------------------------------------------------------------------

    @Override
    public UiaFrame frame() {
        return frame;
    }

    @Override
    public double[] geometry() {
        long hwnd = handle;
        if (hwnd == 0) return null;
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment rect = temp.allocate(16, 4);
            if ((int) Uia.GET_CLIENT_RECT.invoke(hwnd, rect) == 0) return null;
            MemorySegment origin = temp.allocate(8, 4);
            if ((int) Uia.CLIENT_TO_SCREEN.invoke(hwnd, origin) == 0) return null;
            return new double[]{window.getWindowOptions().getWidth(), window.getWindowOptions().getHeight(),
                    origin.get(JAVA_INT, 0), origin.get(JAVA_INT, 4), rect.get(JAVA_INT, 8), rect.get(JAVA_INT, 12)};
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void later(Runnable action) {
        Scheduler.runLater(action);
    }

    @Override
    public void focus(Element element) {
        window.requestFocus(element);
    }

    @Override
    public MemorySegment hostProvider() {
        long hwnd = handle;
        if (hwnd == 0) return MemorySegment.NULL;
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) Uia.HOST_PROVIDER_FROM_HWND.invoke(hwnd, out);
            return hr >= 0 ? out.get(ADDRESS, 0) : MemorySegment.NULL;
        } catch (Throwable t) {
            return MemorySegment.NULL;
        }
    }

    // ---- the tree and its events -------------------------------------------------------------------------------

    private UiaCom.Obj providerFor(int id) {
        return id == 0 ? root : raised.computeIfAbsent(id, i -> new UiaCom.Obj(this, i));
    }

    @Override
    public UiaCom.Obj object(int id) {
        return providerFor(id);
    }

    private void refresh() {
        try {
            UiaFrame built = UiaFrame.build(window, ids);
            frame = built;
            raised.entrySet().removeIf(e -> {
                if (built.item(e.getKey()) != null) return false;
                e.getValue().release();
                return true;
            });
        } catch (Throwable t) {
            log.debug("Could not read the window for screen readers", t);
        }
    }

    private boolean listening() {
        if (!connected || closed || handle == 0) return false;
        try {
            return (int) Uia.CLIENTS_ARE_LISTENING.invoke() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Events are raised on the main thread, the one the window belongs to: Windows ties an event to its window's thread
     * and does not deliver one raised from another.
     */
    private void raise(Runnable event) {
        window.runOnMainThread(() -> {
            if (!closed) event.run();
        });
    }

    @Override
    public void focusChanged(Window window, Element previous, Element now, boolean byKeyboard) {
        if (!listening()) return;
        refresh();
        if (now == null) return;
        int id = ids.of(now);
        if (frame.item(id) == null) return;
        UiaCom.Obj provider = providerFor(id);
        raise(() -> {
            try {
                Uia.RAISE_EVENT.invoke(provider.simple(), Uia.EVENT_FOCUS_CHANGED);
            } catch (Throwable t) {
                log.debug("Could not tell the screen reader about focus", t);
            }
        });
    }

    @Override
    public void valueChanged(Window window, Element element) {
        if (!listening()) return;
        refresh();
        int id = ids.of(element);
        UiaFrame.Item item = frame.item(id);
        if (item == null) return;
        UiaCom.Obj provider = providerFor(id);
        AccessibleRole role = item.role();
        raise(() -> {
            try (Arena temp = Arena.ofConfined()) {
                MemorySegment before = temp.allocate(Uia.VARIANT_SIZE, 8), after = temp.allocate(Uia.VARIANT_SIZE, 8);
                Uia.variantEmpty(before);
                int property;
                if (role == AccessibleRole.CHECK_BOX || role == AccessibleRole.SWITCH) {
                    property = Uia.PROP_TOGGLE_STATE;
                    Uia.variantInt(after, Boolean.TRUE.equals(item.checked()) ? 1 : 0);
                } else if (role == AccessibleRole.RADIO_BUTTON) {
                    property = Uia.PROP_SELECTION_ITEM_IS_SELECTED;
                    Uia.variantBool(after, Boolean.TRUE.equals(item.checked()));
                } else if (item.ranged()) {
                    property = Uia.PROP_RANGE_VALUE;
                    Uia.variantDouble(after, item.current());
                } else {
                    property = Uia.PROP_VALUE_VALUE;
                    Uia.variantString(after, item.password() ? "" : item.value());
                }
                Uia.RAISE_PROPERTY_CHANGED.invoke(provider.simple(), property, before, after);
                if (after.get(java.lang.foreign.ValueLayout.JAVA_SHORT, 0) == Uia.VT_BSTR) Uia.freeBstr(after.get(ADDRESS, 8));
            } catch (Throwable t) {
                log.debug("Could not tell the screen reader about a change", t);
            }
        });
    }

    @Override
    public void announce(Window window, String text, boolean urgent) {
        if (text == null || text.isBlank() || !listening()) return;
        raise(() -> {
            MemorySegment message = MemorySegment.NULL, activity = MemorySegment.NULL;
            try {
                message = Uia.bstr(text);
                activity = Uia.bstr("rengl");
                // Other; an urgent one cuts off what is being read, the rest wait their turn
                Uia.RAISE_NOTIFICATION.invoke(root.simple(), 4, urgent ? 1 : 2, message, activity);
            } catch (Throwable t) {
                log.debug("Could not announce to the screen reader", t);
            } finally {
                try {
                    Uia.freeBstr(message);
                    Uia.freeBstr(activity);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        timer.cancel();
        long hwnd = handle;
        HOSTS.remove(hwnd);
        try {
            if (hwnd != 0 && (int) Uia.IS_WINDOW.invoke(hwnd) != 0) {
                Uia.RETURN_RAW_ELEMENT_PROVIDER.invoke(hwnd, 0L, 0L, MemorySegment.NULL);
            }
            if (connected) Uia.DISCONNECT_PROVIDER.invoke(root.simple());
        } catch (Throwable t) {
            log.debug("Could not disconnect from the screen reader", t);
        }
        if (hwnd != 0) window.runOnMainThread(() -> unhook(hwnd));
        raised.values().forEach(UiaCom.Obj::release);
        raised.clear();
        root.release();
    }

    /** Main thread: puts the window's own procedure back, if nobody has hooked it since. */
    private static void unhook(long hwnd) {
        try {
            if ((int) Uia.IS_WINDOW.invoke(hwnd) == 0 || stub == null) return;
            long current = (long) Uia.GET_WINDOW_LONG_PTR.invoke(hwnd, Uia.GWLP_WNDPROC);
            Long previous = PREVIOUS.get(hwnd);
            if (previous != null && current == stub.address()) {
                Uia.SET_WINDOW_LONG_PTR.invoke(hwnd, Uia.GWLP_WNDPROC, previous);
                PREVIOUS.remove(hwnd);
            }
        } catch (Throwable t) {
            log.debug("Could not unhook the window", t);
        }
    }
}
