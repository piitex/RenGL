package me.piitex.engine.webview.internal;

import me.piitex.engine.ui.CursorMode;

import java.util.Locale;

/**
 * Turns the engine's mouse, keyboard and input-method events into Chrome DevTools protocol calls ({@code Input.*}).
 * Every Chromium-based backend uses it (WebView2 on Windows, a system Chrome, Edge or Chromium anywhere), so a page
 * sees the same trusted events whichever one it runs in. Not thread-safe: the backend calls it from its own thread.
 */
public final class CdpInput {
    /** Where a call goes: the backend's way of running one DevTools method. */
    @FunctionalInterface
    public interface Sender {
        void send(String method, String paramsJson);
    }

    private final Sender sender;
    private int heldButtons;
    private long lastPressAt;
    private int lastPressButton = -1, clickCount;
    private double lastPressX, lastPressY;

    public CdpInput(Sender sender) {
        this.sender = sender;
    }

    // ------------------------------------------------------------------------------------------------ mouse

    private static String buttonName(int glfwButton) {
        return switch (glfwButton) {
            case 0 -> "left";
            case 1 -> "right";
            case 2 -> "middle";
            default -> "none";
        };
    }

    public void pointerMove(double x, double y, int buttons, int mods) {
        heldButtons = buttons;
        String button = (buttons & 1) != 0 ? "left" : (buttons & 2) != 0 ? "right" : (buttons & 4) != 0 ? "middle" : "none";
        sender.send("Input.dispatchMouseEvent", String.format(Locale.ROOT,
                "{\"type\":\"mouseMoved\",\"x\":%.1f,\"y\":%.1f,\"button\":\"%s\",\"buttons\":%d,\"modifiers\":%d}",
                x, y, button, buttons, Keys.modifiers(mods)));
    }

    public void pointerButton(double x, double y, int button, boolean pressed, int mods) {
        long now = System.nanoTime();
        int bit = button >= 0 && button < 8 ? 1 << button : 0;
        if (pressed) {
            boolean again = button == lastPressButton && now - lastPressAt < 500_000_000L
                    && Math.abs(x - lastPressX) < 5 && Math.abs(y - lastPressY) < 5;
            clickCount = again ? clickCount + 1 : 1;
            lastPressAt = now;
            lastPressButton = button;
            lastPressX = x;
            lastPressY = y;
            heldButtons |= bit;
        } else {
            heldButtons &= ~bit;
        }
        sender.send("Input.dispatchMouseEvent", String.format(Locale.ROOT,
                "{\"type\":\"%s\",\"x\":%.1f,\"y\":%.1f,\"button\":\"%s\",\"buttons\":%d,\"clickCount\":%d,\"modifiers\":%d}",
                pressed ? "mousePressed" : "mouseReleased", x, y, buttonName(button), heldButtons, clickCount, Keys.modifiers(mods)));
    }

    public void pointerScroll(double x, double y, double dx, double dy, int mods) {
        sender.send("Input.dispatchMouseEvent", String.format(Locale.ROOT,
                "{\"type\":\"mouseWheel\",\"x\":%.1f,\"y\":%.1f,\"deltaX\":%.1f,\"deltaY\":%.1f,\"modifiers\":%d}",
                x, y, dx * 100.0, -dy * 100.0, Keys.modifiers(mods)));
    }

    public void pointerLeave() {
        sender.send("Input.dispatchMouseEvent", "{\"type\":\"mouseMoved\",\"x\":-1,\"y\":-1,\"button\":\"none\",\"buttons\":0}");
    }

    // ------------------------------------------------------------------------------------------------ keyboard

    /** A key (a GLFW key code, with GLFW's action: 0 release, 1 press, 2 repeat). */
    public void key(int glfwKey, int action, int mods) {
        int vk = Keys.virtualKey(glfwKey);
        if (vk == 0) return;
        String name = quote(Keys.keyName(glfwKey)), code = quote(Keys.codeName(glfwKey));
        int m = Keys.modifiers(mods);
        if (action == 0) {
            sender.send("Input.dispatchKeyEvent", String.format(Locale.ROOT,
                    "{\"type\":\"keyUp\",\"modifiers\":%d,\"windowsVirtualKeyCode\":%d,\"key\":%s,\"code\":%s}", m, vk, name, code));
            return;
        }
        String command = Keys.command(glfwKey, mods);
        sender.send("Input.dispatchKeyEvent", String.format(Locale.ROOT,
                "{\"type\":\"rawKeyDown\",\"modifiers\":%d,\"windowsVirtualKeyCode\":%d,\"key\":%s,\"code\":%s%s%s}",
                m, vk, name, code, action == 2 ? ",\"autoRepeat\":true" : "",
                command == null ? "" : ",\"commands\":[\"" + command + "\"]"));
        if (vk == 0x0D) { // Enter has no typed character of its own, but a form needs the line break
            sender.send("Input.dispatchKeyEvent", "{\"type\":\"char\",\"text\":\"\\r\",\"unmodifiedText\":\"\\r\",\"windowsVirtualKeyCode\":13}");
        }
    }

