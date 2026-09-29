package me.piitex.engine.graphics;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.math.Rect;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;
import me.piitex.engine.utils.flags.GeneratedFragments;

/**
 * A {@link TextureRegion} placed in the world: where it is, how it is turned, scaled, mirrored and colored, and which
 * layer it is drawn on. It is only a description of a picture to draw; what the thing it shows is, and how it moves,
 * is up to the game, which can keep sprites on its own objects however it likes.
 * <pre>{@code
 * Sprite hero = new Sprite(atlas.get("hero_idle"));
 * hero.setPivot(0.5f, 1f);       // position the sprite by its feet
 * hero.setPosition(200, 300);
 * hero.setFlipX(facingLeft);
 *
 * // in Scene.render:
 * hero.draw(renderer);           // or batch.add(hero) to draw many sprites sorted, culled and batched
 * }</pre>
 * <b>Position and pivot.</b> ({@link #getX()}, {@link #getY()}) is where the sprite's pivot goes. The pivot is a point
 * on the picture, as a fraction of its size: (0, 0), the default, is its top-left corner, (0.5, 0.5) its center and
 * (0.5, 1) the middle of its bottom edge. The sprite rotates and scales around its pivot. Units are the same as the
 * coordinates it is drawn in: window points, or world units under a {@link Camera}. At a scale of 1 each pixel of
 * the region covers one unit.
 * <p>
 * <b>Mirroring</b> flips the picture inside its own box, so a mirrored sprite covers the same area as before.
 * <p>
 * <b>Color</b> is multiplied with the picture: white, the default, leaves it as it is, {@code Color.RED} keeps only its
 * red, and a color's alpha fades it, as does {@link #setOpacity}.
 * <p>
 * A sprite is not thread-safe. Change it and draw it on the render thread, in scene hooks.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class Sprite {
    private static final float[] scratchPositions = new float[8];
    private static final float[] scratchTexCoords = new float[8];
    private static final int[] scratchColor = new int[1];

    private TextureRegion region;
    private float x, y;
    private float pivotX, pivotY;
    private float rotation;
    private float scaleX = 1f, scaleY = 1f;
    private boolean flipX, flipY;
    private Color color = Color.WHITE;
    private float opacity = 1f;
    private int layer;
    private boolean visible = true;

    public Sprite() {
    }

    public Sprite(TextureRegion region) {
        this.region = region;
    }

    /**
     * Draws the sprite now, on its own. To draw many sprites, add them to a {@link SpriteBatch} instead: it draws
     * them in layer order, skips those out of view, and draws each run from one texture in a single call. Nothing is
     * drawn without a region or while hidden.
     */
    public void draw(Renderer renderer) {
        if (!writeQuad(scratchPositions, 0, scratchTexCoords, 0)) return;
        scratchColor[0] = packedColor();
        Texture texture = region.getTexture();
        renderer.drawImageQuads(texture.getImage(), texture.isSmooth(), scratchPositions, scratchTexCoords, scratchColor, 1);
    }

    /**
     * The smallest upright rectangle around the sprite as it is drawn, turned and scaled; empty without a region.
     */
    public Rect getBounds() {
        if (region == null) return new Rect(x, y, 0f, 0f);
        float[] corners = new float[8];
        writeCorners(corners, 0);
        float minX = Math.min(Math.min(corners[0], corners[2]), Math.min(corners[4], corners[6]));
        float maxX = Math.max(Math.max(corners[0], corners[2]), Math.max(corners[4], corners[6]));
        float minY = Math.min(Math.min(corners[1], corners[3]), Math.min(corners[5], corners[7]));
        float maxY = Math.max(Math.max(corners[1], corners[3]), Math.max(corners[5], corners[7]));
        return new Rect(minX, minY, maxX - minX, maxY - minY);
    }

    /**
     * Writes the corners of the region's pixels, top-left, top-right, bottom-right, bottom-left of the picture, and
     * the matching texture coordinates. False, writing nothing, if there is nothing to draw.
     */
    boolean writeQuad(float[] positions, int positionOffset, float[] texCoords, int texOffset) {
        if (region == null || !visible || opacity <= 0f || color == null || color.getA() <= 0f) return false;
        writeCorners(positions, positionOffset);
        float left = region.getX(), top = region.getY();
        float right = left + region.getWidth(), bottom = top + region.getHeight();
        texCoords[texOffset] = left;
        texCoords[texOffset + 1] = top;
        texCoords[texOffset + 2] = right;
        texCoords[texOffset + 3] = top;
        texCoords[texOffset + 4] = right;
        texCoords[texOffset + 5] = bottom;
        texCoords[texOffset + 6] = left;
        texCoords[texOffset + 7] = bottom;
        return true;
    }

    private void writeCorners(float[] out, int offset) {
        float frameWidth = region.getFrameWidth(), frameHeight = region.getFrameHeight();
        float left = region.getOffsetX(), top = region.getOffsetY();
        float right = left + region.getWidth(), bottom = top + region.getHeight();
        if (flipX) {
            left = frameWidth - left;
            right = frameWidth - right;
        }
        if (flipY) {
            top = frameHeight - top;
            bottom = frameHeight - bottom;
        }
        float originX = pivotX * frameWidth, originY = pivotY * frameHeight;
        float cos = 1f, sin = 0f;
        if (rotation != 0f) {
            double radians = Math.toRadians(rotation);
            cos = (float) Math.cos(radians);
            sin = (float) Math.sin(radians);
        }
        corner(out, offset, left, top, originX, originY, cos, sin);
        corner(out, offset + 2, right, top, originX, originY, cos, sin);
        corner(out, offset + 4, right, bottom, originX, originY, cos, sin);
        corner(out, offset + 6, left, bottom, originX, originY, cos, sin);
    }

    private void corner(float[] out, int offset, float frameX, float frameY, float originX, float originY, float cos, float sin) {
        float localX = (frameX - originX) * scaleX, localY = (frameY - originY) * scaleY;
        out[offset] = x + localX * cos - localY * sin;
        out[offset + 1] = y + localX * sin + localY * cos;
    }

    /**
     * The color as an ARGB int, with the opacity folded into its alpha.
     */
    int packedColor() {
        float alpha = Math.clamp(color.getA() * opacity, 0f, 1f);
        return ((int) (alpha * 255f + 0.5f) << 24) | (color.toARGB() & 0xFFFFFF);
    }

    public TextureRegion getRegion() {
        return region;
    }

    /**
     * The picture to draw, such as the current frame of an {@link AnimationPlayer}. Null draws nothing.
     */
    public void setRegion(TextureRegion region) {
        this.region = region;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public void setX(float x) {
        this.x = x;
    }

    public void setY(float y) {
        this.y = y;
    }

    /**
     * Where the pivot goes.
     */
    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public float getPivotX() {
        return pivotX;
    }

    public float getPivotY() {
        return pivotY;
    }

    /**
     * The point of the picture that sits at the sprite's position and that it turns and scales around, as a fraction
     * of the picture's size: (0, 0) is the top-left corner, (1, 1) the bottom-right. Values outside 0 to 1 put the
     * pivot outside the picture, to swing it around a point beside it.
     */
    public void setPivot(float pivotX, float pivotY) {
        this.pivotX = pivotX;
        this.pivotY = pivotY;
    }

    public float getRotation() {
        return rotation;
    }

    /**
     * Rotation in degrees, clockwise, around the pivot.
     */
    public void setRotation(float rotation) {
        this.rotation = rotation;
    }

    public float getScaleX() {
        return scaleX;
    }

    public float getScaleY() {
        return scaleY;
    }

    /**
     * Scales both axes by {@code scale}, around the pivot.
     */
    public void setScale(float scale) {
        this.scaleX = scale;
        this.scaleY = scale;
    }

    public void setScale(float scaleX, float scaleY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }

    public boolean isFlipX() {
        return flipX;
    }

    /**
     * Mirrors the picture left to right, inside its own box.
     */
    public void setFlipX(boolean flipX) {
        this.flipX = flipX;
    }

    public boolean isFlipY() {
        return flipY;
    }

    /**
     * Mirrors the picture top to bottom, inside its own box.
     */
    public void setFlipY(boolean flipY) {
        this.flipY = flipY;
    }

    public Color getColor() {
        return color;
    }

    /**
     * Multiplied with the picture: white leaves it unchanged. Null counts as white.
     */
    public void setColor(Color color) {
        this.color = color == null ? Color.WHITE : color;
    }

    public float getOpacity() {
        return opacity;
    }

    /**
     * From 0, invisible, to 1, the default. Multiplied with the color's alpha.
     */
    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }

    public int getLayer() {
        return layer;
    }

    /**
     * Which layer a {@link SpriteBatch} draws this sprite on: lower layers first, so higher layers cover them. 0 by
     * default; any int works, negative ones too.
     */
    public void setLayer(int layer) {
        this.layer = layer;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}
