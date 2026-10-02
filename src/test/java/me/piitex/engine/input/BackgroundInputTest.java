package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.render3d.Background3D;
import me.piitex.engine.render3d.Viewport3D;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the game input hands a {@link Background3D}'s listener, driven by feeding the window's {@link Input} directly.
 */
class BackgroundInputTest {
    @Test
    void typedTextIsOnlyThereForTheFrameItWasTypedIn() {
        Input input = new Window(new WindowOptions("test").setDimensions(200, 200)).getInput();
        input.beginFrame();
        assertEquals("", input.getTypedText());
        input.charTyped('h');
        input.charTyped(0x1F600); // outside the basic plane: two chars, one code point
        assertEquals("h😀", input.getTypedText());
        input.beginFrame();
        assertEquals("", input.getTypedText());
    }

    @Test
    void theListenerSeesTypedTextExitsAndFocus() throws Exception {
        Window window = new Window(new WindowOptions("test").setDimensions(200, 200));
        Input input = window.getInput();
        List<String> events = new ArrayList<>();
        Background3D background = new Background3D(window, null);
        background.setInputListener(new Viewport3D.InputListener() {
            @Override
            public void pointerMoved(double x, double y) {
                events.add("moved");
            }

            @Override
            public void pointerExited() {
                events.add("exited");
            }

            @Override
            public void charTyped(int codepoint) {
                events.add("char " + (char) codepoint);
            }

            @Override
            public void focusChanged(boolean focused) {
                events.add("focus " + focused);
            }
        });

        background.update(0.016f, 0, 0, 200, 200);
        assertEquals(List.of(), events, "an unfocused window is not focused");

        Method notifyFocused = Window.class.getDeclaredMethod("notifyFocused", boolean.class);
        notifyFocused.setAccessible(true);
        notifyFocused.invoke(window, true);
        input.beginFrame();
        input.mouseEntered(true);
        input.mouseMoved(10, 10, true);
        input.mouseMoved(20, 20, true);
        input.charTyped('a');
        input.charTyped('b');
        background.update(0.016f, 0, 0, 200, 200);
        assertEquals(List.of("focus true", "moved", "char a", "char b"), events);

        events.clear();
        input.beginFrame();
        input.mouseEntered(false);
        background.update(0.016f, 0, 0, 200, 200);
        assertEquals(List.of("exited"), events);

        events.clear();
        input.beginFrame();
        background.update(0.016f, 0, 0, 200, 200);
        assertEquals(List.of(), events, "nothing more is sent once it has left");

        notifyFocused.invoke(window, false);
        input.beginFrame();
        background.update(0.016f, 0, 0, 200, 200);
        assertEquals(List.of("focus false"), events);
    }
}
