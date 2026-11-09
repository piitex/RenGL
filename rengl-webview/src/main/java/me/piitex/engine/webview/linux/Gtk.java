package me.piitex.engine.webview.linux;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.*;

/**
 * Late-bound calls into GLib, GTK 3, GDK, Cairo and WebKitGTK, made from Java with no native code of ours. Functions are
 * looked up by name on first use, so a library version that lacks an optional one is only a problem if it is called
 * ({@link #has} says). The argument layouts follow the Java types: {@code MemorySegment} is a pointer, {@code Integer} an
 * {@code int} or {@code gboolean}, {@code Long} a {@code long} or {@code gsize}, {@code Double}, and a {@code String} is
 * passed as a UTF-8 C string that lives for the call.
 * <p>
 * <b>Never run.</b> Written without a Linux desktop. {@link #available()} says whether the libraries and a display exist.
 */
final class Gtk {
    private static final Logger log = LogManager.getLogger("WebView");

    private static final Linker LINKER = Linker.nativeLinker();
    private static final ConcurrentHashMap<String, MethodHandle> HANDLES = new ConcurrentHashMap<>();

    private static final SymbolLookup LOOKUP;
    private static final String FAILURE;
    /** "4.1" (libsoup 3) or "4.0" (libsoup 2): the two GTK 3 flavours of WebKitGTK. */
    private static final String API;

