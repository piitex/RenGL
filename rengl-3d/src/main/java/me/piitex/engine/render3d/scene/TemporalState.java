package me.piitex.engine.render3d.scene;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * What a temporal upscaler needs to know about the frame before: where the camera was and where each node was, so every
 * pixel can tell how far it moved. One belongs to each place a scene is drawn (see {@link PostPass}), since each has its
 * own previous frame.
 */
final class TemporalState {
    /** Frames drawn since the history was last dropped. */
    long frame;
    /** The camera's view-projection of the last frame, without the jitter, or null when there was none. */
    float[] previousViewProj;
    private Map<Node3D, float[]> previous = new IdentityHashMap<>();
    private Map<Node3D, float[]> next = new IdentityHashMap<>();

    /**
     * Forgets the last frame: the next one shows no motion.
     */
    void reset() {
        frame = 0;
        previousViewProj = null;
        previous.clear();
        next.clear();
    }

    /**
     * Where the node's world matrix (column-major, 16 floats) was in the last frame, or null if it was not drawn then.
     */
    float[] previousWorld(Node3D node) {
        return previous.get(node);
    }

    /**
     * Notes where the node is in this frame, for the next one.
     */
    void remember(Node3D node, float[] world) {
        next.put(node, world);
    }

    /**
     * This frame is done: what was noted becomes the last frame.
     */
    void endFrame(float[] viewProj) {
        Map<Node3D, float[]> done = previous;
        previous = next;
        next = done;
        next.clear();
        previousViewProj = viewProj;
        frame++;
    }
}
