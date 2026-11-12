package me.piitex.engine.access;

import me.piitex.engine.ui.Element;

import java.util.List;

/**
 * One Element as an accessibility tree sees it, frozen at the moment {@link AccessibilityTree#snapshot} read it. A
 * backend turns these into whatever its platform wants (a speech string, a UI Automation provider), without touching
 * the live Elements from another thread.
 *
 * @param element     the Element this came from, to send an action back to
 * @param role        what it is
 * @param name        what it is called, or null
 * @param description longer help, or null
 * @param value       its current value as words, or null
 * @param checked     true or false for a check box, radio button or switch; null for anything else
 * @param disabled    whether it cannot be used
 * @param focused     whether it holds the keyboard focus
 * @param x           left edge in window coordinates
 * @param y           top edge in window coordinates
 * @param width       width
 * @param height      height
 * @param children    what is inside it, in reading order
 */
public record AccessibleNode(Element element, AccessibleRole role, String name, String description, String value,
                             Boolean checked, boolean disabled, boolean focused,
                             double x, double y, double width, double height, List<AccessibleNode> children) {
}
