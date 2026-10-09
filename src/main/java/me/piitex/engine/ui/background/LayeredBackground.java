package me.piitex.engine.ui.background;

import me.piitex.engine.gl.renderer.Renderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Several {@link Background}s as one, drawn one over the next in the order given: the first at the back. An element or
 * a window has a single background, so setting a second one replaces the first; this keeps both:
 * <pre>
 * window.setBackground(new LayeredBackground(new Background3D(window, scene), new ConstellationBackground()));
 * </pre>
 * draws the 3D scene with the constellation over it. Every layer is updated each frame with the same bounds. A layer
 * that is left out is told it was taken away ({@link Background#onDetached}), and so are all of them when the
 * layered background is.
 */
public class LayeredBackground implements Background {
    private final List<Background> layers = new ArrayList<>();

    public LayeredBackground(Background... layers) {
        for (Background layer : layers) if (layer != null) this.layers.add(layer);
    }

    /**
     * Adds a layer in front of the others.
     */
    public LayeredBackground add(Background layer) {
        if (layer != null && !layers.contains(layer)) layers.add(layer);
        return this;
    }

    /**
     * Takes a layer out, telling it so.
     */
    public LayeredBackground remove(Background layer) {
        if (layers.remove(layer)) layer.onDetached();
        return this;
    }

    /**
     * The layers, back to front.
     */
    public List<Background> getLayers() {
        return List.copyOf(layers);
    }

    @Override
    public void render(Renderer renderer, float x, float y, float width, float height) {
        for (Background layer : List.copyOf(layers)) layer.render(renderer, x, y, width, height);
    }

    @Override
    public void update(float delta, float x, float y, float width, float height) {
        for (Background layer : List.copyOf(layers)) layer.update(delta, x, y, width, height);
    }

    @Override
    public void onDetached() {
        for (Background layer : List.copyOf(layers)) layer.onDetached();
    }
}
