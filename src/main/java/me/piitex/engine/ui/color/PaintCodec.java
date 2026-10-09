package me.piitex.engine.ui.color;

import me.piitex.engine.property.PropertyCodec;
import me.piitex.engine.property.PropertyTypes;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves and loads a {@link Paint}, so a layout or a theme can hold a gradient where it holds a color.
 * <ul>
 *   <li>A solid color is written as a color is everywhere else, {@code [r, g, b, a]} or {@code "#RRGGBB"}, so a file
 *   from before a property took a paint still reads.</li>
 *   <li>A gradient is {@code {"type": "gradient", "colors": ["#9E1221", "#2A0A10"], "angle": 90}}: two or more colors
 *   spaced evenly, and the direction in degrees as {@link LinearGradient} says, which is left out for corner to corner.</li>
 *   <li>A gradient that moves has {@code "scroll"} as well, the points it moves each second along its line:
 *   {@code {"type": "gradient", "colors": [...], "angle": 0, "scroll": 60}}.</li>
 *   <li>A color that cycles through the wheel is {@code {"type": "rainbow", "seconds": 6, "saturation": 1,
 *   "brightness": 1, "alpha": 1}}.</li>
 * </ul>
 * {@link #JSON} writes colors as numbers and {@link #HEX} as hex text, which is how a theme file shows them. Both read
 * either. A paint of another kind, such as one an app made itself, can't be written and is left out of a file.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class PaintCodec implements PropertyCodec<Paint> {
    /** Colors as {@code [r, g, b, a]}. */
    public static final PaintCodec JSON = new PaintCodec(false);
    /** Colors as {@code "#RRGGBB"}, or {@code "#RRGGBBAA"} for one that is see-through. */
    public static final PaintCodec HEX = new PaintCodec(true);

    private final boolean hex;

    private PaintCodec(boolean hex) {
        this.hex = hex;
    }

    /** {@code #RRGGBB}, or {@code #RRGGBBAA} for a color that isn't fully opaque. */
    public static String hex(Color color) {
        String text = String.format("#%02X%02X%02X", channel(color.getR()), channel(color.getG()), channel(color.getB()));
        return color.getA() < 1f ? text + String.format("%02X", channel(color.getA())) : text;
    }

    private static int channel(float value) {
        return Math.clamp(Math.round(value * 255f), 0, 255);
    }

    /**
     * The paint as JSON, or null for one this can't write.
     */
    @Override
    public Object write(Paint paint) {
        switch (paint) {
            case RainbowColor rainbow -> {
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("type", "rainbow");
                json.put("seconds", rainbow.getCycleDuration());
                json.put("saturation", rainbow.getSaturation());
                json.put("brightness", rainbow.getBrightness());
                json.put("alpha", rainbow.getAlpha());
                if (rainbow.getHueOffset() != 0f) json.put("hueOffset", rainbow.getHueOffset());
                return json;
            }
            case Color color -> {
                return color(color);
            }
            case LinearGradient gradient -> {
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("type", "gradient");
                List<Object> colors = new ArrayList<>();
                for (Color color : gradient.getColors()) colors.add(color(color));
                json.put("colors", colors);
                if (gradient.hasAngle()) json.put("angle", gradient.getAngle());
                if (gradient instanceof ScrollingGradient scrolling) json.put("scroll", scrolling.getSpeed());
                return json;
            }
            default -> {
                return null;
            }
        }
    }

    private Object color(Color color) {
        if (hex) return hex(color);
        return PropertyTypes.codecFor(Color.class).write(color);
    }

    @Override
    public Paint read(Object json) {
        if (json instanceof Map<?, ?> map) return readObject(map);
        if (json instanceof String || json instanceof List<?>) return readColor(json);
        throw new IllegalArgumentException("expected a color or a gradient, found " + PropertyCodec.describe(json));
    }

    private static Color readColor(Object json) {
        return (Color) PropertyTypes.codecFor(Color.class).read(json);
    }

    private static Paint readObject(Map<?, ?> json) {
        Object type = json.get("type");
        if ("gradient".equals(type)) {
            if (!(json.get("colors") instanceof List<?> list) || list.size() < 2) {
                throw new IllegalArgumentException("a gradient needs \"colors\", a list of at least two");
            }
            List<Color> colors = new ArrayList<>();
            for (Object item : list) colors.add(readColor(item));
            float angle = json.get("angle") == null ? Float.NaN : PropertyCodec.toFloat(json.get("angle"));
            if (json.get("scroll") == null) return new LinearGradient(colors, angle);
            return new ScrollingGradient(colors, angle, PropertyCodec.toFloat(json.get("scroll")));
        }
        if ("rainbow".equals(type)) {
            RainbowColor rainbow = new RainbowColor(
                    number(json, "seconds", 6f), number(json, "saturation", 1f), number(json, "brightness", 1f), number(json, "alpha", 1f));
            rainbow.setHueOffset(number(json, "hueOffset", 0f));
            return rainbow;
        }
        throw new IllegalArgumentException("expected a color, or an object whose \"type\" is gradient or rainbow, found "
                + (type == null ? "an object with no \"type\"" : PropertyCodec.describe(type)));
    }

    private static float number(Map<?, ?> json, String key, float fallback) {
        return json.get(key) == null ? fallback : PropertyCodec.toFloat(json.get(key));
    }
}
