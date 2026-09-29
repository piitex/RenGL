package me.piitex.examples.entities;

import me.piitex.engine.entity.Component;
import me.piitex.engine.property.Property;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * Moves its entity up and down around where it started.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class Bob extends Component {
    @Property(min = 0) public float height = 20f;
    @Property(min = 0, tooltip = "Bobs per second") public float speed = 0.5f;

    private float startY;
    private float time;

    @Override
    protected void onStart() {
        startY = getTransform().getY();
    }

    @Override
    protected void update(float delta) {
        time += delta;
        getTransform().setY(startY + height * (float) Math.sin(time * speed * Math.PI * 2));
    }
}
