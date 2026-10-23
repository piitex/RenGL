package me.piitex.engine.ui.file;

import me.piitex.engine.io.Json;
import me.piitex.engine.io.JsonWriter;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes a tree of containers and elements as JSON, so a screen can be designed in an editor and loaded by
 * the game: {@code window.addContainer(UiFile.load("layouts/menu.json"))}. Then find the parts to give them behaviour:
 * <pre>{@code
 * Container menu = UiFile.load("layouts/menu.json");
 * UiFile.find(menu, "play", ButtonOverlay.class).onAction(this::startGame);
 * }</pre>
 * A layout file looks like this:
 * <pre>{@code
 * {
 *   "format": 1,
 *   "root": {
 *     "kind": "vertical", "name": "Menu", "width": 1280, "height": 720, "spacing": 12, "alignment": "CENTER",
 *     "children": [
 *       { "kind": "text", "name": "title", "text": "My Game", "fontSize": 48 },
 *       { "kind": "button", "name": "play", "text": "Play", "width": 160, "height": 40, "cornerRadius": 6 }
 *     ]
 *   }
 * }
 * }</pre>
 * Each element is a {@link UiKind kind} followed by its {@link UiProperty properties}. Whatever is missing takes its
 * default, and a property that takes its value from the theme until it is set, such as a background, is written only
 * when it was set, so a theme change still reaches the rest. An element whose kind isn't known, which an older engine
 * meets in a file from a newer one, loads as an empty container that is written back as it was.
 * <p>
 * An element's name is for finding it: {@link #find}. Names aren't checked to be unique, and the first match wins.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class UiFile {
    /** The version of the format this class writes. */
    public static final int FORMAT = 1;

    private static final Logger log = LogManager.getLogger("UiFile");

    private UiFile() {
    }

    // --- Whole files ----------------------------------------------------------------------------------------------

    /**
     * Reads a layout file, with relative image paths in it read from the working folder.
     *
     * @throws IllegalArgumentException if it isn't a layout file, naming what is wrong
     * @throws UncheckedIOException     if it can't be read
     */
    public static Container load(String path) {
        return load(Path.of(path), Path.of(""));
    }

    /**
     * Reads a layout file, with relative image paths in it read from {@code base}.
     */
    public static Container load(Path path, Path base) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read " + path, e);
        }
        try {
            return parse(text, base);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Builds the layout {@code json} holds.
     */
    public static Container parse(String json, Path base) {
        if (!(Json.parse(json) instanceof Map<?, ?> file)) throw new IllegalArgumentException("a layout file must be a JSON object");
        Object format = file.get("format");
        if (format instanceof Number n && n.intValue() > FORMAT) {
            throw new IllegalArgumentException("it was saved by a newer version (format " + n.intValue() + ")");
        }
        if (!(file.get("root") instanceof Map<?, ?> root)) throw new IllegalArgumentException("it has no \"root\"");
        Element element = read(root, base);
        if (!(element instanceof Container container)) throw new IllegalArgumentException("the root must be a container");
        return container;
    }

    /**
     * Writes the layout to a file, creating parent folders. It goes to a temporary file beside it first and is then
     * moved into place, so a crash part way through leaves the old file whole.
     */
    public static void save(Container root, Path path) throws IOException {
        String json = toJson(root);
        Path target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, json);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The layout as the text {@link #save} writes.
     */
    public static String toJson(Container root) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("format", FORMAT);
        file.put("root", write(root));
        return JsonWriter.write(file) + "\n";
    }

    // --- Elements -------------------------------------------------------------------------------------------------

    /**
     * An element and everything in it as JSON, ready for {@link JsonWriter}.
     */
    public static Map<String, Object> write(Element element) {
        UiMeta meta = UiMeta.peek(element);
        if (meta != null && meta.raw != null) return new LinkedHashMap<>(meta.raw);
        UiKind kind = UiKinds.of(element);
        if (kind == null) throw new IllegalArgumentException(element.getClass().getName() + " has no layout kind");
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("kind", kind.getId());
        json.put("name", getName(element));
        for (UiProperty p : kind.getProperties()) {
            if (!p.appliesTo(element) || !p.isSet(element)) continue;
            Object value = p.write(element);
            if (value != null) json.put(p.getName(), value);
        }
        if (element instanceof Container container && kind.isContainer()) {
            List<Object> children = new ArrayList<>();
            for (Element child : UiKinds.children(container)) children.add(write(child));
            json.put("children", children);
        }
        return json;
    }

    /**
     * Makes the element {@code json} describes, and what is in it, reading image paths from {@code base}.
     *
     * @throws IllegalArgumentException if a value doesn't fit its property, naming the element and the property
     */
    public static Element read(Map<?, ?> json, Path base) {
        Element element = build(json, base);
        UiKinds.linkGroups(element);
        return element;
    }

    private static Element build(Map<?, ?> json, Path base) {
        String id = json.get("kind") instanceof String s ? s : "";
        String name = json.get("name") instanceof String s ? s : "";
        UiKind kind = UiKinds.get(id);
        if (kind == null) {
            log.warn("Layout element \"{}\" has an unknown kind \"{}\"; keeping it as it is", name, id);
            Container missing = new Container(0, 0);
            UiMeta meta = UiMeta.of(missing);
            meta.name = name;
            meta.raw = new LinkedHashMap<>();
            json.forEach((k, v) -> meta.raw.put(String.valueOf(k), v));
            return missing;
        }
        Element element = kind.create(name);
        for (UiProperty p : kind.getProperties()) {
            if (!json.containsKey(p.getName())) continue;
            try {
                p.read(element, json.get(p.getName()), base);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("\"" + (name.isEmpty() ? id : name) + "\" " + e.getMessage(), e);
            }
        }
        if (element instanceof Container container && kind.isContainer() && json.get("children") instanceof List<?> children) {
            for (Object child : children) {
                if (!(child instanceof Map<?, ?> childJson)) throw new IllegalArgumentException("\"" + name + "\": a child must be an object");
                Element made = build(childJson, base);
                try {
                    kind.getChildren().add(container, made, -1);
                } catch (IllegalStateException | UnsupportedOperationException full) {
                    throw new IllegalArgumentException("\"" + (name.isEmpty() ? id : name) + "\" can't hold \"" + UiFile.getName(made) + "\": " + full.getMessage(), full);
                }
                made.applyAnchor();
            }
        }
        return element;
    }

    /**
     * A copy of {@code element} and everything in it, as a layout file would give back: the same names, values and
     * which values are set. Image paths are read from {@code base}.
     */
    public static Element copy(Element element, Path base) {
        return read(write(element), base);
    }

    // --- Names and finding ----------------------------------------------------------------------------------------

    /**
     * Whether {@code element} stands in for one whose kind wasn't known when the file was loaded. It is an empty
     * container with nothing to edit, and is written back as it was.
     */
    public static boolean isPlaceholder(Element element) {
        UiMeta meta = UiMeta.peek(element);
        return meta != null && meta.raw != null;
    }

    /** The name of {@code element}, empty if it has none. */
    public static String getName(Element element) {
        UiMeta meta = UiMeta.peek(element);
        return meta == null ? "" : meta.name;
    }

    /** Names {@code element}, so {@link #find} can find it and the file keeps the name. */
    public static void setName(Element element, String name) {
        UiMeta.of(element).name = name == null ? "" : name;
    }

    /**
     * The first element named {@code name} in {@code root}, or in it, looking at {@code root} itself first and then
     * through what it holds, in order. Null if there is none.
     */
    public static Element find(Element root, String name) {
        if (name.equals(getName(root))) return root;
        UiKind kind = UiKinds.of(root);
        if (root instanceof Container container && kind != null && kind.isContainer()) {
            for (Element child : UiKinds.children(container)) {
                Element found = find(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * As {@link #find(Element, String)}, as {@code type}.
     *
     * @throws IllegalArgumentException if there is no element of that name, or it isn't a {@code type}, saying which
     */
    public static <T extends Element> T find(Element root, String name, Class<T> type) {
        Element found = find(root, name);
        if (found == null) throw new IllegalArgumentException("No element named \"" + name + "\"");
        if (!type.isInstance(found)) {
            throw new IllegalArgumentException("\"" + name + "\" is a " + found.getClass().getSimpleName() + ", not a " + type.getSimpleName());
        }
        return type.cast(found);
    }
}
