package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.jetbrains.skia.FontMetrics;
import org.jetbrains.skia.Typeface;

import java.io.File;

/**
 * A single line of text. x and y give the top-left corner, matching every other
 * Element; the baseline offset Skia's drawString() needs is handled inside the renderer
 * rather than here.
 * <p>
 * The background and border are transparent and zero-thickness by default, where Element
 * defaults them to opaque white, since text overlays usually sit on top of something
 * else rather than behind their own filled box. Use getStyling() as usual to add a
 * background behind the text.
 */
@GeneratedClass(
        prompter = "piitex",
        model = "Opus 4.5",
        reviewed = false
)
public class TextOverlay extends Overlay implements Themeable, FontScalable {
    private String text;
    private float fontSize;
    private Overridable<Paint> textColor;
    private Typeface typeface;

    private org.jetbrains.skia.Font skiaFont;
    private boolean fontDirty = true;
    private boolean fixedWidth;
    private boolean sizing;

    /**
     * Text with a 16pt black {@link Font#getDefault() default} font.
     */
    public TextOverlay(String text) {
        this(text, 16f, Color.BLACK);
    }

    /**
     * Text using {@link Font#getDefault() the default font} at the given size and color.
     *
     * @param textColor a plain {@link Color} for a solid fill, or a
     *                  {@link me.piitex.engine.ui.color.LinearGradient}/
     *                  {@link me.piitex.engine.ui.color.ScrollingGradient} for a
     *                  gradient/animated fill.
     */
    public TextOverlay(String text, float fontSize, Paint textColor) {
        this.text = text;
        this.fontSize = fontSize;
        this.textColor = new Overridable<>(textColor);

        // Attempt to load system default, but don't crash if it fails
        this.typeface = Font.getDefault();

        // Seeded as a baseline, not pinned. ButtonOverlay reuses this constructor
        // through super(...) and then themes its own Styling, which a pinned value here
        // would block. See the class docs on applyThemeXxx and Overridable.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        updateComputedSize();
        ThemeManager.register(this);
    }

    // New constructor to bypass system default

    /**
     * Text using a custom font file loaded (and cached, via {@link Font}) from
     * disk, instead of the system default.
     *
     * @param textColor a plain {@link Color} for a solid fill, or a
     *                  {@link me.piitex.engine.ui.color.LinearGradient}/
     *                  {@link me.piitex.engine.ui.color.ScrollingGradient} for a
     *                  gradient/animated fill.
     */
    public TextOverlay(String text, File fontFile, float fontSize, Paint textColor) {
        this.text = text;
        this.fontSize = fontSize;
        this.textColor = new Overridable<>(textColor);
        this.typeface = Font.load(fontFile);

        // See the other constructor for why these are seeded, not pinned.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        updateComputedSize();
        ThemeManager.register(this);
    }

    /**
     * Use a custom font file instead of the system default. Cached by path, so it is cheap to call repeatedly with the same file.
     */
    public void setFontFile(File fontFile) {
        this.typeface = Font.load(fontFile);
        fontDirty = true;
        updateComputedSize();
    }

