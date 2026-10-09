# Graphics

[Documentation](../README.md) › Graphics

The pieces for drawing a 2D game world, in `me.piitex.engine.graphics`. What the things on screen are and how they
move is up to the game; the camera has helpers for following and shaking, and particles move themselves.

| Page | What it covers |
|---|---|
| [Camera](camera.md) | `Camera`: which part of the world is shown, zoomed and turned, in which part of the window; following, shaking and staying inside the level; converting mouse positions to world points |
| [Sprites](sprites.md) | `Texture`, `TextureRegion` and `Sprite`: pictures placed, turned, scaled, mirrored and tinted; `SpriteBatch` to draw many in layer order, skipping those out of view, in few draw calls |
| [Texture atlases](atlases.md) | `TextureAtlas`: named pictures from one packed image, read from the JSON that texture packers and Aseprite export, or built from a grid |
| [Tile maps](tile-maps.md) | `TileMap`: levels made in the Tiled map editor, drawn in view only, with animated tiles and parallax; collision shapes for `Slide`; spawn points, triggers and other objects with their properties |
| [Sprite animation](sprite-animation.md) | `Animation` and `AnimationPlayer`: frame-by-frame animation, loop modes, frame events |
| [Particles](particles.md) | `ParticleSystem`, `ParticleEffect` and `ParticleEmitter`: sparks, smoke and debris, in bursts or steadily, reused and drawn in one batch |
| [Shaders and post effects](shaders.md) | `ShaderEffect`: SkSL shaders that fill shapes, or post-process what's drawn, on the whole screen or one element |
| [Debug drawing](debug-drawing.md) | `DebugDraw`: lines, shapes and labels in world space for seeing hit boxes, rays and state, from fixed steps or frames |

They fit together like this:

```java nocompile
TextureAtlas atlas = TextureAtlas.load("assets/characters.json");   // pictures and animations
Sprite hero = new Sprite();
AnimationPlayer anim = new AnimationPlayer(atlas.getAnimation("walk"));

// Scene.update
anim.update(delta);
hero.setRegion(anim.getFrame());

// Scene.render
camera.begin(renderer);                        // world coordinates from here
batch.add(hero);                               // plus everything else in the world
batch.draw(renderer, camera.getVisibleBounds());
camera.end(renderer);
```

Each piece can be used without the others. A sprite can be drawn without a batch, a camera works with plain
`Renderer` calls, and a game with its own animation system only needs `Sprite.setRegion`.

A runnable example is `me.piitex.examples.demo.SpriteDemo`: a tile world with a few hundred hopping slimes and
spinning coins from a packed sprite sheet, a camera that follows the player and can zoom and turn, and a minimap
drawn by a second camera. It also has a shader-drawn sea, post effects on F2 and a debug view on F1.
