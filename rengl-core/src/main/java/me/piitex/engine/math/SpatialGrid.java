package me.piitex.engine.math;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Finds the things near a place quickly, without testing every thing in the game: the enemies within reach of an
 * explosion, the pickup nearest the player, the walls around a moving character. Space is cut into square cells and
 * each thing is filed under the cells its bounds cover, so a question only looks at the few cells it covers.
 * <pre>{@code
 * private final SpatialGrid<Enemy> grid = new SpatialGrid<>(128f);
 * private final List<Enemy> nearby = new ArrayList<>();
 *
 * protected void fixedUpdate(float step) {
 *     grid.clear();
 *     for (Enemy enemy : enemies) grid.addCircle(enemy, enemy.x, enemy.y, enemy.radius); // or move them as they move
 *
 *     for (Enemy enemy : grid.queryCircle(blast.x, blast.y, blast.radius, nearby)) enemy.hurt(blast.damage);
 *     Enemy target = grid.nearest(turret.x, turret.y, turret.range);
 * }
 * }</pre>
 * Things are added with their bounds: a rectangle, a circle or a point. Adding a thing that is already in the grid
 * moves it. Either clear and re-add everything each step, or {@link #add} each thing again when it moves and
 * {@link #remove} it when it goes; re-adding a thing that stays in the same cells costs little.
 * <p>
 * Questions test the things' bounds, not their exact shapes, so a circle's corners count. Test the exact shape
 * afterwards when it matters. A thing touching the area counts as in it, so a point on an area's edge is found.
 * Answers come in the same order for the same adds, so a seeded game replays the same way.
 * <p>
 * <b>Cell size.</b> Around the size of a typical thing, or a little bigger, works well. Much smaller and a big thing
 * is filed under many cells; much bigger and each question looks through many things. Coordinates can be anything,
 * negative included.
 * <p>
 * Things are told apart by identity ({@code ==}), not {@code equals}: add the objects themselves, and add and remove
 * the same object, not a copy or a freshly boxed number. Not thread-safe.
 */
public final class SpatialGrid<T> {
    /** A thing covering more cells than this is surely a mistake, such as bounds in the wrong units. */
    private static final long MAX_CELLS_PER_ITEM = 1 << 16;
    private final float cellSize;
    private final Map<T, Entry<T>> entries = new IdentityHashMap<>();
    private final List<Entry<T>> spareEntries = new ArrayList<>();
    // Cells by their packed coordinates, in an open-addressed table: null is an empty slot. Emptied cells stay, to be
    // reused, until clear() finds far more cells than things.
    private Cell<T>[] table = newTable(64);
    private int cellCount;
    // Cells holding something since the last clear(), in the order they were first filled.
    private final List<Cell<T>> filledCells = new ArrayList<>();
    private final List<Cell<T>> scratchCells = new ArrayList<>();
    private int queryStamp;

    /**
     * An empty grid of square cells {@code cellSize} across.
     *
     * @throws IllegalArgumentException if {@code cellSize} is not above 0
     */
    public SpatialGrid(float cellSize) {
        if (!(cellSize > 0f) || Float.isInfinite(cellSize)) throw new IllegalArgumentException("cellSize must be above 0: " + cellSize);
        this.cellSize = cellSize;
    }

    public float getCellSize() {
        return cellSize;
    }

    /**
     * Adds {@code item} with the rectangle from ({@code x}, {@code y}) that is {@code width} by {@code height} as its
     * bounds, or moves it there if it is already in the grid. A size of 0 makes a point.
     *
     * @throws IllegalArgumentException if a coordinate is not a finite number, a size is negative, or the bounds cover
     *                                  more than 65,536 cells
     */
    public void add(T item, float x, float y, float width, float height) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width) || !Float.isFinite(height)) {
            throw new IllegalArgumentException("bounds must be finite: " + x + ", " + y + ", " + width + ", " + height);
        }
        if (width < 0f || height < 0f) throw new IllegalArgumentException("size must not be negative: " + width + " by " + height);
        int minCellX = cell(x), minCellY = cell(y), maxCellX = cell(x + width), maxCellY = cell(y + height);
        if (((long) maxCellX - minCellX + 1) * ((long) maxCellY - minCellY + 1) > MAX_CELLS_PER_ITEM) {
            throw new IllegalArgumentException("bounds " + width + " by " + height + " cover more than " + MAX_CELLS_PER_ITEM
                    + " cells of " + cellSize + "; use bigger cells");
        }
        Entry<T> entry = entries.get(item);
        if (entry == null) {
            entry = spareEntries.isEmpty() ? new Entry<>() : spareEntries.removeLast();
            entry.item = item;
            entries.put(item, entry);
        } else if (entry.minCellX == minCellX && entry.minCellY == minCellY && entry.maxCellX == maxCellX && entry.maxCellY == maxCellY) {
            entry.setBounds(x, y, width, height); // still in the same cells
            return;
        } else {
            fileAway(entry, false);
        }
        entry.setBounds(x, y, width, height);
        entry.minCellX = minCellX;
        entry.minCellY = minCellY;
        entry.maxCellX = maxCellX;
        entry.maxCellY = maxCellY;
        fileAway(entry, true);
    }

    /**
     * Adds {@code item} with {@code bounds}, or moves it there.
     */
    public void add(T item, Rect bounds) {
        add(item, bounds.x(), bounds.y(), Math.max(bounds.width(), 0f), Math.max(bounds.height(), 0f));
    }

    /**
     * Adds {@code item} with the bounds of {@code shape}, or moves it there. A grid of {@link Shape}s can add each
     * with itself: {@code grid.add(wall, wall)}.
     */
    public void add(T item, Shape shape) {
        add(item, shape.bounds());
    }

    /**
     * Adds {@code item} with the bounds of the circle of {@code radius} around ({@code x}, {@code y}), or moves it
     * there.
     */
    public void addCircle(T item, float x, float y, float radius) {
        float r = Math.max(radius, 0f);
        add(item, x - r, y - r, r * 2f, r * 2f);
    }

    /**
     * Adds {@code item} as a point, or moves it there.
     */
    public void addPoint(T item, float x, float y) {
        add(item, x, y, 0f, 0f);
    }

    /**
     * Takes {@code item} out of the grid. Returns false if it wasn't in it.
     */
    public boolean remove(T item) {
        Entry<T> entry = entries.remove(item);
        if (entry == null) return false;
        fileAway(entry, false);
        entry.item = null;
        spareEntries.add(entry);
        return true;
    }

    public boolean contains(T item) {
        return entries.containsKey(item);
    }

    /**
     * The bounds {@code item} was last added with, or null if it isn't in the grid.
     */
    public Rect getBounds(T item) {
        Entry<T> entry = entries.get(item);
        return entry == null ? null : new Rect(entry.minX, entry.minY, entry.maxX - entry.minX, entry.maxY - entry.minY);
    }

    /**
     * How many things are in the grid.
     */
    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Takes everything out, keeping the memory to refill the grid without making garbage.
     */
    public void clear() {
        for (Cell<T> cell : filledCells) {
            Arrays.fill(cell.entries, 0, cell.count, null);
            cell.count = 0;
            cell.filled = false;
        }
        for (Entry<T> entry : entries.values()) {
            entry.item = null;
            spareEntries.add(entry);
        }
        // Things that roam leave empty cells behind; drop them once there are far more than were in use.
        if (cellCount > 4096 && cellCount > filledCells.size() * 8) {
            table = newTable(64);
            cellCount = 0;
        }
        filledCells.clear();
        entries.clear();
    }

    /**
     * The things whose bounds overlap or touch the rectangle from ({@code x}, {@code y}) that is {@code width} by
     * {@code height}, put in {@code results} after clearing it. Returns {@code results}, ready for a for loop.
     */
    public List<T> query(float x, float y, float width, float height, List<T> results) {
        results.clear();
        float right = x + width, bottom = y + height;
        int stamp = nextStamp();
        for (Cell<T> cell : cellsIn(x, y, right, bottom)) {
            for (int i = 0; i < cell.count; i++) {
                Entry<T> entry = cell.entries[i];
                if (entry.stamp == stamp) continue;
                entry.stamp = stamp;
                if (entry.minX <= right && entry.maxX >= x && entry.minY <= bottom && entry.maxY >= y) results.add(entry.item);
            }
        }
        return results;
    }

    /**
     * The things whose bounds overlap or touch {@code area}, put in {@code results} after clearing it.
     */
    public List<T> query(Rect area, List<T> results) {
        return query(area.x(), area.y(), area.width(), area.height(), results);
    }

    /**
     * The things whose bounds come within {@code radius} of ({@code x}, {@code y}), put in {@code results} after
     * clearing it: everything an explosion there could reach.
     */
    public List<T> queryCircle(float x, float y, float radius, List<T> results) {
        results.clear();
        float r = Math.max(radius, 0f), radiusSquared = r * r;
        int stamp = nextStamp();
        for (Cell<T> cell : cellsIn(x - r, y - r, x + r, y + r)) {
            for (int i = 0; i < cell.count; i++) {
                Entry<T> entry = cell.entries[i];
                if (entry.stamp == stamp) continue;
                entry.stamp = stamp;
                if (entry.distanceSquared(x, y) <= radiusSquared) results.add(entry.item);
            }
        }
        return results;
    }

    /**
     * The thing nearest ({@code x}, {@code y}) within {@code maxDistance}, measured to the nearest point of its
     * bounds, so 0 for one whose bounds hold the point. Null if nothing is that close. Use
     * {@link Float#POSITIVE_INFINITY} to search everything. Of two things equally near, the one found first wins,
     * which is the same one each time for the same adds.
     */
    public T nearest(float x, float y, float maxDistance) {
        return nearest(x, y, maxDistance, null);
    }

    /**
     * The nearest thing that {@code filter} accepts, such as the nearest living enemy that isn't the one asking.
     * {@code filter} may be null to accept everything.
     */
    public T nearest(float x, float y, float maxDistance, Predicate<? super T> filter) {
        if (entries.isEmpty() || !(maxDistance >= 0f)) return null;
        int stamp = nextStamp();
        int centerX = cell(x), centerY = cell(y);
        float[] bestSquared = {maxDistance == Float.POSITIVE_INFINITY ? Float.POSITIVE_INFINITY : maxDistance * maxDistance};
        Entry<T> best = null;
        long maxRing = (long) Math.min(Math.ceil(maxDistance / cellSize) + 1.0, Long.MAX_VALUE);
        // Search square rings of cells outwards. Anything not yet seen is at least (ring - 1) cells away, so once the
        // best is nearer than that there is no need to go on.
        for (long ring = 0; ring <= maxRing; ring++) {
            if (ring > 0) {
                float nearestPossible = (ring - 1) * cellSize;
                if (nearestPossible * nearestPossible > bestSquared[0]) break;
            }
            if (ring * 8 > filledCells.size()) {
                // A ring would look up more cells than are filled: look through the filled ones instead, and be done.
                for (Cell<T> cell : filledCells) best = closer(cell, x, y, stamp, filter, best, bestSquared);
                break;
            }
            // The cells ring cells away: the top and bottom rows whole, then the two sides between them.
            for (long cy = centerY - ring; cy <= centerY + ring; cy++) {
                boolean wholeRow = ring == 0 || cy == centerY - ring || cy == centerY + ring;
                for (long cx = centerX - ring; cx <= centerX + ring; cx += wholeRow ? 1 : ring * 2) {
                    Cell<T> cell = find(pack(cx, cy));
                    if (cell != null) best = closer(cell, x, y, stamp, filter, best, bestSquared);
                }
            }
        }
        return best == null ? null : best.item;
    }

    // The nearer of best and the unseen things of cell that the filter accepts. bestSquared holds best's distance.
    private Entry<T> closer(Cell<T> cell, float x, float y, int stamp, Predicate<? super T> filter, Entry<T> best, float[] bestSquared) {
        for (int i = 0; i < cell.count; i++) {
            Entry<T> entry = cell.entries[i];
            if (entry.stamp == stamp) continue;
            entry.stamp = stamp;
            float distanceSquared = entry.distanceSquared(x, y);
            if (distanceSquared > bestSquared[0] || distanceSquared == bestSquared[0] && best != null) continue;
            if (filter != null && !filter.test(entry.item)) continue;
            bestSquared[0] = distanceSquared;
            best = entry;
        }
        return best;
    }

    // ---- Cells

    private int cell(float coordinate) {
        return (int) Math.floor(Math.clamp(coordinate / cellSize, Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    private static long pack(long cellX, long cellY) {
        return cellX << 32 | cellY & 0xFFFFFFFFL;
    }

    private int nextStamp() {
        if (++queryStamp == 0) {
            // Wrapped around after four billion questions: forget the old stamps so none is mistaken for this one.
            for (Entry<T> entry : entries.values()) entry.stamp = 0;
            queryStamp = 1;
        }
        return queryStamp;
    }

    // The filled cells whose squares overlap the area, row by row; or, when the area covers more cells than are
    // filled, the filled cells in the area in the order they were filled. Reuses one list.
    private List<Cell<T>> cellsIn(float left, float top, float right, float bottom) {
        scratchCells.clear();
        if (filledCells.isEmpty() || !(right >= left) || !(bottom >= top)) return scratchCells;
        int minX = cell(left), minY = cell(top), maxX = cell(right), maxY = cell(bottom);
        long area = ((long) maxX - minX + 1) * ((long) maxY - minY + 1);
        if (area > filledCells.size()) {
            for (Cell<T> cell : filledCells) {
                if (cell.count > 0 && cell.x >= minX && cell.x <= maxX && cell.y >= minY && cell.y <= maxY) scratchCells.add(cell);
            }
            return scratchCells;
        }
        for (long cy = minY; cy <= maxY; cy++) {
            for (long cx = minX; cx <= maxX; cx++) {
                Cell<T> cell = find(pack(cx, cy));
                if (cell != null && cell.count > 0) scratchCells.add(cell);
            }
        }
        return scratchCells;
    }

    private void fileAway(Entry<T> entry, boolean add) {
        for (long cy = entry.minCellY; cy <= entry.maxCellY; cy++) {
            for (long cx = entry.minCellX; cx <= entry.maxCellX; cx++) {
                if (add) {
                    Cell<T> cell = findOrCreate(cx, cy);
                    cell.add(entry);
                    if (!cell.filled) {
                        cell.filled = true;
                        filledCells.add(cell);
                    }
                } else {
                    Cell<T> cell = find(pack(cx, cy));
                    if (cell != null) cell.remove(entry);
                }
            }
        }
    }

    private Cell<T> find(long key) {
        int mask = table.length - 1;
        for (int slot = slot(key, mask); ; slot = slot + 1 & mask) {
            Cell<T> cell = table[slot];
            if (cell == null) return null;
            if (cell.key == key) return cell;
        }
    }

    private Cell<T> findOrCreate(long cellX, long cellY) {
        long key = pack(cellX, cellY);
        int mask = table.length - 1;
        int slot = slot(key, mask);
        for (; table[slot] != null; slot = slot + 1 & mask) {
            if (table[slot].key == key) return table[slot];
        }
        Cell<T> cell = new Cell<>(key, (int) cellX, (int) cellY);
        table[slot] = cell;
        if (++cellCount > table.length / 2) grow();
        return cell;
    }

    private void grow() {
        Cell<T>[] old = table;
        table = newTable(old.length * 2);
        int mask = table.length - 1;
        for (Cell<T> cell : old) {
            if (cell == null) continue;
            int slot = slot(cell.key, mask);
            while (table[slot] != null) slot = slot + 1 & mask;
            table[slot] = cell;
        }
    }

    private static int slot(long key, int mask) {
        long mixed = key * 0x9E3779B97F4A7C15L;
        return (int) (mixed ^ mixed >>> 32) & mask;
    }

    @SuppressWarnings("unchecked")
    private static <T> Cell<T>[] newTable(int size) {
        return (Cell<T>[]) new Cell[size];
    }

    private static final class Cell<T> {
        final long key;
        final int x, y;
        @SuppressWarnings("unchecked")
        Entry<T>[] entries = (Entry<T>[]) new Entry[4];
        int count;
        boolean filled; // in filledCells since the last clear()

        Cell(long key, int x, int y) {
            this.key = key;
            this.x = x;
            this.y = y;
        }

        void add(Entry<T> entry) {
            if (count == entries.length) entries = Arrays.copyOf(entries, count * 2);
            entries[count++] = entry;
        }

        void remove(Entry<T> entry) {
            for (int i = 0; i < count; i++) {
                if (entries[i] == entry) {
                    // Shift down rather than swap in the last, so the order things were added in is kept.
                    System.arraycopy(entries, i + 1, entries, i, count - i - 1);
                    entries[--count] = null;
                    return;
                }
            }
        }
    }

    private static final class Entry<T> {
        T item;
        float minX, minY, maxX, maxY;
        int minCellX, minCellY, maxCellX, maxCellY;
        int stamp;

        void setBounds(float x, float y, float width, float height) {
            minX = x;
            minY = y;
            maxX = x + width;
            maxY = y + height;
        }

        float distanceSquared(float x, float y) {
            float dx = Math.max(Math.max(minX - x, 0f), x - maxX);
            float dy = Math.max(Math.max(minY - y, 0f), y - maxY);
            return dx * dx + dy * dy;
        }
    }
}
