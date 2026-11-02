package me.piitex.engine.gl;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.input.FileDrag;
import me.piitex.engine.ui.containers.Container;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The COM side of dragging files to and from the window, driven the way another program would drive it: through the
 * function tables of the objects. Nothing here uses the real mouse or drops anything anywhere. Skips off Windows.
 */
class WindowsDragDropTest {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final int E_NOINTERFACE = 0x80004002, E_FAIL = 0x80004005, DV_E_FORMATETC = 0x80040064, DV_E_TYMED = 0x80040069;
    private static final String TITLE = "RenGL drop target test";

    private static void windowsOnly() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "uses OLE");
    }

    private static Path path(String name) {
        return Path.of(System.getProperty("java.io.tmpdir"), name).toAbsolutePath();
    }

    // ---- calling COM objects ------------------------------------------------------------------------------------

    private static int call(MemorySegment object, int slot, FunctionDescriptor descriptor, Object... arguments) throws Throwable {
        MemorySegment table = object.reinterpret(8).get(ADDRESS, 0).reinterpret(8L * (slot + 1));
        Object[] all = new Object[arguments.length + 2];
        all[0] = table.get(ADDRESS, 8L * slot);
        all[1] = object;
        System.arraycopy(arguments, 0, all, 2, arguments.length);
        return (int) LINKER.downcallHandle(descriptor).invokeWithArguments(all);
    }

    private static final FunctionDescriptor ONE = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor ADDRESS_1 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor ADDRESS_2 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

    private static MemorySegment formatEtc(Arena arena, int format, int tymed) {
        MemorySegment f = arena.allocate(32, 8);
        f.set(JAVA_SHORT, 0, (short) format);
        f.set(JAVA_INT, 16, 1); // DVASPECT_CONTENT
        f.set(JAVA_INT, 20, -1);
        f.set(JAVA_INT, 24, tymed);
        return f;
    }

    private static byte[] iid(int d1) {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(d1).putShort((short) 0).putShort((short) 0)
                .put(new byte[]{(byte) 0xC0, 0, 0, 0, 0, 0, 0, 0x46}).array();
    }

    // ---- the files being dragged out ----------------------------------------------------------------------------

    @Test
    void theFilesAreMadeOnlyWhenSomeoneAsksForThem() throws Throwable {
        windowsOnly();
        AtomicInteger asked = new AtomicInteger();
        List<Path> files = List.of(path("one.txt"), path("a folder"));
        WindowsDragDrop.DataObject data = new WindowsDragDrop.DataObject(() -> {
            asked.incrementAndGet();
            return files;
        });
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment object = WindowsDragDrop.dataObjectPointer(data);

            assertEquals(0, call(object, 5, ADDRESS_1, formatEtc(arena, 15, 1)), "it holds a list of files");
            assertEquals(DV_E_FORMATETC, call(object, 5, ADDRESS_1, formatEtc(arena, 1, 1)), "but not text");
            assertEquals(DV_E_TYMED, call(object, 5, ADDRESS_1, formatEtc(arena, 15, 4)), "and only in memory, not as a stream");
            assertEquals(0, asked.get(), "asking what it holds is not asking for the files");

            assertEquals(files, WindowsDragDrop.readFiles(object));
            assertEquals(files, WindowsDragDrop.readFiles(object), "a second reader gets the same files");
            assertEquals(1, asked.get(), "the source was asked once");
        } finally {
            data.release();
        }
    }

    @Test
    void aSourceThatFailsFailsTheRequestAndIsNotAskedAgain() throws Throwable {
        windowsOnly();
        AtomicInteger asked = new AtomicInteger();
        WindowsDragDrop.DataObject data = new WindowsDragDrop.DataObject(() -> {
            asked.incrementAndGet();
            throw new IOException("the archive is damaged");
        });
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment object = WindowsDragDrop.dataObjectPointer(data);
            int hr = call(object, 3, ADDRESS_2, formatEtc(arena, 15, 1), arena.allocate(24, 8));
            assertEquals(E_FAIL, hr);
            assertEquals(List.of(), WindowsDragDrop.readFiles(object));
            assertEquals(1, asked.get());
            assertNotNull(data.failure);
        } finally {
            data.release();
        }
    }

    @Test
    void theDataObjectAnswersAsComAndListsItsOneFormat() throws Throwable {
        windowsOnly();
        WindowsDragDrop.DataObject data = new WindowsDragDrop.DataObject(List::of);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment object = WindowsDragDrop.dataObjectPointer(data);
            MemorySegment out = arena.allocate(8, 8);

            assertEquals(0, call(object, 0, ADDRESS_2, arena.allocateFrom(ValueLayout.JAVA_BYTE, iid(0x10e)), out), "IDataObject");
            assertEquals(object.address(), out.get(ADDRESS, 0).address());
            assertEquals(1, call(object, 2, ONE), "that asked for a reference, which is given back");
            assertEquals(0, call(object, 0, ADDRESS_2, arena.allocateFrom(ValueLayout.JAVA_BYTE, iid(0)), out), "IUnknown");
            assertEquals(1, call(object, 2, ONE));
            assertEquals(E_NOINTERFACE, call(object, 0, ADDRESS_2, arena.allocateFrom(ValueLayout.JAVA_BYTE, iid(0x122)), out), "not a drop target");
            assertEquals(0, out.get(ADDRESS, 0).address());

            MemorySegment list = arena.allocate(8, 8);
            assertEquals(0, call(object, 8, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), 1, list), "EnumFormatEtc(GET)");
            MemorySegment enumerator = list.get(ADDRESS, 0);
            assertNotEquals(0, enumerator.address());
            MemorySegment first = arena.allocate(32, 8), fetched = arena.allocate(4, 4);
            assertEquals(0, call(enumerator, 3, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS), 1, first, fetched), "Next");
            assertEquals(15, first.get(JAVA_SHORT, 0) & 0xFFFF, "CF_HDROP");
            assertEquals(1, fetched.get(JAVA_INT, 0));
            call(enumerator, 2, ONE);
        } finally {
            data.release();
        }
    }

    @Test
    void theDragEndsWhenTheButtonComesUpOrEscapeIsPressed() throws Throwable {
        windowsOnly();
        FunctionDescriptor query = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);
        WindowsDragDrop.DropSource source = new WindowsDragDrop.DropSource();
        try {
            MemorySegment object = WindowsDragDrop.dropSourcePointer(source);
            int MK_LBUTTON = 1;
            assertEquals(0, call(object, 3, query, 0, MK_LBUTTON), "button still down: carry on");
            assertEquals(0, call(object, 3, query, 0, MK_LBUTTON | 4), "with Shift held too");
            assertEquals(WindowsDragDrop.DRAGDROP_S_DROP, call(object, 3, query, 0, 0), "button up: drop");
            assertEquals(WindowsDragDrop.DRAGDROP_S_CANCEL, call(object, 3, query, 1, MK_LBUTTON), "Escape");
            assertEquals(WindowsDragDrop.DRAGDROP_S_USEDEFAULTCURSORS, call(object, 4, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), 1));
        } finally {
            source.release();
        }
    }

    // ---- the window as a drop target ----------------------------------------------------------------------------

    /** The few user32 calls the window test needs. */
    private static final class User32 {
        private final Arena arena = Arena.ofAuto();
        private final MethodHandle findWindow, getProp, clientToScreen, exStyle;

        User32() {
            SymbolLookup lookup = SymbolLookup.libraryLookup("user32", arena);
            findWindow = LINKER.downcallHandle(lookup.find("FindWindowW").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
            getProp = LINKER.downcallHandle(lookup.find("GetPropW").orElseThrow(), FunctionDescriptor.of(ADDRESS, JAVA_LONG, ADDRESS));
            clientToScreen = LINKER.downcallHandle(lookup.find("ClientToScreen").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
            exStyle = LINKER.downcallHandle(lookup.find("GetWindowLongPtrW").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT));
        }

        long find(String title) throws Throwable {
            return (long) findWindow.invoke(MemorySegment.NULL, arena.allocateFrom(title, StandardCharsets.UTF_16LE));
        }

        MemorySegment property(long hwnd, String name) throws Throwable {
            return (MemorySegment) getProp.invoke(hwnd, arena.allocateFrom(name, StandardCharsets.UTF_16LE));
        }

        /** The screen point that is (x, y) in the window, packed the way a POINTL is passed by value. */
        long screenPoint(long hwnd, int x, int y) throws Throwable {
            MemorySegment point = arena.allocate(8, 4);
            point.set(JAVA_INT, 0, x);
            point.set(JAVA_INT, 4, y);
            int ok = (int) clientToScreen.invoke(hwnd, point);
            return (point.get(JAVA_INT, 0) & 0xFFFFFFFFL) | ((long) point.get(JAVA_INT, 4) << 32);
        }

        long exStyle(long hwnd) throws Throwable {
            return (long) exStyle.invoke(hwnd, -20); // GWL_EXSTYLE
        }
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        for (int i = 0; i < 150 && !condition.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), what);
    }

    private static final FunctionDescriptor ENTER = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor OVER = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS);

    @Test
    void aWindowHearsFilesComeAndGoWhereThePointerIsAndDropsThemThere() throws Exception {
        windowsOnly();
        AtomicInteger frames = new AtomicInteger(), leaves = new AtomicInteger(), asked = new AtomicInteger();
        List<FileDrag.Hover> hovers = new CopyOnWriteArrayList<>();
        List<FileDrag.Drop> drops = new CopyOnWriteArrayList<>();
        List<List<Path>> plainDrops = new CopyOnWriteArrayList<>();
        AtomicReference<FileDrag.Outcome> started = new AtomicReference<>();
        List<Path> files = List.of(path("report.pdf"), path("a folder"));

        Window window = new Window(new WindowOptions(TITLE).setDimensions(400, 220).setVsync(false));
        window.hide();
        Container root = new Container(400, 220);
        root.onUpdate(_ -> frames.incrementAndGet());
        window.addContainer(root);
        window.onFileDragOver(hovers::add);
        window.onFileDragLeave(leaves::incrementAndGet);
        window.onFileDropAt(drops::add);
        window.onFileDrop(plainDrops::add);

        Throwable[] failure = new Throwable[1];
        boolean[] drove = new boolean[1];
        Thread driver = new Thread(() -> {
            WindowsDragDrop.DataObject data = null;
            try {
                User32 user32 = new User32();
                long hwnd = 0;
                for (int i = 0; i < 150 && hwnd == 0; i++) {
                    hwnd = user32.find(TITLE);
                    if (hwnd == 0) Thread.sleep(100);
                }
                for (int i = 0; i < 150 && frames.get() < 10; i++) Thread.sleep(100);
                if (hwnd == 0 || frames.get() < 10) return; // no window or no frames: no display, skip
                drove[0] = true;

                MemorySegment target = user32.property(hwnd, "OleDropTargetInterface");
                assertNotEquals(0, target.address(), "the window registered an OLE drop target");
                assertEquals(0, user32.exStyle(hwnd) & 0x10, "the older WM_DROPFILES is off, so a drop is not delivered twice");

                data = new WindowsDragDrop.DataObject(() -> {
                    asked.incrementAndGet();
                    return files;
                });
                MemorySegment dataObject = WindowsDragDrop.dataObjectPointer(data);
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment effect = arena.allocate(4, 4);

                    effect.set(JAVA_INT, 0, 3); // the source allows copy and move
                    assertEquals(0, call(target, 3, ENTER, dataObject, 0, user32.screenPoint(hwnd, 50, 60), effect));
                    assertEquals(1, effect.get(JAVA_INT, 0), "it will copy");
                    waitFor(() -> !hovers.isEmpty(), "the hover reached the window");
                    FileDrag.Hover entered = hovers.getLast();
                    assertEquals(files, entered.files());
                    assertEquals(50, entered.x(), 1);
                    assertEquals(60, entered.y(), 1);

                    effect.set(JAVA_INT, 0, 3);
                    assertEquals(0, call(target, 4, OVER, 0, user32.screenPoint(hwnd, 70, 80), effect));
                    waitFor(() -> Math.abs(hovers.getLast().x() - 70) <= 1, "the move reached the window");
                    assertEquals(80, hovers.getLast().y(), 1);

                    assertEquals(0, call(target, 5, ONE));
                    waitFor(() -> leaves.get() == 1, "the leave reached the window");

                    effect.set(JAVA_INT, 0, 3);
                    assertEquals(0, call(target, 3, ENTER, dataObject, 0, user32.screenPoint(hwnd, 10, 10), effect));
                    effect.set(JAVA_INT, 0, 3);
                    assertEquals(0, call(target, 6, ENTER, dataObject, 0, user32.screenPoint(hwnd, 30, 40), effect), "Drop");
                    assertEquals(1, effect.get(JAVA_INT, 0));
                    waitFor(() -> !drops.isEmpty() && !plainDrops.isEmpty(), "the drop reached the window");
                    assertEquals(1, drops.size());
                    assertEquals(files, drops.getFirst().files());
                    assertEquals(30, drops.getFirst().x(), 1);
                    assertEquals(40, drops.getFirst().y(), 1);
                    assertEquals(List.of(files), plainDrops);
                    assertEquals(1, leaves.get(), "a drop is not a leave");

                    // With nothing listening for drops the pointer says no, and nothing is reported.
                    window.onFileDrop(null);
                    window.onFileDropAt(null);
                    int hoversBefore = hovers.size();
                    effect.set(JAVA_INT, 0, 3);
                    assertEquals(0, call(target, 3, ENTER, dataObject, 0, user32.screenPoint(hwnd, 50, 60), effect));
                    assertEquals(0, effect.get(JAVA_INT, 0), "no drop is taken");
                    Thread.sleep(200);
                    assertEquals(hoversBefore, hovers.size());
                    call(target, 5, ONE);

                    // The button is not down, so a request to drag out does nothing and does not ask for the files.
                    int askedBefore = asked.get();
                    window.startFileDrag(() -> {
                        asked.incrementAndGet();
                        return files;
                    }, started::set);
                    waitFor(() -> started.get() != null, "the drag request was answered");
                    assertEquals(askedBefore, asked.get());
                }
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                if (data != null) data.release();
                window.close();
            }
        }, "drop-target-driver");
        driver.setDaemon(true);
        driver.start();

        try {
            new Engine().start(window);
        } catch (Throwable t) {
            assumeTrue(false, "the engine could not start here: " + t);
        } finally {
            driver.join(5_000);
        }

        assumeTrue(drove[0], "the window never opened");
        if (failure[0] != null) fail(failure[0]);
        assertEquals(FileDrag.Outcome.CANCELLED, started.get(), "no button held: nothing was dragged");
    }
}
