package me.piitex.engine.video;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.*;

/**
 * Just enough of GStreamer to play a file through {@code playbin} into two {@code appsink}s, called straight from Java
 * (no native library is shipped). Only touched on Linux; {@link #available()} says whether GStreamer 1.x loaded and
 * started.
 * <p>
 * Everything here is a thin wrapper over one C function. The numbers below are GStreamer's own enum values, which are
 * part of its stable 1.x interface. Nothing in this class has been run against a real GStreamer: it was written from
 * the headers, and the first Linux machine to run {@code SystemMediaSourceTest} is where a mistake would show.
 */
final class GStreamer {
    private GStreamer() {
    }

    private static final Logger log = LogManager.getLogger("Video");

    // GstState
    static final int STATE_NULL = 1, STATE_PAUSED = 3, STATE_PLAYING = 4;
    // GstStateChangeReturn
    static final int CHANGE_FAILURE = 0, CHANGE_SUCCESS = 1, CHANGE_ASYNC = 2, CHANGE_NO_PREROLL = 3;
    // GstFormat
    static final int FORMAT_TIME = 3;
    // GstSeekFlags
    static final int SEEK_FLUSH = 1 << 0, SEEK_KEY_UNIT = 1 << 2, SEEK_SNAP_BEFORE = 1 << 5;
    // GstMessageType
    static final int MESSAGE_ERROR = 1 << 1, MESSAGE_TAG = 1 << 4;
    // GstMapFlags
    static final int MAP_READ = 1;
    static final long NANOS_PER_MILLI = 1_000_000L;

    // Field offsets in structs that GStreamer has no function to read (64-bit layouts; see the notes at each use).
    /** GstBuffer: a 64-byte GstMiniObject, the pool pointer, then {@code pts}. */
    static final long BUFFER_PTS = 72, BUFFER_SIZE = 112;
    /** GstMapInfo: memory pointer, flags, then {@code data}, {@code size}. 104 bytes in all. */
    static final long MAP_DATA = 16, MAP_SIZE = 24, MAP_INFO_SIZE = 128;
    /** GError: a 32-bit domain, a 32-bit code, then the message. */
    static final long ERROR_MESSAGE = 8;

    private static volatile Boolean available;

    static boolean available() {
        Boolean a = available;
        if (a == null) {
            synchronized (GStreamer.class) {
                a = available;
                if (a == null) available = a = probe();
            }
        }
        return a;
    }

