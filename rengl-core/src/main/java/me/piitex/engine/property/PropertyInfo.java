package me.piitex.engine.property;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.lang.reflect.Field;

/**
 * One {@link Property} of a class: its name, its type, and how to read and change it on an object. Get these from
 * {@link TypeInfo#getProperties()}. An editor builds a field for each one from its {@link #getType() type}, and undo
 * only needs {@link #get} before a change and {@link #set} to put the old value back.
 */
public final class PropertyInfo {
    private final String name;
    private final Field field;
    private final Property annotation;
    private final PropertyCodec<Object> codec;

    @SuppressWarnings("unchecked")
    PropertyInfo(Field field, Property annotation) {
        this.field = field;
        this.annotation = annotation;
        this.name = annotation.name().isEmpty() ? field.getName() : annotation.name();
        this.codec = (PropertyCodec<Object>) PropertyTypes.codecFor(field.getType());
    }

    /**
     * The name the property is saved under: {@link Property#name()}, or the field's name.
     */
    public String getName() {
        return name;
    }

    public Class<?> getType() {
        return field.getType();
    }

    /**
     * The class that declares the field, which may be a superclass of the one it was looked up on.
     */
    public Class<?> getDeclaringClass() {
        return field.getDeclaringClass();
    }

    public String getTooltip() {
        return annotation.tooltip();
    }

    public double getMin() {
        return annotation.min();
    }

    public double getMax() {
        return annotation.max();
    }

    /**
     * The property's value on {@code target}, boxed for primitives.
     */
    public Object get(Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Can't read property " + describe(), e);
        }
    }

    /**
     * Changes the property on {@code target}. Numbers are converted to the field's number type, so an {@link Integer}
     * can be set on a {@code float} field.
     *
     * @throws IllegalArgumentException if the value doesn't fit the field (null for a primitive, or the wrong type)
     */
    public void set(Object target, Object value) {
        Class<?> type = field.getType();
        if (value == null && type.isPrimitive()) {
            throw new IllegalArgumentException("Property " + describe() + " is a " + type + " and can't be null");
        }
        if (value instanceof Number n && !type.isInstance(value)) value = convertNumber(n, type);
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Can't change property " + describe(), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Property " + describe() + " can't hold a "
                    + value.getClass().getSimpleName(), e);
        }
    }

    /**
     * The property's value on {@code target} as JSON.
     */
    public Object write(Object target) {
        Object value = get(target);
        return value == null ? null : codec.write(value);
    }

    /**
     * Sets the property on {@code target} from JSON written by {@link #write}.
     *
     * @throws IllegalArgumentException if the JSON doesn't fit the property, naming it
     */
    public void read(Object target, Object json) {
        Object value;
        try {
            value = json == null ? null : codec.read(json);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Property " + describe() + ": " + e.getMessage(), e);
        }
        set(target, value);
    }

    private String describe() {
        return field.getDeclaringClass().getSimpleName() + "." + name;
    }

    private static Object convertNumber(Number n, Class<?> type) {
        if (type == int.class || type == Integer.class) return n.intValue();
        if (type == float.class || type == Float.class) return n.floatValue();
        if (type == double.class || type == Double.class) return n.doubleValue();
        if (type == long.class || type == Long.class) return n.longValue();
        if (type == short.class || type == Short.class) return n.shortValue();
        if (type == byte.class || type == Byte.class) return n.byteValue();
        return n;
    }

    @Override
    public String toString() {
        return describe() + " (" + field.getType().getSimpleName() + ")";
    }
}
