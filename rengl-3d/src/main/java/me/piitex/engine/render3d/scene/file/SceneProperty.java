package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.property.PropertyCodec;
import me.piitex.engine.property.PropertyTypes;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One saved and editable value of a part of a 3D scene: a node's position, a light's color, the scene's fog. It has its
 * name in the file, its type, and how to read and change it on the {@code T} it belongs to. An editor builds a field for
 * each from its {@link #getType() type} and {@link #getHint() hint}, and undo needs only {@link #get} before a change
 * and {@link #set} to put the old value back.
 * <p>
 * The types are {@link Boolean}, {@link Integer}, {@link Float}, {@link String}, an enum,
 * {@link me.piitex.engine.ui.color.Color} and {@link Vec3}. A value that is not set, such as an image that has none, is
 * an empty string, never null.
 *
 * @param <T> what the property is of: a {@link me.piitex.engine.render3d.scene.Node3D}, a
 *            {@link me.piitex.engine.render3d.scene.Material}, a {@link me.piitex.engine.render3d.scene.Light}, a
 *            {@link me.piitex.engine.render3d.scene.Camera} or the {@link me.piitex.engine.render3d.scene.Scene3D}
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class SceneProperty<T> {
    /** What a text value stands for, so an editor can offer the right control. */
    public enum Hint {
        NONE,
        /** A path to an image file in the project. */
        IMAGE,
        /** A path to a model file in the project: glTF, GLB, OBJ, FBX or Collada. */
        MODEL,
        /** The name of one of the animations the node's model has, as {@link Scene3DFile#animations} lists. */
        ANIMATION
    }

    /** Changes a property, with the folder relative file paths are read from. */
    @FunctionalInterface
    interface Setter<T> {
        void set(T target, Object value, Path base);
    }

    private final String name;
    private final String label;
    private final Class<?> type;
    private final Function<T, Object> getter;
    private final Setter<T> setter;
    private String group = "";
    private String tooltip = "";
    private Hint hint = Hint.NONE;
    private double min = Double.NEGATIVE_INFINITY, max = Double.POSITIVE_INFINITY;
    private double step = 0;
    private Object omitted;
    private Predicate<T> applies = t -> true;

    SceneProperty(String name, String label, Class<?> type, Function<T, Object> getter, Setter<T> setter) {
        this.name = name;
        this.label = label;
        this.type = type;
        this.getter = getter;
        this.setter = setter;
        if (type != Vec3.class && !PropertyTypes.isSupported(type)) throw new IllegalArgumentException("no codec for " + type);
    }

    SceneProperty<T> group(String group) {
        this.group = group;
        return this;
    }

    SceneProperty<T> tip(String tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    /** Gives the property a tooltip if it has none. */
    void defaultTip(String tooltip) {
        if (this.tooltip.isEmpty()) this.tooltip = tooltip;
    }

    SceneProperty<T> hint(Hint hint) {
        this.hint = hint;
        return this;
    }

    SceneProperty<T> range(double min, double max) {
        this.min = min;
        this.max = max;
        return this;
    }

    /** How much one step of an editor's arrows or a drag changes a number. */
    SceneProperty<T> step(double step) {
        this.step = step;
        return this;
    }

    /** Leaves the property out of a file while it has this value, as it is what a missing one gives. */
    SceneProperty<T> omitWhen(Object value) {
        this.omitted = value;
        return this;
    }

    SceneProperty<T> when(Predicate<T> applies) {
        this.applies = applies;
        return this;
    }

    /** The name the property is saved under. */
    public String getName() {
        return name;
    }

    /** A name for people: "Cast Shadow". */
    public String getLabel() {
        return label;
    }

    public Class<?> getType() {
        return type;
    }

    /** The section an editor shows it in: "Transform", "Surface"... */
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

    /** How much one step of an editor's arrows changes a number, or 0 to leave it to the editor. */
    public double getStep() {
        return step;
    }

    public boolean isNumber() {
        return Number.class.isAssignableFrom(type);
    }

    /**
     * Whether this property means anything for {@code target} as it is now: a sphere's radius doesn't for a cube, and a
     * cone's angles don't for a point light.
     */
    public boolean appliesTo(T target) {
        return applies.test(target);
    }

    /** The value now, boxed. */
    public Object get(T target) {
        return getter.apply(target);
    }

    /**
     * Changes the property. Numbers are converted to the property's number type. Relative file paths are read from the
     * working folder.
     */
    public void set(T target, Object value) {
        set(target, value, Path.of(""));
    }

    /**
     * As {@link #set(Object, Object)}, reading relative file paths from {@code base}, which is the project's folder in
     * an editor.
     */
    public void set(T target, Object value, Path base) {
        if (value instanceof Number n && !type.isInstance(value)) value = convert(n, type);
        setter.set(target, value, base);
    }

    /** The value as JSON, or null if there is nothing to write. */
    Object write(T target) {
        Object value = get(target);
        if (value == null) return null;
        Object json = encode(value);
        return omitted != null && json.equals(encode(omitted)) ? null : json;
    }

    // Compared as JSON, as a Color has no equals of its own.
    @SuppressWarnings("unchecked")
    private Object encode(Object value) {
        if (type == Vec3.class) {
            Vec3 v = (Vec3) value;
            return List.of(tidy(v.x()), tidy(v.y()), tidy(v.z()));
        }
        Object json = ((PropertyCodec<Object>) PropertyTypes.codecFor(type)).write(value);
        if (json instanceof Float f) return tidy(f);
        if (json instanceof List<?> list && type == me.piitex.engine.ui.color.Color.class) {
            List<Object> tidied = new java.util.ArrayList<>();
            for (Object item : list) tidied.add(item instanceof Float f ? (Object) tidy(f) : item);
            return tidied;
        }
        return json;
    }

    // Six decimals is more than a float is good for, and keeps 0.1 from being written as 0.10000000149 and a turn of
    // nothing as -0.
    private static float tidy(float value) {
        float rounded = (float) (Math.round((double) value * 1e6) / 1e6);
        return rounded == 0f ? 0f : rounded;
    }

    /** Sets the value from JSON written by {@link #write}. */
    @SuppressWarnings("unchecked")
    void read(T target, Object json, Path base) {
        Object value;
        try {
            if (type == Vec3.class) {
                if (!(json instanceof List<?> v) || v.size() != 3) {
                    throw new IllegalArgumentException("expected [x, y, z], found " + PropertyCodec.describe(json));
                }
                value = new Vec3(PropertyCodec.toFloat(v.get(0)), PropertyCodec.toFloat(v.get(1)), PropertyCodec.toFloat(v.get(2)));
            } else {
                value = ((PropertyCodec<Object>) PropertyTypes.codecFor(type)).read(json);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("\"" + name + "\": " + e.getMessage(), e);
        }
        set(target, value, base);
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
