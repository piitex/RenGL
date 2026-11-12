package me.piitex.engine.access.uia;

import me.piitex.engine.access.AccessibleRole;
import me.piitex.engine.ui.Element;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The COM objects UI Automation talks to. One {@link Obj} stands for one control (or the window), and shows the system
 * a different table of functions for each interface it asks for: the basic element, its place in the tree, and the
 * patterns that say what can be done with it (press, toggle, set a value).
 * <p>
 * The functions are called from threads the system chooses, at any moment, so every one of them answers from the latest
 * {@link UiaFrame} and does nothing to the Elements. Anything that acts on a control is handed to the render thread.
 * A function that threw would take the process down, so each answers with an error code instead.
 */
final class UiaCom {
    private static final Logger log = LogManager.getLogger("Engine");

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena GLOBAL = Arena.global();

    // Which table of functions a view holds
    static final int SIMPLE = 0, FRAGMENT = 1, FRAGMENT_ROOT = 2, INVOKE = 3, TOGGLE = 4, VALUE = 5, RANGE_VALUE = 6, SELECTION_ITEM = 7;
    private static final int VIEWS = 8;

    private static byte[] guid(int d1, int d2, int d3, int... d4) {
        ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(d1).putShort((short) d2).putShort((short) d3);
        for (int v : d4) b.put((byte) v);
        return b.array();
    }

    private static final byte[] IID_IUNKNOWN = guid(0, 0, 0, 0xC0, 0, 0, 0, 0, 0, 0, 0x46);
    private static final byte[][] IIDS = new byte[VIEWS][];

    static {
        IIDS[SIMPLE] = guid(0xd6dd68d1, 0x86fd, 0x4332, 0x86, 0x66, 0x9a, 0xbe, 0xde, 0xa2, 0xd2, 0x4c);
        IIDS[FRAGMENT] = guid(0xf7063da8, 0x8359, 0x439c, 0x92, 0x97, 0xbb, 0xc5, 0x29, 0x9a, 0x7d, 0x87);
        IIDS[FRAGMENT_ROOT] = guid(0x620ce2a5, 0xab8f, 0x40a9, 0x86, 0xcb, 0xde, 0x3c, 0x75, 0x59, 0x9b, 0x58);
        IIDS[INVOKE] = guid(0x54fcb24b, 0xe18e, 0x47a2, 0xb4, 0xd3, 0xec, 0xcb, 0xe7, 0x75, 0x99, 0xa2);
        IIDS[TOGGLE] = guid(0x56d00bd0, 0xc4f4, 0x433c, 0xa8, 0x36, 0x1a, 0x52, 0xa5, 0x7e, 0x08, 0x92);
        IIDS[VALUE] = guid(0xc7935180, 0x6fb3, 0x4201, 0xb1, 0x74, 0x7d, 0xf7, 0x3a, 0xdb, 0xf6, 0x4a);
        IIDS[RANGE_VALUE] = guid(0x36dc7aef, 0x33e6, 0x4691, 0xaf, 0xe1, 0x2b, 0xe7, 0x27, 0x4b, 0x3d, 0x33);
        IIDS[SELECTION_ITEM] = guid(0x2acad808, 0xb2d4, 0x452d, 0xa4, 0x07, 0x91, 0xff, 0x1a, 0xd1, 0x67, 0xb2);
    }

    private static final MethodHandle CALL_1 = LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** What the system needs from the window and its controls. Implemented by {@link UiaHost}. */
    interface Owner {
        UiaFrame frame();

        /** The window's size in its own units, and where the client area sits on the screen in pixels. */
        double[] geometry();

        /** Runs on the render thread. */
        void later(Runnable action);

        void focus(Element element);

        MemorySegment hostProvider();

        /**
         * The one object that stands for this control (0 is the window). Windows tells providers apart by their
         * identity, so asking twice must give the same object back.
         */
        Obj object(int id);
    }

    // ---- objects -----------------------------------------------------------------------------------------------

    private static final Map<Long, Obj> LIVE = new ConcurrentHashMap<>();
    private static final AtomicLong COOKIES = new AtomicLong(1);

    /** A control as COM sees it. Freed when the last reference to any of its views is released. */
    static final class Obj {
        final Owner owner;
        final int id;
        private final long cookie = COOKIES.getAndIncrement();
        private final Arena arena = Arena.ofShared();
        private final AtomicInteger references = new AtomicInteger(1);
        private final MemorySegment[] views = new MemorySegment[VIEWS];

