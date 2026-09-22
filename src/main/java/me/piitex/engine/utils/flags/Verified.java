package me.piitex.engine.utils.flags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Used to mark a {@link GeneratedFunction} or other as verified to work.
 * ta
 */
@Retention(value = RetentionPolicy.SOURCE)
@Target(value = ElementType.TYPE)
public @interface Verified {
    String reviewer();

    boolean accepted();
}
