# Game

[Documentation](../README.md) › Game

The pieces a game builds on, on top of the UI toolkit.

| Page | What it covers |
|---|---|
| [Scenes](scenes.md) | `Scene` and `SceneManager`: main menu, gameplay, pause menu, and moving between them |
| [Game time](game-time.md) | `GameTime`: the fixed-step game loop, time scale, pausing, and smooth drawing between steps |

The two work together: a [`Scene`](scenes.md) runs its game logic on the [game clock](game-time.md), while its
buttons and text are ordinary elements on the real clock, so menus keep animating while the game is paused or in
slow motion.

A runnable example is `me.piitex.examples.demo.GameLoopDemo`: a menu, a small game with bouncing balls and a player
moved with the arrow keys, a pause menu drawn over it, and time scale keys.