    /** A typed character (a code point). */
    public void text(int codePoint) {
        if (codePoint < 0x20 || codePoint == 0x7F) return;
        String s = new String(Character.toChars(codePoint));
        sender.send("Input.dispatchKeyEvent", "{\"type\":\"char\",\"text\":" + quote(s) + ",\"unmodifiedText\":" + quote(s) + "}");
    }

    /** The input method's unfinished text; empty clears it. */
    public void composition(String text, int caret) {
        int c = Math.max(0, Math.min(caret, text.length()));
        sender.send("Input.imeSetComposition", "{\"text\":" + quote(text) + ",\"selectionStart\":" + c + ",\"selectionEnd\":" + c + "}");
    }

    // ------------------------------------------------------------------------------------------------ the page's cursor

    /** What the page wants under the pointer, and whether a text field has its focus. */
    public record Probe(CursorMode cursor, boolean editing) {
    }

    private static final String CURSOR_SCRIPT = """
            (function(x, y) {
              var e = document.elementFromPoint(x, y), c = 'default';
              if (e) {
                c = getComputedStyle(e).cursor;
                if (c === 'auto') {
                  if (e.closest('a[href],button,summary,[role=button],select,label[for]')) c = 'pointer';
                  else if (e.closest('input:not([type=button]):not([type=submit]):not([type=checkbox]):not([type=radio]):not([type=range]),textarea,[contenteditable=""],[contenteditable="true"]')) c = 'text';
                  else c = 'default';
                }
              }
              var a = document.activeElement;
              var editing = !!a && (a.isContentEditable || /^(TEXTAREA|SELECT)$/.test(a.tagName)
                  || (a.tagName === 'INPUT' && !/^(button|submit|checkbox|radio|range|reset|image|file|color)$/i.test(a.type)));
              return c + '|' + (editing ? 1 : 0);
            })(%d, %d)""";

    /** A script that reports the cursor under a point and the text-field state; read its result with {@link #parseProbe}. */
    public static String probeScript(double x, double y) {
        return String.format(Locale.ROOT, CURSOR_SCRIPT, (int) x, (int) y);
    }

    /** Reads the JSON a {@link #probeScript} returned, or null when it is not one. */
    public static Probe parseProbe(String json) {
        if (json == null) return null;
        String s = json.length() >= 2 && json.startsWith("\"") ? json.substring(1, json.length() - 1) : json;
        int bar = s.lastIndexOf('|');
        if (bar < 0) return null;
        return new Probe(cursorFor(s.substring(0, bar)), s.endsWith("|1"));
    }

    /** The engine's cursor for a CSS cursor name, or null for the default arrow. */
    public static CursorMode cursorFor(String css) {
        return switch (css) {
            case "pointer", "grab", "grabbing", "alias", "copy", "context-menu", "help" -> CursorMode.POINTER;
            case "text", "vertical-text" -> CursorMode.TEXT;
            case "crosshair", "cell" -> CursorMode.CROSSHAIR;
            case "ew-resize", "col-resize", "e-resize", "w-resize" -> CursorMode.RESIZE_HORIZONTAL;
            case "ns-resize", "row-resize", "n-resize", "s-resize" -> CursorMode.RESIZE_VERTICAL;
            case "nwse-resize", "nw-resize", "se-resize" -> CursorMode.RESIZE_NWSE;
            case "nesw-resize", "ne-resize", "sw-resize" -> CursorMode.RESIZE_NESW;
            case "move", "all-scroll" -> CursorMode.RESIZE_ALL;
            case "not-allowed", "no-drop" -> CursorMode.NOT_ALLOWED;
            default -> null;
        };
    }

    /** A string as a JSON literal. */
    public static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
