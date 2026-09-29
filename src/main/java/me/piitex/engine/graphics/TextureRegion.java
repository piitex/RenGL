package me.piitex.engine.graphics;

import me.piitex.engine.utils.flags.GeneratedClass;
import me.piitex.engine.utils.flags.GeneratedFragments;

import java.util.ArrayList;
import java.util.List;

/**
 * One picture on a {@link Texture}: a rectangle of its pixels, such as one frame of a sprite sheet. Immutable.
 * <p>
 * A region may be <b>trimmed</b>, as texture packers do to save space: the transparent border around the picture is
 * cut off, and the region remembers where the kept pixels sat in the full picture ({@link #getOffsetX()},
 * {@link #getOffsetY()}) and how big the full picture was ({@link #getFrameWidth()}, {@link #getFrameHeight()}).
 * {@link Sprite} draws a trimmed region exactly where the full picture would have been, so frames of an animation
 * stay lined up however much of each was trimmed. For an untrimmed region the offsets are 0 and the frame size is
 * the region's own size.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class TextureRegion {
    private final Texture texture;
    private final int x, y, width, height;
    private final int offsetX, offsetY, frameWidth, frameHeight;

    /**
     * The whole texture.
     */
    public TextureRegion(Texture texture) {
        this(texture, 0, 0, texture.getWidth(), texture.getHeight());
    }

    /**
     * The rectangle at ({@code x}, {@code y}) of {@code width} by {@code height} pixels, untrimmed.
     */
    public TextureRegion(Texture texture, int x, int y, int width, int height) {
        this(texture, x, y, width, height, 0, 0, width, height);
    }

    /**
     * A trimmed region: the kept pixels at ({@code x}, {@code y}) on the texture belong at ({@code offsetX},
     * {@code offsetY}) in a full picture of {@code frameWidth} by {@code frameHeight}.
     */
    public TextureRegion(Texture texture, int x, int y, int width, int height,
                         int offsetX, int offsetY, int frameWidth, int frameHeight) {
        if (texture == null) throw new IllegalArgumentException("texture must not be null");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("region must not be empty: " + width + "x" + height);
        if (x < 0 || y < 0 || x + width > texture.getWidth() || y + height > texture.getHeight()) {
            throw new IllegalArgumentException("region " + x + "," + y + " " + width + "x" + height
                    + " is outside the " + texture.getWidth() + "x" + texture.getHeight() + " texture");
        }
        if (offsetX < 0 || offsetY < 0 || offsetX + width > frameWidth || offsetY + height > frameHeight) {
            throw new IllegalArgumentException("trimmed pixels at " + offsetX + "," + offsetY + " " + width + "x" + height
                    + " don't fit the " + frameWidth + "x" + frameHeight + " frame");
        }
        this.texture = texture;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
    }

    /**
     * Cuts the region into a grid of {@code cellWidth} by {@code cellHeight} cells, row by row from the top left,
     * for a sprite sheet laid out on a grid. Cells that don't fit whole are left out.
     */
    public List<TextureRegion> split(int cellWidth, int cellHeight) {
        return split(cellWidth, cellHeight, 0, 0);
    }

    /**
     * As {@link #split(int, int)}, for a sheet with {@code margin} pixels around the edge and {@code spacing} pixels
     * between cells.
     */
    public List<TextureRegion> split(int cellWidth, int cellHeight, int margin, int spacing) {
        if (cellWidth <= 0 || cellHeight <= 0) throw new IllegalArgumentException("cells must not be empty");
        if (margin < 0 || spacing < 0) throw new IllegalArgumentException("margin and spacing must not be negative");
        List<TextureRegion> cells = new ArrayList<>();
        for (int cy = margin; cy + cellHeight <= height - margin; cy += cellHeight + spacing) {
            for (int cx = margin; cx + cellWidth <= width - margin; cx += cellWidth + spacing) {
                cells.add(new TextureRegion(texture, x + cx, y + cy, cellWidth, cellHeight));
            }
        }
        return cells;
    }

    public Texture getTexture() {
        return texture;
    }

    /**
     * Left edge of the region on the texture, in pixels.
     */
    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    /**
     * Width of the region's pixels on the texture: without the trimmed border, if any.
     */
    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Where the region's pixels sit inside the full picture, from its left edge. 0 unless trimmed.
     */
    public int getOffsetX() {
        return offsetX;
    }

    public int getOffsetY() {
        return offsetY;
    }

    /**
     * Width of the full picture, including any trimmed border: the size a {@link Sprite} of it occupies.
     */
    public int getFrameWidth() {
        return frameWidth;
    }

    public int getFrameHeight() {
        return frameHeight;
    }

    public boolean isTrimmed() {
        return offsetX != 0 || offsetY != 0 || width != frameWidth || height != frameHeight;
    }

    @Override
    public String toString() {
        return "TextureRegion[" + x + "," + y + " " + width + "x" + height
                + (isTrimmed() ? " in " + frameWidth + "x" + frameHeight + " at " + offsetX + "," + offsetY : "") + "]";
    }
}
