package me.piitex.engine.ui.file;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A kind of element a layout file can hold: a button, a text field, a vertical layout... It knows its saved name
 * ({@link #getId()}), the class it makes, whether it holds other elements, and its {@link UiProperty properties}.
 * {@link UiKinds} lists the built-in ones and takes more.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class UiKind {
    private final String id;
    private final String label;
    private final String group;
    private final Class<? extends Element> type;
    private final Supplier<? extends Element> maker;
    private final List<UiProperty> properties;
    private final UiChildren children;
    private final Consumer<Container> starter;

    /**
     * @param id         the name saved in files: lowercase letters, "text", "button"
     * @param label      the name shown to people
     * @param group      the heading an editor lists it under, such as "Containers" or "Controls"
     * @param type       the class {@code maker} makes; elements of exactly this class are this kind
     * @param maker      makes a new one, with a size and look that suit a first placement
     * @param properties all of its properties, in the order they are applied on loading
     */
    public UiKind(String id, String label, String group, Class<? extends Element> type, Supplier<? extends Element> maker,
                  List<UiProperty> properties) {
        this(id, label, group, type, maker, properties, Container.class.isAssignableFrom(type) ? UiChildren.PLAIN : UiChildren.NONE, null);
    }

    private UiKind(String id, String label, String group, Class<? extends Element> type, Supplier<? extends Element> maker,
                   List<UiProperty> properties, UiChildren children, Consumer<Container> starter) {
        this.id = id;
        this.label = label;
        this.group = group;
        this.type = type;
        this.maker = maker;
        this.properties = List.copyOf(properties);
        this.children = children;
        this.starter = starter;
    }

    /** This kind, holding its elements the way {@code children} says, such as a tab container's tabs. */
    public UiKind holding(UiChildren children) {
        return new UiKind(id, label, group, type, maker, properties, children, starter);
    }

    /**
     * This kind, with {@code starter} run on each one an editor adds fresh, to give it something to start from, such as
     * a tab container's first tabs. Loading a file doesn't run it, as the file says what the container holds.
     */
    public UiKind startingWith(Consumer<Container> starter) {
        return new UiKind(id, label, group, type, maker, properties, children, starter);
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getGroup() {
        return group;
    }

    public Class<? extends Element> getType() {
        return type;
    }

    /** Whether elements of this kind hold other elements. */
    public boolean isContainer() {
        return children != UiChildren.NONE && Container.class.isAssignableFrom(type);
    }

    /** How elements of this kind hold the others in them. */
    public UiChildren getChildren() {
        return children;
    }

    /** Every property, in the order they are applied when a file is loaded. */
    public List<UiProperty> getProperties() {
        return properties;
    }

    public UiProperty getProperty(String name) {
        for (UiProperty p : properties) if (p.getName().equals(name)) return p;
        return null;
    }

    /** A new element of this kind, named {@code name}. */
    public Element create(String name) {
        Element element = maker.get();
        UiFile.setName(element, name);
        return element;
    }

    /**
     * A new element of this kind for an editor to add: as {@link #create}, and holding what a first one should.
     */
    public Element createNew(String name) {
        Element element = create(name);
        if (starter != null && element instanceof Container container) starter.accept(container);
        return element;
    }

    @Override
    public String toString() {
        return "UiKind[" + id + "]";
    }
}