        Obj(Owner owner, int id) {
            this.owner = owner;
            this.id = id;
            for (int i = 0; i < VIEWS; i++) {
                MemorySegment view = arena.allocate(16, 8);
                view.set(ADDRESS, 0, TABLES[i]);
                view.set(JAVA_LONG, 8, cookie);
                views[i] = view;
            }
            LIVE.put(cookie, this);
        }

        /** The pointer for one interface. Does not add a reference. */
        MemorySegment view(int index) {
            return views[index];
        }

        MemorySegment simple() {
            return views[SIMPLE];
        }

        int addRef() {
            return references.incrementAndGet();
        }

        int release() {
            int left = references.decrementAndGet();
            if (left <= 0) {
                LIVE.remove(cookie);
                arena.close();
                return 0;
            }
            return left;
        }

        boolean isRoot() {
            return id == 0;
        }

        UiaFrame.Item item() {
            return owner.frame().item(id);
        }

        /** Whether this control answers to the interface: the basic ones always, a pattern by what the control is. */
        boolean supports(int view) {
            if (view == SIMPLE || view == FRAGMENT) return true;
            if (view == FRAGMENT_ROOT) return isRoot();
            UiaFrame.Item item = item();
            if (item == null || isRoot()) return false;
            AccessibleRole role = item.role();
            return switch (view) {
                case INVOKE -> role == AccessibleRole.BUTTON || role == AccessibleRole.LINK || role == AccessibleRole.MENU_ITEM;
                case TOGGLE -> role == AccessibleRole.CHECK_BOX || role == AccessibleRole.SWITCH;
                case VALUE -> role == AccessibleRole.TEXT_FIELD || role == AccessibleRole.TEXT_AREA || role == AccessibleRole.COMBO_BOX;
                case RANGE_VALUE -> item.ranged();
                case SELECTION_ITEM -> role == AccessibleRole.RADIO_BUTTON || role == AccessibleRole.LIST_ITEM || role == AccessibleRole.TAB;
                default -> false;
            };
        }
    }

    private static Obj from(MemorySegment self) {
        long cookie = self.reinterpret(16).get(JAVA_LONG, 8);
        return LIVE.get(cookie);
    }

    private static boolean same(MemorySegment riid, byte[] guid) {
        return riid.reinterpret(16).mismatch(MemorySegment.ofArray(guid)) == -1;
    }

    /** A pointer that is safe to give away: takes a reference for the receiver. */
    private static MemorySegment give(Obj object, int view) {
        object.addRef();
        return object.view(view);
    }

    private static void out(MemorySegment pointer, MemorySegment value) {
        pointer.reinterpret(8).set(ADDRESS, 0, value);
    }

    // ---- the tables --------------------------------------------------------------------------------------------

