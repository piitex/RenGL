package me.piitex.engine.render3d.scene;

import me.piitex.engine.ui.color.Color;

/**
 * How a surface looks: a colour, an optional texture, and how shiny it is. The scene shades with the lights it has,
 * a Lambert diffuse term plus a Blinn-Phong highlight.
 * <p>
 * Colours are written like the UI's: what you see on screen. The scene converts to linear light to shade, and back.
 * The final colour is the texture times the colour, so a white colour shows the texture as it is.
 */
public final class Material {
    private float r = 1f, g = 1f, b = 1f, a = 1f;
    private TextureData texture;
    private float specular = 0.3f;
    private float shininess = 32f;
    private boolean unlit;
    private boolean doubleSided;
    private boolean blend;
    private float alphaCutoff;

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
}
