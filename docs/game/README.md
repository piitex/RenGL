# Game

[Documentation](../README.md) › Game

The pieces a game builds on, on top of the UI toolkit.

| Page | What it covers |
|---|---|
| [Scenes](scenes.md) | `Scene` and `SceneManager`: main menu, gameplay, pause menu, and moving between them |
| [Entities and scene files](entities.md) | `EntityScene`, `Entity`, `Component` and `@Property`: scenes built from data, saved to and loaded from JSON |
| [Game time](game-time.md) | `GameTime`: the fixed-step game loop, time scale, pausing, and smooth drawing between steps |
| [Scene transitions](transitions.md) | `SceneTransition`: fades, slides, wipes and your own effects when the scenes change |
| [Geometry and raycasts](geometry.md) | `Vec2`, `Rect`, `Circle` and `Polygon`: overlap tests, raycasts, pushing apart, `Slide` for moving among walls, and number helpers such as `damp` and `rotateTowards` |
| [Spatial grid](spatial-grid.md) | `SpatialGrid`: the things in an area, within a radius, or nearest a point, without testing them all |
| [Random numbers](random.md) | `Rng`: ranges, odds, picks and points in a circle, from a seed |
| [Events](events.md) | `EventBus`: posting what happened, such as an enemy killed, to whoever listens |
| [Object pools](pooling.md) | `Pool` and `Poolable`: reusing bullets, particles and other objects made and thrown away often |

Scenes and game time work together: a [`Scene`](scenes.md) runs its game logic on the [game clock](game-time.md), while its
buttons and text are ordinary elements on the real clock, so menus keep animating while the game is paused or in
slow motion.

For controls, see [Game input](../input/game-input.md): polled keys and mouse, and named actions players can rebind.

To draw the game world, see [Graphics](../graphics/README.md): a camera, sprites, texture atlases, frame animation
and batched drawing.

To animate values and script "wait, then..." moments, see [Tweens and sequences](../animation/tweens-and-sequences.md).
Played from a scene, they run on its game time.

For loading screens, see [Loading](../core/loading.md): the `Loader` runs the work in the background and reports
progress, and the loading screen itself is an ordinary scene that draws it however the game likes.

A runnable example is `me.piitex.examples.demo.GameLoopDemo`: a menu, a loading screen, a small game with bouncing
balls and a player moved with the arrow keys, a pause menu drawn over it, time scale keys, and transitions between
them all.

A full game built on all of this is `me.piitex.examples.geometrybattle.GeometryBattle`: an agar.io-style arena of
neon shapes with bots, six attacks, passives picked from three cards at each level, shader-drawn fighters and post
effects, pooled particles drawn in one batch, slow motion through the time scale, and an F2 setting for how much
screen-wide shader work to do.
