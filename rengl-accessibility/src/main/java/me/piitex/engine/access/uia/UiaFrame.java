package me.piitex.engine.access.uia;

import me.piitex.engine.Window;
import me.piitex.engine.access.AccessibilityTree;
import me.piitex.engine.access.AccessibleNode;
import me.piitex.engine.access.AccessibleRole;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextAreaOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A copy of a window's accessibility tree that is safe to read from any thread. The operating system asks about the
 * window from threads of its own, while the Elements belong to the render thread; so the render thread makes one of
 * these every so often and the system's questions are answered from the latest, without touching an Element.
 * <p>
 * Id 0 is the window itself, the root. Every other Element keeps the same id for as long as it lives, so an id the
 * system holds on to still means the same control after the next copy is made.
 */
final class UiaFrame {
    /** One control. {@code element} is only for acting on it, and only from the render thread. */
    record Item(int id, int parent, int[] children, AccessibleRole role, String name, String description, String value,
                Boolean checked, boolean disabled, boolean focused, boolean focusable, boolean password, boolean readOnly,
                boolean ranged, double min, double max, double current, double step,
                double x, double y, double width, double height, String className, Element element) {
    }

    /** Hands out an id the first time it sees an Element. Render thread only. */
    static final class Ids {
        private final Map<Element, Integer> ids = new WeakHashMap<>();
        private int next = 1;

        int of(Element element) {
            return ids.computeIfAbsent(element, e -> next++);
        }
    }

    static final UiaFrame EMPTY = new UiaFrame(Map.of(0, new Item(0, -1, new int[0], AccessibleRole.GROUP, "", "", null, null,
            false, false, false, false, false, false, 0, 0, 0, 0, 0, 0, 0, 0, "Window", null)), -1);

    private final Map<Integer, Item> items;
    private final int focus;

    private UiaFrame(Map<Integer, Item> items, int focus) {
        this.items = items;
        this.focus = focus;
    }

    Item item(int id) {
        return items.get(id);
    }

    Item root() {
        return items.get(0);
    }

    /** The id of the control that has keyboard focus, or -1. */
    int focus() {
        return focus;
    }

    /** The deepest control that contains this point, in the window's own units, or 0 for the window itself. */
    int hit(double x, double y) {
        int found = 0;
        Item at = root();
        while (true) {
            Item next = null;
            // Later children draw on top of earlier ones
            for (int i = at.children().length - 1; i >= 0; i--) {
                Item child = items.get(at.children()[i]);
                if (child != null && x >= child.x() && y >= child.y() && x < child.x() + child.width() && y < child.y() + child.height()) {
                    next = child;
                    break;
                }
            }
            if (next == null) return found;
            found = next.id();
            at = next;
        }
    }

    /** Reads the window now. Render thread only. */
    static UiaFrame build(Window window, Ids ids) {
        Map<Integer, Item> items = new HashMap<>();
        int[] focus = {-1};
        List<AccessibleNode> top = AccessibilityTree.snapshot(window);
        int[] children = new int[top.size()];
        for (int i = 0; i < children.length; i++) children[i] = add(top.get(i), 0, ids, items, focus);
        items.put(0, new Item(0, -1, children, AccessibleRole.GROUP, "", "", null, null, false, false, false, false, false,
                false, 0, 0, 0, 0, 0, 0, 0, 0, "Window", null));
        return new UiaFrame(items, focus[0]);
    }

    private static int add(AccessibleNode node, int parent, Ids ids, Map<Integer, Item> items, int[] focus) {
        Element element = node.element();
        int id = ids.of(element);
        List<AccessibleNode> kids = node.children();
        int[] children = new int[kids.size()];
        for (int i = 0; i < children.length; i++) children[i] = add(kids.get(i), id, ids, items, focus);
        if (node.focused()) focus[0] = id;

        boolean password = element instanceof TextFieldOverlay field && field.isPassword();
        boolean readOnly = switch (element) {
            case TextFieldOverlay field -> field.isReadOnly();
            case TextAreaOverlay area -> area.isReadOnly();
            default -> true;
        };
        boolean ranged = false;
        double min = 0, max = 0, current = 0, step = 0;
        if (element instanceof SliderOverlay slider) {
            ranged = true;
            min = slider.getMin();
            max = slider.getMax();
            current = slider.getValue();
            step = slider.getStep();
        } else if (element instanceof me.piitex.engine.ui.overlays.KnobOverlay knob) {
            ranged = true;
            min = knob.getMin();
            max = knob.getMax();
            current = knob.getValue();
            step = knob.getStep();
        } else if (element instanceof ProgressBarOverlay progress) {
            ranged = true;
            max = 100;
            current = progress.getProgress() * 100;
        }
        items.put(id, new Item(id, parent, children, node.role(), node.name(), node.description(), node.value(), node.checked(),
                node.disabled(), node.focused(), element instanceof Focusable && !node.disabled(), password, readOnly,
                ranged, min, max, current, step, node.x(), node.y(), node.width(), node.height(),
                element.getClass().getSimpleName(), element));
        return id;
    }

    /** The children of {@code parent} as a list, for walking siblings. */
    List<Integer> siblings(int parent) {
        Item item = items.get(parent);
        List<Integer> out = new ArrayList<>();
        if (item != null) for (int c : item.children()) out.add(c);
        return out;
    }
}
