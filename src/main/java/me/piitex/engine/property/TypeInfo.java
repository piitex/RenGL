package me.piitex.engine.property;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link Property properties} of a class, found once and kept. This is what saving, loading and an editor's
 * inspector all work from.
 * <pre>{@code
 * TypeInfo info = TypeInfo.of(Health.class);
 * for (PropertyInfo p : info.getProperties()) {
 *     System.out.println(p.getName() + " = " + p.get(health));
 * }
 *
 * Map<String, Object> json = info.write(health);   // {"max": 100, "regenPerSecond": 2}
 * info.read(otherHealth, json);                   // copies them onto another object
 * }</pre>
 * Properties are listed superclass first, then in the order the fields are declared. A class inherits its
 * superclasses' properties, and two properties of one class can't share a name.
 * <p>
 * The information is kept per class in a {@link ClassValue}, so a class from a game's own class loader, as an editor
 * loads one, is let go together with that loader.
 */
public final class TypeInfo {
    private static final ClassValue<TypeInfo> cache = new ClassValue<>() {
        @Override
        protected TypeInfo computeValue(Class<?> type) {
            return new TypeInfo(type);
        }
    };

    private final Class<?> type;
    private final List<PropertyInfo> properties;
    private final Map<String, PropertyInfo> byName = new HashMap<>();

    private TypeInfo(Class<?> type) {
        this.type = type;
        List<PropertyInfo> found = new ArrayList<>();
        collect(type, found);
        for (PropertyInfo p : found) {
            if (byName.putIfAbsent(p.getName(), p) != null) {
                throw new IllegalStateException(type.getName() + " has two properties named \"" + p.getName() + "\"");
            }
        }
        this.properties = Collections.unmodifiableList(found);
    }

    /**
     * The properties of {@code type}.
     *
     * @throws IllegalStateException if a {@link Property} field is static or final, has a type
     *                               {@link PropertyTypes} doesn't know, or shares its name with another property
     */
    public static TypeInfo of(Class<?> type) {
        return cache.get(type);
    }

    private static void collect(Class<?> type, List<PropertyInfo> into) {
        if (type == null || type == Object.class) return;
        collect(type.getSuperclass(), into);
        // getDeclaredFields is in declaration order on every JVM in use, though the spec doesn't promise it.
        for (Field field : type.getDeclaredFields()) {
            Property annotation = field.getAnnotation(Property.class);
            if (annotation == null) continue;
            String where = type.getName() + "." + field.getName();
            if (Modifier.isStatic(field.getModifiers())) {
                throw new IllegalStateException("@Property field " + where + " must not be static");
            }
            if (Modifier.isFinal(field.getModifiers())) {
                throw new IllegalStateException("@Property field " + where + " must not be final");
            }
            if (!PropertyTypes.isSupported(field.getType())) {
                throw new IllegalStateException("@Property field " + where + " has type "
                        + field.getType().getName() + ", which has no codec. Register one with PropertyTypes.register");
            }
            try {
                field.setAccessible(true);
            } catch (RuntimeException e) {
                throw new IllegalStateException("@Property field " + where + " can't be accessed. If the class is in a"
                        + " named module, open its package to me.piitex.engine", e);
            }
            into.add(new PropertyInfo(field, annotation));
        }
    }

    public Class<?> getType() {
        return type;
    }

    public List<PropertyInfo> getProperties() {
        return properties;
    }

    /**
     * The property with this saved name, or null.
     */
    public PropertyInfo getProperty(String name) {
        return byName.get(name);
    }

    /**
     * Every property of {@code target} as JSON, in {@link #getProperties()} order.
     */
    public Map<String, Object> write(Object target) {
        Map<String, Object> json = new LinkedHashMap<>();
        for (PropertyInfo p : properties) json.put(p.getName(), p.write(target));
        return json;
    }

    /**
     * Sets the properties of {@code target} found in {@code json}. Properties missing from it keep their current
     * value, and keys that match no property are ignored, so a file saved before a property was added or removed
     * still loads.
     *
     * @throws IllegalArgumentException if a value doesn't fit its property, naming it
     */
    public void read(Object target, Map<?, ?> json) {
        for (PropertyInfo p : properties) {
            if (json.containsKey(p.getName())) p.read(target, json.get(p.getName()));
        }
    }

    @Override
    public String toString() {
        return "TypeInfo[" + type.getName() + ", " + properties.size() + " properties]";
    }
}
