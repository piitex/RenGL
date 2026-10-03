package me.piitex.engine.render3d.scene;

/**
 * How the soft edge of a shadow is worked out from the shadow map: one lookup per pixel ({@link #HARD}), a few
 * ({@link #PCF}) or many ({@link #SOFT}). Choose one per light with {@link Shadows#filter}, or write your own.
 * <p>
 * A custom filter supplies the <em>body</em> of a GLSL function that returns how much of the light reaches the point,
 * from 0 (in shadow) to 1 (lit). These are in scope:
 * <ul>
 *   <li>{@code vec2 uv}: where the point falls in the shadow map;</li>
 *   <li>{@code float depth}: the point's own depth in the map, with the bias already applied;</li>
 *   <li>{@code vec2 texel}: the size of one shadow map pixel in {@code uv} units;</li>
 *   <li>{@code float softness}: the light's {@link Shadows#softness} (1 by default);</li>
 *   <li>{@code float shadowLit(vec2 tap, float depth)}: 1 if a point at {@code depth} is lit at the map position {@code tap}, else 0.
 *       It keeps the lookup inside the light's own part of the map, so use it for every sample.</li>
 * </ul>
 * For example, a 5-tap cross:
 * <pre>{@code
 * ShadowFilter cross = ShadowFilter.custom("cross", """
 *         float s = shadowLit(uv, depth);
 *         s += shadowLit(uv + vec2(texel.x, 0.0) * softness, depth);
 *         s += shadowLit(uv - vec2(texel.x, 0.0) * softness, depth);
 *         s += shadowLit(uv + vec2(0.0, texel.y) * softness, depth);
 *         s += shadowLit(uv - vec2(0.0, texel.y) * softness, depth);
 *         return s * 0.2;
 *         """);
 * }</pre>
 * The scene's shader is built to hold exactly the filters its lights use, so an unused filter costs nothing.
 */
public interface ShadowFilter {
    /**
     * One lookup: crisp, blocky edges, the cheapest.
     */
    ShadowFilter HARD = new BuiltinShadowFilter("hard", 0);
    /**
     * A 3x3 bilinear-weighted blur: the default.
     */
    ShadowFilter PCF = new BuiltinShadowFilter("pcf", 1);
    /**
     * A wider blur (5x5) for the softest edges at a higher cost.
     */
    ShadowFilter SOFT = new BuiltinShadowFilter("soft", 2);

    /**
     * A filter written in GLSL; see the description of this interface for what the body can use.
     *
     * @param name a short label, unique among your filters (letters and digits)
     */
    static ShadowFilter custom(String name, String glslBody) {
        return new CustomShadowFilter(name, glslBody);
    }

    String name();

    /**
     * The GLSL body of a custom filter, or {@code null} for the built-in ones.
     */
    default String glsl() {
        return null;
    }
}
