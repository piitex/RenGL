package me.piitex.engine.utils.flags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as using AI generated code fragments. This is different from an entire generated function or class.
 * Typically used in AI driven bug fixes or assistance.
 */
@Retention(value = RetentionPolicy.SOURCE)
@Target(value = ElementType.TYPE)
public @interface GeneratedFragments {
    String prompter();
    String model();
    boolean reviewed();
}
