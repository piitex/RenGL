package me.piitex.engine.video;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.*;

/**
 * Just enough of the Objective-C runtime and Apple's media frameworks to read video through AVFoundation, called
 * straight from Java (no native library is shipped). Only touched on macOS; {@link #available()} says whether the
 * frameworks loaded.
 */
final class ObjC {
    private ObjC() {
    }

    /** {@code CMTime}: value, timescale, flags, epoch. */
    static final GroupLayout CMTIME = MemoryLayout.structLayout(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG);
    static final GroupLayout CMTIME_RANGE = MemoryLayout.structLayout(CMTIME, CMTIME);
    static final GroupLayout CGSIZE = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE);

    /** A value passed by value, which needs its layout spelled out. */
    record Struct(MemorySegment segment, MemoryLayout layout) {
    }

    private static final Linker LINKER = Linker.nativeLinker();
    private static final boolean X86 = System.getProperty("os.arch").matches("x86_64|amd64");
    private static final ConcurrentHashMap<FunctionDescriptor, MethodHandle> SENDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, MemorySegment> SELECTORS = new ConcurrentHashMap<>();

    private static final SymbolLookup OBJC_LIB, CF, CM, CV;
    private static final MemorySegment MSG_SEND, MSG_SEND_STRET;
    private static final MethodHandle GET_CLASS, SEL_REGISTER, POOL_PUSH, POOL_POP, CF_RELEASE;
    private static final MethodHandle CM_PTS, CM_IMAGE, CM_DATA, CM_BLOCK_LEN, CM_BLOCK_COPY, CM_SUBTYPE, CM_ASBD;
    private static final MethodHandle CV_LOCK, CV_UNLOCK, CV_BASE, CV_STRIDE, CV_WIDTH, CV_HEIGHT;
    private static final boolean AVAILABLE;

    static {
        boolean ok = false;
        SymbolLookup objc = null, cf = null, cm = null, cv = null;
        MemorySegment send = null, stret = null;
        MethodHandle[] h = new MethodHandle[19];
        try {
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                objc = lib("/usr/lib/libobjc.A.dylib");
                lib("/System/Library/Frameworks/Foundation.framework/Foundation");
                lib("/System/Library/Frameworks/AVFoundation.framework/AVFoundation");
                cf = lib("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation");
                cm = lib("/System/Library/Frameworks/CoreMedia.framework/CoreMedia");
                cv = lib("/System/Library/Frameworks/CoreVideo.framework/CoreVideo");
                send = objc.find("objc_msgSend").orElseThrow();
                stret = X86 ? objc.find("objc_msgSend_stret").orElseThrow() : null;
                h[0] = fn(objc, "objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS));
                h[1] = fn(objc, "sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS));
                h[2] = fn(objc, "objc_autoreleasePoolPush", FunctionDescriptor.of(ADDRESS));
                h[3] = fn(objc, "objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(ADDRESS));
                h[4] = fn(cf, "CFRelease", FunctionDescriptor.ofVoid(ADDRESS));
                h[5] = fn(cm, "CMSampleBufferGetPresentationTimeStamp", FunctionDescriptor.of(CMTIME, ADDRESS));
                h[6] = fn(cm, "CMSampleBufferGetImageBuffer", FunctionDescriptor.of(ADDRESS, ADDRESS));
                h[7] = fn(cm, "CMSampleBufferGetDataBuffer", FunctionDescriptor.of(ADDRESS, ADDRESS));
                h[8] = fn(cm, "CMBlockBufferGetDataLength", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
                h[9] = fn(cm, "CMBlockBufferCopyDataBytes", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS));
                h[10] = fn(cm, "CMFormatDescriptionGetMediaSubType", FunctionDescriptor.of(JAVA_INT, ADDRESS));
                h[11] = fn(cm, "CMAudioFormatDescriptionGetStreamBasicDescription", FunctionDescriptor.of(ADDRESS, ADDRESS));
                h[12] = fn(cv, "CVPixelBufferLockBaseAddress", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
                h[13] = fn(cv, "CVPixelBufferUnlockBaseAddress", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
                h[14] = fn(cv, "CVPixelBufferGetBaseAddress", FunctionDescriptor.of(ADDRESS, ADDRESS));
                h[15] = fn(cv, "CVPixelBufferGetBytesPerRow", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
                h[16] = fn(cv, "CVPixelBufferGetWidth", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
                h[17] = fn(cv, "CVPixelBufferGetHeight", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
                ok = true;
            }
        } catch (Throwable e) {
            ok = false;
        }
        AVAILABLE = ok;
        OBJC_LIB = objc;
        CF = cf;
        CM = cm;
        CV = cv;
        MSG_SEND = send;
        MSG_SEND_STRET = stret;
        GET_CLASS = h[0];
        SEL_REGISTER = h[1];
        POOL_PUSH = h[2];
        POOL_POP = h[3];
        CF_RELEASE = h[4];
        CM_PTS = h[5];
        CM_IMAGE = h[6];
        CM_DATA = h[7];
        CM_BLOCK_LEN = h[8];
        CM_BLOCK_COPY = h[9];
        CM_SUBTYPE = h[10];
        CM_ASBD = h[11];
        CV_LOCK = h[12];
        CV_UNLOCK = h[13];
        CV_BASE = h[14];
        CV_STRIDE = h[15];
        CV_WIDTH = h[16];
        CV_HEIGHT = h[17];
    }

    private static SymbolLookup lib(String path) {
        return SymbolLookup.libraryLookup(path, Arena.global());
    }

    private static MethodHandle fn(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.find(name).orElseThrow(), descriptor);
    }

    static boolean available() {
        return AVAILABLE;
    }

    // ------------------------------------------------------------------ the runtime

    /** Frees what the code in between autoreleased. Wrap every stretch of calls in one. */
    static final class Pool implements AutoCloseable {
        private final MemorySegment context = (MemorySegment) invoke(POOL_PUSH);

        @Override
        public void close() {
            invoke(POOL_POP, context);
        }
    }

    static Pool pool() {
        return new Pool();
    }

    static MemorySegment cls(String name) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment c = (MemorySegment) invoke(GET_CLASS, a.allocateFrom(name));
            if (c.equals(MemorySegment.NULL)) throw new IllegalStateException("No Objective-C class " + name);
            return c;
        }
    }

    private static MemorySegment sel(String name) {
        return SELECTORS.computeIfAbsent(name, n -> {
            try (Arena a = Arena.ofConfined()) {
                return (MemorySegment) invoke(SEL_REGISTER, a.allocateFrom(n));
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

    /**
     * Sends a message. {@code ret} is the return layout, or null for none. A struct return needs {@code arena} to
     * allocate into.
     */
    static Object send(MemoryLayout ret, Arena arena, MemorySegment self, String selector, Object... args) {
        boolean stret = X86 && ret instanceof GroupLayout g && g.byteSize() > 16;
        int lead = stret ? 3 : 2;
        MemoryLayout[] layouts = new MemoryLayout[args.length + lead];
        Object[] call = new Object[args.length + lead + (!stret && ret instanceof GroupLayout ? 1 : 0)];
        int at = 0;
        MemorySegment result = null;
        if (!stret && ret instanceof GroupLayout) call[at++] = arena;
        if (stret) {
            result = arena.allocate(ret);
            layouts[0] = ADDRESS;
            call[at++] = result;
        }
        layouts[stret ? 1 : 0] = ADDRESS;
        layouts[stret ? 2 : 1] = ADDRESS;
        call[at++] = self;
        call[at++] = sel(selector);
        for (int i = 0; i < args.length; i++) {
            layouts[lead + i] = layoutOf(args[i]);
            call[at++] = plain(args[i]);
        }
        FunctionDescriptor d = stret || ret == null ? FunctionDescriptor.ofVoid(layouts) : FunctionDescriptor.of(ret, layouts);
        MethodHandle h = SENDS.computeIfAbsent(d, key -> LINKER.downcallHandle(stret ? MSG_SEND_STRET : MSG_SEND, key));
        Object out = invoke(h, call);
        return stret ? result : out;
    }

    static MemorySegment id(MemorySegment self, String selector, Object... args) {
        return (MemorySegment) send(ADDRESS, null, self, selector, args);
    }

    static long lng(MemorySegment self, String selector, Object... args) {
        return (long) send(JAVA_LONG, null, self, selector, args);
    }

    static boolean bool(MemorySegment self, String selector, Object... args) {
        return (byte) send(JAVA_BYTE, null, self, selector, args) != 0;
    }

    static float flt(MemorySegment self, String selector, Object... args) {
        return (float) send(JAVA_FLOAT, null, self, selector, args);
    }

    static void run(MemorySegment self, String selector, Object... args) {
        send(null, null, self, selector, args);
    }

    static MemorySegment struct(MemoryLayout layout, Arena arena, MemorySegment self, String selector, Object... args) {
        return (MemorySegment) send(layout, arena, self, selector, args);
    }

    static MemorySegment retain(MemorySegment object) {
        return id(object, "retain");
    }

    static void release(MemorySegment object) {
        if (object != null && !object.equals(MemorySegment.NULL)) run(object, "release");
    }

    static boolean isNull(MemorySegment p) {
        return p == null || p.equals(MemorySegment.NULL);
    }

    // ------------------------------------------------------------------ Foundation helpers

    static MemorySegment string(Arena arena, String s) {
        return id(cls("NSString"), "stringWithUTF8String:", arena.allocateFrom(s));
    }

    static String text(MemorySegment nsString) {
        if (isNull(nsString)) return "";
        MemorySegment chars = id(nsString, "UTF8String");
        return isNull(chars) ? "" : chars.reinterpret(1 << 16).getString(0);
    }

    static MemorySegment number(int value) {
        return id(cls("NSNumber"), "numberWithInt:", value);
    }

    static MemorySegment number(double value) {
        return id(cls("NSNumber"), "numberWithDouble:", value);
    }

    static MemorySegment number(boolean value) {
        return id(cls("NSNumber"), "numberWithBool:", value);
    }

    static MemorySegment dictionary(Arena arena, MemorySegment[] keys, MemorySegment[] values) {
        MemorySegment k = arena.allocate(ADDRESS, keys.length), v = arena.allocate(ADDRESS, values.length);
        for (int i = 0; i < keys.length; i++) {
            k.setAtIndex(ADDRESS, i, keys[i]);
            v.setAtIndex(ADDRESS, i, values[i]);
        }
        return id(cls("NSDictionary"), "dictionaryWithObjects:forKeys:count:", v, k, (long) keys.length);
    }

    // ------------------------------------------------------------------ CoreMedia and CoreVideo

    /** A CMTime as seconds. */
    static double seconds(MemorySegment cmTime) {
        long value = cmTime.get(JAVA_LONG, 0);
        int scale = cmTime.get(JAVA_INT, 8);
        int flags = cmTime.get(JAVA_INT, 12);
        return (flags & 1) != 0 && scale != 0 ? value / (double) scale : Double.NaN;
    }

    static MemorySegment time(Arena arena, double seconds) {
        MemorySegment t = arena.allocate(CMTIME);
        t.set(JAVA_LONG, 0, Math.round(seconds * 90_000));
        t.set(JAVA_INT, 8, 90_000);
        t.set(JAVA_INT, 12, 1); // valid
        return t;
    }

    static MemorySegment range(Arena arena, double start, double duration) {
        MemorySegment r = arena.allocate(CMTIME_RANGE);
        MemorySegment.copy(time(arena, start), 0, r, 0, 24);
        MemorySegment.copy(time(arena, duration), 0, r, 24, 24);
        return r;
    }

    static void cfRelease(MemorySegment object) {
        if (!isNull(object)) invoke(CF_RELEASE, object);
    }

    static double presentationTime(Arena arena, MemorySegment sampleBuffer) {
        return seconds((MemorySegment) invoke(CM_PTS, arena, sampleBuffer));
    }

    static MemorySegment imageBuffer(MemorySegment sampleBuffer) {
        return (MemorySegment) invoke(CM_IMAGE, sampleBuffer);
    }

    static MemorySegment dataBuffer(MemorySegment sampleBuffer) {
        return (MemorySegment) invoke(CM_DATA, sampleBuffer);
    }

    static long blockLength(MemorySegment blockBuffer) {
        return (long) invoke(CM_BLOCK_LEN, blockBuffer);
    }

    static int copyBlock(MemorySegment blockBuffer, long length, MemorySegment destination) {
        return (int) invoke(CM_BLOCK_COPY, blockBuffer, 0L, length, destination);
    }

    static int mediaSubType(MemorySegment formatDescription) {
        return (int) invoke(CM_SUBTYPE, formatDescription);
    }

    /** The audio stream description of a format description (40 bytes), or null. */
    static MemorySegment audioDescription(MemorySegment formatDescription) {
        MemorySegment p = (MemorySegment) invoke(CM_ASBD, formatDescription);
        return isNull(p) ? null : p.reinterpret(40);
    }

    static String fourcc(int code) {
        return new String(new char[]{(char) (code >>> 24 & 0xFF), (char) (code >>> 16 & 0xFF), (char) (code >>> 8 & 0xFF), (char) (code & 0xFF)});
    }

    static boolean lockPixels(MemorySegment pixelBuffer) {
        return (int) invoke(CV_LOCK, pixelBuffer, 1L) == 0; // read only
    }

    static void unlockPixels(MemorySegment pixelBuffer) {
        invoke(CV_UNLOCK, pixelBuffer, 1L);
    }

    static MemorySegment pixelBase(MemorySegment pixelBuffer) {
        return (MemorySegment) invoke(CV_BASE, pixelBuffer);
    }

    static long pixelStride(MemorySegment pixelBuffer) {
        return (long) invoke(CV_STRIDE, pixelBuffer);
    }

    static int pixelWidth(MemorySegment pixelBuffer) {
        return (int) (long) invoke(CV_WIDTH, pixelBuffer);
    }

    static int pixelHeight(MemorySegment pixelBuffer) {
        return (int) (long) invoke(CV_HEIGHT, pixelBuffer);
    }

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
