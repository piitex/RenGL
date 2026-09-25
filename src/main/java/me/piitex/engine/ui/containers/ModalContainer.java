package me.piitex.engine.ui.containers;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;

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
 * Only one modal is shown at a time: showing a second closes the first. A dropdown
 * opened from inside the modal still works, since its popup layers above the modal.
 */
public class ModalContainer extends Container {
    private final Container content;
    private Color backdropColor = new Color(0f, 0f, 0f, 0.55f);
    private boolean dismissOnBackdropClick = true;
    private boolean dismissOnEscape = true;
    private float fadeSeconds = 0.15f;
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
        // Transparent: the backdrop is drawn in render(); Element's default is opaque white.
        getStyling().setBackgroundColor(Color.TRANSPARENT);
        getStyling().setHoverColor(Color.TRANSPARENT);
        getStyling().setBorderThickness(0f);
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

    public Color getBackdropColor() {
        return backdropColor;
    }

    /**
     * The color drawn over the whole window behind the panel. Default 55% black.
     */
    public void setBackdropColor(Color backdropColor) {
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
        return window != null && window.getModal() == this;
    }

    /**
     * Shows this modal over everything in {@code window}.
     */
    public void show(Window window) {
        window.showModal(this);
        lastCenterX = lastCenterY = Double.NaN;
        fitToWindow(window);
        if (fadeSeconds > 0f) fadeIn(fadeSeconds);
    }

    /**
     * Closes this modal if it's showing.
     */
    public void close() {
        Window window = getWindow();
        if (window != null && window.getModal() == this) {
            window.closeModal();
        }
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
        super.update(delta);
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        if (area == null) renderer.drawQuad((float) getX(), (float) getY(), (float) getWidth(), (float) getHeight(), backdropColor, 0f);
        else renderer.drawQuad((float) area[0], (float) area[1], (float) area[2], (float) area[3], backdropColor, 0f);
        renderChildren(renderer);
    }
}
