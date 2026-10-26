package me.piitex.engine.ui.containers;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.containers.ToastManager.Level;
import me.piitex.engine.ui.containers.ToastManager.Position;
import me.piitex.engine.ui.containers.ToastManager.Toast;
import me.piitex.engine.ui.events.MouseClickEvent;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.*;

class ToastManagerTest {
    private static final double W = 800, H = 600;
    private ToastManager manager;

    @BeforeEach
    void setUp() {
        manager = new ToastManager();
    }

    /** Runs the clock for {@code seconds} in 50 ms frames. */
    private void run(double seconds) {
        for (int i = 0; i < Math.round(seconds / 0.05); i++) manager.advance(0.05f, W, H);
    }

    @Test
    void aToastStaysItsDurationThenFadesAndGoes() {
        Toast toast = manager.show(new Toast("Saved").setDuration(1f));
        run(0.5);
        assertFalse(toast.isDismissed());
        assertEquals(1, manager.getToasts().size());
        run(1.5);
        assertTrue(toast.isDismissed());
        assertTrue(manager.getToasts().isEmpty());
    }

    @Test
    void theDismissCallbackRunsOnceWhateverTheRoute() {
        int[] runs = {0};
        Toast toast = manager.show(new Toast("x").setDuration(0.2f).onDismiss(() -> runs[0]++));
        run(2);
        assertEquals(1, runs[0]);
        Toast second = manager.show(new Toast("y").setDuration(0).onDismiss(() -> runs[0]++));
        manager.clear();
        assertEquals(2, runs[0]);
        assertTrue(second.isDismissed());
        assertTrue(manager.getToasts().isEmpty());
    }

    @Test
    void aToastWithNoDurationStaysUntilDismissed() {
        Toast toast = manager.show(new Toast("Stay").setDuration(0));
        run(30);
        assertFalse(toast.isDismissed());
        toast.dismiss();
        run(0.5);
        assertTrue(toast.isDismissed());
    }

    @Test
    void theShortcutsSetTheLevelAndTitle() {
        assertEquals(Level.INFO, manager.info("a").getLevel());
        assertEquals(Level.SUCCESS, manager.success("a").getLevel());
        assertEquals(Level.WARNING, manager.warning("a").getLevel());
        Toast error = manager.error("Upload failed", "No answer.");
        assertEquals(Level.ERROR, error.getLevel());
        assertEquals("Upload failed", error.getTitle());
        assertEquals("No answer.", error.getMessage());
    }

    @Test
    void toastsStackFromTheBottomRightCornerNewestNearest() {
        Toast first = manager.show("first");
        Toast second = manager.show("second");
        run(1);
        assertEquals(W - 16 - 340, first.getX(), 0.5);
        assertEquals(340, first.getWidth(), 0.001);
        assertEquals(H - 16 - second.getHeight(), second.getY(), 0.5, "the newest is nearest the edge");
        assertEquals(second.getY() - 8 - first.getHeight(), first.getY(), 0.5, "the older one is above it, 8 apart");
    }

    @Test
    void thePositionChoosesTheCornerAndTheDirection() {
        manager.setPosition(Position.TOP_LEFT);
        Toast first = manager.show("first");
        Toast second = manager.show("second");
        run(1);
        assertEquals(16, first.getX(), 0.5);
        assertEquals(16, second.getY(), 0.5, "the newest is nearest the top edge");
        assertEquals(second.getY() + second.getHeight() + 8, first.getY(), 0.5);

        ToastManager centered = new ToastManager();
        centered.setPosition(Position.TOP_CENTER);
        Toast middle = centered.show("middle");
        for (int i = 0; i < 20; i++) centered.advance(0.05f, W, H);
        assertEquals((W - 340) / 2, middle.getX(), 0.5);
    }

    @Test
    void aNarrowWindowNarrowsTheToasts() {
        Toast toast = manager.show("narrow");
        manager.advance(0.05f, 200, 300);
        assertEquals(168, toast.getWidth(), 0.001);
    }

