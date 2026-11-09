package me.piitex.engine.webview.macos;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.*;

/**
 * The Objective-C class WKWebView calls back into: {@code RenGLWebViewDelegate}, an {@code NSObject} subclass defined at
 * run time that serves as the navigation delegate, the UI delegate and the script message handler. Each method is a static
 * Java method behind an upcall. The delegate object finds its backend by its own address.
 * <p>
 * An exception that escapes an upcall ends the process, so every method catches everything, and a policy handler is
 * always called (denying) even when the work before it failed.
 */
final class WkDelegate {
    private static final Logger log = LogManager.getLogger("WebView");

    private static final ConcurrentHashMap<Long, WKWebViewBackend> BACKENDS = new ConcurrentHashMap<>();
    private static MemorySegment delegateClass;

    private WkDelegate() {
    }

    /** A new delegate object whose callbacks go to {@code backend}. */
    static synchronized MemorySegment create(WKWebViewBackend backend) {
        if (delegateClass == null) delegateClass = define();
        MemorySegment instance = ObjC.id(ObjC.id(delegateClass, "alloc"), "init");
        BACKENDS.put(instance.address(), backend);
        return instance;
    }

    static void forget(MemorySegment instance) {
        if (instance != null) BACKENDS.remove(instance.address());
    }

    private static MemorySegment define() {
        return ObjC.defineClass("RenGLWebViewDelegate", "NSObject",
                List.of("WKNavigationDelegate", "WKUIDelegate", "WKScriptMessageHandler"), imps());
    }

    /** The methods of the delegate class: selector, type encoding and the Java method behind each. */
    static List<ObjC.Imp> imps() {
        FunctionDescriptor v4 = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
        FunctionDescriptor v5 = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
        return List.of(
                        ObjC.imp("webView:decidePolicyForNavigationAction:decisionHandler:", "v@:@@@?", WkDelegate.class,
                                "decidePolicy", v5),
                        ObjC.imp("webView:didFinishNavigation:", "v@:@@", WkDelegate.class, "didFinish", v4),
                        ObjC.imp("webView:didFailNavigation:withError:", "v@:@@@", WkDelegate.class, "didFail", v5),
                        ObjC.imp("webView:didFailProvisionalNavigation:withError:", "v@:@@@", WkDelegate.class, "didFail", v5),
                        ObjC.imp("webViewWebContentProcessDidTerminate:", "v@:@", WkDelegate.class, "processDied",
                                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)),
                        ObjC.imp("webView:createWebViewWithConfiguration:forNavigationAction:windowFeatures:", "@@:@@@@",
                                WkDelegate.class, "createWebView",
                                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
                        ObjC.imp("webView:requestMediaCapturePermissionForOrigin:initiatedByFrame:type:decisionHandler:", "v@:@@@q@?",
                                WkDelegate.class, "mediaCapture",
                                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS)),
                        ObjC.imp("userContentController:didReceiveScriptMessage:", "v@:@@", WkDelegate.class, "scriptMessage", v4));
    }

    private static void guarded(MemorySegment self, Consumer<WKWebViewBackend> action) {
        try {
            WKWebViewBackend backend = BACKENDS.get(self.address());
            if (backend != null) action.accept(backend);
        } catch (Throwable t) {
            log.warn("A web view callback failed: {}", t.toString());
        }
    }

    // ------------------------------------------------------------------------------------------------ the callbacks

    /** {@code -webView:decidePolicyForNavigationAction:decisionHandler:}; the handler takes 1 to allow and 0 to cancel. */
    static void decidePolicy(MemorySegment self, MemorySegment cmd, MemorySegment webView, MemorySegment action, MemorySegment handler) {
        long policy = 0;
        try {
            WKWebViewBackend backend = BACKENDS.get(self.address());
            if (backend != null) policy = backend.decideNavigation(action) ? 1 : 0;
        } catch (Throwable t) {
            log.warn("A web view navigation check failed: {}", t.toString());
            policy = 0;
        }
        try {
            ObjC.callBlock(handler, policy);
        } catch (Throwable t) {
            log.warn("Could not answer a navigation decision: {}", t.toString());
        }
    }

    static void didFinish(MemorySegment self, MemorySegment cmd, MemorySegment webView, MemorySegment navigation) {
        guarded(self, b -> b.navigationDone(true, 0));
    }

    static void didFail(MemorySegment self, MemorySegment cmd, MemorySegment webView, MemorySegment navigation, MemorySegment error) {
        guarded(self, b -> b.navigationDone(false, ObjC.isNull(error) ? -1 : (int) ObjC.lng(error, "code")));
    }

    static void processDied(MemorySegment self, MemorySegment cmd, MemorySegment webView) {
        guarded(self, b -> b.processDied());
    }

    /** {@code window.open}: no window is made. */
    static MemorySegment createWebView(MemorySegment self, MemorySegment cmd, MemorySegment webView, MemorySegment configuration,
                                       MemorySegment action, MemorySegment features) {
        guarded(self, b -> b.windowOpenRequested(action));
        return MemorySegment.NULL;
    }

    /** Camera and microphone: refused unless the options allow them. {@code type}: 0 camera, 1 microphone, 2 both. */
    static void mediaCapture(MemorySegment self, MemorySegment cmd, MemorySegment webView, MemorySegment origin, MemorySegment frame,
                             long type, MemorySegment handler) {
        long decision = 2; // WKPermissionDecisionDeny
        try {
            WKWebViewBackend backend = BACKENDS.get(self.address());
            if (backend != null && backend.mediaAllowed(type)) decision = 1;
        } catch (Throwable t) {
            decision = 2;
        }
        try {
            ObjC.callBlock(handler, decision);
        } catch (Throwable t) {
            log.warn("Could not answer a permission request: {}", t.toString());
        }
    }

    static void scriptMessage(MemorySegment self, MemorySegment cmd, MemorySegment controller, MemorySegment message) {
        guarded(self, b -> b.scriptMessage(message));
    }
}
