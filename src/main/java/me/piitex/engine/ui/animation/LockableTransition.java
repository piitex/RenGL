package me.piitex.engine.ui.animation;

import me.piitex.engine.ui.Element;

/**
 * Base for transitions that run to a fixed end and so can lock the game while they play. It holds the two switches
 * and implements {@link #finish} by jumping to the end and applying it, so the Element lands in its final state and
 * any completion callback fires. Each setter returns the transition itself, so it chains off the constructor or off
 * {@link Element#fadeIn}: {@code title.fadeIn(2f).lock().skippable()}.
 * <p>
 * Neither switch is on by default.
 *
 * @param <T> the concrete transition, so the setters return it
 */
public abstract class LockableTransition<T extends LockableTransition<T>> implements Transition {
    private boolean locking;
    private boolean skippable;

    /**
     * Locks the game while this plays; see {@link Transition#isLocking()}.
     */
    public T lock() {
        return lock(true);
    }

    public T lock(boolean locking) {
        this.locking = locking;
        return self();
    }

    /**
     * Lets a key press or mouse click end this early; see {@link Transition#isSkippable()}. It only matters while it
     * {@link #lock locks}.
     */
    public T skippable() {
        return skippable(true);
    }

    public T skippable(boolean skippable) {
        this.skippable = skippable;
        return self();
    }

    @Override
    public boolean isLocking() {
        return locking;
    }

    @Override
    public boolean isSkippable() {
        return skippable;
    }

    @Override
    public boolean finish(Element target) {
        if (isFinished()) return false;
        jumpToEnd();
        update(target, 0f);
        return true;
    }

    /**
     * Moves this to its last moment, so the next {@code update(target, 0)} applies the end state and finishes.
     */
    protected abstract void jumpToEnd();

    @SuppressWarnings("unchecked")
    private T self() {
        return (T) this;
    }
}
