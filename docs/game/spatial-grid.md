# Spatial grid

[Documentation](../README.md) › [Game](README.md)

Finding what is near something by testing every thing in the game gets slow as the numbers grow: a hundred bullets
checked against a hundred enemies is ten thousand tests a step. A `SpatialGrid` (`me.piitex.engine.math`) cuts space
into square cells and files each thing under the cells it covers, so a question only looks at the few things in the
cells it touches.

```java nocompile
private final SpatialGrid<Enemy> grid = new SpatialGrid<>(128f);
private final List<Enemy> nearby = new ArrayList<>();

@Override
protected void fixedUpdate(float step) {
    grid.clear();
    for (Enemy enemy : enemies) grid.addCircle(enemy, enemy.x, enemy.y, enemy.radius);

    for (Bullet bullet : bullets) {
        for (Enemy enemy : grid.queryCircle(bullet.x, bullet.y, bullet.radius, nearby)) {
            if (enemy.hitBox().intersects(bullet.hitBox())) enemy.hurt(bullet.damage);
        }
    }
    Enemy target = grid.nearest(turret.x, turret.y, turret.range);
}
```

## Adding things

Each thing is filed with its bounds: a rectangle, a circle or a point.

| Method | Files it as |
|---|---|
| `add(item, x, y, width, height)`, `add(item, rect)` | A rectangle from its top-left corner |
| `add(item, shape)` | The [`bounds()`](geometry.md#shapes) of a shape. A grid of walls can add each with itself: `grid.add(wall, wall)` |
| `addCircle(item, x, y, radius)` | The square around a circle |
| `addPoint(item, x, y)` | A point |
| `remove(item)` | Takes it out |
| `clear()` | Takes everything out, keeping the memory to refill it |

Adding a thing that is already in the grid moves it. Two ways to keep a grid up to date:

- **Rebuild it each step.** `clear()`, then add everything again. Simple, and the grid reuses its memory, so it makes
  no garbage. Best when most things move.
- **Move things as they move.** `add` a thing again when it moves and `remove` it when it goes. A thing that stays in
  the same cells costs almost nothing to move. Best for many things of which few move, such as walls or pickups.

Things are told apart by identity (`==`), not `equals`: add the objects themselves, and add and remove the same
object, not a copy or a freshly boxed number.

## Asking

| Method | Answers |
|---|---|
| `query(x, y, width, height, results)`, `query(rect, results)` | The things whose bounds overlap or touch the rectangle |
| `queryCircle(x, y, radius, results)` | The things whose bounds come within `radius` of the point: what an explosion there could reach |
| `nearest(x, y, maxDistance)` | The nearest thing within `maxDistance`, or null. `Float.POSITIVE_INFINITY` searches everything |
| `nearest(x, y, maxDistance, filter)` | The nearest thing the filter accepts: `grid.nearest(x, y, range, e -> e.alive && e != self)` |
| `contains(item)`, `getBounds(item)`, `size()`, `isEmpty()` | What is in it |

The queries clear `results` and fill it, and return it, so one list can be reused for every question with no
garbage.

- **Bounds, not shapes.** A circle is filed as the square around it, so a query can return things whose corners are
  in the area but whose shapes aren't. Test the exact shape afterwards when it matters.
- **Touching counts.** A thing on the edge of the area is found, and so is a point exactly on it.
- **Distances are to the bounds,** the nearest point of them, so 0 for a thing whose bounds hold the point.
- **The order is repeatable.** The same adds give the same answers in the same order, so a seeded game replays the
  same way. Of two things equally near, `nearest` returns the same one each time.

## Choosing a cell size

Around the size of a typical thing, or a little bigger. Much smaller and a big thing is filed under many cells;
much bigger and each question looks through many things. For things of very different sizes, such as bullets and
bosses, two grids work better than one.

Coordinates can be anything, negative included, and the grid only keeps the cells in use, so a level can be any size.
A thing whose bounds cover more than 65,536 cells throws `IllegalArgumentException`, since that is almost always
bounds in the wrong units.

## Things to know

- **Not thread-safe.** Use it from the game loop.
- **Don't change the grid inside a `nearest` filter.** Queries are safe: they have finished filling their list
  before you loop over it.
- [`Slide.move`](geometry.md#pushing-apart-and-sliding) tests every solid it is given, so for a big level pass it
  the solids a query finds around the move:

```java nocompile
Rect box = player.hitBox();
Rect sweep = box.union(box.translate(dx, dy)).expand(1f);
Slide slide = Slide.move(box, dx, dy, walls.query(sweep, nearbyWalls));
```
