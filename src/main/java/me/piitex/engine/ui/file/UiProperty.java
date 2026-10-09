package me.piitex.engine.ui.file;

import me.piitex.engine.property.PropertyCodec;
import me.piitex.engine.property.PropertyTypes;
import me.piitex.engine.ui.Element;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One saved and editable value of a {@link UiKind}: its name in the file, its type, and how to read and change it on
 * an element. An editor builds a field for each from its {@link #getType() type} and {@link #getHint() hint}, and
 * undo needs only {@link #get} before a change and {@link #set} to put the old value back.
 * <p>
 * The types are {@link Boolean}, {@link Integer}, {@link Float}, {@link Double}, {@link String}, an enum,
 * {@link me.piitex.engine.ui.color.Color}, {@link me.piitex.engine.ui.color.Paint} (a color, a gradient, a rainbow),
 * {@link me.piitex.engine.ui.background.Background} (an effect behind the element) and {@link List} (of strings).
 * <p>
 * A <i>themed</i> property is one a theme gives a value until it is set on purpose, such as a button's background.
 * It is saved only once set, and {@link #reset} hands it back to the theme.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class UiProperty {
    /** What a text or path value stands for, so an editor can offer the right control. */
    public enum Hint {
        NONE,
        /** A path to an image file in the project. */
        IMAGE,
        /** The name of one of the built-in icons, as {@link UiIcons#names()} lists. */
        ICON
    }

    /** Changes a property, with the folder relative file paths are read from. */
    @FunctionalInterface
    interface Setter {
        void set(Element element, Object value, Path base);
    }

    private final String name;
    private final String label;
    private final Class<?> type;
    private final Function<Element, Object> getter;
    private final Setter setter;
    private String group = "";
    private String tooltip = "";
    private Hint hint = Hint.NONE;
    private double min = Double.NEGATIVE_INFINITY, max = Double.POSITIVE_INFINITY;
    private boolean themed;
    private boolean nullable;
    private boolean common;
    private Object omitted;
    private Consumer<Element> resetter;
    private Predicate<Element> applies = e -> true;

    UiProperty(String name, String label, Class<?> type, Function<Element, Object> getter, Setter setter) {
        this.name = name;
        this.label = label;
        this.type = type;
        this.getter = getter;
        this.setter = setter;
        if (!isList() && !PropertyTypes.isSupported(type)) throw new IllegalArgumentException("no codec for " + type);
    }

    UiProperty group(String group) {
        this.group = group;
        return this;
    }

    UiProperty tip(String tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    /** Gives the property a tooltip if it has none, such as one from {@link UiTips}. */
    void defaultTip(String tooltip) {
        if (this.tooltip.isEmpty()) this.tooltip = tooltip;
    }

    /** Marks the property as one every element has, which {@link UiDocs} explains once rather than for each kind. */
    UiProperty common() {
        this.common = true;
        return this;
    }

    UiProperty hint(Hint hint) {
        this.hint = hint;
        return this;
    }

    UiProperty range(double min, double max) {
        this.min = min;
        this.max = max;
        return this;
    }

    UiProperty themed() {
        this.themed = true;
        return this;
    }

    /** The property can be nothing: a text color on hover that isn't set, an effect that isn't there. */
    UiProperty nullable() {
        this.nullable = true;
        return this;
    }

    /** What handing a themed property back does to its element, where the theme doesn't reach it, such as clearing a tint. */
    UiProperty onReset(Consumer<Element> resetter) {
        this.resetter = resetter;
        return this;
    }

    /** Leaves the property out of a file while it has this value, as it is what a missing one gives. */
    UiProperty omitWhen(Object value) {
        this.omitted = value;
        return this;
    }

    UiProperty when(Predicate<Element> applies) {
        this.applies = applies;
        return this;
    }

    /** The name the property is saved under. */
    public String getName() {
        return name;
    }

    /** A name for people: "Corner Radius". */
    public String getLabel() {
        return label;
    }

    public Class<?> getType() {
        return type;
    }

    /** The section an editor shows it in: "Layout", "Style", "Text"... */
    public String getGroup() {
        return group;
    }

    public String getTooltip() {
        return tooltip;
    }

    public Hint getHint() {
        return hint;
    }

    /** The lowest value an editor offers, for numbers. Loading does not enforce it. */
    public double getMin() {
        return min;
    }

    /** The highest value an editor offers, for numbers. */
    public double getMax() {
        return max;
    }

    /** Whether every kind has it: the place, the size, the style. A kind's own properties are not. */
    public boolean isCommon() {
        return common;
    }

    public boolean isThemed() {
        return themed;
    }

    /** Whether the property can be nothing, which {@link #get} gives as null. */
    public boolean isNullable() {
        return nullable;
    }

    public boolean isNumber() {
        return Number.class.isAssignableFrom(type);
    }

    public boolean isList() {
        return type == List.class;
    }

    /**
     * Whether this property means anything for {@code element} as it is now: a position doesn't for an element in a
     * layout, which places it, and an offset doesn't for one that isn't anchored.
     */
    public boolean appliesTo(Element element) {
        return applies.test(element);
    }

    /** The value now, boxed. Null for an image that isn't set, or another property that can be nothing. */
    public Object get(Element element) {
        return getter.apply(element);
    }

    /**
     * Changes the property, and marks it as set on purpose. Numbers are converted to the property's number type.
     * Relative file paths are read from the working folder.
     */
    public void set(Element element, Object value) {
        set(element, value, Path.of(""));
    }

    /**
     * As {@link #set(Element, Object)}, reading relative file paths from {@code base}, which is the project's folder
     * in an editor.
     */
    public void set(Element element, Object value, Path base) {
        if (value instanceof Number n && !type.isInstance(value)) value = convert(n, type);
        setter.set(element, value, base);
        UiMeta.of(element).explicit.add(name);
    }

    /**
     * Whether it is set on purpose. A property that isn't themed always counts, as it is always saved.
     */
    public boolean isSet(Element element) {
        if (!themed) return true;
        UiMeta meta = UiMeta.peek(element);
        return meta != null && meta.explicit.contains(name);
    }

    /**
     * Hands a themed property back to the theme. The theme gives every explicit value on the element back, so the
     * others are set again after it, as they were. Does nothing to a property that isn't themed.
     */
    public void reset(Element element) {
        if (!themed) return;
        UiMeta meta = UiMeta.of(element);
        if (!meta.explicit.remove(name)) return;
        UiKind kind = UiKinds.of(element);
        List<UiProperty> keep = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (kind != null) {
            for (UiProperty p : kind.getProperties()) {
                if (p.themed && meta.explicit.contains(p.name)) {
                    keep.add(p);
                    values.add(p.get(element));
                }
            }
        }
        if (resetter != null) resetter.accept(element);
        element.followTheme();
        meta.explicit.clear();
        for (int i = 0; i < keep.size(); i++) {
            if (values.get(i) != null) keep.get(i).set(element, values.get(i));
        }
    }

    /** The value as JSON, or null if there is nothing to write. */
    Object write(Element element) {
        Object value = get(element);
        if (value == null || value.equals(omitted)) return null;
        if (isList()) return new ArrayList<>((List<?>) value);
        @SuppressWarnings("unchecked") PropertyCodec<Object> codec = (PropertyCodec<Object>) PropertyTypes.codecFor(type);
        return codec.write(value);
    }

    /** Sets the value from JSON written by {@link #write}. */
    @SuppressWarnings("unchecked")
    void read(Element element, Object json, Path base) {
        Object value;
        try {
            if (json == null) value = null;
            else if (isList()) {
                if (!(json instanceof List<?> list)) throw new IllegalArgumentException("expected a list, found " + PropertyCodec.describe(json));
                List<String> strings = new ArrayList<>();
                for (Object item : list) strings.add(String.valueOf(item));
                value = strings;
            } else {
                value = ((PropertyCodec<Object>) PropertyTypes.codecFor(type)).read(json);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("\"" + name + "\": " + e.getMessage(), e);
        }
        if (value == null && type.isPrimitive()) return;
        set(element, value, base);
    }

    private static Object convert(Number n, Class<?> type) {
        if (type == Integer.class) return n.intValue();
        if (type == Float.class) return n.floatValue();
        if (type == Double.class) return n.doubleValue();
        if (type == Long.class) return n.longValue();
        return n;
    }

    @Override
    public String toString() {
        return name + " (" + type.getSimpleName() + ")";
    }
}
