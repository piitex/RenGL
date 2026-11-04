package me.piitex.engine;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.ui.text.TextAlign;
import me.piitex.engine.ui.text.TextShaping;
import me.piitex.engine.utils.flags.GeneratedFragments;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.ImageInfo;
import org.jetbrains.skia.Typeface;

import java.util.List;
import java.util.Random;

/**
 * The RenGL splash screen the engine plays over the first window before the application's own UI appears.
 * <p>
 * While it plays ({@link #holdsApp()}) the application is held: its containers and scenes are neither updated nor drawn
 * and the input meant for them is dropped, so the application starts from its first frame once the splash lets go. The
 * splash then fades out over the running application.
 * <p>
 * The animation is a pure function of {@link #time}, drawn with the plain {@link Renderer} API, so it looks the same on
 * every graphics backend and can be drawn at any moment into an offscreen renderer.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
final class SplashScreen {
    /** Seconds the splash is fully shown before it starts to fade. */
    static final float HOLD_END = 2.5f;
    /** Seconds the fade over the application takes. */
    static final float FADE = 0.45f;

    private static final Color CYAN = new Color(0.31f, 0.82f, 1f, 1f);
    private static final Color VIOLET = new Color(0.56f, 0.38f, 0.97f, 1f);
    private static final Color PINK = new Color(0.97f, 0.46f, 0.72f, 1f);
    private static final Color INK_TOP = new Color(0.027f, 0.035f, 0.075f, 1f);
    private static final Color INK_BOTTOM = new Color(0.075f, 0.058f, 0.19f, 1f);
    private static final Color WHITE = new Color(0.95f, 0.96f, 1f, 1f);

    private static final String TAGLINE = "Creativity and design for everyday applications";

    private static final int STARS = 72;
    private static final float[] starX = new float[STARS], starY = new float[STARS];
    private static final float[] starVX = new float[STARS], starVY = new float[STARS];
    private static final float[] starPhase = new float[STARS], starSize = new float[STARS];

    static {
        Random random = new Random(0x52656E47L);
        for (int i = 0; i < STARS; i++) {
            starX[i] = random.nextFloat();
            starY[i] = random.nextFloat();
            starVX[i] = (random.nextFloat() - 0.5f) * 0.012f;
            starVY[i] = -0.004f - random.nextFloat() * 0.012f; // drifts slowly upward
            starPhase[i] = random.nextFloat() * 6.2832f;
            starSize[i] = 0.8f + random.nextFloat() * 1.5f;
        }
    }

    private float time;
    private float[] trace = new float[16];
    private Image glowImage;

    // Fonts are built for one window scale and rebuilt when it changes.
    private int fontKey = -1;
    private org.jetbrains.skia.Font wordFont, markFont, tagFont, smallFont;

    /** Advances the animation. A long frame (the first one compiles shaders) moves it by at most 50 ms, so it is not skipped. */
    void advance(float delta) {
        time += Math.min(Math.max(delta, 0f), 0.05f);
    }

    /** Jumps to {@code seconds} into the animation. */
    void seek(float seconds) {
        time = seconds;
    }

    float time() {
        return time;
    }

    /** True while the application is held back: nothing of it is updated, drawn or given input. */
    boolean holdsApp() {
        return time < HOLD_END;
    }

    boolean finished() {
        return time >= HOLD_END + FADE;
    }

    void dispose() {
        closeFonts();
    }

    private void closeFonts() {
        if (glowImage != null) {
            glowImage.close();
            glowImage = null;
        }
        for (org.jetbrains.skia.Font f : new org.jetbrains.skia.Font[]{wordFont, markFont, tagFont, smallFont}) {
            if (f != null) f.close();
        }
        wordFont = markFont = tagFont = smallFont = null;
        fontKey = -1;
    }

    /** Draws the splash over a {@code width} x {@code height} window. {@code api} is the graphics API's name, shown in the footer. */
    void render(Renderer r, int width, int height, String api) {
        float t = time;
        float s = Math.clamp(Math.min(width / 900f, height / 560f), 0.45f, 2f);
        buildFonts(s);

        float exit = ease(clamp01((t - HOLD_END) / FADE));
        float cx = width / 2f;
        float markCy = height * 0.40f;
        float radius = 74f * s;

        r.pushAlpha(1f - exit);

        // Backdrop: deep ink, two soft colour pools, a drifting star field.
        r.drawGradient(0, 0, width, height, new LinearGradient(List.of(INK_TOP, INK_BOTTOM), 90f), 0);
        float bgIn = ease(clamp01(t / 0.9f));
        r.pushAlpha(bgIn);
        glow(r, width * 0.12f, height * 0.10f, Math.max(width, height) * 0.55f, CYAN, 0.14f);
        glow(r, width * 0.92f, height * 0.95f, Math.max(width, height) * 0.60f, VIOLET, 0.24f);
        stars(r, width, height, s, t);
        r.popAlpha();

        // Everything else grows a little as it leaves.
        r.pushScale(1f + 0.05f * exit, cx, height / 2f);

        // Halo behind the mark swells as the mark forms and then breathes.
        float form = ease(clamp01((t - 0.2f) / 1.3f));
        float breathe = 0.5f + 0.5f * (float) Math.sin(t * 2.2f);
        glow(r, cx, markCy, radius * (2.6f + 0.15f * breathe), VIOLET, 0.8f * form);
        glow(r, cx, markCy, radius * 1.7f, CYAN, 0.38f * form);

        drawMark(r, cx, markCy, radius, s, t);
        float wordTop = drawWordmark(r, cx, markCy + radius + 30f * s, s, t);

        // Accent line and tagline.
        float lineP = ease(clamp01((t - 1.3f) / 0.5f));
        float lineY = wordTop + wordFont.getSize() * 1.28f;
        float lineW = 150f * s * lineP;
        if (lineW > 0.5f) {
            r.pushAlpha(lineP);
            r.drawGradient(cx - lineW / 2f, lineY, lineW, Math.max(1.5f, 2f * s),
                    new LinearGradient(List.of(CYAN, VIOLET, PINK), 0f), s);
            r.popAlpha();
        }
        float tagP = ease(clamp01((t - 1.4f) / 0.5f));
        if (tagP > 0f) {
            r.pushAlpha(0.78f * tagP);
            r.drawText(TAGLINE, cx, lineY + 16f * s + (1f - tagP) * 8f * s, tagFont, new Color(0.72f, 0.76f, 0.92f, 1f), TextAlign.CENTER);
            r.popAlpha();
        }

        r.popScale();

        // Footer and a thin progress line along the bottom edge.
        float footP = ease(clamp01((t - 1.6f) / 0.4f));
        if (footP > 0f && api != null) {
            r.pushAlpha(0.42f * footP);
            r.drawText("RENDERING WITH " + api.toUpperCase(), cx, height - 40f * s, smallFont, new Color(0.7f, 0.74f, 0.95f, 1f), TextAlign.CENTER);
            r.popAlpha();
        }
        float progress = clamp01(t / HOLD_END);
        float barH = Math.max(2f, 3f * s);
        r.pushAlpha(0.9f);
        if (progress > 0.001f) {
            r.drawGradient(0, height - barH, width * ease(progress), barH, new LinearGradient(List.of(CYAN, VIOLET, PINK), 0f), 0);
        }
        r.popAlpha();

        r.popAlpha();
    }

    // ---- the mark: two hexagons that trace themselves, a lettered core, an orbiting spark -------------------------

    private void drawMark(Renderer r, float cx, float cy, float rad, float s, float t) {
        LinearGradient ring = new LinearGradient(List.of(CYAN, VIOLET, PINK, CYAN), 0f);
        float spin = Math.max(0f, t - 1.2f) * 14f;

        // Outer hexagon: traces clockwise from the top vertex.
        float outer = ease(clamp01((t - 0.2f) / 0.85f));
        if (outer > 0f) {
            int n = hexTrace(cx, cy, rad, 0f, 1, outer);
            for (int g = 0; g < 4; g++) { // a halo along the stroke: wide and faint to narrow and brighter
                r.pushAlpha(0.045f + 0.03f * g);
                r.drawPolyline(trace, n, ring, (18f - g * 3.6f) * s);
                r.popAlpha();
            }
            r.drawPolyline(trace, n, ring, Math.max(2f, 4f * s));
            if (outer < 1f) { // the leading spark
                float hx = trace[(n - 1) * 2], hy = trace[(n - 1) * 2 + 1];
                glow(r, hx, hy, 22f * s, WHITE, 0.8f);
                r.drawCircle(hx, hy, 3.2f * s, WHITE);
            }
        }

        // Inner hexagon: turned a half step, traces the other way, then turns slowly.
        float inner = ease(clamp01((t - 0.5f) / 0.75f));
        if (inner > 0f) {
            int n = hexTrace(cx, cy, rad * 0.64f, 30f + spin, -1, inner);
            r.pushAlpha(0.65f);
            r.drawPolyline(trace, n, new LinearGradient(List.of(PINK, VIOLET, CYAN), 0f), Math.max(1.5f, 2.2f * s));
            r.popAlpha();
        }

        // A ring of light leaves the mark the moment the outer hexagon closes.
        float pulse = clamp01((t - 1.05f) / 0.7f);
        if (pulse > 0f && pulse < 1f) {
            float pr = rad * (1f + 1.1f * easeOut(pulse));
            r.pushAlpha(0.55f * (1f - pulse));
            r.drawBorder(cx - pr, cy - pr, pr * 2, pr * 2, new Color(0.7f, 0.8f, 1f, 1f), Math.max(1.5f, 2f * s), pr);
            r.popAlpha();
        }

        // The R, springing in.
        float letterP = clamp01((t - 0.95f) / 0.5f);
        if (letterP > 0f) {
            float pop = easeOutBack(letterP);
            FontMetricsCompat m = FontMetricsCompat.of(markFont);
            float capH = markFont.getSize() * 0.72f;
            float top = cy + capH / 2f + m.getAscent(); // baseline sits half a cap height below the centre
            r.pushAlpha(ease(letterP));
            r.pushScale(pop, cx, cy);
            r.drawText("R", cx, top, markFont, new LinearGradient(List.of(WHITE, new Color(0.78f, 0.84f, 1f, 1f)), 90f), TextAlign.CENTER);
            r.popScale();
            r.popAlpha();
        }

        // Two sparks run the outer edge once it is closed.
        if (outer >= 1f) {
            float fade = ease(clamp01((t - 1.05f) / 0.5f));
            for (int i = 0; i < 2; i++) {
                float u = ((t - 1.05f) * 0.22f + i * 0.5f) % 1f;
                for (int k = 6; k >= 0; k--) { // a short fading tail
                    float tu = ((u - k * 0.006f) % 1f + 1f) % 1f;
                    hexPoint(cx, cy, rad, 0f, tu);
                    r.pushAlpha(fade * (1f - k / 7f));
                    r.drawCircle(trace[0], trace[1], (3.2f - k * 0.3f) * s, i == 0 ? CYAN : PINK);
                    r.popAlpha();
                }
            }
        }
    }

    /** Fills {@link #trace} with the outline of a pointy-top hexagon drawn up to {@code p} (0..1) of its way round; returns the point count. */
    private int hexTrace(float cx, float cy, float rad, float rotDeg, int dir, float p) {
        float edges = 6f * p;
        int full = Math.min((int) edges, 6);
        float frac = edges - full;
        int n = 0;
        for (int k = 0; k <= full; k++) {
            hexVertex(n++, cx, cy, rad, rotDeg, dir, k);
        }
        if (full < 6 && frac > 0f) {
            hexVertex(n, cx, cy, rad, rotDeg, dir, full + 1);
            float x0 = trace[(n - 1) * 2], y0 = trace[(n - 1) * 2 + 1];
            trace[n * 2] = x0 + (trace[n * 2] - x0) * frac;
            trace[n * 2 + 1] = y0 + (trace[n * 2 + 1] - y0) * frac;
            n++;
        }
        return n;
    }

    private void hexVertex(int slot, float cx, float cy, float rad, float rotDeg, int dir, int k) {
        double a = Math.toRadians(rotDeg - 90f + dir * 60f * k);
        trace[slot * 2] = cx + (float) Math.cos(a) * rad;
        trace[slot * 2 + 1] = cy + (float) Math.sin(a) * rad;
    }

    /** Puts the point {@code u} (0..1) of the way round a hexagon's outline in trace[0], trace[1]. */
    private void hexPoint(float cx, float cy, float rad, float rotDeg, float u) {
        float e = u * 6f;
        int k = (int) e;
        float f = e - k;
        hexVertex(2, cx, cy, rad, rotDeg, 1, k);
        hexVertex(3, cx, cy, rad, rotDeg, 1, k + 1);
        trace[0] = trace[4] + (trace[6] - trace[4]) * f;
        trace[1] = trace[5] + (trace[7] - trace[5]) * f;
    }

    // ---- the wordmark ----------------------------------------------------------------------------------------------

    /** Draws "RenGL" letter by letter, then a shimmer across it. Returns the top of the line. */
    private float drawWordmark(Renderer r, float cx, float top, float s, float t) {
        String word = "RenGL";
        float spacing = wordFont.getSize() * 0.045f;
        float[] widths = new float[word.length()];
        float total = 0f;
        for (int i = 0; i < widths.length; i++) {
            widths[i] = TextShaping.width(wordFont, word.substring(i, i + 1));
            total += widths[i] + (i < widths.length - 1 ? spacing : 0f);
        }
        float x = cx - total / 2f;
        float left = x;
        for (int i = 0; i < widths.length; i++) {
            float p = ease(clamp01((t - (0.95f + 0.07f * i)) / 0.45f));
            if (p > 0f) {
                r.pushAlpha(p);
                r.drawText(word.substring(i, i + 1), x, top + (1f - p) * 22f * s, wordFont, letterPaint(i));
                r.popAlpha();
            }
            x += widths[i] + spacing;
        }

        // Shimmer: a bright band crosses the word, drawn as a soft stack of clipped white copies.
        float sweep = clamp01((t - 1.7f) / 0.55f);
        if (sweep > 0f && sweep < 1f) {
            float bandX = left - 80f * s + (total + 160f * s) * ease(sweep);
            float h = wordFont.getSize() * 1.3f;
            float[] halfWidth = {46f * s, 28f * s, 12f * s};
            float[] alpha = {0.16f, 0.22f, 0.30f};
            for (int b = 0; b < 3; b++) {
                r.pushClip(bandX - halfWidth[b], top - 4f * s, halfWidth[b] * 2, h);
                r.pushAlpha(alpha[b]);
                float lx = left;
                for (int i = 0; i < widths.length; i++) {
                    r.drawText(word.substring(i, i + 1), lx, top, wordFont, WHITE);
                    lx += widths[i] + spacing;
                }
                r.popAlpha();
                r.popClip();
            }
        }
        return top;
    }

    /** "Ren" is near white; "GL" carries the brand gradient. */
    private static me.piitex.engine.ui.color.Paint letterPaint(int index) {
        return switch (index) {
            case 3 -> new LinearGradient(List.of(CYAN, VIOLET), 0f);
            case 4 -> new LinearGradient(List.of(VIOLET, PINK), 0f);
            default -> WHITE;
        };
    }

    // ---- backdrop pieces -------------------------------------------------------------------------------------------

    private static void stars(Renderer r, int width, int height, float s, float t) {
        float[] px = new float[STARS], py = new float[STARS];
        float link = 120f * s;
        for (int i = 0; i < STARS; i++) {
            px[i] = (((starX[i] + starVX[i] * t) % 1f + 1f) % 1f) * width;
            py[i] = (((starY[i] + starVY[i] * t) % 1f + 1f) % 1f) * height;
        }
        for (int i = 0; i < STARS; i++) {
            for (int j = i + 1; j < STARS; j++) {
                float dx = px[i] - px[j], dy = py[i] - py[j];
                float d2 = dx * dx + dy * dy;
                if (d2 < link * link) {
                    float a = (1f - (float) Math.sqrt(d2) / link) * 0.16f;
                    r.drawLine(px[i], py[i], px[j], py[j], new Color(0.55f, 0.65f, 1f, a), 1f);
                }
            }
        }
        for (int i = 0; i < STARS; i++) {
            float twinkle = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * 1.6f + starPhase[i]));
            r.drawCircle(px[i], py[i], starSize[i] * s, new Color(0.78f, 0.85f, 1f, 0.55f * twinkle));
        }
    }

    /** A soft pool of colour: one pre-rendered radial falloff, drawn tinted. Stacked translucent circles band in 8 bits. */
    private void glow(Renderer r, float x, float y, float radius, Color color, float strength) {
        if (strength <= 0.001f || radius <= 0.5f) return;
        if (glowImage == null) glowImage = makeGlowImage();
        r.drawImage(glowImage, x - radius, y - radius, radius * 2, radius * 2, 0f, Math.clamp(strength, 0f, 1f),
                new Color(color.getR(), color.getG(), color.getB(), 1f));
    }

    /** White that fades with distance from the centre, as premultiplied pixels with a little noise so 8 bits don't band. */
    private static Image makeGlowImage() {
        final int size = 256;
        byte[] pixels = new byte[size * size * 4];
        Random noise = new Random(1);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float d = (float) Math.hypot(x + 0.5f - size / 2f, y + 0.5f - size / 2f) / (size / 2f);
                float f = Math.max(0f, 1f - d);
                float a = f * f * f * (f * 0.6f + 0.4f); // soft shoulder, long tail
                int v = Math.clamp(Math.round(a * 255f + noise.nextFloat() - 0.5f), 0, 255);
                int i = (y * size + x) * 4;
                pixels[i] = pixels[i + 1] = pixels[i + 2] = pixels[i + 3] = (byte) v; // premultiplied white
            }
        }
        return Image.Companion.makeRaster(ImageInfo.Companion.makeN32Premul(size, size), pixels, size * 4);
    }

    // ---- fonts and easing ------------------------------------------------------------------------------------------

    private void buildFonts(float s) {
        int key = Math.round(s * 100f);
        if (key == fontKey && wordFont != null) return;
        closeFonts();
        Typeface face = Font.systemDefault();
        if (face == null) face = Font.getDefault();
        wordFont = new org.jetbrains.skia.Font(face, 60f * s);
        wordFont.setEmboldened(true);
        markFont = new org.jetbrains.skia.Font(face, 72f * s);
        markFont.setEmboldened(true);
        tagFont = new org.jetbrains.skia.Font(face, 15f * s);
        smallFont = new org.jetbrains.skia.Font(face, 10.5f * s);
        fontKey = key;
    }

    private static float clamp01(float v) {
        return Math.clamp(v, 0f, 1f);
    }

    private static float ease(float x) { // smoothstep-ish ease in-out
        return x * x * (3f - 2f * x);
    }

    private static float easeOut(float x) {
        float inv = 1f - x;
        return 1f - inv * inv * inv;
    }

    private static float easeOutBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        float v = x - 1f;
        return 1f + c3 * v * v * v + c1 * v * v;
    }
}
