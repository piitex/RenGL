package me.piitex.examples.entities;

import me.piitex.engine.entity.Component;
import me.piitex.engine.property.Property;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * Turns its entity at a steady speed. Children turn with it, which is what makes the planets orbit.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class Spin extends Component {
    @Property(tooltip = "Degrees per second, clockwise")
    public float degreesPerSecond = 90f;

    @Override
    protected void update(float delta) {
        getTransform().rotate(degreesPerSecond * delta);
    }
}
