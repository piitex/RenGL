# Geometry and raycasts

[Documentation](../README.md) › [Game](README.md)

`me.piitex.engine.math` has points, shapes and the questions a game asks of them: is this point inside, do these
two overlap, what does this ray hit first, and how to push them apart. [`Slide`](#pushing-apart-and-sliding) moves a
shape among solid ones, stopping at walls and landing on floors. Nothing moves or bounces by itself: what a hit means
is up to the game.

Two neighbours in the same package have pages of their own: [`SpatialGrid`](spatial-grid.md), for finding the things
near a place quickly, and [`Rng`](random.md), for random numbers.

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
| `moveTowards(target, maxDistance)` | Moved towards `target` by at most `maxDistance`, without overshooting |
| `limit(maxLength)` | No longer than `maxLength`, same direction: a top speed |

`Vec2` makes a new object for every step of a sum, which is fine almost everywhere. In a loop over thousands of
things each step, the [`MathUtil`](#mathutil) helpers take plain numbers instead.

## Shapes

`Shape` is a `Rect`, a `Circle` or a convex `Polygon`. All three are immutable and share these methods:

| Method | Result |
|---|---|
| `contains(x, y)` | Whether the point is inside |
| `intersects(shape)` | Whether the two overlap, for any pair of shapes |
| `overlap(shape)` | How to push this shape out of the other, or null. See [Pushing apart and sliding](#pushing-apart-and-sliding) |
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

A convex outline: a triangle, a slope, a turned box. A concave shape (an L, a star) is several, which
`Polygon.decompose` works out.

| Creating | |
|---|---|
| `new Polygon(x0, y0, x1, y1, ...)` | Through the points, in either winding. Throws `IllegalArgumentException` if it is not convex, crosses itself, or has no area |
| `Polygon.of(Vec2...)` | The same from points |
| `Polygon.box(centerX, centerY, width, height, degrees)` | A turned rectangle: a sprite's hit box |
| `Polygon.regular(centerX, centerY, radius, sides)` | A triangle, hexagon and so on |
| `Polygon.decompose(x0, y0, x1, y1, ...)` | A `List<Polygon>`: any outline that doesn't cross itself, concave too, cut into convex pieces that cover it exactly. A convex outline comes back as one |

`rotate(degrees, originX, originY)` turns it. `getPointCount()`, `getX(i)`, `getY(i)` and `getPoints()` read the
corners, which are kept clockwise on screen.

To draw one, use `renderer.drawPolygon(polygon, color)` or `renderer.drawPolygonOutline(polygon, color, thickness)`.
The renderer can also fill concave shapes and ones that cross themselves; see
[the renderer](../reference/custom-elements.md#the-renderer).

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

## Pushing apart and sliding

`overlap` says how two overlapping shapes are to be pushed apart: move the first `depth()` along `normal()`, a
direction of length 1 pointing away from the second, and they only touch. It is null when they don't overlap, exactly
when `intersects` is false.

```java nocompile
Overlap overlap = player.hitBox().overlap(crate.hitBox());
if (overlap != null) {
    player.x += overlap.pushX();   // normal().x() * depth()
    player.y += overlap.pushY();
}
```

It is the shortest way out, so a shape that moved most of the way through another in one step comes out of the far
side. When two ways out of a rectangle or circle are equally short, up wins.

`Slide.move` does the whole job for a character moving through a level: it moves a shape, stops it at walls, lands it
on floors and slides it along both.

```java nocompile
@Override
protected void fixedUpdate(float step) {
    velocityY += GRAVITY * step;
    Slide slide = Slide.move(player.hitBox(), velocityX * step, velocityY * step, walls);
    player.x += slide.movedX();
    player.y += slide.movedY();
    Vec2 velocity = slide.slideVelocity(velocityX, velocityY); // landing stops the fall, a wall stops the run
    velocityX = velocity.x();
    velocityY = velocity.y();
    grounded = slide.onFloor();
}
```

| Result | |
|---|---|
| `movedX()`, `movedY()` | How far it actually went: add them to the owner's position |
| `shape()` | The shape where it ended up |
| `onFloor()`, `onWall()`, `onCeiling()` | What it hit. A floor faces up, up to 45 degrees off level; steeper is a wall |
| `hit()`, `normals()` | Whether it hit anything, and the direction each different surface faces |
| `slideVelocity(vx, vy)` | The velocity without the part heading into the surfaces it hit |

- **It can't tunnel.** It moves in steps of at most half the shape's smaller side, so a fast fall still lands.
- **Tile floors are smooth.** Overlaps are pushed out deepest first, so a box running along a floor of many tiles
  doesn't catch on the seams between them.
- **Slopes hold.** Floors push straight up, and on a floor `slideVelocity` changes only the vertical speed, so a
  character standing on a slope stays put and one running up it climbs. Running down a slope faster than gravity
  pulls leaves the floor for a moment at each step, as in most simple platformers.
- **A shape that starts inside a solid** is pushed out of it.
- **Keep gravity on while standing.** A floor only counts while the move presses into it.
- **Pass only the solids nearby.** Every solid is tested at every step. For a big level, ask a
  [`SpatialGrid`](spatial-grid.md) for the solids around the move and pass those.

## MathUtil

Number helpers for game code.

| Method | Result |
|---|---|
| `clamp(value, min, max)` | Kept within the range |
| `lerp(a, b, t)`, `inverseLerp(a, b, value)` | Blend, and where a value sits between two others |
| `approach(current, target, maxStep)` | Moved towards the target by at most `maxStep`, without overshooting: steady acceleration |
| `damp(current, target, rate, delta)` | Moved part of the way, easing in: a camera following the player. Unlike `lerp` with a fixed fraction each frame, it gives the same motion at any frame rate |
| `distance(x0, y0, x1, y1)`, `distanceSquared(...)`, `length(x, y)` | Distances without making a `Vec2`, and faster than `Math.hypot` |
| `wrapAngle(degrees)` | Into -180 to 180: `wrapAngle(target - current)` is the shortest turn |
| `angleTo(fromX, fromY, toX, toY)` | The direction from one point to another in degrees: 0 is right, 90 down |
| `angleDifference(from, to)` | The shortest turn between two headings, from -180 to 180 |
| `rotateTowards(current, target, maxStep)` | A heading turned the short way towards another by at most `maxStep` degrees: a turret with a turning speed |
| `lerpAngle(from, to, t)` | A heading `t` of the way to another, going the short way round |
| `progress(time, delay, duration)` | How far through an animation that starts `delay` seconds in and lasts `duration` is: 0 before, rising evenly to 1, then 1 |
| `progress(time, delay, duration, easing)` | The same, shaped by an [`Easing`](../animation/transitions.md#easing) |
| `pulse(time, period)` | Rises smoothly from 0 to 1 and back every `period` seconds: 0 at time 0, 1 half a period later. For anything that breathes, blinks or throbs |
| `fadeInOut(time, lifetime, fadeIn, fadeOut)` | The opacity of something that lives `lifetime` seconds: fading in over the first `fadeIn`, out over the last `fadeOut`, 0 outside its life |

```java nocompile
@Override
protected void update(float delta) {
    cameraX = MathUtil.damp(cameraX, player.x, 8f, delta);
    float aim = MathUtil.angleTo(turret.x, turret.y, player.x, player.y);
    turret.angle = MathUtil.rotateTowards(turret.angle, aim, 180f * delta); // at most 180 degrees a second
    if (MathUtil.distanceSquared(turret.x, turret.y, player.x, player.y) < range * range) turret.fire();
}
```

The last four work an animation out from a clock, such as [`Scene.getAge()`](scenes.md#popup-scenes), each time it is drawn, with
nothing to start or cancel:

```java nocompile
@Override
protected void render(Renderer renderer, float alpha) {
    for (int i = 0; i < cards.size(); i++) {
        // Each card rises 90 points into place, a moment after the one before
        float rise = 1f - MathUtil.progress(getAge(), 0.06f * i, 0.35f, Easing.EASE_OUT_CUBIC);
        drawCard(renderer, cards.get(i), y + rise * 90f);
    }
    float glow = MathUtil.pulse(getAge(), 2f); // 0 to 1 and back every 2 seconds
    renderer.drawCircle(x, y, 40f + 6f * glow, gold.withOpacity(0.2f + 0.3f * glow));
}
```

## Seeing them

[`DebugDraw.shape(shape, color)`](../graphics/debug-drawing.md) draws the outline of any shape, which is the quickest
way to check a hit box lines up with its sprite.
