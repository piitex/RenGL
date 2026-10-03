package me.piitex.engine.render3d.scene;

import java.util.Objects;

record CustomShadowFilter(String name, String glsl) implements ShadowFilter {
    CustomShadowFilter {
        Objects.requireNonNull(glsl, "glsl");
        if (!name.matches("[A-Za-z0-9]+")) throw new IllegalArgumentException("A filter name is letters and digits: " + name);
    }
}
