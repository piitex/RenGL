package me.piitex.engine.graphics;

import me.piitex.engine.math.Rect;
import me.piitex.engine.math.Vec2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CameraBoundsTest {
    private static Camera camera() {
        Camera camera = new Camera(200f, 100f);
        camera.setBounds(new Rect(0, 0, 1000, 1000));
        return camera;
    }

    @Test
    void anUprightViewStopsAtTheEdge() {
        Camera camera = camera();
        camera.setPosition(0, 0);
        Rect view = camera.getVisibleBounds();
        assertEquals(0f, view.x(), 0.01f);
        assertEquals(0f, view.y(), 0.01f);
    }

    @Test
    void aTurnedViewStaysInsideTheBounds() {
        Camera camera = camera();
        camera.setRotation(30f);
        for (float[] aim : new float[][]{{0, 0}, {1000, 0}, {0, 1000}, {1000, 1000}}) {
            camera.setPosition(aim[0], aim[1]);
            Rect view = camera.getVisibleBounds();
            assertTrue(view.x() >= -0.01f && view.y() >= -0.01f, "past the top left: " + view);
            assertTrue(view.x() + view.width() <= 1000.01f && view.y() + view.height() <= 1000.01f, "past the bottom right: " + view);
        }
    }

    @Test
    void aLevelSmallerThanTheTurnedViewIsCentered() {
        Camera camera = new Camera(200f, 100f);
        camera.setBounds(new Rect(0, 0, 80, 80));
        camera.setRotation(90f);
        camera.setPosition(0, 0);
        Vec2 middle = camera.screenToWorld(100f, 50f);
        assertEquals(40f, middle.x(), 0.01f);
        assertEquals(40f, middle.y(), 0.01f);
    }
}
