package me.piitex.engine.utils;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps objects that are no longer used so they can be used again, instead of creating new ones: bullets, particles,
 * damage numbers, anything a game makes and throws away many times a second.
 * <pre>{@code
 * Pool<Bullet> bullets = new Pool<>(Bullet::new);
 *
 * Bullet bullet = bullets.obtain(); // a free one, or a new one if none are free
 * ...
 * bullets.free(bullet);             // back into the pool, reset, for the next obtain
 * }</pre>
 * An object is reset when it is freed: {@link Poolable#reset()} if it implements {@link Poolable}, then the pool's
 * own reset function if it has one. {@link #obtain()} hands it out as it is, so whoever obtains it sets it up.
 * <p>
 * A pool is not thread-safe. Use each pool from one thread, normally the render thread.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class Pool<T> {
    private final Supplier<? extends T> factory;
    private final Consumer<? super T> reset;
    private final int maxFree;

    private final ArrayDeque<T> free = new ArrayDeque<>();
    // The same objects as free, to catch one freed twice without searching the queue.
    private final Set<T> freeSet = Collections.newSetFromMap(new IdentityHashMap<>());
    private int created;

    /**
     * A pool that creates objects with {@code factory} and keeps every freed one.
     */
    public Pool(Supplier<? extends T> factory) {
        this(factory, null, Integer.MAX_VALUE);
    }

    /**
     * A pool that creates objects with {@code factory} and runs {@code reset} on each one freed. {@code reset} may be
     * null.
     */
    public Pool(Supplier<? extends T> factory, Consumer<? super T> reset) {
        this(factory, reset, Integer.MAX_VALUE);
    }

    /**
     * A pool that keeps at most {@code maxFree} free objects. An object freed while the pool is full is reset and
     * then dropped, so a spike, such as one huge explosion, doesn't keep its objects forever.
     *
     * @param reset run on each object freed, or null
     * @throws IllegalArgumentException if {@code maxFree} is negative
     */
    public Pool(Supplier<? extends T> factory, Consumer<? super T> reset, int maxFree) {
        this.factory = Objects.requireNonNull(factory, "factory");
        if (maxFree < 0) {
            throw new IllegalArgumentException("maxFree must not be negative: " + maxFree);
        }
        this.reset = reset;
        this.maxFree = maxFree;
    }

    /**
     * A free object, or a new one from the factory if none are free.
     *
     * @throws NullPointerException if the factory returns null
     */
    public T obtain() {
        T object = free.pollLast();
        if (object != null) {
            freeSet.remove(object);
            return object;
        }
        return create();
    }

    /**
     * Resets {@code object} and keeps it for a later {@link #obtain()}. Stop using it afterwards, and cancel anything
     * that still refers to it, such as a tween, since the next {@code obtain} hands it to someone else.
     *
     * @throws NullPointerException if {@code object} is null
     * @throws IllegalStateException if {@code object} is already free in this pool
     */
    public void free(T object) {
        Objects.requireNonNull(object, "object");
        if (freeSet.contains(object)) {
            throw new IllegalStateException("Object is already free in this pool: " + object);
        }
        if (object instanceof Poolable poolable) {
            poolable.reset();
        }
        if (reset != null) {
            reset.accept(object);
        }
        if (free.size() < maxFree) {
            free.addLast(object);
            freeSet.add(object);
        }
    }

    /**
     * {@link #free Frees} each object, such as every live bullet when a level ends. The collection itself is left as it
     * is, so clear it afterwards.
     */
    public void freeAll(Iterable<? extends T> objects) {
        for (T object : objects) {
            free(object);
        }
    }

    /**
     * Creates objects until {@code count} are free, or the pool is full, so the first ones used in play needn't be
     * created then. Does nothing if that many are already free.
     *
     * @return this pool
     * @throws IllegalArgumentException if {@code count} is negative
     */
    public Pool<T> fill(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative: " + count);
        }
        int target = Math.min(count, maxFree);
        while (free.size() < target) {
            T object = create();
            free.addLast(object);
            freeSet.add(object);
        }
        return this;
    }

    /**
     * Drops every free object. Objects in use are not affected and can still be freed back into the pool.
     */
    public void clear() {
        free.clear();
        freeSet.clear();
    }

    /**
     * How many objects are free, waiting for {@link #obtain()}.
     */
    public int getFreeCount() {
        return free.size();
    }

    /**
     * The most free objects the pool keeps, {@link Integer#MAX_VALUE} for no limit.
     */
    public int getMaxFree() {
        return maxFree;
    }

    /**
     * How many objects the factory has created in all. Once it stops growing during play, the pool is reusing every
     * object it needs.
     */
    public int getCreatedCount() {
        return created;
    }

    private T create() {
        T object = Objects.requireNonNull(factory.get(), "Pool factory returned null");
        created++;
        return object;
    }
}
