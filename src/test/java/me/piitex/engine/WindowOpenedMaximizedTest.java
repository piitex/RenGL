package me.piitex.engine;

import me.piitex.engine.ui.containers.ResizableContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WindowOpenedMaximizedTest {
    @Test
    void aContainerBuiltForTheRequestedSizeScalesToAWindowThatOpenedLarger() {
        WindowOptions options = new WindowOptions("test").setDimensions(800, 500);
        Window window = new Window(options);
        ResizableContainer panel = new ResizableContainer(0, 0, 800, 500);
        window.addContainer(panel);

        options.markOpened();          // what the engine does as the window opens...
        options.syncDimensions(1600, 1000); // ...maximized, so the OS gave it more than was asked for

        assertEquals(800, options.getDesignWidth());
        assertEquals(500, options.getDesignHeight());

        panel.update(0f);

        assertEquals(1600, panel.getWidth(), 0.001);
        assertEquals(1000, panel.getHeight(), 0.001);
    }

    @Test
    void theDesignSizeIsTheFirstOpenOnly() {
        WindowOptions options = new WindowOptions("test").setDimensions(800, 500);
        options.markOpened();
        options.syncDimensions(1600, 1000);
        options.markOpened(); // reopened for a renderer swap

        assertEquals(800, options.getDesignWidth());
    }
}
