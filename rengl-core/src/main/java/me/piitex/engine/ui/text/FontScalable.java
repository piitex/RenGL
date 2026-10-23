package me.piitex.engine.ui.text;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.ResizableContainer;

/**
 * An {@link Element} that draws text and can have its font sizes changed from the outside. This is what {@link
 * ResizableContainer} uses to scale text along with a resize. An element with more than one font (e.g. a
 * slider's label and tick labels) exposes all of them, in a fixed order.
 */
public interface FontScalable {
    /**
     * The current size of each font this element draws with, in points. Same length and order every call.
     */
    float[] getFontSizes();

    /**
     * Sets the sizes, in the same order as {@link #getFontSizes()}.
     */
    void setFontSizes(float[] sizes);
}
