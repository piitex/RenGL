package me.piitex.engine.webview.macos;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Objective-C classes are built at run time on a Mac, where a method whose Java signature does not match its
 * descriptor would only fail then. The foreign-function API checks that when the upcall stub is made, and that works on
 * any system, so it is checked here.
 */
class UpcallsTest {
    private static List<ObjC.Imp> all() {
        List<ObjC.Imp> all = new ArrayList<>(WkDelegate.imps());
        all.addAll(WKWebViewBackend.windowImps());
        return all;
    }

    @Test
    void everyMethodOfTheRuntimeClassesMakesAnUpcallStub() {
        for (ObjC.Imp m : all()) {
            assertNotNull(Linker.nativeLinker().upcallStub(m.target(), m.descriptor(), Arena.ofAuto()), m.selector());
        }
    }

    @Test
    void theArgumentCountsMatchTheSelectorAndTheTypeEncoding() {
        for (ObjC.Imp m : all()) {
            long colons = m.selector().chars().filter(c -> c == ':').count();
            assertEquals(2 + colons, m.descriptor().argumentLayouts().size(), "arguments of " + m.selector() + " (self, _cmd, then one per colon)");
            // a type encoding is the return type, then self, _cmd and one type per colon; a block is the two characters @?
            String types = m.encoding().replace("@?", "B");
            assertEquals(3 + colons, types.length(), "type encoding of " + m.selector() + ": " + m.encoding());
            assertEquals(m.descriptor().returnLayout().isEmpty(), types.charAt(0) == 'v', "return type of " + m.selector());
        }
    }

    @Test
    void theSelectorsAreUnique() {
        List<String> selectors = all().stream().map(ObjC.Imp::selector).toList();
        assertEquals(selectors.size(), selectors.stream().distinct().count());
    }

    @Test
    void theDelegateAnswersTheCallbacksWebKitMakes() {
        List<String> selectors = all().stream().map(ObjC.Imp::selector).toList();
        for (String needed : new String[]{
                "webView:decidePolicyForNavigationAction:decisionHandler:",
                "webView:didFinishNavigation:",
                "webView:didFailNavigation:withError:",
                "webView:didFailProvisionalNavigation:withError:",
                "webView:createWebViewWithConfiguration:forNavigationAction:windowFeatures:",
                "webView:requestMediaCapturePermissionForOrigin:initiatedByFrame:type:decisionHandler:",
                "userContentController:didReceiveScriptMessage:",
                "isKeyWindow"}) {
            assertTrue(selectors.contains(needed), needed);
        }
    }

    @Test
    void theMainThreadAndBlockFunctionsMakeStubs() {
        assertNotNull(ObjC.drainStub());
        assertNotNull(ObjC.blockStub());
    }
}
