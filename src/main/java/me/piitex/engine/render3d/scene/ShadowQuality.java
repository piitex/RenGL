package me.piitex.engine.render3d.scene;

/**
 * A ready-made choice of shadow sharpness and cost for a light: pass it to {@link Light#setShadows(ShadowQuality)}, or
 * start from it with {@link Shadows#of(ShadowQuality)} and change what you need.
 * <p>
 * The size is the width and height in pixels of one shadow picture: a directional light uses one per cascade, a spot light
 * one, and a point light six (one per side), so a point light costs six times its tile.
 */
public enum ShadowQuality {
    /** 512 pixels: blocky up close, for small or distant lights. */
    LOW(512, ShadowFilter.PCF),
    /** 1024 pixels: fine for most lights, and the choice for point lights. */
    MEDIUM(1024, ShadowFilter.PCF),
    /** 2048 pixels: what a scene's sun gets without any setting. */
    HIGH(2048, ShadowFilter.PCF),
    /** 4096 pixels with the widest soft edge; about 64 MB for one tile. */
    ULTRA(4096, ShadowFilter.SOFT);

    private final int size;
    private final ShadowFilter filter;

    ShadowQuality(int size, ShadowFilter filter) {
        this.size = size;
        this.filter = filter;
    }

    /**
     * The width and height of one shadow picture, in pixels.
     */
    public int size() {
        return size;
    }

    public ShadowFilter filter() {
        return filter;
    }
}
