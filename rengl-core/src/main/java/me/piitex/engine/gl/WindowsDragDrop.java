package me.piitex.engine.gl;

import me.piitex.engine.input.FileDrag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Drag and drop with the rest of Windows, through OLE: the one way a program can both hear a drag approach (GLFW only
 * reports a finished drop) and start a drag that Explorer and the desktop accept.
 * <ul>
 *   <li><b>Into the window.</b> An {@code IDropTarget} registered on the window. It is told when files enter, move over
 *       and leave the window, with the pointer, and when they are dropped.</li>
 *   <li><b>Out of the window.</b> {@code DoDragDrop} with an {@code IDropSource} and an {@code IDataObject} that holds
 *       {@code CF_HDROP}, the format Explorer copies files from. The data object builds the list of files only when
 *       the target asks for it, which is when the user lets go.</li>
 * </ul>
 * The three COM interfaces are implemented here as native tables of function pointers that call back into Java. COM
 * objects live in the thread that created them (a single-threaded apartment), so everything here runs on the engine's
 * main thread, the one that pumps the window's messages. A callback that threw would take the process down, so each
 * one answers with an error code instead.
 * <p>
 * Only loaded on Windows.
 */
final class WindowsDragDrop {
    private static final Logger log = LogManager.getLogger("Engine");

    // HRESULTs
    static final int S_OK = 0;
    private static final int E_NOTIMPL = 0x80004001, E_NOINTERFACE = 0x80004002, E_FAIL = 0x80004005, E_OUTOFMEMORY = 0x8007000E;
    private static final int DV_E_FORMATETC = 0x80040064, DV_E_LINDEX = 0x80040068, DV_E_TYMED = 0x80040069, DV_E_DVASPECT = 0x8004006B;
    private static final int OLE_E_ADVISENOTSUPPORTED = 0x80040003, RPC_E_CHANGED_MODE = 0x80010106;
    private static final int DATA_S_SAMEFORMATETC = 0x00040130;
    static final int DRAGDROP_S_DROP = 0x00040100, DRAGDROP_S_CANCEL = 0x00040101, DRAGDROP_S_USEDEFAULTCURSORS = 0x00040102;

    private static final int CF_HDROP = 15, TYMED_HGLOBAL = 1, DVASPECT_CONTENT = 1, DATADIR_GET = 1;
    private static final int DROPEFFECT_NONE = 0, DROPEFFECT_COPY = 1;
    private static final int GMEM_MOVEABLE_ZEROINIT = 0x0042;
    private static final int MK_LBUTTON = 1;
    private static final int WM_LBUTTONUP = 0x0202;

    // x64 layouts: FORMATETC { WORD cf; ptd; DWORD aspect; LONG index; DWORD tymed } and STGMEDIUM { DWORD tymed; handle; pUnkForRelease }
    private static final int FORMATETC_SIZE = 32, STGMEDIUM_SIZE = 24;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena GLOBAL = Arena.global();
    private static final SymbolLookup OLE32 = SymbolLookup.libraryLookup("ole32", GLOBAL);
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", GLOBAL);
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", GLOBAL);
    private static final SymbolLookup SHELL32 = SymbolLookup.libraryLookup("shell32", GLOBAL);

    private static MethodHandle down(SymbolLookup library, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(library.find(name).orElseThrow(), descriptor);
    }

