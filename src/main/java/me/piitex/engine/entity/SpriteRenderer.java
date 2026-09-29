package me.piitex.engine.entity;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.graphics.Sprite;
import me.piitex.engine.graphics.TextureRegion;
import me.piitex.engine.math.Rect;
import me.piitex.engine.property.Property;
import me.piitex.engine.property.TextureRef;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Draws an image at its entity, turned and scaled with it. At a scale of 1 each pixel of the image covers one world
 * unit.
 * <pre>{@code
 * entity.addComponent(new SpriteRenderer(TextureRef.of("assets/hero.png")));
 * }</pre>
 * The pivot is the point of the image placed at the entity's position, as a fraction of its size: (0.5, 0.5), the
 * default, is its center; (0.5, 1) the middle of its bottom edge. An image that can't be loaded is logged once and
 * not drawn, so a missing file doesn't stop the game.
 */
@DrawInEditor
public class SpriteRenderer extends Component implements Bounded {
    private static final Logger log = LogManager.getLogger("SpriteRenderer");

    @Property(tooltip = "The image to draw")
    public TextureRef texture;
    @Property(tooltip = "Multiplied with the image; white leaves it as it is")
    public Color color = Color.WHITE;
    @Property(min = 0, max = 1, tooltip = "The point of the image at the entity's position, 0 to 1 across")
    public float pivotX = 0.5f;
    @Property(min = 0, max = 1, tooltip = "The point of the image at the entity's position, 0 to 1 down")
    public float pivotY = 0.5f;
    @Property public boolean flipX;
    @Property public boolean flipY;

    private final Sprite sprite = new Sprite();
    private TextureRef loaded;
    private boolean failed;

    public SpriteRenderer() {
    }

    public SpriteRenderer(TextureRef texture) {
        this.texture = texture;
    }

    @Override
    protected void render(Renderer renderer, float alpha) {
        if (sync()) sprite.draw(renderer);
    }

    /**
     * The world area the image covers as drawn now, turned and scaled, or an empty rectangle when there is no image.
     * An editor uses it to pick sprites with the mouse.
     */
    @Override
    public Rect getBounds() {
        return sync() ? sprite.getBounds() : new Rect(0, 0, 0, 0);
    }

    /**
     * The sprite this component draws with, set up from the properties at the last draw.
     */
    public Sprite getSprite() {
        return sprite;
    }

    // Brings the sprite up to date with the properties and the entity; false when there is nothing to draw.
    private boolean sync() {
        Entity entity = getEntity();
        if (texture == null || entity == null) return false;
        if (!texture.equals(loaded)) {
            loaded = texture;
            failed = false;
            try {
                sprite.setRegion(new TextureRegion(texture.load()));
            } catch (RuntimeException e) {
                failed = true;
                sprite.setRegion(null);
                log.warn("Could not load image {} for {}: {}", texture.getPath(), entity, e.toString());
            }
        }
        if (failed) return false;
        sprite.setPosition(entity.getWorldX(), entity.getWorldY());
        sprite.setRotation(entity.getWorldRotation());
        sprite.setScale(entity.getWorldScaleX(), entity.getWorldScaleY());
        sprite.setPivot(pivotX, pivotY);
        sprite.setFlipX(flipX);
        sprite.setFlipY(flipY);
        sprite.setColor(color == null ? Color.WHITE : color);
        return true;
    }
}
