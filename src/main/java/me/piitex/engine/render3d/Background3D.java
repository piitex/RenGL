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
 * never reaches it, and a press on empty space, or on a panel that has no click listener, does. (A container with a
 * click listener, or a draggable or scrollable element, takes the press first; that is the interface having priority,
 * and not something a background can opt out of.) A
 * {@link Viewport3D.InputListener} (set with {@link #setInputListener}, or the content itself if it implements one,
 * as {@code OrbitControls} does) gets:
 * <ul>
 *   <li>a left-button drag, from a press the UI didn't take until the button is released, and a click when the
 *   mouse hardly moved in between;</li>
 *   <li>the wheel;</li>
 *   <li>the mouse moving ({@link Viewport3D.InputListener#pointerMoved}), which also fires while the mouse is over
 *   the UI, and {@code pointerExited} when it leaves the window;</li>
 *   <li>keys the UI didn't take, as press, repeat and release, and the text typed while no UI element has keyboard focus
 *   ({@link Viewport3D.InputListener#charTyped});</li>
 *   <li>{@code focusChanged}: the background counts as focused while the window has the OS focus and no UI element
 *   holds the keyboard, so it follows the same rule as the keys and the typed text it receives.</li>
 * </ul>
 * <p>
 * <b>As an element's background.</b> {@link me.piitex.engine.ui.Element#setBackground} takes it too: the 3D then fills that
 * element (a panel with a spinning model behind its text) and the input is limited to that element's box: a press, the
 * mouse moving and the wheel only count over it (a drag that began there goes on wherever the mouse is), and the
 * coordinates the listener gets are relative to its corner. Keys and typed text follow the window's focus, as for a
 * whole-window background.
 * <p>
 * Backgrounds replace each other, one to a window or element: to keep an effect such as a constellation behind the 3D
 * (or in front of it), put both in a {@link me.piitex.engine.ui.background.LayeredBackground}. A background that is
 * replaced or removed frees its GPU objects by itself, after that frame.
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
    private float pressX, pressY;
    private boolean pressMoved;
    private boolean wasInside, hadFocus;

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
     * Anti-aliasing samples per pixel: 4 by default, 1 for none; see {@link Viewport3D#setSamples}.
     */
    public void setSamples(int samples) {
        view.setSamples(samples);
    }

    public int getSamples() {
        return view.getSamples();
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
     * True (the default) draws the content every frame. False draws it only after {@link #requestRender}, a window
     * resize, new content, or input that reaches the listener, and shows the last picture otherwise; see
     * {@link Viewport3D#setContinuous}.
     */
    public void setContinuous(boolean continuous) {
        view.setContinuous(continuous);
    }

    public boolean isContinuous() {
        return view.isContinuous();
    }

    /**
     * Asks for the content to be drawn on the next frame, when it is not {@link #setContinuous continuous}.
     */
    public void requestRender() {
        view.requestRender();
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
            wasInside = false;
            hadFocus = false;
            return;
        }
        Input in = window.getInput();
        float mouseX = in.getMouseX() - x, mouseY = in.getMouseY() - y;

        // Over the background's own box: the whole window, or the element it is the background of.
        boolean over = in.isMouseInWindow() && mouseX >= 0f && mouseY >= 0f && mouseX < width && mouseY < height;
        boolean down = in.isMouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean fed = false;
        boolean focused = window.isFocused() && window.getFocusedElement() == null;
        if (focused != hadFocus) {
            fed = true;
            hadFocus = focused;
            listener.focusChanged(focused);
        }
        if (dragging && !down) {
            fed = true;
            dragging = false;
            listener.dragEnded(mouseX, mouseY);
            if (!pressMoved && Math.hypot(mouseX - pressX, mouseY - pressY) <= Viewport3D.CLICK_SLOP) listener.clicked(mouseX, mouseY);
        } else if (!dragging && over && in.isMouseJustPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT) && down) {
            fed = true;
            dragging = true;
            pressX = mouseX;
            pressY = mouseY;
            pressMoved = false;
            listener.dragStarted(mouseX, mouseY);
        }

        float dx = in.getMouseDeltaX(), dy = in.getMouseDeltaY();
        if (dx != 0f || dy != 0f) {
            fed = true;
            if (dragging) {
                if (Math.hypot(mouseX - pressX, mouseY - pressY) > Viewport3D.CLICK_SLOP) pressMoved = true;
                listener.dragged(mouseX, mouseY, dx, dy);
            } else if (over) {
                listener.pointerMoved(mouseX, mouseY);
            }
        }
        if (wasInside && !over && !dragging) {
            fed = true;
            listener.pointerExited();
        }
        if (!dragging) wasInside = over; // a drag that ends outside the box still owes the exit

        float scrollX = in.getScrollX(), scrollY = in.getScrollY();
        if (over && (scrollX != 0f || scrollY != 0f)) {
            fed = true;
            listener.scrolled(scrollX, scrollY, mouseX, mouseY);
        }

        String typed = in.getTypedText();
        for (int i = 0; i < typed.length(); ) {
            int codepoint = typed.codePointAt(i);
            i += Character.charCount(codepoint);
            fed = true;
            listener.charTyped(codepoint);
        }

        int mods = in.getModifiers();
        for (int key = FIRST_KEY; key <= GLFW.GLFW_KEY_LAST; key++) {
            if (in.isKeyJustPressed(key)) {
                fed = true;
                listener.keyPressed(key, GLFW.GLFW_PRESS, mods);
            } else if (in.isKeyRepeated(key)) {
                fed = true;
                listener.keyPressed(key, GLFW.GLFW_REPEAT, mods);
            }
            if (in.isKeyJustReleased(key)) {
                fed = true;
                listener.keyPressed(key, GLFW.GLFW_RELEASE, mods);
            }
        }
        if (fed) view.requestRender();
    }

    /**
     * Frees the content and the target. The background can draw again, which starts them afresh. Render thread, between
     * frames. This happens by itself when the window closes; call it when the background is removed earlier, after
     * {@code window.setBackground(null)}.
     */
    public void close() {
        view.close();
    }

    /**
     * Frees the GPU objects once the frame that took the background away has finished: it was replaced by another or
     * removed with null. A background that is put back later starts afresh.
     */
    @Override
    public void onDetached() {
        view.closeAfterFrame();
    }
}
