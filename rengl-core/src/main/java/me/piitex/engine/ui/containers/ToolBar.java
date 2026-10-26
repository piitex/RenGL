package me.piitex.engine.ui.containers;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.layout.HorizontalLayout;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.SeparatorOverlay;
import me.piitex.engine.ui.overlays.SpacerOverlay;

/**
 * A row of tool buttons under a menu bar: icon buttons for the common actions, thin dividers between groups, and any other
 * widget (a {@link me.piitex.engine.ui.overlays.DropdownOverlay}, a {@link me.piitex.engine.ui.overlays.SliderOverlay}, a
 * search box) in the same row.
 * <pre>{@code
 * ToolBar bar = new ToolBar(800);
 * bar.addButton(Icons.OPEN, "Open (Ctrl+O)", this::open);
 * bar.addButton(Icons.SAVE, "Save (Ctrl+S)", this::save);
 * bar.addSeparator();
 * bar.addButton("Build", this::build);
 * bar.addSpacer();                       // everything after this sits at the right end
 * bar.addElement(searchField);
 * }</pre>
 * It is a {@link HorizontalLayout}, so everything a layout does (padding, spacing, alignment, {@link
 * Element#setLayoutWeight weights}) applies, and it follows the theme like a {@link Container} that {@link #useTheme() uses
 * the theme}. The buttons it makes are flat: no box until the pointer is over them, with the label or icon centred. The
 * button is returned so it can be disabled, given a different tooltip, or restyled.
 */
public class ToolBar extends HorizontalLayout {
    private float buttonSize = 32f;

    public ToolBar(double width) {
        this(width, 40);
    }

    public ToolBar(double width, double height) {
        super(width, height);
        setPadding(4f);
        setSpacing(4f);
        setAlignment(Layout.Alignment.CENTER_LEFT);
        useTheme();
    }

    public float getButtonSize() {
        return buttonSize;
    }

    /**
     * The width and height of the icon buttons made after this. Default 32.
     */
    public void setButtonSize(float buttonSize) {
        this.buttonSize = Math.max(8f, buttonSize);
    }

    /**
     * An icon button; {@code tooltip} says what it does (null for none).
     */
    public ButtonOverlay addButton(IconAsset icon, String tooltip, Runnable action) {
        ButtonOverlay button = flat(new ButtonOverlay("", 14f, Color.BLACK, buttonSize, buttonSize));
        button.setIcon(icon);
        button.setIconSize(buttonSize * 0.55f);
        if (tooltip != null) button.setTooltip(tooltip);
        button.onAction(action);
        addElement(button);
        return button;
    }

    /**
     * A text button as wide as its label needs.
     */
    public ButtonOverlay addButton(String text, Runnable action) {
        ButtonOverlay button = flat(new ButtonOverlay(text, 15f, Color.BLACK, Math.max(buttonSize, text.length() * 9 + 20), buttonSize));
        button.onAction(action);
        addElement(button);
        return button;
    }

    /**
     * A button with an icon beside its label, such as "Run".
     */
    public ButtonOverlay addIconTextButton(IconAsset icon, String text, Runnable action) {
        ButtonOverlay button = addButton(text, action);
        button.setIcon(icon);
        button.setIconSize(buttonSize * 0.5f);
        button.setWidth(button.getWidth() + buttonSize * 0.5f + 6f);
        return button;
    }

    /**
     * A thin vertical divider, to set one group of buttons off from the next.
     */
    public SeparatorOverlay addSeparator() {
        SeparatorOverlay separator = new SeparatorOverlay(buttonSize * 0.7f, SeparatorOverlay.Orientation.VERTICAL);
        addElement(separator);
        return separator;
    }

    /**
     * Flexible space: what is added after it is pushed to the far end of the bar.
     */
    public SpacerOverlay addSpacer() {
        SpacerOverlay spacer = new SpacerOverlay();
        addElement(spacer);
        return spacer;
    }

    private static ButtonOverlay flat(ButtonOverlay button) {
        button.getStyling().setBackgroundColor(Color.TRANSPARENT);
        button.getStyling().setHoverColor(new Color(0.5f, 0.5f, 0.5f, 0.22f));
        button.getStyling().setBorderThickness(0f);
        button.getStyling().setCornerRadius(5f);
        return button;
    }
}
