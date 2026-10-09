package me.piitex.engine.tilemap;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.math.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A folder of layers. Hiding, fading, tinting, offsetting or setting the parallax of the group does the same to
 * every layer in it, on top of their own settings.
 */
public final class GroupLayer extends MapLayer {
    private final List<MapLayer> layers = new ArrayList<>();

    public GroupLayer(String name) {
        super(name);
    }

    /**
     * The layers in the group, bottom first, as they are drawn.
     */
    public List<MapLayer> getLayers() {
        return Collections.unmodifiableList(layers);
    }

    /**
     * Adds {@code layer} on top of the others in the group.
     *
     * @throws IllegalArgumentException if the layer is already in a map or group
     */
    public void add(MapLayer layer) {
        if (layer.parent != null || layer.map != null) throw new IllegalArgumentException(layer + " is already in a map or group");
        for (GroupLayer group = this; group != null; group = group.parent) {
            if (group == layer) throw new IllegalArgumentException("A group can't be put inside itself");
        }
        layers.add(layer);
        layer.parent = this;
        layer.setMap(map);
        if (map != null) map.layersChanged();
    }

    /**
     * Takes {@code layer} out of the group.
     */
    public boolean remove(MapLayer layer) {
        if (!layers.remove(layer)) return false;
        layer.parent = null;
        layer.setMap(null);
        if (map != null) map.layersChanged();
        return true;
    }

    @Override
    void setMap(TileMap map) {
        super.setMap(map);
        for (MapLayer layer : layers) layer.setMap(map);
    }

    @Override
    void render(Renderer renderer, Rect view, DrawState parent) {
        DrawState state = parent.with(this);
        if (state == null) return;
        for (MapLayer layer : layers) layer.renderBlended(renderer, view, state);
    }
}