    public void setFontFile(String fontFilePath) {
        setFontFile(new File(fontFilePath));
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
        // No fontDirty here: changing the string does not invalidate the Font object.
        // Only its size or typeface do.
        updateComputedSize();
    }

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
        fontDirty = true;
        updateComputedSize();
    }

    @Override
    public float[] getFontSizes() {
        return new float[]{fontSize};
    }

    @Override
    public void setFontSizes(float[] sizes) {
        setFontSize(sizes[0]);
    }

    /**
     * The current text {@link Paint}: a plain {@link Color}, a gradient, or an animated gradient.
     */
    public Paint getTextColor() {
        return textColor.get();
    }

    /** Pins this text color against every future theme switch. See {@link Overridable}. */
    public void setTextColor(Paint textColor) {
        this.textColor.set(textColor);
    }

    /**
     * Recomputes this overlay's width/height (see {@link #getWidth()}/{@link
     * #getHeight()}) to match what {@link #getText()} will actually measure out to at
     * the current font and size. It is called automatically whenever any of those
     * change.
     * <p>
     * Without it, a TextOverlay's width and height stay at Element's 0x0 default, since
     * nothing else sets them. That is harmless for a bare TextOverlay drawn directly
     * onto a Container, but it breaks anything that reads an Element's size to do its
     * own layout. A {@link me.piitex.engine.ui.layout.Layout} would size its cursor
     * advance and spacing off a zero-height child, leaving a large heading crowded by
     * the next child. Hit-testing depends on it too: a 0x0 Element can never contain a
     * click or hover point, so a bare TextOverlay could not be hovered or clicked.
     * <p>
     * {@link ButtonOverlay} overrides this to a no-op, since its box size is set
     * explicitly at construction and should not auto-resize to fit its label the way a
     * plain TextOverlay does.
     */
    protected void updateComputedSize() {
        sizing = true;
        try {
            if (text == null || text.isEmpty()) {
                if (!fixedWidth) setWidth(0);
                setHeight(0);
                return;
            }

            org.jetbrains.skia.Font font = getOrBuildFont();
            FontMetrics metrics = font.getMetrics();
            if (!fixedWidth) setWidth(font.measureTextWidth(text));
            setHeight(-metrics.getAscent() + metrics.getDescent());
        } finally {
            sizing = false;
        }
    }

    /**
     * Setting a width wider than the text gives {@link #setTextAlignment} room to work: the text is then placed
     * inside that box instead of the box shrinking back to the text. A width of 0 or less returns to sizing
     * automatically to the text.
     */
    @Override
    public void setWidth(double width) {
        super.setWidth(width);
        if (!sizing) {
            fixedWidth = width > 0;
            if (!fixedWidth) updateComputedSize();
        }
    }

    /**
     * Builds the Font lazily and only when size/typeface actually changed. Protected so a subclass (e.g. {@link ButtonOverlay}) can measure/draw text itself without rebuilding its own Font from the same Typeface.
     */
    protected org.jetbrains.skia.Font getOrBuildFont() {
        if (fontDirty || skiaFont == null) {
            if (skiaFont != null) {
                skiaFont.close(); // native resource, so release the old one
            }
            skiaFont = new org.jetbrains.skia.Font(typeface, fontSize);
            fontDirty = false;
        }
        return skiaFont;
    }

    /**
     * Draws this overlay's background/border (transparent by default, see class docs)
     * via {@code super.render(...)}, then draws the text itself on top via {@link
     * #drawText}. A null or empty {@link #getText()} skips the text draw entirely,
     * leaving only whatever background/border/onRender-hook drawing {@code
     * super.render(...)} produced.
     */
    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;

        // Background/border (transparent by default) + fires the user's onRender hook.
        super.render(renderer);
        drawText(renderer);
    }

    /**
     * Draws {@link #getText()} at this overlay's own (x, y), the top-left corner, the
     * same as every other Element. A subclass that wants the text positioned
     * differently, such as {@link ButtonOverlay} centering it within a box, overrides
     * this rather than {@link #render}, so it keeps the background, border, and
     * onRender-hook sequence from {@code super.render(...)} instead of reimplementing
     * it.
     */
    protected void drawText(Renderer renderer) {
        if (text != null && !text.isEmpty()) {
            org.jetbrains.skia.Font font = getOrBuildFont();
            float x = (float) getX() + Math.max(0f, (float) getWidth() - font.measureTextWidth(text)) * textAlignH(0f);
            float y = (float) getY();
            if (getTextAlignment() != null) {
                FontMetrics m = font.getMetrics();
                y += Math.max(0f, (float) getHeight() - (-m.getAscent() + m.getDescent())) * textAlignV(0f);
            }
            renderer.drawText(text, x, y, font, resolveTextColor(textColor.get()));
        }
    }

    /**
     * Advances the base Element's Styling paints via {@code super.update(...)}, then
     * additionally advances {@link #getTextColor()} if it is an {@link AnimatedPaint}.
     * textColor lives outside {@link me.piitex.engine.ui.color.Styling}, so the base
     * class cannot tick it on its own.
     */
    @Override
    public void update(float delta) {
        super.update(delta); // advances Styling's background/border/hover paints
        if (textColor.get() instanceof AnimatedPaint animated) {
            animated.advance(delta);
        }
    }


    /**
     * Releases the native Font this overlay owns. It does not close the underlying
     * Typeface, which is shared through FontCache and may still be in use elsewhere.
     * Call this when permanently discarding the TextOverlay, such as when removing it
     * from its Container for good.
     */
    public void dispose() {
        if (skiaFont != null) {
            skiaFont.close();
            skiaFont = null;
        }
    }

    /** Themes {@link #getTextColor()} as a baseline. This is a no-op once {@link #setTextColor} has pinned it. See {@link Overridable}. */
    @Override
    public void applyTheme(Theme theme) {
        textColor.applyTheme(theme.getText());
    }
}