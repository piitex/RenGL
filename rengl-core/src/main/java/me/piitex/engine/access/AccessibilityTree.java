package me.piitex.engine.access;

import me.piitex.engine.Window;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a window's Elements into a tree of {@link AccessibleNode}s, the shape every accessibility API wants. Only what a
 * person would meet is in it: an Element with no {@link AccessibleRole role} and no name is a layout box, left out with
 * its children kept; one that is hidden with {@link Element#setAccessibleHidden} is left out with everything inside.
 * While a popup or a modal is open it holds only that, since the rest cannot be reached.
 * <p>
 * Call it from the thread that owns the UI (the main thread).
 */
public final class AccessibilityTree {
    private AccessibilityTree() {
    }

    /**
     * The top level of {@code window}'s tree, in reading order (back to front, then the order Elements were added).
     */
    public static List<AccessibleNode> snapshot(Window window) {
        List<AccessibleNode> out = new ArrayList<>();
        Element focus = window.getFocusedElement();
        if (window.getPopup() != null) {
            read(window.getPopup(), focus, out);
        } else if (window.getModal() != null) {
            read(window.getModal(), focus, out);
        } else {
            for (Container container : window.getContainers().values()) read(container, focus, out);
        }
        return out;
    }

    /**
     * The node for one Element and what is in it, or nothing at all when it is hidden. A plain layout box yields its
     * children directly.
     */
    public static List<AccessibleNode> snapshot(Element element, Element focus) {
        List<AccessibleNode> out = new ArrayList<>();
        read(element, focus, out);
        return out;
    }

    private static void read(Element element, Element focus, List<AccessibleNode> out) {
        if (!element.isEnabled() || element.isAccessibleHidden()) return;

        List<AccessibleNode> children = new ArrayList<>();
        if (element instanceof Container container) {
            for (Element child : container.getElements().values()) read(child, focus, children);
        }

        AccessibleRole role = element.getAccessibleRole();
        String name = element.getAccessibleName();
        if (role == AccessibleRole.NONE && (name == null || name.isBlank())) {
            out.addAll(children); // a layout box adds nothing of its own
            return;
        }
        out.add(new AccessibleNode(element, role, name, element.getAccessibleDescription(), element.getAccessibleValue(),
                element.getAccessibleChecked(), element.isEffectivelyDisabled(), element == focus,
                element.getAbsoluteX(), element.getAbsoluteY(), element.getWidth(), element.getHeight(), children));
    }
}
