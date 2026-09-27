# Events

[Documentation](../README.md) › [Game](README.md)

An `EventBus` (`me.piitex.engine.event`) lets parts of a game tell each other what happened without knowing about each
other. The enemy code posts `EnemyKilled`; the score, the sound and the achievements each listen for it, and none of
them needs a reference to the others.

```java nocompile
record EnemyKilled(Enemy enemy, int points) {}

EventBus events = new EventBus();
events.subscribe(EnemyKilled.class, e -> score += e.points());
events.subscribe(EnemyKilled.class, e -> sounds.play("death"));

events.post(new EnemyKilled(enemy, 100));
```

The engine keeps no bus of its own. Make one for the game, or one per level, and pass it to what needs it.

## Events

Any object is an event, and records suit them well. A listener for a type also receives its subtypes, so listening for
a shared interface catches a whole family:

```java nocompile
sealed interface PickUp permits CoinPickedUp, KeyPickedUp {}

events.subscribe(PickUp.class, p -> sounds.play("pickup")); // every kind of pick-up
events.subscribe(Object.class, e -> log.debug("{}", e));    // everything
```

The bus decides nothing else. There are no priorities, and no way for one listener to stop the others. An event that
should be stoppable carries its own flag for listeners to set and check.

## Posting

| Method | Delivers |
|---|---|
| `post(event)` | Now, on this thread, to each listener in the order they subscribed. Returns whether anyone received it |
| `postLater(event)` | On the render thread at the start of the next frame. Safe from any thread, such as a loading worker. Returns a `Task` that can cancel it until then |

A listener can post another event, which is delivered right away, before `post` returns to the first.

## Subscribing

`subscribe(type, listener)` returns a `Subscription`. `cancel()` it when the listener should stop hearing events,
typically when whatever listens is removed. It also works with try-with-resources, since `close()` does the same.

| Method | Description |
|---|---|
| `subscribe(type, listener)` | Listens for `type` and its subtypes |
| `hasListeners(type)` | Whether anything would receive an event of `type`, to skip building one nobody hears |
| `clear()` | Cancels every subscription on the bus |
| `Subscription.cancel()`, `isActive()` | Stops one listener; whether it still listens |

### From a scene

`Scene.subscribe(bus, type, listener)` subscribes until the scene is removed, so subscribing in `onEnter` needs no
clean-up:

```java nocompile
@Override
protected void onEnter() {
    subscribe(events, EnemyKilled.class, e -> scoreText.setText("Score: " + (score += e.points())));
}
```

A scene's listeners also hear events while the scene is paused under another. Check `isRunning()` in the listener to
ignore those.

## Things to know

- A listener that throws is logged, and the other listeners still get the event.
- Subscribing, cancelling and posting are safe from any thread and from inside a listener. A listener cancelled while
  an event is being delivered doesn't get it; one subscribed during delivery gets the next event, not this one.
- `post` runs listeners on the calling thread. Post from the render thread, or use `postLater`, when listeners touch
  the UI or game state.
