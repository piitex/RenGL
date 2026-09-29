package me.piitex.engine.entity;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stands in for a component whose class couldn't be found or created when a scene was loaded, for example after the
 * class was renamed. It does nothing, but keeps the component's saved data and writes it back unchanged when the scene
 * is saved, so opening and saving a scene never loses a component. An editor can show these so they get fixed.
 */
public final class MissingComponent extends Component {
    private final String type;
    private final Map<String, Object> data;
    private final String reason;

    MissingComponent(String type, Map<?, ?> data, String reason) {
        this.type = type;
        Map<String, Object> copy = new LinkedHashMap<>();
        data.forEach((k, v) -> copy.put(String.valueOf(k), v));
        this.data = Collections.unmodifiableMap(copy);
        this.reason = reason;
    }

    /**
     * The class name the scene file gave.
     */
    public String getType() {
        return type;
    }

    /**
     * The component's JSON as it was in the file, "type" included.
     */
    public Map<String, Object> getData() {
        return data;
    }

    /**
     * Why the component couldn't be made.
     */
    public String getReason() {
        return reason;
    }
}
