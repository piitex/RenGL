package me.piitex.engine.upscale.metalfx;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The few Objective-C calls MetalFX needs, made through the Objective-C runtime ({@code objc_msgSend}) so that no native code
 * of our own is involved. Every object is a {@code long}, the address of an {@code id}; a selector is its name with the colons
 * ({@code "setColorTexture:"}). Only macOS has the runtime, so {@link #load()} gives nothing anywhere else.
 * <p>
 * {@code objc_msgSend} takes the argument types of the method it ends up calling, so there is one handle for each shape used
 * here. None returns a structure, which on Intel would need {@code objc_msgSend_stret}. The pointer-sized values (an object,
 * an {@code NSUInteger}) are all {@code long}.
 */
final class ObjC {
    private static final Logger log = LogManager.getLogger("Engine");

    private static final String RUNTIME = "/usr/lib/libobjc.A.dylib";
    private static final String METAL_FX = "/System/Library/Frameworks/MetalFX.framework/MetalFX";

    private static ObjC instance;
    private static boolean tried;
    private static String failure = "not looked for yet";

    private final MethodHandle getClass, registerName, poolPush, poolPop;
    private final MethodHandle sendObject, sendObjectArg, sendVoid, sendVoidArg, sendBool, sendFloat, sendLong, askBool;
    private final Map<String, Long> selectors = new ConcurrentHashMap<>();

    private ObjC() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup runtime = SymbolLookup.libraryLookup(RUNTIME, Arena.global());
        getClass = linker.downcallHandle(runtime.find("objc_getClass").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
        registerName = linker.downcallHandle(runtime.find("sel_registerName").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
        poolPush = linker.downcallHandle(runtime.find("objc_autoreleasePoolPush").orElseThrow(), FunctionDescriptor.of(JAVA_LONG));
        poolPop = linker.downcallHandle(runtime.find("objc_autoreleasePoolPop").orElseThrow(), FunctionDescriptor.ofVoid(JAVA_LONG));
        MemorySegment msgSend = runtime.find("objc_msgSend").orElseThrow();
        sendObject = linker.downcallHandle(msgSend, FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG));
        sendObjectArg = linker.downcallHandle(msgSend, FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        sendVoid = linker.downcallHandle(msgSend, FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG));
        sendVoidArg = linker.downcallHandle(msgSend, FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG));
        sendBool = linker.downcallHandle(msgSend, FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_BOOLEAN));
        sendFloat = linker.downcallHandle(msgSend, FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_FLOAT));
        sendLong = linker.downcallHandle(msgSend, FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG));
        askBool = linker.downcallHandle(msgSend, FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        // Loading the framework is what registers its classes with the runtime.
        SymbolLookup.libraryLookup(METAL_FX, Arena.global());
    }

    /**
     * The runtime, or null off macOS or where MetalFX (macOS 13 and newer) is missing; {@link #failure()} says why.
     */
    static synchronized ObjC load() {
        if (tried) return instance;
        tried = true;
        if (!isMac()) {
            failure = "MetalFX is Apple's and needs macOS";
            return null;
        }
        try {
            instance = new ObjC();
            failure = null;
        } catch (Throwable t) {
            failure = "the Objective-C runtime or the MetalFX framework (macOS 13 or newer) could not be loaded: " + t;
            log.warn("MetalFX is not available: {}", failure);
        }
        return instance;
    }

    /** Why {@link #load()} gave nothing, or null if it did. */
    static synchronized String failure() {
        return instance == null ? failure : null;
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    // ---- calls --------------------------------------------------------------------------------------------------------

    /** The class of that name, or 0 where it does not exist (a framework that is not there, or an older macOS). */
    long cls(String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (long) getClass.invokeExact(arena.allocateFrom(name).address());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private long sel(String name) {
        return selectors.computeIfAbsent(name, n -> {
            try (Arena arena = Arena.ofConfined()) {
                return (long) registerName.invokeExact(arena.allocateFrom(n).address());
            } catch (Throwable t) {
                throw rethrow(t);
            }
        });
    }

    /** {@code [receiver selector]}, returning an object. */
    long object(long receiver, String selector) {
        try {
            return (long) sendObject.invokeExact(receiver, sel(selector));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code [receiver selector:argument]}, returning an object. */
    long object(long receiver, String selector, long argument) {
        try {
            return (long) sendObjectArg.invokeExact(receiver, sel(selector), argument);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code [receiver selector]}, returning nothing. */
    void call(long receiver, String selector) {
        try {
            sendVoid.invokeExact(receiver, sel(selector));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code [receiver selector:argument]} for an object or an {@code NSUInteger} argument, returning nothing. */
    void call(long receiver, String selector, long argument) {
        try {
            sendVoidArg.invokeExact(receiver, sel(selector), argument);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    void call(long receiver, String selector, boolean argument) {
        try {
            sendBool.invokeExact(receiver, sel(selector), argument);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    void call(long receiver, String selector, float argument) {
        try {
            sendFloat.invokeExact(receiver, sel(selector), argument);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code [receiver selector]} where the method returns an {@code NSUInteger}. */
    long number(long receiver, String selector) {
        try {
            return (long) sendLong.invokeExact(receiver, sel(selector));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code [receiver selector:argument]} where the method returns a {@code BOOL}. */
    boolean ask(long receiver, String selector, long argument) {
        try {
            return (boolean) askBool.invokeExact(receiver, sel(selector), argument);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Whether the object answers the selector: the properties of newer macOS versions are only called where they exist. */
    boolean responds(long receiver, String selector) {
        return ask(receiver, "respondsToSelector:", sel(selector));
    }

    void release(long object) {
        if (object != 0) call(object, "release");
    }

    /**
     * Objects the runtime hands back "autoreleased" (a command buffer, for one) belong to a pool, and a thread of Java has none,
     * so each frame's work is bracketed: {@code long pool = push(); try { ... } finally { pop(pool); }}.
     */
    long push() {
        try {
            return (long) poolPush.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    void pop(long pool) {
        try {
            poolPop.invokeExact(pool);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException e) throw e;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }
}
