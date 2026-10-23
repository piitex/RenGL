package me.piitex.engine.event;

import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.scheduler.Task;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Lets parts of a game tell each other what happened without knowing about each other: the enemy code posts
 * {@code EnemyKilled}, and the score, the sound and the achievements each listen for it.
 * <pre>{@code
 * record EnemyKilled(Enemy enemy, int points) {}
 *
 * EventBus events = new EventBus();
 * events.subscribe(EnemyKilled.class, e -> score += e.points());
 * events.post(new EnemyKilled(enemy, 100));
 * }</pre>
 * Any object is an event; records suit them well. A listener for a type also receives its subtypes, so listening for
 * a shared interface or {@code Object} catches a whole family. The bus decides nothing else: there are no priorities
 * or cancellation, and an event that should be stoppable carries its own flag.
 * <p>
 * {@link #post} calls the listeners at once, on the calling thread, in the order they subscribed. {@link #postLater}
 * queues the event for the render thread, which is the safe way to post from a worker thread. A listener that throws is
 * logged, and the others still run. Subscribing, unsubscribing and posting are safe from any thread and from inside a
 * listener; a listener unsubscribed while an event is being delivered does not receive it.
 * <p>
 * Keep one bus for the game, or one per scene, as suits: the engine keeps none of its own.
 */
public final class EventBus {
    private static final Logger log = LogManager.getLogger("EventBus");
    private static final Map<Class<?>, List<Class<?>>> HIERARCHY = new ConcurrentHashMap<>();

    private final Map<Class<?>, List<Listener<?>>> listeners = new ConcurrentHashMap<>();

    /**
     * Calls {@code listener} with every event posted to this bus that is a {@code type}. Returns the subscription to
     * {@link Subscription#cancel() cancel}, which stops it.
     */
    public <E> Subscription subscribe(Class<E> type, Consumer<? super E> listener) {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        Listener<E> entry = new Listener<>(this, type, listener);
        listeners.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>()).add(entry);
        return entry;
    }

    /**
     * Delivers {@code event} to its listeners now, on this thread. Returns whether any listener received it.
     */
    public boolean post(Object event) {
        if (event == null) throw new IllegalArgumentException("event must not be null");
        List<Listener<?>> matching = null;
        for (Class<?> type : hierarchy(event.getClass())) {
            List<Listener<?>> forType = listeners.get(type);
            if (forType == null || forType.isEmpty()) continue;
            if (matching == null) matching = new ArrayList<>();
            matching.addAll(forType);
        }
        if (matching == null) return false;
        // Listeners of several matching types run in the order they subscribed, whichever type they chose.
        if (matching.size() > 1) matching.sort((a, b) -> Long.compare(a.order, b.order));

        boolean delivered = false;
        for (Listener<?> listener : matching) {
            delivered |= listener.deliver(event);
        }
        return delivered;
    }

    /**
     * Delivers {@code event} on the render thread at the start of the next frame, from any thread. Returns the task,
     * which can cancel it until then.
     */
    public Task postLater(Object event) {
        if (event == null) throw new IllegalArgumentException("event must not be null");
        return Scheduler.runLater(() -> post(event));
    }

    /**
     * Whether anything listens for {@code type} or a supertype of it, for skipping the work of building an event
     * nobody receives.
     */
    public boolean hasListeners(Class<?> type) {
        for (Class<?> t : hierarchy(type)) {
            List<Listener<?>> forType = listeners.get(t);
            if (forType != null && !forType.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Cancels every subscription on this bus.
     */
    public void clear() {
        for (List<Listener<?>> forType : listeners.values()) {
            for (Listener<?> listener : forType) listener.active = false;
        }
        listeners.clear();
    }

    private void remove(Listener<?> listener) {
        List<Listener<?>> forType = listeners.get(listener.type);
        if (forType != null) forType.remove(listener);
    }

    /**
     * The class, its superclasses and every interface it implements, most specific first.
     */
    private static List<Class<?>> hierarchy(Class<?> type) {
        return HIERARCHY.computeIfAbsent(type, t -> {
            Set<Class<?>> all = new LinkedHashSet<>();
            for (Class<?> c = t; c != null; c = c.getSuperclass()) all.add(c);
            for (Class<?> c = t; c != null; c = c.getSuperclass()) addInterfaces(c, all);
            if (t.isInterface()) all.add(Object.class);
            return List.copyOf(all);
        });
    }

    private static void addInterfaces(Class<?> type, Set<Class<?>> into) {
        for (Class<?> i : type.getInterfaces()) {
            if (into.add(i)) addInterfaces(i, into);
        }
    }

    private static final class Listener<E> implements Subscription {
        private static final AtomicLong NEXT = new AtomicLong();

        final EventBus bus;
        final Class<E> type;
        final Consumer<? super E> consumer;
        final long order = NEXT.getAndIncrement();
        volatile boolean active = true;

        Listener(EventBus bus, Class<E> type, Consumer<? super E> consumer) {
            this.bus = bus;
            this.type = type;
            this.consumer = consumer;
        }

        boolean deliver(Object event) {
            if (!active) return false;
            try {
                consumer.accept(type.cast(event));
            } catch (Throwable t) {
                log.error("Listener for {} threw", type.getName(), t);
            }
            return true;
        }

        @Override
        public void cancel() {
            if (!active) return;
            active = false;
            bus.remove(this);
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }
}
