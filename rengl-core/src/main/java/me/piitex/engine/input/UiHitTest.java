package me.piitex.engine.input;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.scroll.Scrollable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Finds what is under a point in a tree of {@link Container}s, the way the window finds what a mouse press landed on.
 * The window's input handling and any other surface that shows a container (a screen in a 3D scene, say) use these
 * same rules, so a button is found, hovered and scrolled alike wherever it is drawn.
 * <p>
 * Points are in the same space the container is laid out in: the space {@link Element#getX()} and {@link Element#getY()}
 * are given in.
 */
public final class UiHitTest {
    private UiHitTest() {
    }

    /**
     * Sets hovered on {@code container} and its whole subtree: true for {@code hit} and every Container on the path to it, false for everything else. Returns whether {@code container} itself is on that path.
     */
    public static boolean applyHover(Container container, Element hit) {
        boolean onPath = container == hit;
        for (Element child : container.getElements().values()) {
            if (child instanceof Container childContainer) {
                if (applyHover(childContainer, hit)) {
                    onPath = true;
                }
            } else {
                boolean hovered = child == hit;
                child.setHovered(hovered && !child.isEffectivelyDisabled());
                if (hovered) onPath = true;
            }
        }
        container.setHovered(onPath && !container.isEffectivelyDisabled());
        return onPath;
    }

    /**
     * Recursively finds the topmost (highest z-index) Element at (px, py) within
     * container's subtree, descending into any child that is itself a Container. The
     * recursion is what lets a Focusable or Draggable nested inside a Layout inside a
     * plain Container be found; that nesting is ordinary, since Layout extends Container,
     * and a search that stopped at the immediate parent would never reach the Element
     * itself. Returns {@code container} if it contains the point but nothing inside it,
     * at any depth, does, or null if the point is not in {@code container} at all, or if it is only on the empty part of
     * a container that {@link Container#isPointerPassthrough passes the pointer through}.
     */
    public static Element findTopmostHit(Container container, double px, double py) {
        if (!container.isEnabled()) return null;
        // A container that doesn't clip shows its children past its own edge, so they can be pointed at there too.
        boolean inside = container.contains(px, py);
        if (!inside && container.isClipping()) return null;
        if (inside && container.interceptsPointer(px, py)) return container;

        for (Map.Entry<Integer, Element> entry : container.getElements().descendingMap().entrySet()) {
            Element child = entry.getValue();
            if (!child.isEnabled()) continue;

            if (child instanceof Container childContainer) {
                // A container that shows and does nothing where it is empty lets the point through to what is below.
                Element nested = findTopmostHit(childContainer, px, py);
                if (nested != null) return nested;
                continue;
            }
            if (child.contains(px, py)) return child;
        }
        return inside && !container.isPointerPassthrough(px, py) ? container : null;
    }

    /**
     * Recursively finds the innermost enabled {@link Scrollable} Element whose bounds
     * contain (px, py), preferring a nested one over an outer one it's inside of.
     * Returns null if nothing in this subtree is Scrollable at that point. A Scrollable that
     * reports {@link Scrollable#canScroll} false for this event is skipped, so the wheel falls
     * through to the enclosing one (e.g. an auto-height text block inside a ScrollContainer).
     * <p>
     * A plain, non-Container child that implements {@link Scrollable}, such as
     * {@link me.piitex.engine.ui.overlays.TextAreaOverlay} with its built-in scrolling,
     * is checked directly, the same way {@link #findTopmostHit} treats any Element,
     * Container or not, as a valid hit. Only a {@link Container} recurses further first,
     * since a plain Element has no children to descend into.
     */
    public static Element findScrollTarget(Container container, double px, double py, double dx, double dy) {
        if (!container.isEnabled()) return null;
        boolean inside = container.contains(px, py);
        if (!inside && container.isClipping()) return null;

        for (Map.Entry<Integer, Element> entry : container.getElements().descendingMap().entrySet()) {
            Element child = entry.getValue();
            if (!child.isEnabled()) continue;
            if (child instanceof Container childContainer) {
                Element nested = findScrollTarget(childContainer, px, py, dx, dy);
                if (nested != null) return nested;
            } else if (child.contains(px, py) && child instanceof Scrollable scrollable && scrollable.canScroll(dx, dy)) {
                return child;
            }
        }

        return inside && container instanceof Scrollable scrollable && scrollable.canScroll(dx, dy) ? container : null;
    }

    /**
     * Adds to {@code out}, depth-first in draw order, every enabled Element in {@code container}'s subtree that Tab can
     * land on: one that is {@link Element#isFocusTraversable()} and {@link Focusable}. A disabled container hides its
     * whole subtree.
     */
    public static void collectFocusStops(Container container, List<Element> out) {
        if (!container.isEnabled() || container.isDisabled()) return;
        for (Element child : container.getElements().values()) {
            if (!child.isEnabled() || child.isDisabled()) continue;
            if (child.isFocusTraversable() && child instanceof Focusable) {
                out.add(child);
            }
            if (child instanceof Container childContainer) {
                collectFocusStops(childContainer, out);
            }
        }
    }

    /**
     * Puts tab stops in the order Tab visits them: those with a non-negative {@link Element#getTabIndex()} first in
     * ascending order, then the rest in the order they were collected. The sort is stable.
     */
    public static void sortTabOrder(List<Element> stops) {
        stops.sort(Comparator.comparingInt(e -> e.getTabIndex() < 0 ? Integer.MAX_VALUE : e.getTabIndex()));
    }
}
