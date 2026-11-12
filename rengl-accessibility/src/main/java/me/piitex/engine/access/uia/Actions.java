package me.piitex.engine.access.uia;

import me.piitex.engine.ui.Activatable;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextAreaOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;

/**
 * What a screen reader's "press", "toggle" and "set value" do to each kind of control. Render thread only. Each one
 * counts as the user's doing: the control's change listeners run as they would for a click, a key or typing, and the
 * new state is announced as it would be after a key press.
 */
final class Actions {
    private Actions() {
    }

    /** Presses a button or link, flips a check box or switch, picks a radio button. */
    static void press(Element element) {
        if (element instanceof Activatable activatable) {
            activatable.activate();
        } else {
            return;
        }
        element.notifyAccessibleChange();
    }

    static void setText(Element element, String text) {
        if (element instanceof TextFieldOverlay field) {
            field.setTextByUser(text);
        } else if (element instanceof TextAreaOverlay area) {
            area.setTextByUser(text);
        }
    }

    static void setNumber(Element element, double value) {
        if (element instanceof SliderOverlay slider) slider.setValueByUser(value);
        else if (element instanceof me.piitex.engine.ui.overlays.KnobOverlay knob) knob.setValueByUser(value);
    }
}
