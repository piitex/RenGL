package me.piitex.engine.utils.flags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Used to mark a function as being AI generated.
 *
 */
@Retention(RetentionPolicy.SOURCE)
@Target(value = ElementType.METHOD)
public @interface GeneratedFunction {
    String prompter();

    String model();

    boolean reviewed();
}
