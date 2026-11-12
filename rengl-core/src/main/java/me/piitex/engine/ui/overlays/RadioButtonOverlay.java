package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;

/**
 * A clickable, mutually exclusive circular selector, filling the role JavaFX's and
 * AtlantaFX's {@code RadioButton} plays within a {@link RadioGroup}. The ring is this
 * Element's own {@link me.piitex.engine.ui.color.Styling}, meaning the background,
 * hover, and border field tokens {@link CheckBoxOverlay}'s box also uses, rounded into
 * a full circle by the constructor. The inner dot is drawn on top while
 * {@link #isSelected()}, using {@link Theme#getAccent()}, as {@link CheckBoxOverlay}'s checkmark does.
 * <p>
 * Selection is exclusive only within a {@link RadioGroup}, so add every mutually
 * exclusive RadioButtonOverlay to the same group through {@link RadioGroup#add}. A
 * button not in any group selects itself in isolation when clicked, and only
 * {@link #setSelected setSelected(false)} deselects it afterward.
 * <p>
 * This class selects itself on a click with a handler of its own, kept apart from
 * {@link #onMouseClick}: yours runs after it and does not replace it. Use {@link RadioGroup#onChange} to react to
 * selection changes.
 */
public class RadioButtonOverlay extends Overlay implements Themeable, me.piitex.engine.ui.Activatable {
    private boolean selected;
    private RadioGroup group;
    private final Overridable<Paint> dotColor = new Overridable<>(Color.BLACK);

    /**
     * An unselected 18x18 circle.
     */
    public RadioButtonOverlay() {
        this(18);
    }

    /**
     * An unselected {@code size}x{@code size} circle.
     */
    public RadioButtonOverlay(double size) {
        setWidth(size);
        setHeight(size);
        setCursorMode(CursorMode.POINTER);
        // A square box rounded at half its own size renders as a full circle rather
        // than a rounded rect. applyTheme never sets the corner radius, so this
        // baseline stays through theme switches and followTheme(). Theme#getCornerRadius()
        // is for the square controls that use it: ButtonOverlay, TextFieldOverlay, and
        // a themed Container.
        getStyling().applyThemeCornerRadius((float) (size / 2));
        setBuiltInClickListener(event -> select());
        setClickOnRelease(true);
        setFocusTraversable(true);
        ThemeManager.register(this);
    }

    /**
     * Whether this button is currently the selected member of its {@link RadioGroup}, or, when ungrouped, whether it has been clicked or set selected. See the class docs.
     */
    public boolean isSelected() {
        return selected;
    }

    /**
     * Selects this button. If it belongs to a {@link RadioGroup}, every other member is
     * deselected and {@link RadioGroup#onChange} fires. If it is ungrouped, only this
     * button is selected. A no-op if it is already selected. This is what a click runs.
     */
    public void select() {
        if (selected) return;
        if (group != null) {
            group.select(this, true);
        } else {
            selected = true;
        }
    }

    /**
     * Sets the selected state programmatically. Unlike {@link #select()}, this does not fire
     * {@link RadioGroup#onChange}. Selecting still deselects the other members of its group,
     * and deselecting the group's selected member leaves the group with no selection. This
     * is also the only way to deselect a button that is not in a group.
     */
    public void setSelected(boolean selected) {
        if (group == null) {
            this.selected = selected;
        } else if (selected) {
            group.select(this, false);
        } else if (group.getSelected() == this) {
            group.select(null, false);
        }
    }

    /**
     * Package-private: only {@link RadioGroup} sets this directly, so it can select the chosen button and deselect its siblings in one pass. Everything else goes through {@link #select()} or {@link #setSelected}.
     */
    void setSelectedInternal(boolean selected) {
        this.selected = selected;
    }

    /**
     * The {@link RadioGroup} this button belongs to, or null if it is ungrouped. See the class docs. This is set through {@link RadioGroup#add} rather than directly.
     */
    public RadioGroup getGroup() {
        return group;
    }

    /**
     * Package-private: only {@link RadioGroup#add} and {@link RadioGroup#remove} set this.
     */
    void setGroupInternal(RadioGroup group) {
        this.group = group;
    }

    public Paint getDotColor() {
        return dotColor.get();
    }

    /**
     * Pins the inner dot color against every future theme switch. See {@link Overridable}.
     */
    public void setDotColor(Paint dotColor) {
        this.dotColor.set(dotColor);
    }

    /**
     * Themes the ring, meaning the background, hover, and border field tokens
     * {@link CheckBoxOverlay} also uses, along with the inner dot color, as a baseline.
     * It skips whichever property {@link #setDotColor} or {@link #getStyling()}'s own
     * setters have already pinned. See {@link Overridable}. It does not touch the corner
     * radius, which is pinned at construction to keep this circular. See the
     * constructor.
     */
    @Override
    public void update(float delta) {
        super.update(delta);
        AnimatedPaint.advanceIfAnimated(dotColor.get(), delta);
    }

    @Override
    public void applyTheme(Theme theme) {
        getStyling().applyThemeBackgroundColor(theme.getFieldBackground());
        getStyling().applyThemeHoverColor(theme.getFieldHoverBackground());
        getStyling().applyThemeBorderColor(theme.getFieldBorder());
        getStyling().applyThemeBorderThickness(theme.getBorderThickness());
        dotColor.applyTheme(theme.getAccent());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        dotColor.clearExplicit();
    }

    /**
     * Draws the ring through {@code super.render(...)}, using the background, hover, and border styling that the constructor rounds into a circle, then draws a filled inner dot while {@link #isSelected()}.
     */
    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;

        super.render(renderer);

        if (selected) {
            float centerX = (float) (getX() + getWidth() / 2.0);
            float centerY = (float) (getY() + getHeight() / 2.0);
            float radius = (float) (Math.min(getWidth(), getHeight()) / 2.0) * 0.45f;
            renderer.drawCircle(centerX, centerY, radius, dotColor.get());
        }
    }

    // --- Accessibility ---

    @Override
    protected me.piitex.engine.access.AccessibleRole defaultAccessibleRole() {
        return me.piitex.engine.access.AccessibleRole.RADIO_BUTTON;
    }

    @Override
    public Boolean getAccessibleChecked() {
        return selected;
    }

    /**
     * Selects the button from the keyboard: Tab to it, then Space or Enter.
     */
    @Override
    public void activate() {
        select();
    }

    @Override
    protected boolean usesFocusRing() {
        return true;
    }
}
