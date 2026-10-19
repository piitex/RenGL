package me.piitex.engine.render3d.scene;

/**
 * How a point or spot light fades with distance. Set one per light with {@link Light#setFalloff}; directional lights
 * don't fade. Every one of them reaches 0 at the light's range or sooner, except {@link #NONE}.
 * <p>
 * A custom falloff supplies the <em>body</em> of a GLSL function that returns the share of light left, from 0 to 1,
 * with {@code float dist} (the distance from the light) and {@code float range} (the light's range) in scope:
 * <pre>{@code
 * LightFalloff smooth = LightFalloff.custom("smooth", "float t = clamp(1.0 - dist / range, 0.0, 1.0); return t * t * (3.0 - 2.0 * t);");
 * }</pre>
 * The scene's shader is built to hold exactly the falloffs its lights use.
 */
public interface LightFalloff {
    /**
     * Inverse-square-like and smoothly windowed to nothing at the range: the default, and what most game engines do.
     */
    LightFalloff WINDOWED_INVERSE_SQUARE = new BuiltinLightFalloff("windowedInverseSquare", 0);
    /**
     * Straight-line fade from full strength at the light to nothing at the range.
     */
    LightFalloff LINEAR = new BuiltinLightFalloff("linear", 1);
    /**
     * Full strength everywhere: the light never fades and ignores its range, as for a light very far away.
     */
    LightFalloff NONE = new BuiltinLightFalloff("none", 2);

    /**
     * A falloff written in GLSL; see the description of this interface.
     *
     * @param name a short label, unique among your falloffs (letters and digits)
     */
    static LightFalloff custom(String name, String glslBody) {
        return new CustomLightFalloff(name, glslBody);
    }

    String name();

    /**
     * The GLSL body of a custom falloff, or {@code null} for the built-in ones.
     */
    default String glsl() {
        return null;
    }
}
