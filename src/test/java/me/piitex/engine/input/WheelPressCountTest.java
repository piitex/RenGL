package me.piitex.engine.input;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WheelPressCountTest {
    private static Input inputWith(InputContext context) {
        Input input = new Window(new WindowOptions("test").setDimensions(200, 200)).getInput();
        input.addContext(context);
        return input;
    }

    @Test
    void everyNotchInAFrameIsAPress() {
        InputContext controls = new InputContext("test").bind("zoom", Binding.wheel(Binding.WHEEL_UP));
        Input input = inputWith(controls);
        input.beginFrame();
        assertEquals(0, controls.getPressCount("zoom"));
        input.scrolled(0, 3);
        assertTrue(controls.isJustPressed("zoom"));
        assertEquals(3, controls.getPressCount("zoom"));
        input.beginFrame();
        assertFalse(controls.isJustPressed("zoom"));
        assertEquals(0, controls.getPressCount("zoom"));
        input.scrolled(0, 1);
        assertEquals(1, controls.getPressCount("zoom"));
    }
}
