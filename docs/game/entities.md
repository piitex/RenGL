# Entities and scene files

[Documentation](../README.md) › [Game](README.md)

An `EntityScene` (`me.piitex.engine.entity`) is a [scene](scenes.md) made of entities with components, which can be
saved to a JSON file and loaded from one. A game written this way describes its levels as data: the file says what
is where, and the components, Java classes in the game, say what each thing does. This is what the RenGL editor will
edit.

```java nocompile
window.getSceneManager().set(EntityScene.load("scenes/level1.json"));
```

It is an ordinary `Scene`, so transitions, pausing, timers and the scene's container for buttons and text all work as
before.

## Entities

An `Entity` is a name, a `Transform` (position, rotation in degrees clockwise, and scale) and a list of components.
Entities form a tree: a child moves, turns and scales with its parent, and its transform is relative to the parent.

```java nocompile
EntityScene scene = new EntityScene();
Entity player = scene.create("Player");
player.getTransform().setPosition(100, 200);
player.addComponent(new SpriteRenderer(TextureRef.of("assets/hero.png")));
player.addComponent(new BoxCollider(24, 32));

Entity sword = new Entity("Sword");
sword.getTransform().setPosition(16, 0); // 16 units to the player's right, turning with it
player.addChild(sword);
```

| Method | What it does |
|---|---|
| `scene.create(name)`, `scene.add(entity)` | Adds a top-level entity |
| `parent.addChild(child)`, `child.removeFromParent()` | Moves entities in the tree, keeping their local transform |
| `entity.destroy()` | Removes it and its children. Safe from inside a component's hooks |
| `scene.find(name)`, `scene.getEntity(id)`, `entity.find(name)` | Look entities up |
| `scene.forEachEntity(action)`, `scene.getComponents(type)` | Walk the whole tree |
| `entity.getWorldX()`, `getWorldRotation()`, `getWorldScaleX()` ... | Where it ends up after its parents |
| `entity.setWorldPosition(x, y)`, `worldToLocal`, `localToWorld` | Convert between world and an entity's own space |
| `entity.setActive(false)` | Stops it and its children updating and drawing, without removing them |

Every entity in a scene has an **id**, unique in that scene and kept in the file, so saved references between entities
survive saving and loading. The scene gives an entity an id when it is added if it has none, or if another entity
already has it (as when pasting a copy).

Entities are drawn in tree order: parents before children, earlier siblings before later ones, so later ones are on
top.

## Components

A component is a class extending `Component`. Fields marked `@Property` are saved with the scene and will be shown in
the editor; other fields are runtime state.

```java nocompile
public class Spin extends Component {
    @Property public float degreesPerSecond = 90f;

    @Override
    protected void update(float delta) {
        getTransform().rotate(degreesPerSecond * delta);
    }
}
```

A component class needs a public no-argument constructor, so a file can create it.

| Hook | When it runs |
|---|---|
| `onStart()` | Once, when the component is in a scene on the stack: when the scene enters, or when it is added to a running scene. Every entity of the scene exists by then. |
| `fixedUpdate(step)` | At the fixed tick rate while the scene is running. |
| `update(delta)` | Once per frame while the scene is running. |
| `render(renderer, alpha)` | Every frame the scene is visible, in world coordinates under the scene's camera. |
| `onDestroy()` | Once after `onStart`, when the component is removed, its entity destroyed, or the scene leaves the stack. |

The hooks only run while the entity and all its parents are active. `getEntity()`, `getTransform()`,
`getComponent(type)` and `getScene()` reach the rest of the scene.

### Built-in components

| Component | What it does |
|---|---|
| `SpriteRenderer` | Draws an image at its entity, turned and scaled with it. `pivotX`/`pivotY` pick the point of the image at the entity's position (the center by default), and `color` tints it. |
| `BoxCollider` | A rectangle hit area, `width` by `height`, around the entity plus `offsetX`/`offsetY`. |
| `CircleCollider` | A circle hit area of `radius`. |

`SpriteRenderer` and the colliders are `Bounded`: `getBounds()` is the world area they cover, as an upright
rectangle. The editor picks entities with the mouse by it, so a component of your own that draws something should
implement `Bounded` too; an entity without one is picked by its position alone.

