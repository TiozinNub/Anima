package dev.luizloyola.anima.core.continuity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A field left out of its owner's snapshot because {@code restore} rebuilds it from what was saved.
 * Still compared by {@link StateGraph}: rebuilt means rebuilt equal, at restore, not on some later
 * tick.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Derived {
    /** What it is rebuilt from. */
    String value();
}
