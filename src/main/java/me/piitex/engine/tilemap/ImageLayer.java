package me.piitex.engine.tilemap;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.graphics.Texture;
import me.piitex.engine.graphics.TextureRegion;
import me.piitex.engine.math.Rect;

/**
 * A layer showing one picture, such as a sky or a far-off range of hills, often with parallax. Repeated across or
 * down, it fills the view in that direction however far the camera goes.
 */
public final class ImageLayer extends MapLayer {
    private final Texture texture;
    private boolean repeatX, repeatY;

    /**
     * A layer showing {@code texture} with its top-left corner at the layer's offset; a null texture shows nothing.
     */
    public ImageLayer(String name, Texture texture) {
        super(name);
        this.texture = texture;
    }

    /**
     * The picture, or null if the layer has none.
     */
    public Texture getTexture() {
        return texture;
    }

    public boolean isRepeatX() {
        return repeatX;
    }

    public boolean isRepeatY() {
        return repeatY;
    }

    /**
     * Whether the picture repeats across ({@code x}) and down ({@code y}) without end.
     */
    public void setRepeat(boolean x, boolean y) {
        this.repeatX = x;
        this.repeatY = y;
    }

    @Override
    void render(Renderer renderer, Rect view, DrawState parent) {
        DrawState state = parent.with(this);
        if (state == null || texture == null) return;
        TileMap map = requireMap();
        float x = state.offsetX + state.shiftX(map, view), y = state.offsetY + state.shiftY(map, view);
        float w = texture.getWidth(), h = texture.getHeight();
        int firstX = 0, lastX = 0, firstY = 0, lastY = 0;
        if (view != null) {
            if (repeatX) {
                firstX = (int) Math.floor((view.x() - x) / w);
                lastX = (int) Math.floor((view.right() - x) / w);
            } else if (x >= view.right() || x + w <= view.x()) {
                return;
            }
            if (repeatY) {
                firstY = (int) Math.floor((view.y() - y) / h);
                lastY = (int) Math.floor((view.bottom() - y) / h);
            } else if (y >= view.bottom() || y + h <= view.y()) {
                return;
            }
            if ((long) (lastX - firstX + 1) * (lastY - firstY + 1) > 100_000) return; // a view far too big to be real
        }
        QuadBuffer quads = map.quads();
        TextureRegion whole = new TextureRegion(texture);
        int color = state.packed();
        for (int row = firstY; row <= lastY; row++) {
            for (int column = firstX; column <= lastX; column++) {
                TileLayer.addQuad(renderer, quads, whole, 0, x + column * w, y + row * h, w, h, color);
            }
        }
        quads.flush(renderer);
    }
}
