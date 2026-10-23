package me.piitex.engine.property;

import me.piitex.engine.math.Vec2;
import me.piitex.engine.ui.background.Background;
import me.piitex.engine.ui.background.BackgroundKinds;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.PaintCodec;
import me.piitex.engine.utils.flags.GeneratedClass;
import me.piitex.engine.utils.flags.GeneratedFragments;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The types a {@link Property} can have, each with the {@link PropertyCodec} that saves and loads it.
 * <p>
 * Built in:
 * <table>
 *   <tr><th>Type</th><th>Saved as</th></tr>
 *   <tr><td>{@code int}, {@code long}, {@code short}, {@code byte}, {@code float}, {@code double} and their boxes</td><td>a number</td></tr>
 *   <tr><td>{@code boolean}, {@link Boolean}</td><td>true or false</td></tr>
 *   <tr><td>{@code char}, {@link Character}, {@link String}</td><td>a string</td></tr>
 *   <tr><td>any enum</td><td>the constant's name, {@code "EASE_IN"}</td></tr>
 *   <tr><td>{@link Color}</td><td>{@code [r, g, b, a]}, each 0 to 1; {@code "#RRGGBB"} and {@code "#RRGGBBAA"} are also read</td></tr>
 *   <tr><td>{@link Paint}</td><td>a color as above, or a gradient or rainbow as an object; see {@link PaintCodec}</td></tr>
 *   <tr><td>{@link Background}</td><td>an object with the {@code "type"} of one of the {@link BackgroundKinds kinds} and its settings; one of another class is not written</td></tr>
 *   <tr><td>{@link Vec2}</td><td>{@code [x, y]}</td></tr>
 *   <tr><td>{@link TextureRef}</td><td>the image's path</td></tr>
 * </table>
 * Add others with {@link #register}. A codec registered for a class is used for fields of exactly that class, not
 * for its subclasses.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class PropertyTypes {
    private static final Map<Class<?>, PropertyCodec<?>> codecs = new ConcurrentHashMap<>();

    static {
        PropertyCodec<Integer> intCodec = number(Number::intValue);
        codecs.put(int.class, intCodec);
        codecs.put(Integer.class, intCodec);
        PropertyCodec<Long> longCodec = number(Number::longValue);
        codecs.put(long.class, longCodec);
        codecs.put(Long.class, longCodec);
        PropertyCodec<Short> shortCodec = number(Number::shortValue);
        codecs.put(short.class, shortCodec);
        codecs.put(Short.class, shortCodec);
        PropertyCodec<Byte> byteCodec = number(Number::byteValue);
        codecs.put(byte.class, byteCodec);
        codecs.put(Byte.class, byteCodec);
        PropertyCodec<Float> floatCodec = number(Number::floatValue);
        codecs.put(float.class, floatCodec);
        codecs.put(Float.class, floatCodec);
        PropertyCodec<Double> doubleCodec = number(Number::doubleValue);
        codecs.put(double.class, doubleCodec);
        codecs.put(Double.class, doubleCodec);

        PropertyCodec<Boolean> booleanCodec = new PropertyCodec<>() {
            public Object write(Boolean value) {
                return value;
            }

            public Boolean read(Object json) {
                if (json instanceof Boolean b) return b;
                throw new IllegalArgumentException("expected true or false, found " + PropertyCodec.describe(json));
            }
        };
        codecs.put(boolean.class, booleanCodec);
        codecs.put(Boolean.class, booleanCodec);

        PropertyCodec<Character> charCodec = new PropertyCodec<>() {
            public Object write(Character value) {
                return value.toString();
            }

            public Character read(Object json) {
                if (json instanceof String s && s.length() == 1) return s.charAt(0);
                throw new IllegalArgumentException("expected a one-letter string, found " + PropertyCodec.describe(json));
            }
        };
        codecs.put(char.class, charCodec);
        codecs.put(Character.class, charCodec);

        codecs.put(String.class, new PropertyCodec<String>() {
            public Object write(String value) {
                return value;
            }

            public String read(Object json) {
                if (json instanceof String s) return s;
                throw new IllegalArgumentException("expected a string, found " + PropertyCodec.describe(json));
            }
        });

        codecs.put(Color.class, new PropertyCodec<Color>() {
            public Object write(Color c) {
                return List.of(c.getR(), c.getG(), c.getB(), c.getA());
            }

            public Color read(Object json) {
                if (json instanceof String hex) return parseHex(hex);
                if (json instanceof List<?> v && (v.size() == 3 || v.size() == 4)) {
                    float a = v.size() == 4 ? PropertyCodec.toFloat(v.get(3)) : 1f;
                    return new Color(PropertyCodec.toFloat(v.get(0)), PropertyCodec.toFloat(v.get(1)),
                            PropertyCodec.toFloat(v.get(2)), a);
                }
                throw new IllegalArgumentException("expected [r, g, b, a] or \"#RRGGBB\", found " + PropertyCodec.describe(json));
            }
        });

        codecs.put(Paint.class, PaintCodec.JSON);

        codecs.put(Background.class, new PropertyCodec<Background>() {
            public Object write(Background value) {
                return BackgroundKinds.write(value);
            }

            public Background read(Object json) {
                return BackgroundKinds.read(json, java.nio.file.Path.of(""));
            }
        });

        codecs.put(Vec2.class, new PropertyCodec<Vec2>() {
            public Object write(Vec2 v) {
                return List.of(v.x(), v.y());
            }

            public Vec2 read(Object json) {
                if (json instanceof List<?> v && v.size() == 2) {
                    return new Vec2(PropertyCodec.toFloat(v.get(0)), PropertyCodec.toFloat(v.get(1)));
                }
                throw new IllegalArgumentException("expected [x, y], found " + PropertyCodec.describe(json));
            }
        });

        codecs.put(TextureRef.class, new PropertyCodec<TextureRef>() {
            public Object write(TextureRef value) {
                return value.getPath();
            }

            public TextureRef read(Object json) {
                if (json instanceof String path && !path.isEmpty()) return TextureRef.of(path);
                throw new IllegalArgumentException("expected an image path, found " + PropertyCodec.describe(json));
            }
        });
    }

    private PropertyTypes() {
    }

    /**
     * Lets {@link Property} fields have this type, saved and loaded with {@code codec}. Replaces any codec already
     * registered for it, built-in ones included.
     */
    public static <T> void register(Class<T> type, PropertyCodec<T> codec) {
        if (type == null || codec == null) throw new IllegalArgumentException("type and codec must not be null");
        codecs.put(type, codec);
    }

    /**
     * Whether {@link Property} fields can have this type.
     */
    public static boolean isSupported(Class<?> type) {
        return codecFor(type) != null;
    }

    /**
     * The codec for the type, or null if it has none. Enums get one on first use.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> PropertyCodec<T> codecFor(Class<T> type) {
        PropertyCodec<?> codec = codecs.get(type);
        if (codec == null && type.isEnum()) {
            codec = enumCodec((Class) type);
            // Enums from a game's own class loader are not kept, so an editor can drop that loader on a rebuild.
            if (type.getClassLoader() == PropertyTypes.class.getClassLoader()) codecs.put(type, codec);
        }
        return (PropertyCodec<T>) codec;
    }

    private static <E extends Enum<E>> PropertyCodec<E> enumCodec(Class<E> type) {
        return new PropertyCodec<>() {
            public Object write(E value) {
                return value.name();
            }

            public E read(Object json) {
                if (json instanceof String name) {
                    try {
                        return Enum.valueOf(type, name);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException(type.getSimpleName() + " has no constant " + name);
                    }
                }
                throw new IllegalArgumentException("expected the name of a " + type.getSimpleName() + ", found " + PropertyCodec.describe(json));
            }
        };
    }

    private static <N extends Number> PropertyCodec<N> number(java.util.function.Function<Number, N> convert) {
        return new PropertyCodec<>() {
            public Object write(N value) {
                return value;
            }

            public N read(Object json) {
                if (json instanceof Number n) return convert.apply(n);
                throw new IllegalArgumentException("expected a number, found " + PropertyCodec.describe(json));
            }
        };
    }

    private static Color parseHex(String hex) {
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        if (digits.length() != 6 && digits.length() != 8) {
            throw new IllegalArgumentException("expected \"#RRGGBB\" or \"#RRGGBBAA\", found \"" + hex + "\"");
        }
        try {
            long value = Long.parseLong(digits, 16);
            if (digits.length() == 6) value = (value << 8) | 0xFF;
            return new Color(((value >> 24) & 0xFF) / 255f, ((value >> 16) & 0xFF) / 255f,
                    ((value >> 8) & 0xFF) / 255f, (value & 0xFF) / 255f);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad color \"" + hex + "\"");
        }
    }
}
