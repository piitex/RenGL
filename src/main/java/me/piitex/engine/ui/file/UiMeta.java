package me.piitex.engine.ui.file;

import me.piitex.engine.ui.Element;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * What a layout file knows about an element that the element itself doesn't keep: its name, the properties that were
 * set on purpose (the rest follow the theme), and values the element has no getter for, such as an image's path. It is
 * held weakly, so it goes when the element does.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
final class UiMeta {
    private static final Map<Element, UiMeta> all = Collections.synchronizedMap(new WeakHashMap<>());

    String name = "";
    /** The properties set on purpose. A themed property that isn't in here is left to the theme and not saved. */
    final Set<String> explicit = new HashSet<>();
    /** Values kept for properties whose element can't give them back. */
    final Map<String, Object> stash = new LinkedHashMap<>();
    /** For an element whose kind wasn't known when it was loaded: what the file said, to write back unchanged. */
    Map<String, Object> raw;

    private UiMeta() {
    }

    static UiMeta of(Element element) {
        return all.computeIfAbsent(element, e -> new UiMeta());
    }

    /** The meta of {@code element} if it has any, without making it. */
    static UiMeta peek(Element element) {
        return all.get(element);
    }
}
