package me.piitex.engine.property;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * Turns values of one type into JSON values and back, for saving {@link Property properties}. The JSON side uses the
 * shapes {@link me.piitex.engine.io.Json} reads and {@link me.piitex.engine.io.JsonWriter} writes: maps, lists,
 * strings, numbers (read back as {@link Double}), booleans and null.
 * <pre>{@code
 * PropertyTypes.register(Rect.class, new PropertyCodec<>() {
 *     public Object write(Rect r) {
 *         return List.of(r.x(), r.y(), r.width(), r.height());
 *     }
 *
 *     public Rect read(Object json) {
 *         List<?> v = (List<?>) json;
 *         return new Rect(PropertyCodec.toFloat(v.get(0)), PropertyCodec.toFloat(v.get(1)),
 *                 PropertyCodec.toFloat(v.get(2)), PropertyCodec.toFloat(v.get(3)));
 *     }
 * });
 * }</pre>
 * Null values never reach a codec: a null property is saved as JSON null and read back as null.
 */
public interface PropertyCodec<T> {
    /**
     * The value as JSON. Never called with null.
     */
    Object write(T value);

    /**
     * The value back from what {@link #write} produced. Never called with null.
     *
     * @throws IllegalArgumentException if the JSON is not in the shape this codec writes
     */
    T read(Object json);

    /**
     * A JSON number as a float, with a clear error for anything else.
     */
    static float toFloat(Object json) {
        if (json instanceof Number n) return n.floatValue();
        throw new IllegalArgumentException("expected a number, found " + describe(json));
    }

    /**
     * A short description of a JSON value for error messages.
     */
    static String describe(Object json) {
        if (json == null) return "null";
        if (json instanceof String s) return "\"" + s + "\"";
        if (json instanceof java.util.Map<?, ?>) return "an object";
        if (json instanceof java.util.List<?>) return "an array";
        return json.toString();
    }
}
