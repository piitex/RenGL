package me.piitex.engine.gl;

import me.piitex.engine.input.OpenFiles;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_getUid;

/**
 * Receives "open these files" on macOS, where a program is not given its files as arguments: Finder sends the
 * running application an Apple event, which AppKit turns into {@code application:openFiles:} on the application
 * delegate. GLFW owns that delegate ({@code GLFWApplicationDelegate}) and does not implement the method, so this adds it
 * to the class. The request is queued in {@link OpenFiles}.
 * <p>
 * It has to be added before {@code glfwInit}. Finder's event for a double-clicked file is delivered while GLFW starts
 * the application up, and an application delegate without the method just misses it. The GLFW class is already in the
 * Objective-C runtime once its library is loaded, so that is possible.
 * <p>
 * AppKit also reports the process's own command-line words as files to open, so launching with
 * {@code java -jar app.jar} asks to open {@code app.jar}. {@link OpenFiles#onlyRealFiles} drops those.
 * <p>
 * Does nothing on other platforms, where {@link OpenFiles#fromArguments} is the way in.
 */
public final class MacOpenFiles {
    private static final Logger log = LogManager.getLogger("Engine");
    private static boolean installed;

    private MacOpenFiles() {
    }

    /**
     * Main thread, before GLFW is initialised. Safe to call more than once.
     */
    public static synchronized void install() {
        if (installed || !System.getProperty("os.name", "").toLowerCase().contains("mac")) return;
        installed = true;
        try {
            Class.forName("org.lwjgl.glfw.GLFW"); // loads GLFW's native library, which registers its Objective-C classes; glfwInit stays for later
            Mac.install();
        } catch (Throwable t) {
            log.warn("Opening files from Finder is not available: {}", t.toString());
        }
    }

    /**
     * For tests: sends {@code application:openFiles:} to the application delegate, as AppKit does.
     * Only valid after GLFW is initialised.
     */
    public static void simulate(List<Path> paths) {
        Mac.simulate(paths);
    }

    /**
     * All Objective-C interop, in its own class so nothing macOS-specific loads elsewhere.
     */
    private static final class Mac {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final long MSG_SEND = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
        private static final MethodHandle SEND2 = send(2), SEND3 = send(3), SEND4 = send(4);
        private static final MethodHandle ADD_METHOD = LINKER.downcallHandle(
                MemorySegment.ofAddress(ObjCRuntime.getLibrary().getFunctionAddress("class_addMethod")),
                FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

        private static MethodHandle send(int args) {
            var params = new java.lang.foreign.MemoryLayout[args];
            java.util.Arrays.fill(params, ValueLayout.JAVA_LONG);
            return LINKER.downcallHandle(MemorySegment.ofAddress(MSG_SEND), FunctionDescriptor.of(ValueLayout.JAVA_LONG, params));
        }

        private static long call(long receiver, String selector) throws Throwable {
            return (long) SEND2.invokeExact(receiver, sel_getUid(selector));
        }

        private static long call(long receiver, String selector, long a) throws Throwable {
            return (long) SEND3.invokeExact(receiver, sel_getUid(selector), a);
        }

        static void install() throws Throwable {
            long delegate = objc_getClass("GLFWApplicationDelegate");
            if (delegate == 0L) {
                log.debug("GLFWApplicationDelegate is not registered; cannot receive files from Finder");
                return;
            }
            MethodHandle handler = MethodHandles.lookup().findStatic(Mac.class, "onOpenFiles",
                    MethodType.methodType(void.class, long.class, long.class, long.class, long.class));
            MemorySegment stub = LINKER.upcallStub(handler,
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                    Arena.global()); // must live as long as the class
            long types = Arena.global().allocateFrom("v@:@@").address();
            byte added = (byte) ADD_METHOD.invokeExact(delegate, sel_getUid("application:openFiles:"), stub.address(), types);
            if (added == 0) log.debug("GLFW's application delegate already handles application:openFiles:");
        }

        /**
         * The IMP behind {@code -application:openFiles:}. Runs on the main thread, so it only queues the paths.
         */
        @SuppressWarnings("unused") // reached through the upcall stub
        private static void onOpenFiles(long self, long selector, long application, long filenames) {
            try {
                OpenFiles.deliver(OpenFiles.onlyRealFiles(readPaths(filenames)));
                // Tell AppKit it was handled, or it shows its own "can't open" alert: NSApplicationDelegateReplySuccess = 0.
                call(application, "replyToOpenOrPrint:", 0L);
            } catch (Throwable t) {
                log.error("Receiving files from Finder failed", t);
            }
        }

        private static List<Path> readPaths(long nsArray) throws Throwable {
            List<Path> paths = new ArrayList<>();
            long count = call(nsArray, "count");
            for (long i = 0; i < count; i++) {
                long item = call(nsArray, "objectAtIndex:", i);
                long chars = call(item, "UTF8String");
                if (chars == 0L) continue;
                try {
                    paths.add(Path.of(MemorySegment.ofAddress(chars).reinterpret(Long.MAX_VALUE).getString(0)));
                } catch (InvalidPathException ignored) {
                    // not a path this file system can name
                }
            }
            return paths;
        }

        static void simulate(List<Path> paths) {
            try (Arena arena = Arena.ofConfined()) {
                long array = call(call(objc_getClass("NSMutableArray"), "alloc"), "init");
                for (Path path : paths) {
                    long string = call(objc_getClass("NSString"), "stringWithUTF8String:", arena.allocateFrom(path.toString()).address());
                    call(array, "addObject:", string);
                }
                long app = call(objc_getClass("NSApplication"), "sharedApplication");
                long delegate = call(app, "delegate");
                long ignored = (long) SEND4.invokeExact(delegate, sel_getUid("application:openFiles:"), app, array);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
    }
}
