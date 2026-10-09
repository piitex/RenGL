# Texture atlases

[Documentation](../README.md) › [Graphics](README.md)

A `TextureAtlas` (`me.piitex.engine.graphics`) holds named [regions](sprites.md#regions), and optionally named
[animations](sprite-animation.md), usually all cut from one packed image. Packing a game's pictures onto a few
images keeps them in few draw calls: a [`SpriteBatch`](sprites.md#drawing-many-spritebatch) draws each run from one
texture in one call.

```java nocompile
TextureAtlas atlas = TextureAtlas.load("assets/characters.json");

Sprite coin = new Sprite(atlas.get("coin"));                        // "coin.png" in the file
Animation walk = atlas.getAnimation("walk");                        // an Aseprite tag
Animation spin = Animation.of(atlas.getRegions("coin_"), 0.08f,     // coin_0, coin_1, ... coin_10
        Animation.PlayMode.LOOP);
```

## Reading a packed sheet

`TextureAtlas.load(path)` reads the JSON sprite sheet format most tools export: TexturePacker's "JSON (Hash)" and
"JSON (Array)", which Aseprite, Free Texture Packer and others also write.

| In the file | Becomes |
|---|---|
| Each entry of `frames` | A region under its name. Trimmed frames keep their offset and full size. |
| `meta.image` | The image, looked for next to the JSON file. |
| Aseprite tags in `meta.frameTags` | An animation under the tag's name, with each frame's `duration`. Forward and ping-pong tags loop, reverse and ping-pong-reverse ones play backwards, and a tag set to repeat once plays once. |

`load` takes a `String`, `File` or `Path`; a `Path` can point into a zip file system such as a mounted asset pack.
`TextureAtlas.parse(json, texture)` reads JSON you already have, with a texture you already loaded.

Loading can run on any thread, so it fits in a [`Loader`](../core/loading.md) step:

```java nocompile
loader.add("Characters", progress -> characters = TextureAtlas.load("assets/characters.json"));
```

A file the reader can't use throws an `IllegalArgumentException` that names the file and what is wrong with it.

### Export settings

- **Rotation** is fine. Packers can turn pictures 90 degrees clockwise to fit more in; the reader marks those regions
  `isRotated()` and they draw upright, with the same size and offsets as an unturned one. Build one yourself with the
  `TextureRegion` constructor that takes `rotated`, giving the upright size.
- **Trimming** is fine and saves space.
- For pixel art, call `setSmooth(false)` on the atlas's texture after loading:
  `atlas.get("any").getTexture().setSmooth(false)`.

## Finding regions

| Method | Returns |
|---|---|
| `get(name)` | The region. The file extension packers keep in names may be left out: `get("coin")` finds `"coin.png"`. Throws, naming it, if there is none. |
| `find(name)` | The same, or null. |
| `getRegions(prefix)` | Every region whose name starts with `prefix`, with numbers ordered by value, so `walk_2` comes before `walk_10`. For animation frames packed as separate pictures. |
| `getAnimation(name)`, `findAnimation(name)` | An animation, by name. |
| `getRegionNames()`, `getAnimationNames()` | Every name, in the order they were added. |

## Building one yourself

An atlas is also a plain map of names to regions. Any other format, or a sheet laid out on a grid, takes a few lines:

```java nocompile
Texture sheet = Texture.load("assets/tiles.png");
List<TextureRegion> cells = new TextureRegion(sheet).split(16, 16);
TextureAtlas tiles = new TextureAtlas()
        .add("grass", cells.get(0))
        .add("water", cells.get(1))
        .addAnimation("waves", Animation.of(cells.subList(1, 4), 0.5f, Animation.PlayMode.PING_PONG));
```

A loader for another tool's format can build an atlas the same way, with `add` and `addAnimation`.

## Things to know

- One atlas can hold regions from several textures, for packers that split a large set over several images. Each
  texture is a separate run for a `SpriteBatch`.
- Once built, an atlas can be read from any thread, as long as nothing is added to it any more.
