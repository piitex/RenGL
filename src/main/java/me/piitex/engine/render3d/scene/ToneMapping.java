package me.piitex.engine.render3d.scene;

/**
 * How a scene's lit colours are brought into the range a screen can show. Lights can add up to more than white
 * (several lights on one surface, a bright light, a strong emissive glow); without tone mapping the sum is cut off at
 * white and bright areas turn into flat patches. A tone mapper bends the bright end smoothly instead, so highlights keep
 * their shape. Set one with {@link Scene3D#setToneMapping}; {@link Scene3D#setExposure} scales the light first.
 */
public enum ToneMapping {
    /**
     * Colours are cut off at white. What a scene did before tone mapping existed, and the default: nothing changes.
     */
    NONE,
    /**
     * {@code c / (1 + c)}: soft and simple, never reaches white, a little flat and washed out in the middle tones.
     */
    REINHARD,
    /**
     * A fit of the ACES film curve: more contrast, richer shadows and a gentle roll off of highlights, the usual look of
     * games and film. It darkens and saturates mid tones, so raise the exposure a little (1.2 to 1.5) if the scene gets dim.
     */
    ACES
}
