package me.piitex.engine.webview.macos;

import me.piitex.engine.webview.webkit.WebKitBackend;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static java.lang.foreign.ValueLayout.*;

/**
 * The Objective-C runtime, AppKit and CoreGraphics calls the WKWebView engine needs, made from Java with no native code of
 * ours. Only touched on macOS; {@link #available()} says whether the frameworks loaded.
 * <p>
 * It is a second copy of what {@code rengl-video} and {@code rengl-upscale} each keep (their copies are package-private to
 * those modules and cover other frameworks); folding the three into one shared class is left for when one of them has
 * been run on a Mac. This one adds what a UI engine needs and they do not: classes defined at run time, blocks, calls
 * onto the main thread, struct arguments and a few CoreGraphics functions.
 * <p>
 * <b>Never run.</b> It was written without a Mac, from the documented calling conventions.
 */
final class ObjC {
    private static final Logger log = LogManager.getLogger("WebView");

    static final GroupLayout NSPOINT = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE);
    static final GroupLayout NSRECT = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
    static final GroupLayout NSRANGE = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG);

    /** {@code NSNotFound}. */
    static final long NOT_FOUND = Long.MAX_VALUE;
    /** kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little: BGRA bytes, premultiplied. */
    private static final int BITMAP_INFO = 2 | (2 << 12);
    private static final int BLOCK_IS_GLOBAL = 1 << 28;

    /** A value passed by value, which needs its layout spelled out. */
    record Struct(MemorySegment segment, MemoryLayout layout) {
    }

    /** A method of a class defined at run time: its selector, type encoding and the Java code behind it. */
    record Imp(String selector, String encoding, MethodHandle target, FunctionDescriptor descriptor) {
    }

    private static final Linker LINKER = Linker.nativeLinker();
    private static final ConcurrentHashMap<FunctionDescriptor, MethodHandle> SENDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<FunctionDescriptor, MethodHandle> BLOCK_CALLS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, MemorySegment> SELECTORS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Runnable> MAIN = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<Long, PendingBlock> BLOCKS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService REAPER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "RenGL-WebView-blocks");
        t.setDaemon(true);
        return t;
    });

    private static final Natives N;
    private static final String FAILURE;

    static {
        Natives loaded = null;
        String failure = null;
        try {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
                failure = "WKWebView is Apple's and needs macOS";
            } else {
                loaded = new Natives();
            }
        } catch (Throwable t) {
            failure = "The Objective-C runtime or the WebKit framework could not be loaded: " + t;
            log.warn("WKWebView is not available: {}", failure);
        }
        N = loaded;
        FAILURE = failure;
    }

    private ObjC() {
    }

    /** The native handles. Built once, on macOS only. */
    private static final class Natives {
        final MethodHandle getClass, registerName, poolPush, poolPop, allocatePair, registerPair, addMethod, addProtocol, getProtocol;
        final MethodHandle dispatchAsyncF, runLoop;
        final MethodHandle cgWidth, cgHeight, cgColorSpace, cgBitmap, cgDraw, cgRelease, csRelease, cgScroll, cgSetLocation, cfRelease,
                mainDisplay, displayHeight;
        final MemorySegment msgSend, mainQueue, drainStub, blockStub, blockIsa, runLoopMode, blockDescriptor;

        Natives() throws Throwable {
            SymbolLookup objc = lib("/usr/lib/libobjc.A.dylib");
            SymbolLookup system = lib("/usr/lib/libSystem.B.dylib");
            lib("/System/Library/Frameworks/Foundation.framework/Foundation");
            lib("/System/Library/Frameworks/AppKit.framework/AppKit");
            lib("/System/Library/Frameworks/WebKit.framework/WebKit");
            SymbolLookup cf = lib("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation");
            SymbolLookup cg = lib("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics");

            msgSend = objc.find("objc_msgSend").orElseThrow();
            getClass = fn(objc, "objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS));
            registerName = fn(objc, "sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS));
            poolPush = fn(objc, "objc_autoreleasePoolPush", FunctionDescriptor.of(ADDRESS));
            poolPop = fn(objc, "objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(ADDRESS));
            allocatePair = fn(objc, "objc_allocateClassPair", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
            registerPair = fn(objc, "objc_registerClassPair", FunctionDescriptor.ofVoid(ADDRESS));
            addMethod = fn(objc, "class_addMethod", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            addProtocol = fn(objc, "class_addProtocol", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS));
            getProtocol = fn(objc, "objc_getProtocol", FunctionDescriptor.of(ADDRESS, ADDRESS));

            dispatchAsyncF = fn(system, "dispatch_async_f", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
            mainQueue = system.find("_dispatch_main_q").orElseThrow();
            blockIsa = system.find("_NSConcreteGlobalBlock").orElseThrow();
            runLoop = fn(cf, "CFRunLoopRunInMode", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_BYTE));
            runLoopMode = cf.find("kCFRunLoopDefaultMode").orElseThrow().reinterpret(8).get(ADDRESS, 0);
            cfRelease = fn(cf, "CFRelease", FunctionDescriptor.ofVoid(ADDRESS));

            cgWidth = fn(cg, "CGImageGetWidth", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            cgHeight = fn(cg, "CGImageGetHeight", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            cgColorSpace = fn(cg, "CGColorSpaceCreateDeviceRGB", FunctionDescriptor.of(ADDRESS));
            cgBitmap = fn(cg, "CGBitmapContextCreate",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
            cgDraw = fn(cg, "CGContextDrawImage", FunctionDescriptor.ofVoid(ADDRESS, NSRECT, ADDRESS));
            cgRelease = fn(cg, "CGContextRelease", FunctionDescriptor.ofVoid(ADDRESS));
            csRelease = fn(cg, "CGColorSpaceRelease", FunctionDescriptor.ofVoid(ADDRESS));
            cgScroll = fn(cg, "CGEventCreateScrollWheelEvent2",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
            cgSetLocation = fn(cg, "CGEventSetLocation", FunctionDescriptor.ofVoid(ADDRESS, NSPOINT));
            mainDisplay = fn(cg, "CGMainDisplayID", FunctionDescriptor.of(JAVA_INT));
            displayHeight = fn(cg, "CGDisplayPixelsHigh", FunctionDescriptor.of(JAVA_LONG, JAVA_INT));

            drainStub = drainStub();
            blockStub = blockStub();
            // struct Block_descriptor { unsigned long reserved; unsigned long size; }
            blockDescriptor = Arena.global().allocate(16, 8);
            blockDescriptor.set(JAVA_LONG, 0, 0L);
            blockDescriptor.set(JAVA_LONG, 8, 32L);
        }

        private static SymbolLookup lib(String path) {
            return SymbolLookup.libraryLookup(path, Arena.global());
        }

        private static MethodHandle fn(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(lookup.find(name).orElseThrow(), descriptor);
        }
    }

    /** The C function {@code dispatch_async_f} calls on the main thread. */
    static MemorySegment drainStub() {
        try {
            return LINKER.upcallStub(MethodHandles.lookup().findStatic(ObjC.class, "drain", MethodType.methodType(void.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ADDRESS), Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The C function every block made by {@link #block} points at. */
    static MemorySegment blockStub() {
        try {
            return LINKER.upcallStub(MethodHandles.lookup().findStatic(ObjC.class, "blockInvoke",
                            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS), Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean available() {
        return N != null;
    }

    /** Why {@link #available()} is false, or null. */
    static String failure() {
        return FAILURE;
    }

    static boolean hasClass(String name) {
        if (N == null) return false;
        try (Arena a = Arena.ofConfined()) {
            return !isNull((MemorySegment) invoke(N.getClass, a.allocateFrom(name)));
        }
    }

    // ------------------------------------------------------------------------------------------------ the runtime

    /** Frees what the code in between autoreleased. Wrap every stretch of calls made from a thread of Java in one. */
    static final class Pool implements AutoCloseable {
        private final MemorySegment context = (MemorySegment) invoke(N.poolPush);

        @Override
        public void close() {
            invoke(N.poolPop, context);
        }
    }

    static Pool pool() {
        return new Pool();
    }

    static MemorySegment cls(String name) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment c = (MemorySegment) invoke(N.getClass, a.allocateFrom(name));
            if (isNull(c)) throw new IllegalStateException("No Objective-C class " + name);
            return c;
        }
    }

    static MemorySegment selector(String name) {
        return SELECTORS.computeIfAbsent(name, n -> {
            try (Arena a = Arena.ofConfined()) {
                return (MemorySegment) invoke(N.registerName, a.allocateFrom(n));
            }
        });
    }

    private static MemoryLayout layoutOf(Object o) {
        return switch (o) {
            case MemorySegment _ -> ADDRESS;
            case Struct s -> s.layout();
            case Integer _ -> JAVA_INT;
            case Long _ -> JAVA_LONG;
            case Double _ -> JAVA_DOUBLE;
            case Float _ -> JAVA_FLOAT;
            case Short _ -> JAVA_SHORT;
            case Byte _, Boolean _ -> JAVA_BYTE;
            default -> throw new IllegalArgumentException("Unsupported argument " + o.getClass());
        };
    }

    private static Object plain(Object o) {
        return switch (o) {
            case Struct s -> s.segment();
            case Boolean b -> (byte) (b ? 1 : 0);
            default -> o;
        };
    }

    /** {@code [self selector:args...]}. {@code ret} is the return layout, or null for none. Never returns a structure. */
    static Object send(MemoryLayout ret, MemorySegment self, String selector, Object... args) {
        MemoryLayout[] layouts = new MemoryLayout[args.length + 2];
        Object[] call = new Object[args.length + 2];
        layouts[0] = ADDRESS;
        layouts[1] = ADDRESS;
        call[0] = self;
        call[1] = selector(selector);
        for (int i = 0; i < args.length; i++) {
            layouts[2 + i] = layoutOf(args[i]);
            call[2 + i] = plain(args[i]);
        }
        FunctionDescriptor d = ret == null ? FunctionDescriptor.ofVoid(layouts) : FunctionDescriptor.of(ret, layouts);
        MethodHandle h = SENDS.computeIfAbsent(d, key -> LINKER.downcallHandle(N.msgSend, key));
        return invoke(h, call);
    }

    static MemorySegment id(MemorySegment self, String selector, Object... args) {
        return (MemorySegment) send(ADDRESS, self, selector, args);
    }

    static long lng(MemorySegment self, String selector, Object... args) {
        return (long) send(JAVA_LONG, self, selector, args);
    }

    static double dbl(MemorySegment self, String selector, Object... args) {
        return (double) send(JAVA_DOUBLE, self, selector, args);
    }

    static boolean bool(MemorySegment self, String selector, Object... args) {
        return (byte) send(JAVA_BYTE, self, selector, args) != 0;
    }

    static void run(MemorySegment self, String selector, Object... args) {
        send(null, self, selector, args);
    }

    static boolean responds(MemorySegment self, String selector) {
        return bool(self, "respondsToSelector:", selector(selector));
    }

    static boolean isNull(MemorySegment p) {
        return p == null || p.equals(MemorySegment.NULL) || p.address() == 0;
    }

    static MemorySegment alloc(String className) {
        return id(cls(className), "alloc");
    }

    static void release(MemorySegment object) {
        if (!isNull(object)) run(object, "release");
    }

    // ------------------------------------------------------------------------------------------------ values

    static Struct point(Arena a, double x, double y) {
        MemorySegment s = a.allocate(NSPOINT);
        s.set(JAVA_DOUBLE, 0, x);
        s.set(JAVA_DOUBLE, 8, y);
        return new Struct(s, NSPOINT);
    }

    static Struct rect(Arena a, double x, double y, double w, double h) {
        MemorySegment s = a.allocate(NSRECT);
        s.set(JAVA_DOUBLE, 0, x);
        s.set(JAVA_DOUBLE, 8, y);
        s.set(JAVA_DOUBLE, 16, w);
        s.set(JAVA_DOUBLE, 24, h);
        return new Struct(s, NSRECT);
    }

    static Struct range(Arena a, long location, long length) {
        MemorySegment s = a.allocate(NSRANGE);
        s.set(JAVA_LONG, 0, location);
        s.set(JAVA_LONG, 8, length);
        return new Struct(s, NSRANGE);
    }

    static MemorySegment string(Arena a, String s) {
        return id(cls("NSString"), "stringWithUTF8String:", a.allocateFrom(s));
    }

    /** A Java string as an {@code NSString} that lives until the enclosing pool drains. */
    static MemorySegment string(String s) {
        try (Arena a = Arena.ofConfined()) {
            return string(a, s);
        }
    }

    static String text(MemorySegment nsString) {
        if (isNull(nsString)) return null;
        MemorySegment chars = id(nsString, "UTF8String");
        if (isNull(chars)) return null;
        long length = lng(nsString, "lengthOfBytesUsingEncoding:", 4L /* NSUTF8StringEncoding */);
        return chars.reinterpret(length + 1).getString(0);
    }

    /** A string for an object that is an {@code NSString} or something with a description (a number). */
    static String describe(MemorySegment object) {
        if (isNull(object)) return null;
        if (bool(object, "isKindOfClass:", cls("NSString"))) return text(object);
        return text(id(object, "description"));
    }

    static MemorySegment url(String address) {
        return id(cls("NSURL"), "URLWithString:", string(address));
    }

    // ------------------------------------------------------------------------------------------------ classes defined at run time

    /** An {@link Imp} for a static method of {@code owner} whose Java signature matches {@code descriptor}. */
    static Imp imp(String selector, String encoding, Class<?> owner, String method, FunctionDescriptor descriptor) {
        try {
            Class<?>[] parameters = descriptor.argumentLayouts().stream().map(ObjC::carrier).toArray(Class<?>[]::new);
            Class<?> result = descriptor.returnLayout().map(ObjC::carrier).orElse(void.class);
            MethodHandle target = MethodHandles.lookup().findStatic(owner, method, MethodType.methodType(result, parameters));
            return new Imp(selector, encoding, target, descriptor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No method " + method + " on " + owner, e);
        }
    }

    private static Class<?> carrier(MemoryLayout layout) {
        if (layout.equals(ADDRESS)) return MemorySegment.class;
        if (layout.equals(JAVA_LONG)) return long.class;
        if (layout.equals(JAVA_INT)) return int.class;
        if (layout.equals(JAVA_BYTE)) return byte.class;
        if (layout.equals(JAVA_DOUBLE)) return double.class;
        throw new IllegalArgumentException("Unsupported layout " + layout);
    }

    /**
     * Defines (or finds, if an earlier call did) a subclass of {@code superclass} with these methods. Each method is an
     * upcall into Java, so it must never throw: an exception that escapes would end the process.
     */
    static MemorySegment defineClass(String name, String superclass, List<String> protocols, List<Imp> methods) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment existing = (MemorySegment) invoke(N.getClass, a.allocateFrom(name));
            if (!isNull(existing)) return existing;
        }
        MemorySegment c = (MemorySegment) invoke(N.allocatePair, cls(superclass), Arena.global().allocateFrom(name), 0L);
        if (isNull(c)) throw new IllegalStateException("Could not create the class " + name);
        for (Imp m : methods) {
            MemorySegment stub = LINKER.upcallStub(m.target(), m.descriptor(), Arena.global());
            invoke(N.addMethod, c, selector(m.selector()), stub, Arena.global().allocateFrom(m.encoding()));
        }
        for (String p : protocols) {
            MemorySegment protocol = (MemorySegment) invoke(N.getProtocol, Arena.global().allocateFrom(p));
            if (!isNull(protocol)) invoke(N.addProtocol, c, protocol);
        }
        invoke(N.registerPair, c);
        return c;
    }

    // ------------------------------------------------------------------------------------------------ blocks

    private record PendingBlock(MemorySegment memory, BiConsumer<MemorySegment, MemorySegment> handler) {
    }

    /**
     * An Objective-C block taking two objects and returning nothing (a completion handler such as
     * {@code (id result, NSError *error)}), backed by {@code handler}. It is a "global" block, which the runtime never
     * copies, so it can be handed to any API. Its memory is kept for a few seconds after it is called.
     */
    static MemorySegment block(BiConsumer<MemorySegment, MemorySegment> handler) {
        MemorySegment b = Arena.ofAuto().allocate(32, 8);
        b.set(ADDRESS, 0, N.blockIsa);
        b.set(JAVA_INT, 8, BLOCK_IS_GLOBAL);
        b.set(JAVA_INT, 12, 0);
        b.set(ADDRESS, 16, N.blockStub);
        b.set(ADDRESS, 24, N.blockDescriptor);
        BLOCKS.put(b.address(), new PendingBlock(b, handler));
        return b;
    }

    /** The one function every block of {@link #block} points at. Runs on whatever thread the API calls it on. */
    static void blockInvoke(MemorySegment block, MemorySegment a, MemorySegment b) {
        PendingBlock pending = BLOCKS.get(block.address());
        if (pending == null) return;
        try (Pool pool = pool()) {
            pending.handler().accept(a, b);
        } catch (Throwable t) {
            log.warn("A web view completion handler failed: {}", t.toString());
        } finally {
            try {
                REAPER.schedule(() -> BLOCKS.remove(block.address()), 10, TimeUnit.SECONDS);
            } catch (RuntimeException ignored) {
                // shutting down
            }
        }
    }

    /** Calls a block that takes one {@code NSInteger} (a policy decision handler). */
    static void callBlock(MemorySegment block, long argument) {
        if (isNull(block)) return;
        MemorySegment function = block.reinterpret(32).get(ADDRESS, 16);
        FunctionDescriptor d = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG);
        MethodHandle h = BLOCK_CALLS.computeIfAbsent(d, LINKER::downcallHandle);
        invoke(h, function, block, argument);
    }

    // ------------------------------------------------------------------------------------------------ the main thread

    /** Runs {@code task} on the main thread, in the main run loop. It is not run if nothing pumps that loop. */
    static void onMainThread(Runnable task) {
        MAIN.add(task);
        invoke(N.dispatchAsyncF, N.mainQueue, MemorySegment.NULL, N.drainStub);
    }

    static boolean isMainThread() {
        return bool(cls("NSThread"), "isMainThread");
    }

    /** The function {@code dispatch_async_f} calls on the main thread. */
    static void drain(MemorySegment context) {
        try (Pool pool = pool()) {
            Runnable task;
            while ((task = MAIN.poll()) != null) {
                try {
                    task.run();
                } catch (Throwable t) {
                    log.warn("A web view task failed: {}", t.toString());
                }
            }
        } catch (Throwable t) {
            log.warn("The web view task loop failed: {}", t.toString());
        }
    }

    /**
     * Runs the main run loop for about this long, from the main thread. A program with a window of its own does this
     * already (GLFW's event polling is it). A program that does not, such as a test run with {@code -XstartOnFirstThread},
     * calls it so the web view's tasks get to run.
     */
    static void pump(long millis) {
        long end = System.nanoTime() + millis * 1_000_000L;
        do {
            double seconds = Math.max(0.001, (end - System.nanoTime()) / 1e9);
            int result = (int) invoke(N.runLoop, N.runLoopMode, seconds, (byte) 1);
            if (result == 1 /* kCFRunLoopRunFinished: nothing to run */) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } while (System.nanoTime() < end);
    }

    // ------------------------------------------------------------------------------------------------ pictures and scrolling

    /** An {@code NSImage} as raw BGRA pixels, at the image's own pixel size (twice the points on a Retina display). */
    static WebKitBackend.Picture picture(MemorySegment nsImage) {
        MemorySegment image = id(nsImage, "CGImageForProposedRect:context:hints:", MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
        if (isNull(image)) return null;
        long w = (long) invoke(N.cgWidth, image), h = (long) invoke(N.cgHeight, image);
        if (w <= 0 || h <= 0 || w * h * 4 > Integer.MAX_VALUE - 16) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pixels = a.allocate(w * h * 4, 16);
            MemorySegment colors = (MemorySegment) invoke(N.cgColorSpace);
            MemorySegment context = (MemorySegment) invoke(N.cgBitmap, pixels, w, h, 8L, w * 4, colors, BITMAP_INFO);
            invoke(N.csRelease, colors);
            if (isNull(context)) return null;
            invoke(N.cgDraw, context, rect(a, 0, 0, w, h).segment(), image);
            invoke(N.cgRelease, context);
            return new WebKitBackend.Picture((int) w, (int) h, pixels.toArray(JAVA_BYTE));
        }
    }

    /** The height of the main display in points, which converts between AppKit's and CoreGraphics' coordinates. */
    static double mainDisplayHeight() {
        return (long) invoke(N.displayHeight, (int) invoke(N.mainDisplay));
    }

    /**
     * A scroll-wheel {@code NSEvent}: AppKit cannot build one directly, so it is made from a CoreGraphics event, which
     * carries its position in global display coordinates. With the web view's window at the screen's bottom-left corner
     * those are the window's own coordinates, which is what the page reads.
     *
     * @return the event, or null when CoreGraphics gave none
     */
    static MemorySegment scrollEvent(Arena a, int pixelsX, int pixelsY, double displayX, double displayY) {
        MemorySegment cg = (MemorySegment) invoke(N.cgScroll, MemorySegment.NULL, 0 /* pixels */, 2, pixelsY, pixelsX, 0);
        if (isNull(cg)) return null;
        try {
            invoke(N.cgSetLocation, cg, point(a, displayX, displayY).segment());
            return id(cls("NSEvent"), "eventWithCGEvent:", cg);
        } finally {
            invoke(N.cfRelease, cg);
        }
    }

    // ------------------------------------------------------------------------------------------------ plumbing

    private static Object invoke(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