    private static final MethodHandle OLE_INITIALIZE = down(OLE32, "OleInitialize", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle REGISTER_DRAG_DROP = down(OLE32, "RegisterDragDrop", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle REVOKE_DRAG_DROP = down(OLE32, "RevokeDragDrop", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DO_DRAG_DROP = down(OLE32, "DoDragDrop", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle RELEASE_STG_MEDIUM = down(OLE32, "ReleaseStgMedium", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle CREATE_FORMAT_ENUM = down(SHELL32, "SHCreateStdEnumFmtEtc", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle DRAG_ACCEPT_FILES = down(SHELL32, "DragAcceptFiles", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle SCREEN_TO_CLIENT = down(USER32, "ScreenToClient", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_CURSOR_POS = down(USER32, "GetCursorPos", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle POST_MESSAGE = down(USER32, "PostMessageW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GLOBAL_ALLOC = down(KERNEL32, "GlobalAlloc", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_LONG));
    private static final MethodHandle GLOBAL_LOCK = down(KERNEL32, "GlobalLock", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GLOBAL_UNLOCK = down(KERNEL32, "GlobalUnlock", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GLOBAL_SIZE = down(KERNEL32, "GlobalSize", FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    // Calling a method of someone else's COM object: the first argument is the function pointer taken from its table.
    private static final MethodHandle CALL_1 = LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CALL_2 = LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    private static final int IUNKNOWN_SLOTS = 3;
    private static final int SLOT_GET_DATA = 3, SLOT_QUERY_GET_DATA = 5;

    private static final Map<Long, ComObject> LIVE = new ConcurrentHashMap<>();

    /**
     * Set while this process is the source of a drag, so a drop back onto its own window is refused instead of
     * adding the application's own files to itself.
     */
    private static volatile boolean dragging;
    private static Boolean oleReady; // main thread only

    private WindowsDragDrop() {
    }

    // ---- COM plumbing -------------------------------------------------------------------------------------------

    private static byte[] guid(int d1, int d2, int d3, int... d4) {
        ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(d1).putShort((short) d2).putShort((short) d3);
        for (int v : d4) b.put((byte) v);
        return b.array();
    }

    private static final byte[] IID_IUNKNOWN = guid(0, 0, 0, 0xC0, 0, 0, 0, 0, 0, 0, 0x46);
    private static final byte[] IID_IDATAOBJECT = guid(0x10e, 0, 0, 0xC0, 0, 0, 0, 0, 0, 0, 0x46);
    private static final byte[] IID_IDROPSOURCE = guid(0x121, 0, 0, 0xC0, 0, 0, 0, 0, 0, 0, 0x46);
    private static final byte[] IID_IDROPTARGET = guid(0x122, 0, 0, 0xC0, 0, 0, 0, 0, 0, 0, 0x46);

    private static boolean sameGuid(MemorySegment riid, byte[] guid) {
        return riid.reinterpret(16).mismatch(MemorySegment.ofArray(guid)) == -1;
    }

    /**
     * An object other code can call as COM: a block of native memory whose first word points at a table of functions.
     * It lives until its last reference is released.
     */
    private abstract static class ComObject {
        private final Arena arena = Arena.ofShared();
        private final AtomicInteger references = new AtomicInteger(1);
        final MemorySegment self;
        private final byte[] iid;

        ComObject(MemorySegment table, byte[] iid) {
            this.iid = iid;
            self = arena.allocate(16, 8);
            self.set(ADDRESS, 0, table);
            LIVE.put(self.address(), this);
        }

        int addRef() {
            return references.incrementAndGet();
        }

        int release() {
            int left = references.decrementAndGet();
            if (left == 0) {
                LIVE.remove(self.address());
                arena.close();
            }
            return Math.max(left, 0);
        }
    }

    private static MemorySegment upcall(String method, MemoryLayout... arguments) {
        FunctionDescriptor descriptor = FunctionDescriptor.of(JAVA_INT, arguments);
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(WindowsDragDrop.class, method, descriptor.toMethodType());
            return LINKER.upcallStub(target, descriptor, GLOBAL);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MemorySegment table(MemorySegment... functions) {
        MemorySegment table = GLOBAL.allocate(8L * functions.length, 8);
        for (int i = 0; i < functions.length; i++) table.setAtIndex(ADDRESS, i, functions[i]);
        return table;
    }

    private static MemorySegment[] unknown() {
        return new MemorySegment[]{
                upcall("queryInterface", ADDRESS, ADDRESS, ADDRESS),
                upcall("addRef", ADDRESS),
                upcall("release", ADDRESS)};
    }

    private static MemorySegment tableOf(MemorySegment[] base, MemorySegment... own) {
        MemorySegment[] all = new MemorySegment[base.length + own.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(own, 0, all, base.length, own.length);
        return table(all);
    }

    private static int queryInterface(MemorySegment self, MemorySegment riid, MemorySegment ppv) {
        try {
            MemorySegment out = ppv.reinterpret(8);
            ComObject object = LIVE.get(self.address());
            if (object != null && (sameGuid(riid, IID_IUNKNOWN) || sameGuid(riid, object.iid))) {
                out.set(ADDRESS, 0, self);
                object.addRef();
                return S_OK;
            }
            out.set(ADDRESS, 0, MemorySegment.NULL);
            return E_NOINTERFACE;
        } catch (Throwable t) {
            return E_FAIL;
        }
    }

    private static int addRef(MemorySegment self) {
        ComObject object = LIVE.get(self.address());
        return object == null ? 0 : object.addRef();
    }

    private static int release(MemorySegment self) {
        ComObject object = LIVE.get(self.address());
        return object == null ? 0 : object.release();
    }

    private static MemorySegment slot(MemorySegment object, int index) {
        MemorySegment table = object.reinterpret(8).get(ADDRESS, 0).reinterpret(8L * (index + 1));
        return table.get(ADDRESS, 8L * index);
    }

    // ---- starting up -------------------------------------------------------------------------------------------

    /**
     * Main thread: readies the thread for OLE, once. False if it can't be done, for instance when something has
     * already made the thread a multi-threaded apartment, which OLE's drag and drop refuses to run in.
     */
    static boolean ensureOle() {
        if (oleReady == null) {
            try {
                int hr = (int) OLE_INITIALIZE.invoke(MemorySegment.NULL);
                oleReady = hr >= 0; // S_FALSE: already done, which is as good
                if (!oleReady) log.warn("OLE could not start on the main thread (0x{}): files can't be dragged to or from the window", Integer.toHexString(hr));
                if (hr == RPC_E_CHANGED_MODE) log.warn("The main thread was already made a multi-threaded apartment");
            } catch (Throwable t) {
                log.warn("OLE could not start", t);
                oleReady = false;
            }
        }
        return oleReady;
    }

    // ---- dragging out ------------------------------------------------------------------------------------------

    /**
     * The files being dragged out. Holds the {@link FileDrag.Source} and asks it once, the first time anything wants
     * the files.
     */
    static final class DataObject extends ComObject {
        private static final MemorySegment TABLE = tableOf(unknown(),
                upcall("dataGetData", ADDRESS, ADDRESS, ADDRESS),
                upcall("dataGetDataHere", ADDRESS, ADDRESS, ADDRESS),
                upcall("dataQueryGetData", ADDRESS, ADDRESS),
                upcall("dataGetCanonicalFormatEtc", ADDRESS, ADDRESS, ADDRESS),
                upcall("dataSetData", ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
                upcall("dataEnumFormatEtc", ADDRESS, JAVA_INT, ADDRESS),
                upcall("dataAdvise", ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                upcall("dataUnadvise", ADDRESS, JAVA_INT),
                upcall("dataEnumAdvise", ADDRESS, ADDRESS));

        private final FileDrag.Source source;
        private boolean asked;
        private byte[] bytes;
        Throwable failure;

        DataObject(FileDrag.Source source) {
            super(TABLE, IID_IDATAOBJECT);
            this.source = source;
        }

        /** Asks the source the first time, and answers the same afterwards, even when it failed. */
        byte[] bytes() throws Exception {
            if (!asked) {
                asked = true;
                try {
                    bytes = DropFiles.encode(source.files());
                } catch (Throwable t) {
                    failure = t;
                }
            }
            if (failure != null) throw new Exception(failure);
            return bytes;
        }
    }

    private static DataObject dataObject(MemorySegment self) {
        return LIVE.get(self.address()) instanceof DataObject data ? data : null;
    }

    private static int checkFormat(MemorySegment formatEtc) {
        MemorySegment f = formatEtc.reinterpret(FORMATETC_SIZE);
        if ((f.get(JAVA_SHORT, 0) & 0xFFFF) != CF_HDROP) return DV_E_FORMATETC;
        if ((f.get(JAVA_INT, 16) & DVASPECT_CONTENT) == 0) return DV_E_DVASPECT;
        if (f.get(JAVA_INT, 20) != -1) return DV_E_LINDEX;
        if ((f.get(JAVA_INT, 24) & TYMED_HGLOBAL) == 0) return DV_E_TYMED;
        return S_OK;
    }

    private static int dataGetData(MemorySegment self, MemorySegment formatEtc, MemorySegment medium) {
        try {
            DataObject data = dataObject(self);
            if (data == null) return E_FAIL;
            int format = checkFormat(formatEtc);
            if (format != S_OK) return format;
            byte[] bytes;
            try {
                bytes = data.bytes();
            } catch (Throwable t) {
                log.error("Could not make the files being dragged out", data.failure != null ? data.failure : t);
                return E_FAIL;
            }
            MemorySegment handle = (MemorySegment) GLOBAL_ALLOC.invoke(GMEM_MOVEABLE_ZEROINIT, (long) bytes.length);
            if (handle.equals(MemorySegment.NULL)) return E_OUTOFMEMORY;
            MemorySegment block = ((MemorySegment) GLOBAL_LOCK.invoke(handle)).reinterpret(bytes.length);
            block.copyFrom(MemorySegment.ofArray(bytes));
            int ignored = (int) GLOBAL_UNLOCK.invoke(handle);
            MemorySegment m = medium.reinterpret(STGMEDIUM_SIZE);
            m.set(JAVA_INT, 0, TYMED_HGLOBAL);
            m.set(ADDRESS, 8, handle);
            m.set(ADDRESS, 16, MemorySegment.NULL); // the receiver frees the block with ReleaseStgMedium
            return S_OK;
        } catch (Throwable t) {
            return E_FAIL;
        }
    }

    private static int dataGetDataHere(MemorySegment self, MemorySegment formatEtc, MemorySegment medium) {
        return E_NOTIMPL;
    }

    private static int dataQueryGetData(MemorySegment self, MemorySegment formatEtc) {
        try {
            return checkFormat(formatEtc);
        } catch (Throwable t) {
            return E_FAIL;
        }
    }

    private static int dataGetCanonicalFormatEtc(MemorySegment self, MemorySegment in, MemorySegment out) {
        try {
            out.reinterpret(FORMATETC_SIZE).set(ADDRESS, 8, MemorySegment.NULL);
            return DATA_S_SAMEFORMATETC;
        } catch (Throwable t) {
            return E_FAIL;
        }
    }

    private static int dataSetData(MemorySegment self, MemorySegment formatEtc, MemorySegment medium, int release) {
        return E_NOTIMPL;
    }

    private static int dataEnumFormatEtc(MemorySegment self, int direction, MemorySegment enumerator) {
        if (direction != DATADIR_GET) return E_NOTIMPL;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment format = arena.allocate(FORMATETC_SIZE, 8);
            format.set(JAVA_SHORT, 0, (short) CF_HDROP);
            format.set(JAVA_INT, 16, DVASPECT_CONTENT);
            format.set(JAVA_INT, 20, -1);
            format.set(JAVA_INT, 24, TYMED_HGLOBAL);
            return (int) CREATE_FORMAT_ENUM.invoke(1, format, enumerator);
        } catch (Throwable t) {
            return E_FAIL;
        }
    }

    private static int dataAdvise(MemorySegment self, MemorySegment formatEtc, int flags, MemorySegment sink, MemorySegment connection) {
        return OLE_E_ADVISENOTSUPPORTED;
    }

    private static int dataUnadvise(MemorySegment self, int connection) {
        return OLE_E_ADVISENOTSUPPORTED;
    }

    private static int dataEnumAdvise(MemorySegment self, MemorySegment enumerator) {
        return OLE_E_ADVISENOTSUPPORTED;
    }

    /**
     * Decides when the drag ends: dropped when the left button comes up, cancelled by Escape.
     */
    static final class DropSource extends ComObject {
        private static final MemorySegment TABLE = tableOf(unknown(),
                upcall("sourceQueryContinueDrag", ADDRESS, JAVA_INT, JAVA_INT),
                upcall("sourceGiveFeedback", ADDRESS, JAVA_INT));

        DropSource() {
            super(TABLE, IID_IDROPSOURCE);
        }
    }

    private static int sourceQueryContinueDrag(MemorySegment self, int escapePressed, int keyState) {
        try {
            if (escapePressed != 0) return DRAGDROP_S_CANCEL;
            if ((keyState & MK_LBUTTON) == 0) return DRAGDROP_S_DROP;
            return S_OK;
        } catch (Throwable t) {
            return DRAGDROP_S_CANCEL;
        }
    }

    private static int sourceGiveFeedback(MemorySegment self, int effect) {
        return DRAGDROP_S_USEDEFAULTCURSORS;
    }

    /**
     * Main thread, with the left button held: drags what {@code source} makes out of the window until the button
     * comes up, and answers how it ended. Blocks until then; the operating system pumps the window's messages
     * meanwhile. The files are copied, never moved, whatever the target would prefer.
     */
    static FileDrag.Outcome dragOut(FileDrag.Source source) {
        if (!ensureOle()) return FileDrag.Outcome.FAILED;
        DataObject data = new DataObject(source);
        DropSource dropSource = new DropSource();
        dragging = true;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment effect = arena.allocate(JAVA_INT);
            int hr = (int) DO_DRAG_DROP.invoke(data.self, dropSource.self, DROPEFFECT_COPY, effect);
            if (data.failure != null) return FileDrag.Outcome.FAILED;
            if (hr == DRAGDROP_S_DROP) return effect.get(JAVA_INT, 0) != DROPEFFECT_NONE ? FileDrag.Outcome.DROPPED : FileDrag.Outcome.CANCELLED;
            if (hr == DRAGDROP_S_CANCEL) return FileDrag.Outcome.CANCELLED;
            log.warn("The drag out of the window failed (0x{})", Integer.toHexString(hr));
            return FileDrag.Outcome.FAILED;
        } catch (Throwable t) {
            log.error("The drag out of the window failed", t);
            return FileDrag.Outcome.FAILED;
        } finally {
            dragging = false;
            data.release();
            dropSource.release();
        }
    }

    /**
     * Tells the window the left button came up. The drag's own message loop took the real release, so the window's
     * input would otherwise think the button is still held.
     */
    static void postLeftButtonUp(long hwnd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment point = arena.allocate(8, 4);
            MemorySegment window = MemorySegment.ofAddress(hwnd);
            int got = (int) GET_CURSOR_POS.invoke(point);
            int converted = (int) SCREEN_TO_CLIENT.invoke(window, point);
            long x = point.get(JAVA_INT, 0) & 0xFFFFL, y = point.get(JAVA_INT, 4) & 0xFFFFL;
            int posted = (int) POST_MESSAGE.invoke(window, WM_LBUTTONUP, 0L, (y << 16) | x);
        } catch (Throwable t) {
            log.warn("Could not release the mouse button after a drag", t);
        }
    }

    // ---- dropping in ---------------------------------------------------------------------------------------------

    /**
     * The window's drop target.
     */
    static final class Target extends ComObject {
        private static final MemorySegment TABLE = tableOf(unknown(),
                upcall("targetDragEnter", ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS),
                upcall("targetDragOver", ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS),
                upcall("targetDragLeave", ADDRESS),
                upcall("targetDrop", ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS));

        private final long hwnd;
        private final FileDropListener listener;
        private List<Path> files = List.of(); // what is being dragged over the window

        Target(long hwnd, FileDropListener listener) {
            super(TABLE, IID_IDROPTARGET);
            this.hwnd = hwnd;
            this.listener = listener;
        }

        /** The window's own coordinates for a screen point packed as the low and high halves of {@code point}. */
        double[] toClient(long point) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment p = arena.allocate(8, 4);
                p.set(JAVA_INT, 0, (int) point);
                p.set(JAVA_INT, 4, (int) (point >> 32));
                int converted = (int) SCREEN_TO_CLIENT.invoke(MemorySegment.ofAddress(hwnd), p);
                return new double[]{p.get(JAVA_INT, 0), p.get(JAVA_INT, 4)};
            }
        }

        boolean takes(List<Path> candidates) {
            return !dragging && !candidates.isEmpty() && listener.accepts();
        }

        /** Sets the effect the pointer shows, and tells the listener where the files are. */
        int hover(long point, MemorySegment effectOut) throws Throwable {
            MemorySegment effect = effectOut.reinterpret(4);
            boolean copy = takes(files) && (effect.get(JAVA_INT, 0) & DROPEFFECT_COPY) != 0;
            effect.set(JAVA_INT, 0, copy ? DROPEFFECT_COPY : DROPEFFECT_NONE);
            if (copy) {
                double[] at = toClient(point);
                listener.over(files, at[0], at[1]);
            }
            return S_OK;
        }
    }

    private static Target target(MemorySegment self) {
        return LIVE.get(self.address()) instanceof Target target ? target : null;
    }

    private static int targetDragEnter(MemorySegment self, MemorySegment dataObject, int keyState, long point, MemorySegment effect) {
        try {
            Target target = target(self);
            if (target == null) return E_FAIL;
            target.files = dragging ? List.of() : readFiles(dataObject);
            return target.hover(point, effect);
        } catch (Throwable t) {
            return E_FAIL;
        }
    }

    private static int targetDragOver(MemorySegment self, int keyState, long point, MemorySegment effect) {
        try {
            Target target = target(self);
            return target == null ? E_FAIL : target.hover(point, effect);
        } catch (Throwable t) {
            return E_FAIL;
        }
    }

    private static int targetDragLeave(MemorySegment self) {
        try {
            Target target = target(self);
            if (target == null) return E_FAIL;
            boolean wasOver = !target.files.isEmpty();
            target.files = List.of();
            if (wasOver) target.listener.leave();
            return S_OK;
        } catch (Throwable t) {
            return E_FAIL;
        }
    }

    private static int targetDrop(MemorySegment self, MemorySegment dataObject, int keyState, long point, MemorySegment effectOut) {
        try {
            Target target = target(self);
            if (target == null) return E_FAIL;
            List<Path> files = dragging ? List.of() : readFiles(dataObject);
            target.files = List.of();
            MemorySegment effect = effectOut.reinterpret(4);
            boolean copy = target.takes(files) && (effect.get(JAVA_INT, 0) & DROPEFFECT_COPY) != 0;
            effect.set(JAVA_INT, 0, copy ? DROPEFFECT_COPY : DROPEFFECT_NONE);
            if (copy) {
                double[] at = target.toClient(point);
                target.listener.drop(files, at[0], at[1]);
            }
            return S_OK;
        } catch (Throwable t) {
            return E_FAIL;
        }
    }

    /**
     * The files in someone else's data object, or none if it holds no file list (text, a picture, or files that only
     * exist as a stream, such as ones inside a zip folder in Explorer).
     */
    static List<Path> readFiles(MemorySegment dataObject) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment format = arena.allocate(FORMATETC_SIZE, 8);
            format.set(JAVA_SHORT, 0, (short) CF_HDROP);
            format.set(JAVA_INT, 16, DVASPECT_CONTENT);
            format.set(JAVA_INT, 20, -1);
            format.set(JAVA_INT, 24, TYMED_HGLOBAL);
            int has = (int) callWithOne(dataObject, SLOT_QUERY_GET_DATA, format);
            if (has != S_OK) return List.of();
            MemorySegment medium = arena.allocate(STGMEDIUM_SIZE, 8);
            int got = (int) callWithTwo(dataObject, SLOT_GET_DATA, format, medium);
            if (got != S_OK) return List.of();
            try {
                if (medium.get(JAVA_INT, 0) != TYMED_HGLOBAL) return List.of();
                MemorySegment handle = medium.get(ADDRESS, 8);
                long size = (long) GLOBAL_SIZE.invoke(handle);
                MemorySegment block = ((MemorySegment) GLOBAL_LOCK.invoke(handle)).reinterpret(size);
                try {
                    return DropFiles.decode(block.toArray(ValueLayout.JAVA_BYTE));
                } finally {
                    int ignored = (int) GLOBAL_UNLOCK.invoke(handle);
                }
            } finally {
                RELEASE_STG_MEDIUM.invoke(medium);
            }
        }
    }

    private static int callWithOne(MemorySegment object, int slotIndex, MemorySegment a) throws Throwable {
        return (int) CALL_1.invoke(slot(object, slotIndex), object, a);
    }

    private static int callWithTwo(MemorySegment object, int slotIndex, MemorySegment a, MemorySegment b) throws Throwable {
        return (int) CALL_2.invoke(slot(object, slotIndex), object, a, b);
    }

    /**
     * Main thread: makes the window take drops through OLE instead of the shell's older {@code WM_DROPFILES}, which
     * can't tell the program anything until the drop. Null if it can't be done, in which case GLFW's drop callback
     * still delivers drops.
     */
    static Target register(long hwnd, FileDropListener listener) {
        if (!ensureOle()) return null;
        Target target = new Target(hwnd, listener);
        try {
            int hr = (int) REGISTER_DRAG_DROP.invoke(MemorySegment.ofAddress(hwnd), target.self);
            if (hr != S_OK) {
                log.warn("The window could not take OLE drops (0x{}); dropped files will arrive without hover events", Integer.toHexString(hr));
                target.release();
                return null;
            }
            // With an OLE target the older message would never arrive; turning it off keeps a drop from being delivered twice.
            DRAG_ACCEPT_FILES.invoke(MemorySegment.ofAddress(hwnd), 0);
            return target;
        } catch (Throwable t) {
            log.warn("The window could not take OLE drops", t);
            target.release();
            return null;
        }
    }

    /**
     * Main thread, before the window is destroyed.
     */
    static void unregister(long hwnd, Target target) {
        try {
            int ignored = (int) REVOKE_DRAG_DROP.invoke(MemorySegment.ofAddress(hwnd));
        } catch (Throwable t) {
            log.warn("Could not stop taking drops", t);
        }
        target.release();
    }

    /** For tests: an {@code IDropSource} as the operating system would be given it. */
    static MemorySegment dropSourcePointer(DropSource source) {
        return source.self;
    }

    /** For tests: the pointer to hand to code that expects an {@code IDropTarget}. */
    static MemorySegment pointer(Target target) {
        return target.self;
    }

    /** For tests: an {@code IDataObject} for a drag out, as the operating system would be given it. */
    static MemorySegment dataObjectPointer(DataObject data) {
        return data.self;
    }
}
