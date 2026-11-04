package me.piitex.engine;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
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
 * The RenGL splash screen, which the engine plays over the first window before the application's own UI appears unless
 * the application sets its own {@link Splash} or switches the splash off (see {@link WindowOptions#setSplash}).
 * <p>
 * While it plays ({@link #isHoldingApp()}) the application is held: its containers and scenes are neither updated nor
 * drawn and the input meant for them is dropped, so the application starts from its first frame once the splash lets
 * go. The splash then fades out over the running application.
 * <p>
 * If the window's {@linkplain WindowOptions#addWarmUp warm-ups} are still running when the hold ends, the splash stays
 * up until they are done: the timeline waits at its end, the backdrop keeps moving, the progress bar stops short of
 * full and the footer names the work.
 * <p>
 * The animation is a function of the time since it began (and of the time spent waiting), drawn with the plain
 * {@link Renderer} API, so it looks the same on every graphics backend and can be drawn at any moment into an
 * offscreen renderer. It plays once; make another for another window.
 * <p>
 * With the {@code rengl-audio} module on the classpath it also has a sound, cue by cue in step with the picture (see
 * {@link RenGLSplashSound}); {@link WindowOptions#setSplashSoundEnabled} turns it off.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class RenGLSplash implements Splash {
    /**
     * Seconds the splash is fully shown before it starts to fade.
     */
    static final float HOLD_END = 2.5f;
    /**
     * Seconds the fade over the application takes.
     */
    static final float FADE = 0.45f;
    /**
     * The widest the progress bar gets while warm-ups are still running, as a fraction of the window.
     */
    private static final float WAITING_BAR = 0.9f;

    /**
     * The colour at the top of the backdrop. The engine also clears the window to it while the splash holds the application.
     */
    static final Color INK_TOP = new Color(0.027f, 0.035f, 0.075f, 1f);

    private static final Color CYAN = new Color(0.31f, 0.82f, 1f, 1f);
    private static final Color VIOLET = new Color(0.56f, 0.38f, 0.97f, 1f);
    private static final Color PINK = new Color(0.97f, 0.46f, 0.72f, 1f);
    private static final Color INK_BOTTOM = new Color(0.075f, 0.058f, 0.19f, 1f);
    private static final Color WHITE = new Color(0.95f, 0.96f, 1f, 1f);
    private static final Color LETTER_BOTTOM = new Color(0.78f, 0.84f, 1f, 1f);
    private static final Color PULSE_RING = new Color(0.7f, 0.8f, 1f, 1f);
    private static final Color TAGLINE_COLOR = new Color(0.72f, 0.76f, 0.92f, 1f);
    private static final Color FOOTER_COLOR = new Color(0.7f, 0.74f, 0.95f, 1f);
    private static final Color STAR_COLOR = new Color(0.78f, 0.85f, 1f, 1f);

    private static final String TAGLINE = "Java GUI Engine";
    private static final String WORD = "RenGL";

    /**
     * A full turn in radians, for the random twinkle phase of each star.
     */
    private static final float TAU = (float) (2 * Math.PI);

    /**
     * The window size the layout was designed for; the splash scales with the tighter of the two dimensions.
     */
    private static final float DESIGN_WIDTH = 900f;
    private static final float DESIGN_HEIGHT = 560f;

    private static final int STARS = 72;
    /**
     * Start position as a fraction of the window (0..1).
     */
    private static final float[] starX = new float[STARS], starY = new float[STARS];
    /**
     * Drift in window fractions per second.
     */
    private static final float[] starVelocityX = new float[STARS], starVelocityY = new float[STARS];
    private static final float[] starPhase = new float[STARS], starSize = new float[STARS];

    static {
        // The seed is "ReNG" in ASCII, so the star field is the same on every run.
        Random random = new Random(0x52656E47L);
        for (int i = 0; i < STARS; i++) {
            starX[i] = random.nextFloat();
            starY[i] = random.nextFloat();
            // Sideways drift is uniform in -0.006..+0.006 of the window per second.
            starVelocityX[i] = (random.nextFloat() - 0.5f) * 0.012f;
            // Upward drift (negative y is up) of 0.004..0.016 of the window per second.
            starVelocityY[i] = -0.004f - random.nextFloat() * 0.012f;
            // A random point in the twinkle cycle, so the stars don't pulse together.
            starPhase[i] = random.nextFloat() * TAU;
            // Radius of 0.8..2.3 logical pixels before scaling.
            starSize[i] = 0.8f + random.nextFloat() * 1.5f;
        }
    }

    /**
     * Seconds of the timeline played. It stops at {@link #HOLD_END} while the window's warm-ups are still running.
     */
    private float time;
    /**
     * Seconds spent at the end of the hold, waiting for the warm-ups. The backdrop keeps moving through them.
     */
    private float waited;
    /**
     * The window's warm-up progress, or null when the splash was not started on a window.
     */
    private Loading loading;
    /**
     * The graphics API's name, shown in the footer; null shows no footer.
     */
    private String api;
    /**
     * Scratch space for the points of a hexagon outline: x and y pairs, up to the 7 points a full outline needs.
     */
    private final float[] trace = new float[16];
    private Image glowImage;
    /**
     * The splash's sound, or null when it has none (switched off, no audio module, or not started on a window).
     */
    private RenGLSplashSound sound;

    // Fonts are built for one window scale and rebuilt when it changes.
    private int fontKey = -1;
    private org.jetbrains.skia.Font wordFont, markFont, tagFont, smallFont;

    /**
     * Reads which graphics API draws the splash, for the footer.
     */
    @Override
    public void onStart(Window window) {
        showApi(window.getRenderer().name());
        loading = window.getLoading();
        if (window.getWindowOptions().isSplashSoundEnabled()) {
            SoundOutput output = SoundOutput.find();
            if (output != null) {
                sound = new RenGLSplashSound(output);
                sound.loadInBackground();
            }
        }
    }

    /**
     * Plays {@code sound} with the timeline, for tests that have no window.
     */
    void playSound(RenGLSplashSound sound) {
        this.sound = sound;
    }

    /**
     * Whether warm-ups are still running, which keeps the splash up past its usual length.
     */
    private boolean isWaiting() {
        return loading != null && !loading.isDone();
    }

    /**
     * Sets the graphics API's name shown in the footer.
     */
    void showApi(String api) {
        this.api = api;
    }

    /**
     * Advances the animation. A long frame (the first one compiles shaders) moves it by at most 50 ms, so it is not skipped.
     */
    @Override
    public void update(float delta) {
        float step = Math.min(Math.max(delta, 0f), 0.05f);
        if (isWaiting()) {
            // The timeline plays up to the end of the hold and no further; what is left of the step goes to the wait.
            float room = Math.max(0f, HOLD_END - time);
            float played = Math.min(step, room);
            time += played;
            waited += step - played;
        } else {
            time += step;
        }
        // While the warm-ups run the timeline rests at the end of the hold; rounding can leave it a hair past, which
        // must not start the fade's sound before the fade.
        if (sound != null) sound.advance(isWaiting() ? Math.min(time, HOLD_END) : time);
    }

    /**
     * Jumps to {@code seconds} into the animation.
     */
    void seek(float seconds) {
        time = seconds;
    }

    /**
     * Follows {@code progress} instead of a window's, for tests that have no window.
     */
    void watch(Loading progress) {
        loading = progress;
    }

    float waited() {
        return waited;
    }

    float time() {
        return time;
    }

    /**
     * True while the application is held back: nothing of it is updated, drawn or given input. That lasts the hold, and
     * longer if the window's warm-ups are still running when it ends.
     */
    @Override
    public boolean isHoldingApp() {
        return time < HOLD_END || isWaiting();
    }

    @Override
    public boolean isFinished() {
        return time >= HOLD_END + FADE;
    }

    /**
     * The deep ink of the backdrop, so the window is that colour from its very first frame.
     */
    @Override
    public Color getClearColor() {
        return INK_TOP;
    }

    @Override
    public void dispose() {
        closeFonts();
        // The last sounds are left to ring out over the application; they are freed once they have finished.
        if (sound != null) {
            sound.release();
            sound = null;
        }
    }

    private void closeFonts() {
        if (glowImage != null) {
            glowImage.close();
            glowImage = null;
        }
        for (org.jetbrains.skia.Font font : new org.jetbrains.skia.Font[]{wordFont, markFont, tagFont, smallFont}) {
            if (font != null) font.close();
        }
        wordFont = markFont = tagFont = smallFont = null;
        fontKey = -1;
    }

    @Override
    public void render(Renderer renderer, int width, int height) {
        // The animation's clock: the timeline plus any time spent waiting at its end, so the backdrop never freezes. The
        // timeline's own events (the intro, the fade) all follow time alone.
        float seconds = time + waited;
        // Everything is laid out for a 900x560 window and scaled by whichever dimension has less room, within 0.45x to 2x.
        float scale = Math.clamp(Math.min(width / DESIGN_WIDTH, height / DESIGN_HEIGHT), 0.45f, 2f);
        buildFonts(scale);

        // 0 while the splash holds, rising to 1 across the fade; eased so the fade starts and ends gently.
        float exit = ease(progress(time, HOLD_END, FADE));
        float centerX = width / 2f;
        // The mark sits 40% of the way down the window, leaving the lower part for the wordmark and tagline.
        float markCenterY = height * 0.40f;
        float markRadius = 74f * scale;

        renderer.pushAlpha(1f - exit);

        // Backdrop: deep ink, two soft colour pools, a drifting star field.
        renderer.drawGradient(0, 0, width, height, new LinearGradient(List.of(INK_TOP, INK_BOTTOM), 90f), 0);
        float backdropIn = ease(progress(seconds, 0f, 0.9f));
        renderer.pushAlpha(backdropIn);
        float longestSide = Math.max(width, height);
        glow(renderer, width * 0.12f, height * 0.10f, longestSide * 0.55f, CYAN, 0.14f);
        glow(renderer, width * 0.92f, height * 0.95f, longestSide * 0.60f, VIOLET, 0.24f);
        drawStars(renderer, width, height, scale, seconds);
        renderer.popAlpha();

        // Everything else grows 5% about the window centre as it leaves.
        renderer.pushScale(1f + 0.05f * exit, centerX, height / 2f);

        // Halo behind the mark swells as the mark forms and then breathes.
        float form = ease(progress(seconds, 0.2f, 1.3f));
        // A sine wave remapped from -1..1 to 0..1; 2.2 radians per second is a breath about every 2.9 seconds.
        float breathe = 0.5f + 0.5f * (float) Math.sin(seconds * 2.2f);
        glow(renderer, centerX, markCenterY, markRadius * (2.6f + 0.15f * breathe), VIOLET, 0.8f * form);
        glow(renderer, centerX, markCenterY, markRadius * 1.7f, CYAN, 0.38f * form);

        drawMark(renderer, centerX, markCenterY, markRadius, scale, seconds);
        // The wordmark starts 30 units under the mark's lower edge (its centre plus its radius).
        float wordTop = markCenterY + markRadius + 30f * scale;
        drawWordmark(renderer, centerX, wordTop, scale, seconds);

        // Accent line and tagline.
        float lineProgress = ease(progress(seconds, 1.3f, 0.5f));
        // The line sits 1.28 font sizes below the top of the word: the height of its letters plus a gap.
        float lineY = wordTop + wordFont.getSize() * 1.28f;
        float lineWidth = 150f * scale * lineProgress; // grows from the middle outwards to 150 units
        if (lineWidth > 0.5f) {
            renderer.pushAlpha(lineProgress);
            renderer.drawGradient(centerX - lineWidth / 2f, lineY, lineWidth, Math.max(1.5f, 2f * scale),
                    new LinearGradient(List.of(CYAN, VIOLET, PINK), 0f), scale);
            renderer.popAlpha();
        }
        float taglineProgress = ease(progress(seconds, 1.4f, 0.5f));
        if (taglineProgress > 0f) {
            renderer.pushAlpha(0.78f * taglineProgress);
            // The tagline is 16 units under the line and slides up the last 8 units as it fades in.
            float taglineY = lineY + 16f * scale + (1f - taglineProgress) * 8f * scale;
            renderer.drawText(TAGLINE, centerX, taglineY, tagFont, TAGLINE_COLOR, TextAlign.CENTER);
            renderer.popAlpha();
        }

        renderer.popScale();

        // Footer and a thin progress line along the bottom edge.
        float footerProgress = ease(progress(seconds, 1.6f, 0.4f));
        // While warm-ups run the footer says what is being loaded; otherwise it names the graphics API.
        String loadingName = isWaiting() ? loading.getCurrent() : null;
        String footer = loadingName != null ? "LOADING " + loadingName.toUpperCase()
                : api != null ? "RENDERING WITH " + api.toUpperCase() : null;
        if (footerProgress > 0f && footer != null) {
            renderer.pushAlpha(0.42f * footerProgress);
            renderer.drawText(footer, centerX, height - 40f * scale, smallFont, FOOTER_COLOR, TextAlign.CENTER);
            renderer.popAlpha();
        }
        // How far through the held part of the splash we are; the bar below fills the window width as it goes.
        float barFraction = ease(progress(time, 0f, HOLD_END));
        float barAlpha = 0.9f;
        if (isWaiting()) {
            // The work is not finished, so the bar stops at 90% of the width rather than claim it is, and breathes
            // (5 radians per second, between half and full strength) to show that it is still going.
            barFraction = Math.min(barFraction, WAITING_BAR);
            barAlpha *= 0.75f + 0.25f * (float) Math.sin(seconds * 5f);
        }
        float barHeight = Math.max(2f, 3f * scale);
        renderer.pushAlpha(barAlpha);
        if (barFraction > 0.001f) {
            renderer.drawGradient(0, height - barHeight, width * barFraction, barHeight,
                    new LinearGradient(List.of(CYAN, VIOLET, PINK), 0f), 0);
        }
        renderer.popAlpha(); // the bar's
        renderer.popAlpha(); // the splash's, from the fade
    }

    /**
     * The mark: two hexagons that trace themselves, a lettered core and two orbiting sparks.
     */
    private void drawMark(Renderer renderer, float centerX, float centerY, float radius, float scale, float seconds) {
        LinearGradient ring = new LinearGradient(List.of(CYAN, VIOLET, PINK, CYAN), 0f);
        // The inner hexagon starts turning at 1.2 s, at 14 degrees per second.
        float spinDegrees = Math.max(0f, seconds - 1.2f) * 14f;

        // Outer hexagon: traces clockwise from the top vertex.
        float outer = ease(progress(seconds, 0.2f, 0.85f));
        if (outer > 0f) {
            int pointCount = hexTrace(centerX, centerY, radius, 0f, 1, outer);
            // A halo along the stroke: four passes from wide and faint (18 units, 4.5% alpha) down to narrow and
            // brighter (7.2 units, 13.5% alpha), each 3.6 units thinner and 3% more opaque than the one before.
            for (int pass = 0; pass < 4; pass++) {
                renderer.pushAlpha(0.045f + 0.03f * pass);
                renderer.drawPolyline(trace, pointCount, ring, (18f - pass * 3.6f) * scale);
                renderer.popAlpha();
            }
            renderer.drawPolyline(trace, pointCount, ring, Math.max(2f, 4f * scale));
            if (outer < 1f) {
                // The leading spark rides the last point of the outline drawn so far.
                float headX = trace[(pointCount - 1) * 2];
                float headY = trace[(pointCount - 1) * 2 + 1];
                glow(renderer, headX, headY, 22f * scale, WHITE, 0.8f);
                renderer.drawCircle(headX, headY, 3.2f * scale, WHITE);
            }
        }

        // Inner hexagon: 64% of the outer radius, turned a half step (30 degrees), traces the other way, then turns slowly.
        float inner = ease(progress(seconds, 0.5f, 0.75f));
        if (inner > 0f) {
            int pointCount = hexTrace(centerX, centerY, radius * 0.64f, 30f + spinDegrees, -1, inner);
            renderer.pushAlpha(0.65f);
            renderer.drawPolyline(trace, pointCount, new LinearGradient(List.of(PINK, VIOLET, CYAN), 0f), Math.max(1.5f, 2.2f * scale));
            renderer.popAlpha();
        }

        // A ring of light leaves the mark the moment the outer hexagon closes.
        float pulse = progress(seconds, 1.05f, 0.7f);
        if (pulse > 0f && pulse < 1f) {
            // The ring starts at the hexagon's radius and grows to 2.1 times it, quickly at first and then settling.
            float pulseRadius = radius * (1f + 1.1f * easeOut(pulse));
            renderer.pushAlpha(0.55f * (1f - pulse)); // fades linearly to nothing as it grows
            // A square border whose corner radius equals the pulse radius is a circle.
            renderer.drawBorder(centerX - pulseRadius, centerY - pulseRadius, pulseRadius * 2, pulseRadius * 2,
                    PULSE_RING, Math.max(1.5f, 2f * scale), pulseRadius);
            renderer.popAlpha();
        }

        // The R, springing in.
        float letterProgress = progress(seconds, 0.95f, 0.5f);
        if (letterProgress > 0f) {
            float pop = easeOutBack(letterProgress); // overshoots its size a little, then settles at 1
            FontMetricsCompat metrics = FontMetricsCompat.of(markFont);
            // A capital letter is about 72% as tall as the font size.
            float capHeight = markFont.getSize() * 0.72f;
            // drawText takes the top of the text. The ascent is negative (the distance from the baseline up to the top),
            // so top = baseline + ascent, with the baseline half a cap height below the centre so the R is centred.
            float textTop = centerY + capHeight / 2f + metrics.getAscent();
            renderer.pushAlpha(ease(letterProgress));
            renderer.pushScale(pop, centerX, centerY);
            renderer.drawText("R", centerX, textTop, markFont, new LinearGradient(List.of(WHITE, LETTER_BOTTOM), 90f), TextAlign.CENTER);
            renderer.popScale();
            renderer.popAlpha();
        }

        // Two sparks run the outer edge once it is closed.
        if (outer >= 1f) {
            float sparkFade = ease(progress(seconds, 1.05f, 0.5f));
            for (int spark = 0; spark < 2; spark++) {
                // Position along the outline as a fraction 0..1 (the % 1 wraps it round): 0.22 of a lap per second, and
                // the second spark starts half a lap behind the first.
                float lapPosition = ((seconds - 1.05f) * 0.22f + spark * 0.5f) % 1f;
                for (int tail = 6; tail >= 0; tail--) {
                    // Each tail dot sits 0.006 of a lap behind the one in front. Java's % keeps the sign of the dividend, so
                    // adding 1 and taking % 1 again wraps a negative position back into 0..1.
                    float dotPosition = ((lapPosition - tail * 0.006f) % 1f + 1f) % 1f;
                    hexPoint(centerX, centerY, radius, 0f, dotPosition);
                    // The dots fade and shrink towards the end of the tail (from 100% alpha and 3.2 units to 14% and 1.4).
                    renderer.pushAlpha(sparkFade * (1f - tail / 7f));
                    renderer.drawCircle(trace[0], trace[1], (3.2f - tail * 0.3f) * scale, spark == 0 ? CYAN : PINK);
                    renderer.popAlpha();
                }
            }
        }
    }

    /**
     * Fills {@link #trace} with the outline of a pointy-top hexagon drawn up to {@code fraction} (0..1) of its way round
     * and returns the number of points.
     *
     * @param rotationDegrees turns the hexagon clockwise by this many degrees
     * @param direction       1 to trace clockwise, -1 for anticlockwise
     */
    private int hexTrace(float centerX, float centerY, float radius, float rotationDegrees, int direction, float fraction) {
        // A hexagon has 6 edges, so the fraction of the way round times 6 is how many edges are covered, whole and partial.
        float edgesCovered = 6f * fraction;
        int wholeEdges = Math.min((int) edgesCovered, 6);
        float partialEdge = edgesCovered - wholeEdges;
        int pointCount = 0;
        // Every whole edge ends on a vertex, and the first vertex begins the outline: wholeEdges + 1 points.
        for (int vertex = 0; vertex <= wholeEdges; vertex++) {
            hexVertex(pointCount++, centerX, centerY, radius, rotationDegrees, direction, vertex);
        }
        if (wholeEdges < 6 && partialEdge > 0f) {
            // The next vertex goes in the slot after the last one, then is pulled back towards the last vertex so the
            // outline stops partway along the unfinished edge: last + (next - last) * partialEdge.
            hexVertex(pointCount, centerX, centerY, radius, rotationDegrees, direction, wholeEdges + 1);
            float lastX = trace[(pointCount - 1) * 2];
            float lastY = trace[(pointCount - 1) * 2 + 1];
            trace[pointCount * 2] = lastX + (trace[pointCount * 2] - lastX) * partialEdge;
            trace[pointCount * 2 + 1] = lastY + (trace[pointCount * 2 + 1] - lastY) * partialEdge;
            pointCount++;
        }
        return pointCount;
    }

    /**
     * Writes vertex number {@code vertex} of a pointy-top hexagon into slot {@code slot} (an x, y pair) of {@link #trace}.
     */
    private void hexVertex(int slot, float centerX, float centerY, float radius, float rotationDegrees, int direction, int vertex) {
        // The vertices are 60 degrees apart (360 / 6). Starting at -90 degrees puts vertex 0 at the top, because the
        // y axis points down the screen; a negative direction runs round the other way.
        double angle = Math.toRadians(rotationDegrees - 90f + direction * 60f * vertex);
        // A point on a circle of the given radius: centre + (cos, sin) * radius.
        trace[slot * 2] = centerX + (float) Math.cos(angle) * radius;
        trace[slot * 2 + 1] = centerY + (float) Math.sin(angle) * radius;
    }

    /**
     * Puts the point {@code position} (0..1) of the way round a hexagon's outline in trace[0], trace[1].
     */
    private void hexPoint(float centerX, float centerY, float radius, float rotationDegrees, float position) {
        // Six edges: the whole part of position * 6 is the edge, the remainder is how far along that edge.
        float edgesCovered = position * 6f;
        int edge = (int) edgesCovered;
        float alongEdge = edgesCovered - edge;
        // The two ends of the edge go in the spare slots 2 and 3 (indices 4..7), then the point is the blend between
        // them: start + (end - start) * alongEdge.
        hexVertex(2, centerX, centerY, radius, rotationDegrees, 1, edge);
        hexVertex(3, centerX, centerY, radius, rotationDegrees, 1, edge + 1);
        trace[0] = trace[4] + (trace[6] - trace[4]) * alongEdge;
        trace[1] = trace[5] + (trace[7] - trace[5]) * alongEdge;
    }

    /**
     * Draws "RenGL" letter by letter, then a shimmer across it. {@code top} is the top of the line of text.
     */
    private void drawWordmark(Renderer renderer, float centerX, float top, float scale, float seconds) {
        // The gap between letters is 4.5% of the font size.
        float spacing = wordFont.getSize() * 0.045f;
        float[] widths = new float[WORD.length()];
        float totalWidth = 0f;
        for (int i = 0; i < widths.length; i++) {
            widths[i] = TextShaping.width(wordFont, WORD.substring(i, i + 1));
            // The whole word is every letter plus a gap between each pair (so none after the last).
            totalWidth += widths[i] + (i < widths.length - 1 ? spacing : 0f);
        }
        // Centred: the word starts half its width to the left of the centre.
        float left = centerX - totalWidth / 2f;
        float letterX = left;
        for (int i = 0; i < widths.length; i++) {
            // Each letter starts 0.07 s after the one before it and takes 0.45 s to arrive.
            float letterProgress = ease(progress(seconds, 0.95f + 0.07f * i, 0.45f));
            if (letterProgress > 0f) {
                renderer.pushAlpha(letterProgress);
                // A letter rises 22 units into place as it fades in.
                renderer.drawText(WORD.substring(i, i + 1), letterX, top + (1f - letterProgress) * 22f * scale, wordFont, letterPaint(i));
                renderer.popAlpha();
            }
            letterX += widths[i] + spacing;
        }

        // Shimmer: a bright band crosses the word, drawn as a soft stack of clipped white copies.
        float sweep = progress(seconds, 1.7f, 0.55f);
        if (sweep > 0f && sweep < 1f) {
            // The band's centre travels from 80 units left of the word to 80 units right of it (the word's width plus 160).
            float bandX = left - 80f * scale + (totalWidth + 160f * scale) * ease(sweep);
            float bandHeight = wordFont.getSize() * 1.3f;
            // Three nested clips, wide and faint to narrow and bright; where they overlap their alphas add up, so the
            // band is brightest in the middle and soft at the edges.
            float[] halfWidth = {46f * scale, 28f * scale, 12f * scale};
            float[] alpha = {0.16f, 0.22f, 0.30f};
            for (int band = 0; band < 3; band++) {
                renderer.pushClip(bandX - halfWidth[band], top - 4f * scale, halfWidth[band] * 2, bandHeight);
                renderer.pushAlpha(alpha[band]);
                float copyX = left;
                for (int i = 0; i < widths.length; i++) {
                    renderer.drawText(WORD.substring(i, i + 1), copyX, top, wordFont, WHITE);
                    copyX += widths[i] + spacing;
                }
                renderer.popAlpha();
                renderer.popClip();
            }
        }
    }

    /**
     * "Ren" is near white; "GL" carries the brand gradient.
     */
    private static Paint letterPaint(int index) {
        return switch (index) {
            case 3 -> new LinearGradient(List.of(CYAN, VIOLET), 0f);
            case 4 -> new LinearGradient(List.of(VIOLET, PINK), 0f);
            default -> WHITE;
        };
    }

    private static void drawStars(Renderer renderer, int width, int height, float scale, float seconds) {
        float[] positionX = new float[STARS], positionY = new float[STARS];
        // Two stars closer than this are joined by a line.
        float linkDistance = 120f * scale;
        for (int i = 0; i < STARS; i++) {
            // Start + velocity * time, wrapped into 0..1 (adding 1 and taking % 1 again fixes a negative remainder), then
            // multiplied up from a fraction of the window to pixels.
            positionX[i] = (((starX[i] + starVelocityX[i] * seconds) % 1f + 1f) % 1f) * width;
            positionY[i] = (((starY[i] + starVelocityY[i] * seconds) % 1f + 1f) % 1f) * height;
        }
        for (int first = 0; first < STARS; first++) {
            for (int second = first + 1; second < STARS; second++) {
                float deltaX = positionX[first] - positionX[second];
                float deltaY = positionY[first] - positionY[second];
                // Pythagoras, left squared so the square root is only taken for stars that are close enough.
                float distanceSquared = deltaX * deltaX + deltaY * deltaY;
                if (distanceSquared < linkDistance * linkDistance) {
                    // The line is 16% opaque when the stars touch and fades linearly to nothing at the link distance.
                    float lineAlpha = (1f - (float) Math.sqrt(distanceSquared) / linkDistance) * 0.16f;
                    renderer.drawLine(positionX[first], positionY[first], positionX[second], positionY[second],
                            new Color(0.55f, 0.65f, 1f, lineAlpha), 1f);
                }
            }
        }
        for (int i = 0; i < STARS; i++) {
            // A sine wave (1.6 radians per second, offset by the star's own phase) remapped from -1..1 to 0..1, then to
            // 0.35..1 so a star never disappears completely.
            float twinkle = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(seconds * 1.6f + starPhase[i]));
            renderer.drawCircle(positionX[i], positionY[i], starSize[i] * scale,
                    new Color(STAR_COLOR.getR(), STAR_COLOR.getG(), STAR_COLOR.getB(), 0.55f * twinkle));
        }
    }

    /**
     * A soft pool of colour: one pre-rendered radial falloff, drawn tinted. Stacked translucent circles band in 8 bits.
     */
    private void glow(Renderer renderer, float centerX, float centerY, float radius, Color color, float strength) {
        if (strength <= 0.001f || radius <= 0.5f) return;
        if (glowImage == null) glowImage = makeGlowImage();
        // The image is a square twice the radius wide, centred on the point.
        renderer.drawImage(glowImage, centerX - radius, centerY - radius, radius * 2, radius * 2, 0f, Math.clamp(strength, 0f, 1f),
                new Color(color.getR(), color.getG(), color.getB(), 1f));
    }

    /**
     * White that fades with distance from the centre, as premultiplied pixels with a little noise so 8 bits don't band.
     */
    private static Image makeGlowImage() {
        final int size = 256;
        byte[] pixels = new byte[size * size * 4];
        Random noise = new Random(1);
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                // Distance from the pixel's centre to the image's centre, as a fraction of the half size: 0 in the middle,
                // 1 at the middle of an edge.
                float distance = (float) Math.hypot(column + 0.5f - size / 2f, row + 0.5f - size / 2f) / (size / 2f);
                // Closeness to the centre: 1 in the middle, 0 at distance 1 and beyond.
                float closeness = Math.max(0f, 1f - distance);
                // closeness cubed gives a long soft tail; the last factor (0.4 to 1) keeps the middle from looking pointed.
                float opacity = closeness * closeness * closeness * (closeness * 0.6f + 0.4f);
                // Up to half a step of random noise before rounding to 0..255 breaks the 8-bit steps into grain.
                int value = Math.clamp(Math.round(opacity * 255f + noise.nextFloat() - 0.5f), 0, 255);
                int offset = (row * size + column) * 4; // four bytes per pixel, rows one after the other
                // Premultiplied white: every channel, alpha included, is the same value.
                pixels[offset] = pixels[offset + 1] = pixels[offset + 2] = pixels[offset + 3] = (byte) value;
            }
        }
        return Image.Companion.makeRaster(ImageInfo.Companion.makeN32Premul(size, size), pixels, size * 4);
    }

    private void buildFonts(float scale) {
        // Rebuild only when the scale moves by at least one hundredth.
        int key = Math.round(scale * 100f);
        if (key == fontKey && wordFont != null) return;
        closeFonts();
        Typeface face = Font.systemDefault();
        if (face == null) face = Font.getDefault();
        wordFont = new org.jetbrains.skia.Font(face, 60f * scale);
        wordFont.setEmboldened(true);
        markFont = new org.jetbrains.skia.Font(face, 72f * scale);
        markFont.setEmboldened(true);
        tagFont = new org.jetbrains.skia.Font(face, 15f * scale);
        smallFont = new org.jetbrains.skia.Font(face, 10.5f * scale);
        fontKey = key;
    }

    /**
     * How far {@code seconds} is through a stretch of time that starts at {@code start} and lasts {@code duration}:
     * 0 before it, 1 after it, a straight line between.
     */
    private static float progress(float seconds, float start, float duration) {
        return Math.clamp((seconds - start) / duration, 0f, 1f);
    }

    /**
     * Smoothstep, 3x^2 - 2x^3: 0 to 1 with a slow start and a slow end.
     */
    private static float ease(float progress) {
        return progress * progress * (3f - 2f * progress);
    }

    /**
     * 1 - (1 - x)^3: 0 to 1, fast at first and slowing to a stop.
     */
    private static float easeOut(float progress) {
        float remaining = 1f - progress;
        return 1f - remaining * remaining * remaining;
    }

    /**
     * The standard "back" easing: 0 to 1, but it overshoots to about 1.1 before settling. With c1 = 1.70158 (the usual
     * overshoot constant) and c3 = c1 + 1 the curve is 1 + c3 * (x - 1)^3 + c1 * (x - 1)^2.
     */
    private static float easeOutBack(float progress) {
        float overshoot = 1.70158f;
        float cubicWeight = overshoot + 1f;
        float shifted = progress - 1f;
        return 1f + cubicWeight * shifted * shifted * shifted + overshoot * shifted * shifted;
    }
}
