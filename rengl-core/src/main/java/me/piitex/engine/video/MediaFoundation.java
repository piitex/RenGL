package me.piitex.engine.video;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.*;

/**
 * Just enough of COM and Windows' Media Foundation to read video through a Source Reader, called straight from Java
 * (no native library is shipped). Only touched on Windows; {@link #available()} says whether the libraries loaded and
 * Media Foundation started (an "N" edition of Windows without the media pack has none).
 * <p>
 * A COM object is a pointer to a pointer to a table of functions. {@link #call} reads the table, picks the function by
 * its slot (counted from {@code IUnknown}'s three: QueryInterface, AddRef, Release) and passes the object as the first
 * argument. Every method used here returns an {@code HRESULT}.
 */
final class MediaFoundation {
    private MediaFoundation() {
    }

    // --- slots of the interfaces this class's users call
    static final int RELEASE = 2;
    // IMFAttributes, which IMFMediaType and IMFSample extend
    static final int GET_UINT32 = 7, GET_UINT64 = 8, GET_GUID = 10, GET_BLOB = 15, SET_UINT32 = 21, SET_UINT64 = 22, SET_GUID = 24;
    // IMFSample
    static final int SAMPLE_CONVERT_TO_CONTIGUOUS = 41;
    // IMFMediaBuffer
    static final int BUFFER_LOCK = 3, BUFFER_UNLOCK = 4;
    // IMFSourceReader
    static final int READER_SET_STREAM_SELECTION = 4, READER_GET_NATIVE_TYPE = 5, READER_GET_CURRENT_TYPE = 6,
            READER_SET_CURRENT_TYPE = 7, READER_SET_POSITION = 8, READER_READ_SAMPLE = 9, READER_FLUSH = 10,
            READER_GET_PRESENTATION_ATTRIBUTE = 12;

    // --- stream selectors and flags of the Source Reader
    static final int ALL_STREAMS = 0xFFFFFFFE, ANY_STREAM = 0xFFFFFFFE, MEDIA_SOURCE = 0xFFFFFFFF;
    static final int END_OF_STREAM = 0x2, NATIVE_TYPE_CHANGED = 0x10, CURRENT_TYPE_CHANGED = 0x20;
    static final int INVALID_STREAM_NUMBER = 0xC00D36B3;

    // --- attribute keys and formats
    static final String MT_MAJOR_TYPE = "48eba18e-f8c9-4687-bf11-0a74c9f96a8f";
    static final String MT_SUBTYPE = "f7e34c9a-42e8-4714-b74b-cb29d72c35e5";
    static final String MT_FRAME_SIZE = "1652c33d-d6b2-4012-b834-72030849a37d";
    static final String MT_FRAME_RATE = "c459a2e8-3d2c-4e44-b132-fee5156c7bb0";
    static final String MT_DEFAULT_STRIDE = "644b4e48-1e02-4516-b0eb-c01ca9d49ac6";
    static final String MT_MINIMUM_DISPLAY_APERTURE = "d7388766-18fe-48c6-a177-ee894867c8c4";
    static final String MT_GEOMETRIC_APERTURE = "66758743-7e5f-400d-980a-aa8596c85696";
    static final String MT_VIDEO_ROTATION = "c380465d-2271-428c-9b83-ecea3b4a85c1";
    static final String MT_AUDIO_CHANNELS = "37e48bf5-645e-4c5b-89de-ada9e29b696a";
    static final String MT_AUDIO_RATE = "5faeeae7-0290-4c31-9e8a-c534f68d9dba";
    static final String MT_AUDIO_BITS = "f2deb57f-40fa-4764-aa33-ed4f2d1ff669";
    static final String PD_DURATION = "6c990d33-bb8e-477a-8598-0d5d96fcd88a";
    static final String READER_ENABLE_VIDEO_PROCESSING = "fb394f3d-ccf1-42ee-bbb3-f9b845d5681d";
    static final String VIDEO = "73646976-0000-0010-8000-00aa00389b71";
    static final String AUDIO = "73647561-0000-0010-8000-00aa00389b71";
    static final String RGB32 = "00000016-0000-0010-8000-00aa00389b71";
    static final String PCM = "00000001-0000-0010-8000-00aa00389b71";
    static final String GUID_NULL = "00000000-0000-0000-0000-000000000000";

    private static final int MF_VERSION = 0x00020070; // SDK 2, API 0x70
    private static final int RPC_E_CHANGED_MODE = 0x80010106;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final ConcurrentHashMap<FunctionDescriptor, MethodHandle> CALLS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> COM_READY = new ThreadLocal<>();
    private static final MethodHandle CO_INITIALIZE, MF_CREATE_MEDIA_TYPE, MF_CREATE_ATTRIBUTES, MF_CREATE_READER_FROM_URL;
    private static final boolean AVAILABLE;

