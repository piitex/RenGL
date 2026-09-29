# Sprites

[Documentation](../README.md) › [Graphics](README.md)

Three classes in `me.piitex.engine.graphics` describe pictures to draw, and a fourth draws many of them at once.

| Class | What it is |
|---|---|
| `Texture` | An image that sprites are cut from, and whether it is drawn smooth or with hard pixel edges. |
| `TextureRegion` | One picture on a texture: a rectangle of its pixels, such as one frame of a sprite sheet. |
| `Sprite` | A region placed in the world: position, pivot, rotation, scale, mirroring, color and layer. |
| `SpriteBatch` | Draws many sprites in layer order, leaves out those out of view, and batches the draw calls. |

```java nocompile
Texture sheet = Texture.load("assets/hero.png");
sheet.setSmooth(false);                              // pixel art
List<TextureRegion> frames = new TextureRegion(sheet).split(32, 32);

Sprite hero = new Sprite(frames.get(0));
hero.setPivot(0.5f, 1f);                             // stand on (x, y)
hero.setPosition(200, 300);

// in Scene.render:
hero.draw(renderer);
```

## Textures

| Method | What it does |
|---|---|
| `Texture.load(File)`, `load(String)`, `load(Path)` | Loads a PNG, JPEG, WEBP, GIF or BMP. Files go through [`ImageLoader`](../styling/images-and-icons.md), so loading the same file twice shares one image. A `Path` can point into a zip file system, such as a mounted asset pack. |
| `Texture.decode(byte[])` | Decodes an image already in memory. |
| `new Texture(Image)` | Wraps a Skia image, such as one drawn with an `OffscreenRenderer`. |
| `setSmooth(boolean)` | True by default: drawn scaled, neighbouring pixels blend. False keeps hard pixel edges, as pixel art wants. It applies to every region of the texture. |

Loading can run on any thread, for example in a [`Loader`](../core/loading.md) step.

## Regions

`new TextureRegion(texture)` is the whole texture, and `new TextureRegion(texture, x, y, width, height)` a rectangle
of it, in pixels. `split(cellWidth, cellHeight)` cuts a region into a grid, row by row from the top left, for a sprite
sheet laid out on a grid. `split(cellWidth, cellHeight, margin, spacing)` handles sheets with a border and gaps.

A region can be **trimmed**, as texture packers do to save space: the transparent border is cut off, and the region
keeps where the remaining pixels sat in the full picture and how big it was (`getOffsetX/Y`, `getFrameWidth/Height`).
Sprites draw a trimmed region exactly where the full picture would have been, so the frames of an animation stay
lined up. [Atlases](atlases.md) read trimming from the packer's JSON.

## Sprites

| Property | Meaning |
|---|---|
| `setRegion(region)` | The picture. Null draws nothing. |
| `setPosition(x, y)` | Where the pivot goes. |
| `setPivot(px, py)` | The point of the picture placed at the position, and that it turns and scales around, as a fraction of its size: (0, 0), the default, is the top-left corner, (0.5, 0.5) the center, (0.5, 1) the middle of the bottom edge. |
| `setRotation(degrees)` | Clockwise, around the pivot. |
| `setScale(s)`, `setScale(sx, sy)` | Around the pivot. At 1 each pixel of the region covers one unit. |
| `setFlipX`, `setFlipY` | Mirrors the picture inside its own box, so it covers the same area as before. |
| `setColor(color)` | Multiplied with the picture: white, the default, leaves it unchanged, and a gray picture takes the color's tint. |
| `setOpacity(0..1)` | Fades it, together with the color's alpha. |
| `setLayer(int)` | Which layer a `SpriteBatch` draws it on. |
| `setVisible(boolean)` | Hidden sprites aren't drawn. |

`getBounds()` is the upright rectangle around the sprite as drawn, turned and scaled, for simple hit tests.

A sprite's coordinates are whatever it is drawn in: window points, or world units under a [camera](camera.md).

Sprites don't move by themselves and aren't tied to any kind of game object. Keep one on each of your own objects,
or share one and move it before each draw: see [snapshots](#one-sprite-many-places) below.

Sprite color multiplies. The `tint` of `Renderer.drawImage` and `ImageOverlay` is different: it recolors an image
while keeping its shape, as for icons.

## Drawing many: SpriteBatch

```java nocompile
private final SpriteBatch batch = new SpriteBatch();

@Override
protected void render(Renderer renderer, float alpha) {
    for (Enemy enemy : enemies) batch.add(enemy.sprite);
    batch.add(player.sprite);

    camera.begin(renderer);
    batch.draw(renderer, camera.getVisibleBounds());
    camera.end(renderer);
}
```

`add` records sprites; `draw` draws them and empties the batch for the next frame. `clear()` empties it without
drawing.

### Order

Sprites are drawn by layer, lowest first, so higher layers cover lower ones. Within a layer they are drawn in the
order they were added, unless a sort key is set with `setSortKey`: then lowest key first, and in the order added where
keys are equal.

| Sort key | Use |
|---|---|
| none (null) | The default: the order you add them in. |
| `SpriteBatch.BY_Y` | Lower on screen is drawn in front: characters in a top-down game. Put their pivots at their feet. |
| any `sprite -> value` | Your own, for example `s -> s.getX() + s.getY()` for an isometric view. |

The key is read when a sprite is added, so it can be changed between adds, for example `BY_Y` for characters and
none for the ground.

### Leaving out what's out of view

`draw(renderer, visible)` skips sprites whose bounds don't overlap `visible`. Pass the camera's
`getVisibleBounds()`. `draw(renderer)` draws everything.

A tile map knows where its tiles are, so it should only add the tiles in view in the first place, as
[`TileMap`](tile-maps.md) and `SpriteDemo` do. Culling is for things that move.

### Batching

Each run of sprites that are next in the draw order and share a texture is drawn with one call. Pictures packed
on one [atlas](atlases.md) share a texture, so a whole scene can be one call. `SpriteDemo` draws its tiles, slimes,
shadows and coins, over 20,000 sprites at its most, in one call.

| After `draw` | Value |
|---|---|
| `getDrawnCount()` | Sprites drawn |
| `getCulledCount()` | Sprites left out for being out of view |
| `getDrawCallCount()` | Calls made: one per run of one texture |

### One sprite, many places

`add` records the sprite as it is at that moment. Changing it afterwards doesn't change what the batch draws, so one
sprite can be moved and added again, as many times as needed:

```java nocompile
for (int y = firstRow; y <= lastRow; y++) {
    for (int x = firstColumn; x <= lastColumn; x++) {
        tile.setRegion(tiles[map[y][x]]);
        tile.setPosition(x * TILE, y * TILE);
        batch.add(tile);
    }
}
```

## Smooth and pixelated

| | `setSmooth(true)` (default) | `setSmooth(false)` |
|---|---|---|
| Scaled up | Soft | Square pixels |
| Scaled down | Blended | Pixels picked |
| Draw calls | Skia draws each sprite, but merges consecutive ones from one texture on the GPU | One call per run |

Either way, a sprite never shows pixels from beside its region on the sheet, and sprites side by side leave no
seams, however they are scaled or turned. For pixel art, also use a whole-number camera zoom and
[`setPixelSnap(true)`](camera.md#the-view) to keep every pixel the same size.

## Things to know

- Change and draw sprites on the render thread.
- `Sprite.draw` and `SpriteBatch.draw` go through `Renderer.drawImageQuads`. The built-in renderers implement it.
  A custom `Renderer` gets a default version that works as long as it implements `pushTransform`; without that,
  turned and mirrored sprites come out wrong.
