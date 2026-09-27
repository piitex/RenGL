package me.piitex.engine.utils;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * An object that clears itself when it goes back into a {@link Pool}. {@link Pool#free} calls {@link #reset()} for
 * every object that implements this, so the pool needs no reset function of its own.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public interface Poolable {

    /**
     * Puts the object back to how a new one would be, and drops references it holds, such as a target or a listener,
     * so they can be garbage collected while the object waits in the pool.
     */
    void reset();
}
