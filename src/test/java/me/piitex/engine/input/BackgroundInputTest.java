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

    private static void focus(Window window) throws Exception {
        Method notifyFocused = Window.class.getDeclaredMethod("notifyFocused", boolean.class);
        notifyFocused.setAccessible(true);
        notifyFocused.invoke(window, true);
    }

    private static Viewport3D.InputListener recorder(List<String> events) {
        return new Viewport3D.InputListener() {
            @Override
            public void pointerMoved(double x, double y) {
                events.add("moved " + (int) x + "," + (int) y);
            }

            @Override
            public void pointerExited() {
                events.add("exited");
            }

            @Override
            public void dragStarted(double x, double y) {
                events.add("start " + (int) x + "," + (int) y);
            }

            @Override
            public void dragged(double x, double y, double dx, double dy) {
                events.add("dragged " + (int) x + "," + (int) y);
            }

            @Override
            public void dragEnded(double x, double y) {
                events.add("end " + (int) x + "," + (int) y);
            }

            @Override
            public void clicked(double x, double y) {
                events.add("clicked " + (int) x + "," + (int) y);
            }

            @Override
            public void scrolled(double dx, double dy, double x, double y) {
                events.add("scrolled " + (int) x + "," + (int) y);
            }
        };
    }

    @Test
    void asAnElementsBackgroundOnlyItsOwnBoxCounts() throws Exception {
        Window window = new Window(new WindowOptions("test").setDimensions(400, 300));
        focus(window);
        Input input = window.getInput();
        List<String> events = new ArrayList<>();
        Background3D background = new Background3D(window, null);
        background.setInputListener(recorder(events));
        // The element stands at (100, 50) and is 200 by 100.
        float x = 100, y = 50, w = 200, h = 100;

        input.beginFrame();
        input.mouseEntered(true);
        input.mouseMoved(20, 20, true);
        input.mouseMoved(30, 25, true);
        input.scrolled(0, 1);
        input.mousePressed(0, 0);
        background.update(0.016f, x, y, w, h);
        assertEquals(List.of(), events, "outside the element: no movement, no wheel, no press");
        input.beginFrame();
        input.mouseReleased(0);
        background.update(0.016f, x, y, w, h);
        assertEquals(List.of(), events, "and no release of a press it never had");

        events.clear();
        input.beginFrame();
        input.mouseMoved(150, 80, true);
        background.update(0.016f, x, y, w, h);
        assertEquals(List.of("moved 50,30"), events, "over it: in its own coordinates");

        events.clear();
        input.beginFrame();
        input.scrolled(0, 1);
        background.update(0.016f, x, y, w, h);
        assertEquals(List.of("scrolled 50,30"), events);

        events.clear();
        input.beginFrame();
        input.mousePressed(0, 0);
        background.update(0.016f, x, y, w, h);
        assertEquals(List.of("start 50,30"), events);

        events.clear();
        input.beginFrame();
        input.mouseMoved(380, 250, true); // dragged out of the box: the drag goes on
        background.update(0.016f, x, y, w, h);
        assertEquals(List.of("dragged 280,200"), events);

        events.clear();
        input.beginFrame();
        input.mouseReleased(0);
        background.update(0.016f, x, y, w, h);
        assertEquals(List.of("end 280,200", "exited"), events, "and ends wherever it is let go, with the exit it owes");

        events.clear();
        input.beginFrame();
        background.update(0.016f, x, y, w, h);
        assertEquals(List.of(), events, "once");
    }

    @Test
    void leavingTheBoxIsExitingEvenWhenStillInTheWindow() throws Exception {
        Window window = new Window(new WindowOptions("test").setDimensions(400, 300));
        focus(window);
        Input input = window.getInput();
        List<String> events = new ArrayList<>();
        Background3D background = new Background3D(window, null);
        background.setInputListener(recorder(events));
        input.beginFrame();
        input.mouseEntered(true);
        input.mouseMoved(150, 80, true);
        input.mouseMoved(160, 85, true);
        background.update(0.016f, 100, 50, 200, 100);
        assertEquals(List.of("moved 60,35"), events);
        events.clear();
        input.beginFrame();
        input.mouseMoved(20, 20, true);
        background.update(0.016f, 100, 50, 200, 100);
        assertEquals(List.of("exited"), events);
    }

    // ---- backgrounds that are taken away, and layers ---------------------------------------------------------------

    private static final class Probe implements me.piitex.engine.ui.background.Background {
        final String name;
        final List<String> log;

        Probe(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void render(me.piitex.engine.gl.renderer.Renderer renderer, float x, float y, float width, float height) {
            log.add("render " + name);
        }

        @Override
        public void update(float delta, float x, float y, float width, float height) {
            log.add("update " + name);
        }

        @Override
        public void onDetached() {
            log.add("detached " + name);
        }
    }

    @Test
    void aReplacedOrRemovedBackgroundIsToldSo() {
        Window window = new Window(new WindowOptions("test").setDimensions(200, 200));
        List<String> log = new ArrayList<>();
        Probe a = new Probe("a", log), b = new Probe("b", log);
        window.setBackground(a);
        assertEquals(List.of(), log);
        window.setBackground(a);
        assertEquals(List.of(), log, "setting the same one again takes nothing away");
        window.setBackground(b);
        assertEquals(List.of("detached a"), log);
        window.setBackground(null);
        assertEquals(List.of("detached a", "detached b"), log);

        log.clear();
        me.piitex.engine.ui.overlays.TextOverlay element = new me.piitex.engine.ui.overlays.TextOverlay("x");
        element.setBackground(a);
        element.setBackground(b);
        element.setBackground(null);
        assertEquals(List.of("detached a", "detached b"), log);
    }

    @Test
    void layeredBackgroundsDrawBackToFrontAndPassEverythingOn() {
        List<String> log = new ArrayList<>();
        me.piitex.engine.ui.background.LayeredBackground layers = new me.piitex.engine.ui.background.LayeredBackground(
                new Probe("back", log), null, new Probe("front", log));
        assertEquals(2, layers.getLayers().size(), "nulls are skipped");
        layers.update(0.1f, 0, 0, 10, 10);
        layers.render(null, 0, 0, 10, 10);
        assertEquals(List.of("update back", "update front", "render back", "render front"), log);

        log.clear();
        Probe extra = new Probe("extra", log);
        layers.add(extra).add(extra);
        assertEquals(3, layers.getLayers().size(), "a layer is not added twice");
        layers.remove(extra);
        assertEquals(List.of("detached extra"), log);
        log.clear();
        layers.onDetached();
        assertEquals(List.of("detached back", "detached front"), log);
    }
}
