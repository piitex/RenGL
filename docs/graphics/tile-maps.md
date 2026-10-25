# Tile maps

[Documentation](../README.md) › [Graphics](README.md)

> Needs the `rengl-tilemap` [module](../modules.md).

A `TileMap` (`me.piitex.engine.tilemap`) is a level made of tiles, usually drawn in the free
[Tiled](https://www.mapeditor.org) map editor: layers of tiles, layers of placed objects such as spawn points and
doors, background pictures, and the custom properties set on all of them. The engine loads Tiled's files, draws the
map, and turns its collision into [shapes](../game/geometry.md) that [`Slide`](../game/geometry.md#pushing-apart-and-sliding)
and [`SpatialGrid`](../game/spatial-grid.md) work with.

```java nocompile
TileMap map = TileMap.load("assets/levels/forest.tmx");
map.setSmooth(false);                                   // pixel art: hard pixel edges when zoomed
List<Shape> walls = map.getCollisionShapes();           // drawn in Tiled's tile collision editor
MapObject start = map.findObject("player_start");
player.setPosition(start.getX(), start.getY());
camera.setBounds(map.getBounds());

// Scene.update
map.update(delta);                                      // animated tiles
Slide slide = Slide.move(player.hitBox(), dx, dy, walls);

// Scene.render
camera.begin(renderer);
map.draw(renderer, camera.getVisibleBounds());          // only what is in view
camera.end(renderer);
```

## Loading

`TileMap.load(path)` reads either of Tiled's formats, TMX (`.tmx`, XML) or JSON (`.tmj`, `.json`), and everything the
map uses: tile sets inside it or in their own files (`.tsx`, `.tsj`), images and templates (`.tx`, `.tj`). Each is
looked for relative to the file that names it, as Tiled saves them. `load` takes a `String`, `File` or `Path`; a
`Path` can point into a zip file system such as a mounted asset pack.

Loading can run on any thread, so it fits in a [`Loader`](../core/loading.md) step. A file the reader can't use
throws an `IllegalArgumentException` that names the file and what is wrong with it; a file that is missing throws an
`UncheckedIOException` that names it.

| Read | |
|---|---|
| Maps | [Orthogonal](#isometric-staggered-and-hexagonal-maps) (fixed size or infinite), isometric, staggered and hexagonal; every tile layer format (CSV, base64 uncompressed, zlib, gzip or zstd, and XML) |
| Layers | Tile layers, object layers, image layers and groups, with visibility, opacity, tint, blend mode, offset and parallax |
| Tiles | Mirrored and turned tiles, tiles bigger than the grid, drawing offsets, animations, collision shapes, "Tile Render Size" and "Fill Mode" |
| Tile sets | One image cut into a grid (with margin and spacing), or a collection of images, as files or embedded in a `.tmx`; a transparent color that is cut out |
| Objects | Rectangles, ellipses, capsules, points, polygons, polylines, text and tile objects, turned, and templates |
| Properties | Every type: string, int, float, bool, color, file, object and custom classes |

Not supported:

- **Infinite isometric, staggered and hexagonal maps**, and the rotation of hexagonal tiles: see
  [the other orientations](#isometric-staggered-and-hexagonal-maps).
- **Images embedded in a JSON map.** Tiled can't write them there; only a `.tmx` holds them. They must be base64, plain or compressed.
- **Fonts for text objects.** Every text object is drawn in the engine's [default font](../styling/fonts.md), whatever family
  Tiled shows. The family name is there to read (`getTextFormat().fontFamily()`).

### Custom class defaults

Tiled doesn't save a class member left at its default, so by itself a map only holds what was changed. Give the loader the
project file and it fills in the rest:

```java nocompile
TiledProject project = TiledProject.load("assets/game.tiled-project");
TileMap map = TileMap.load("assets/levels/forest.tmx", project);
float speed = enemy.getProperties().getFloat("speed", 80f);   // the class default when the map doesn't set it
```

It applies to anything with a class (a map, layer, tile set, tile or object), and to a property whose type is a class, down
through classes made of classes. A class that overrides the default of a member that is itself a class (an `Enemy` whose
`stats` start at attack 5) wins over that class's own defaults. Without the project, those members read as not set and
the getter's default applies, as before. `project.getClassNames()` lists what it defines.

## Drawing

`map.draw(renderer, view)` draws the map as Tiled shows it, inside a [camera](camera.md). Pass the camera's
`getVisibleBounds()`: only the tiles in it are drawn, one draw call per layer for tiles from one image, so the size of
the map doesn't matter. If the map has a background color, the view is filled with it first.

- **Animated tiles** follow the map's clock. Call `map.update(delta)` every frame; `setTime` sets it.
- **Pixel art** wants `map.setSmooth(false)`, which sets it on every image the map uses.
- **Tiles bigger than the grid**, like a tree two cells tall, sit on the bottom-left corner of their cell and reach
  up and right, as in Tiled.

### Sprites between layers

`map.draw` draws every layer. To draw characters above the ground but under the tree tops, draw the layers one at a
time and the sprites in between:

```java nocompile
Rect view = camera.getVisibleBounds();
for (MapLayer layer : map.getLayers()) {
    layer.draw(renderer, view);
    if (layer.getName().equals("ground")) batch.draw(renderer, view);
}
```

### Changing layers while the game runs

Every layer can be hidden, faded, tinted, moved and given parallax from code, the same settings as in Tiled's layer
properties. A group's settings apply on top of those of the layers in it.

| Method | |
|---|---|
| `setVisible(boolean)` | Hides or shows it. Hidden layers still count for collision |
| `setOpacity(float)` | 0 invisible, 1 solid: fade a roof out when the player walks inside |
| `setTint(color)` | Multiplied with everything the layer draws; null for none |
| `setBlendMode(BlendMode)` | How it combines with what is below: `NORMAL`, `ADD`, `MULTIPLY`, `SCREEN`, `OVERLAY`, `DARKEN`, `LIGHTEN`, `COLOR_DODGE`, `COLOR_BURN`, `HARD_LIGHT`, `SOFT_LIGHT`, `DIFFERENCE`, `EXCLUSION`. Tiled's layer "Mode" loads here |
| `setOffset(x, y)` | Moves it, in pixels |
| `setParallax(x, y)` | How fast it scrolls: 1 with the map, 0.5 half as fast, 0 not at all |

**Parallax** is measured from the middle of the view you draw with: when it is at the map's parallax origin, (0, 0)
unless set in the map's properties, every layer sits where Tiled shows it. It only moves what is drawn, not tile
or object positions.

`draw(renderer)`, without a view, draws the whole map, without a background or parallax: into an image for a
minimap, say, drawn once with an [`OffscreenRenderer`](../core/snapshots.md).

## Layers

| `TileMap` method | Returns |
|---|---|
| `getLayers()` | The layers at the top, bottom first; groups hold more |
| `getAllLayers()` | Every layer, groups and their contents included, in drawing order |
| `getLayer(name)`, `findLayer(name)` | The first layer of that name, searching groups too. `get` throws if there is none, `find` returns null |
| `getTileLayer(name)`, `getObjectLayer(name)` | The same, as the kind you expect. Throws if the layer is another kind |

`TileLayer`, `ObjectLayer`, `ImageLayer` and `GroupLayer` all extend `MapLayer`, which has the settings above and
`getName()`, `getId()`, `getType()` (the layer's class) and `getProperties()`.

### Tile layers

A grid with a `Tile` or nothing in each cell. Cells count in tiles from the map's top-left corner.

| Method | |
|---|---|
| `getTile(x, y)` | The tile in the cell, or null if it is empty or outside the layer |
| `getTileAt(worldX, worldY)` | The tile under a world point, allowing for the layer's offset: the tile under the player's feet |
| `getFlags(x, y)` | How it is mirrored or turned: `FLIP_X`, `FLIP_Y` and `FLIP_DIAGONAL` or'ed together |
| `setTile(x, y, tile)`, `setTile(x, y, tile, flags)` | Changes the cell; null empties it. Flags such as `ROTATE_90`, `ROTATE_180`, `ROTATE_270` |
| `getWidth()`, `getHeight()`, `getStartX()`, `getStartY()` | The cells it covers. The start is 0 except in an infinite map, whose layers can reach into negative cells |

A `Tile` is the kind of tile, shared by every cell it is placed in: `getType()` (its class), `getProperties()`,
`getRegion()` (its picture), `getAnimation()`, and `getCollisionShapes()`. To test what the player stands on:

```java nocompile
Tile under = map.getTileLayer("ground").getTileAt(player.x, player.bottom() + 1);
if (under != null && under.getType().equals("Ice")) friction = 0.1f;
```

`TileMap.toTileX(worldX)` and `toTileY` give the cell a world point is in on an orthogonal map, and `getCellBounds(x, y)`
the rectangle a cell covers. For the other orientations, and in general for picking, use `toCell` and `getCellShape`: see
[below](#isometric-staggered-and-hexagonal-maps).

## Isometric, staggered and hexagonal maps

`map.getOrientation()` says how the grid is laid out, as Tiled's *Orientation* does:

| `Orientation` | The grid |
|---|---|
| `ORTHOGONAL` | Rows and columns of rectangles. What the rest of this page assumes unless it says otherwise |
| `ISOMETRIC` | A diamond of cells. Cell (0, 0) is at the top, **x** runs down and to the right and **y** down and to the left |
| `STAGGERED` | Isometric diamonds in the rows (or columns) of a rectangular map, every other one shifted half a cell |
| `HEXAGONAL` | Hexagons in rows (`StaggerAxis.Y`, pointed tops) or columns (`StaggerAxis.X`, flat tops), every other one shifted |

`getStaggerAxis()`, `isStaggerOdd()` (whether the odd rows or columns are the shifted ones, rather than the even) and
`getHexSideLength()` are Tiled's *Stagger Axis*, *Stagger Index* and *Tile Side Length*. They load from `.tmx` and `.tmj` like
everything else, and a map can be built in code with `TileMap.isometric(...)`, `TileMap.staggered(...)` and
`TileMap.hexagonal(...)`.

**Cells are not at `x * tileWidth` any more**, so ask the map where they are and which one a point is in. These work on every
orientation, and what is named *box* is the `tileWidth` by `tileHeight` rectangle the diamond or hexagon sits in:

```java nocompile
TileMap.Cell cell = map.toCell(mouseWorldX, mouseWorldY);       // the cell under the mouse, also outside the map
Tile tile = map.getTileLayer("ground").getTile(cell.x(), cell.y());

Shape outline = map.getCellShape(cell.x(), cell.y());           // a Polygon (a diamond or a hexagon), or a Rect on an orthogonal map
float[] middle = map.getCellCenter(cell.x(), cell.y());         // where to put a unit standing on the cell
Rect box = map.getCellBounds(cell.x(), cell.y());
```

| | Orthogonal | The others |
|---|---|---|
| `getBounds()` | width and height times the tile size | The area Tiled shows: for an isometric map `(width + height) * tileWidth / 2` across and `(width + height) * tileHeight / 2` down; the shifted rows or columns add half a cell |
| `toCell`, `getTileAt` | Plain division | Looks at the few cells around the point and takes the one whose outline holds it, so a point near a corner is in the right cell |
| `toTileX`, `toTileY` | The column and the row | Throw `UnsupportedOperationException`: with diamonds and hexagons each depends on both coordinates, so use `toCell` |
| `layer.getSolidRects(...)` | Joined rectangles | Throws: use `layer.getSolidShapes(...)`, which gives joined rectangles on an orthogonal map and one diamond or hexagon for each solid cell on the others |
| `layer.getCollisionShapes()` | Cells solid all over are joined | Each tile's own shapes are placed on its picture; nothing is joined |

**Drawing** follows Tiled: tiles sit on the bottom of their cell's box, on the left of it, except on an isometric map where a tile
narrower or wider than the cell is centred across it. Tiles are drawn bottom first (rows, or for staggered columns, whole columns, in
the map's render order), so a tall tile overlaps the ones behind it as it should, and only the cells in view are drawn.

**Objects on an isometric map** are saved by Tiled on a square grid whose unit is `tileHeight` and drawn as diamonds. They are read
where they are drawn, in the same units as everything else: `getX()` and `getY()` of a point or a tile are screen pixels, a polygon or
polyline has its points converted, a **rectangle** (or capsule) comes out as a `POLYGON` of four corners (the parallelogram it is),
and an **ellipse** as a 16-sided `POLYGON`. Their rotation is dropped. A tile object's *Object Alignment* is *Bottom* by default there, as
in Tiled, so it stands on its point. Objects on the other three orientations are in plain pixels and are read as they are.

Not read: the 60 and 120 degree rotation flags of hexagonal tiles (a rotated hexagonal tile is drawn unrotated; mirrored ones are
mirrored), and **infinite** maps that are not orthogonal, which fail to load saying so. Collision shapes drawn on a tile in an isometric
*tile set* (whose grid Tiled shows as diamonds) are taken as measured on the tile's picture, as for any tile.

## Collision

Three ways to mark what is solid in Tiled, each a line of code:

| Marked in Tiled with | Shapes |
|---|---|
| Shapes drawn on tiles in the tile set's collision editor | `map.getCollisionShapes()`: from every tile layer, placed wherever those tiles are, mirrored and turned with them. `layer.getCollisionShapes()` for one layer |
| A layer of solid tiles, or a class or property on the tiles | `layer.getSolidRects(tile -> true)`, or `tile -> tile.getType().equals("Wall")`: rectangles over the cells |
| Rectangles, ellipses and polygons on an object layer | `map.getObjectLayer("collision").getShapes()` |

Cells that are solid all over are joined into large rectangles, so a floor of a hundred tiles is one shape: fewer
shapes to test, and nothing for a sliding character to catch on. Concave polygons are cut into convex ones.

The shapes are a snapshot: after changing tiles, ask again. For a big level, put them in a
[`SpatialGrid`](../game/spatial-grid.md) and pass `Slide` only the ones near the move:

```java nocompile
SpatialGrid<Shape> walls = new SpatialGrid<>(64f);
for (Shape shape : map.getCollisionShapes()) walls.add(shape, shape);

Rect box = player.hitBox();
Rect sweep = box.union(box.translate(dx, dy)).expand(1f);
Slide slide = Slide.move(box, dx, dy, walls.query(sweep, nearbyWalls));
```

## Objects

Everything placed on an object layer is a `MapObject`: where the player starts, where enemies spawn, doors, pickups,
paths. Find them by name or by class and read their properties:

```java nocompile
for (MapObject object : map.getObjectLayer("entities").getObjects()) {
    switch (object.getType()) {
        case "Coin" -> coins.add(new Coin(object.getX(), object.getY()));
        case "Enemy" -> enemies.add(new Enemy(object.getBounds(), object.getProperties().getFloat("speed", 60f)));
        case "Door" -> doors.add(new Door(object.getBounds(), object.getProperties().getObject("leadsTo")));
    }
}
```

| Method | |
|---|---|
| `map.findObject(name)`, `layer.find(name)` | The first object of that name, or null |
| `map.getObjectsOfType(type)`, `layer.getObjectsOfType(type)` | Every object of that class |
| `map.getObject(id)` | By the number Tiled gave it, which object properties point to |
| `getKind()` | `RECTANGLE`, `ELLIPSE`, `CAPSULE`, `POINT`, `POLYGON`, `POLYLINE`, `TEXT` or `TILE` |
| `getX()`, `getY()` | Where Tiled puts it, plus its layer's offset. The top-left corner, **but for a tile object its bottom-left corner**, as Tiled saves it |
| `getWidth()`, `getHeight()`, `getRotation()` | As set in Tiled. It turns clockwise around (`getX()`, `getY()`) |
| `getBounds()` | The box it really covers, turned, whatever its kind |
| `getShapes()` | Its shapes for collision; empty for points and polylines |
| `getPoints()` | A polygon's or polyline's points in the world: a path to patrol |
| `getTile()`, `isFlipX()`, `isFlipY()` | What a tile object shows |
| `getText()`, `getTextFormat()` | A text object's text, and its font family, size, color, style and alignment |
| `setVisible(boolean)` | Hides a tile object, such as a coin that was picked up |
| `makeSprite()` | A [`Sprite`](sprites.md) that looks like a tile object: hide the object and move the sprite, for a crate that can be pushed |

Objects made from a **template** have the template's settings with the object's own on top, including their
properties. Drawing an object layer draws its visible tile objects and text objects; other kinds are only for the game.

A **text object** is drawn inside its box, turned with the object and clipped to the box, in the size, color,
weight and alignment Tiled shows: wrapped to the width when "Word wrap" is on, and placed at the top, middle or bottom of
the box. A text object whose box has no size draws without clipping from its corner.

## Properties

Maps, layers, tile sets, tiles and objects all have `getProperties()`: the custom properties set on them in Tiled.
Each getter takes the value to return when the property isn't set.

| Method | Reads |
|---|---|
| `getString(name)`, `getString(name, default)` | Any property as text; null if not set |
| `getInt(name, default)`, `getFloat(name, default)` | Numbers |
| `getBoolean(name, default)` | `bool` properties |
| `getColor(name)` | `color` properties, as a `Color` |
| `getFile(name)` | `file` properties, as a `Path` resolved against the file the property was set in |
| `getObject(name)` | The `MapObject` an `object` property points to |
| `getMembers(name)` | The members of a custom class property, as more `Properties` |
| `has(name)`, `getNames()`, `get(name)` | What is set, and the value as loaded |

A value that can't be read as asked, such as text read with `getInt`, throws, naming the property.

## Building a map in code

A generated level, or a map from another editor, can be built with the same classes and drawn and tested the same
way:

```java nocompile
TileSet terrain = TileSet.fromGrid("terrain", Texture.load("assets/terrain.png"), 16, 16);
TileMap map = new TileMap(100, 60, 16, 16);
map.addTileSet(terrain);
TileLayer ground = new TileLayer("ground", 100, 60);
map.addLayer(ground);
for (int x = 0; x < 100; x++) ground.setTile(x, 59, terrain.getTile(3));
List<Rect> floor = ground.getSolidRects(tile -> true);
```

Groups are `new GroupLayer(name)` with layers `add`ed to them, and pictures `new ImageLayer(name, texture)`.

## Things to know

- **Units are the map's pixels.** A tile covers `getTileWidth()` by `getTileHeight()` world units. Zoom the camera
  to show them bigger, and keep the game's positions in the same units.
- **Not thread-safe.** Load anywhere; after that, use the map on the render thread.
- **Pictures are shared.** Two maps using the same tile set image share it, so `setSmooth` on one changes both.
- **Tiled keeps working.** Maps are read as they are saved, so edit in Tiled, save, and load again; nothing needs
  exporting.
