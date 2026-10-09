package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.GpuDevice;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Meshes and textures being uploaded to the GPU ahead of the first frame that draws them, started by
 * {@link Scene3D#preload(Node3D)}. The scene uploads a few milliseconds' worth each frame
 * ({@link Scene3D#setPreloadBudget}), so the picture keeps moving while a big model loads.
 */
public final class Preload {
    private final List<Object> items;
    private final Map<GpuDevice, Integer> done = new IdentityHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean finished;

    Preload(List<Object> items) {
        this.items = items;
        finished = items.isEmpty();
    }

    /**
     * How many meshes and textures this uploads.
     */
    public int getTotal() {
        return items.size();
    }

    /**
     * How far along it is, 0 to 1. With the scene shown on several devices, the slowest one.
     */
    public float getProgress() {
        if (finished) return 1f;
        int least = done.isEmpty() ? 0 : Integer.MAX_VALUE;
        for (int n : done.values()) least = Math.min(least, n);
        return (float) least / items.size();
    }

    /**
     * True once everything is on the GPU, so showing the model no longer stalls. Also true if there was nothing to upload.
     */
    public boolean isDone() {
        return finished;
    }

    /**
     * Runs {@code action} on the render thread when the upload is done, or right away if it already is.
     */
    public Preload onDone(Runnable action) {
        if (finished) {
            action.run();
        } else {
            listeners.add(action);
        }
        return this;
    }

    int next(GpuDevice device) {
        return done.getOrDefault(device, 0);
    }

    Object item(int index) {
        return items.get(index);
    }

    void advance(GpuDevice device, int index) {
        done.put(device, index);
    }

    /**
     * Marks it finished when every device in {@code devices} has everything; true if it just did.
     */
    boolean complete(Iterable<GpuDevice> devices) {
        if (finished) return false;
        for (GpuDevice device : devices) {
            if (done.getOrDefault(device, 0) < items.size()) return false;
        }
        finished = true;
        List<Runnable> run = new ArrayList<>(listeners);
        listeners.clear();
        run.forEach(Runnable::run);
        return true;
    }
}