    private static MemorySegment upcall(String method, MemoryLayout... arguments) {
        FunctionDescriptor descriptor = FunctionDescriptor.of(JAVA_INT, arguments);
        try {
            MethodHandle target = MethodHandles.lookup().findStatic(UiaCom.class, method, descriptor.toMethodType());
            return LINKER.upcallStub(target, descriptor, GLOBAL);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MemorySegment table(MemorySegment... own) {
        MemorySegment[] all = new MemorySegment[3 + own.length];
        all[0] = upcall("queryInterface", ADDRESS, ADDRESS, ADDRESS);
        all[1] = upcall("addRef", ADDRESS);
        all[2] = upcall("release", ADDRESS);
        System.arraycopy(own, 0, all, 3, own.length);
        MemorySegment table = GLOBAL.allocate(8L * all.length, 8);
        for (int i = 0; i < all.length; i++) table.setAtIndex(ADDRESS, i, all[i]);
        return table;
    }

    private static final MemorySegment[] TABLES = new MemorySegment[VIEWS];

    static {
        TABLES[SIMPLE] = table(
                upcall("providerOptions", ADDRESS, ADDRESS),
                upcall("patternProvider", ADDRESS, JAVA_INT, ADDRESS),
                upcall("propertyValue", ADDRESS, JAVA_INT, ADDRESS),
                upcall("hostProvider", ADDRESS, ADDRESS));
        TABLES[FRAGMENT] = table(
                upcall("navigate", ADDRESS, JAVA_INT, ADDRESS),
                upcall("runtimeId", ADDRESS, ADDRESS),
                upcall("boundingRectangle", ADDRESS, ADDRESS),
                upcall("embeddedFragmentRoots", ADDRESS, ADDRESS),
                upcall("setFocus", ADDRESS),
                upcall("fragmentRoot", ADDRESS, ADDRESS));
        TABLES[FRAGMENT_ROOT] = table(
                upcall("elementFromPoint", ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS),
                upcall("focusedElement", ADDRESS, ADDRESS));
        TABLES[INVOKE] = table(upcall("invoke", ADDRESS));
        TABLES[TOGGLE] = table(
                upcall("toggle", ADDRESS),
                upcall("toggleState", ADDRESS, ADDRESS));
        TABLES[VALUE] = table(
                upcall("setValue", ADDRESS, ADDRESS),
                upcall("getValue", ADDRESS, ADDRESS),
                upcall("valueIsReadOnly", ADDRESS, ADDRESS));
        TABLES[RANGE_VALUE] = table(
                upcall("setRange", ADDRESS, JAVA_DOUBLE),
                upcall("rangeValue", ADDRESS, ADDRESS),
                upcall("rangeIsReadOnly", ADDRESS, ADDRESS),
                upcall("rangeMaximum", ADDRESS, ADDRESS),
                upcall("rangeMinimum", ADDRESS, ADDRESS),
                upcall("rangeLargeChange", ADDRESS, ADDRESS),
                upcall("rangeSmallChange", ADDRESS, ADDRESS));
        TABLES[SELECTION_ITEM] = table(
                upcall("select", ADDRESS),
                upcall("addToSelection", ADDRESS),
                upcall("removeFromSelection", ADDRESS),
                upcall("isSelected", ADDRESS, ADDRESS),
                upcall("selectionContainer", ADDRESS, ADDRESS));
    }

    /** Forces the tables to be built, and so any failure to build them to show up now. */
    static void init() {
        if (TABLES[SIMPLE] == null) throw new IllegalStateException();
    }

    // ---- IUnknown ----------------------------------------------------------------------------------------------

    private static int queryInterface(MemorySegment self, MemorySegment riid, MemorySegment ppv) {
        try {
            Obj object = from(self);
            if (object == null) {
                out(ppv, MemorySegment.NULL);
                return Uia.E_NOINTERFACE;
            }
            if (same(riid, IID_IUNKNOWN)) {
                out(ppv, give(object, SIMPLE));
                return Uia.S_OK;
            }
            for (int view = 0; view < VIEWS; view++) {
                if (same(riid, IIDS[view]) && object.supports(view)) {
                    out(ppv, give(object, view));
                    return Uia.S_OK;
                }
            }
            out(ppv, MemorySegment.NULL);
            return Uia.E_NOINTERFACE;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int addRef(MemorySegment self) {
        Obj object = from(self);
        return object == null ? 0 : object.addRef();
    }

    private static int release(MemorySegment self) {
        Obj object = from(self);
        return object == null ? 0 : object.release();
    }

    private static int fail(Throwable t) {
        log.debug("UI Automation provider call failed", t);
        return Uia.E_FAIL;
    }

    // ---- IRawElementProviderSimple -----------------------------------------------------------------------------

    private static int providerOptions(MemorySegment self, MemorySegment result) {
        try {
            result.reinterpret(4).set(JAVA_INT, 0, Uia.PROVIDER_SERVER_SIDE);
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int patternProvider(MemorySegment self, int pattern, MemorySegment result) {
        try {
            Obj object = from(self);
            int view = switch (pattern) {
                case Uia.PATTERN_INVOKE -> INVOKE;
                case Uia.PATTERN_TOGGLE -> TOGGLE;
                case Uia.PATTERN_VALUE -> VALUE;
                case Uia.PATTERN_RANGE_VALUE -> RANGE_VALUE;
                case Uia.PATTERN_SELECTION_ITEM -> SELECTION_ITEM;
                default -> -1;
            };
            out(result, object != null && view >= 0 && object.supports(view) ? give(object, view) : MemorySegment.NULL);
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int controlType(UiaFrame.Item item) {
        return switch (item.role()) {
            case BUTTON, SWITCH -> 50000;
            case CHECK_BOX -> 50002;
            case COMBO_BOX -> 50003;
            case TEXT_FIELD, TEXT_AREA -> 50004;
            case LINK -> 50005;
            case IMAGE -> 50006;
            case LIST_ITEM -> 50007;
            case LIST -> 50008;
            case MENU -> 50009;
            case MENU_ITEM -> 50011;
            case PROGRESS_BAR -> 50012;
            case RADIO_BUTTON -> 50013;
            case SLIDER -> 50015;
            case SPIN_BUTTON -> 50016;
            case STATUS -> 50017;
            case TAB_LIST -> 50018;
            case TAB -> 50019;
            case TEXT -> 50020;
            case TREE -> 50023;
            case TREE_ITEM -> 50024;
            case GROUP -> 50026;
            case TABLE -> 50036;
            case DIALOG -> 50032;
            default -> 50025; // custom
        };
    }

    private static int propertyValue(MemorySegment self, int property, MemorySegment result) {
        try {
            Uia.variantEmpty(result);
            Obj object = from(self);
            if (object == null) return Uia.S_OK;
            if (object.isRoot()) return Uia.S_OK; // the window's own properties come from the window
            UiaFrame.Item item = object.item();
            if (item == null) return Uia.S_OK;
            switch (property) {
                case Uia.PROP_CONTROL_TYPE -> Uia.variantInt(result, controlType(item));
                case Uia.PROP_LOCALIZED_CONTROL_TYPE -> {
                    if (item.role() == AccessibleRole.SWITCH) Uia.variantString(result, "switch");
                }
                case Uia.PROP_NAME -> Uia.variantString(result, item.name());
                case Uia.PROP_HAS_KEYBOARD_FOCUS -> Uia.variantBool(result, item.focused());
                case Uia.PROP_IS_KEYBOARD_FOCUSABLE -> Uia.variantBool(result, item.focusable());
                case Uia.PROP_IS_ENABLED -> Uia.variantBool(result, !item.disabled());
                case Uia.PROP_AUTOMATION_ID -> Uia.variantString(result, "rengl-" + item.id());
                case Uia.PROP_CLASS_NAME -> Uia.variantString(result, item.className());
                case Uia.PROP_HELP_TEXT, Uia.PROP_FULL_DESCRIPTION -> {
                    if (item.description() != null && !item.description().isBlank()) Uia.variantString(result, item.description());
                }
                case Uia.PROP_IS_CONTROL_ELEMENT, Uia.PROP_IS_CONTENT_ELEMENT -> Uia.variantBool(result, true);
                case Uia.PROP_IS_PASSWORD -> Uia.variantBool(result, item.password());
                case Uia.PROP_IS_OFFSCREEN -> Uia.variantBool(result, false);
                case Uia.PROP_FRAMEWORK_ID -> Uia.variantString(result, "RenGL");
                default -> {
                }
            }
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int hostProvider(MemorySegment self, MemorySegment result) {
        try {
            Obj object = from(self);
            out(result, object != null && object.isRoot() ? object.owner.hostProvider() : MemorySegment.NULL);
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    // ---- IRawElementProviderFragment ---------------------------------------------------------------------------

    private static int navigate(MemorySegment self, int direction, MemorySegment result) {
        try {
            Obj object = from(self);
            out(result, MemorySegment.NULL);
            if (object == null) return Uia.S_OK;
            UiaFrame frame = object.owner.frame();
            UiaFrame.Item item = frame.item(object.id);
            if (item == null) return Uia.UIA_E_ELEMENTNOTAVAILABLE;
            int target = -1;
            switch (direction) {
                case 0 -> target = object.isRoot() ? -1 : item.parent(); // parent; the window's parent is the window system's
                case 1, 2 -> {
                    if (object.isRoot()) break;
                    var siblings = frame.siblings(item.parent());
                    int at = siblings.indexOf(item.id());
                    int to = direction == 1 ? at + 1 : at - 1;
                    if (at >= 0 && to >= 0 && to < siblings.size()) target = siblings.get(to);
                }
                case 3 -> target = item.children().length == 0 ? -1 : item.children()[0];
                case 4 -> target = item.children().length == 0 ? -1 : item.children()[item.children().length - 1];
                default -> {
                }
            }
            if (target >= 0) out(result, give(object.owner.object(target), FRAGMENT));
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int runtimeId(MemorySegment self, MemorySegment result) {
        try {
            Obj object = from(self);
            out(result, object == null || object.isRoot() ? MemorySegment.NULL : Uia.intArray(Uia.UIA_APPEND_RUNTIME_ID, object.id));
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int boundingRectangle(MemorySegment self, MemorySegment result) {
        try {
            MemorySegment rect = result.reinterpret(32);
            rect.fill((byte) 0);
            Obj object = from(self);
            if (object == null) return Uia.S_OK;
            double[] g = object.owner.geometry(); // unitsWide, unitsHigh, screenX, screenY, pixelsWide, pixelsHigh
            if (g == null || g[0] <= 0 || g[1] <= 0) return Uia.S_OK;
            double sx = g[4] / g[0], sy = g[5] / g[1];
            if (object.isRoot()) {
                rect.set(JAVA_DOUBLE, 0, g[2]);
                rect.set(JAVA_DOUBLE, 8, g[3]);
                rect.set(JAVA_DOUBLE, 16, g[4]);
                rect.set(JAVA_DOUBLE, 24, g[5]);
                return Uia.S_OK;
            }
            UiaFrame.Item item = object.item();
            if (item == null) return Uia.UIA_E_ELEMENTNOTAVAILABLE;
            rect.set(JAVA_DOUBLE, 0, g[2] + item.x() * sx);
            rect.set(JAVA_DOUBLE, 8, g[3] + item.y() * sy);
            rect.set(JAVA_DOUBLE, 16, item.width() * sx);
            rect.set(JAVA_DOUBLE, 24, item.height() * sy);
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int embeddedFragmentRoots(MemorySegment self, MemorySegment result) {
        try {
            out(result, MemorySegment.NULL);
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int setFocus(MemorySegment self) {
        try {
            Obj object = from(self);
            UiaFrame.Item item = object == null ? null : object.item();
            if (item == null) return Uia.UIA_E_ELEMENTNOTAVAILABLE;
            if (item.element() == null || !item.focusable()) return Uia.UIA_E_NOTSUPPORTED;
            Element element = item.element();
            object.owner.later(() -> object.owner.focus(element));
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int fragmentRoot(MemorySegment self, MemorySegment result) {
        try {
            Obj object = from(self);
            out(result, object == null ? MemorySegment.NULL : give(object.owner.object(0), FRAGMENT_ROOT));
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    // ---- IRawElementProviderFragmentRoot -----------------------------------------------------------------------

    private static int elementFromPoint(MemorySegment self, double screenX, double screenY, MemorySegment result) {
        try {
            out(result, MemorySegment.NULL);
            Obj object = from(self);
            if (object == null) return Uia.S_OK;
            double[] g = object.owner.geometry();
            if (g == null || g[4] <= 0 || g[5] <= 0) return Uia.S_OK;
            double x = (screenX - g[2]) * g[0] / g[4], y = (screenY - g[3]) * g[1] / g[5];
            int hit = object.owner.frame().hit(x, y);
            if (hit > 0) out(result, give(object.owner.object(hit), FRAGMENT));
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int focusedElement(MemorySegment self, MemorySegment result) {
        try {
            out(result, MemorySegment.NULL);
            Obj object = from(self);
            if (object == null) return Uia.S_OK;
            int focus = object.owner.frame().focus();
            if (focus > 0) out(result, give(object.owner.object(focus), FRAGMENT));
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    // ---- patterns ----------------------------------------------------------------------------------------------

    /** The control and the Element to act on, or an error code. */
    private interface Action {
        void run(Element element);
    }

    private static int act(MemorySegment self, Action action) {
        Obj object = from(self);
        UiaFrame.Item item = object == null ? null : object.item();
        if (item == null || item.element() == null) return Uia.UIA_E_ELEMENTNOTAVAILABLE;
        if (item.disabled()) return Uia.UIA_E_ELEMENTNOTENABLED;
        Element element = item.element();
        object.owner.later(() -> {
            try {
                action.run(element);
            } catch (Throwable t) {
                log.warn("An accessibility action failed", t);
            }
        });
        return Uia.S_OK;
    }

    private static int invoke(MemorySegment self) {
        try {
            return act(self, Actions::press);
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int toggle(MemorySegment self) {
        try {
            return act(self, Actions::press);
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int toggleState(MemorySegment self, MemorySegment result) {
        try {
            Obj object = from(self);
            UiaFrame.Item item = object == null ? null : object.item();
            if (item == null) return Uia.UIA_E_ELEMENTNOTAVAILABLE;
            result.reinterpret(4).set(JAVA_INT, 0, Boolean.TRUE.equals(item.checked()) ? 1 : 0);
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int setValue(MemorySegment self, MemorySegment text) {
        try {
            String value = text.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
            Obj object = from(self);
            UiaFrame.Item item = object == null ? null : object.item();
            if (item != null && item.readOnly()) return Uia.UIA_E_NOTSUPPORTED;
            return act(self, element -> Actions.setText(element, value));
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int getValue(MemorySegment self, MemorySegment result) {
        try {
            Obj object = from(self);
            UiaFrame.Item item = object == null ? null : object.item();
            if (item == null) return Uia.UIA_E_ELEMENTNOTAVAILABLE;
            String value = item.password() || item.value() == null ? "" : item.value();
            out(result, Uia.bstr(value));
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int valueIsReadOnly(MemorySegment self, MemorySegment result) {
        try {
            Obj object = from(self);
            UiaFrame.Item item = object == null ? null : object.item();
            if (item == null) return Uia.UIA_E_ELEMENTNOTAVAILABLE;
            result.reinterpret(4).set(JAVA_INT, 0, item.readOnly() ? -1 : 0);
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    /** Whether a screen reader may set this range, as against one it can only read (a progress bar). */
    private static boolean settableRange(me.piitex.engine.ui.Element element) {
        return element instanceof me.piitex.engine.ui.overlays.SliderOverlay
                || element instanceof me.piitex.engine.ui.overlays.KnobOverlay;
    }

    private static int setRange(MemorySegment self, double value) {
        try {
            Obj object = from(self);
            UiaFrame.Item item = object == null ? null : object.item();
            if (item != null && !settableRange(item.element())) return Uia.UIA_E_NOTSUPPORTED;
            return act(self, element -> Actions.setNumber(element, value));
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int range(MemorySegment self, MemorySegment result, int what) {
        try {
            Obj object = from(self);
            UiaFrame.Item item = object == null ? null : object.item();
            if (item == null) return Uia.UIA_E_ELEMENTNOTAVAILABLE;
            double step = item.step() > 0 ? item.step() : Math.max((item.max() - item.min()) / 100, 1e-9);
            double value = switch (what) {
                case 0 -> item.current();
                case 1 -> item.max();
                case 2 -> item.min();
                case 3 -> Math.max(step, (item.max() - item.min()) / 10); // a page
                default -> step;
            };
            result.reinterpret(8).set(JAVA_DOUBLE, 0, value);
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int rangeValue(MemorySegment self, MemorySegment result) {
        return range(self, result, 0);
    }

    private static int rangeMaximum(MemorySegment self, MemorySegment result) {
        return range(self, result, 1);
    }

    private static int rangeMinimum(MemorySegment self, MemorySegment result) {
        return range(self, result, 2);
    }

    private static int rangeLargeChange(MemorySegment self, MemorySegment result) {
        return range(self, result, 3);
    }

    private static int rangeSmallChange(MemorySegment self, MemorySegment result) {
        return range(self, result, 4);
    }

    private static int rangeIsReadOnly(MemorySegment self, MemorySegment result) {
        try {
            Obj object = from(self);
            UiaFrame.Item item = object == null ? null : object.item();
            if (item == null) return Uia.UIA_E_ELEMENTNOTAVAILABLE;
            result.reinterpret(4).set(JAVA_INT, 0, settableRange(item.element()) ? 0 : -1);
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int select(MemorySegment self) {
        try {
            Obj object = from(self);
            UiaFrame.Item item = object == null ? null : object.item();
            if (item != null && Boolean.TRUE.equals(item.checked())) return Uia.S_OK; // already the chosen one
            return act(self, Actions::press);
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int addToSelection(MemorySegment self) {
        return select(self);
    }

    private static int removeFromSelection(MemorySegment self) {
        return Uia.UIA_E_NOTSUPPORTED;
    }

    private static int isSelected(MemorySegment self, MemorySegment result) {
        try {
            Obj object = from(self);
            UiaFrame.Item item = object == null ? null : object.item();
            if (item == null) return Uia.UIA_E_ELEMENTNOTAVAILABLE;
            result.reinterpret(4).set(JAVA_INT, 0, Boolean.TRUE.equals(item.checked()) ? -1 : 0);
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static int selectionContainer(MemorySegment self, MemorySegment result) {
        try {
            out(result, MemorySegment.NULL);
            return Uia.S_OK;
        } catch (Throwable t) {
            return fail(t);
        }
    }

    // ---- for the host ------------------------------------------------------------------------------------------

    /** Calls Release on a COM pointer that someone else gave us. */
    static void releasePointer(MemorySegment pointer) {
        if (pointer == null || pointer.equals(MemorySegment.NULL)) return;
        try {
            MemorySegment table = pointer.reinterpret(8).get(ADDRESS, 0).reinterpret(24);
            MemorySegment function = table.get(ADDRESS, 16);
            int ignored = (int) CALL_1.invoke(function, pointer);
        } catch (Throwable t) {
            log.debug("Release failed", t);
        }
    }
}
