package me.piitex.engine.ui.events;

import me.piitex.engine.ui.Element;

public class ElementEvent {
    private final Element element;

    public ElementEvent(Element element) {
        this.element = element;
    }

    public Element getElement() {
        return element;
    }
}
