# Graphics

[Documentation](../README.md) › Graphics

The pieces for drawing a 2D game world, in `me.piitex.engine.graphics`. They only draw: what the things on screen
are, how they move and how the camera follows them is up to the game.

| Page | What it covers |
|---|---|
| [Camera](camera.md) | `Camera`: which part of the world is shown, zoomed and turned, in which part of the window; converting mouse positions to world points |
| [Sprites](sprites.md) | `Texture`, `TextureRegion` and `Sprite`: pictures placed, turned, scaled, mirrored and tinted; `SpriteBatch` to draw many in layer order, skipping those out of view, in few draw calls |
| [Texture atlases](atlases.md) | `TextureAtlas`: named pictures from one packed image, read from the JSON that texture packers and Aseprite export, or built from a grid |
| [Sprite animation](sprite-animation.md) | `Animation` and `AnimationPlayer`: frame-by-frame animation, loop modes, frame events |

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
drawn by a second camera.
