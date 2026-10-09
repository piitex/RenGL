package me.piitex.engine.ui.containers;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.animation.Easing;
import me.piitex.engine.ui.animation.ScaleTransition;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.theme.Theme;

/**
 * A dialog: a dimmed full-window backdrop (or just part of the window, see {@link #setArea}) with a {@link #getContent() content} panel
 * centered on it, drawn above every other Container. While shown it blocks the rest of
 * the UI: clicks, hover, scrolling, and Tab traversal all stay inside the modal, since
 * the backdrop covers the whole window and {@link Window#showModal} restricts focus to
 * it. It is shown with {@link #show(Window)} and dismissed with {@link #close()}, a
 * backdrop click ({@link #setDismissOnBackdropClick}), or Escape
 * ({@link #setDismissOnEscape}).
 * <p>
 * The content panel is an ordinary {@link Container}: either the one built by {@link
 * #ModalContainer(double, double)} (themed via {@link Container#useTheme()}), or any
 * Container/{@link me.piitex.engine.ui.layout.Layout} you pass to {@link
 * #ModalContainer(Container)}, where a VerticalLayout is usually the easiest way to lay
 * out a dialog. Children of a panel built by hand are positioned relative to the
 * panel's origin as it stands when they are added, which is (0, 0) unless the panel has
 * been moved. The modal re-centers the panel, children included, every frame, so it
 * stays centered through window resizes. If the panel is draggable ({@link
 * Container#setDraggable}), it keeps the offset it is dragged to from the centre.
 * <p>
 * Modals stack: showing a second one while the first is open puts it on top, and it alone
 * receives the keyboard, Tab traversal and Escape. Closing it reveals the first again, still
 * as it was left. {@link Window#getModal()} is the topmost. A dropdown opened from inside a
 * modal still works, since its popup layers above every modal.
 */
public class ModalContainer extends Container {
    private final Container content;
    private Paint backdropColor = new Color(0f, 0f, 0f, 0.55f);
    private boolean dismissOnBackdropClick = true;
    private boolean dismissOnEscape = true;
    private float fadeSeconds = 0.15f;
    // How it animates on close, null closes instantly.
    private CloseAnimation closeAnimation;
    // True while the fade-out is running, so a second close doesn't restart it.
    private boolean closing = false;
    private Runnable onClose;
    // Where the panel was centered last frame, so an offset the user dragged it to can be told from the centre.
    private double lastCenterX = Double.NaN, lastCenterY = Double.NaN;
    // The part of the window the modal covers, x, y, width, height. Null is all of it.
    private double[] area;

    /**
     * A modal whose content is a new themed {@code width} by {@code height} panel. Add children through {@link #getContent()}.
     */
    public ModalContainer(double width, double height) {
        this(new Container(width, height));
        content.useTheme();
    }

    /**
     * A modal presenting {@code content} (e.g. a {@link me.piitex.engine.ui.layout.VerticalLayout}) as its panel. Call {@code content.useTheme()} yourself if you want the themed panel look.
     */
    public ModalContainer(Container content) {
        super(0, 0);
        this.content = content;
        // Transparent, and kept so by applyTheme below: the backdrop is drawn in render() instead.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        addElement(content);
        // Presses on the panel or anything in it reach this listener too, since a click goes up to the nearest
        // listener above what was hit (see InputProcessor#handleMouseButton). Only the backdrop dismisses.
        onMouseClick(event -> {
            if (dismissOnBackdropClick && !this.content.contains(event.getMouseX(), event.getMouseY())) close();
        });
    }

    /**
     * The centered panel.
     */
    public Container getContent() {
        return content;
    }

    public Paint getBackdropColor() {
        return backdropColor;
    }

    /**
     * The paint drawn over the whole window behind the panel: a color, or a gradient for a vignette. Default 55% black.
     */
    public void setBackdropColor(Paint backdropColor) {
        this.backdropColor = backdropColor;
    }

    public boolean isDismissOnBackdropClick() {
        return dismissOnBackdropClick;
    }

    /**
     * Whether clicking outside the panel closes the modal. Default true.
     */
    public void setDismissOnBackdropClick(boolean dismissOnBackdropClick) {
        this.dismissOnBackdropClick = dismissOnBackdropClick;
    }

    public boolean isDismissOnEscape() {
        return dismissOnEscape;
    }

    /**
     * Whether Escape closes the modal. Default true.
     */
    public void setDismissOnEscape(boolean dismissOnEscape) {
        this.dismissOnEscape = dismissOnEscape;
    }

    /**
     * Fade-in duration on show; 0 for none. Default 0.15s.
     */
    public void setFadeSeconds(float fadeSeconds) {
        this.fadeSeconds = Math.max(0f, fadeSeconds);
    }

    public CloseAnimation getCloseAnimation() {
        return closeAnimation;
    }

    /**
     * Plays {@code animation} on {@link #close()}, a backdrop click or Escape before the modal is removed; null closes
     * instantly, the default. The modal keeps blocking the rest of the UI until the animation is done.
     * {@link #closeImmediately()} skips it. See {@link CloseAnimation} for the built in ones.
     */
    public void setCloseAnimation(CloseAnimation animation) {
        this.closeAnimation = animation;
    }

