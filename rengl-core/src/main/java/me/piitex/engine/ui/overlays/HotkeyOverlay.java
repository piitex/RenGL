package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.Binding;
import me.piitex.engine.input.InputContext;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.scroll.Scrollable;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A box that shows a key binding and learns a new one: the "Jump: Space" row of a controls screen. Clicking it says "Press a
 * key...", and the next key the player presses, with the modifiers held, becomes the binding.
 * <pre>{@code
 * HotkeyOverlay jump = new HotkeyOverlay(160, 32);
 * jump.setBinding(Binding.key(GLFW.GLFW_KEY_SPACE));
 * jump.onBindingChange(b -> settings.setJump(b));
 *
 * // or edit an action of an InputContext directly, with a warning when the key is taken
 * HotkeyOverlay fire = new HotkeyOverlay(160, 32);
 * fire.bindTo(gameplay, "fire");
 * }</pre>
 * It produces a {@link Binding}, the engine's own key, mouse button or wheel direction with modifiers, so the result can go
 * straight into an {@link InputContext} and be saved with it. While it listens Escape cancels, and Backspace or Delete
 * unbinds it ({@link #setAllowClear}). Tab and Enter are captured like any other key, so a player can bind them; clicking
 * elsewhere cancels. Pressing only a modifier (Ctrl, Shift) shows it as the start of a combination, waiting for the key.
 * <p>
 * By default only keys are learned. {@link #setAllowMouse} also learns the extra mouse buttons (middle, side buttons, right)
 * and the wheel; a left click while listening always cancels, since that is how the player leaves. {@link #setAllowModifiers}
 * off learns the key alone, for games where Shift and Ctrl are used on their own.
 * <p>
 * A binding that {@link #setConflictCheck is refused} is not taken: the box says why for a moment and goes on listening.
 * <p>
 * Colors come from the theme like a text field's; the accent marks the box while it listens.
 */
public class HotkeyOverlay extends Overlay implements Themeable, Focusable, Scrollable, FontScalable {
    private static final float MESSAGE_SECONDS = 2f;
    private static final Color REFUSED = new Color(0.9f, 0.3f, 0.3f, 1f);

    private Binding binding;
    private boolean listening;
    private int previewMods;
    private String message = "";
    private float messageTime;
    private boolean focused;

    private boolean allowClear = true;
    private boolean allowMouse;
    private boolean allowModifiers = true;
    private String emptyText = "Unbound";
    private String listenText = "Press a key...";

    private Function<Binding, String> conflictCheck;
    private Consumer<Binding> onBindingChange;
    private Runnable onListenStart;

    private float fontSize = 15f;
    private final Typeface typeface = Font.getDefault();
    private org.jetbrains.skia.Font font;
    private boolean fontDirty = true;

    private final Overridable<Paint> textColor = new Overridable<>(Color.BLACK);
    private final Overridable<Paint> hintColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> accentColor = new Overridable<>(Color.GRAY);

    public HotkeyOverlay(double width, double height) {
        setWidth(width);
        setHeight(height);
        setCursorMode(CursorMode.POINTER);
        setFocusTraversable(true);
        getStyling().applyThemeBorderThickness(1f);
        getStyling().applyThemeCornerRadius(4f);
        setBuiltInClickListener(this::onClick);
        ThemeManager.register(this);
    }

    /* Binding */

    /**
     * What the box shows now, or null when unbound.
     */
    public Binding getBinding() {
        return binding;
    }

    /**
     * Sets the binding shown (null for unbound) without firing {@link #onBindingChange}, and stops listening.
     */
    public void setBinding(Binding binding) {
        this.binding = binding;
        stopListening();
    }

    /**
     * Fired when the player learns a new binding, or unbinds with Backspace (then with null). Not fired by {@link
     * #setBinding}.
     */
    public void onBindingChange(Consumer<Binding> onBindingChange) {
        this.onBindingChange = onBindingChange;
    }

    /**
     * Makes the box edit the first binding of {@code action} in {@code context}: it shows that binding, writes a new one back
     * into the context, and refuses a binding that another action of the context already uses, saying which. Replaces any
     * {@link #setConflictCheck check} and {@link #onBindingChange listener} set before.
     */
    public void bindTo(InputContext context, String action) {
        List<Binding> current = context.getBindings(action);
        this.binding = current.isEmpty() ? null : current.getFirst();
        this.conflictCheck = candidate -> {
            for (String other : context.getActionsBoundTo(candidate)) {
                if (!other.equals(action)) return "In use by " + other;
            }
            return null;
        };
        this.onBindingChange = changed -> {
            List<Binding> next = new ArrayList<>(context.getBindings(action));
            if (!next.isEmpty()) next.removeFirst();
            if (changed != null) next.addFirst(changed);
            context.setBindings(action, next);
        };
    }

    /**
     * Decides whether a binding the player pressed may be taken: return null to accept it, or the reason to refuse it, which
     * is shown for a moment. The box keeps listening after a refusal.
     */
    public void setConflictCheck(Function<Binding, String> conflictCheck) {
        this.conflictCheck = conflictCheck;
    }

    public boolean isListening() {
        return listening;
    }

    /**
     * Starts listening, as a click does.
     */
    public void startListening() {
        listening = true;
        previewMods = 0;
        message = "";
        if (onListenStart != null) onListenStart.run();
    }

    /**
     * Stops listening without changing the binding.
     */
    public void stopListening() {
        listening = false;
        previewMods = 0;
    }

    /**
     * Called when the box starts listening, so a controls screen can make sure no other box is.
     */
    public void onListenStart(Runnable onListenStart) {
        this.onListenStart = onListenStart;
    }

    /* Settings */

    public boolean isAllowClear() {
        return allowClear;
    }

    /**
     * Whether Backspace or Delete while listening unbinds the box. On by default; turn it off for an action that must always
     * have a key (then those two keys can be bound).
     */
    public void setAllowClear(boolean allowClear) {
        this.allowClear = allowClear;
    }

    public boolean isAllowMouse() {
        return allowMouse;
    }

    /**
     * Whether mouse buttons other than the left, and the wheel, can be learned too. Off by default.
     */
    public void setAllowMouse(boolean allowMouse) {
        this.allowMouse = allowMouse;
    }

    public boolean isAllowModifiers() {
        return allowModifiers;
    }

    /**
     * Whether Ctrl, Shift, Alt and Super held with the key become part of the binding. On by default; off keeps just the key.
     */
    public void setAllowModifiers(boolean allowModifiers) {
        this.allowModifiers = allowModifiers;
    }

    /**
     * What is shown for no binding; "Unbound" by default.
     */
    public void setEmptyText(String emptyText) {
        this.emptyText = emptyText == null ? "" : emptyText;
    }

    /**
     * What is shown while listening; "Press a key..." by default.
     */
    public void setListenText(String listenText) {
        this.listenText = listenText == null ? "" : listenText;
    }

    /**
     * The text now: the binding's display name, the prompt, or the reason the last press was refused.
     */
    public String getDisplayText() {
        if (messageTime > 0 && !message.isEmpty()) return message;
        if (listening) {
            if (previewMods != 0 && allowModifiers) {
                // "Ctrl+Shift+" and then dots where the key will go: the name of a combination with a stand-in key, cut short.
                String combo = Binding.key(GLFW.GLFW_KEY_SPACE, previewMods).getDisplayName();
                return combo.substring(0, combo.length() - "Space".length()) + "...";
            }
            return listenText;
        }
        return binding == null ? emptyText : binding.getDisplayName();
    }

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
        fontDirty = true;
    }

    @Override
    public float[] getFontSizes() {
        return new float[]{fontSize};
    }

    @Override
    public void setFontSizes(float[] sizes) {
        setFontSize(sizes[0]);
    }

    public Paint getTextColor() {
        return textColor.get();
    }

    public void setTextColor(Paint textColor) {
        this.textColor.set(textColor);
    }

    public Paint getAccentColor() {
        return accentColor.get();
    }

    /**
     * The color of the border while listening.
     */
    public void setAccentColor(Paint accentColor) {
        this.accentColor.set(accentColor);
    }

    /* Input */

    private void onClick(MouseClickEvent event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        if (!listening) {
            if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) startListening();
            return;
        }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            stopListening(); // the way out of listening
        } else if (allowMouse) {
            accept(Binding.mouse(event.getButton(), modifiersFor(currentMods())));
        }
    }

    @Override
    public void onScroll(double deltaX, double deltaY) {
        if (!listening || !allowMouse) return;
        int direction = deltaY > 0 ? Binding.WHEEL_UP : deltaY < 0 ? Binding.WHEEL_DOWN : deltaX > 0 ? Binding.WHEEL_RIGHT : deltaX < 0 ? Binding.WHEEL_LEFT : -1;
        if (direction >= 0) accept(Binding.wheel(direction, modifiersFor(currentMods())));
    }

    @Override
    public boolean canScroll(double deltaX, double deltaY) {
        return listening && allowMouse; // otherwise the wheel scrolls the page behind it
    }

    @Override
    public void onFocusGained() {
        focused = true;
    }

    @Override
    public void onFocusLost() {
        focused = false;
        stopListening();
    }

    @Override
    public void onCharTyped(int codepoint) {
    }

    @Override
    public boolean wantsTab() {
        return listening;
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (!listening) {
            if (action == GLFW.GLFW_PRESS && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE)) startListening();
            return;
        }
        if (Binding.isModifierKey(key)) {
            // Show the combination so far; the key it waits for decides it.
            previewMods = modifiersFor(mods);
            return;
        }
        if (action == GLFW.GLFW_RELEASE) return;
        int pressedMods = modifiersFor(mods);
        if (key == GLFW.GLFW_KEY_ESCAPE && pressedMods == 0) {
            stopListening();
            return;
        }
        if (allowClear && pressedMods == 0 && (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE)) {
            binding = null;
            stopListening();
            if (onBindingChange != null) onBindingChange.accept(null);
            return;
        }
        Binding learned;
        try {
            learned = Binding.key(key, pressedMods);
        } catch (IllegalArgumentException unknownKey) {
            return; // a key the engine has no name for
        }
        accept(learned);
    }

    private int modifiersFor(int mods) {
        return allowModifiers ? mods & Binding.MODIFIER_MASK : 0;
    }

    /**
     * The modifier bits held now, from the window's input. Separate so a test can say so without a window.
     */
    protected int currentMods() {
        me.piitex.engine.Window window = getWindow();
        return window == null ? 0 : window.getInput().getModifiers();
    }

    private void accept(Binding learned) {
        String refusal = conflictCheck == null ? null : conflictCheck.apply(learned);
        if (refusal != null) {
            message = refusal;
            messageTime = MESSAGE_SECONDS;
            previewMods = 0;
            return;
        }
        binding = learned;
        stopListening();
        message = "";
        messageTime = 0;
        if (onBindingChange != null) onBindingChange.accept(learned);
    }

    /* Theme */

    @Override
    public void applyTheme(Theme theme) {
        getStyling().applyThemeBackgroundColor(theme.getFieldBackground());
        getStyling().applyThemeHoverColor(theme.getFieldBackground());
        getStyling().applyThemeBorderColor(theme.getFieldBorder());
        textColor.applyTheme(theme.getText());
        hintColor.applyTheme(theme.getText().withAlpha(0.55f));
        accentColor.applyTheme(theme.getAccent());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        textColor.clearExplicit();
        hintColor.clearExplicit();
        accentColor.clearExplicit();
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        if (messageTime > 0) messageTime = Math.max(0f, messageTime - delta);
        AnimatedPaint.advanceIfAnimated(textColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(hintColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(accentColor.get(), delta);
    }

    /* Rendering */

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);
        float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
        if (isHovered() && !listening) {
            renderer.pushAlpha(0.06f);
            renderer.drawQuad(x, y, w, h, textColor.get(), getStyling().getCornerRadius());
            renderer.popAlpha();
        }
        if (listening || focused) {
            renderer.drawBorder(x, y, w, h, accentColor.get(), listening ? 2f : 1.5f, getStyling().getCornerRadius());
        }
        String text = getDisplayText();
        boolean refused = messageTime > 0 && !message.isEmpty();
        Paint color = refused ? REFUSED : (binding == null || listening) && !text.contains("+") ? hintColor.get() : textColor.get();
        org.jetbrains.skia.Font f = getFont();
        float textW = f.measureTextWidth(text);
        FontMetricsCompat m = FontMetricsCompat.of(f);
        float textH = -m.getAscent() + m.getDescent();
        renderer.pushClip(x + 4f, y, w - 8f, h);
        renderer.drawText(text, x + Math.max(6f, (w - textW) / 2f), y + (h - textH) / 2f, f, resolveTextColor(color));
        renderer.popClip();
    }

    private org.jetbrains.skia.Font getFont() {
        if (fontDirty || font == null) {
            if (font != null) font.close();
            font = new org.jetbrains.skia.Font(typeface, fontSize);
            fontDirty = false;
        }
        return font;
    }

    /**
     * Frees the font used for the text, if any was drawn.
     */
    public void dispose() {
        if (font != null) {
            font.close();
            font = null;
        }
    }
}
