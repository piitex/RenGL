package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;

import java.util.function.BiConsumer;

/**
 * An on-screen thumb stick, for a touch screen or a mouse-driven game that wants to steer with a stick: press it, drag, and
 * read how far and which way the thumb is pushed.
 * <pre>{@code
 * JoystickOverlay stick = new JoystickOverlay(140);
 * stick.setDeadZone(0.15);
 * hud.addElement(stick);
 *
 * // every frame, in the game loop
 * player.move(stick.getAxisX(), stick.getAxisY());
 * }</pre>
 * Like a gamepad stick it is read by polling: {@link #getAxisX()} and {@link #getAxisY()} are each between -1 and 1, with
 * <b>y positive up</b> (pushing the thumb up gives +1), whatever way the screen's y grows. {@link #getMagnitude()} is the
 * push as a whole, 0 to 1, and {@link #getAngle()} the direction in degrees, 0 pointing right and 90 up. A push inside
 * the {@linkplain #setDeadZone dead zone} reads as 0, and the range outside it is stretched so it starts at 0 right at its
 * edge, not with a jump. {@link #onMove} reports changes instead, for code that does not poll.
 * <p>
 * It returns to the center when released ({@link #setSnapBack}) unless that is turned off, so it can hold a setting. {@link
 * #setDirections} turns it into a digital pad that reports only 4 or 8 directions at full push.
 * <p>
 * Colors come from the theme: the base from {@link Theme#getFieldBackground()} and {@link Theme#getFieldBorder()}, the
 * thumb from {@link Theme#getAccent()}.
 */
public class JoystickOverlay extends Overlay implements Themeable, Draggable {
    private double thumbRatio = 0.4; // thumb radius as a fraction of the base's
    private double deadZone = 0.1;
    private int directions; // 0 is analog
    private boolean snapBack = true;

    // Where the thumb is, as a fraction of its travel, in screen orientation (y down), length at most 1.
    private double rawX;
    private double rawY;
    private boolean dragging;

    private final Overridable<Paint> baseColor = new Overridable<>(new Color(0f, 0f, 0f, 0.25f));
    private final Overridable<Paint> ringColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> thumbColor = new Overridable<>(Color.GRAY);

    private BiConsumer<Double, Double> onMove;
    private Runnable onRelease;

    /**
     * A stick whose base is {@code diameter} points across.
     */
    public JoystickOverlay(double diameter) {
        setWidth(diameter);
        setHeight(diameter);
        // The base is drawn by hand as a circle; Element's square box would show around it.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        ThemeManager.register(this);
    }

    /* Reading */

    /**
     * Left and right, from -1 (left) to 1 (right), after the dead zone and the {@link #setDirections direction snapping}.
     */
    public double getAxisX() {
        return processed()[0];
    }

    /**
     * Up and down, from -1 (down) to 1 (up): <b>positive is up</b>, as the stick of a gamepad reads, not as the screen's y.
     */
    public double getAxisY() {
        return 0.0 - processed()[1]; // not a negation, which would turn a resting 0 into -0.0
    }

    /**
     * How hard the stick is pushed, 0 to 1.
     */
    public double getMagnitude() {
        double[] p = processed();
        return Math.min(1.0, Math.hypot(p[0], p[1]));
    }

    /**
     * Which way the stick is pushed, in degrees from 0 (right) counter-clockwise through 90 (up) to 359; 0 when the stick is
     * at rest.
     */
    public double getAngle() {
        double[] p = processed();
        if (p[0] == 0 && p[1] == 0) return 0;
        double deg = Math.toDegrees(Math.atan2(-p[1], p[0]));
        return deg < 0 ? deg + 360 : deg;
    }

    /**
     * Whether the thumb is being pushed past the dead zone.
     */
    public boolean isActive() {
        return getMagnitude() > 0;
    }

    public boolean isDragging() {
        return dragging;
    }

    /**
     * The push after the dead zone and direction snapping, as {x, y} in screen orientation (y down).
     */
    private double[] processed() {
        double mag = Math.hypot(rawX, rawY);
        if (mag <= deadZone || mag == 0) return new double[]{0, 0};
        double angle = Math.atan2(rawY, rawX);
        double out = Math.min(1.0, (mag - deadZone) / (1.0 - deadZone));
        if (directions > 0) {
            double slice = 2 * Math.PI / directions;
            angle = Math.round(angle / slice) * slice;
            out = 1.0;
        }
        double x = Math.cos(angle) * out, y = Math.sin(angle) * out;
        // Rounding noise: a snapped direction like straight up should be exactly 0 on the other axis.
        if (Math.abs(x) < 1e-9) x = 0;
        if (Math.abs(y) < 1e-9) y = 0;
        return new double[]{x, y};
    }

    /* Settings */

    public double getDeadZone() {
        return deadZone;
    }

    /**
     * The push, from 0 to 0.9, below which the stick reads as at rest; 0.1 by default.
     */
    public void setDeadZone(double deadZone) {
        this.deadZone = Math.max(0, Math.min(0.9, deadZone));
    }

    public int getDirections() {
        return directions;
    }

    /**
     * 0, the default, is analog. 4 or 8 makes the stick digital: it reports only that many directions, at full push (4 is
     * up, down, left and right; 8 adds the diagonals). Any other number is taken as that many evenly spaced directions.
     */
    public void setDirections(int directions) {
        this.directions = Math.max(0, directions);
    }

