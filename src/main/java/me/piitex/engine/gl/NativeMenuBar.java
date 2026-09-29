package me.piitex.engine.gl;

import me.piitex.engine.ui.menu.MenuItem;
import me.piitex.engine.ui.menu.PopupMenu;
import me.piitex.engine.utils.flags.GeneratedFragments;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_getUid;

/**
 * Puts a {@link me.piitex.engine.ui.menu.MenuBarOverlay}'s menus in the macOS system menu bar
 * (the strip pinned to the top of the screen) instead of drawing them inside the window.
 * GLFW has no menu API, so this talks to AppKit directly through the Objective-C runtime,
 * the same way {@link me.piitex.engine.gl.renderer.metal.MetalContext} does. On every other
 * platform {@link #isSupported()} is false and nothing here does anything, so the in-window bar
 * stays in use.
 * <p>
 * Not meant to be called directly: {@code MenuBarOverlay} feeds it. The custom menus are
 * inserted between GLFW's own application menu (the first entry, with Quit) and its Window menu.
 * <p>
 * <b>Threading.</b> AppKit only allows menu changes on the main thread, while the UI model
 * lives on the render thread, so the two sides only meet through hand-off points, mirroring
 * {@link me.piitex.engine.input.Clipboard#pump()}:
 * <ul>
 *     <li>the render thread calls {@link #submit} each frame with a plain immutable {@link Snapshot} of the
 *         menus; a changed snapshot is parked in {@link #pending};</li>
 *     <li>the main loop calls {@link #pump()}, which rebuilds the native menus from the parked snapshot;</li>
 *     <li>choosing a native item queues its {@link MenuItem#fire()} and the render loop runs it from
 *         {@link #drainActions()}, so callbacks still run on the render thread like every other UI callback.</li>
 * </ul>
 * Because the native menu is rebuilt whenever the snapshot changes, enabling/disabling an item or flipping a
 * check item is picked up automatically. There is no separate refresh call.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class NativeMenuBar {
    private static final Logger log = LogManager.getLogger("Engine");
    private static final boolean MAC = System.getProperty("os.name").toLowerCase().contains("mac");

    /**
     * One immutable row of a menu, copied from a {@link MenuItem} on the render thread.
     */
    record Node(String label, String shortcut, boolean enabled, boolean checked, boolean checkable,
                boolean separator, List<Node> children, MenuItem source) {
    }

    /**
     * Every menu of the bar, in order. Records compare deeply, so equal snapshots mean nothing visible changed.
     */
    record Snapshot(List<String> titles, List<List<Node>> menus) {
        static final Snapshot EMPTY = new Snapshot(List.of(), List.of());
    }

    private static final AtomicReference<Snapshot> pending = new AtomicReference<>();
    private static final Queue<Runnable> actions = new ConcurrentLinkedQueue<>();
    private static Object owner;          // render thread: the bar currently driving the native menu
    private static Snapshot lastSent;     // render thread: what was last handed over

    private NativeMenuBar() {
    }

    /**
     * Whether this platform has a system menu bar this class can drive (macOS only).
     */
    public static boolean isSupported() {
        return MAC;
    }

    /**
     * Render thread. Offers {@code owner}'s menus for the system menu bar; the native menus are rebuilt only when
     * they differ from what was last offered. One native bar exists per application: the first owner to submit
     * keeps it until it calls {@link #release}, and submissions from any other owner are ignored.
     */
    public static void submit(Object owner, List<String> titles, List<PopupMenu> menus) {
        if (!MAC) return;
        if (NativeMenuBar.owner != null && NativeMenuBar.owner != owner) return;
        NativeMenuBar.owner = owner;

        List<List<Node>> nodes = new ArrayList<>(menus.size());
        for (PopupMenu menu : menus) nodes.add(copy(menu.getItems()));
        Snapshot snapshot = new Snapshot(List.copyOf(titles), nodes);
        if (snapshot.equals(lastSent)) return;
        lastSent = snapshot;
        pending.set(snapshot);
    }

    /**
     * Render thread. Removes {@code owner}'s menus from the system menu bar and frees it for another bar.
     */
    public static void release(Object owner) {
        if (!MAC || NativeMenuBar.owner != owner) return;
        NativeMenuBar.owner = null;
        lastSent = Snapshot.EMPTY;
        pending.set(Snapshot.EMPTY);
    }

    private static List<Node> copy(List<MenuItem> items) {
        List<Node> out = new ArrayList<>(items.size());
        for (MenuItem item : items) {
            out.add(new Node(item.getLabel(), item.getShortcut(), item.isEnabled(), item.isChecked(),
                    item.isCheckable(), item.isSeparator(), copy(item.getChildren()), item));
        }
        return out;
    }

    /**
     * Main thread, once per loop iteration: applies the newest submitted snapshot to the system menu bar.
     */
    public static void pump() {
        if (!MAC) return;
        Snapshot snapshot = pending.getAndSet(null);
        if (snapshot == null) return;
        try {
            Mac.rebuild(snapshot);
        } catch (Throwable t) {
            log.error("Failed to build the native menu bar; the in-window bar is unaffected.", t);
        }
    }

    /**
     * Render thread, once per frame: runs the callbacks of items chosen in the system menu bar.
     */
    public static void drainActions() {
        Runnable action;
        while ((action = actions.poll()) != null) {
            action.run();
        }
    }

    /**
     * All Objective-C interop, in its own class so nothing macOS-specific is loaded elsewhere
     * ({@code ObjCRuntime} does not exist off macOS).
     */
    private static final class Mac {
        private static final long MSG_SEND = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
        private static final Linker LINKER = Linker.nativeLinker();

        private static final MethodHandle SEND2 = sendHandle(2);
        private static final MethodHandle SEND3 = sendHandle(3);
        private static final MethodHandle SEND4 = sendHandle(4);
        private static final MethodHandle SEND5 = sendHandle(5);

        private static long target;                  // instance of our action-receiving class
        private static int installed;                // custom menus currently in the main menu
        private static long nextTag = 1;
        private static final Map<Long, Node> byTag = new HashMap<>(); // main thread only

        private static MethodHandle sendHandle(int args) {
            MemoryLayout[] params = new MemoryLayout[args];
            java.util.Arrays.fill(params, ValueLayout.JAVA_LONG);
            return LINKER.downcallHandle(MemorySegment.ofAddress(MSG_SEND),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, params));
        }

        // Every objc_msgSend here uses long for pointers, NSInteger/NSUInteger and BOOL, which is valid on
        // both arm64 and x86-64 for the integer-class arguments used by the selectors below.

        private static long send(long receiver, String selector) {
            try {
                return (long) SEND2.invokeExact(receiver, sel_getUid(selector));
            } catch (Throwable t) {
                throw new IllegalStateException("objc_msgSend " + selector + " failed", t);
            }
        }

        private static long send(long receiver, String selector, long a) {
            try {
                return (long) SEND3.invokeExact(receiver, sel_getUid(selector), a);
            } catch (Throwable t) {
                throw new IllegalStateException("objc_msgSend " + selector + " failed", t);
            }
        }

        private static long send(long receiver, String selector, long a, long b) {
            try {
                return (long) SEND4.invokeExact(receiver, sel_getUid(selector), a, b);
            } catch (Throwable t) {
                throw new IllegalStateException("objc_msgSend " + selector + " failed", t);
            }
        }

        private static long send(long receiver, String selector, long a, long b, long c) {
            try {
                return (long) SEND5.invokeExact(receiver, sel_getUid(selector), a, b, c);
            } catch (Throwable t) {
                throw new IllegalStateException("objc_msgSend " + selector + " failed", t);
            }
        }

        private static long cls(String name) {
            return objc_getClass(name);
        }

        /**
         * An autoreleased NSString; the arena only has to outlive the copy AppKit makes.
         */
        private static long nsString(String s, Arena arena) {
            return send(cls("NSString"), "stringWithUTF8String:", arena.allocateFrom(s == null ? "" : s).address());
        }

        /* Action target: an Objective-C class whose menuAction: forwards to Java. */

        private static long actionTarget() {
            if (target != 0L) return target;
            try (Arena arena = Arena.ofConfined()) {
                long nsObject = cls("NSObject");
                long name = arena.allocateFrom("RenGLMenuTarget").address();
                long cls = invokeAllocateClassPair(nsObject, name);
                if (cls != 0L) {
                    MethodHandle onAction = MethodHandles.lookup().findStatic(Mac.class, "onAction",
                            MethodType.methodType(void.class, long.class, long.class, long.class));
                    MemorySegment stub = LINKER.upcallStub(onAction,
                            FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                            Arena.global()); // must live as long as the class
                    long types = Arena.global().allocateFrom("v@:@").address();
                    invokeAddMethod(cls, sel_getUid("menuAction:"), stub.address(), types);
                    invokeRegisterClassPair(cls);
                } else {
                    cls = cls("RenGLMenuTarget"); // already registered
                }
                target = send(send(cls, "alloc"), "init");
                return target;
            } catch (Throwable t) {
                throw new IllegalStateException("Could not register the menu action target", t);
            }
        }

        private static final MethodHandle ALLOCATE_CLASS_PAIR = LINKER.downcallHandle(
                MemorySegment.ofAddress(ObjCRuntime.getLibrary().getFunctionAddress("objc_allocateClassPair")),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        private static final MethodHandle REGISTER_CLASS_PAIR = LINKER.downcallHandle(
                MemorySegment.ofAddress(ObjCRuntime.getLibrary().getFunctionAddress("objc_registerClassPair")),
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG));
        private static final MethodHandle ADD_METHOD = LINKER.downcallHandle(
                MemorySegment.ofAddress(ObjCRuntime.getLibrary().getFunctionAddress("class_addMethod")),
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

        private static long invokeAllocateClassPair(long superclass, long name) throws Throwable {
            return (long) ALLOCATE_CLASS_PAIR.invokeExact(superclass, name, 0L);
        }

        private static void invokeRegisterClassPair(long cls) throws Throwable {
            REGISTER_CLASS_PAIR.invokeExact(cls);
        }

        private static void invokeAddMethod(long cls, long selector, long imp, long types) throws Throwable {
            ADD_METHOD.invokeExact(cls, selector, imp, types);
        }

        /**
         * The IMP behind {@code -menuAction:}. Runs on the main thread, so it only queues the callback.
         */
        @SuppressWarnings("unused") // reached through the upcall stub
        private static void onAction(long self, long selector, long sender) {
            try {
                Node node = byTag.get(send(sender, "tag"));
                if (node != null && node.source() != null) actions.add(node.source()::fire);
            } catch (Throwable t) {
                log.error("Native menu action failed", t);
            }
        }

        /* Menu construction */

        static void rebuild(Snapshot snapshot) {
            long pool = send(send(cls("NSAutoreleasePool"), "alloc"), "init");
            try (Arena arena = Arena.ofConfined()) {
                long app = send(cls("NSApplication"), "sharedApplication");
                long mainMenu = send(app, "mainMenu");
                if (mainMenu == 0L) mainMenu = createMainMenu(app, arena);

                for (int i = 0; i < installed; i++) send(mainMenu, "removeItemAtIndex:", 1L);
                installed = 0;
                byTag.clear();
                nextTag = 1;

                long index = Math.min(1L, send(mainMenu, "numberOfItems")); // after the application menu
                for (int i = 0; i < snapshot.titles().size(); i++) {
                    String title = snapshot.titles().get(i);
                    long item = send(send(cls("NSMenuItem"), "alloc"), "initWithTitle:action:keyEquivalent:",
                            nsString(title, arena), 0L, nsString("", arena));
                    long submenu = buildMenu(snapshot.menus().get(i), title, arena);
                    send(item, "setSubmenu:", submenu);
                    send(submenu, "release");
                    send(mainMenu, "insertItem:atIndex:", item, index++);
                    send(item, "release");
                    installed++;
                }
            } finally {
                send(pool, "drain");
            }
        }

        /**
         * Only used if GLFW did not create a main menu: a bare application menu with Quit.
         */
        private static long createMainMenu(long app, Arena arena) {
            long menu = send(send(cls("NSMenu"), "alloc"), "initWithTitle:", nsString("", arena));
            long appItem = send(send(cls("NSMenuItem"), "alloc"), "initWithTitle:action:keyEquivalent:",
                    nsString("", arena), 0L, nsString("", arena));
            long appMenu = send(send(cls("NSMenu"), "alloc"), "initWithTitle:", nsString("Application", arena));
            long quit = send(send(cls("NSMenuItem"), "alloc"), "initWithTitle:action:keyEquivalent:",
                    nsString("Quit", arena), sel_getUid("terminate:"), nsString("q", arena));
            send(appMenu, "addItem:", quit);
            send(appItem, "setSubmenu:", appMenu);
            send(menu, "addItem:", appItem);
            send(app, "setMainMenu:", menu);
            return send(app, "mainMenu");
        }

        /**
         * Builds an NSMenu (owned, +1) from {@code nodes}; the caller releases it after attaching it.
         */
        private static long buildMenu(List<Node> nodes, String title, Arena arena) {
            long menu = send(send(cls("NSMenu"), "alloc"), "initWithTitle:", nsString(title, arena));
            send(menu, "setAutoenablesItems:", 0L); // enabled state comes from the model, not from responder-chain checks

            for (Node node : nodes) {
                if (node.separator()) {
                    send(menu, "addItem:", send(cls("NSMenuItem"), "separatorItem"));
                    continue;
                }
                boolean hasChildren = !node.children().isEmpty();
                Key key = parseShortcut(node.shortcut());
                long item = send(send(cls("NSMenuItem"), "alloc"), "initWithTitle:action:keyEquivalent:",
                        nsString(node.label(), arena),
                        hasChildren ? 0L : sel_getUid("menuAction:"),
                        nsString(key == null ? "" : key.character(), arena));
                long tag = nextTag++;
                byTag.put(tag, node);
                send(item, "setTag:", tag);
                send(item, "setTarget:", actionTarget());
                send(item, "setEnabled:", node.enabled() ? 1L : 0L);
                if (key != null) send(item, "setKeyEquivalentModifierMask:", key.mask());
                if (node.checkable() && node.checked()) send(item, "setState:", 1L); // NSControlStateValueOn
                if (hasChildren) {
                    long child = buildMenu(node.children(), node.label(), arena);
                    send(item, "setSubmenu:", child);
                    send(child, "release");
                }
                send(menu, "addItem:", item);
                send(item, "release");
            }
            return menu;
        }
    }

    /**
     * A key equivalent: the key and its NSEventModifierFlags mask.
     */
    record Key(String character, long mask) {
    }

    /**
     * Turns display text like {@code "Ctrl+Shift+Z"} into a macOS key equivalent, or null if it has none this can
     * express (empty text, or a named key such as {@code "F5"}, which the in-window text still shows).
     * {@code Ctrl}, {@code Cmd}, {@code Command}, {@code Meta} and {@code Super} all mean the Command key, since
     * a shortcut written as Ctrl+S is Cmd+S on a Mac; {@code Alt} and {@code Option} mean Option.
     */
    static Key parseShortcut(String shortcut) {
        if (shortcut == null || shortcut.isBlank()) return null;
        String[] parts = shortcut.split("\\+");
        String key = parts[parts.length - 1].trim();
        if (key.length() != 1) return null;

        long mask = 0L;
        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i].trim().toLowerCase()) {
                case "ctrl", "control", "cmd", "command", "meta", "super" -> mask |= 1L << 20;
                case "alt", "option", "opt" -> mask |= 1L << 19;
                case "shift" -> mask |= 1L << 17;
                default -> {
                    return null;
                }
            }
        }
        return new Key(key.toLowerCase(), mask);
    }
}
