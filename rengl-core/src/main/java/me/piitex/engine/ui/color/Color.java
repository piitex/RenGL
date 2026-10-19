package me.piitex.engine.ui.color;

/**
 * A solid RGBA color with float components in [0, 1]. A plain Color is immutable. The
 * {@link RainbowColor} subclass is the exception: its components change over time, and
 * it works anywhere a Color is accepted. {@link #fromHSV} builds one from hue,
 * saturation, and brightness, and the constants below are the standard CSS/X11 named
 * colors.
 * <p>
 * Code reading a Color should call {@link #getR()}, {@link #getG()}, {@link #getB()},
 * {@link #getA()}, or {@link #toARGB()} at draw time rather than copying the values once,
 * so a {@link RainbowColor} keeps animating.
 */
public class Color implements Paint {
    private final float r, g, b, a;

    public Color(float color) {
        this.r = color;
        this.g = color;
        this.b = color;
        this.a = color;
    }

    public Color(float r, float g, float b, float a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    public float getR() {
        return r;
    }

    public float getG() {
        return g;
    }

    public float getB() {
        return b;
    }

    public float getA() {
        return a;
    }

    public int toARGB() {
        return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    /**
     * This color with its alpha set to {@code alpha}, clamped to [0, 1]. A new plain Color, so on a
     * {@link RainbowColor} it holds the hue of the moment it was called.
     */
    public Color withAlpha(float alpha) {
        return new Color(getR(), getG(), getB(), Math.clamp(alpha, 0f, 1f));
    }

    /**
     * This color with its alpha multiplied by {@code opacity}, clamped to [0, 1]: for fading something in or out
     * while keeping how see-through it already was. {@code withOpacity(0.5f)} on a color of alpha 0.8 gives 0.4.
     * To fade everything drawn for a while, {@link me.piitex.engine.gl.renderer.Renderer#pushAlpha} saves passing
     * the opacity to every color.
     */
    public Color withOpacity(float opacity) {
        return new Color(getR(), getG(), getB(), getA() * Math.clamp(opacity, 0f, 1f));
    }

    @Override
    public String toString() {
        return "(" + r + "," + g + "," + b + "," + a + ")";
    }

    /**
     * A Color from hue, saturation, and brightness. {@code hue} is in turns and wraps, so
     * 0, 1, and 2 are all red and any float is valid. Saturation and brightness are [0, 1].
     * See {@link RainbowColor} for a Color whose hue cycles by itself.
     */
    public static Color fromHSV(float hue, float saturation, float brightness, float alpha) {
        float[] rgb = new float[3];
        hsvToRgb(hue, saturation, brightness, rgb);
        return new Color(rgb[0], rgb[1], rgb[2], alpha);
    }

    /**
     * HSV to RGB into {@code out}, without allocating. {@code hue} is in turns and wraps.
     */
    static void hsvToRgb(float hue, float s, float v, float[] out) {
        float h = (hue - (float) Math.floor(hue)) * 6f;
        int sector = (int) h;
        float f = h - sector;
        float p = v * (1f - s);
        float q = v * (1f - s * f);
        float t = v * (1f - s * (1f - f));
        switch (sector) {
            case 0 -> { out[0] = v; out[1] = t; out[2] = p; }
            case 1 -> { out[0] = q; out[1] = v; out[2] = p; }
            case 2 -> { out[0] = p; out[1] = v; out[2] = t; }
            case 3 -> { out[0] = p; out[1] = q; out[2] = v; }
            case 4 -> { out[0] = t; out[1] = p; out[2] = v; }
            default -> { out[0] = v; out[1] = p; out[2] = q; }
        }
    }

    // Named colors below follow the standard CSS/X11 color keyword palette (the same
    // set JavaFX's own Color class ships), so the values are the well-known ones rather
    // than anything picked off a color wheel, so Color.CORNFLOWER_BLUE and
    // Color.DARK_SLATE_GRAY look the way those names do anywhere else. RED, BLUE,
    // BLACK, WHITE, and TRANSPARENT predate this palette and already matched the
    // standard values. GREEN, LIGHT_GRAY, and DARK_GRAY predate it too and do not match
    // the CSS values for those names: this GREEN is pure and bright, while CSS's GREEN
    // is the darker, muted tone LIME uses here. They keep their current values, since
    // existing code may depend on them.
    public static final Color ALICE_BLUE = new Color(0xF0 / 255f, 0xF8 / 255f, 0xFF / 255f, 1.0f);
    public static final Color ANTIQUE_WHITE = new Color(0xFA / 255f, 0xEB / 255f, 0xD7 / 255f, 1.0f);
    public static final Color AQUA = new Color(0x00 / 255f, 0xFF / 255f, 0xFF / 255f, 1.0f);
    public static final Color AQUAMARINE = new Color(0x7F / 255f, 0xFF / 255f, 0xD4 / 255f, 1.0f);
    public static final Color AZURE = new Color(0xF0 / 255f, 0xFF / 255f, 0xFF / 255f, 1.0f);
    public static final Color BEIGE = new Color(0xF5 / 255f, 0xF5 / 255f, 0xDC / 255f, 1.0f);
    public static final Color BISQUE = new Color(0xFF / 255f, 0xE4 / 255f, 0xC4 / 255f, 1.0f);
    public static final Color BLACK = new Color(0.0f, 0.0f, 0.0f, 1.0f);
    public static final Color BLANCHED_ALMOND = new Color(0xFF / 255f, 0xEB / 255f, 0xCD / 255f, 1.0f);
    public static final Color BLUE = new Color(0.0f, 0.0f, 1.0f, 1.0f);
    public static final Color BLUE_VIOLET = new Color(0x8A / 255f, 0x2B / 255f, 0xE2 / 255f, 1.0f);
    public static final Color BROWN = new Color(0xA5 / 255f, 0x2A / 255f, 0x2A / 255f, 1.0f);
    public static final Color BURLYWOOD = new Color(0xDE / 255f, 0xB8 / 255f, 0x87 / 255f, 1.0f);
    public static final Color CADET_BLUE = new Color(0x5F / 255f, 0x9E / 255f, 0xA0 / 255f, 1.0f);
    public static final Color CHARTREUSE = new Color(0x7F / 255f, 0xFF / 255f, 0x00 / 255f, 1.0f);
    public static final Color CHOCOLATE = new Color(0xD2 / 255f, 0x69 / 255f, 0x1E / 255f, 1.0f);
    public static final Color CORAL = new Color(0xFF / 255f, 0x7F / 255f, 0x50 / 255f, 1.0f);
    public static final Color CORNFLOWER_BLUE = new Color(0x64 / 255f, 0x95 / 255f, 0xED / 255f, 1.0f);
    public static final Color CORNSILK = new Color(0xFF / 255f, 0xF8 / 255f, 0xDC / 255f, 1.0f);
    public static final Color CRIMSON = new Color(0xDC / 255f, 0x14 / 255f, 0x3C / 255f, 1.0f);
    public static final Color CYAN = new Color(0x00 / 255f, 0xFF / 255f, 0xFF / 255f, 1.0f);
    public static final Color DARK_BLUE = new Color(0x00 / 255f, 0x00 / 255f, 0x8B / 255f, 1.0f);
    public static final Color DARK_CYAN = new Color(0x00 / 255f, 0x8B / 255f, 0x8B / 255f, 1.0f);
    public static final Color DARK_GOLDENROD = new Color(0xB8 / 255f, 0x86 / 255f, 0x0B / 255f, 1.0f);
    public static final Color DARK_GRAY = new Color(0.3f, 0.3f, 0.3f, 1.0f);
    public static final Color DARK_GREEN = new Color(0x00 / 255f, 0x64 / 255f, 0x00 / 255f, 1.0f);
    public static final Color DARK_KHAKI = new Color(0xBD / 255f, 0xB7 / 255f, 0x6B / 255f, 1.0f);
    public static final Color DARK_MAGENTA = new Color(0x8B / 255f, 0x00 / 255f, 0x8B / 255f, 1.0f);
    public static final Color DARK_OLIVE_GREEN = new Color(0x55 / 255f, 0x6B / 255f, 0x2F / 255f, 1.0f);
    public static final Color DARK_ORANGE = new Color(0xFF / 255f, 0x8C / 255f, 0x00 / 255f, 1.0f);
    public static final Color DARK_ORCHID = new Color(0x99 / 255f, 0x32 / 255f, 0xCC / 255f, 1.0f);
    public static final Color DARK_RED = new Color(0x8B / 255f, 0x00 / 255f, 0x00 / 255f, 1.0f);
    public static final Color DARK_SALMON = new Color(0xE9 / 255f, 0x96 / 255f, 0x7A / 255f, 1.0f);
    public static final Color DARK_SEA_GREEN = new Color(0x8F / 255f, 0xBC / 255f, 0x8F / 255f, 1.0f);
    public static final Color DARK_SLATE_BLUE = new Color(0x48 / 255f, 0x3D / 255f, 0x8B / 255f, 1.0f);
    public static final Color DARK_SLATE_GRAY = new Color(0x2F / 255f, 0x4F / 255f, 0x4F / 255f, 1.0f);
    public static final Color DARK_TURQUOISE = new Color(0x00 / 255f, 0xCE / 255f, 0xD1 / 255f, 1.0f);
    public static final Color DARK_VIOLET = new Color(0x94 / 255f, 0x00 / 255f, 0xD3 / 255f, 1.0f);
    public static final Color DEEP_PINK = new Color(0xFF / 255f, 0x14 / 255f, 0x93 / 255f, 1.0f);
    public static final Color DEEP_SKY_BLUE = new Color(0x00 / 255f, 0xBF / 255f, 0xFF / 255f, 1.0f);
    public static final Color DIM_GRAY = new Color(0x69 / 255f, 0x69 / 255f, 0x69 / 255f, 1.0f);
    public static final Color DODGER_BLUE = new Color(0x1E / 255f, 0x90 / 255f, 0xFF / 255f, 1.0f);
    public static final Color FIREBRICK = new Color(0xB2 / 255f, 0x22 / 255f, 0x22 / 255f, 1.0f);
    public static final Color FLORAL_WHITE = new Color(0xFF / 255f, 0xFA / 255f, 0xF0 / 255f, 1.0f);
    public static final Color FOREST_GREEN = new Color(0x22 / 255f, 0x8B / 255f, 0x22 / 255f, 1.0f);
    public static final Color FUCHSIA = new Color(0xFF / 255f, 0x00 / 255f, 0xFF / 255f, 1.0f);
    public static final Color GAINSBORO = new Color(0xDC / 255f, 0xDC / 255f, 0xDC / 255f, 1.0f);
    public static final Color GHOST_WHITE = new Color(0xF8 / 255f, 0xF8 / 255f, 0xFF / 255f, 1.0f);
    public static final Color GOLD = new Color(0xFF / 255f, 0xD7 / 255f, 0x00 / 255f, 1.0f);
    public static final Color GOLDENROD = new Color(0xDA / 255f, 0xA5 / 255f, 0x20 / 255f, 1.0f);
    public static final Color GRAY = new Color(0x80 / 255f, 0x80 / 255f, 0x80 / 255f, 1.0f);
    public static final Color GREEN = new Color(0.0f, 1.0f, 0.0f, 1.0f);
    public static final Color GREEN_YELLOW = new Color(0xAD / 255f, 0xFF / 255f, 0x2F / 255f, 1.0f);
    public static final Color HONEYDEW = new Color(0xF0 / 255f, 0xFF / 255f, 0xF0 / 255f, 1.0f);
    public static final Color HOT_PINK = new Color(0xFF / 255f, 0x69 / 255f, 0xB4 / 255f, 1.0f);
    public static final Color INDIAN_RED = new Color(0xCD / 255f, 0x5C / 255f, 0x5C / 255f, 1.0f);
    public static final Color INDIGO = new Color(0x4B / 255f, 0x00 / 255f, 0x82 / 255f, 1.0f);
    public static final Color IVORY = new Color(0xFF / 255f, 0xFF / 255f, 0xF0 / 255f, 1.0f);
    public static final Color KHAKI = new Color(0xF0 / 255f, 0xE6 / 255f, 0x8C / 255f, 1.0f);
    public static final Color LAVENDER = new Color(0xE6 / 255f, 0xE6 / 255f, 0xFA / 255f, 1.0f);
    public static final Color LAVENDER_BLUSH = new Color(0xFF / 255f, 0xF0 / 255f, 0xF5 / 255f, 1.0f);
    public static final Color LAWN_GREEN = new Color(0x7C / 255f, 0xFC / 255f, 0x00 / 255f, 1.0f);
    public static final Color LEMON_CHIFFON = new Color(0xFF / 255f, 0xFA / 255f, 0xCD / 255f, 1.0f);
    public static final Color LIGHT_BLUE = new Color(0xAD / 255f, 0xD8 / 255f, 0xE6 / 255f, 1.0f);
    public static final Color LIGHT_CORAL = new Color(0xF0 / 255f, 0x80 / 255f, 0x80 / 255f, 1.0f);
    public static final Color LIGHT_CYAN = new Color(0xE0 / 255f, 0xFF / 255f, 0xFF / 255f, 1.0f);
    public static final Color LIGHT_GOLDENROD_YELLOW = new Color(0xFA / 255f, 0xFA / 255f, 0xD2 / 255f, 1.0f);
    public static final Color LIGHT_GRAY = new Color(0.7f, 0.7f, 0.7f, 1.0f);
    public static final Color LIGHT_GREEN = new Color(0x90 / 255f, 0xEE / 255f, 0x90 / 255f, 1.0f);
    public static final Color LIGHT_PINK = new Color(0xFF / 255f, 0xB6 / 255f, 0xC1 / 255f, 1.0f);
    public static final Color LIGHT_SALMON = new Color(0xFF / 255f, 0xA0 / 255f, 0x7A / 255f, 1.0f);
    public static final Color LIGHT_SEA_GREEN = new Color(0x20 / 255f, 0xB2 / 255f, 0xAA / 255f, 1.0f);
    public static final Color LIGHT_SKY_BLUE = new Color(0x87 / 255f, 0xCE / 255f, 0xFA / 255f, 1.0f);
    public static final Color LIGHT_SLATE_GRAY = new Color(0x77 / 255f, 0x88 / 255f, 0x99 / 255f, 1.0f);
    public static final Color LIGHT_STEEL_BLUE = new Color(0xB0 / 255f, 0xC4 / 255f, 0xDE / 255f, 1.0f);
    public static final Color LIGHT_YELLOW = new Color(0xFF / 255f, 0xFF / 255f, 0xE0 / 255f, 1.0f);
    public static final Color LIME = new Color(0x00 / 255f, 0xFF / 255f, 0x00 / 255f, 1.0f);
    public static final Color LIME_GREEN = new Color(0x32 / 255f, 0xCD / 255f, 0x32 / 255f, 1.0f);
    public static final Color LINEN = new Color(0xFA / 255f, 0xF0 / 255f, 0xE6 / 255f, 1.0f);
    public static final Color MAGENTA = new Color(0xFF / 255f, 0x00 / 255f, 0xFF / 255f, 1.0f);
    public static final Color MAROON = new Color(0x80 / 255f, 0x00 / 255f, 0x00 / 255f, 1.0f);
    public static final Color MEDIUM_AQUAMARINE = new Color(0x66 / 255f, 0xCD / 255f, 0xAA / 255f, 1.0f);
    public static final Color MEDIUM_BLUE = new Color(0x00 / 255f, 0x00 / 255f, 0xCD / 255f, 1.0f);
    public static final Color MEDIUM_ORCHID = new Color(0xBA / 255f, 0x55 / 255f, 0xD3 / 255f, 1.0f);
    public static final Color MEDIUM_PURPLE = new Color(0x93 / 255f, 0x70 / 255f, 0xDB / 255f, 1.0f);
    public static final Color MEDIUM_SEA_GREEN = new Color(0x3C / 255f, 0xB3 / 255f, 0x71 / 255f, 1.0f);
    public static final Color MEDIUM_SLATE_BLUE = new Color(0x7B / 255f, 0x68 / 255f, 0xEE / 255f, 1.0f);
    public static final Color MEDIUM_SPRING_GREEN = new Color(0x00 / 255f, 0xFA / 255f, 0x9A / 255f, 1.0f);
    public static final Color MEDIUM_TURQUOISE = new Color(0x48 / 255f, 0xD1 / 255f, 0xCC / 255f, 1.0f);
    public static final Color MEDIUM_VIOLET_RED = new Color(0xC7 / 255f, 0x15 / 255f, 0x85 / 255f, 1.0f);
    public static final Color MIDNIGHT_BLUE = new Color(0x19 / 255f, 0x19 / 255f, 0x70 / 255f, 1.0f);
    public static final Color MINT_CREAM = new Color(0xF5 / 255f, 0xFF / 255f, 0xFA / 255f, 1.0f);
    public static final Color MISTY_ROSE = new Color(0xFF / 255f, 0xE4 / 255f, 0xE1 / 255f, 1.0f);
    public static final Color MOCCASIN = new Color(0xFF / 255f, 0xE4 / 255f, 0xB5 / 255f, 1.0f);
    public static final Color NAVAJO_WHITE = new Color(0xFF / 255f, 0xDE / 255f, 0xAD / 255f, 1.0f);
    public static final Color NAVY = new Color(0x00 / 255f, 0x00 / 255f, 0x80 / 255f, 1.0f);
    public static final Color OLD_LACE = new Color(0xFD / 255f, 0xF5 / 255f, 0xE6 / 255f, 1.0f);
    public static final Color OLIVE = new Color(0x80 / 255f, 0x80 / 255f, 0x00 / 255f, 1.0f);
    public static final Color OLIVE_DRAB = new Color(0x6B / 255f, 0x8E / 255f, 0x23 / 255f, 1.0f);
    public static final Color ORANGE = new Color(0xFF / 255f, 0xA5 / 255f, 0x00 / 255f, 1.0f);
    public static final Color ORANGE_RED = new Color(0xFF / 255f, 0x45 / 255f, 0x00 / 255f, 1.0f);
    public static final Color ORCHID = new Color(0xDA / 255f, 0x70 / 255f, 0xD6 / 255f, 1.0f);
    public static final Color PALE_GOLDENROD = new Color(0xEE / 255f, 0xE8 / 255f, 0xAA / 255f, 1.0f);
    public static final Color PALE_GREEN = new Color(0x98 / 255f, 0xFB / 255f, 0x98 / 255f, 1.0f);
    public static final Color PALE_TURQUOISE = new Color(0xAF / 255f, 0xEE / 255f, 0xEE / 255f, 1.0f);
    public static final Color PALE_VIOLET_RED = new Color(0xDB / 255f, 0x70 / 255f, 0x93 / 255f, 1.0f);
    public static final Color PAPAYA_WHIP = new Color(0xFF / 255f, 0xEF / 255f, 0xD5 / 255f, 1.0f);
    public static final Color PEACH_PUFF = new Color(0xFF / 255f, 0xDA / 255f, 0xB9 / 255f, 1.0f);
    public static final Color PERU = new Color(0xCD / 255f, 0x85 / 255f, 0x3F / 255f, 1.0f);
    public static final Color PINK = new Color(0xFF / 255f, 0xC0 / 255f, 0xCB / 255f, 1.0f);
    public static final Color PLUM = new Color(0xDD / 255f, 0xA0 / 255f, 0xDD / 255f, 1.0f);
    public static final Color POWDER_BLUE = new Color(0xB0 / 255f, 0xE0 / 255f, 0xE6 / 255f, 1.0f);
    public static final Color PURPLE = new Color(0x80 / 255f, 0x00 / 255f, 0x80 / 255f, 1.0f);
    public static final Color RED = new Color(1.0f, 0.0f, 0.0f, 1.0f);
    public static final Color ROSY_BROWN = new Color(0xBC / 255f, 0x8F / 255f, 0x8F / 255f, 1.0f);
    public static final Color ROYAL_BLUE = new Color(0x41 / 255f, 0x69 / 255f, 0xE1 / 255f, 1.0f);
    public static final Color SADDLE_BROWN = new Color(0x8B / 255f, 0x45 / 255f, 0x13 / 255f, 1.0f);
    public static final Color SALMON = new Color(0xFA / 255f, 0x80 / 255f, 0x72 / 255f, 1.0f);
    public static final Color SANDY_BROWN = new Color(0xF4 / 255f, 0xA4 / 255f, 0x60 / 255f, 1.0f);
    public static final Color SEA_GREEN = new Color(0x2E / 255f, 0x8B / 255f, 0x57 / 255f, 1.0f);
    public static final Color SEASHELL = new Color(0xFF / 255f, 0xF5 / 255f, 0xEE / 255f, 1.0f);
    public static final Color SIENNA = new Color(0xA0 / 255f, 0x52 / 255f, 0x2D / 255f, 1.0f);
    public static final Color SILVER = new Color(0xC0 / 255f, 0xC0 / 255f, 0xC0 / 255f, 1.0f);
    public static final Color SKY_BLUE = new Color(0x87 / 255f, 0xCE / 255f, 0xEB / 255f, 1.0f);
    public static final Color SLATE_BLUE = new Color(0x6A / 255f, 0x5A / 255f, 0xCD / 255f, 1.0f);
    public static final Color SLATE_GRAY = new Color(0x70 / 255f, 0x80 / 255f, 0x90 / 255f, 1.0f);
    public static final Color SNOW = new Color(0xFF / 255f, 0xFA / 255f, 0xFA / 255f, 1.0f);
    public static final Color SPRING_GREEN = new Color(0x00 / 255f, 0xFF / 255f, 0x7F / 255f, 1.0f);
    public static final Color STEEL_BLUE = new Color(0x46 / 255f, 0x82 / 255f, 0xB4 / 255f, 1.0f);
    public static final Color TAN = new Color(0xD2 / 255f, 0xB4 / 255f, 0x8C / 255f, 1.0f);
    public static final Color TEAL = new Color(0x00 / 255f, 0x80 / 255f, 0x80 / 255f, 1.0f);
    public static final Color THISTLE = new Color(0xD8 / 255f, 0xBF / 255f, 0xD8 / 255f, 1.0f);
    public static final Color TOMATO = new Color(0xFF / 255f, 0x63 / 255f, 0x47 / 255f, 1.0f);
    public static final Color TRANSPARENT = new Color(0.0f, 0.0f, 0.0f, 0.0f);
    public static final Color TURQUOISE = new Color(0x40 / 255f, 0xE0 / 255f, 0xD0 / 255f, 1.0f);
    public static final Color VIOLET = new Color(0xEE / 255f, 0x82 / 255f, 0xEE / 255f, 1.0f);
    public static final Color WHEAT = new Color(0xF5 / 255f, 0xDE / 255f, 0xB3 / 255f, 1.0f);
    public static final Color WHITE = new Color(1.0f, 1.0f, 1.0f, 1.0f);
    public static final Color WHITE_SMOKE = new Color(0xF5 / 255f, 0xF5 / 255f, 0xF5 / 255f, 1.0f);
    public static final Color YELLOW = new Color(0xFF / 255f, 0xFF / 255f, 0x00 / 255f, 1.0f);
    public static final Color YELLOW_GREEN = new Color(0x9A / 255f, 0xCD / 255f, 0x32 / 255f, 1.0f);
}
