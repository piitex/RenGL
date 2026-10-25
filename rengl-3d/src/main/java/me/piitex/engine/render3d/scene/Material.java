package me.piitex.engine.render3d.scene;

import me.piitex.engine.ui.color.Color;

/**
 * How a surface looks: a colour, an optional texture, and how it reacts to light. There are two ways to shade it:
 * <ul>
 * <li><b>Classic</b> (the default): a Lambert diffuse term plus a Blinn-Phong highlight, set with
 * {@link #setSpecular} and {@link #setShininess}. Simple and cheap.</li>
 * <li><b>Physically based</b> (PBR): the metallic-roughness model glTF uses. Choose it with {@link #setPbr}, or by
 * setting {@link #setMetallic metallic}, {@link #setRoughness roughness}, or any of the maps ({@link #setNormalTexture
 * normal}, {@link #setMetallicRoughnessTexture metallic-roughness}, {@link #setOcclusionTexture occlusion},
 * {@link #setEmissiveTexture emissive}). Models loaded from glTF files are PBR.</li>
 * </ul>
 * Colours are written like the UI's: what you see on screen. The scene converts to linear light to shade, and back.
 * The final colour is the texture times the colour, so a white colour shows the texture as it is. The normal,
 * metallic-roughness and occlusion maps hold data, not colours, and are read as they are.
 */
public final class Material {
    private float r = 1f, g = 1f, b = 1f, a = 1f;
    private TextureData texture;
    private float specular = 0.3f;
    private float shininess = 32f;
    private boolean unlit;
    private boolean doubleSided;
    private boolean blend;
    private boolean additive;
    private float alphaCutoff;
    private boolean pbr;
    private float metallic = 0f;
    private float roughness = 0.5f;
    private TextureData normalTexture;
    private TextureData metallicRoughnessTexture;
    private TextureData occlusionTexture;
    private TextureData emissiveTexture;
    private float emissiveR, emissiveG, emissiveB;
    private float glowRange, glowStrength = 1f;
    /** How many materials have a glow on, so a scene with none skips looking for them. */
    private static final java.util.concurrent.atomic.AtomicInteger GLOWING = new java.util.concurrent.atomic.AtomicInteger();

    static boolean anyGlow() {
        return GLOWING.get() > 0;
    }
    private float normalScale = 1f;
    private float uvOffsetU, uvOffsetV, uvScaleU = 1f, uvScaleV = 1f, uvRotation;
    private int uvSets;

    public Material() {
    }

    public Material(Color color) {
        setColor(color);
    }

    public Material(TextureData texture) {
        this.texture = texture;
    }

    public Material setColor(Color color) {
        return setColor(color.getR(), color.getG(), color.getB(), color.getA());
    }

    public Material setColor(float r, float g, float b, float a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
        return this;
    }

    public float getRed() {
        return r;
    }

    public float getGreen() {
        return g;
    }

    public float getBlue() {
        return b;
    }

    public float getAlpha() {
        return a;
    }

    public TextureData getTexture() {
        return texture;
    }

    public Material setTexture(TextureData texture) {
        this.texture = texture;
        return this;
    }

    public float getSpecular() {
        return specular;
    }

    /**
     * The strength of the highlight, 0 for a matte surface to 1.
     */
    public Material setSpecular(float specular) {
        this.specular = specular;
        return this;
    }

    public float getShininess() {
        return shininess;
    }

    /**
     * The tightness of the highlight: a few for a broad glow, a few hundred for a pin-point shine.
     */
    public Material setShininess(float shininess) {
        this.shininess = shininess;
        return this;
    }

    public boolean isUnlit() {
        return unlit;
    }

    /**
     * Skips lighting: the surface is drawn in its plain colour.
     */
    public Material setUnlit(boolean unlit) {
        this.unlit = unlit;
        return this;
    }

    public boolean isDoubleSided() {
        return doubleSided;
    }

    /**
     * Draws the back of triangles too, lit from the side that faces the camera. Off by default, which skips them.
     */
    public Material setDoubleSided(boolean doubleSided) {
        this.doubleSided = doubleSided;
        return this;
    }

    public boolean isBlend() {
        return blend;
    }

    /**
     * Mixes the surface with what is behind it by its alpha. Blended surfaces are drawn after the solid ones,
     * farthest first, ordered by their node's position, so overlapping see-through surfaces can still look wrong.
     */
    public Material setBlend(boolean blend) {
        this.blend = blend;
        return this;
    }

