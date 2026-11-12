package me.piitex.engine.access.uia;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The Win32 calls and constants the UI Automation provider needs, and the small data types it has to build for them:
 * BSTR strings, VARIANTs and SAFEARRAYs. Windows only; loading the class anywhere else fails, which
 * {@link UiaHost#isSupported()} checks for first.
 */
final class Uia {
    private Uia() {
    }

    // HRESULTs
    static final int S_OK = 0;
    static final int E_NOINTERFACE = 0x80004002, E_FAIL = 0x80004005, E_INVALIDARG = 0x80070057;
    static final int UIA_E_ELEMENTNOTENABLED = 0x80040200, UIA_E_ELEMENTNOTAVAILABLE = 0x80040201, UIA_E_NOTSUPPORTED = 0x80040204;

    // Messages and window words
    static final int WM_GETOBJECT = 0x003D, GWLP_WNDPROC = -4;
    static final int UIA_ROOT_OBJECT_ID = -25;
    static final int SPI_GETSCREENREADER = 0x0046;

    // VARIANT types
    static final short VT_EMPTY = 0, VT_I4 = 3, VT_R8 = 5, VT_BSTR = 8, VT_BOOL = 11;

    // Events and properties
    static final int EVENT_FOCUS_CHANGED = 20005;
    static final int PROP_RUNTIME_ID = 30000, PROP_CONTROL_TYPE = 30003, PROP_LOCALIZED_CONTROL_TYPE = 30004, PROP_NAME = 30005,
            PROP_HAS_KEYBOARD_FOCUS = 30008, PROP_IS_KEYBOARD_FOCUSABLE = 30009, PROP_IS_ENABLED = 30010, PROP_AUTOMATION_ID = 30011,
            PROP_CLASS_NAME = 30012, PROP_HELP_TEXT = 30013, PROP_IS_CONTROL_ELEMENT = 30016, PROP_IS_CONTENT_ELEMENT = 30017,
            PROP_IS_PASSWORD = 30019, PROP_IS_OFFSCREEN = 30022, PROP_FRAMEWORK_ID = 30024, PROP_FULL_DESCRIPTION = 30159;
    static final int PROP_VALUE_VALUE = 30045, PROP_RANGE_VALUE = 30047, PROP_SELECTION_ITEM_IS_SELECTED = 30079, PROP_TOGGLE_STATE = 30086;

    // Patterns
    static final int PATTERN_INVOKE = 10000, PATTERN_VALUE = 10002, PATTERN_RANGE_VALUE = 10003, PATTERN_SELECTION_ITEM = 10010,
            PATTERN_TOGGLE = 10015;

    static final int UIA_APPEND_RUNTIME_ID = 0x2A;

    /** ProviderOptions_ServerSideProvider. (0x1 is ClientSideProvider, which Windows does not send events across processes for.) */
    static final int PROVIDER_SERVER_SIDE = 0x2;

    /** A VARIANT on x64 is 24 bytes: its type, three reserved words, then a 16 byte union. */
    static final MemoryLayout VARIANT = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG, JAVA_LONG);
    static final long VARIANT_SIZE = 24;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena GLOBAL = Arena.global();
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", GLOBAL);
    private static final SymbolLookup UIA = SymbolLookup.libraryLookup("uiautomationcore", GLOBAL);
    private static final SymbolLookup OLEAUT32 = SymbolLookup.libraryLookup("oleaut32", GLOBAL);

    private static MethodHandle down(SymbolLookup library, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(library.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), descriptor);
    }

    static final MethodHandle SET_WINDOW_LONG_PTR = down(USER32, "SetWindowLongPtrW", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG));
    static final MethodHandle GET_WINDOW_LONG_PTR = down(USER32, "GetWindowLongPtrW", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT));
    static final MethodHandle CALL_WINDOW_PROC = down(USER32, "CallWindowProcW", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG));
    static final MethodHandle GET_CLIENT_RECT = down(USER32, "GetClientRect", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
    static final MethodHandle CLIENT_TO_SCREEN = down(USER32, "ClientToScreen", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
    static final MethodHandle SCREEN_TO_CLIENT = down(USER32, "ScreenToClient", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
    static final MethodHandle IS_WINDOW = down(USER32, "IsWindow", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    static final MethodHandle SYSTEM_PARAMETERS_INFO = down(USER32, "SystemParametersInfoW", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));

    static final MethodHandle RETURN_RAW_ELEMENT_PROVIDER = down(UIA, "UiaReturnRawElementProvider", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS));
    static final MethodHandle HOST_PROVIDER_FROM_HWND = down(UIA, "UiaHostProviderFromHwnd", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
    static final MethodHandle RAISE_EVENT = down(UIA, "UiaRaiseAutomationEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    static final MethodHandle RAISE_PROPERTY_CHANGED = down(UIA, "UiaRaiseAutomationPropertyChangedEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, VARIANT, VARIANT));
    static final MethodHandle RAISE_NOTIFICATION = down(UIA, "UiaRaiseNotificationEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle CLIENTS_ARE_LISTENING = down(UIA, "UiaClientsAreListening", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle DISCONNECT_PROVIDER = down(UIA, "UiaDisconnectProvider", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final MethodHandle SYS_ALLOC_STRING = down(OLEAUT32, "SysAllocString", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle SYS_FREE_STRING = down(OLEAUT32, "SysFreeString", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle SAFE_ARRAY_CREATE_VECTOR = down(OLEAUT32, "SafeArrayCreateVector", FunctionDescriptor.of(ADDRESS, JAVA_SHORT, JAVA_INT, JAVA_INT));
    private static final MethodHandle SAFE_ARRAY_ACCESS_DATA = down(OLEAUT32, "SafeArrayAccessData", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SAFE_ARRAY_UNACCESS_DATA = down(OLEAUT32, "SafeArrayUnaccessData", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    // ---- strings, variants, arrays ------------------------------------------------------------------------------

    /** A new BSTR holding {@code text}. The receiver of it frees it. */
    static MemorySegment bstr(String text) throws Throwable {
        try (Arena temp = Arena.ofConfined()) {
            return (MemorySegment) SYS_ALLOC_STRING.invoke(temp.allocateFrom(text, StandardCharsets.UTF_16LE));
        }
    }

    static void freeBstr(MemorySegment bstr) throws Throwable {
        if (bstr != null && !bstr.equals(MemorySegment.NULL)) SYS_FREE_STRING.invoke(bstr);
    }

    /** Clears a VARIANT to VT_EMPTY. */
    static void variantEmpty(MemorySegment variant) {
        variant.reinterpret(VARIANT_SIZE).fill((byte) 0);
    }

    static void variantInt(MemorySegment variant, int value) {
        MemorySegment v = variant.reinterpret(VARIANT_SIZE);
        v.fill((byte) 0);
        v.set(JAVA_SHORT, 0, VT_I4);
        v.set(JAVA_INT, 8, value);
    }

    static void variantBool(MemorySegment variant, boolean value) {
        MemorySegment v = variant.reinterpret(VARIANT_SIZE);
        v.fill((byte) 0);
        v.set(JAVA_SHORT, 0, VT_BOOL);
        v.set(JAVA_SHORT, 8, (short) (value ? -1 : 0));
    }

    static void variantDouble(MemorySegment variant, double value) {
        MemorySegment v = variant.reinterpret(VARIANT_SIZE);
        v.fill((byte) 0);
        v.set(JAVA_SHORT, 0, VT_R8);
        v.set(JAVA_DOUBLE, 8, value);
    }

    /** The caller owns the BSTR inside, as the receiver of any VARIANT does. */
    static void variantString(MemorySegment variant, String value) throws Throwable {
        MemorySegment v = variant.reinterpret(VARIANT_SIZE);
        v.fill((byte) 0);
        v.set(JAVA_SHORT, 0, VT_BSTR);
        v.set(ADDRESS, 8, bstr(value == null ? "" : value));
    }

    /** A SAFEARRAY of 32-bit integers, such as a runtime id. */
    static MemorySegment intArray(int... values) throws Throwable {
        MemorySegment array = (MemorySegment) SAFE_ARRAY_CREATE_VECTOR.invoke(VT_I4, 0, values.length);
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) SAFE_ARRAY_ACCESS_DATA.invoke(array, out);
            if (hr < 0) throw new IllegalStateException("SafeArrayAccessData 0x" + Integer.toHexString(hr));
            MemorySegment data = out.get(ADDRESS, 0).reinterpret(4L * values.length);
            for (int i = 0; i < values.length; i++) data.setAtIndex(JAVA_INT, i, values[i]);
            SAFE_ARRAY_UNACCESS_DATA.invoke(array);
        }
        return array;
    }

    /** Whether a screen reader says it is running (NVDA, JAWS and Narrator all set this). */
    static boolean screenReaderRunning() {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment flag = temp.allocate(JAVA_INT);
            int ok = (int) SYSTEM_PARAMETERS_INFO.invoke(SPI_GETSCREENREADER, 0, flag, 0);
            return ok != 0 && flag.get(JAVA_INT, 0) != 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
