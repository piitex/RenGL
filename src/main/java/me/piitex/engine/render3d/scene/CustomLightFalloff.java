package me.piitex.engine.render3d.scene;

import java.util.Objects;

record CustomLightFalloff(String name, String glsl) implements LightFalloff {
    CustomLightFalloff {
        Objects.requireNonNull(glsl, "glsl");
        if (!name.matches("[A-Za-z0-9]+")) throw new IllegalArgumentException("A falloff name is letters and digits: " + name);
    }
}
