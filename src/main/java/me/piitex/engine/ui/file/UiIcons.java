package me.piitex.engine.ui.file;

import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The built-in icons a layout file can name: the {@link Feather} set, by the name of its file, such as
 * {@code "arrow-right"}.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class UiIcons {
    /** The icon a new icon element shows. */
    public static final String DEFAULT = "star";

    private static final Map<String, IconAsset> byName = new TreeMap<>();

    static {
        for (Field field : Feather.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != IconAsset.class) continue;
            try {
                IconAsset asset = (IconAsset) field.get(null);
                String file = asset.resourcePath().substring(asset.resourcePath().lastIndexOf('/') + 1);
                byName.put(file.substring(0, file.length() - ".svg".length()), asset);
            } catch (IllegalAccessException e) {
                // A field that can't be read is an icon that isn't offered.
            }
        }
    }

    private UiIcons() {
    }

    /** Every icon name, sorted. */
    public static List<String> names() {
        return Collections.unmodifiableList(new ArrayList<>(byName.keySet()));
    }

    /** The icon called {@code name}, or null if there isn't one. */
    public static IconAsset get(String name) {
        return name == null ? null : byName.get(name);
    }
}
