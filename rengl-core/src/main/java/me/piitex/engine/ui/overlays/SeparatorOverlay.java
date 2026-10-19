package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;

/**
 * A thin dividing line. Draws a single {@link Renderer#drawLine} centered within this
 * Element's bounds rather than filling them
 * (unlike a plain {@link Element#render} background quad), so the line stays a fixed
 * {@link #getThickness()} even if {@code height} (for a {@link Orientation#HORIZONTAL}
 * separator) ends up taller than that, as when giving it the same row height as its
 * neighbors in a {@link me.piitex.engine.ui.layout.VerticalLayout}.
 * <p>
 * The background and border are transparent and zero-thickness by default, as in
 * {@link TextOverlay} and {@link ImageOverlay}, since this overlay is the line itself
 * rather than a filled box behind one.
 */
public class SeparatorOverlay extends Overlay implements Themeable {

    public enum Orientation {
        HORIZONTAL, VERTICAL
    }

    private final Orientation orientation;
    private float thickness = 1f;
    private final Overridable<Paint> lineColor = new Overridable<>(Color.LIGHT_GRAY);

    /**
     * A horizontal separator {@code length} points long, 1pt thick.
     */
    public SeparatorOverlay(double length) {
        this(length, Orientation.HORIZONTAL);
    }

    public SeparatorOverlay(double length, float thickness) {
        this(length, Orientation.HORIZONTAL);
        this.thickness = thickness;
    }


    /**
     * A separator {@code length} points long along its own axis, 1pt thick across it.
     */
    public SeparatorOverlay(double length, Orientation orientation) {
        this.orientation = orientation;
        if (orientation == Orientation.HORIZONTAL) {
            setWidth(length);
            setHeight(thickness);
        } else {
            setWidth(thickness);
            setHeight(length);
        }
        // Seeded as a baseline, not pinned. See Overridable.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        ThemeManager.register(this);
    }

    public Orientation getOrientation() {
        return orientation;
    }

    /**
     * How wide the drawn line itself is, independent of {@code width} and {@code height}. See the class docs.
     */
    public float getThickness() {
        return thickness;
    }

    public void setThickness(float thickness) {
        this.thickness = thickness;
    }

    public Paint getLineColor() {
        return lineColor.get();
    }

    /**
     * Pins the line color against every future theme switch. See {@link Overridable}.
     */
    public void setLineColor(Paint lineColor) {
        this.lineColor.set(lineColor);
    }

    /**
     * Colors the line from {@link Theme#getContainerBorder()}, the same neutral
     * divider tone a themed {@link Container}'s own border
     * already uses, as a baseline. This is a no-op once {@link #setLineColor} has pinned
     * it. See {@link Overridable}.
     */
    @Override
    public void applyTheme(Theme theme) {
        lineColor.applyTheme(theme.getContainerBorder());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        lineColor.clearExplicit();
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        AnimatedPaint.advanceIfAnimated(lineColor.get(), delta);
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;

        // Transparent background/border (see constructor) + fires the user's onRender hook.
        super.render(renderer);

        if (orientation == Orientation.HORIZONTAL) {
            float lineY = (float) (getY() + getHeight() / 2.0);
            renderer.drawLine((float) getX(), lineY, (float) (getX() + getWidth()), lineY, lineColor.get(), thickness);
        } else {
            float lineX = (float) (getX() + getWidth() / 2.0);
            renderer.drawLine(lineX, (float) getY(), lineX, (float) (getY() + getHeight()), lineColor.get(), thickness);
        }
    }
}
