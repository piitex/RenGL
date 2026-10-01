package me.piitex.engine.render3d;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.Input;
import me.piitex.engine.ui.background.Background;
import org.lwjgl.glfw.GLFW;

/**
 * 3D that fills the whole window behind the UI. It is a window {@link Background}, so nothing in the core API knows
 * about it:
 * <pre>
 * Scene3D scene = new Scene3D();
 * ...
 * Background3D background = new Background3D(window, scene);
 * window.setBackground(background);
 * </pre>
 * Every container, overlay and popup draws over it, and what it shows is the same {@link Viewport3D.Content} a
 * {@link Viewport3D} takes, so a scene works in either place. It draws exactly like a viewport that covers the window:
 * the content renders into an offscreen target the size of the window in pixels (see {@link #setResolutionScale}) and
 * the UI layer draws that, so it needs a window on OpenGL or Vulkan (on macOS, {@code RenderAPI.VULKAN}).
 * <p>
 * <b>Input.</b> A background has no place in the UI's hit testing, so it is fed from the window's game
 * {@link Input}: the clicks, wheel turns and keys that the interface left alone. A press on a button or a text field
 * never reaches it, and a press on empty space, or on a panel that has no click listener, does. A
 * {@link Viewport3D.InputListener} (set with {@link #setInputListener}, or the content itself if it implements one,
 * as {@code OrbitControls} does) gets:
 * <ul>
 *   <li>a left-button drag, from a press the UI didn't take until the button is released;</li>
 *   <li>the wheel;</li>
 *   <li>the mouse moving ({@link Viewport3D.InputListener#pointerMoved}), which also fires while the mouse is over
 *   the UI and is never followed by {@code pointerExited};</li>
 *   <li>keys the UI didn't take, as press, repeat and release.</li>
 * </ul>
 * There is no focus, and no typed characters, since the background is never the thing a click focuses.
 * <p>
 * Threading: everything here runs on the render thread, like the engine's other backgrounds.
 */
public class Background3D implements Background {
    private static final int FIRST_KEY = GLFW.GLFW_KEY_SPACE;

    private final Window window;
    private final Viewport3D view = new Viewport3D(1, 1);
    private Viewport3D.Content content;
    private Viewport3D.InputListener input;

    private boolean dragging;

    /**
     * @param window the window it is a background of, whose {@link Window#getInput()} it reads. Set it with
     *               {@link Window#setBackground}.
     * @param content what to show, or null to set it later with {@link #setContent}
     */
    public Background3D(Window window, Viewport3D.Content content) {
        this.window = window;
        setContent(content);
    }

    /**
     * What the background shows. Takes effect on the next frame; the previous content is disposed then.
     */
    public void setContent(Viewport3D.Content content) {
        this.content = content;
        view.setContent(content);
    }

    public Viewport3D.Content getContent() {
        return content;
    }

    /**
     * Where the input goes, or null (the default) for the content if it implements
     * {@link Viewport3D.InputListener}, and nobody otherwise.
     */
    public void setInputListener(Viewport3D.InputListener input) {
        this.input = input;
    }

    private Viewport3D.InputListener listener() {
        if (input != null) return input;
        return content instanceof Viewport3D.InputListener l ? l : null;
    }

    /**
     * The picture's resolution relative to the window's size in pixels. 1 (the default) is one rendered pixel per screen
     * pixel; 0.5 renders a quarter as many and lets the UI layer scale it up. Clamped to 0.1 to 4.
     */
    public void setResolutionScale(float resolutionScale) {
        view.setResolutionScale(resolutionScale);
    }

    public float getResolutionScale() {
        return view.getResolutionScale();
    }

    /**
     * Shows or hides the 3D without removing the background. A hidden background does not render, so it costs nothing.
     */
    public void setEnabled(boolean enabled) {
        view.setEnabled(enabled);
    }

    public boolean isEnabled() {
        return view.isEnabled();
    }

    /**
     * The device the content draws with, or null before the first frame.
     */
    public GpuDevice getDevice() {
        return view.getDevice();
    }

    @Override
    public void render(Renderer renderer, float x, float y, float width, float height) {
        view.setPosition(x, y);
        view.setWidth(width);
        view.setHeight(height);
        view.render(renderer);
    }

    @Override
    public void update(float delta, float x, float y, float width, float height) {
        view.update(delta);
        if (!view.isEnabled()) return;

        Viewport3D.InputListener listener = listener();
        if (listener == null) {
            dragging = false;
            return;
        }
        Input in = window.getInput();
        float mouseX = in.getMouseX() - x, mouseY = in.getMouseY() - y;

        boolean down = in.isMouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        if (dragging && !down) {
            dragging = false;
            listener.dragEnded(mouseX, mouseY);
        } else if (!dragging && in.isMouseJustPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT) && down) {
            dragging = true;
            listener.dragStarted(mouseX, mouseY);
        }

        float dx = in.getMouseDeltaX(), dy = in.getMouseDeltaY();
        if (dx != 0f || dy != 0f) {
            if (dragging) listener.dragged(mouseX, mouseY, dx, dy);
            else if (in.isMouseInWindow()) listener.pointerMoved(mouseX, mouseY);
        }

        float scrollX = in.getScrollX(), scrollY = in.getScrollY();
        if (scrollX != 0f || scrollY != 0f) listener.scrolled(scrollX, scrollY, mouseX, mouseY);

        int mods = in.getModifiers();
        for (int key = FIRST_KEY; key <= GLFW.GLFW_KEY_LAST; key++) {
            if (in.isKeyJustPressed(key)) listener.keyPressed(key, GLFW.GLFW_PRESS, mods);
            else if (in.isKeyRepeated(key)) listener.keyPressed(key, GLFW.GLFW_REPEAT, mods);
            if (in.isKeyJustReleased(key)) listener.keyPressed(key, GLFW.GLFW_RELEASE, mods);
        }
    }

    /**
     * Frees the content and the target. The background can draw again, which starts them afresh. Render thread, between
     * frames. This happens by itself when the window closes; call it when the background is removed earlier, after
     * {@code window.setBackground(null)}.
     */
    public void close() {
        view.close();
    }
}
