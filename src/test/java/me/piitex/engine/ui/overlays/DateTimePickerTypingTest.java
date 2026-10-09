package me.piitex.engine.ui.overlays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DateTimePickerTypingTest {
    private DateTimePickerOverlay picker;
    private final AtomicInteger changes = new AtomicInteger();

    @BeforeEach
    void setUp() {
        picker = new DateTimePickerOverlay(200, 34);
        picker.onChange(p -> changes.incrementAndGet());
    }

    private void type(String text) {
        text.codePoints().forEach(picker::onCharTyped);
    }

    private void press(int key) {
        picker.onKeyPressed(key, GLFW.GLFW_PRESS, 0);
    }

    @Test
    void aTypedDateIsReadOnEnter() {
        type("2026-09-19");
        assertNull(picker.getDate(), "nothing is set until Enter");
        press(GLFW.GLFW_KEY_ENTER);
        assertEquals(LocalDate.of(2026, 9, 19), picker.getDate());
        assertEquals(1, changes.get());
    }

    @Test
    void textThatIsNotADateIsKeptOutOfTheValue() {
        type("not a date");
        press(GLFW.GLFW_KEY_ENTER);
        assertNull(picker.getDate());
        assertEquals(0, changes.get());
    }

    @Test
    void aDateOutsideTheLimitsIsRefused() {
        picker.setMaxDate(LocalDate.of(2026, 1, 1));
        type("2026-09-19");
        press(GLFW.GLFW_KEY_ENTER);
        assertNull(picker.getDate());
    }

    @Test
    void escapeThrowsTheTypingAway() {
        picker.setDate(LocalDate.of(2020, 2, 2));
        type("2026-09-19");
        press(GLFW.GLFW_KEY_ESCAPE);
        press(GLFW.GLFW_KEY_ENTER); // nothing typed now: this would open the panel, which needs a window
        assertEquals(LocalDate.of(2020, 2, 2), picker.getDate());
    }

    @Test
    void backspaceEditsWhatWasTyped() {
        type("2026-09-199");
        press(GLFW.GLFW_KEY_BACKSPACE);
        press(GLFW.GLFW_KEY_ENTER);
        assertEquals(LocalDate.of(2026, 9, 19), picker.getDate());
    }

    @Test
    void leavingTheBoxKeepsAReadableValueAndDropsTheRest() {
        type("2026-09-19");
        picker.onFocusLost();
        assertEquals(LocalDate.of(2026, 9, 19), picker.getDate());

        type("garbage");
        picker.onFocusLost();
        assertEquals(LocalDate.of(2026, 9, 19), picker.getDate());
        assertEquals(1, changes.get());
    }

    @Test
    void aTimeAndADateTimeAreTypedInTheFormatTheBoxShows() {
        DateTimePickerOverlay time = new DateTimePickerOverlay(200, 34, DateTimePickerOverlay.Mode.TIME);
        "14:30".codePoints().forEach(time::onCharTyped);
        time.onKeyPressed(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0);
        assertEquals(LocalTime.of(14, 30), time.getTime());

        DateTimePickerOverlay both = new DateTimePickerOverlay(200, 34, DateTimePickerOverlay.Mode.DATE_TIME);
        "2026-09-19 08:05".codePoints().forEach(both::onCharTyped);
        both.onKeyPressed(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0);
        assertEquals(LocalDateTime.of(2026, 9, 19, 8, 5), both.getDateTime());
    }

    @Test
    void secondsAreKeptOnlyWhenShown() {
        picker.setTime(LocalTime.of(10, 20, 30));
        assertEquals(LocalTime.of(10, 20), picker.getTime());

        picker.setShowSeconds(true);
        picker.setTime(LocalTime.of(10, 20, 30));
        assertEquals(LocalTime.of(10, 20, 30), picker.getTime());

        DateTimePickerOverlay time = new DateTimePickerOverlay(200, 34, DateTimePickerOverlay.Mode.TIME);
        time.setShowSeconds(true);
        "23:59:58".codePoints().forEach(time::onCharTyped);
        time.onKeyPressed(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS, 0);
        assertEquals(LocalTime.of(23, 59, 58), time.getTime());
        assertEquals("23:59:58", time.getDisplayText());

        time.setShowSeconds(false);
        assertEquals(LocalTime.of(23, 59), time.getTime(), "turning seconds off drops them");
    }
}
