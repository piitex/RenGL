package me.piitex.engine.utils;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * A cache of loaded things (decoded pictures, typefaces) that stays under a size limit by letting go of the ones used
 * longest ago. Each entry has a weight, such as its size in bytes, and when the total weighs more than the limit the
 * least recently used entries are dropped until it fits. The entry just asked for is never dropped, so one thing bigger
 * than the whole limit still loads.
 * <p>
 * Dropping an entry only removes the cache's reference. It does not close it: whoever still holds it keeps a working
 * object, and its native memory is freed once the last holder lets go. Asking for a dropped key loads it again.
 * <p>
 * Safe from any thread. The loader runs outside the lock, so a slow decode does not hold up other lookups; if two threads
 * load the same key at once, the first to finish wins and both get that one.
 */
public final class WeightedCache<V> {
    private record Entry<V>(V value, long weight) {
    }

    private final LinkedHashMap<String, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long limit;
    private long total;

    /**
     * @param limit the total weight to stay under, 0 or less for no limit
     */
    public WeightedCache(long limit) {
        this.limit = limit;
    }

    /**
     * The cached value for {@code key}, loading it with {@code loader} (weighed by {@code weigher}) on a miss.
     */
    public V get(String key, Supplier<V> loader, ToLongFunction<V> weigher) {
        synchronized (this) {
            Entry<V> hit = entries.get(key);
            if (hit != null) return hit.value();
        }
        V loaded = loader.get();
        long weight = Math.max(0, weigher.applyAsLong(loaded));
        synchronized (this) {
            Entry<V> raced = entries.get(key);
            if (raced != null) return raced.value();
            entries.put(key, new Entry<>(loaded, weight));
            total += weight;
            trim();
            return loaded;
        }
    }

    private void trim() {
        if (limit <= 0) return;
        Iterator<Map.Entry<String, Entry<V>>> oldest = entries.entrySet().iterator();
        while (total > limit && entries.size() > 1 && oldest.hasNext()) {
            total -= oldest.next().getValue().weight();
            oldest.remove();
        }
    }

    /**
     * Changes the limit and drops what no longer fits. 0 or less means no limit.
     */
    public synchronized void setLimit(long limit) {
        this.limit = limit;
        trim();
    }

    public synchronized long getLimit() {
        return limit;
    }

    /**
     * The total weight of what is cached now.
     */
    public synchronized long getWeight() {
        return total;
    }

    public synchronized int size() {
        return entries.size();
    }

    /**
     * Drops {@code key}, so the next request loads it again. Returns whether it was cached.
     */
    public synchronized boolean remove(String key) {
        Entry<V> removed = entries.remove(key);
        if (removed == null) return false;
        total -= removed.weight();
        return true;
    }

    /**
     * Drops everything.
     */
    public synchronized void clear() {
        entries.clear();
        total = 0;
    }
}
