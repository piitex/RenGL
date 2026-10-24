package me.piitex.engine.render3d.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes lights and glowing surfaces blink, pulse or flicker. A {@link LightPattern} says how bright things are at each
 * moment; this applies it to the {@linkplain #light lights} and {@linkplain #glow materials} it was given, so a lamp
 * and the bulb you see in it stay in step:
 * <pre>{@code
 * Light lamp = Light.point(new Vec3(2, 3, 0), Color.RED, 6f, 8f);
 * scene.lights().add(lamp);
 * Material bulb = new Material(Color.BLACK).setEmissive(Color.RED, 4f);
 * lampNode.setMaterial(bulb);
 *
 * scene.animate(LightAnimation.of(LightPattern.blink(1f)).light(lamp).glow(bulb));
 * }</pre>
 * Whatever strength a light or material has when it is added is its full strength; the animation scales it down to
 * nothing and back. So set the intensity and the emissive first, and add them before the animation runs.
 * <p>
 * An animation does nothing until it is {@linkplain Scene3D#animate(LightAnimation) added to a scene} (which advances
 * it once per frame and keeps a viewport that only draws on change drawing), or until you call {@link #update} yourself.
 * Lights with the same pattern can share one animation; give lights their own animation, or {@linkplain #setTime a
 * different start time}, when they should not blink together.
 * <p>
 * It changes the lights and materials directly, so it is not saved in a scene file, and a light that is animated by
 * this should not also be changed by hand. A light dimmed to 0 takes no room in the scene's 64 lights.
 */
public final class LightAnimation {
    private final LightPattern pattern;
    private final List<Light> lights = new ArrayList<>();
    private final List<Float> lightFull = new ArrayList<>();
    private final List<Material> materials = new ArrayList<>();
    private final List<float[]> materialFull = new ArrayList<>();
    private float minimum;
    private float speed = 1f;
    private double time;
    private boolean playing = true;
    private float level = 1f;

    private LightAnimation(LightPattern pattern) {
        this.pattern = pattern;
    }

    /**
     * An animation of the pattern, with nothing to change yet: add lights and materials with {@link #light} and
     * {@link #glow}.
     */
    public static LightAnimation of(LightPattern pattern) {
        if (pattern == null) throw new IllegalArgumentException("pattern must not be null");
        return new LightAnimation(pattern);
    }

    /**
     * Makes the light's intensity follow the pattern, from nothing up to the intensity it has now.
     */
    public LightAnimation light(Light light) {
        if (light == null) throw new IllegalArgumentException("light must not be null");
        lights.add(light);
        lightFull.add(light.getIntensity());
        return this;
    }

    /**
     * Makes the material's emissive glow follow the pattern, from nothing up to the glow it has now. A material with no
     * glow (the default) is left alone, so set {@link Material#setEmissive(me.piitex.engine.ui.color.Color, float)} first.
     */
    public LightAnimation glow(Material material) {
        if (material == null) throw new IllegalArgumentException("material must not be null");
        materials.add(material);
        materialFull.add(new float[]{material.getEmissiveRed(), material.getEmissiveGreen(), material.getEmissiveBlue()});
        return this;
    }

    /**
     * The brightness at the lowest point of the pattern, as a share of full strength: 0, the default, goes fully dark,
     * and 0.1 leaves a dim LED or a glowing filament.
     */
    public LightAnimation setMinimum(float minimum) {
        this.minimum = Math.max(0f, Math.min(1f, minimum));
        return this;
    }

    public float getMinimum() {
        return minimum;
    }

    /**
     * How fast the pattern runs, 1 by default: 2 blinks twice as fast. Not below 0.
     */
    public LightAnimation setSpeed(float speed) {
        this.speed = Math.max(0f, speed);
        return this;
    }

    public float getSpeed() {
        return speed;
    }

    /**
     * Seconds into the pattern. Set it differently on each of several animations to put them out of step.
     */
    public LightAnimation setTime(float seconds) {
        this.time = seconds;
        return this;
    }

    public float getTime() {
        return (float) time;
    }

    /**
     * True (the default) while it advances. While paused the lights and materials stay as they are.
     */
    public boolean isPlaying() {
        return playing;
    }

    public LightAnimation setPlaying(boolean playing) {
        this.playing = playing;
        return this;
    }

    /**
     * The brightness as of the last {@link #update}, from the minimum up to 1.
     */
    public float getLevel() {
        return level;
    }

    /**
     * Moves on by {@code delta} seconds, if playing, and sets every light and material to the new brightness. A scene
     * does this each frame once the animation is added to it.
     */
    public void update(float delta) {
        if (playing) time += delta * speed;
        float raw = pattern.level((float) time);
        float shape = Float.isNaN(raw) ? 1f : Math.max(0f, Math.min(1f, raw));
        level = minimum + (1f - minimum) * shape;
        for (int i = 0; i < lights.size(); i++) lights.get(i).setIntensity(lightFull.get(i) * level);
        for (int i = 0; i < materials.size(); i++) {
            float[] full = materialFull.get(i);
            if (full[0] > 0f || full[1] > 0f || full[2] > 0f) {
                materials.get(i).setEmissive(full[0] * level, full[1] * level, full[2] * level);
            }
        }
    }
}
