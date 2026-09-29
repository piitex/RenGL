package me.piitex.engine.entity;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a component whose {@link Component#render render} an editor calls while the scene is being edited, so what it
 * draws shows in the editor's view. No other hook runs in the editor, {@link Component#onStart()} included, so such a
 * render must work from the component's properties and its entity alone.
 * <pre>{@code
 * @DrawInEditor
 * public class Quad extends Component {
 *     @Property public float width = 32f, height = 32f;
 *
 *     @Override
 *     protected void render(Renderer renderer, float alpha) {
 *         ... // draws from width, height and the entity's transform
 *     }
 * }
 * }</pre>
 * Components without it are only drawn when the game runs. Subclasses of a marked component are marked too.
 *
 * @see EntityScene#renderInEditor
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DrawInEditor {
}
