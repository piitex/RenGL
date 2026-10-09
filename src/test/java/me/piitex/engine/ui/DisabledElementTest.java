package me.piitex.engine.ui;

import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DisabledElementTest {
    @Test
    void anElementIsNotDisabledByDefault() {
        CheckBoxOverlay box = new CheckBoxOverlay(12);
        assertFalse(box.isDisabled());
        assertFalse(box.isEffectivelyDisabled());
        assertNull(box.getDisablingElement());
    }

    @Test
    void aDisabledContainerDisablesWhatIsInsideIt() {
        Container panel = new Container(200, 100);
        CheckBoxOverlay box = new CheckBoxOverlay(12);
        panel.addElement(box);

        panel.setDisabled(true);
        assertFalse(box.isDisabled(), "its own flag is untouched");
        assertTrue(box.isEffectivelyDisabled());
        assertSame(panel, box.getDisablingElement());
        assertEquals(CursorMode.NOT_ALLOWED, box.getCursorMode());

        panel.setDisabled(false);
        assertFalse(box.isEffectivelyDisabled());
        assertNotEquals(CursorMode.NOT_ALLOWED, box.getCursorMode());
    }

    @Test
    void theDisabledClickHandlerBelongsToTheElementThatIsDisabled() {
        Container panel = new Container(200, 100);
        CheckBoxOverlay box = new CheckBoxOverlay(12);
        panel.addElement(box);
        AtomicInteger told = new AtomicInteger();
        panel.onDisabledClick(told::incrementAndGet);
        panel.setDisabled(true);

        box.getDisablingElement().fireDisabledClick();
        assertEquals(1, told.get());
    }

    @Test
    void theFadeIsClampedToZeroToOne() {
        CheckBoxOverlay box = new CheckBoxOverlay(12);
        assertEquals(Element.DEFAULT_DISABLED_OPACITY, box.getDisabledOpacity());
        box.setDisabledOpacity(3f);
        assertEquals(1f, box.getDisabledOpacity());
    }
}
