package me.piitex.engine.render3d.scene;

/**
 * How one {@link Light} casts shadows: pick a {@link ShadowQuality} and change what you need.
 * <pre>{@code
 * sun.setShadows(Shadows.of(ShadowQuality.HIGH).cascades(3).distance(80f));
 * lamp.setShadows(Shadows.of(ShadowQuality.LOW).bias(1.5f).softness(2f));
 * fire.setShadows(ShadowQuality.MEDIUM);
 * }</pre>
 * A value that is not set is taken from the scene ({@link Scene3D#setShadowBias}, {@link Scene3D#setShadowSoftness},
 * {@link Scene3D#setShadowDistance}, {@link Scene3D#setShadowMapSize}), so the scene's settings are the defaults and a light only
 * names where it differs. Instances are immutable: every method returns a changed copy.
 *
 * @param size     the width and height of one shadow picture in pixels, 256 to 8192, or 0 for the scene's size (point lights: 1024)
 * @param bias     scales the offset that stops a surface shadowing itself (see {@link Scene3D#setShadowBias}), or NaN for the scene's
 * @param softness how wide the soft edge is, as a multiple of the default, 0.25 to 4, or NaN for the scene's
 * @param distance directional lights only: how far from the camera the shadows reach, or NaN for the scene's
 * @param cascades directional lights only: how many pictures the view is split into, 1 to 4. More cascades keep shadows sharp near the
 *                 camera and still reach far, at the cost of drawing the shadow casters once per cascade
 * @param filter   how the edge is filtered, or null for {@link ShadowFilter#PCF}
 */
public record Shadows(int size, float bias, float softness, float distance, int cascades, ShadowFilter filter) {
    /**
     * Everything taken from the scene: what {@link Light#setCastShadow(boolean) setCastShadow(true)} means.
     */
    public static final Shadows SCENE = new Shadows(0, Float.NaN, Float.NaN, Float.NaN, 1, null);

    public Shadows {
        if (size != 0) size = Math.max(256, Math.min(8192, size));
        if (!Float.isNaN(bias)) bias = Math.max(0f, bias);
        if (!Float.isNaN(softness)) softness = Math.max(0.25f, Math.min(4f, softness));
        if (!Float.isNaN(distance)) distance = Math.max(0.1f, distance);
        cascades = Math.max(1, Math.min(4, cascades));
    }

    /**
     * The settings of a quality preset, with everything else from the scene.
     */
    public static Shadows of(ShadowQuality quality) {
        return new Shadows(quality.size(), Float.NaN, Float.NaN, Float.NaN, 1, quality.filter());
    }

    public Shadows size(int size) {
        return new Shadows(size, bias, softness, distance, cascades, filter);
    }

    public Shadows bias(float bias) {
        return new Shadows(size, bias, softness, distance, cascades, filter);
    }

    public Shadows softness(float softness) {
        return new Shadows(size, bias, softness, distance, cascades, filter);
    }

    public Shadows distance(float distance) {
        return new Shadows(size, bias, softness, distance, cascades, filter);
    }

    public Shadows cascades(int cascades) {
        return new Shadows(size, bias, softness, distance, cascades, filter);
    }

    public Shadows filter(ShadowFilter filter) {
        return new Shadows(size, bias, softness, distance, cascades, filter);
    }

    // What the renderer asks: the setting, or the scene's where the light doesn't name one.

    int resolveSize(Light.Type type, Scene3D scene) {
        return size != 0 ? size : type == Light.Type.POINT ? ShadowQuality.MEDIUM.size() : scene.getShadowMapSize();
    }

    float resolveBias(Scene3D scene) {
        return Float.isNaN(bias) ? scene.getShadowBias() : bias;
    }

    float resolveSoftness(Scene3D scene) {
        return Float.isNaN(softness) ? scene.getShadowSoftness() : softness;
    }

    float resolveDistance(Scene3D scene) {
        return Float.isNaN(distance) ? scene.getShadowDistance() : distance;
    }

    ShadowFilter resolveFilter() {
        return filter == null ? ShadowFilter.PCF : filter;
    }
}
