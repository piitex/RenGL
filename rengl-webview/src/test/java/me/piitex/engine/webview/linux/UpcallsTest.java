package me.piitex.engine.webview.linux;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The GTK callbacks are Java methods behind C function pointers, made when the first view starts. The foreign-function API
 * checks that each method matches its descriptor when the pointer is made, and that works on any system, so it is checked
 * here rather than on the first Linux run.
 */
class UpcallsTest {
    @Test
    void everyCallbackMakesAnUpcallStub() throws Exception {
        int count = 0;
        for (Field f : WebKitGtkBackend.Stubs.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != MemorySegment.class) continue;
            f.setAccessible(true);
            MemorySegment stub = (MemorySegment) f.get(null); // the first read runs the class's initialiser, which makes them all
            assertNotNull(stub, f.getName());
            assertNotEquals(0, stub.address(), f.getName());
            count++;
        }
        assertEquals(10, count, "the callbacks of the view");
    }

    @Test
    void theLoopsIdleAndTimerFunctionsMakeStubs() {
        GtkLoop.createStubs();
        GtkLoop.createStubs(); // once is enough
    }

    @Test
    void theBackendReportsWhyItIsNotThereOffLinux() {
        if (System.getProperty("os.name").toLowerCase().contains("linux")) return;
        assertNotNull(WebKitGtkBackend.unavailableReason());
        assertNull(WebKitGtkBackend.version());
    }
}
