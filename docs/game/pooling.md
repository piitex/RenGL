# Object pools

[Documentation](../README.md) › [Game](README.md)

A `Pool` (`me.piitex.engine.utils`) keeps objects the game has finished with so they can be used again, instead of
creating new ones. It suits things a game makes and throws away many times a second: bullets, particles, damage
numbers, sound voices.

```java nocompile
Pool<Bullet> bullets = new Pool<>(Bullet::new);

Bullet bullet = bullets.obtain(); // a free one, or a new one when none are free
bullet.fire(x, y, direction);
...
bullets.free(bullet);             // reset, and kept for the next obtain
```

Creating objects is cheap in Java, so a pool isn't always worth it. Reach for one when a profiler shows garbage
collection pauses during play, or when an object is costly to set up.

## Resetting

A freed object is reset so the next `obtain` gets a clean one, and so it lets go of whatever it pointed at while it
waits. There are two ways, and a pool uses both if both are there:

1. The object implements `Poolable`, and the pool calls its `reset()`.
2. The pool has a reset function: `new Pool<>(ArrayList::new, List::clear)`.

```java nocompile
class Bullet implements Poolable {
    float x, y;
    Vec2 velocity = Vec2.ZERO;
    Enemy target;

    @Override
    public void reset() {
        velocity = Vec2.ZERO;
        target = null;
    }
}
```

`obtain` hands out an object as it is, so whoever obtains it sets it up.

## Using one in a scene

Keep a list of the live objects, and free each one as it finishes:

```java nocompile
public class Level extends Scene {
    private final Pool<Bullet> bullets = new Pool<>(Bullet::new);
    private final List<Bullet> active = new ArrayList<>();

    void shoot(float x, float y, Vec2 direction) {
        Bullet bullet = bullets.obtain();
        bullet.fire(x, y, direction);
        active.add(bullet);
    }

    @Override
    protected void update(float delta) {
        active.removeIf(bullet -> {
            bullet.update(delta);
            if (bullet.isAlive()) return false;
            bullets.free(bullet);
            return true;
        });
    }

    @Override
    protected void onExit() {
        bullets.freeAll(active);
        active.clear();
    }
}
```

## Methods

| Method | Description |
|---|---|
| `new Pool<>(factory)` | Creates objects with `factory` and keeps every one freed |
| `new Pool<>(factory, reset)` | Also runs `reset` on each object freed |
| `new Pool<>(factory, reset, maxFree)` | Keeps at most `maxFree` free objects. One freed while the pool is full is reset and dropped, so a spike, such as one huge explosion, doesn't hold its objects forever |
| `obtain()` | A free object, or a new one. The last one freed comes out first |
| `free(object)` | Resets it and keeps it. Throws `IllegalStateException` if it is already free in this pool |
| `freeAll(objects)` | Frees each one. The collection is left as it is, so clear it afterwards |
| `fill(count)` | Creates objects until `count` are free, so the first ones in play needn't be created then. Returns the pool |
| `clear()` | Drops the free objects. Objects in use can still be freed back |
| `getFreeCount()` | How many are waiting for `obtain` |
| `getCreatedCount()` | How many the factory has made in all. When it stops growing during play, every object is being reused |
| `getMaxFree()` | The limit given, `Integer.MAX_VALUE` for none |

## Things to know

- **Stop using an object once it is freed.** The next `obtain` hands it to someone else. Cancel anything that still
  refers to it first, such as a [tween](../animation/tweens-and-sequences.md) moving it or an
  [event subscription](events.md).
- Freeing the same object twice throws, rather than letting two owners share it. An object freed while the pool was
  full, or before `clear()`, is no longer in the pool, so it can be freed again.
- A reset that throws is passed on to the caller of `free`, and the object isn't kept.
- A pool isn't thread-safe. Use each pool from one thread, normally the render thread.
- The engine keeps no pools of its own. Make one for each kind of object, where the game needs it.
