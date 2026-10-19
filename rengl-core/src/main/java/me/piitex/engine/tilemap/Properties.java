package me.piitex.engine.tilemap;

import me.piitex.engine.ui.color.Color;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The custom properties set in Tiled on a map, layer, tile set, tile or object: named values the game reads, such
 * as a door's destination or an enemy's speed.
 * <pre>{@code
 * float speed = enemy.getProperties().getFloat("speed", 80f);
 * if (tile.getProperties().getBoolean("deadly", false)) player.die();
 * MapObject target = door.getProperties().getObject("leadsTo");
 * }</pre>
 * Each getter takes a default, returned when the property isn't set. The getters convert where it makes sense, so a
 * whole {@code float} reads with {@link #getInt} and any value reads with {@link #getString}, and throw an
 * {@link IllegalArgumentException} naming the property when a value can't be read as asked, such as a string that
 * isn't a number.
 * <p>
 * Members of a custom class that were left at their default aren't saved by Tiled, so they read as not set and the
 * getter's default applies, unless the map was loaded with its {@link TiledProject}, which supplies them. Immutable.
 */
public final class Properties {
    static final Properties EMPTY = new Properties(Map.of(), null);

    private final Map<String, Object> values;
    private final TileMap map;

    /**
     * An object property: resolved to the object when read, since the object may be loaded after the property.
     */
    record ObjectId(int id) {
    }

    Properties(Map<String, Object> values, TileMap map) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.map = map;
    }

    /**
     * The values as stored, for building a class on top of another.
     */
    Map<String, Object> raw() {
        return values;
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    /**
     * The names of every property set, in the order Tiled saved them.
     */
    public Set<String> getNames() {
        return values.keySet();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * The value as loaded, or null if it isn't set: a {@link String}, {@link Integer}, {@link Float},
     * {@link Boolean}, {@link Color}, {@link Path} for a file, {@link MapObject} for an object (null if it points
     * nowhere), or {@link Properties} for a custom class. Members of a class saved in a JSON map carry no type, so
     * their numbers load as {@link Integer} when whole and {@link Float} otherwise.
     */
    public Object get(String name) {
        Object value = values.get(name);
        return value instanceof ObjectId id ? resolve(id) : value;
    }

    /**
     * The value as text, or null if it isn't set. Any type reads: a number as its digits, a color as
     * {@code #AARRGGBB}, a file as its path.
     */
    public String getString(String name) {
        return getString(name, null);
    }

    public String getString(String name, String defaultValue) {
        Object value = values.get(name);
        if (value == null) return defaultValue;
        if (value instanceof Color color) return String.format("#%08X", color.toARGB());
        if (value instanceof ObjectId id) return Integer.toString(id.id());
        return String.valueOf(value);
    }

    public int getInt(String name, int defaultValue) {
        Object value = values.get(name);
        if (value == null) return defaultValue;
        double number = number(name, value);
        if (number != Math.rint(number) || Math.abs(number) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Property '" + name + "' is " + value + ", not a whole number");
        }
        return (int) number;
    }

    public float getFloat(String name, float defaultValue) {
        Object value = values.get(name);
        return value == null ? defaultValue : (float) number(name, value);
    }

    /**
     * A {@code bool} property. The text "true" or "false" and the numbers 1 and 0 read too.
     */
    public boolean getBoolean(String name, boolean defaultValue) {
        Object value = values.get(name);
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            if (s.equalsIgnoreCase("true") || s.equals("1")) return true;
            if (s.equalsIgnoreCase("false") || s.equals("0")) return false;
        }
        if (value instanceof Number n && (n.doubleValue() == 0 || n.doubleValue() == 1)) return n.doubleValue() == 1;
        throw new IllegalArgumentException("Property '" + name + "' is " + value + ", not true or false");
    }

    /**
     * A {@code color} property, or null if it isn't set. Text like {@code #FF8800} or {@code #80FF8800} reads too.
     */
    public Color getColor(String name) {
        return getColor(name, null);
    }

    public Color getColor(String name, Color defaultValue) {
        Object value = values.get(name);
        if (value == null) return defaultValue;
        if (value instanceof Color color) return color;
        if (value instanceof String s) {
            Color color = TiledReader.parseColor(s);
            if (color != null) return color;
        }
        throw new IllegalArgumentException("Property '" + name + "' is " + value + ", not a color");
    }

    /**
     * A {@code file} property, resolved against the folder of the file it was set in, or null if it isn't set.
     */
    public Path getFile(String name) {
        Object value = values.get(name);
        if (value == null || value instanceof Path) return (Path) value;
        throw new IllegalArgumentException("Property '" + name + "' is " + value + ", not a file");
    }

    /**
     * The object an {@code object} property points to, or null if it isn't set, points nowhere, or is set on a tile
     * set, where there is no map to look in.
     */
    public MapObject getObject(String name) {
        Object value = values.get(name);
        if (value == null) return null;
        if (value instanceof ObjectId id) return resolve(id);
        throw new IllegalArgumentException("Property '" + name + "' is " + value + ", not an object");
    }

    /**
     * The members of a property of a custom class, empty if it isn't set.
     */
    public Properties getMembers(String name) {
        Object value = values.get(name);
        if (value == null) return EMPTY;
        if (value instanceof Properties members) return members;
        throw new IllegalArgumentException("Property '" + name + "' is " + value + ", not a class");
    }

    private MapObject resolve(ObjectId id) {
        return map == null || id.id() == 0 ? null : map.getObject(id.id());
    }

    private static double number(String name, Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // falls through to the error below
            }
        }
        throw new IllegalArgumentException("Property '" + name + "' is " + value + ", not a number");
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
