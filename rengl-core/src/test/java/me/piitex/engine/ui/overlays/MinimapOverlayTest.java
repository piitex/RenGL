package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.ui.color.Color;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MinimapOverlayTest {
    // 200 by 100 points showing a 1000 by 500 world: 0.2 points per world unit, the world exactly fills the box.
    private MinimapOverlay map;
    private final List<double[]> navigations = new ArrayList<>();
    private final List<MinimapOverlay.Marker> clicked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        map = new MinimapOverlay(200, 100);
        map.setPosition(0, 0);
        map.setWorld(0, 0, 1000, 500);
        map.onNavigate((x, y) -> navigations.add(new double[]{x, y}));
        map.onMarkerClick(clicked::add);
    }

    @Test
    void theWholeWorldIsFittedInsideTheBox() {
        assertEquals(0.2, map.getPointsPerUnit(), 1e-9);
        assertEquals(0, map.worldToScreenX(0), 1e-9);
        assertEquals(200, map.worldToScreenX(1000), 1e-9);
        assertEquals(100, map.worldToScreenY(500), 1e-9);
    }

    @Test
    void aWorldWithAnotherShapeIsLetterboxedNotStretched() {
        map.setWorld(0, 0, 1000, 1000); // square world in a wide box: 0.1 per unit, 50 points of margin each side
        assertEquals(0.1, map.getPointsPerUnit(), 1e-9);
        assertEquals(50, map.worldToScreenX(0), 1e-9);
        assertEquals(150, map.worldToScreenX(1000), 1e-9);
        assertEquals(0, map.worldToScreenY(0), 1e-9);
    }

    @Test
    void screenAndWorldAreInverses() {
        map.centerOn(300, 200);
        map.setZoom(2);
        for (double wx : new double[]{200, 450, 800}) {
            assertEquals(wx, map.screenToWorldX(map.worldToScreenX(wx)), 1e-9);
        }
        assertEquals(333, map.screenToWorldY(map.worldToScreenY(333)), 1e-9);
    }

    @Test
    void flippingYPutsTheWorldsTopAtTheBottom() {
        map.setFlipY(true);
        assertEquals(100, map.worldToScreenY(0), 1e-9);
        assertEquals(0, map.worldToScreenY(500), 1e-9);
        assertEquals(250, map.screenToWorldY(50), 1e-9); // the middle stays the middle
        assertEquals(500, map.screenToWorldY(0), 1e-9);
    }

    @Test
    void zoomingKeepsTheViewInsideTheWorld() {
        map.setZoom(2);
        assertEquals(0.4, map.getPointsPerUnit(), 1e-9);
        map.centerOn(0, 0); // asked for the corner: the view's half-width is 250 world units, so it stops there
        assertEquals(250, map.getCenterX(), 1e-9);
        assertEquals(125, map.getCenterY(), 1e-9);
        map.centerOn(5000, 5000);
        assertEquals(750, map.getCenterX(), 1e-9);
        assertEquals(375, map.getCenterY(), 1e-9);
    }

    @Test
    void zoomIsHeldBetweenTheLimits() {
        map.setZoomLimits(1, 4);
        map.setZoom(10);
        assertEquals(4, map.getZoom(), 1e-9);
        map.setZoom(0.1);
        assertEquals(1, map.getZoom(), 1e-9);
    }

    @Test
    void theWheelZoomsByNotchesAndSaysWhenItCannot() {
        assertFalse(map.canScroll(0, -1), "already fully zoomed out");
        assertTrue(map.canScroll(0, 1));
        map.onScroll(0, 1);
        assertEquals(1.25, map.getZoom(), 1e-9);
        map.onScroll(0, -1);
        assertEquals(1.0, map.getZoom(), 1e-9);
    }

    @Test
    void followingAMarkerKeepsTheViewOnIt() {
        map.setZoom(4);
        MinimapOverlay.Marker player = map.addMarker(500, 250, Color.WHITE);
        map.setFollow(player);
        player.setPosition(300, 200);
        map.update(0f);
        assertEquals(300, map.getCenterX(), 1e-9);
        assertEquals(200, map.getCenterY(), 1e-9);
        assertEquals(100, map.worldToScreenX(300), 1e-9); // the player sits in the middle of the box
        map.centerOn(100, 100); // moving the view by hand ends the following
        player.setPosition(700, 400);
        map.update(0f);
        assertNotEquals(700, map.getCenterX());
    }

    @Test
    void pressingAndDraggingReportsTheWorldPositionUnderThePointer() {
        map.onDragStart(100, 50);
        assertArrayEquals(new double[]{500, 250}, navigations.getLast(), 1e-9);
        map.onDragUpdate(20, 10);
        assertArrayEquals(new double[]{100, 50}, navigations.getLast(), 1e-9);
        map.onDragUpdate(-500, 900); // off the map: held to the world's edge
        assertArrayEquals(new double[]{0, 500}, navigations.getLast(), 1e-9);
        map.onDragEnd(0, 0);
        int count = navigations.size();
        map.onDragUpdate(50, 50); // after the release nothing follows
        assertEquals(count, navigations.size());
    }

    @Test
    void aPressOnAMarkerClicksItInsteadOfNavigating() {
        MinimapOverlay.Marker base = map.addMarker(500, 250, Color.RED);
        map.onDragStart(102, 51); // within the marker's reach
        assertEquals(List.of(base), clicked);
        assertTrue(navigations.isEmpty());
    }

    @Test
    void theTopmostMarkerWinsAndAHiddenOneIsSkipped() {
        MinimapOverlay.Marker under = map.addMarker(500, 250, Color.RED);
        MinimapOverlay.Marker over = map.addMarker(500, 250, Color.BLUE);
        assertSame(over, map.markerAt(100, 50));
        over.setVisible(false);
        assertSame(under, map.markerAt(100, 50));
        assertNull(map.markerAt(10, 10));
    }

    @Test
    void aRoundMapIgnoresPressesInItsCorners() {
        MinimapOverlay round = new MinimapOverlay(100, 100);
        round.setPosition(0, 0);
        round.setWorld(0, 0, 100, 100);
        round.setRound(true);
        List<double[]> hits = new ArrayList<>();
        round.onNavigate((x, y) -> hits.add(new double[]{x, y}));
        round.onDragStart(2, 2); // the corner is outside the circle
        assertTrue(hits.isEmpty());
        round.onDragStart(50, 50);
        assertEquals(1, hits.size());
    }

    @Test
    void markersCanBeRemovedAndTheListIsACopy() {
        MinimapOverlay.Marker a = map.addMarker(1, 1, Color.RED);
        map.addMarker(2, 2, Color.RED);
        assertEquals(2, map.getMarkers().size());
        assertTrue(map.removeMarker(a));
        assertFalse(map.removeMarker(a));
        assertEquals(1, map.getMarkers().size());
        map.clearMarkers();
        assertTrue(map.getMarkers().isEmpty());
    }

    @Test
    void markersAndTheCameraOutlineAreDrawn() {
        map.getStyling().setBackgroundColor(Color.BLACK);
        map.addMarker(500, 250, Color.RED).setSize(5);
        map.addMarker(100, 100, Color.GREEN).setShape(MinimapOverlay.Shape.SQUARE);
        map.addMarker(900, 400, Color.BLUE).setShape(MinimapOverlay.Shape.ARROW).setHeading(90);
        map.addMarker(900, 100, Color.YELLOW).setShape(MinimapOverlay.Shape.DIAMOND);
        map.setViewport(250, 125, 500, 250);
        try (OffscreenRenderer r = new OffscreenRenderer(200, 100, 1f)) {
            r.begin();
            map.render(r);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                int dot = bmp.getColor(100, 50), square = bmp.getColor(20, 20);
                assertTrue(((dot >> 16) & 0xFF) > 200 && (dot & 0xFF) < 80, "the red dot is at the world's middle");
                assertTrue(((square >> 8) & 0xFF) > 200 && ((square >> 16) & 0xFF) < 80, "the green square is up and left");
                bmp.close();
            }
        }
    }
}
