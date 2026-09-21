package me.piitex.engine.ui.events;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.Element;

public class ElementRenderEvent extends ElementEvent {
    private final Renderer renderer;

    public ElementRenderEvent(Element element, Renderer renderer) {
        super(element);
        this.renderer = renderer;
    }

    public Renderer getRenderer() {
        return renderer;
    }
}