    /**
     * Shorthand for {@code setCloseAnimation(CloseAnimation.fade(seconds))}, or no animation for 0.
     */
    public void setFadeOutSeconds(float seconds) {
        closeAnimation = seconds > 0f ? CloseAnimation.fade(seconds) : null;
    }

    /**
     * Limits the modal to part of the window, in window coordinates. The backdrop is drawn there, the panel is centered
     * there, and only presses inside it are blocked, so the rest of the window (a sidebar, say) stays usable. Set it
     * before {@link #show(Window)} and again whenever the layout changes.
     */
    public void setArea(double x, double y, double width, double height) {
        area = new double[]{x, y, width, height};
    }

    /**
     * Back to covering the whole window, the default.
     */
    public void clearArea() {
        area = null;
    }

    @Override
    public boolean contains(double px, double py) {
        if (area == null) return super.contains(px, py);
        return px >= area[0] && px <= area[0] + area[2] && py >= area[1] && py <= area[1] + area[3];
    }

    /**
     * Runs whenever the modal closes, by any route.
     */
    public void onClose(Runnable onClose) {
        this.onClose = onClose;
    }

    public boolean isShowing() {
        Window window = getWindow();
        return window != null && window.isShowingModal(this);
    }

    /**
     * Shows this modal over everything in {@code window}.
     */
    public void show(Window window) {
        window.showModal(this);
        closing = false;
        // Put it back the way it started, in case a close animation ran on an earlier show.
        setOpacity(1f);
        content.setScale(1f);
        content.clearScaleOrigin();
        lastCenterX = lastCenterY = Double.NaN;
        fitToWindow(window);
        if (fadeSeconds > 0f) fadeIn(fadeSeconds);
    }

    /**
     * Closes this modal if it's showing, playing its {@link #setCloseAnimation close animation} first if it has one.
     */
    public void close() {
        if (closeAnimation == null) {
            closeImmediately();
            return;
        }
        if (closing || !isShowing()) return;
        closing = true;
        closeAnimation.play(this, this::closeImmediately);
    }

    /**
     * Closes this modal right away if it's showing, with no fade-out.
     */
    public void closeImmediately() {
        Window window = getWindow();
        if (window != null) window.closeModal(this);
    }

    /**
     * Called by {@link Window#closeModal()} once the modal has been removed. Not meant to be called directly.
     */
    public void notifyClosed() {
        if (onClose != null) onClose.run();
    }

    private void fitToWindow(Window window) {
        double w = window.getWindowOptions().getWidth();
        double h = window.getWindowOptions().getHeight();
        setBounds(0, 0, w, h);
        double areaX = area == null ? 0 : area[0], areaY = area == null ? 0 : area[1];
        double centerX = areaX + ((area == null ? w : area[2]) - content.getWidth()) / 2.0;
        double centerY = areaY + ((area == null ? h : area[3]) - content.getHeight()) / 2.0;
        // A panel made draggable keeps the offset it was dragged to, through resizes; otherwise the offset is 0.
        double offsetX = Double.isNaN(lastCenterX) ? 0 : content.getX() - lastCenterX;
        double offsetY = Double.isNaN(lastCenterY) ? 0 : content.getY() - lastCenterY;
        lastCenterX = centerX;
        lastCenterY = centerY;
        content.moveTo(centerX + offsetX, centerY + offsetY);
    }

    @Override
    public void update(float delta) {
        Window window = getWindow();
        if (window != null) fitToWindow(window);
        AnimatedPaint.advanceIfAnimated(backdropColor, delta);
        super.update(delta);
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        if (area == null) renderer.drawQuad((float) getX(), (float) getY(), (float) getWidth(), (float) getHeight(), backdropColor, 0f);
        else renderer.drawQuad((float) area[0], (float) area[1], (float) area[2], (float) area[3], backdropColor, 0f);
        renderChildren(renderer);
    }

    /**
     * What plays when a modal closes. It has to call {@code onFinished} once it's done, which removes the modal.
     * Anything can go in here, an animation on the modal or on its {@link #getContent() panel}, so a custom one is
     * just a lambda. {@link #fade} and {@link #shrink} are ready made.
     */
    @FunctionalInterface
    public interface CloseAnimation {
        void play(ModalContainer modal, Runnable onFinished);

        /**
         * The whole modal, backdrop included, fades out.
         */
        static CloseAnimation fade(float seconds) {
            return (modal, onFinished) -> modal.fadeOut(seconds, onFinished);
        }

        /**
         * The panel shrinks to {@code endScale} times its size toward the point ({@code targetX}, {@code targetY}) in the
         * window while everything fades out, like a folder closing back into its icon on macOS or GNOME.
         */
        static CloseAnimation shrink(float seconds, double targetX, double targetY, float endScale) {
            return (modal, onFinished) -> {
                Container panel = modal.getContent();
                panel.setScaleOrigin(targetX, targetY);
                panel.playTransition(new ScaleTransition(1f, endScale, seconds, Easing.EASE_IN_OUT, null));
                modal.fadeOut(seconds, onFinished);
            };
        }
    }

    /**
     * Leaves the box alone so the modal stays transparent, even after {@link #useTheme()}.
     */
    @Override
    public void applyTheme(Theme theme) {
    }
}
