package dev.luizloyola.anima.core.continuity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A field that is not saved and cannot change what the next tick does: a search in flight (re-issued
 * on restore), a reference to the level, a log-pacing counter, a debug watcher. {@link StateGraph}
 * skips it. Anything else that is not saved is a value a reload loses.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Ephemeral {
    /** Why losing it cannot matter. */
    String value();
}