    public boolean isAdditive() {
        return additive;
    }

    /**
     * Adds the surface's colour, times its alpha, to what is behind it instead of mixing: for light-like things such as
     * flames, sparks and glows, which can't get darker than their background. Turns {@link #setBlend blending} on, and
     * like blended surfaces these are drawn after the solid ones and cast no shadow. Fog fades them out instead of
     * tinting them.
     */
    public Material setAdditive(boolean additive) {
        this.additive = additive;
        if (additive) this.blend = true;
        return this;
    }

    public float getAlphaCutoff() {
        return alphaCutoff;
    }

    /**
     * Pixels whose alpha (texture times colour) is below this are not drawn at all: a cut-out for leaves, fences and
     * the like that needs no sorting. 0, the default, draws everything.
     */
    public Material setAlphaCutoff(float alphaCutoff) {
        this.alphaCutoff = alphaCutoff;
        return this;
    }

    // ---- physically based shading ------------------------------------------------------------------------------

    public boolean isPbr() {
        return pbr;
    }

    /**
     * Shades this material with the physically based model instead of the classic one. Setting any of the PBR
     * properties below turns it on by itself; this is for choosing it with the defaults (a dielectric of medium
     * roughness) or going back to classic with {@code false}.
     */
    public Material setPbr(boolean pbr) {
        this.pbr = pbr;
        return this;
    }

    public float getMetallic() {
        return metallic;
    }

    /**
     * 0 for a plastic, wood or stone, 1 for a bare metal, which tints its reflections with the colour. Multiplied with
     * the blue channel of the metallic-roughness map when there is one.
     */
    public Material setMetallic(float metallic) {
        this.metallic = clamp01(metallic);
        this.pbr = true;
        return this;
    }

    public float getRoughness() {
        return roughness;
    }

    /**
     * 0 for a mirror-sharp highlight to 1 for a fully matte surface. Multiplied with the green channel of the
     * metallic-roughness map when there is one.
     */
    public Material setRoughness(float roughness) {
        this.roughness = clamp01(roughness);
        this.pbr = true;
        return this;
    }

    public TextureData getNormalTexture() {
        return normalTexture;
    }

    /**
     * A tangent-space normal map: bumps and dents on a flat surface. Needs the mesh's texture coordinates. Uses the
     * OpenGL convention (green up), which is glTF's.
     */
    public Material setNormalTexture(TextureData normalTexture) {
        this.normalTexture = normalTexture;
        this.pbr = true;
        return this;
    }

    public float getNormalScale() {
        return normalScale;
    }

    /**
     * How strongly the normal map bends the surface: 1 as authored, 0 for flat.
     */
    public Material setNormalScale(float normalScale) {
        this.normalScale = normalScale;
        return this;
    }

    public TextureData getMetallicRoughnessTexture() {
        return metallicRoughnessTexture;
    }

    /**
     * Roughness in the green channel and metallic in the blue, the glTF layout.
     */
    public Material setMetallicRoughnessTexture(TextureData texture) {
        this.metallicRoughnessTexture = texture;
        this.pbr = true;
        return this;
    }

    public TextureData getOcclusionTexture() {
        return occlusionTexture;
    }

    /**
     * Baked shadowing in the corners and creases, in the red channel. It dims the ambient light only.
     */
    public Material setOcclusionTexture(TextureData texture) {
        this.occlusionTexture = texture;
        this.pbr = true;
        return this;
    }

    public TextureData getEmissiveTexture() {
        return emissiveTexture;
    }

    /**
     * A picture of light the surface gives off by itself, multiplied by the emissive colour (white if none was set).
     */
    public Material setEmissiveTexture(TextureData texture) {
        this.emissiveTexture = texture;
        if (emissiveR == 0f && emissiveG == 0f && emissiveB == 0f) setEmissive(1f, 1f, 1f);
        this.pbr = true;
        return this;
    }

    public float getEmissiveRed() {
        return emissiveR;
    }

    public float getEmissiveGreen() {
        return emissiveG;
    }

    public float getEmissiveBlue() {
        return emissiveB;
    }

    /**
     * Light the surface gives off whatever the lights do, such as a lamp or a screen. A screen colour, scaled by
     * {@code strength}; black (the default) is none. It does not light other surfaces unless you also {@link #setGlow set a glow}.
     */
    public Material setEmissive(Color color, float strength) {
        return setEmissive((float) Math.pow(color.getR(), 2.2) * strength, (float) Math.pow(color.getG(), 2.2) * strength,
                (float) Math.pow(color.getB(), 2.2) * strength);
    }

