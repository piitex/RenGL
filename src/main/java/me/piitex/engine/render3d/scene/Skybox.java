package me.piitex.engine.render3d.scene;

import me.piitex.engine.ui.color.Color;

/**
 * What is seen where nothing else is drawn: the sky behind a {@link Scene3D}. It turns with the camera but never moves
 * with it, so it looks infinitely far away. Three kinds:
 * <ul>
 * <li>{@link #gradient}: three colours blended from the top of the sky to the ground. No images.</li>
 * <li>{@link #cube}: six pictures, the faces of a cube around the camera, in the layout most skybox image sets use.</li>
 * <li>{@link #equirect}: one wide panorama, twice as wide as it is high.</li>
 * </ul>
 * <pre>
 * scene.setSkybox(Skybox.gradient(Color.CORNFLOWER_BLUE, Color.WHITE, new Color(0.25f, 0.22f, 0.2f, 1f)));
 * </pre>
 * The sky is not a light: it does not tint the objects in the scene. Set the scene's ambient light and lights to match.
 * One skybox can be set on any number of scenes.
 */
public final class Skybox {
    enum Kind {GRADIENT, CUBE, EQUIRECT}

    final Kind kind;
    final Color top, horizon, bottom;
    final TextureData[] faces;
    final TextureData panorama;
    private float brightness = 1f;

    private Skybox(Kind kind, Color top, Color horizon, Color bottom, TextureData[] faces, TextureData panorama) {
        this.kind = kind;
        this.top = top;
        this.horizon = horizon;
        this.bottom = bottom;
        this.faces = faces;
        this.panorama = panorama;
    }

    /**
     * A sky that blends from {@code top} straight up, through {@code horizon} at eye level, to {@code bottom} straight
     * down.
     */
    public static Skybox gradient(Color top, Color horizon, Color bottom) {
        return new Skybox(Kind.GRADIENT, top, horizon, bottom, null, null);
    }

    /**
     * Six square pictures of the same size, in the order +X (right), -X (left), +Y (up), -Y (down), +Z (towards a camera
     * that looks along -Z, so behind it) and -Z (ahead of it). This is the order of the usual {@code right, left, top,
     * bottom, front, back} file sets, laid out the way OpenGL and Vulkan cube maps are.
     */
    public static Skybox cube(TextureData positiveX, TextureData negativeX, TextureData positiveY,
                              TextureData negativeY, TextureData positiveZ, TextureData negativeZ) {
        TextureData[] faces = {positiveX, negativeX, positiveY, negativeY, positiveZ, negativeZ};
        int size = faces[0].width();
        for (TextureData face : faces) {
            if (face.width() != size || face.height() != size) {
                throw new IllegalArgumentException("The six faces of a cube skybox must be square and the same size");
            }
        }
        return new Skybox(Kind.CUBE, null, null, null, faces, null);
    }

    /**
     * One panorama covering the whole sky: its middle row is the horizon, the top row straight up. Twice as wide as
     * high is the usual shape.
     */
    public static Skybox equirect(TextureData panorama) {
        return new Skybox(Kind.EQUIRECT, null, null, null, null, panorama);
    }

    public float getBrightness() {
        return brightness;
    }

    /**
     * Multiplies the sky's brightness: 1 as drawn, below 1 for dusk, above 1 for glare.
     */
    public Skybox setBrightness(float brightness) {
        this.brightness = Math.max(0f, brightness);
        return this;
    }
}
