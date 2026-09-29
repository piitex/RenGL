package me.piitex.examples.entities;

import me.piitex.engine.entity.Bounded;
import me.piitex.engine.entity.Component;
import me.piitex.engine.entity.DrawInEditor;
import me.piitex.engine.entity.Entity;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.math.Rect;
import me.piitex.engine.math.Vec2;
import me.piitex.engine.property.Property;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * Draws a rounded rectangle centered on its entity, turned and scaled with it. A corner radius of half the size makes
 * a circle. It draws in the editor too.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
@DrawInEditor
public class Quad extends Component implements Bounded {
    @Property(min = 0) public float width = 32f;
    @Property(min = 0) public float height = 32f;
    @Property public Color color = Color.WHITE;
    @Property(min = 0) public float cornerRadius;

    @Override
    protected void render(Renderer renderer, float alpha) {
        Entity e = getEntity();
        renderer.pushTransform(e.getWorldX(), e.getWorldY(), e.getWorldRotation(), e.getWorldScaleX(), e.getWorldScaleY(), 0f, 0f);
        renderer.drawQuad(-width / 2f, -height / 2f, width, height, color, cornerRadius);
        renderer.popTransform();
    }

    @Override
    public Rect getBounds() {
        Entity e = getEntity();
        if (e == null) return new Rect(0, 0, 0, 0);
        Vec2 a = e.localToWorld(-width / 2f, -height / 2f), b = e.localToWorld(width / 2f, -height / 2f);
        Vec2 c = e.localToWorld(width / 2f, height / 2f), d = e.localToWorld(-width / 2f, height / 2f);
        return Rect.fromCorners(Math.min(Math.min(a.x(), b.x()), Math.min(c.x(), d.x())),
                Math.min(Math.min(a.y(), b.y()), Math.min(c.y(), d.y())),
                Math.max(Math.max(a.x(), b.x()), Math.max(c.x(), d.x())),
                Math.max(Math.max(a.y(), b.y()), Math.max(c.y(), d.y())));
    }
}
