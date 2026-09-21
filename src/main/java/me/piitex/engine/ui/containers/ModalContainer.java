package me.piitex.engine.ui.containers;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.color.Color;

/**
 * A dialog: a dimmed full-window backdrop with a {@link #getContent() content} panel
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
 * stays centered through window resizes.
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

    /** A modal whose content is a new themed {@code width} by {@code height} panel. Add children through {@link #getContent()}. */
    public ModalContainer(double width, double height) {
        this(new Container(width, height));
        content.useTheme();
    }

    /** A modal presenting {@code content} (e.g. a {@link me.piitex.engine.ui.layout.VerticalLayout}) as its panel. Call {@code content.useTheme()} yourself if you want the themed panel look. */
    public ModalContainer(Container content) {
        super(0, 0);
        this.content = content;
        // Transparent: the backdrop is drawn in render(); Element's default is opaque white.
        getStyling().setBackgroundColor(Color.TRANSPARENT);
        getStyling().setHoverColor(Color.TRANSPARENT);
        getStyling().setBorderThickness(0f);
        addElement(content);
        // Only fires for presses on the backdrop itself. A press on the panel or any
        // child hits those instead (see InputProcessor#findTopmostHit).
        onMouseClick(event -> {
            if (dismissOnBackdropClick) close();
        });
    }

    /** The centered panel. */
    public Container getContent() {
        return content;
    }

    public Color getBackdropColor() {
        return backdropColor;
    }

    /** The color drawn over the whole window behind the panel. Default 55% black. */
    public void setBackdropColor(Color backdropColor) {
        this.backdropColor = backdropColor;
    }

    public boolean isDismissOnBackdropClick() {
        return dismissOnBackdropClick;
    }

    /** Whether clicking outside the panel closes the modal. Default true. */
    public void setDismissOnBackdropClick(boolean dismissOnBackdropClick) {
        this.dismissOnBackdropClick = dismissOnBackdropClick;
    }

    public boolean isDismissOnEscape() {
        return dismissOnEscape;
    }

    /** Whether Escape closes the modal. Default true. */
    public void setDismissOnEscape(boolean dismissOnEscape) {
        this.dismissOnEscape = dismissOnEscape;
    }

    /** Fade-in duration on show; 0 for none. Default 0.15s. */
    public void setFadeSeconds(float fadeSeconds) {
        this.fadeSeconds = Math.max(0f, fadeSeconds);
    }

    /** Runs whenever the modal closes, by any route. */
    public void onClose(Runnable onClose) {
        this.onClose = onClose;
    }

    public boolean isShowing() {
        Window window = getWindow();
        return window != null && window.getModal() == this;
    }

    /** Shows this modal over everything in {@code window}. */
    public void show(Window window) {
        window.showModal(this);
        fitToWindow(window);
        if (fadeSeconds > 0f) fadeIn(fadeSeconds);
    }

    /** Closes this modal if it's showing. */
    public void close() {
        Window window = getWindow();
        if (window != null && window.getModal() == this) {
            window.closeModal();
        }
    }

    /** Called by {@link Window#closeModal()} once the modal has been removed. Not meant to be called directly. */
    public void notifyClosed() {
        if (onClose != null) onClose.run();
    }

    private void fitToWindow(Window window) {
        double w = window.getWindowOptions().getWidth();
        double h = window.getWindowOptions().getHeight();
        setBounds(0, 0, w, h);
        content.moveTo((w - content.getWidth()) / 2.0, (h - content.getHeight()) / 2.0);
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
        renderer.drawQuad((float) getX(), (float) getY(), (float) getWidth(), (float) getHeight(), backdropColor, 0f);
        renderChildren(renderer);
    }
}
