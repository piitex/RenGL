package me.piitex.engine.event;

import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * A listener's place on an {@link EventBus}. Cancel it when the listener should stop hearing events, such as when the
 * thing that listens is removed. {@link #close()} does the same, for try-with-resources.
 */
public interface Subscription extends AutoCloseable {

    /**
     * Stops the listener from receiving any more events, including one being delivered right now to listeners after
     * it. Calling it again does nothing.
     */
    void cancel();

    /**
     * True until {@link #cancel()} is called or the bus is {@link EventBus#clear() cleared}.
     */
    boolean isActive();

    @Override
    default void close() {
        cancel();
    }
}