    @Test
    void aLongMessageWrapsAndTheTitleAddsALine() {
        Toast shortOne = manager.show("Short");
        Toast longOne = manager.show("This is a much longer message that cannot possibly fit on a single line of a toast. ".repeat(3));
        Toast titled = manager.show(new Toast("Short").setTitle("Heading"));
        run(0.1);
        assertTrue(longOne.getHeight() > shortOne.getHeight() * 2, "wrapped: " + longOne.getHeight() + " vs " + shortOne.getHeight());
        assertTrue(titled.getHeight() > shortOne.getHeight());
    }

    @Test
    void onlyMaxVisibleShowAtOnceTheRestWait() {
        manager.setMaxVisible(2);
        Toast a = manager.show(new Toast("a").setDuration(0.3f));
        Toast b = manager.show(new Toast("b").setDuration(0));
        Toast c = manager.show(new Toast("c").setDuration(0));
        run(0.2);
        assertFalse(Float.isNaN(a.getX()));
        assertFalse(Float.isNaN(b.getX()));
        assertTrue(Float.isNaN(c.getX()), "the third is still waiting");
        run(1.2);
        assertTrue(a.isDismissed());
        assertFalse(Float.isNaN(c.getX()), "it took the freed place");
    }

    @Test
    void clickingAToastRunsItsActionAndDismissesIt() {
        int[] ran = {0};
        Toast toast = manager.show(new Toast("Deleted").setDuration(0).setAction("Undo", () -> ran[0]++));
        run(1);
        double cx = toast.getX() + toast.getWidth() / 2, cy = toast.getY() + toast.getHeight() / 2;
        assertTrue(manager.contains(cx, cy));
        manager.getMouseClickListenerConsumer().accept(new MouseClickEvent(cx, cy, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS));
        assertEquals(1, ran[0]);
        run(0.5);
        assertTrue(toast.isDismissed());
    }

    @Test
    void clickingOutsideAnyToastDoesNothing() {
        Toast toast = manager.show(new Toast("Stay").setDuration(0));
        run(1);
        manager.getMouseClickListenerConsumer().accept(new MouseClickEvent(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS));
        manager.getMouseClickListenerConsumer().accept(new MouseClickEvent(toast.getX() + 5, toast.getY() + 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS));
        run(1);
        assertFalse(toast.isDismissed());
    }

    @Test
    void theManagerOnlyCoversTheToastsSoTheRestOfTheWindowKeepsItsClicks() {
        assertFalse(manager.contains(10, 10));
        assertFalse(manager.contains(0, 0));
        Toast toast = manager.show(new Toast("Hi").setDuration(0));
        run(1);
        assertFalse(manager.contains(10, 10));
        assertTrue(manager.contains(toast.getX() + 4, toast.getY() + 4));
        toast.dismiss();
        run(1);
        assertFalse(manager.contains(toast.getX() + 4, toast.getY() + 4));
    }

    @Test
    void aToastIsDrawnWithItsBackgroundAndStripe() {
        manager.setBackgroundPaint(me.piitex.engine.ui.color.Color.WHITE);
        manager.setTextPaint(me.piitex.engine.ui.color.Color.BLACK);
        Toast toast = manager.show(new Toast("Level up").setLevel(Level.ERROR).setDuration(0));
        for (int i = 0; i < 40; i++) manager.advance(0.05f, 400, 300);
        try (OffscreenRenderer r = new OffscreenRenderer(400, 300, 1f)) {
            r.begin();
            manager.render(r);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                int stripe = bmp.getColor((int) toast.getX() + 2, (int) (toast.getY() + toast.getHeight() / 2));
                int body = bmp.getColor((int) (toast.getX() + toast.getWidth() - 40), (int) (toast.getY() + toast.getHeight() - 4));
                assertTrue(((stripe >> 16) & 0xFF) > 180 && (stripe & 0xFF) < 100, "the error stripe is red");
                assertTrue((body & 0xFF) > 200 && ((body >> 16) & 0xFF) > 200, "the body is the white background");
                bmp.close();
            }
        }
        manager.dispose();
    }
}