Colliders only answer questions: `collider.getShape()` is the area in world coordinates as a
[shape](geometry.md), `overlaps(other)` tests two, and `getOverlapping()` finds every other collider touching this one.
What a hit means is up to the game. `scene.setShowColliders(true)` outlines them all.

### Drawing in the editor

The editor never runs a scene's components while it is being edited, so nothing moves or reacts there. A component
that draws something can still show in the editor's view: mark its class `@DrawInEditor`, and the editor calls its
`render` (only that hook) with nothing started. Such a `render` must work from the component's properties and its
entity alone, without anything `onStart` would set up.

```java nocompile
@DrawInEditor
public class Quad extends Component {
    @Property public float width = 32f, height = 32f;
    @Property public Color color = Color.WHITE;

    @Override
    protected void render(Renderer renderer, float alpha) {
        Entity e = getEntity();
        renderer.pushTransform(e.getWorldX(), e.getWorldY(), e.getWorldRotation(), e.getWorldScaleX(), e.getWorldScaleY(), 0f, 0f);
        renderer.drawQuad(-width / 2f, -height / 2f, width, height, color, 0f);
        renderer.popTransform();
    }
}
```

`SpriteRenderer` is marked. `scene.renderInEditor(renderer, camera)` is what draws a scene this way: through the
given camera, with only the marked components and the colliders if they are shown. It leaves out the background, so
the editor can draw its grid over the background and under the scene.

## Properties

`@Property` works on any class, not only components; `TypeInfo.of(type)` lists a class's properties and reads and
writes them as JSON. Saving, loading and the editor's inspector all work from it.

| Property type | Saved as |
|---|---|
| numbers, `boolean`, `char`, `String` | the JSON value |
| enums | the constant's name |
| `Color` | `[r, g, b, a]`, each 0 to 1. `"#RRGGBB"` and `"#RRGGBBAA"` are also read |
| `Vec2` | `[x, y]` |
| `TextureRef` | the image's path |

A `TextureRef` path that is not absolute is relative to the working directory, as the game runs from its project
folder. An editor points `TextureRef.setBaseDirectory(path)` at the project it has open so the same paths work there.

`@Property(name = "hp")` saves a field under another name, so the field can be renamed without breaking files, and
`min`, `max` and `tooltip` are hints for the editor. `PropertyTypes.register(type, codec)` adds other types.

## The scene file

```json
{
  "format": 1,
  "camera": { "x": 0, "y": 0, "zoom": 1 },
  "background": [0.06, 0.07, 0.1, 1],
  "entities": [
    {
      "id": 1,
      "name": "Sun",
      "transform": { "x": 0, "y": 0, "rotation": 0, "scaleX": 1, "scaleY": 1 },
      "components": [
        { "type": "me.piitex.examples.entities.Spin", "degreesPerSecond": 30 }
      ],
      "children": [ ... ]
    }
  ]
}
```

- Each component is its class's full name under `"type"`, followed by its properties. A component therefore can't
  have a property named "type".
- Anything left out takes its default: a missing transform is (0, 0), unrotated, at scale 1, and a missing property
  keeps what the component's constructor set. Keys that match no property are ignored.
- A component whose class can't be found (renamed, say) loads as a `MissingComponent` with a warning in the log. It
  does nothing, but is written back unchanged on save, so no data is lost.
- The camera's position is the world point in the middle of the window, so (0, 0) is the window's center by default.

`scene.save(path)` writes a scene. It goes to a temporary file first and is then moved into place, so a crash while
saving leaves the old file whole. `SceneFile` has the rest: loading into your own subclass of `EntityScene`,
loading component classes through a given class loader (as the editor does), and turning single entities into JSON
and back for copy and paste.

## Extending EntityScene

A game can extend `EntityScene` for scene-wide logic. Call `super` in any of `onEnter`, `onExit`, `fixedUpdate`,
`update`, `render` and `onResize` you override, or the entities stop running. Load a file into it with
`SceneFile.read(myScene, path)`.

## Example

`me.piitex.examples.entities.EntityDemo` loads `demo/entities.json`: a sun with an orbiting planet and moon, a
bobbing image and a turning crate with its collider outlined. The code places nothing; all of it is in the file.
