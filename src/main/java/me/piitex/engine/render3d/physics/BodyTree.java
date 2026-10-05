package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Ray;
import me.piitex.engine.render3d.math.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The world's bodies in a tree of boxes, so a query visits only the branches its box or ray passes through instead of
 * every body: finding what each moving body might touch, raycasts and overlap tests all take about log n steps.
 * <p>
 * Each body is a leaf whose box is its own grown by {@link #FAT} and stretched the way it is moving, so a body that
 * moves a little stays inside its leaf and the tree doesn't change. When it leaves, its leaf is taken out and put back
 * where it now fits best, by the surface area its parents would gain; the tree is kept balanced by rotations, as an AVL
 * tree is. The layout follows Box2D's dynamic tree. A body with an endless box (a plane) can't be placed in a tree of
 * boxes, so it is kept beside it and every query looks at it.
 */
final class BodyTree {
    /** How far a leaf's box reaches past its body's, in metres. */
    static final float FAT = 0.1f;
    /** How many seconds of motion a leaf's box is stretched by. */
    private static final float PREDICT = 4f / 60f;
    static final int NONE = -1, UNBOUNDED = -2;
    private static final int NULL = -1;

    private float[] box = new float[0];
    private int[] parent = new int[0], child1 = new int[0], child2 = new int[0], height = new int[0];
    private RigidBody[] leafBody = new RigidBody[0];
    private int root = NULL, free = NULL, leaves;
    private int[] stack = new int[64];
    private final List<RigidBody> unbounded = new ArrayList<>();

    /**
     * Puts the body in, or moves its leaf if its box has left the leaf's (or the leaf has grown far too big for it).
     */
    void update(RigidBody body) {
        Bounds b = body.collisionBounds();
        boolean finite = finite(b);
        if (body.proxy == UNBOUNDED) {
            if (!finite) return;
            unbounded.remove(body);
            body.proxy = NONE;
        }
        if (!finite) {
            if (body.proxy >= 0) remove(body);
            unbounded.add(body);
            body.proxy = UNBOUNDED;
            return;
        }
        float[] fresh = fatBox(body, b);
        int leaf = body.proxy;
        if (leaf >= 0) {
            int o = leaf * 6;
            boolean inside = box[o] <= b.min().x() && box[o + 1] <= b.min().y() && box[o + 2] <= b.min().z()
                    && box[o + 3] >= b.max().x() && box[o + 4] >= b.max().y() && box[o + 5] >= b.max().z();
            // A body that was moving fast and has stopped would keep a huge leaf, and turn up in queries far from it.
            float loose = 2f * FAT;
            boolean tooBig = fresh[0] - box[o] > loose || fresh[1] - box[o + 1] > loose || fresh[2] - box[o + 2] > loose
                    || box[o + 3] - fresh[3] > loose || box[o + 4] - fresh[4] > loose || box[o + 5] - fresh[5] > loose;
            if (inside && !tooBig) return;
            removeLeaf(leaf);
        } else {
            leaf = allocate();
            leafBody[leaf] = body;
            height[leaf] = 0;
            body.proxy = leaf;
            leaves++;
        }
        System.arraycopy(fresh, 0, box, leaf * 6, 6);
        insertLeaf(leaf);
    }

    void remove(RigidBody body) {
        if (body.proxy == UNBOUNDED) {
            unbounded.remove(body);
        } else if (body.proxy >= 0) {
            removeLeaf(body.proxy);
            release(body.proxy);
            leaves--;
        }
        body.proxy = NONE;
    }

    /**
     * Calls {@code visit} for each body whose leaf overlaps the box, in no particular order; the leaves are a little
     * bigger than the bodies, so the caller tests the bodies' own boxes.
     */
    void query(Bounds bounds, Consumer<RigidBody> visit) {
        query(bounds.min().x(), bounds.min().y(), bounds.min().z(), bounds.max().x(), bounds.max().y(), bounds.max().z(), visit);
    }

    void query(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, Consumer<RigidBody> visit) {
        for (int i = 0; i < unbounded.size(); i++) visit.accept(unbounded.get(i));
        if (root == NULL) return;
        int top = 0;
        stack[top++] = root;
        while (top > 0) {
            int node = stack[--top];
            int o = node * 6;
            if (box[o] > maxX || box[o + 3] < minX || box[o + 1] > maxY || box[o + 4] < minY || box[o + 2] > maxZ || box[o + 5] < minZ) continue;
            if (child1[node] == NULL) {
                visit.accept(leafBody[node]);
            } else {
                if (top + 2 > stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
                stack[top++] = child1[node];
                stack[top++] = child2[node];
            }
        }
    }

    /**
     * Calls {@code visit} for each body whose leaf the ray enters within {@code maxDistance}, in no particular order.
     */
    void raycast(Ray ray, float maxDistance, Consumer<RigidBody> visit) {
        for (int i = 0; i < unbounded.size(); i++) visit.accept(unbounded.get(i));
        if (root == NULL) return;
        Vec3 o = ray.origin(), d = ray.direction();
        float ix = 1f / d.x(), iy = 1f / d.y(), iz = 1f / d.z();
        int top = 0;
        stack[top++] = root;
        while (top > 0) {
            int node = stack[--top];
            if (!hits(node, o, ix, iy, iz, maxDistance)) continue;
            if (child1[node] == NULL) {
                visit.accept(leafBody[node]);
            } else {
                if (top + 2 > stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
                stack[top++] = child1[node];
                stack[top++] = child2[node];
            }
        }
    }

    /**
     * Whether the ray enters the node's box within {@code max}: the slab test, with the 1/0 infinities of a ray along
     * an axis doing the right thing.
     */
    private boolean hits(int node, Vec3 o, float ix, float iy, float iz, float max) {
        int b = node * 6;
        float near = 0f, far = max;
        float t1 = (box[b] - o.x()) * ix, t2 = (box[b + 3] - o.x()) * ix;
        if (Float.isNaN(t1) || Float.isNaN(t2)) {
            if (o.x() < box[b] || o.x() > box[b + 3]) return false;
        } else {
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
        }
        t1 = (box[b + 1] - o.y()) * iy;
        t2 = (box[b + 4] - o.y()) * iy;
        if (Float.isNaN(t1) || Float.isNaN(t2)) {
            if (o.y() < box[b + 1] || o.y() > box[b + 4]) return false;
        } else {
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
        }
        t1 = (box[b + 2] - o.z()) * iz;
        t2 = (box[b + 5] - o.z()) * iz;
        if (Float.isNaN(t1) || Float.isNaN(t2)) {
            if (o.z() < box[b + 2] || o.z() > box[b + 5]) return false;
        } else {
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
        }
        return near <= far;
    }

    int size() {
        return leaves + unbounded.size();
    }

    /** The longest path from the root to a leaf: about log2 of the number of bodies when the tree is balanced. */
    int height() {
        return root == NULL ? 0 : height[root];
    }

    /**
     * Checks that every parent holds its children's boxes and has the right height, and that the heights of each
     * node's children differ by at most one; for tests.
     */
    void validate() {
        if (root == NULL) return;
        if (parent[root] != NULL) throw new IllegalStateException("The root has a parent");
        int[] count = {0};
        validate(root, count);
        if (count[0] != leaves) throw new IllegalStateException(count[0] + " leaves found, " + leaves + " kept");
    }

    private void validate(int node, int[] count) {
        if (child1[node] == NULL) {
            if (height[node] != 0) throw new IllegalStateException("A leaf of height " + height[node]);
            if (leafBody[node].proxy != node) throw new IllegalStateException("A leaf whose body points elsewhere");
            count[0]++;
            return;
        }
        int a = child1[node], b = child2[node];
        if (parent[a] != node || parent[b] != node) throw new IllegalStateException("A child that doesn't know its parent");
        if (height[node] != 1 + Math.max(height[a], height[b])) throw new IllegalStateException("A wrong height");
        if (Math.abs(height[a] - height[b]) > 1) throw new IllegalStateException("An unbalanced node");
        for (int k = 0; k < 3; k++) {
            if (box[node * 6 + k] > Math.min(box[a * 6 + k], box[b * 6 + k]) || box[node * 6 + 3 + k] < Math.max(box[a * 6 + 3 + k], box[b * 6 + 3 + k])) {
                throw new IllegalStateException("A box that doesn't hold its children");
            }
        }
        validate(a, count);
        validate(b, count);
    }

    // ---- Building ---------------------------------------------------------------------------------------------------

    private static boolean finite(Bounds b) {
        return Float.isFinite(b.min().x()) && Float.isFinite(b.min().y()) && Float.isFinite(b.min().z())
                && Float.isFinite(b.max().x()) && Float.isFinite(b.max().y()) && Float.isFinite(b.max().z());
    }

    private static float[] fatBox(RigidBody body, Bounds b) {
        float[] fat = {b.min().x() - FAT, b.min().y() - FAT, b.min().z() - FAT, b.max().x() + FAT, b.max().y() + FAT, b.max().z() + FAT};
        float[] move = {body.vx * PREDICT, body.vy * PREDICT, body.vz * PREDICT};
        for (int k = 0; k < 3; k++) {
            if (move[k] < 0f) fat[k] += move[k];
            else fat[k + 3] += move[k];
        }
        return fat;
    }

    private int allocate() {
        if (free == NULL) {
            int old = parent.length, size = Math.max(16, old * 2);
            box = Arrays.copyOf(box, size * 6);
            parent = Arrays.copyOf(parent, size);
            child1 = Arrays.copyOf(child1, size);
            child2 = Arrays.copyOf(child2, size);
            height = Arrays.copyOf(height, size);
            leafBody = Arrays.copyOf(leafBody, size);
            for (int i = old; i < size; i++) {
                parent[i] = i + 1 < size ? i + 1 : NULL; // the free list runs through parent
                height[i] = -1;
            }
            free = old;
        }
        int node = free;
        free = parent[node];
        parent[node] = child1[node] = child2[node] = NULL;
        height[node] = 0;
        return node;
    }

    private void release(int node) {
        parent[node] = free;
        height[node] = -1;
        leafBody[node] = null;
        free = node;
    }

    private float area(int node) {
        int o = node * 6;
        float x = box[o + 3] - box[o], y = box[o + 4] - box[o + 1], z = box[o + 5] - box[o + 2];
        return x * y + y * z + z * x;
    }

    /** The surface area (halved) of the box holding nodes {@code a} and {@code b}. */
    private float unionArea(int a, int b) {
        int i = a * 6, j = b * 6;
        float x = Math.max(box[i + 3], box[j + 3]) - Math.min(box[i], box[j]);
        float y = Math.max(box[i + 4], box[j + 4]) - Math.min(box[i + 1], box[j + 1]);
        float z = Math.max(box[i + 5], box[j + 5]) - Math.min(box[i + 2], box[j + 2]);
        return x * y + y * z + z * x;
    }

    private void join(int node, int a, int b) {
        int o = node * 6, i = a * 6, j = b * 6;
        for (int k = 0; k < 3; k++) {
            box[o + k] = Math.min(box[i + k], box[j + k]);
            box[o + 3 + k] = Math.max(box[i + 3 + k], box[j + 3 + k]);
        }
    }

    private void insertLeaf(int leaf) {
        if (root == NULL) {
            root = leaf;
            parent[leaf] = NULL;
            return;
        }
        // Walk down to the sibling that makes the tree's boxes grow least.
        int index = root;
        while (child1[index] != NULL) {
            int c1 = child1[index], c2 = child2[index];
            float area = area(index);
            float combined = unionArea(index, leaf);
            float cost = 2f * combined; // a new parent of this node and the leaf
            float inheritance = 2f * (combined - area); // what every node further down would add to this one
            float cost1 = unionArea(leaf, c1) - (child1[c1] == NULL ? 0f : area(c1)) + inheritance;
            float cost2 = unionArea(leaf, c2) - (child1[c2] == NULL ? 0f : area(c2)) + inheritance;
            if (cost < cost1 && cost < cost2) break;
            index = cost1 < cost2 ? c1 : c2;
        }
        int sibling = index;
        int oldParent = parent[sibling];
        int newParent = allocate();
        parent[newParent] = oldParent;
        join(newParent, leaf, sibling);
        height[newParent] = height[sibling] + 1;
        if (oldParent != NULL) {
            if (child1[oldParent] == sibling) child1[oldParent] = newParent;
            else child2[oldParent] = newParent;
        } else {
            root = newParent;
        }
        child1[newParent] = sibling;
        child2[newParent] = leaf;
        parent[sibling] = newParent;
        parent[leaf] = newParent;
        refit(parent[leaf]);
    }

    private void removeLeaf(int leaf) {
        if (leaf == root) {
            root = NULL;
            return;
        }
        int p = parent[leaf], grandParent = parent[p];
        int sibling = child1[p] == leaf ? child2[p] : child1[p];
        if (grandParent != NULL) {
            if (child1[grandParent] == p) child1[grandParent] = sibling;
            else child2[grandParent] = sibling;
            parent[sibling] = grandParent;
            release(p);
            refit(grandParent);
        } else {
            root = sibling;
            parent[sibling] = NULL;
            release(p);
        }
        parent[leaf] = NULL;
    }

    /** Balances and refits each node from {@code index} up to the root. */
    private void refit(int index) {
        while (index != NULL) {
            index = balance(index);
            int c1 = child1[index], c2 = child2[index];
            height[index] = 1 + Math.max(height[c1], height[c2]);
            join(index, c1, c2);
            index = parent[index];
        }
    }

    /**
     * If one child of {@code a} is more than one level taller than the other, turns the taller up into {@code a}'s
     * place and returns it; otherwise returns {@code a}.
     */
    private int balance(int a) {
        if (child1[a] == NULL || height[a] < 2) return a;
        int b = child1[a], c = child2[a];
        int lean = height[c] - height[b];
        if (lean > 1) {
            int f = child1[c], g = child2[c];
            child1[c] = a;
            parent[c] = parent[a];
            parent[a] = c;
            replaceChild(parent[c], a, c);
            if (height[f] > height[g]) {
                child2[c] = f;
                child2[a] = g;
                parent[g] = a;
                join(a, b, g);
                join(c, a, f);
                height[a] = 1 + Math.max(height[b], height[g]);
                height[c] = 1 + Math.max(height[a], height[f]);
            } else {
                child2[c] = g;
                child2[a] = f;
                parent[f] = a;
                join(a, b, f);
                join(c, a, g);
                height[a] = 1 + Math.max(height[b], height[f]);
                height[c] = 1 + Math.max(height[a], height[g]);
            }
            return c;
        }
        if (lean < -1) {
            int d = child1[b], e = child2[b];
            child1[b] = a;
            parent[b] = parent[a];
            parent[a] = b;
            replaceChild(parent[b], a, b);
            if (height[d] > height[e]) {
                child2[b] = d;
                child1[a] = e;
                parent[e] = a;
                join(a, c, e);
                join(b, a, d);
                height[a] = 1 + Math.max(height[c], height[e]);
                height[b] = 1 + Math.max(height[a], height[d]);
            } else {
                child2[b] = e;
                child1[a] = d;
                parent[d] = a;
                join(a, c, d);
                join(b, a, e);
                height[a] = 1 + Math.max(height[c], height[d]);
                height[b] = 1 + Math.max(height[a], height[e]);
            }
            return b;
        }
        return a;
    }

    private void replaceChild(int node, int old, int now) {
        if (node == NULL) {
            root = now;
        } else if (child1[node] == old) {
            child1[node] = now;
        } else {
            child2[node] = now;
        }
    }
}
