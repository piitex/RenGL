# Sprite animation

[Documentation](../README.md) › [Graphics](README.md)

Frame-by-frame animation, in `me.piitex.engine.graphics`:

| Class | What it is |
|---|---|
| `Animation` | The frames, how long each is shown, how it repeats, and the events frames report. Immutable, so one animation can be shared by every character that uses it. |
| `AnimationPlayer` | Plays animations for one character: keeps its place, moves it on, and reports events and the end. |

These are for pictures changing frame by frame. To move, fade or scale an element over time, see
[Animation](../animation/transitions.md).

```java nocompile
List<TextureRegion> frames = new TextureRegion(Texture.load("assets/hero.png")).split(32, 32);
Animation idle = Animation.of(frames.subList(0, 2), 0.5f, Animation.PlayMode.LOOP);
Animation walk = Animation.of(frames.subList(2, 8), 0.1f, Animation.PlayMode.LOOP)
        .withEvent(1, "footstep")      // frames 1 and 4 of the six: each foot down
        .withEvent(4, "footstep");

AnimationPlayer anim = new AnimationPlayer();
anim.onEvent(event -> { if (event.equals("footstep")) footstep.play(); });

// Scene.update
anim.play(moving ? walk : idle);   // switches only when the animation changes
anim.update(delta);
hero.setRegion(anim.getFrame());
```

## Animations

| Method | What it does |
|---|---|
| `Animation.of(regions, frameDuration, mode)` | Each region for `frameDuration` seconds. |
| `new Animation(frames, mode)` | From `Animation.Frame(region, duration[, event])` records, for frames of different lengths. |
| `withMode(mode)`, `withEvent(frame, event)`, `reversed()` | A changed copy. |
| `getDuration()` | Seconds for one pass. |
| `getRegionAt(time)` | The picture shown `time` seconds in, without a player. |

[Atlases](atlases.md) read Aseprite tags as animations, with each frame's own duration.

| Play mode | After the last frame |
|---|---|
| `ONCE` | Stays on it. |
| `LOOP` | Starts again from the first. |
| `PING_PONG` | Plays back to the first, then forwards again: 0 1 2 3 2 1 0 1 ... |

`getRegionAt(time)` suits things that animate on their own, such as every coin in a level spinning to
`GameTime.getTime()`. Add a different offset to each to put them out of step.

## Players

| Method | What it does |
|---|---|
| `play(animation)` | Switches to it from its first frame. Does nothing if it is already playing, even if finished, so it can be called every frame with whichever animation should show. |
| `restart()` | Plays the current animation again from the start. |
| `update(delta)` | Moves it on by `delta` seconds, times the speed. |
| `getFrame()` | The picture to show now. |
| `getFrameIndex()` | Which frame of the animation that is. |
| `isFinished()` | True once a `ONCE` animation has shown its last frame for its full time. |
| `setSpeed(s)` | 1 by default; 2 is twice as fast. |
| `setPaused(boolean)` | Holds the current frame. |

Pass `update` the scene's `delta` so animations follow [game time](../game/game-time.md): they slow down with the
time scale and stop while the game is paused.

## Events

An event is a name on a frame. The player reports it through `onEvent` each time that frame starts, including the
first frame (on the first `update` after `play`) and frames an update skips past: a slow frame never loses a
footstep. For timing things to the picture: a footstep sound, the frame a sword hits, the moment a jump leaves the
ground.

`onFinish` runs when a `ONCE` animation ends, typically to go back to idle:

```java nocompile
anim.onFinish(() -> anim.play(idle));
anim.play(attack);
```

Both callbacks run inside `update`, in the order things happened, and may switch animations. Several can be added.

An update that covers many passes of a looping animation at once reports the events of the last pass or two, not
every one it skipped.

## Things to know

- A player isn't thread-safe; use it on the render thread, from scene hooks.
- Durations must be above 0.
