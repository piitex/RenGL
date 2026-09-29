package me.piitex.engine.property;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as a property: a value that is saved with its object and that an editor can show and change. Fields
 * without it are runtime state: not saved, not shown.
 * <pre>{@code
 * public class Health extends Component {
 *     @Property public int max = 100;
 *     @Property(min = 0) public float regenPerSecond = 2f;
 *     private int current; // runtime state
 * }
 * }</pre>
 * The field's type must be one {@link PropertyTypes} knows: the primitives and their boxes, {@link String}, enums,
 * {@link me.piitex.engine.ui.color.Color}, {@link me.piitex.engine.math.Vec2}, {@link TextureRef}, or a type
 * registered with {@link PropertyTypes#register}. The field may be private, but not static or final.
 * <p>
 * {@link TypeInfo} reads these annotations; see it for how properties are found, saved and loaded.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Property {
    /**
     * The name the property is saved under and shown with. Empty, the default, uses the field's name. Setting it
     * lets the field be renamed without breaking saved files.
     */
    String name() default "";

    /**
     * A short explanation an editor can show when the property's field is hovered.
     */
    String tooltip() default "";

    /**
     * The lowest value an editor offers, for number properties. Loading does not enforce it.
     */
    double min() default Double.NEGATIVE_INFINITY;

    /**
     * The highest value an editor offers, for number properties. Loading does not enforce it.
     */
    double max() default Double.POSITIVE_INFINITY;
}
