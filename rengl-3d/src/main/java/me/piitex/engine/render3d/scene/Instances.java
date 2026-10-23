package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;

import java.util.Arrays;

/**
 * Many copies of one node's mesh, drawn in a single call: a forest, a crowd, a field of stars. Each instance has its
 * own transform, relative to the node, and optionally its own tint. The copies share the node's mesh and material, and
 * cost one instance's worth of data each instead of a node each, so thousands are cheap.
 * <pre>
 * Instances trees = new Instances();
 * for (int i = 0; i &lt; 5000; i++) trees.add(new Vec3(random(), 0, random()), Quat.IDENTITY, Vec3.ONE);
 * node.setInstances(trees);
 * </pre>
 * Change them (even every frame) from the render thread, for example in an update handler. Instances are picked
 * individually; {@link Hit#instance()} says which.
 */
public final class Instances {
    private float[] matrices = new float[16 * 16];
    private float[] tints = new float[4 * 16];
    private int count;
    private boolean tinted;

    public int size() {
        return count;
    }

    /**
     * Adds an instance placed by the transform, and returns its index.
     */
    public int add(Vec3 position, Quat rotation, Vec3 scale) {
        return add(Mat4.compose(position, rotation, scale));
    }

    public int add(Vec3 position) {
        return add(position, Quat.IDENTITY, Vec3.ONE);
    }

    public int add(Mat4 transform) {
        ensure(count + 1);
        System.arraycopy(transform.toArray(), 0, matrices, count * 16, 16);
        Arrays.fill(tints, count * 4, count * 4 + 4, 1f);
        return count++;
    }

    /**
     * Adds an instance from a column-major 4x4 matrix of 16 floats, the layout of {@link Mat4#toArray()}. Cheaper than
     * building a {@link Mat4} when thousands of instances move every frame.
     */
    public int add(float[] columnMajor) {
        checkMatrix(columnMajor);
        ensure(count + 1);
        System.arraycopy(columnMajor, 0, matrices, count * 16, 16);
        Arrays.fill(tints, count * 4, count * 4 + 4, 1f);
        return count++;
    }

    /**
     * Replaces an instance's transform from 16 column-major floats, without allocating.
     */
    public Instances set(int index, float[] columnMajor) {
        check(index);
        checkMatrix(columnMajor);
        System.arraycopy(columnMajor, 0, matrices, index * 16, 16);
        return this;
    }

    public Instances set(int index, Mat4 transform) {
        check(index);
        System.arraycopy(transform.toArray(), 0, matrices, index * 16, 16);
        return this;
    }

    public Instances set(int index, Vec3 position, Quat rotation, Vec3 scale) {
        return set(index, Mat4.compose(position, rotation, scale));
    }

    public Mat4 get(int index) {
        check(index);
        return new Mat4(Arrays.copyOfRange(matrices, index * 16, index * 16 + 16));
    }

    /**
     * Multiplies the material's colour for one instance. White (the default) leaves it as it is.
     */
    public Instances setTint(int index, Color color) {
        check(index);
        tints[index * 4] = color.getR();
        tints[index * 4 + 1] = color.getG();
        tints[index * 4 + 2] = color.getB();
        tints[index * 4 + 3] = color.getA();
        tinted = true;
        return this;
    }

    /**
     * Sets an instance's tint without creating a {@link Color}.
     */
    public Instances setTint(int index, float r, float g, float b, float a) {
        check(index);
        tints[index * 4] = r;
        tints[index * 4 + 1] = g;
        tints[index * 4 + 2] = b;
        tints[index * 4 + 3] = a;
        tinted = true;
        return this;
    }

    public Instances clear() {
        count = 0;
        tinted = false;
        return this;
    }

    private static void checkMatrix(float[] m) {
        if (m == null || m.length < 16) throw new IllegalArgumentException("A transform is 16 floats, column-major");
    }

    private void check(int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException("Instance " + index + " of " + count);
    }

    private void ensure(int needed) {
        if (needed * 16 > matrices.length) {
            int capacity = Math.max(needed, count * 2);
            matrices = Arrays.copyOf(matrices, capacity * 16);
            tints = Arrays.copyOf(tints, capacity * 4);
        }
    }

    /**
     * The 16 floats of an instance's matrix inside {@link #matrixData()}.
     */
    float[] matrixData() {
        return matrices;
    }

    float[] tintData() {
        return tints;
    }

    boolean hasTints() {
        return tinted;
    }

    Instances copy() {
        Instances c = new Instances();
        c.matrices = matrices.clone();
        c.tints = tints.clone();
        c.count = count;
        c.tinted = tinted;
        return c;
    }
}
