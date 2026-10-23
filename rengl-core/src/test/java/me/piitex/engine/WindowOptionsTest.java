package me.piitex.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WindowOptionsTest {
    @Test
    void widthAndHeightChangeOneSideAtATime() {
        WindowOptions options = new WindowOptions("test").setDimensions(800, 600);
        options.setWidth(1280);
        assertEquals(1280, options.getWidth());
        assertEquals(600, options.getHeight());
        options.setHeight(720);
        assertEquals(1280, options.getWidth());
        assertEquals(720, options.getHeight());
    }

    @Test
    void windowSettersAndOptionSettersShareOneState() {
        Window window = new Window(new WindowOptions("test").setDimensions(100, 100));
        window.getWindowOptions().setWidth(300);
        window.setTitle("renamed");
        assertEquals(300, window.getWindowOptions().getWidth());
        assertEquals("renamed", window.getWindowOptions().getTitle());
        window.setSize(640, 480);
        assertEquals(640, window.getWindowOptions().getWidth());
        assertEquals(480, window.getWindowOptions().getHeight());
    }

    @Test
    void oneOptionsObjectDrivesOneWindow() {
        WindowOptions options = new WindowOptions("test");
        new Window(options);
        assertThrows(IllegalStateException.class, () -> new Window(options));
    }

    @Test
    void rendererIsStoredWhenNoEngineIsRunning() {
        Window window = new Window(new WindowOptions("test"));
        window.getWindowOptions().setRenderer(RenderAPI.VULKAN);
        assertEquals(RenderAPI.VULKAN, window.getWindowOptions().getRenderer());
        window.getWindowOptions().setRenderer(null);
        assertEquals(RenderAPI.AUTO, window.getWindowOptions().getRenderer());
    }

    @Test
    void maximizedAndFullscreenAreStoredWhenNoEngineIsRunning() {
        Window window = new Window(new WindowOptions("test"));
        assertFalse(window.isMaximized());
        window.setMaximized(true);
        window.getWindowOptions().setFullscreen(true).setWindowed(false);
        assertTrue(window.getWindowOptions().isMaximized());
        assertTrue(window.isMaximized());
        assertTrue(window.isFullscreen());
        assertFalse(window.getWindowOptions().isWindowed());
        window.getWindowOptions().setMaximized(false).setWindowed(true);
        assertFalse(window.isMaximized());
        assertTrue(window.getWindowOptions().isWindowed());
    }
}
