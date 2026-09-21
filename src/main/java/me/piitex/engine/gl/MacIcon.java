package me.piitex.engine.gl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.macosx.ObjCRuntime;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.JNI.invokePPPP;
import static org.lwjgl.system.JNI.invokePPPPP;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_getUid;

import java.nio.ByteBuffer;

/**
 * Sets the application icon on macOS. GLFW has no window icon there because macOS windows don't have one:
 * the icon shown in the Dock and the app switcher belongs to the application, so it is set on
 * {@code NSApplication}. Same Objective-C runtime approach as {@code MetalContext}.
 * <p>
 * MAIN THREAD ONLY (AppKit).
 */
final class MacIcon {
    private static final Logger log = LogManager.getLogger("Engine");
    private static final long objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

    private MacIcon() {
    }

    /** {@code encoded} is an image file's bytes (PNG, JPEG, ...); AppKit does the decoding. */
    static void set(byte[] encoded) {
        ByteBuffer buffer = memAlloc(encoded.length).put(encoded).flip();
        try {
            long pool = invokePPP(invokePPP(objc_getClass("NSAutoreleasePool"), sel_getUid("alloc"), objc_msgSend),
                    sel_getUid("init"), objc_msgSend);
            try {
                long data = invokePPPPP(objc_getClass("NSData"), sel_getUid("dataWithBytes:length:"),
                        memAddress(buffer), encoded.length, objc_msgSend); // copies the bytes, autoreleased
                long image = invokePPPP(invokePPP(objc_getClass("NSImage"), sel_getUid("alloc"), objc_msgSend),
                        sel_getUid("initWithData:"), data, objc_msgSend);
                if (image == 0L) {
                    log.warn("macOS could not decode the application icon.");
                    return;
                }
                long app = invokePPP(objc_getClass("NSApplication"), sel_getUid("sharedApplication"), objc_msgSend);
                invokePPPP(app, sel_getUid("setApplicationIconImage:"), image, objc_msgSend); // NSApp retains it
                invokePPP(image, sel_getUid("release"), objc_msgSend);
            } finally {
                invokePPP(pool, sel_getUid("release"), objc_msgSend);
            }
        } finally {
            memFree(buffer);
        }
    }
}
