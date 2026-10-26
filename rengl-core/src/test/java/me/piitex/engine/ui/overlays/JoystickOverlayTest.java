package me.piitex.engine.ui.overlays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JoystickOverlayTest {
    // A 100 point base at the origin: center (50, 50), the thumb is 40 percent of it so it travels 30 points.
    private JoystickOverlay stick;
    private final List<double[]> moves = new ArrayList<>();
    private int releases;

    @BeforeEach
    void setUp() {
        stick = new JoystickOverlay(100);
        stick.setPosition(0, 0);
        stick.onMove((x, y) -> moves.add(new double[]{x, y}));
        stick.onRelease(() -> releases++);
    }

    @Test
    void atRestEverythingReadsZero() {
        assertEquals(0, stick.getAxisX());
        assertEquals(0, stick.getAxisY());
        assertEquals(0, stick.getMagnitude());
        assertEquals(0, stick.getAngle());
        assertFalse(stick.isActive());
    }

    @Test
    void pushingRightReadsPlusOneOnX() {
        stick.onDragStart(80, 50);
        assertEquals(1.0, stick.getAxisX(), 0.001);
        assertEquals(0.0, stick.getAxisY(), 0.001);
        assertEquals(0.0, stick.getAngle(), 0.001);
        assertTrue(stick.isActive());
        assertTrue(stick.isDragging());
    }

    @Test
    void pushingUpOnTheScreenReadsPositiveYBecauseYIsUp() {
        stick.onDragStart(50, 20);
        assertEquals(1.0, stick.getAxisY(), 0.001);
        assertEquals(90.0, stick.getAngle(), 0.001);
        stick.onDragUpdate(50, 80);
        assertEquals(-1.0, stick.getAxisY(), 0.001);
        assertEquals(270.0, stick.getAngle(), 0.001);
    }

    @Test
    void thePushIsCappedAtTheEdgeOfTheBase() {
        stick.onDragStart(500, 50);
        assertEquals(1.0, stick.getAxisX(), 0.001);
        assertEquals(1.0, stick.getMagnitude(), 0.001);
        stick.onDragUpdate(500, 500); // far to the lower right: still at most one in total
        assertEquals(1.0, stick.getMagnitude(), 0.001);
        assertEquals(Math.sqrt(0.5), stick.getAxisX(), 0.001);
        assertEquals(-Math.sqrt(0.5), stick.getAxisY(), 0.001);
    }

    @Test
    void aPushInsideTheDeadZoneReadsZero() {
        stick.onDragStart(52, 50); // 2 of 30 points: 0.067 of the travel, inside the default 0.1
        assertEquals(0.0, stick.getAxisX());
        assertFalse(stick.isActive());
        assertTrue(moves.isEmpty());
    }

    @Test
    void outsideTheDeadZoneTheRangeStartsFromZero() {
        stick.setDeadZone(0.2);
        stick.onDragStart(50 + 30 * 0.6, 50); // 0.6 of the travel
        assertEquals((0.6 - 0.2) / 0.8, stick.getAxisX(), 0.001); // 0.5, not 0.6
        stick.onDragUpdate(50 + 30 * 0.21, 50);
        assertEquals(0.01 / 0.8, stick.getAxisX(), 0.001); // just past the edge it is just above zero
    }

    @Test
    void aDiagonalPushReadsItsAngle() {
        stick.onDragStart(50 + 21.2132, 50 - 21.2132); // up and right, 30 points out
        assertEquals(45.0, stick.getAngle(), 0.01);
        assertEquals(1.0, stick.getMagnitude(), 0.001);
    }

    @Test
    void releasingSnapsBackAndReportsTheRestOnce() {
        stick.onDragStart(80, 50);
        stick.onDragEnd(80, 50);
        assertEquals(0.0, stick.getAxisX());
        assertFalse(stick.isDragging());
        assertEquals(1, releases);
        double[] last = moves.getLast();
        assertEquals(0.0, last[0]);
        assertEquals(0.0, last[1]);
    }

    @Test
    void withoutSnapBackItStaysWhereItWasLeft() {
        stick.setSnapBack(false);
        stick.onDragStart(80, 50);
        stick.onDragEnd(80, 50);
        assertEquals(1.0, stick.getAxisX(), 0.001);
        assertEquals(1, releases);
    }

    @Test
    void onMoveFiresOnlyWhenTheReadingChanges() {
        stick.onDragStart(80, 50);
        int after = moves.size();
        stick.onDragUpdate(80, 50); // the same place
        stick.onDragUpdate(90, 50); // further out, but already at the cap
        assertEquals(after, moves.size());
        stick.onDragUpdate(65, 50);
        assertEquals(after + 1, moves.size());
    }

    @Test
    void fourDirectionsSnapToTheNearestAxis() {
        stick.setDirections(4);
        stick.onDragStart(50 + 25, 50 - 10); // mostly right, a little up
        assertEquals(1.0, stick.getAxisX(), 0.001);
        assertEquals(0.0, stick.getAxisY(), 0.001);
        stick.onDragUpdate(50 + 5, 50 - 25); // mostly up
        assertEquals(0.0, stick.getAxisX(), 0.001);
        assertEquals(1.0, stick.getAxisY(), 0.001);
    }

    @Test
    void eightDirectionsHaveDiagonalsAtFullPush() {
        stick.setDirections(8);
        stick.onDragStart(50 + 12, 50 - 14); // about 49 degrees
        assertEquals(Math.sqrt(0.5), stick.getAxisX(), 0.001);
        assertEquals(Math.sqrt(0.5), stick.getAxisY(), 0.001);
        assertEquals(1.0, stick.getMagnitude(), 0.001);
        assertEquals(45.0, stick.getAngle(), 0.01);
    }

    @Test
    void aDigitalPadStillHasADeadZone() {
        stick.setDirections(4);
        stick.onDragStart(52, 50);
        assertEquals(0.0, stick.getMagnitude());
    }

    @Test
    void settingTheAxesShowsAValueFromElsewhere() {
        stick.setAxes(0.5, 0.5); // x right, y up
        assertEquals(0.5, stick.getAxisX(), 0.1); // the dead zone shifts it a little
        assertTrue(stick.getAxisY() > 0);
        assertTrue(moves.isEmpty());
        stick.setAxes(5, -5);
        assertEquals(1.0, stick.getMagnitude(), 0.001);
    }

    @Test
    void anUpdateWithoutAPressIsIgnored() {
        stick.onDragUpdate(80, 50);
        assertEquals(0.0, stick.getAxisX());
        stick.onDragEnd(80, 50);
        assertEquals(0, releases);
    }
}
