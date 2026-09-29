package me.piitex.engine.io;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.Collection;
import java.util.Map;

/**
 * Writes JSON, the counterpart of {@link Json}. It takes the same shapes {@link Json#parse} gives back: {@link Map}s
 * with string keys become objects (in the map's own key order, so use a {@link java.util.LinkedHashMap} to control
 * it), {@link Collection}s and arrays of objects become arrays, and the rest must be a {@link String},
 * {@link Number}, {@link Boolean}, {@link Character} or null.
 * <pre>{@code
 * Map<String, Object> save = new LinkedHashMap<>();
 * save.put("level", 3);
 * save.put("position", List.of(120.5f, 64f));
 * Files.writeString(path, JsonWriter.write(save));
 * }</pre>
 * Numbers are written as short as they can be read back exactly: {@code 2f} as {@code 2}, and {@code 0.1f} as
 * {@code 0.1} rather than the {@code 0.10000000149011612} a float widened to a double would give. NaN and the
 * infinities have no JSON form and are refused.
 */
public final class JsonWriter {
    private final StringBuilder out = new StringBuilder();
    private final String indent;

    private JsonWriter(String indent) {
        this.indent = indent;
    }

    /**
     * The value as JSON, indented two spaces per level with one entry per line, for files people read and diff.
     *
     * @throws IllegalArgumentException for a value JSON can't hold, naming where it is
     */
    public static String write(Object value) {
        JsonWriter writer = new JsonWriter("  ");
        writer.value(value, 0, "$");
        return writer.out.toString();
    }

    /**
     * The value as JSON on one line, with no spaces.
     *
     * @throws IllegalArgumentException for a value JSON can't hold, naming where it is
     */
    public static String writeCompact(Object value) {
        JsonWriter writer = new JsonWriter(null);
        writer.value(value, 0, "$");
        return writer.out.toString();
    }

    private void value(Object value, int depth, String where) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            string(s);
        } else if (value instanceof Character c) {
            string(c.toString());
        } else if (value instanceof Boolean b) {
            out.append(b.booleanValue());
        } else if (value instanceof Number n) {
            number(n, where);
        } else if (value instanceof Map<?, ?> map) {
            object(map, depth, where);
        } else if (value instanceof Collection<?> list) {
            array(list.toArray(), depth, where);
        } else if (value instanceof Object[] array) {
            array(array, depth, where);
        } else {
            throw new IllegalArgumentException("Can't write a " + value.getClass().getName() + " as JSON at " + where);
        }
    }

    private void object(Map<?, ?> map, int depth, String where) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("JSON object keys must be strings, found " + entry.getKey() + " at " + where);
            }
            if (!first) out.append(',');
            first = false;
            newline(depth + 1);
            string(key);
            out.append(indent == null ? ":" : ": ");
            value(entry.getValue(), depth + 1, where + "." + key);
        }
        newline(depth);
        out.append('}');
    }

    private void array(Object[] items, int depth, String where) {
        if (items.length == 0) {
            out.append("[]");
            return;
        }
        // Short arrays of plain values, such as a position or a color, stay on one line.
        boolean inline = items.length <= 4;
        for (Object item : items) {
            if (item instanceof Map<?, ?> || item instanceof Collection<?> || item instanceof Object[]) inline = false;
        }
        out.append('[');
        for (int i = 0; i < items.length; i++) {
            if (i > 0) out.append(inline && indent != null ? ", " : ",");
            if (!inline) newline(depth + 1);
            value(items[i], depth + 1, where + "[" + i + "]");
        }
        if (!inline) newline(depth);
        out.append(']');
    }

    private void number(Number n, String where) {
        if (n instanceof Float f) {
            if (f.isNaN() || f.isInfinite()) throw new IllegalArgumentException("JSON can't hold " + f + " at " + where);
            // Float.toString is the shortest text that reads back as the same float.
            out.append(trimZero(Float.toString(f)));
        } else if (n instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) throw new IllegalArgumentException("JSON can't hold " + d + " at " + where);
            out.append(trimZero(Double.toString(d)));
        } else {
            out.append(n);
        }
    }

    // "2.0" -> "2", "1.0E10" -> "1.0E10" (left alone, still valid JSON)
    private static String trimZero(String text) {
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    private void string(String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private void newline(int depth) {
        if (indent == null) return;
        out.append('\n');
        out.append(indent.repeat(depth));
    }
}
