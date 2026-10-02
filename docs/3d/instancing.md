# Instancing

[Documentation](../README.md) › [3D](README.md)

`Instances` draws one node's mesh many times, each copy with its own transform and, if you like, its own tint: a forest, a
crowd, a field of stars, the bricks of a wall. All the copies are drawn in a single call, so thousands cost little more
than one.

```java nocompile
Node3D trees = new Node3D("trees", treeMesh, treeMaterial);
Instances positions = new Instances();
Random random = new Random(1);
for (int i = 0; i < 5000; i++) {
    int tree = positions.add(
            new Vec3(random.nextFloat() * 200 - 100, 0, random.nextFloat() * 200 - 100),
            Quat.fromAxisAngle(Vec3.UP, random.nextFloat() * 6.28f),
            new Vec3(1, 0.8f + random.nextFloat() * 0.5f, 1));
    positions.setTint(tree, new Color(0.8f + random.nextFloat() * 0.2f, 1f, 0.8f + random.nextFloat() * 0.2f, 1f));
}
trees.setInstances(positions);
scene.add(trees);
```

| Method | Description |
|---|---|
| `new Instances()` | An empty set |
| `add(Vec3 position, Quat rotation, Vec3 scale)` / `add(Vec3)` / `add(Mat4)` | Adds an instance and returns its index |
| `set(index, Mat4)` / `set(index, position, rotation, scale)` | Replaces an instance's transform |
| `add(float[16])` / `set(index, float[16])` | The same from 16 column-major floats (the layout of `Mat4.toArray()`), which allocates nothing: for thousands of instances that move every frame |
| `get(index)` | An instance's transform |
| `setTint(index, Color)` / `setTint(index, r, g, b, a)` | Multiplies the material's colour for this instance. White, the default, leaves it as it is. The alpha fades a blended or additive instance |
| `size()` / `clear()` | How many there are; remove them all |
| `Node3D.setInstances(Instances)` | Draws the node's mesh once per instance. `null` goes back to a single copy |

An instance's transform is **relative to the node**: the node's own position, rotation and scale move all the copies
together, like a parent.

## Changing them

Change instances from the render thread, for example in the [update handler](scenes.md#the-update-handler). The whole set
is written to the GPU every frame, so moving every instance every frame is fine for thousands of them:

```java nocompile
scene.setUpdateHandler((s, delta) -> {
    time += delta;
    for (int i = 0; i < positions.size(); i++) {
        positions.set(i, new Vec3(base[i].x(), (float) Math.sin(time + i) * 0.3f, base[i].z()), Quat.IDENTITY, Vec3.ONE);
    }
});
```

## What they share and what they don't

- All copies use the node's **mesh and material**. A different material needs a different node. Tints are the one thing
  that varies per copy.
- Copies cast and receive [shadows](lighting.md#shadows) like any node, each in its own place.
- The node's **children are not repeated**; they follow the node once. To repeat a hierarchy, instance each of its meshes
  on its own node.
- [Picking](scenes.md#picking) tests every instance and reports the nearest, with `Hit.instance()` saying which.
- There is no culling of instances that are off screen: they are all drawn. A very large set is as cheap as the GPU can
  fill it; split a huge world into several nodes if most of it is out of view.
- A node with no instances (`size() == 0`) draws nothing.
- Without `Instances`, nodes that share a mesh and a material are still drawn together in one call automatically, so a few
  hundred ordinary nodes are not a problem either.

For your own shaders the same machinery is available: see [per-instance data](custom-rendering.md#instancing).