    private static boolean probe() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) return false;
        try {
            return Lib.READY;
        } catch (Throwable e) {
            Throwable why = e.getCause() != null ? e.getCause() : e;
            log.debug("GStreamer is not available: {}", why.toString());
            return false;
        }
    }

    /**
     * The libraries and the functions taken from them. Loaded the first time {@link #available()} runs on Linux; a
     * missing library or function ends the class's initialisation, which {@code probe} reads as "not available".
     */
    private static final class Lib {
        static final Linker LINKER = Linker.nativeLinker();
        static final SymbolLookup LOOKUP = open("libgstreamer-1.0.so.0", "libgstapp-1.0.so.0", "libgobject-2.0.so.0", "libglib-2.0.so.0");

        static final MethodHandle INIT_CHECK = fn("gst_init_check", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle FACTORY_MAKE = fn("gst_element_factory_make", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle SET_STATE = fn("gst_element_set_state", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        static final MethodHandle GET_STATE = fn("gst_element_get_state", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle SEEK_SIMPLE = fn("gst_element_seek_simple", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG));
        static final MethodHandle QUERY_DURATION = fn("gst_element_query_duration", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        static final MethodHandle GET_BUS = fn("gst_element_get_bus", FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle BUS_POP = fn("gst_bus_timed_pop_filtered", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));
        static final MethodHandle PARSE_ERROR = fn("gst_message_parse_error", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle PARSE_TAG = fn("gst_message_parse_tag", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        static final MethodHandle TAG_STRING = fn("gst_tag_list_get_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle OBJECT_UNREF = fn("gst_object_unref", FunctionDescriptor.ofVoid(ADDRESS));
        static final MethodHandle MINI_UNREF = fn("gst_mini_object_unref", FunctionDescriptor.ofVoid(ADDRESS)); // gst_sample_unref and friends are inline wrappers of this
        static final MethodHandle SAMPLE_BUFFER = fn("gst_sample_get_buffer", FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle SAMPLE_CAPS = fn("gst_sample_get_caps", FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CAPS_STRUCTURE = fn("gst_caps_get_structure", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        static final MethodHandle STRUCTURE_INT = fn("gst_structure_get_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle STRUCTURE_FRACTION = fn("gst_structure_get_fraction", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle BUFFER_MAP = fn("gst_buffer_map", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        static final MethodHandle BUFFER_UNMAP = fn("gst_buffer_unmap", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        static final MethodHandle SET_ARG = fn("gst_util_set_object_arg", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle SINK_PULL = fn("gst_app_sink_try_pull_sample", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle SINK_PREROLL = fn("gst_app_sink_try_pull_preroll", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle SINK_EOS = fn("gst_app_sink_is_eos", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        /** {@code g_object_set (object, name, value, NULL)}: variadic, so the linker is told where the fixed arguments end. */
        static final MethodHandle OBJECT_SET = LINKER.downcallHandle(find("g_object_set"),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS), Linker.Option.firstVariadicArg(1));
        static final MethodHandle FREE = fn("g_free", FunctionDescriptor.ofVoid(ADDRESS));
        static final MethodHandle ERROR_FREE = fn("g_error_free", FunctionDescriptor.ofVoid(ADDRESS));

        static final boolean READY = start();

        private static SymbolLookup open(String... names) {
            List<SymbolLookup> libraries = new ArrayList<>();
            for (String name : names) libraries.add(SymbolLookup.libraryLookup(name, Arena.global()));
            return symbol -> {
                for (SymbolLookup library : libraries) {
                    Optional<MemorySegment> found = library.find(symbol);
                    if (found.isPresent()) return found;
                }
                return Optional.empty();
            };
        }

        private static MemorySegment find(String name) {
            return LOOKUP.find(name).orElseThrow(() -> new IllegalStateException("GStreamer has no " + name));
        }

        private static MethodHandle fn(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(find(name), descriptor);
        }

        private static boolean start() {
            try {
                return (int) INIT_CHECK.invoke(MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL) != 0;
            } catch (Throwable e) {
                throw failed(e);
            }
        }
    }

    private static RuntimeException failed(Throwable e) {
        return e instanceof RuntimeException r ? r : new IllegalStateException(e);
    }

    static boolean isNull(MemorySegment pointer) {
        return pointer == null || pointer.equals(MemorySegment.NULL);
    }

    /** A C string the caller does not own. */
    static String string(MemorySegment pointer) {
        return isNull(pointer) ? null : pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    // ------------------------------------------------------------------ elements and properties

    /** A new element, or NULL when its plug-in is not installed. */
    static MemorySegment make(String factory, String name) {
        try (Arena a = Arena.ofConfined()) {
            return (MemorySegment) Lib.FACTORY_MAKE.invoke(a.allocateFrom(factory), a.allocateFrom(name));
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** Sets a property from its text, the way {@code gst-launch} does: enums and flags by name, numbers and booleans as text. */
    static void setArg(MemorySegment object, String property, String value) {
        try (Arena a = Arena.ofConfined()) {
            Lib.SET_ARG.invoke(object, a.allocateFrom(property), a.allocateFrom(value));
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** Sets a property that holds an object (the element takes its own reference). */
    static void setObject(MemorySegment object, String property, MemorySegment value) {
        try (Arena a = Arena.ofConfined()) {
            Lib.OBJECT_SET.invoke(object, a.allocateFrom(property), value, MemorySegment.NULL);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    static void setString(MemorySegment object, String property, String value) {
        try (Arena a = Arena.ofConfined()) {
            Lib.OBJECT_SET.invoke(object, a.allocateFrom(property), a.allocateFrom(value), MemorySegment.NULL);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    static int setState(MemorySegment element, int state) {
        try {
            return (int) Lib.SET_STATE.invoke(element, state);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** Waits up to {@code timeoutNanos} for a state change to finish; returns a {@code CHANGE_*} value. */
    static int waitForState(MemorySegment element, long timeoutNanos) {
        try {
            return (int) Lib.GET_STATE.invoke(element, MemorySegment.NULL, MemorySegment.NULL, timeoutNanos);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    static boolean seek(MemorySegment element, double seconds, int flags) {
        try {
            long nanos = Math.round(Math.max(0, seconds) * 1e9);
            return (int) Lib.SEEK_SIMPLE.invoke(element, FORMAT_TIME, flags, nanos) != 0;
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** The length in seconds, or 0 when the pipeline does not know it. */
    static double duration(MemorySegment element) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(8);
            if ((int) Lib.QUERY_DURATION.invoke(element, FORMAT_TIME, out) == 0) return 0;
            long nanos = out.get(JAVA_LONG, 0);
            return nanos > 0 ? nanos / 1e9 : 0;
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    static void unref(MemorySegment object) {
        if (isNull(object)) return;
        try {
            Lib.OBJECT_UNREF.invoke(object);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** Releases a sample, message, caps or tag list. */
    static void release(MemorySegment miniObject) {
        if (isNull(miniObject)) return;
        try {
            Lib.MINI_UNREF.invoke(miniObject);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    // ------------------------------------------------------------------ the bus

    static MemorySegment bus(MemorySegment element) {
        try {
            return (MemorySegment) Lib.GET_BUS.invoke(element);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** The next message of the given types, waiting up to {@code timeoutNanos}; NULL if none. The caller releases it. */
    static MemorySegment popMessage(MemorySegment bus, int types, long timeoutNanos) {
        try {
            return (MemorySegment) Lib.BUS_POP.invoke(bus, timeoutNanos, types);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** The text of an error message. */
    static String errorText(MemorySegment message) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment error = a.allocate(8), debug = a.allocate(8);
            Lib.PARSE_ERROR.invoke(message, error, debug);
            MemorySegment e = error.get(ADDRESS, 0), d = debug.get(ADDRESS, 0);
            String text = isNull(e) ? "unknown error" : string(e.reinterpret(ERROR_MESSAGE + 8).get(ADDRESS, ERROR_MESSAGE));
            if (!isNull(e)) Lib.ERROR_FREE.invoke(e);
            if (!isNull(d)) Lib.FREE.invoke(d);
            return text;
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** The named text tags of a tag message ("video-codec", "audio-codec"), null for those it lacks. */
    static String[] tagStrings(MemorySegment message, String... tags) {
        String[] result = new String[tags.length];
        try (Arena a = Arena.ofConfined()) {
            MemorySegment list = a.allocate(8);
            Lib.PARSE_TAG.invoke(message, list);
            MemorySegment tagList = list.get(ADDRESS, 0);
            if (isNull(tagList)) return result;
            for (int i = 0; i < tags.length; i++) {
                MemorySegment out = a.allocate(8);
                if ((int) Lib.TAG_STRING.invoke(tagList, a.allocateFrom(tags[i]), out) != 0) {
                    MemorySegment text = out.get(ADDRESS, 0);
                    result[i] = string(text);
                    if (!isNull(text)) Lib.FREE.invoke(text);
                }
            }
            release(tagList);
            return result;
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    // ------------------------------------------------------------------ appsink and samples

    /** The next sample, waiting up to {@code timeoutNanos}; NULL if none. The caller releases it. */
    static MemorySegment pullSample(MemorySegment sink, long timeoutNanos) {
        try {
            return (MemorySegment) Lib.SINK_PULL.invoke(sink, timeoutNanos);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** The sample the sink prerolled on, which stays available; NULL if it has none. The caller releases it. */
    static MemorySegment pullPreroll(MemorySegment sink, long timeoutNanos) {
        try {
            return (MemorySegment) Lib.SINK_PREROLL.invoke(sink, timeoutNanos);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    static boolean ended(MemorySegment sink) {
        try {
            return (int) Lib.SINK_EOS.invoke(sink) != 0;
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** Borrowed from the sample. */
    static MemorySegment sampleBuffer(MemorySegment sample) {
        try {
            return (MemorySegment) Lib.SAMPLE_BUFFER.invoke(sample);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** Borrowed from the sample. */
    static MemorySegment sampleCaps(MemorySegment sample) {
        try {
            return (MemorySegment) Lib.SAMPLE_CAPS.invoke(sample);
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** An integer field of the first structure of the caps, or {@code fallback} when it has none. */
    static int capsInt(MemorySegment caps, String field, int fallback) {
        if (isNull(caps)) return fallback;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment structure = (MemorySegment) Lib.CAPS_STRUCTURE.invoke(caps, 0);
            if (isNull(structure)) return fallback;
            MemorySegment out = a.allocate(4);
            return (int) Lib.STRUCTURE_INT.invoke(structure, a.allocateFrom(field), out) != 0 ? out.get(JAVA_INT, 0) : fallback;
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** A fraction field (such as {@code framerate}) as a number, or 0 when it is missing or has no denominator. */
    static double capsFraction(MemorySegment caps, String field) {
        if (isNull(caps)) return 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment structure = (MemorySegment) Lib.CAPS_STRUCTURE.invoke(caps, 0);
            if (isNull(structure)) return 0;
            MemorySegment num = a.allocate(4), den = a.allocate(4);
            if ((int) Lib.STRUCTURE_FRACTION.invoke(structure, a.allocateFrom(field), num, den) == 0) return 0;
            int d = den.get(JAVA_INT, 0);
            return d == 0 ? 0 : (double) num.get(JAVA_INT, 0) / d;
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    /** Maps the buffer's memory for reading into {@code info} (at least {@link #MAP_INFO_SIZE} bytes). */
    static boolean map(MemorySegment buffer, MemorySegment info) {
        try {
            return (int) Lib.BUFFER_MAP.invoke(buffer, info, MAP_READ) != 0;
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    static void unmap(MemorySegment buffer, MemorySegment info) {
        try {
            Lib.BUFFER_UNMAP.invoke(buffer, info);
        } catch (Throwable e) {
            throw failed(e);
        }
    }
}
