package me.piitex.engine.ui.containers;

import me.piitex.engine.ui.text.TextShaping;
import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.ui.text.TextAlign;
import me.piitex.engine.ui.text.TextLayout;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Short messages that appear in a corner of the window, stay a few seconds and fade away on their own: "Saved", "Download
 * finished", "Could not reach the server". Nothing has to be laid out or removed; ask for one and forget it.
 * <pre>{@code
 * ToastManager.of(window).success("Game saved");
 * ToastManager.of(window).error("Upload failed", "The server did not answer.");
 * ToastManager.of(window).show(new ToastManager.Toast("Level 5 reached").setLevel(Level.SUCCESS).setAction("View", this::openStats));
 * }</pre>
 * {@link #of(Window)} gives the window's manager, which it adds to the window the first time and keeps above every other
 * container (a dialog included) while a toast is showing. The toasts stack away from the {@link #setPosition corner}, newest
 * nearest it, and only {@link #setMaxVisible} show at once; the rest wait their turn. A toast slides in, waits for its
 * {@link Toast#setDuration duration} (paused while the pointer is over it), and fades out. Clicking one dismisses it, after
 * running its {@link Toast#setAction action} if it has one. A toast with no duration stays until clicked or {@link
 * Toast#dismiss dismissed}.
 * <p>
 * The manager only takes the pointer where a toast is: the rest of the window behaves as if it were not there. It can be
 * used from any thread; the toasts are laid out and drawn on the render thread.
 */
public class ToastManager extends Container {
    /**
     * What a toast is about. It sets the color of the stripe along its edge.
     */
    public enum Level {INFO, SUCCESS, WARNING, ERROR}

    /**
     * Which corner (or the middle of an edge) the toasts stack from.
     */
    public enum Position {TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT}

    /**
     * One message. Build it, change what you want, and pass it to {@link ToastManager#show(Toast)}; the setters return the
     * toast.
     */
    public static final class Toast {
        private final String message;
        private String title;
        private Level level = Level.INFO;
        private float duration = 4f;
        private String actionLabel;
        private Runnable action;
        private Runnable onDismiss;

        // Animation and layout state, owned by the manager.
        private float age;
        private float remaining;
        private float closing = -1f; // seconds since it started to leave, or -1
        private float y = Float.NaN;
        private float x = Float.NaN;
        private float width, height, wrapWidth = -1f;
        private List<String> lines = List.of();
        private boolean dismissed;

        public Toast(String message) {
            this.message = message == null ? "" : message;
            this.remaining = duration;
        }

        public String getMessage() {
            return message;
        }

        public String getTitle() {
            return title;
        }

        /**
         * A bold line above the message. None by default.
         */
        public Toast setTitle(String title) {
            this.title = title == null || title.isEmpty() ? null : title;
            return this;
        }

        public Level getLevel() {
            return level;
        }

        public Toast setLevel(Level level) {
            this.level = level == null ? Level.INFO : level;
            return this;
        }

        public float getDuration() {
            return duration;
        }

        /**
         * How many seconds it stays after sliding in; 0 or less keeps it until it is clicked or dismissed. Default 4.
         */
        public Toast setDuration(float seconds) {
            this.duration = seconds;
            this.remaining = seconds;
            return this;
        }

        /**
         * A label drawn at the end of the toast and a callback that runs when the toast is clicked, such as "Undo".
         */
        public Toast setAction(String label, Runnable action) {
            this.actionLabel = label == null || label.isEmpty() ? null : label;
            this.action = action;
            return this;
        }

        /**
         * Runs when the toast goes away by any route: expiring, a click, {@link #dismiss} or {@link ToastManager#clear}.
         */
        public Toast onDismiss(Runnable onDismiss) {
            this.onDismiss = onDismiss;
            return this;
        }

        /**
         * Starts it fading away now. A no-op once it has started.
         */
        public void dismiss() {
            if (closing < 0f) closing = 0f;
        }

        public boolean isDismissed() {
            return dismissed;
        }

        /**
         * Where it is drawn now, in window coordinates; only meaningful once it has been on screen for a frame.
         */
        public float getX() {
            return x;
        }

        public float getY() {
            return y;
        }

        public float getWidth() {
            return width;
        }

        public float getHeight() {
            return height;
        }
    }

    private static final double OFFSCREEN = -10; // where the empty manager sits, so it covers no point of the window
    private static final float SLIDE_SECONDS = 0.22f, FADE_SECONDS = 0.25f, PAD = 12f, GAP = 8f, STRIPE = 5f;

    private final List<Toast> toasts = new CopyOnWriteArrayList<>();
    private Position position = Position.BOTTOM_RIGHT;
    private int maxVisible = 5;
    private float toastWidth = 340f;
    private float margin = 16f;
    private float fontSize = 15f;

    private final Overridable<Paint> backgroundPaint = new Overridable<>(new Color(0.16f, 0.17f, 0.2f, 0.96f));
    private final Overridable<Paint> borderPaint = new Overridable<>(new Color(1f, 1f, 1f, 0.12f));
    private final Overridable<Paint> textPaint = new Overridable<>(Color.WHITE);
    private final Overridable<Paint> accentPaint = new Overridable<>(new Color(0.35f, 0.6f, 1f, 1f));
    private float cornerRadius = 8f;

    private org.jetbrains.skia.Font font, titleFont;
    private Typeface fontTypeface;

    /**
     * A manager that is not attached to any window yet. Normally get one with {@link #of(Window)}; construct one to add to a
     * container yourself.
     */
    public ToastManager() {
        super(0, 0);
        setBounds(OFFSCREEN, OFFSCREEN, 0, 0);
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        setBuiltInClickListener(event -> {
            if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            Toast hit = toastAt(event.getMouseX(), event.getMouseY());
            if (hit == null) return;
            if (hit.action != null) hit.action.run();
            hit.dismiss();
        });
        ThemeManager.register(this);
    }

    /**
     * The manager for {@code window}: the one already among its containers, else a new one added to it.
     */
    public static ToastManager of(Window window) {
        synchronized (ToastManager.class) {
            for (Container container : window.getContainers().values()) {
                if (container instanceof ToastManager existing) return existing;
            }
            ToastManager manager = new ToastManager();
            window.addContainer(manager);
            return manager;
        }
    }

    /* Showing */

    /**
     * Shows {@code toast} after the ones already waiting. Returns it, so it can be dismissed later.
     */
    public Toast show(Toast toast) {
        toast.remaining = toast.duration;
        toast.age = 0f;
        toast.closing = -1f;
        toast.dismissed = false;
        toasts.add(toast);
        Window window = getWindow();
        if (window != null) { // a screen reader or self-voicing host says it too; with none this does nothing
            String spoken = toast.getTitle() == null || toast.getTitle().isBlank() ? toast.getMessage() : toast.getTitle() + ". " + toast.getMessage();
            window.announce(spoken, toast.getLevel() == Level.ERROR);
        }
        return toast;
    }

    public Toast show(String message) {
        return show(new Toast(message));
    }

    public Toast show(String message, Level level) {
        return show(new Toast(message).setLevel(level));
    }

    public Toast info(String message) {
        return show(message, Level.INFO);
    }

    public Toast success(String message) {
        return show(message, Level.SUCCESS);
    }

    public Toast warning(String message) {
        return show(message, Level.WARNING);
    }

    public Toast error(String message) {
        return show(message, Level.ERROR);
    }

    public Toast info(String title, String message) {
        return show(new Toast(message).setTitle(title).setLevel(Level.INFO));
    }

    public Toast success(String title, String message) {
        return show(new Toast(message).setTitle(title).setLevel(Level.SUCCESS));
    }

    public Toast warning(String title, String message) {
        return show(new Toast(message).setTitle(title).setLevel(Level.WARNING));
    }

    public Toast error(String title, String message) {
        return show(new Toast(message).setTitle(title).setLevel(Level.ERROR));
    }

    /**
     * Every toast, showing or waiting, in the order they were shown.
     */
    public List<Toast> getToasts() {
        return List.copyOf(toasts);
    }

    /**
     * Removes every toast at once, without the fade, running their {@link Toast#onDismiss dismiss} callbacks.
     */
    public void clear() {
        for (Toast toast : toasts) finish(toast);
    }

    private void finish(Toast toast) {
        if (!toasts.remove(toast)) return;
        toast.dismissed = true;
        if (toast.onDismiss != null) toast.onDismiss.run();
    }

    /* Look */

    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = position == null ? Position.BOTTOM_RIGHT : position;
    }

    public int getMaxVisible() {
        return maxVisible;
    }

    /**
     * How many toasts show at once; the others wait for one to leave. Default 5.
     */
    public void setMaxVisible(int maxVisible) {
        this.maxVisible = Math.max(1, maxVisible);
    }

    public float getToastWidth() {
        return toastWidth;
    }

    /**
     * The width of a toast, less on a window narrower than that. Default 340.
     */
    public void setToastWidth(float toastWidth) {
        this.toastWidth = Math.max(80f, toastWidth);
    }

    public float getMargin() {
        return margin;
    }

    /**
     * The gap between the toasts and the window's edge. Default 16.
     */
    public void setMargin(float margin) {
        this.margin = Math.max(0f, margin);
    }

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
        closeFonts();
    }

    public float getCornerRadius() {
        return cornerRadius;
    }

    public void setCornerRadius(float cornerRadius) {
        this.cornerRadius = Math.max(0f, cornerRadius);
    }

    public Paint getBackgroundPaint() {
        return backgroundPaint.get();
    }

    /**
     * Pins the toasts' fill against theme switches.
     */
    public void setBackgroundPaint(Paint paint) {
        backgroundPaint.set(paint);
    }

    public Paint getTextPaint() {
        return textPaint.get();
    }

    public void setTextPaint(Paint paint) {
        textPaint.set(paint);
    }

    /**
     * The stripe color of {@link Level#INFO} toasts and of an action's label. The other levels have fixed colors.
     */
    public void setAccentPaint(Paint paint) {
        accentPaint.set(paint);
    }

    @Override
    public void applyTheme(Theme theme) {
        backgroundPaint.applyTheme(theme.getContainerBackground());
        borderPaint.applyTheme(theme.getContainerBorder());
        textPaint.applyTheme(theme.getText());
        accentPaint.applyTheme(theme.getAccent());
        cornerRadius = Math.max(cornerRadius, theme.getCornerRadius());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        backgroundPaint.clearExplicit();
        borderPaint.clearExplicit();
        textPaint.clearExplicit();
        accentPaint.clearExplicit();
    }

    private Paint stripeFor(Level level) {
        return switch (level) {
            case SUCCESS -> new Color(0.20f, 0.72f, 0.42f, 1f);
            case WARNING -> new Color(0.96f, 0.67f, 0.16f, 1f);
            case ERROR -> new Color(0.88f, 0.27f, 0.27f, 1f);
            default -> accentPaint.get();
        };
    }

    /* Layout and timing */

    /**
     * The toasts that are showing: the first {@link #setMaxVisible} that have not left.
     */
    private List<Toast> visible() {
        List<Toast> shown = new ArrayList<>(maxVisible);
        for (Toast toast : toasts) {
            if (shown.size() >= maxVisible) break;
            shown.add(toast);
        }
        return shown;
    }

    private Toast toastAt(double px, double py) {
        for (Toast toast : visible()) {
            if (Float.isNaN(toast.x)) continue;
            if (px >= toast.x && px <= toast.x + toast.width && py >= toast.y && py <= toast.y + toast.height) return toast;
        }
        return null;
    }

    @Override
    public void update(float delta) {
        Window window = getWindow();
        if (window == null) return;
        advance(delta, window.getWindowOptions().getWidth(), window.getWindowOptions().getHeight());
        if (!toasts.isEmpty()) raise(window);
    }

    /**
     * Moves the toasts on by {@code delta} seconds in a window {@code winW} by {@code winH}: timers, fades, positions and the
     * manager's own box. Split out of {@link #update} so it runs without a window.
     */
    void advance(float delta, double winW, double winH) {
        if (toasts.isEmpty()) {
            if (getWidth() != 0 || getHeight() != 0 || getX() != OFFSCREEN) setBounds(OFFSCREEN, OFFSCREEN, 0, 0);
            return;
        }

        float width = (float) Math.min(toastWidth, Math.max(80, winW - 2 * margin));
        org.jetbrains.skia.Font f = getFont();
        float lineH = TextLayout.lineHeight(f);
        double mx = MouseTracker.getX(), my = MouseTracker.getY();
        boolean inWindow = MouseTracker.isInWindow();

        List<Toast> shown = visible();
        float cursor = 0f;
        boolean bottom = position.name().startsWith("BOTTOM");
        for (int i = shown.size() - 1; i >= 0; i--) { // newest first, nearest the edge
            Toast toast = shown.get(i);
            measure(toast, width, f, lineH);
            boolean hovered = inWindow && !Float.isNaN(toast.x) && mx >= toast.x && mx <= toast.x + toast.width
                    && my >= toast.y && my <= toast.y + toast.height;
            toast.age += delta;
            if (toast.closing >= 0f) {
                toast.closing += delta;
            } else if (toast.duration > 0f && toast.age >= SLIDE_SECONDS && !hovered) {
                toast.remaining -= delta;
                if (toast.remaining <= 0f) toast.dismiss();
            }
            if (toast.closing >= FADE_SECONDS) {
                finish(toast);
                continue;
            }

            float targetX = switch (position) {
                case TOP_LEFT, BOTTOM_LEFT -> margin;
                case TOP_CENTER, BOTTOM_CENTER -> (float) (winW - width) / 2f;
                default -> (float) winW - margin - width;
            };
            float targetY = bottom ? (float) winH - margin - cursor - toast.height : margin + cursor;
            if (Float.isNaN(toast.y)) {
                // Starts a little off its place, toward the edge it comes from, and eases in.
                toast.y = targetY + (bottom ? 24f : -24f);
                toast.x = targetX;
            }
            float follow = Math.min(1f, delta * 14f);
            toast.y += (targetY - toast.y) * follow;
            toast.x += (targetX - toast.x) * follow;
            cursor += toast.height + GAP;
        }

        // The manager is only as big as the toasts, so it takes no clicks anywhere else.
        double left = Double.MAX_VALUE, top = Double.MAX_VALUE, right = -Double.MAX_VALUE, bottomEdge = -Double.MAX_VALUE;
        for (Toast toast : visible()) {
            if (Float.isNaN(toast.x)) continue;
            left = Math.min(left, toast.x);
            top = Math.min(top, toast.y);
            right = Math.max(right, toast.x + toast.width);
            bottomEdge = Math.max(bottomEdge, toast.y + toast.height);
        }
        if (left > right) setBounds(OFFSCREEN, OFFSCREEN, 0, 0);
        else setBounds(left, top, right - left, bottomEdge - top);
    }

    private void measure(Toast toast, float width, org.jetbrains.skia.Font f, float lineH) {
        float textWidth = width - STRIPE - 2 * PAD - 18f; // room is kept for the close mark
        if (toast.wrapWidth != textWidth) {
            toast.lines = TextLayout.wrap(toast.message, f, textWidth);
            toast.wrapWidth = textWidth;
        }
        toast.width = width;
        float h = 2 * PAD + toast.lines.size() * lineH;
        if (toast.title != null) h += lineH + 3f;
        if (toast.actionLabel != null) h += lineH + 4f;
        toast.height = Math.max(h, 2 * PAD + lineH);
    }

    /**
     * Keeps this manager the top container while it has toasts to show, so a toast is not hidden behind a window that was
     * added after it. Leaves an open popup (a menu or dropdown list) alone.
     */
    private void raise(Window window) {
        var containers = window.getContainers();
        if (containers.isEmpty() || containers.lastEntry().getValue() == this) return;
        if (window.getPopup() != null && containers.lastEntry().getValue() == window.getPopup()) return;
        if (!containers.containsValue(this)) return;
        window.removeContainer(this);
        window.addContainer(this);
    }

    /* Rendering */

    private org.jetbrains.skia.Font getFont() {
        Typeface typeface = Font.getDefault();
        if (font == null || typeface != fontTypeface) {
            closeFonts();
            font = new org.jetbrains.skia.Font(typeface, fontSize);
            titleFont = new org.jetbrains.skia.Font(typeface, fontSize);
            titleFont.setEmboldened(true);
            fontTypeface = typeface;
        }
        return font;
    }

    private void closeFonts() {
        if (font != null) font.close();
        if (titleFont != null) titleFont.close();
        font = titleFont = null;
    }

    /**
     * Frees the fonts. The manager can still be drawn afterwards; it makes new ones.
     */
    public void dispose() {
        closeFonts();
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled() || toasts.isEmpty()) return;
        org.jetbrains.skia.Font f = getFont();
        float lineH = TextLayout.lineHeight(f);
        float textH = FontMetricsCompat.of(f).getHeight();
        boolean bottom = position.name().startsWith("BOTTOM");
        for (Toast toast : visible()) {
            if (Float.isNaN(toast.x) || toast.wrapWidth < 0f) continue; // not laid out yet
            float alpha = Math.min(1f, toast.age / SLIDE_SECONDS);
            if (toast.closing >= 0f) alpha = Math.min(alpha, 1f - toast.closing / FADE_SECONDS);
            if (alpha <= 0f) continue;
            float x = toast.x, y = toast.y, w = toast.width, h = toast.height;

            renderer.pushAlpha(alpha);
            renderer.pushAlpha(0.22f);
            renderer.drawQuad(x + 1f, y + (bottom ? -1f : 3f), w, h, Color.BLACK, cornerRadius); // a soft shadow
            renderer.popAlpha();
            renderer.drawQuad(x, y, w, h, backgroundPaint.get(), cornerRadius);
            renderer.pushClip(x, y, w, h, cornerRadius);
            renderer.drawQuad(x, y, STRIPE, h, stripeFor(toast.level), 0f);
            renderer.popClip();
            renderer.drawBorder(x, y, w, h, borderPaint.get(), 1f, cornerRadius);

            float tx = x + STRIPE + PAD, ty = y + PAD;
            if (toast.title != null) {
                renderer.drawText(toast.title, tx, ty + (lineH - textH) / 2f, titleFont, textPaint.get());
                ty += lineH + 3f;
            }
            renderer.drawTextLines(toast.lines, tx, ty, lineH, f, textPaint.get(), TextAlign.LEFT);
            ty += toast.lines.size() * lineH;
            if (toast.actionLabel != null) {
                String label = toast.actionLabel.toUpperCase();
                renderer.drawText(label, x + w - PAD - TextShaping.width(f, label), ty + 4f + (lineH - textH) / 2f, titleFont, stripeFor(toast.level));
            }

            // A close mark in the corner.
            float cx = x + w - PAD - 4f, cy = y + PAD + 6f;
            renderer.pushAlpha(0.5f);
            renderer.drawLine(cx - 4f, cy - 4f, cx + 4f, cy + 4f, textPaint.get(), 1.5f);
            renderer.drawLine(cx - 4f, cy + 4f, cx + 4f, cy - 4f, textPaint.get(), 1.5f);
            renderer.popAlpha();
            renderer.popAlpha();
        }
    }
}
