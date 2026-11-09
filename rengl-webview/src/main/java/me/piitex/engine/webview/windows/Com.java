package me.piitex.engine.webview.windows;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static java.lang.foreign.ValueLayout.*;

/**
 * COM and the few Win32 calls the WebView2 backend needs, called straight from Java with no native library of ours.
 * <p>
 * A COM object is a pointer to a pointer to a table of functions. {@link #call} reads the table, picks the function by
 * its slot (counted from {@code IUnknown}'s three: QueryInterface, AddRef, Release) and passes the object as the first
 * argument. Every method used returns an {@code HRESULT}.
 * <p>
 * WebView2 reports back by calling objects the caller provides, so those are made here too: a small native block (the
 * table pointer and an id) whose {@code Invoke} lands in a Java callback. The tables and their stubs are made once and
 * shared; only the 16-byte block is per callback, and a one-shot block is reused once its callback has run.
 */
final class Com {
    private Com() {
    }

    private static final Logger log = LogManager.getLogger("WebView");

    static final Linker LINKER = Linker.nativeLinker();
    static final Arena GLOBAL = Arena.global();
    /** {@code RECT}: left, top, right, bottom. */
    static final MemoryLayout RECT = MemoryLayout.structLayout(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);

    static final int S_OK = 0, S_FALSE = 1;
    static final int RPC_E_CHANGED_MODE = 0x80010106;

    static final int ADD_REF = 1, RELEASE = 2;

    // ------------------------------------------------------------------------------------------------ calling out

    private static final Map<FunctionDescriptor, MethodHandle> CALLS = new ConcurrentHashMap<>();