    public boolean isSnapBack() {
        return snapBack;
    }

    /**
     * Whether the thumb springs back to the center when released. On by default; off, the stick stays where it was left.
     */
    public void setSnapBack(boolean snapBack) {
        this.snapBack = snapBack;
    }

    public double getThumbRatio() {
        return thumbRatio;
    }

    /**
     * The thumb's radius as a fraction of the base's, 0.4 by default. The thumb travels as far as it can without leaving the
     * base.
     */
    public void setThumbRatio(double thumbRatio) {
        this.thumbRatio = Math.max(0.1, Math.min(0.9, thumbRatio));
    }

    /**
     * Puts the thumb where an axis pair says, each from -1 to 1 with y up, clamped to the base. Silent. For showing a value
     * that came from somewhere else, such as a gamepad.
     */
    public void setAxes(double x, double y) {
        rawX = x;
        rawY = -y;
        clampRaw();
    }

    /**
     * Fired with {@code (x, y)}, as {@link #getAxisX()} and {@link #getAxisY()} read, whenever the reading changes by
     * dragging or on release.
     */
    public void onMove(BiConsumer<Double, Double> onMove) {
        this.onMove = onMove;
    }

    /**
     * Fired when the press ends, after {@link #onMove} reported the stick back at rest (if it snaps back).
     */
    public void onRelease(Runnable onRelease) {
        this.onRelease = onRelease;
    }

    /* Colors */

    public Paint getBaseColor() {
        return baseColor.get();
    }

    public void setBaseColor(Paint baseColor) {
        this.baseColor.set(baseColor);
    }

    public Paint getRingColor() {
        return ringColor.get();
    }

    public void setRingColor(Paint ringColor) {
        this.ringColor.set(ringColor);
    }

    public Paint getThumbColor() {
        return thumbColor.get();
    }

    public void setThumbColor(Paint thumbColor) {
        this.thumbColor.set(thumbColor);
    }

    /* Interaction */

    @Override
    public void onDragStart(double x, double y) {
        dragging = true;
        follow(x, y);
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (dragging) follow(x, y);
    }

    @Override
    public void onDragEnd(double x, double y) {
        if (!dragging) return;
        dragging = false;
        if (snapBack) {
            double beforeX = getAxisX(), beforeY = getAxisY();
            rawX = 0;
            rawY = 0;
            if ((beforeX != 0 || beforeY != 0) && onMove != null) onMove.accept(0.0, 0.0);
        }
        if (onRelease != null) onRelease.run();
    }

    private void follow(double windowX, double windowY) {
        double radius = travel();
        double dx = toLocalX(windowX) - (getX() + getWidth() / 2.0);
        double dy = toLocalY(windowY) - (getY() + getHeight() / 2.0);
        double beforeX = getAxisX(), beforeY = getAxisY();
        rawX = radius <= 0 ? 0 : dx / radius;
        rawY = radius <= 0 ? 0 : dy / radius;
        clampRaw();
        double afterX = getAxisX(), afterY = getAxisY();
        if ((afterX != beforeX || afterY != beforeY) && onMove != null) onMove.accept(afterX, afterY);
    }

    private void clampRaw() {
        double len = Math.hypot(rawX, rawY);
        if (len > 1.0) {
            rawX /= len;
            rawY /= len;
        }
    }

    /**
     * How far the thumb's center can move from the base's: the base's radius less the thumb's.
     */
    private double travel() {
        double baseR = Math.min(getWidth(), getHeight()) / 2.0;
        return baseR * (1.0 - thumbRatio);
    }

    /* Theme */

    @Override
    public void applyTheme(Theme theme) {
        baseColor.applyTheme(theme.getFieldBackground().withAlpha(0.55f));
        ringColor.applyTheme(theme.getFieldBorder());
        thumbColor.applyTheme(theme.getAccent());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        baseColor.clearExplicit();
        ringColor.clearExplicit();
        thumbColor.clearExplicit();
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        AnimatedPaint.advanceIfAnimated(baseColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(ringColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(thumbColor.get(), delta);
    }

    /* Rendering */

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);
        float cx = (float) (getX() + getWidth() / 2.0), cy = (float) (getY() + getHeight() / 2.0);
        float baseR = (float) (Math.min(getWidth(), getHeight()) / 2.0);
        float thumbR = (float) (baseR * thumbRatio);
        renderer.drawCircle(cx, cy, baseR, baseColor.get());
        renderer.drawBorder(cx - baseR, cy - baseR, baseR * 2f, baseR * 2f, ringColor.get(), 2f, baseR);
        if (directions > 0) {
            // Tick the directions on the rim so a digital pad shows what it can say.
            for (int i = 0; i < directions; i++) {
                double a = 2 * Math.PI * i / directions;
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                renderer.drawLine(cx + cos * (baseR - 8f), cy + sin * (baseR - 8f), cx + cos * (baseR - 3f), cy + sin * (baseR - 3f),
                        ringColor.get(), 2f);
            }
        }
        float tx = cx + (float) (rawX * travel()), ty = cy + (float) (rawY * travel());
        renderer.drawCircle(tx, ty, thumbR, thumbColor.get());
        if (dragging) renderer.drawBorder(tx - thumbR, ty - thumbR, thumbR * 2f, thumbR * 2f, ringColor.get(), 2f, thumbR);
    }
}
