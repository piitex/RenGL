# Geometry and raycasts

[Documentation](../README.md) › [Game](README.md)

`me.piitex.engine.math` has points, shapes and the questions a game asks of them: is this point inside, do these
two overlap, what does this ray hit first. These are queries only. Nothing moves, bounces or gets pushed apart: what
a hit means is up to the game.

Coordinates are the engine's usual ones, with y growing downwards. Angles are in degrees, and positive turns clockwise
on screen, the same as [`Camera.setRotation`](../graphics/camera.md).

## Vec2

An immutable point or direction. Every operation returns a new one.

```java nocompile
Vec2 velocity = new Vec2(3, 4);
float speed = velocity.length();                    // 5
Vec2 heading = velocity.normalize();                // (0.6, 0.8)
Vec2 next = position.add(velocity.scale(step));
Vec2 aim = Vec2.fromAngle(turret.angle);            // 0 = right, 90 = down
Vec2 halfway = Vec2.lerp(start, end, 0.5f);
```

| Method | Result |
|---|---|
| `add`, `sub`, `scale`, `negate` | The arithmetic you expect. `add(dx, dy)` takes numbers too |
| `dot(other)`, `cross(other)` | `cross` is positive when `other` is clockwise of this vector on screen |
| `length()`, `lengthSquared()`, `distance(other)`, `distanceSquared(other)` | The squared forms skip a square root, enough to compare |
| `normalize()` | Length 1, or `ZERO` for the zero vector |
| `perpendicular()`, `rotate(degrees)` | Turned 90 degrees, or any angle, clockwise |
| `angle()`, `Vec2.fromAngle(degrees)` | Direction as an angle, from -180 to 180, and back |
| `Vec2.lerp(a, b, t)` | The point `t` of the way from `a` to `b` |

## Shapes

`Shape` is a `Rect`, a `Circle` or a convex `Polygon`. All three are immutable and share these methods:

| Method | Result |
|---|---|
| `contains(x, y)` | Whether the point is inside |
| `intersects(shape)` | Whether the two overlap, for any pair of shapes |
| `bounds()` | The `Rect` around it |
| `translate(dx, dy)` | The shape moved |
| `raycast(...)`, `segmentCast(...)` | Where a ray or a line first hits it. See [Raycasts](#raycasts) |

```java nocompile
Rect wall = new Rect(100, 0, 20, 300);              // top-left corner, width, height
Circle blast = new Circle(x, y, 64);                // centre and radius
Polygon hitBox = Polygon.box(x, y, 40, 16, angle);  // a turned box, centred on (x, y)

if (blast.intersects(enemy.hitBox())) enemy.damage(10);
```

Keep a hit box relative to its owner and move it into place when testing, or build a new one each step:

```java nocompile
private static final Rect FEET = new Rect(-8, 12, 16, 4);

boolean grounded(Player p) {
    Rect feet = FEET.translate(p.x, p.y);
    return level.solids().stream().anyMatch(feet::intersects);
}
```

### What counts

- **Overlap needs area.** Shapes that only touch along an edge or at a point don't intersect, so a box sitting
  exactly on the floor isn't in it. A zero-size rectangle or a zero radius overlaps nothing.
- **`contains` includes the outline** for circles and polygons. A `Rect` includes only its top and left edges, so
  tiles that share an edge never both contain a point on it.

### Rect

Besides the shape methods: `right()`, `bottom()`, `centerX()`, `centerY()`, `isEmpty()`, `contains(Rect)` for a
rectangle wholly inside, `union(Rect)` for the rectangle around both, `expand(amount)` to grow or shrink it on every
side, and `Rect.fromCorners(x0, y0, x1, y1)` in either order.

### Circle

A centre and a radius: `new Circle(x, y, radius)`.

### Polygon

A convex outline: a triangle, a slope, a turned box. For a concave shape (an L, a star), use several.

| Creating | |
|---|---|
| `new Polygon(x0, y0, x1, y1, ...)` | Through the points, in either winding. Throws `IllegalArgumentException` if it is not convex, crosses itself, or has no area |
| `Polygon.of(Vec2...)` | The same from points |
| `Polygon.box(centerX, centerY, width, height, degrees)` | A turned rectangle: a sprite's hit box |
| `Polygon.regular(centerX, centerY, radius, sides)` | A triangle, hexagon and so on |

`rotate(degrees, originX, originY)` turns it. `getPointCount()`, `getX(i)`, `getY(i)` and `getPoints()` read the
corners, which are kept clockwise on screen.

## Raycasts

A ray starts at a point, heads in a direction, and reports where it first meets a shape's outline:

```java nocompile
RayHit hit = wall.raycast(eyeX, eyeY, dirX, dirY, 500f); // null if it misses within 500
if (hit != null) {
    float distance = hit.distance();
    Vec2 where = hit.point();
    Vec2 normal = hit.normal(); // the outline's outward direction there, length 1: for bouncing or decals
}

boolean blocked = wall.segmentCast(guard.x, guard.y, player.x, player.y) != null; // line of sight
```

- The direction needn't have length 1. Distances are in the same units as the coordinates. Use
  `Float.POSITIVE_INFINITY` for a ray with no end.
- A ray that starts inside the shape hits at distance 0, at its start, with a zero normal.
- A zero direction or a negative distance throws `IllegalArgumentException`.
- To find the nearest of many shapes, cast against each and keep the smallest `distance()`. Test `bounds()` first
  when there are a lot of them.

## MathUtil

Number helpers for game code.

| Method | Result |
|---|---|
| `clamp(value, min, max)` | Kept within the range |
| `lerp(a, b, t)`, `inverseLerp(a, b, value)` | Blend, and where a value sits between two others |
| `approach(current, target, maxStep)` | Moved towards the target by at most `maxStep`, without overshooting: steady acceleration |
| `damp(current, target, rate, delta)` | Moved part of the way, easing in: a camera following the player. Unlike `lerp` with a fixed fraction each frame, it gives the same motion at any frame rate |
| `wrapAngle(degrees)` | Into -180 to 180: `wrapAngle(target - current)` is the shortest turn |

```java nocompile
@Override
protected void update(float delta) {
    cameraX = MathUtil.damp(cameraX, player.x, 8f, delta);
    turretAngle += MathUtil.clamp(MathUtil.wrapAngle(aimAngle - turretAngle), -180 * delta, 180 * delta);
}
```

## Seeing them

[`DebugDraw.shape(shape, color)`](../graphics/debug-drawing.md) draws the outline of any shape, which is the quickest
way to check a hit box lines up with its sprite.
