package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.scroll.Scrollable;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.layout.HorizontalLayout;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.VerticalLayout;
import org.lwjgl.glfw.GLFW;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A numeric input: a text field with a stacked up and down button pair beside it,
 * filling the role JavaFX's {@code Spinner} plays. Unlike the other overlays, this one
 * is a composite, built from a {@link HorizontalLayout} holding a
 * {@link TextFieldOverlay} and a {@link VerticalLayout} of two {@link ButtonOverlay}s.
 * It therefore inherits their theming, hover cursors, focus traversal, and text editing,
 * including selection and clipboard support, rather than reimplementing them. Reach the
 * parts through {@link #getField()} for anything not exposed here, such as the
 * placeholder, font, or padding.
 * <p>
 * The value changes through:
 * <ul>
 *   <li>the up and down buttons, one {@link #getStep()} per click</li>
 *   <li>the Up and Down arrow keys while the field is focused, where Shift moves 10
 *       steps</li>
 *   <li>the mouse wheel, only while the field is focused, so scrolling past a spinner
 *       inside a {@link me.piitex.engine.ui.scroll.ScrollContainer} does not change it
 *       by accident</li>
 *   <li>typing, which is parsed, clamped, and reformatted when the field loses focus or
 *       Enter is pressed; text that does not parse reverts to the last valid value</li>
 * </ul>
 * Like {@link SliderOverlay#setValue}, {@link #setValue} does not fire
 * {@link #onValueChange}, while every user-driven change does.
 */
public class SpinnerOverlay extends HorizontalLayout implements Scrollable {
    private final SpinnerField field;
    private final ArrowButton upButton;
    private final ArrowButton downButton;

    private double min;
    private double max;
    private double step = 1;
    private int decimals;
    private boolean decimalsExplicit;
    private double value;
    private boolean empty;
    private Consumer<Double> onValueChange;

    /** A 0-100 whole-number spinner, {@code width} x {@code height} points, starting at 0. */
    public SpinnerOverlay(double width, double height) {
        this(width, height, 0, 100, 0);
    }

    public SpinnerOverlay(double width, double height, double min, double max, double initialValue) {
        this(0, 0, width, height, min, max, initialValue);
    }

    public SpinnerOverlay(double x, double y, double width, double height, double min, double max, double initialValue) {
        super(x, y, width, height);
        this.min = min;
        this.max = Math.max(min, max);

        // A bare Container paints an opaque box by default (see Element). This is
        // seeded as a non-pinning baseline, as TextOverlay does, since the field and
        // buttons inside already draw their own themed boxes.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);

        setAlignment(Layout.Alignment.CENTER_LEFT);

        double buttonWidth = Math.max(16, height * 0.8);
        field = new SpinnerField(width - buttonWidth, height);
        field.setRightAligned(true); // with setLabel: label far left, value far right
        upButton = new ArrowButton(buttonWidth, height / 2, true);
        downButton = new ArrowButton(buttonWidth, height / 2, false);
        upButton.onAction(() -> stepBy(1, 1));
        downButton.onAction(() -> stepBy(-1, 1));

        VerticalLayout buttons = new VerticalLayout(buttonWidth, height);
        buttons.getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        buttons.getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        buttons.getStyling().applyThemeBorderThickness(0f);
        buttons.addElement(upButton);
        buttons.addElement(downButton);

        addElement(field);
        addElement(buttons);

        this.value = normalize(initialValue);
        syncText();
    }

    /** The text field showing the value, for setting the placeholder, font, padding, and so on. Its own {@code onTextChanged} and {@code onSubmit} hooks are free for the application to use. */
    public TextFieldOverlay getField() {
        return field;
    }

    public double getValue() {
        return value;
    }

    /** Sets the value, clamped to {@code [min, max]} and rounded to {@link #getDecimals()}. Does NOT fire {@link #onValueChange}. */
    public void setValue(double value) {
        this.value = normalize(value);
        empty = false;
        syncText();
    }

    /** A permanent label anchored at the field's far left in the lighter placeholder color, with the number anchored at the far right. {@code setLabel("Age")} renders as "Age ......... 25", and the label stays visible as the value changes. */
    public void setLabel(String label) {
        field.setPrefix(label == null || label.isEmpty() ? "" : label + " ");
    }

    /** The hint shown, in the theme's lighter placeholder color, while the spinner is {@link #isEmpty() empty}, for example {@code "Age 25"}. This is the same as {@code getField().setPlaceholder(...)}. */
    public void setPlaceholder(String placeholder) {
        field.setPlaceholder(placeholder);
    }

    public String getPlaceholder() {
        return field.getPlaceholder();
    }

    /**
     * Whether the spinner is showing its placeholder instead of a value. Only possible
     * while a placeholder is set: {@link #clear()} and committing blank text both
     * produce it. {@link #getValue()} still returns the last value while empty, which is
     * the value the first button click, arrow key, or wheel notch fills in.
     */
    public boolean isEmpty() {
        return empty;
    }

    /** Empties the field so the placeholder shows. A no-op without a placeholder. Does NOT fire {@link #onValueChange}. */
    public void clear() {
        String placeholder = field.getPlaceholder();
        if (placeholder == null || placeholder.isEmpty()) return;
        empty = true;
        syncText();
    }

    public double getMin() {
        return min;
    }

    /** Also re-clamps the current value into the new range. */
    public void setMin(double min) {
        this.min = min;
        if (max < min) max = min;
        reclamp();
    }

    public double getMax() {
        return max;
    }

    /** Also re-clamps the current value into the new range. */
    public void setMax(double max) {
        this.max = max;
        if (min > max) min = max;
        reclamp();
    }

    public double getStep() {
        return step;
    }

    /** How much one button click/arrow key/wheel notch changes the value by. Non-positive values are ignored. Also sets {@link #getDecimals()} to as many places as {@code step} has, unless {@link #setDecimals} was called. */
    public void setStep(double step) {
        if (step <= 0 || !Double.isFinite(step)) return;
        this.step = step;
        if (!decimalsExplicit) {
            decimals = Math.max(0, BigDecimal.valueOf(step).stripTrailingZeros().scale());
            reclamp();
        }
    }

    /** How many decimal places the value is rounded to and displayed with. */
    public int getDecimals() {
        return decimals;
    }

    public void setDecimals(int decimals) {
        this.decimals = Math.max(0, decimals);
        this.decimalsExplicit = true;
        reclamp();
    }

    /** Registers the callback fired with the new value whenever the user changes it. Only one is held at a time. */
    public void onValueChange(Consumer<Double> onValueChange) {
        this.onValueChange = onValueChange;
    }

    /** Wheel notches adjust the value while the field is focused; ignored otherwise. */
    @Override
    public void onScroll(double deltaX, double deltaY) {
        if (!field.isFocused() || deltaY == 0) return;
        stepBy(deltaY > 0 ? 1 : -1, 1);
    }

    /** Re-normalizes the current value after a range/precision change, without leaving the empty state. */
    private void reclamp() {
        value = normalize(value);
        syncText();
    }

    private void stepBy(int direction, double multiplier) {
        commitText(); // fold in anything typed but not yet committed, so stepping starts from what's on screen
        // From empty, the first step just fills in the current value rather than moving off it.
        applyUserValue(empty ? value : value + direction * step * multiplier);
    }

    /** Parses the field text into the value, or reverts the text if it isn't a number. */
    private void commitText() {
        String placeholder = field.getPlaceholder();
        if (field.getText().isBlank() && placeholder != null && !placeholder.isEmpty()) {
            empty = true;
            syncText();
            return;
        }
        try {
            double parsed = Double.parseDouble(field.getText().trim());
            if (Double.isFinite(parsed)) {
                applyUserValue(parsed);
                return;
            }
        } catch (NumberFormatException ignored) {
        }
        syncText();
    }

    private void applyUserValue(double raw) {
        double next = normalize(raw);
        boolean changed = next != value || empty;
        value = next;
        empty = false;
        syncText();
        if (changed && onValueChange != null) {
            onValueChange.accept(value);
        }
    }

    private double normalize(double raw) {
        double clamped = Math.max(min, Math.min(max, raw));
        double rounded = BigDecimal.valueOf(clamped).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
        return Math.max(min, Math.min(max, rounded));
    }

    private void syncText() {
        field.setText(empty ? "" : String.format(Locale.ROOT, "%." + decimals + "f", value));
    }

    /** The text field, with number-only typing and the spinner's keyboard/commit behavior layered on. */
    private final class SpinnerField extends TextFieldOverlay {
        SpinnerField(double width, double height) {
            super(width, height);
        }

        @Override
        public void onCharTyped(int codepoint) {
            boolean allowed = Character.isDigit(codepoint)
                    || (codepoint == '-' && min < 0)
                    || (codepoint == '.' && decimals > 0);
            if (allowed) {
                super.onCharTyped(codepoint);
            }
        }

        @Override
        public void onKeyPressed(int key, int action, int mods) {
            if (action != GLFW.GLFW_RELEASE) {
                double multiplier = (mods & GLFW.GLFW_MOD_SHIFT) != 0 ? 10 : 1;
                if (key == GLFW.GLFW_KEY_UP) {
                    stepBy(1, multiplier);
                    return;
                }
                if (key == GLFW.GLFW_KEY_DOWN) {
                    stepBy(-1, multiplier);
                    return;
                }
            }
            super.onKeyPressed(key, action, mods);
            if (action == GLFW.GLFW_PRESS && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
                commitText();
            }
        }

        @Override
        public void onFocusLost() {
            super.onFocusLost();
            commitText();
        }
    }

    /** A themed button whose label is a chevron drawn as lines, since a text arrow glyph may be missing from the app's font. */
    private static final class ArrowButton extends ButtonOverlay {
        private final boolean up;

        ArrowButton(double width, double height, boolean up) {
            super("", width, height);
            this.up = up;
        }

        @Override
        protected void drawText(Renderer renderer) {
            Color color = resolveTextColor(getTextColor()) instanceof Color c ? c : Color.BLACK;
            float cx = (float) (getX() + getWidth() / 2);
            float cy = (float) (getY() + getHeight() / 2);
            float half = (float) Math.min(getWidth(), getHeight()) * 0.22f;
            float tip = up ? -half / 2 : half / 2;
            float base = -tip;
            renderer.drawLine(cx - half, cy + base, cx, cy + tip, color, 1.6f);
            renderer.drawLine(cx, cy + tip, cx + half, cy + base, color, 1.6f);
        }
    }
}
