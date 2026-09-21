package me.piitex.engine.ui.containers;

import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;

/**
 * The visual settings for a {@link TabContainer}'s tab strip: tab colors, sizing, spacing, and the selection
 * indicator. Like {@link me.piitex.engine.ui.scroll.ScrollBarStyle}, this is a mutable value object, so set
 * what should change and leave the rest at the defaults.
 * <p>
 * Colors follow the usual {@link Overridable} rule: they start out following the active {@link Theme}, and a
 * color you set explicitly is pinned against every later theme switch. Colors are plain {@link Color}s, not
 * {@link me.piitex.engine.ui.color.Paint}s.
 */
public class TabStyle {
    private float fontSize = 14f;
    private float tabHeight = 0f;
    private float verticalPadding = 9f;
    private float horizontalPadding = 14f;
    private float minTabWidth = 72f;
    private float maxTabWidth = 220f;
    private float spacing = 2f;
    private float cornerRadius = 4f;
    private float indicatorThickness = 2f;
    private float closeButtonSize = 14f;
    private float scrollButtonWidth = 24f;

    private final Overridable<Color> tabColor = new Overridable<>(Color.TRANSPARENT);
    private final Overridable<Color> hoverColor = new Overridable<>(Color.TRANSPARENT);
    private final Overridable<Color> selectedColor = new Overridable<>(Color.TRANSPARENT);
    private final Overridable<Color> textColor = new Overridable<>(new Color(0.5f, 0.5f, 0.5f, 1f));
    private final Overridable<Color> selectedTextColor = new Overridable<>(Color.BLACK);
    private final Overridable<Color> indicatorColor = new Overridable<>(Color.BLUE);
    private final Overridable<Color> dividerColor = new Overridable<>(new Color(0.5f, 0.5f, 0.5f, 1f));

    /** Point size of the tab titles. */
    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = Math.max(1f, fontSize);
    }

    /**
     * Height of the tab strip. 0 (the default) means automatic: the title's line height plus {@link
     * #getVerticalPadding()} above and below, so the strip follows the font size.
     */
    public float getTabHeight() {
        return tabHeight;
    }

    public void setTabHeight(float tabHeight) {
        this.tabHeight = Math.max(0f, tabHeight);
    }

    /** Space above and below a title when {@link #getTabHeight()} is automatic. */
    public float getVerticalPadding() {
        return verticalPadding;
    }

    public void setVerticalPadding(float verticalPadding) {
        this.verticalPadding = Math.max(0f, verticalPadding);
    }

    /** Space between a tab's edge and its title (and its close button). */
    public float getHorizontalPadding() {
        return horizontalPadding;
    }

    public void setHorizontalPadding(float horizontalPadding) {
        this.horizontalPadding = Math.max(0f, horizontalPadding);
    }

    /** Tabs are never squeezed narrower than this when the strip runs out of room; past that the strip scrolls. */
    public float getMinTabWidth() {
        return minTabWidth;
    }

    public void setMinTabWidth(float minTabWidth) {
        this.minTabWidth = Math.max(0f, minTabWidth);
    }

    /** A tab never grows wider than this, however long its title is; longer titles are cut with an ellipsis. */
    public float getMaxTabWidth() {
        return maxTabWidth;
    }

    public void setMaxTabWidth(float maxTabWidth) {
        this.maxTabWidth = Math.max(0f, maxTabWidth);
    }

    /** Gap between neighbouring tabs. */
    public float getSpacing() {
        return spacing;
    }

    public void setSpacing(float spacing) {
        this.spacing = Math.max(0f, spacing);
    }

    /** Corner radius of a tab's hover/selected fill. */
    public float getCornerRadius() {
        return cornerRadius;
    }

    public void setCornerRadius(float cornerRadius) {
        this.cornerRadius = Math.max(0f, cornerRadius);
    }

    /** Thickness of the bar under (or over, for a bottom strip) the selected tab. 0 hides it. */
    public float getIndicatorThickness() {
        return indicatorThickness;
    }

    public void setIndicatorThickness(float indicatorThickness) {
        this.indicatorThickness = Math.max(0f, indicatorThickness);
    }

    /** Side length of the square hit area of a closable tab's close button; the drawn cross is smaller. */
    public float getCloseButtonSize() {
        return closeButtonSize;
    }

    public void setCloseButtonSize(float closeButtonSize) {
        this.closeButtonSize = Math.max(0f, closeButtonSize);
    }

    /** Width of each of the two arrow buttons that appear at the ends of the strip when the tabs do not fit. */
    public float getScrollButtonWidth() {
        return scrollButtonWidth;
    }

    public void setScrollButtonWidth(float scrollButtonWidth) {
        this.scrollButtonWidth = Math.max(0f, scrollButtonWidth);
    }

    /** Fill of an idle, unselected tab. */
    public Color getTabColor() {
        return tabColor.get();
    }

    public void setTabColor(Color color) {
        tabColor.set(color);
    }

    /** Fill of an unselected tab while the cursor is over it. */
    public Color getHoverColor() {
        return hoverColor.get();
    }

    public void setHoverColor(Color color) {
        hoverColor.set(color);
    }

    /** Fill of the selected tab. */
    public Color getSelectedColor() {
        return selectedColor.get();
    }

    public void setSelectedColor(Color color) {
        selectedColor.set(color);
    }

    /** Title color of unselected tabs. Follows the theme's text color, same as the selected tab's, so only the fill changes on selection. */
    public Color getTextColor() {
        return textColor.get();
    }

    public void setTextColor(Color color) {
        textColor.set(color);
    }

    /** Title color of the selected tab. */
    public Color getSelectedTextColor() {
        return selectedTextColor.get();
    }

    public void setSelectedTextColor(Color color) {
        selectedTextColor.set(color);
    }

    /** Color of the bar marking the selected tab, and of the focus outline around it. */
    public Color getIndicatorColor() {
        return indicatorColor.get();
    }

    public void setIndicatorColor(Color color) {
        indicatorColor.set(color);
    }

    /** Color of the thin line separating the strip from the content. */
    public Color getDividerColor() {
        return dividerColor.get();
    }

    public void setDividerColor(Color color) {
        dividerColor.set(color);
    }

    /** Applies {@code theme} to every color that hasn't been set explicitly. See {@link Overridable}. */
    void applyTheme(Theme theme) {
        Color selection = theme.getSelection();
        tabColor.applyTheme(Color.TRANSPARENT);
        hoverColor.applyTheme(theme.getFieldHoverBackground());
        selectedColor.applyTheme(theme.getButtonBackground());
        textColor.applyTheme(theme.getText());
        selectedTextColor.applyTheme(theme.getText());
        // The theme's selection color is translucent (it tints text); the indicator wants it solid.
        indicatorColor.applyTheme(new Color(selection.getR(), selection.getG(), selection.getB(), 1f));
        dividerColor.applyTheme(theme.getContainerBorder());
    }
}