    static {
        SymbolLookup lookup = null;
        String failure = null, api = null;
        try {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
                failure = "WebKitGTK is for Linux";
            } else if (blank(System.getenv("DISPLAY")) && blank(System.getenv("WAYLAND_DISPLAY"))) {
                failure = "There is no graphical session (neither DISPLAY nor WAYLAND_DISPLAY is set); WebKitGTK needs one";
            } else {
                List<SymbolLookup> libs = new ArrayList<>();
                SymbolLookup webkit = null;
                for (String[] candidate : new String[][]{{"4.1", "libwebkit2gtk-4.1.so.0", "libjavascriptcoregtk-4.1.so.0"},
                        {"4.0", "libwebkit2gtk-4.0.so.37", "libjavascriptcoregtk-4.0.so.18"}}) {
                    try {
                        webkit = SymbolLookup.libraryLookup(candidate[1], Arena.global());
                        libs.add(webkit);
                        libs.add(SymbolLookup.libraryLookup(candidate[2], Arena.global()));
                        api = candidate[0];
                        break;
                    } catch (IllegalArgumentException notThere) {
                        libs.clear();
                    }
                }
                if (api == null) {
                    failure = "WebKitGTK (libwebkit2gtk-4.1 or libwebkit2gtk-4.0) is not installed";
                } else {
                    for (String name : new String[]{"libgtk-3.so.0", "libgdk-3.so.0", "libcairo.so.2", "libgobject-2.0.so.0",
                            "libglib-2.0.so.0", "libgio-2.0.so.0"}) {
                        libs.add(SymbolLookup.libraryLookup(name, Arena.global()));
                    }
                    SymbolLookup all = libs.get(0);
                    for (int i = 1; i < libs.size(); i++) all = all.or(libs.get(i));
                    lookup = all.or(LINKER.defaultLookup());
                }
            }
        } catch (Throwable t) {
            failure = "GTK 3 or WebKitGTK could not be loaded: " + t;
            lookup = null;
            log.warn("WebKitGTK is not available: {}", failure);
        }
        LOOKUP = lookup;
        FAILURE = failure;
        API = api;
    }

    private Gtk() {
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static boolean available() {
        return LOOKUP != null;
    }

    /** Why {@link #available()} is false, or null. */
    static String failure() {
        return FAILURE;
    }

    /** The WebKitGTK API generation that loaded ("4.1" or "4.0"), or null. */
    static String api() {
        return API;
    }

    static boolean has(String function) {
        return LOOKUP != null && LOOKUP.find(function).isPresent();
    }

    // ------------------------------------------------------------------------------------------------ calls

    private static MemoryLayout layoutOf(Object o) {
        return switch (o) {
            case MemorySegment _, String _ -> ADDRESS;
            case Integer _ -> JAVA_INT;
            case Long _ -> JAVA_LONG;
            case Double _ -> JAVA_DOUBLE;
            case Short _ -> JAVA_SHORT;
            case Byte _ -> JAVA_BYTE;
            default -> throw new IllegalArgumentException("Unsupported argument " + o.getClass());
        };
    }

    private static Object call(String function, int firstVariadic, MemoryLayout ret, Object... args) {
        MemoryLayout[] layouts = new MemoryLayout[args.length];
        for (int i = 0; i < args.length; i++) layouts[i] = layoutOf(args[i]);
        FunctionDescriptor d = ret == null ? FunctionDescriptor.ofVoid(layouts) : FunctionDescriptor.of(ret, layouts);
        String key = function + d + firstVariadic;
        MethodHandle h = HANDLES.computeIfAbsent(key, k -> {
            MemorySegment address = LOOKUP.find(function).orElseThrow(() -> new IllegalStateException("No function " + function));
            return firstVariadic < 0 ? LINKER.downcallHandle(address, d)
                    : LINKER.downcallHandle(address, d, Linker.Option.firstVariadicArg(firstVariadic));
        });
        try (Arena a = Arena.ofConfined()) {
            Object[] converted = new Object[args.length];
            for (int i = 0; i < args.length; i++) converted[i] = args[i] instanceof String s ? a.allocateFrom(s) : args[i];
            return h.invokeWithArguments(converted);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** A call that returns nothing. */
    static void v(String function, Object... args) {
        call(function, -1, null, args);
    }

    /** A call that returns a pointer. */
    static MemorySegment ptr(String function, Object... args) {
        return (MemorySegment) call(function, -1, ADDRESS, args);
    }

    /** A call that returns an {@code int}, {@code gboolean} or enum. */
    static int i(String function, Object... args) {
        return (int) call(function, -1, JAVA_INT, args);
    }

    /** A call that returns a {@code long}, {@code gsize} or {@code GType}. */
    static long l(String function, Object... args) {
        return (long) call(function, -1, JAVA_LONG, args);
    }

    /** A call to a variadic function returning a pointer; {@code firstVariadic} is the index of the first argument after the fixed ones. */
    static MemorySegment variadicPtr(String function, int firstVariadic, Object... args) {
        return (MemorySegment) call(function, firstVariadic, ADDRESS, args);
    }

    static boolean isNull(MemorySegment p) {
        return p == null || p.address() == 0;
    }

    /** A C string as a Java string, or null for NULL. The pointer is not freed. */
    static String string(MemorySegment cString) {
        if (isNull(cString)) return null;
        return cString.reinterpret(Integer.MAX_VALUE).getString(0);
    }

    /** A string the caller owns (freed with {@code g_free}) as a Java string, freeing it. */
    static String takeString(MemorySegment owned) {
        String s = string(owned);
        if (!isNull(owned)) v("g_free", owned);
        return s;
    }

    // ------------------------------------------------------------------------------------------------ callbacks into Java

    /** A C function pointer that calls the static method {@code method} of {@code owner}. It must never throw. */
    static MemorySegment stub(Class<?> owner, String method, FunctionDescriptor descriptor) {
        try {
            Class<?>[] parameters = descriptor.argumentLayouts().stream().map(Gtk::carrier).toArray(Class<?>[]::new);
            Class<?> result = descriptor.returnLayout().map(Gtk::carrier).orElse(void.class);
            MethodHandle target = MethodHandles.lookup().findStatic(owner, method, MethodType.methodType(result, parameters));
            return LINKER.upcallStub(target, descriptor, Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No method " + method + " on " + owner, e);
        }
    }

    private static Class<?> carrier(MemoryLayout layout) {
        if (layout.equals(ADDRESS)) return MemorySegment.class;
        if (layout.equals(JAVA_LONG)) return long.class;
        if (layout.equals(JAVA_INT)) return int.class;
        throw new IllegalArgumentException("Unsupported layout " + layout);
    }

    /** {@code g_signal_connect}: the handler runs on the GTK thread with {@code data} as its last argument. */
    static void connect(MemorySegment instance, String signal, MemorySegment handler, long data) {
        l("g_signal_connect_data", instance, signal, handler, MemorySegment.ofAddress(data), MemorySegment.NULL, 0);
    }
}