    /**
     * The same in linear light, the units glTF files use: 1 is as bright as a white surface in full light.
     */
    public Material setEmissive(float r, float g, float b) {
        this.emissiveR = Math.max(0f, r);
        this.emissiveG = Math.max(0f, g);
        this.emissiveB = Math.max(0f, b);
        this.pbr = true;
        return this;
    }

    public float getGlowRange() {
        return glowRange;
    }

    public float getGlowStrength() {
        return glowStrength;
    }

    /**
     * Makes a surface that {@link #setEmissive gives off light} also light its surroundings: each node (or each copy of an
     * instanced node) with this material adds a point light for the frame, at the middle of its mesh, in the emissive colour.
     * It lights like any lamp, so what it reaches is shaded by it, but it casts no shadow. The light follows the emissive
     * colour, so a {@link LightAnimation blink} of the material blinks the light with it.
     * <p>
     * A scene lights at most {@link Scene3D#MAX_GLOW_LIGHTS} such surfaces at once, and a scene with many of them (past
     * {@link Scene3D#MAX_LIGHTS} lights in all) shades them through the light grid. Nothing happens for a material with no
     * emissive colour.
     *
     * @param range the distance in world units where the light has faded to nothing; 0 (the default) switches the glow off
     */
    public Material setGlow(float range) {
        return setGlow(range, 1f);
    }

    /**
     * @param range    as above
     * @param strength a multiple of the emissive colour's brightness for the light; 1 gives a light as bright as the surface
     *                 looks, which is a strong one for a large range
     */
    public Material setGlow(float range, float strength) {
        if (glowRange <= 0f && range > 0f) GLOWING.incrementAndGet();
        else if (glowRange > 0f && range <= 0f) GLOWING.decrementAndGet();
        this.glowRange = Math.max(0f, range);
        this.glowStrength = Math.max(0f, strength);
        return this;
    }

    /**
     * What a material's textures can read the second set of texture coordinates for.
     */
    public enum Slot {
        BASE_COLOR, NORMAL, METALLIC_ROUGHNESS, OCCLUSION, EMISSIVE
    }

    /**
     * Moves, turns and stretches every texture of the material over the surface, as glTF's {@code KHR_texture_transform}
     * does: a texture coordinate is scaled, then turned by {@code rotation} radians, then moved by the offset. Tile a
     * texture with a scale above 1 (the textures repeat), slide it for flowing water or a scrolling screen by changing
     * the offset every frame, or turn it with the rotation. The default is no change: offset 0, scale 1, rotation 0.
     */
    public Material setUvTransform(float offsetU, float offsetV, float scaleU, float scaleV, float rotation) {
        this.uvOffsetU = offsetU;
        this.uvOffsetV = offsetV;
        this.uvScaleU = scaleU;
        this.uvScaleV = scaleV;
        this.uvRotation = rotation;
        return this;
    }

    /**
     * The same scale on both axes and no offset or turn, to tile a texture.
     */
    public Material setUvScale(float scale) {
        return setUvTransform(0f, 0f, scale, scale, 0f);
    }

    public float getUvOffsetU() {
        return uvOffsetU;
    }

    public float getUvOffsetV() {
        return uvOffsetV;
    }

    public float getUvScaleU() {
        return uvScaleU;
    }

    public float getUvScaleV() {
        return uvScaleV;
    }

    public float getUvRotation() {
        return uvRotation;
    }

    /**
     * Makes one of the textures read the mesh's second set of texture coordinates ({@code 1}, see
     * {@link Mesh#setUvs2}) instead of the first ({@code 0}, the default): a baked occlusion or light map laid out
     * separately from the colour texture. A mesh with no second set gives set 1 the first's coordinates.
     */
    public Material setTexCoordSet(Slot slot, int set) {
        if (set != 0 && set != 1) throw new IllegalArgumentException("A texture reads texture coordinate set 0 or 1, not " + set);
        if (set == 1) uvSets |= 1 << slot.ordinal();
        else uvSets &= ~(1 << slot.ordinal());
        return this;
    }

    public int getTexCoordSet(Slot slot) {
        return (uvSets >> slot.ordinal()) & 1;
    }

    /**
     * The texture slots that read the second set of coordinates, a bit per {@link Slot} in order.
     */
    int texCoordBits() {
        return uvSets;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
