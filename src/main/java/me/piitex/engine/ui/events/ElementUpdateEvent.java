package me.piitex.engine.ui.events;

import me.piitex.engine.ui.Element;

public class ElementUpdateEvent extends ElementEvent {
    private final float delta;

    public ElementUpdateEvent(Element element, float delta) {
        super(element);
        this.delta = delta;
    }

    public float getDelta() {
        return delta;
    }
}