    static {
        boolean ok = false;
        MethodHandle[] h = new MethodHandle[4];
        try {
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                SymbolLookup ole = SymbolLookup.libraryLookup("ole32.dll", Arena.global());
                SymbolLookup plat = SymbolLookup.libraryLookup("mfplat.dll", Arena.global());
                SymbolLookup rw = SymbolLookup.libraryLookup("mfreadwrite.dll", Arena.global());
                h[0] = fn(ole, "CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
                MethodHandle startup = fn(plat, "MFStartup", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
                h[1] = fn(plat, "MFCreateMediaType", FunctionDescriptor.of(JAVA_INT, ADDRESS));
                h[2] = fn(plat, "MFCreateAttributes", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
                h[3] = fn(rw, "MFCreateSourceReaderFromURL", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
                comInitialize(h[0]);
                ok = (int) startup.invokeExact(MF_VERSION, 0) >= 0; // MFSTARTUP_FULL
            }
        } catch (Throwable e) {
            ok = false;
        }
        AVAILABLE = ok;
        CO_INITIALIZE = h[0];
        MF_CREATE_MEDIA_TYPE = h[1];
        MF_CREATE_ATTRIBUTES = h[2];
        MF_CREATE_READER_FROM_URL = h[3];
    }

    private static MethodHandle fn(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.find(name).orElseThrow(), descriptor);
    }

    static boolean available() {
        return AVAILABLE;
    }

    /**
     * Joins the calling thread to COM's multithreaded apartment. Media Foundation wants this on every thread that
     * touches it, and the decode thread is not the one that opened the file. A thread that is already in another
     * apartment keeps it, which Media Foundation tolerates.
     */
    static void thread() {
        if (COM_READY.get() != null) return;
        COM_READY.set(Boolean.TRUE);
        try {
            comInitialize(CO_INITIALIZE);
        } catch (Throwable e) {
            // MF_E_ errors will say it later, if it matters
        }
    }

    private static void comInitialize(MethodHandle init) throws Throwable {
        int hr = (int) init.invokeExact(MemorySegment.NULL, 0); // COINIT_MULTITHREADED
        if (hr < 0 && hr != RPC_E_CHANGED_MODE) throw new IllegalStateException("CoInitializeEx failed: " + hex(hr));
    }

    // ------------------------------------------------------------------ COM calls

    /**
     * Calls the method in {@code slot} of the COM object {@code object}. {@code descriptor} describes the method's
     * arguments after the implicit {@code this}; the result is its HRESULT.
     */
    static int call(MemorySegment object, int slot, FunctionDescriptor descriptor, Object... args) {
        MemorySegment table = object.reinterpret(8).get(ADDRESS, 0).reinterpret(8L * (slot + 1));
        MemorySegment target = table.get(ADDRESS, 8L * slot);
        MethodHandle handle = CALLS.computeIfAbsent(descriptor, d -> {
            List<MemoryLayout> layouts = new ArrayList<>();
            layouts.add(ADDRESS); // this
            layouts.addAll(d.argumentLayouts());
            return LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, layouts.toArray(new MemoryLayout[0])));
        });
        Object[] all = new Object[args.length + 2];
        all[0] = target;
        all[1] = object;
        System.arraycopy(args, 0, all, 2, args.length);
        try {
            return (int) handle.invokeWithArguments(all);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    static final FunctionDescriptor NO_ARGS = FunctionDescriptor.of(JAVA_INT);
    private static final FunctionDescriptor D_KEY_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_KEY_INT = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor D_BLOB = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor D_KEY_GUID = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);

    static void release(MemorySegment object) {
        if (object != null && !isNull(object)) call(object, RELEASE, NO_ARGS);
    }

    static boolean isNull(MemorySegment p) {
        return p == null || p.equals(MemorySegment.NULL) || p.address() == 0;
    }

    static String hex(int hr) {
        return "0x" + Integer.toHexString(hr).toUpperCase();
    }

    static void check(int hr, String what) throws IOException {
        if (hr < 0) throw new IOException(what + " failed (" + hex(hr) + ")");
    }

    // ------------------------------------------------------------------ creating objects

    /** A new, empty IMFMediaType. */
    static MemorySegment newMediaType(Arena a) throws IOException {
        MemorySegment out = a.allocate(8);
        check(invokeCreate(MF_CREATE_MEDIA_TYPE, out), "MFCreateMediaType");
        return out.get(ADDRESS, 0);
    }

    /** A new IMFAttributes that sets {@code key} to the unsigned integer {@code value}. */
    static MemorySegment newAttributes(Arena a, String key, int value) throws IOException {
        MemorySegment out = a.allocate(8);
        try {
            int hr = (int) MF_CREATE_ATTRIBUTES.invokeExact(out, 1);
            check(hr, "MFCreateAttributes");
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
        MemorySegment attributes = out.get(ADDRESS, 0);
        setUInt32(a, attributes, key, value);
        return attributes;
    }

    /** An IMFSourceReader on the file, or an IOException carrying the HRESULT. */
    static MemorySegment newReader(Arena a, String path, MemorySegment attributes) throws IOException {
        MemorySegment out = a.allocate(8);
        try {
            int hr = (int) MF_CREATE_READER_FROM_URL.invokeExact(a.allocateFrom(path, java.nio.charset.StandardCharsets.UTF_16LE),
                    attributes, out);
            check(hr, "MFCreateSourceReaderFromURL");
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
        return out.get(ADDRESS, 0);
    }

    private static int invokeCreate(MethodHandle create, MemorySegment out) {
        try {
            return (int) create.invokeExact(out);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ attributes

    /** A GUID's 16 bytes: the first three groups little-endian, the last two as written. */
    static MemorySegment guid(Arena a, String text) {
        String[] g = text.split("-");
        MemorySegment m = a.allocate(16);
        m.set(JAVA_INT_UNALIGNED, 0, (int) Long.parseLong(g[0], 16));
        m.set(JAVA_SHORT_UNALIGNED, 4, (short) Integer.parseInt(g[1], 16));
        m.set(JAVA_SHORT_UNALIGNED, 6, (short) Integer.parseInt(g[2], 16));
        String tail = g[3] + g[4];
        for (int i = 0; i < 8; i++) m.set(JAVA_BYTE, 8 + i, (byte) Integer.parseInt(tail.substring(i * 2, i * 2 + 2), 16));
        return m;
    }

    /** Which GUID this is, as the text {@link #guid} was made from. */
    static String guidText(MemorySegment m) {
        StringBuilder tail = new StringBuilder();
        for (int i = 0; i < 8; i++) tail.append(String.format("%02x", m.get(JAVA_BYTE, 8 + i)));
        return String.format("%08x-%04x-%04x-%s-%s", m.get(JAVA_INT_UNALIGNED, 0), m.get(JAVA_SHORT_UNALIGNED, 4) & 0xFFFF,
                m.get(JAVA_SHORT_UNALIGNED, 6) & 0xFFFF, tail.substring(0, 4), tail.substring(4));
    }

    /** The value, or {@code fallback} when the attribute is absent. */
    static long getUInt32(Arena a, MemorySegment attributes, String key, long fallback) {
        MemorySegment v = a.allocate(4);
        return call(attributes, GET_UINT32, D_KEY_PTR, guid(a, key), v) < 0 ? fallback : v.get(JAVA_INT, 0) & 0xFFFFFFFFL;
    }

    /** The value as a signed integer, for a stride, or {@code fallback} when the attribute is absent. */
    static int getInt32(Arena a, MemorySegment attributes, String key, int fallback) {
        MemorySegment v = a.allocate(4);
        return call(attributes, GET_UINT32, D_KEY_PTR, guid(a, key), v) < 0 ? fallback : v.get(JAVA_INT, 0);
    }

    static long getUInt64(Arena a, MemorySegment attributes, String key, long fallback) {
        MemorySegment v = a.allocate(8);
        return call(attributes, GET_UINT64, D_KEY_PTR, guid(a, key), v) < 0 ? fallback : v.get(JAVA_LONG, 0);
    }

    /** The GUID's text, or null when the attribute is absent. */
    static String getGuid(Arena a, MemorySegment attributes, String key) {
        MemorySegment v = a.allocate(16);
        return call(attributes, GET_GUID, D_KEY_GUID, guid(a, key), v) < 0 ? null : guidText(v);
    }

    /** The attribute's bytes, or null when it is absent or longer than {@code max}. */
    static byte[] getBlob(Arena a, MemorySegment attributes, String key, int max) {
        MemorySegment buffer = a.allocate(max), written = a.allocate(4);
        if (call(attributes, GET_BLOB, D_BLOB, guid(a, key), buffer, max, written) < 0) return null;
        return buffer.asSlice(0, written.get(JAVA_INT, 0)).toArray(JAVA_BYTE);
    }

    static void setUInt32(Arena a, MemorySegment attributes, String key, int value) throws IOException {
        check(call(attributes, SET_UINT32, D_KEY_INT, guid(a, key), value), "setting an attribute");
    }

    static void setGuid(Arena a, MemorySegment attributes, String key, String value) throws IOException {
        check(call(attributes, SET_GUID, D_KEY_GUID, guid(a, key), guid(a, value)), "setting an attribute");
    }

    // ------------------------------------------------------------------ the first four bytes of a format GUID

    /** The leading 32 bits of a format GUID, which is the FOURCC or WAVE format tag for the formats Windows defines. */
    static long format(String guidText) {
        return guidText == null ? -1 : Long.parseLong(guidText.substring(0, 8), 16);
    }
}