    static MethodHandle function(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.find(name).orElseThrow(() -> new IllegalStateException("Missing " + name)), descriptor);
    }

    /**
     * Calls the method at {@code slot} of the COM object {@code obj}. {@code descriptor} describes the arguments after
     * {@code this}, and the result is the {@code HRESULT}.
     */
    static int call(MemorySegment obj, int slot, FunctionDescriptor descriptor, Object... args) {
        if (obj == null || obj.address() == 0) throw new IllegalStateException("COM object is gone");
        MemorySegment table = obj.reinterpret(8).get(ADDRESS, 0).reinterpret(8L * (slot + 1));
        MemorySegment target = table.get(ADDRESS, 8L * slot);
        MethodHandle handle = CALLS.computeIfAbsent(descriptor, d -> {
            List<MemoryLayout> layouts = new ArrayList<>();
            layouts.add(ADDRESS);
            layouts.addAll(d.argumentLayouts());
            return LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, layouts.toArray(new MemoryLayout[0])));
        });
        Object[] all = new Object[args.length + 2];
        all[0] = target;
        all[1] = obj;
        System.arraycopy(args, 0, all, 2, args.length);
        try {
            return (int) handle.invokeWithArguments(all);
        } catch (Throwable t) {
            throw new IllegalStateException("COM call failed (slot " + slot + ")", t);
        }
    }

    static final FunctionDescriptor NO_ARGS = FunctionDescriptor.of(JAVA_INT);
    static final FunctionDescriptor PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor PTR_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    static final FunctionDescriptor INT = FunctionDescriptor.of(JAVA_INT, JAVA_INT);
    static final FunctionDescriptor INT_PTR_PTR = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
    static final FunctionDescriptor RECT_ARG = FunctionDescriptor.of(JAVA_INT, RECT);

    static String hex(int hr) {
        return "0x" + Integer.toHexString(hr).toUpperCase();
    }

    static void check(int hr, String what) {
        if (hr < 0) throw new IllegalStateException(what + " failed (" + hex(hr) + ")");
    }

    static void addRef(MemorySegment obj) {
        call(obj, ADD_REF, NO_ARGS);
    }

    static void release(MemorySegment obj) {
        if (obj != null && obj.address() != 0) call(obj, RELEASE, NO_ARGS);
    }

    // ------------------------------------------------------------------------------------------------ strings

    static MemorySegment wide(Arena arena, String s) {
        return arena.allocateFrom(s, StandardCharsets.UTF_16LE);
    }

    /** Reads a NUL-terminated UTF-16 string the caller does not own, or null for a null pointer. */
    static String readWide(MemorySegment p) {
        if (p == null || p.address() == 0) return null;
        return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
    }

    /** Reads a string a method allocated for the caller ({@code CoTaskMemAlloc}) and frees it. */
    static String takeWide(MemorySegment p) {
        String s = readWide(p);
        if (p != null && p.address() != 0) coTaskMemFree(p);
        return s;
    }

    /** Calls a method of the form {@code get_Something(LPWSTR*)} and returns the string. */
    static String getString(MemorySegment obj, int slot) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(8);
            if (call(obj, slot, PTR, out) < 0) return null;
            return takeWide(out.get(ADDRESS, 0));
        }
    }

    /** Calls a method of the form {@code get_Something(BOOL*)} or {@code (int*)}. */
    static int getInt(MemorySegment obj, int slot) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(4);
            if (call(obj, slot, PTR, out) < 0) return 0;
            return out.get(JAVA_INT, 0);
        }
    }

    // ------------------------------------------------------------------------------------------------ Win32

    private static final SymbolLookup OLE = SymbolLookup.libraryLookup("ole32.dll", GLOBAL);
    private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32.dll", GLOBAL);
    private static final SymbolLookup USER = SymbolLookup.libraryLookup("user32.dll", GLOBAL);
    private static final SymbolLookup SHLWAPI = SymbolLookup.libraryLookup("shlwapi.dll", GLOBAL);

    private static final MethodHandle CO_INITIALIZE = function(OLE, "CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CO_UNINITIALIZE = function(OLE, "CoUninitialize", FunctionDescriptor.ofVoid());
    private static final MethodHandle CO_TASK_MEM_FREE = function(OLE, "CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle LOAD_LIBRARY = function(KERNEL, "LoadLibraryExW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_PROC = function(KERNEL, "GetProcAddress", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_WINDOW = function(USER, "CreateWindowExW", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle DESTROY_WINDOW = function(USER, "DestroyWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SET_WINDOW_POS = function(USER, "SetWindowPos", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle PEEK_MESSAGE = function(USER, "PeekMessageW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle TRANSLATE_MESSAGE = function(USER, "TranslateMessage", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DISPATCH_MESSAGE = function(USER, "DispatchMessageW", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle MSG_WAIT = function(USER, "MsgWaitForMultipleObjects", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle POST_THREAD_MESSAGE = function(USER, "PostThreadMessageW", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle CURRENT_THREAD_ID = function(KERNEL, "GetCurrentThreadId", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle SET_FOCUS = function(USER, "SetFocus", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GET_FOCUS = function(USER, "GetFocus", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle IS_CHILD = function(USER, "IsChild", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle MEM_STREAM = function(SHLWAPI, "SHCreateMemStream", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    static final int COINIT_APARTMENTTHREADED = 0x2;
    static final int WM_APP = 0x8000;
    static final int QS_ALLINPUT = 0x4FF;
    static final int PM_REMOVE = 1;
    static final int SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOZORDER = 0x4, SWP_NOACTIVATE = 0x10;

    private static RuntimeException wrap(Throwable t) {
        return t instanceof RuntimeException r ? r : new IllegalStateException(t);
    }

    /** Joins this thread to a single-threaded COM apartment, which WebView2 needs. */
    static int coInitialize() {
        try {
            return (int) CO_INITIALIZE.invokeExact(MemorySegment.NULL, COINIT_APARTMENTTHREADED);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    static void coUninitialize() {
        try {
            CO_UNINITIALIZE.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    static void coTaskMemFree(MemorySegment p) {
        try {
            CO_TASK_MEM_FREE.invokeExact(p);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Loads a DLL from its own folder, so the libraries it depends on there are found, and returns the address of one
     * of its exports.
     */
    static MemorySegment exportOf(String dll, String name) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment module = (MemorySegment) LOAD_LIBRARY.invokeExact(wide(a, dll), MemorySegment.NULL, 0x8 /* LOAD_WITH_ALTERED_SEARCH_PATH */);
            if (module.address() == 0) throw new IllegalStateException("Could not load " + dll);
            MemorySegment proc = (MemorySegment) GET_PROC.invokeExact(module, a.allocateFrom(name));
            if (proc.address() == 0) throw new IllegalStateException(dll + " has no " + name);
            return proc;
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * A window nobody sees, to give the browser something to be a child of. It uses the system's own STATIC class so no
     * window procedure is needed, sits far off screen and never takes focus.
     */
    static MemorySegment createHiddenHost(int width, int height) {
        try (Arena a = Arena.ofConfined()) {
            // WS_POPUP | WS_VISIBLE, WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE
            return (MemorySegment) CREATE_WINDOW.invokeExact(0x08000080, wide(a, "STATIC"), wide(a, "RenGL web view"), 0x90000000,
                    -10000, -10000, width, height, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    static void destroyWindow(MemorySegment hwnd) {
        try {
            int ignored = (int) DESTROY_WINDOW.invokeExact(hwnd);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    static void moveWindow(MemorySegment hwnd, int x, int y, int width, int height) {
        try {
            int ignored = (int) SET_WINDOW_POS.invokeExact(hwnd, MemorySegment.NULL, x, y, width, height, SWP_NOZORDER | SWP_NOACTIVATE);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    static int currentThreadId() {
        try {
            return (int) CURRENT_THREAD_ID.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /** Wakes the message loop of a thread, which {@link #waitForMessage} is sleeping in; safe from any thread. */
    static void wakeThread(int threadId) {
        try {
            int ignored = (int) POST_THREAD_MESSAGE.invokeExact(threadId, WM_APP, 0L, 0L);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    static void setFocus(MemorySegment hwnd) {
        try {
            MemorySegment ignored = (MemorySegment) SET_FOCUS.invokeExact(hwnd);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /** Whether the window with the keyboard is {@code parent} or inside it. */
    static boolean hasKeyboardFocus(MemorySegment parent) {
        try {
            MemorySegment focus = (MemorySegment) GET_FOCUS.invokeExact();
            if (focus.address() == 0) return false;
            if (focus.address() == parent.address()) return true;
            return (int) IS_CHILD.invokeExact(parent, focus) != 0;
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    static MemorySegment newMemoryStream() {
        try {
            return (MemorySegment) MEM_STREAM.invokeExact(MemorySegment.NULL, 0);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /** Dispatches the messages waiting for this thread, which is where WebView2's callbacks arrive. */
    static void dispatchMessages(MemorySegment msg) {
        try {
            while ((int) PEEK_MESSAGE.invokeExact(msg, MemorySegment.NULL, 0, 0, PM_REMOVE) != 0) {
                int ignored = (int) TRANSLATE_MESSAGE.invokeExact(msg);
                MemorySegment ignoredResult = (MemorySegment) DISPATCH_MESSAGE.invokeExact(msg);
            }
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /** Sleeps until a message arrives for this thread or the time is up. */
    static void waitForMessage(int millis) {
        try {
            int ignored = (int) MSG_WAIT.invokeExact(0, MemorySegment.NULL, 0, millis, QS_ALLINPUT);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /** Runs the message loop until {@code done} or the time is up; true if it was done. */
    static boolean pump(BooleanSupplier done, long timeoutMillis) {
        long end = System.nanoTime() + timeoutMillis * 1_000_000L;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment msg = a.allocate(64, 8);
            while (!done.getAsBoolean()) {
                long left = (end - System.nanoTime()) / 1_000_000L;
                if (left <= 0) return false;
                waitForMessage((int) Math.min(left, 50));
                dispatchMessages(msg);
            }
        }
        return true;
    }

    static MemorySegment rect(Arena arena, int left, int top, int right, int bottom) {
        MemorySegment r = arena.allocate(RECT);
        r.set(JAVA_INT, 0, left);
        r.set(JAVA_INT, 4, top);
        r.set(JAVA_INT, 8, right);
        r.set(JAVA_INT, 12, bottom);
        return r;
    }

    // ------------------------------------------------------------------------------------------------ callbacks in

    /** {@code Invoke(HRESULT, pointer)}: the completion of most calls; the pointer is a result or a new object. */
    @FunctionalInterface
    interface ResultHandler {
        void invoke(int hr, MemorySegment value);
    }

    /** {@code Invoke(sender, args)}: an event. */
    @FunctionalInterface
    interface EventHandler {
        void invoke(MemorySegment sender, MemorySegment args);
    }

    /** {@code Invoke(HRESULT)}. */
    @FunctionalInterface
    interface StatusHandler {
        void invoke(int hr);
    }

    private record Entry(Object callback, boolean oneShot, MemorySegment block) {
    }

    private static final Map<Long, Entry> HANDLERS = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong();
    private static final ConcurrentLinkedQueue<MemorySegment> FREE_BLOCKS = new ConcurrentLinkedQueue<>();
    private static volatile MemorySegment resultTable, eventTable, statusTable;

    private static MemorySegment table(MethodHandle invoke, FunctionDescriptor invokeDescriptor) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MemorySegment vtbl = GLOBAL.allocate(32, 8);
            vtbl.set(ADDRESS, 0, LINKER.upcallStub(lookup.findStatic(Com.class, "queryInterface",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), GLOBAL));
            vtbl.set(ADDRESS, 8, LINKER.upcallStub(lookup.findStatic(Com.class, "addRefStub",
                    MethodType.methodType(int.class, MemorySegment.class)), FunctionDescriptor.of(JAVA_INT, ADDRESS), GLOBAL));
            vtbl.set(ADDRESS, 16, LINKER.upcallStub(lookup.findStatic(Com.class, "releaseStub",
                    MethodType.methodType(int.class, MemorySegment.class)), FunctionDescriptor.of(JAVA_INT, ADDRESS), GLOBAL));
            vtbl.set(ADDRESS, 24, LINKER.upcallStub(invoke, invokeDescriptor, GLOBAL));
            return vtbl;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static synchronized void ensureTables() {
        if (resultTable != null) return;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            resultTable = table(lookup.findStatic(Com.class, "invokeResult",
                            MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class)),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            eventTable = table(lookup.findStatic(Com.class, "invokeEvent",
                            MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            statusTable = table(lookup.findStatic(Com.class, "invokeStatus",
                            MethodType.methodType(int.class, MemorySegment.class, int.class)),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // The objects only ever live as long as the program, so reference counting is not needed, and every interface asked
    // for is answered with the object itself.
    private static int queryInterface(MemorySegment self, MemorySegment iid, MemorySegment out) {
        out.reinterpret(8).set(ADDRESS, 0, self);
        return S_OK;
    }

    private static int addRefStub(MemorySegment self) {
        return 2;
    }

    private static int releaseStub(MemorySegment self) {
        return 1;
    }

    private static Entry entryOf(MemorySegment self) {
        return HANDLERS.get(self.reinterpret(16).get(JAVA_LONG, 8));
    }

    private static void finish(Entry entry, MemorySegment self) {
        if (entry.oneShot()) {
            HANDLERS.remove(self.reinterpret(16).get(JAVA_LONG, 8));
            FREE_BLOCKS.add(entry.block());
        }
    }

    private static int invokeResult(MemorySegment self, int hr, MemorySegment value) {
        Entry entry = entryOf(self);
        if (entry == null) return S_OK;
        try {
            ((ResultHandler) entry.callback()).invoke(hr, value);
        } catch (Throwable t) {
            log.error("A web view callback failed", t);
        } finally {
            finish(entry, self);
        }
        return S_OK;
    }

    private static int invokeEvent(MemorySegment self, MemorySegment sender, MemorySegment args) {
        Entry entry = entryOf(self);
        if (entry == null) return S_OK;
        try {
            ((EventHandler) entry.callback()).invoke(sender, args);
        } catch (Throwable t) {
            log.error("A web view event handler failed", t);
        } finally {
            finish(entry, self);
        }
        return S_OK;
    }

    private static int invokeStatus(MemorySegment self, int hr) {
        Entry entry = entryOf(self);
        if (entry == null) return S_OK;
        try {
            ((StatusHandler) entry.callback()).invoke(hr);
        } catch (Throwable t) {
            log.error("A web view callback failed", t);
        } finally {
            finish(entry, self);
        }
        return S_OK;
    }

    private static MemorySegment block(MemorySegment table, Object callback, boolean oneShot) {
        MemorySegment block = oneShot ? FREE_BLOCKS.poll() : null;
        if (block == null) block = GLOBAL.allocate(16, 8);
        long id = IDS.incrementAndGet();
        block.set(ADDRESS, 0, table);
        block.set(JAVA_LONG, 8, id);
        HANDLERS.put(id, new Entry(callback, oneShot, block));
        return block;
    }

    /** A handler for a call that completes once with a result: {@code ExecuteScript}, creating the controller, ... */
    static MemorySegment onResult(ResultHandler handler) {
        ensureTables();
        return block(resultTable, handler, true);
    }

    /** A handler for a call that completes once with only a status: {@code CapturePreview}. */
    static MemorySegment onStatus(StatusHandler handler) {
        ensureTables();
        return block(statusTable, handler, true);
    }

    /** A handler that stays registered and is called for every event. */
    static MemorySegment onEvent(EventHandler handler) {
        ensureTables();
        return block(eventTable, handler, false);
    }

    private static volatile MemorySegment ignoring;

    /** A handler that is never released and does nothing, for calls whose outcome does not matter. */
    static MemorySegment ignoreResult() {
        MemorySegment h = ignoring;
        if (h == null) {
            synchronized (Com.class) {
                if (ignoring == null) {
                    ensureTables();
                    ignoring = block(resultTable, (ResultHandler) (hr, v) -> {
                    }, false);
                }
                h = ignoring;
            }
        }
        return h;
    }
}
