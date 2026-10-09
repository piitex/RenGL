package me.piitex.engine.ui.containers;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.events.MouseClickEvent;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.overlays.Overlay;
import me.piitex.engine.ui.scroll.Scrollable;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.text.FontMetricsCompat;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A strip of tabs above (or below) a content area that shows the content of the selected tab. Each tab pairs a
 * title with one {@link Element}, usually a {@link Layout} or a {@link me.piitex.engine.ui.scroll.ScrollContainer}.
 * <pre>{@code
 * TabContainer tabs = new TabContainer(20, 20, 600, 400);
 * tabs.addTab("General", generalPanel);
 * tabs.addTab("Advanced", advancedPanel).setClosable(true);
 * tabs.onTabSelected(tab -> System.out.println("Now showing " + tab.getTitle()));
 * root.addElement(tabs);
 * }</pre>
 * <b>Content.</b> Every tab's content is a child of this container, resized to fill the area under the strip
 * every frame (inside {@link #getPadding()}), so anything that lays itself out gets the room automatically. Only
 * the selected tab's content is enabled; the rest are disabled, which keeps their state (scroll position, typed
 * text) but stops them drawing, updating and receiving input. Do not enable or disable a tab's content yourself,
 * select the tab instead. Use {@link #addTab} and {@link #removeTab} rather than {@link #addElement}.
 * <p>
 * <b>Selecting.</b> The user picks a tab with the mouse, or with Left/Right/Home/End while the strip has keyboard
 * focus (Tab reaches it like any other focusable element). Code uses {@link #selectTab}. The first tab added is
 * selected automatically, and {@link #onTabSelected} fires for every change of selection, wherever it came from.
 * A {@link Tab#setEnabled(boolean) disabled} tab cannot be picked by the user.
 * <p>
 * <b>Closing.</b> A {@link Tab#setClosable(boolean) closable} tab shows a close button, and closes on a
 * middle-click. Both go through {@link #onTabCloseRequested}, which can veto the close, and then {@link
 * #onTabClosed}. {@link #removeTab} bypasses both, since it is the code asking.
 * <p>
 * <b>Overflow.</b> When the tabs do not fit, they shrink down to {@link TabStyle#getMinTabWidth()}, with long
 * titles cut short by an ellipsis (the full title shows as a tooltip). If they still do not fit, an arrow button
 * appears at each end of the strip, and the tabs scroll between them (the arrows, or the mouse wheel), following
 * the selection. The arrows take their own room, so no tab is left half hidden behind the edge.
 * <p>
 * <b>Styling.</b> This container's own {@link #getStyling()} is the panel behind both strip and content and is
 * themed like any {@link Container#useTheme() themed container}. The tab strip is styled through {@link
 * #getTabStyle()}. {@link #getPadding()} insets the content area only; the strip always spans the full width.
 */
public class TabContainer extends Layout {

    /**
     * Which edge of the container the tab strip sits on.
     */
    public enum TabPosition {
        TOP, BOTTOM
    }

    /**
     * One tab: its title and content, and whether the user can close or select it. Created by {@link
     * TabContainer#addTab}; the title, closable, enabled and tooltip properties can be changed at any time.
     */
    public static final class Tab {
        private TabContainer container;
        private final Element content;
        private String title;
        private String tooltip;
        private boolean closable;
        private boolean enabled = true;

        // Filled in by the strip's layout: the tab's position within the (unscrolled) strip.
        private float naturalWidth = -1f;
        private double x, width;

        private Tab(TabContainer container, String title, Element content) {
            this.container = container;
            this.title = title;
            this.content = content;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title == null ? "" : title;
            changed();
        }

        /**
         * The element shown while this tab is selected.
         */
        public Element getContent() {
            return content;
        }

        public boolean isClosable() {
            return closable;
        }

        /**
         * Whether the tab shows a close button. False by default.
         */
        public void setClosable(boolean closable) {
            this.closable = closable;
            changed();
        }

        public boolean isEnabled() {
            return enabled;
        }

        /**
         * A disabled tab is drawn dimmed and cannot be selected by the user. Code can still select it.
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTooltip() {
            return tooltip;
        }

        /**
         * Text shown when the cursor rests on the tab. Null (the default) shows the full title, but only when it had to be cut short.
         */
        public void setTooltip(String tooltip) {
            this.tooltip = tooltip;
        }

        /**
         * The container this tab belongs to, or null once it has been removed.
         */
        public TabContainer getContainer() {
            return container;
        }

        public boolean isSelected() {
            return container != null && container.selected == this;
        }

        private void changed() {
            naturalWidth = -1f;
            if (container != null) container.tabsChanged();
        }
    }

    private static final double WHEEL_STEP = 40;

    private final List<Tab> tabs = new ArrayList<>();
    private final TabStyle style = new TabStyle();
    private final TabBar bar = new TabBar();
    private Tab selected;
    private TabPosition position = TabPosition.TOP;

    private Consumer<Tab> onTabSelected;
    private Predicate<Tab> onTabCloseRequested;
    private Consumer<Tab> onTabClosed;

    public TabContainer(double width, double height) {
        this(0, 0, width, height);
    }

    public TabContainer(double x, double y, double width, double height) {
        super(x, y, width, height);
        super.addElement(bar, 0);
        useTheme();
    }

    /**
     * Adds a tab at the end. The first tab added becomes the selected one.
     *
     * @throws IllegalArgumentException if {@code content} already belongs to a tab
     */
    public Tab addTab(String title, Element content) {
        return addTab(tabs.size(), title, content);
    }

    /**
     * As {@link #addTab(String, Element)}, inserting the tab at {@code index} (0 = leftmost).
     */
    public Tab addTab(int index, String title, Element content) {
        Objects.requireNonNull(content, "content");
        if (index < 0 || index > tabs.size()) {
            throw new IndexOutOfBoundsException("index " + index + ", tab count " + tabs.size());
        }
        if (tabFor(content) != null) {
            throw new IllegalArgumentException("That element is already the content of a tab");
        }

        Tab tab = new Tab(this, title == null ? "" : title, content);
        tabs.add(index, tab);
        content.setEnabled(false);
        if (content instanceof Container container) {
            container.setClipping(true); // it must not draw over the strip while it is being squeezed
        }
        super.addElement(content); // stores it and relayouts
        tabsChanged();

        if (selected == null) {
            select(tab);
        }
        return tab;
    }

    /**
     * Removes {@code tab} and its content, which is left enabled so it can be used elsewhere. If it was selected,
     * the tab that takes its place (the next one, else the previous) is selected. Does not consult {@link
     * #onTabCloseRequested} or fire {@link #onTabClosed}; see {@link #closeTab} for that. Returns false if the tab
     * is not part of this container.
     */
    public boolean removeTab(Tab tab) {
        if (tab == null || tab.container != this) return false;
        detach(tab);
        return true;
    }

    /**
     * As {@link #removeTab(Tab)}, by position. Returns the removed tab.
     */
    public Tab removeTab(int index) {
        Tab tab = tabs.get(index);
        detach(tab);
        return tab;
    }

    /**
     * Closes {@code tab} the way the user would: {@link #onTabCloseRequested} may refuse, and {@link #onTabClosed}
     * fires afterwards. Returns whether the tab was closed. Works on tabs that are not {@link Tab#isClosable()}.
     */
    public boolean closeTab(Tab tab) {
        if (tab == null || tab.container != this) return false;
        if (onTabCloseRequested != null && !onTabCloseRequested.test(tab)) return false;
        detach(tab);
        if (onTabClosed != null) onTabClosed.accept(tab);
        return true;
    }

    /**
     * The tabs in display order. A read-only view of the live list.
     */
    public List<Tab> getTabs() {
        return Collections.unmodifiableList(tabs);
    }

    public int getTabCount() {
        return tabs.size();
    }

    /**
     * The selected tab, or null while there are no tabs.
     */
    public Tab getSelectedTab() {
        return selected;
    }

    /**
     * Position of the selected tab, or -1 while there are no tabs.
     */
    public int getSelectedIndex() {
        return selected == null ? -1 : tabs.indexOf(selected);
    }

    /**
     * Selects {@code tab}, firing {@link #onTabSelected} if that is a change. Ignores tabs of other containers.
     */
    public void selectTab(Tab tab) {
        if (tab != null && tab.container == this) select(tab);
    }

    /**
     * As {@link #selectTab(Tab)}, by position.
     */
    public void selectTab(int index) {
        select(tabs.get(index));
    }

    public TabPosition getTabPosition() {
        return position;
    }

    /**
     * Puts the strip above ({@link TabPosition#TOP}, the default) or below the content.
     */
    public void setTabPosition(TabPosition position) {
        this.position = position == null ? TabPosition.TOP : position;
        relayout();
    }

    /**
     * The colors and sizes of the tab strip. Mutate it in place.
     */
    public TabStyle getTabStyle() {
        return style;
    }

    /**
     * Registers the callback fired with the tab that has just become selected. Only one is held at a time.
     */
    public void onTabSelected(Consumer<Tab> onTabSelected) {
        this.onTabSelected = onTabSelected;
    }

    /**
     * Registers a filter for user-initiated closes (close button, middle-click, {@link #closeTab}): return false to
     * keep the tab open, e.g. while a confirmation dialog is up, and call {@link #removeTab} later if the user
     * agrees. Only one is held at a time.
     */
    public void onTabCloseRequested(Predicate<Tab> onTabCloseRequested) {
        this.onTabCloseRequested = onTabCloseRequested;
    }

    /**
     * Registers the callback fired with a tab after it was closed (see {@link #closeTab}). Only one is held at a time.
     */
    public void onTabClosed(Consumer<Tab> onTabClosed) {
        this.onTabClosed = onTabClosed;
    }

    /**
     * Tabs own their content; use {@link #addTab}.
     */
    @Override
    public void addElement(Element element) {
        throw new UnsupportedOperationException("Use addTab");
    }

    @Override
    public void addElement(Element element, int index) {
        throw new UnsupportedOperationException("Use addTab");
    }

    @Override
    public void insertElement(Element element, int index) {
        throw new UnsupportedOperationException("Use addTab");
    }

    /**
     * Removing a tab's content removes the tab. The tab strip itself cannot be removed.
     */
    @Override
    public Element removeElement(int index) {
        Element element = getElements().get(index);
        if (element == bar) {
            throw new UnsupportedOperationException("The tab strip cannot be removed");
        }
        Tab tab = tabFor(element);
        if (tab != null) {
            detach(tab);
            return element;
        }
        return super.removeElement(index);
    }

    @Override
    public void applyTheme(Theme theme) {
        super.applyTheme(theme);
        style.applyTheme(theme);
    }

    @Override
    public void followTheme() {
        super.followTheme();
        style.followTheme();
    }

    @Override
    protected void layoutChildren() {
        double border = getStyling().getBorderThickness();
        double innerWidth = Math.max(0, getWidth() - 2 * border);
        double innerHeight = Math.max(0, getHeight() - 2 * border);
        double barHeight = Math.min(bar.preferredHeight(), innerHeight);
        boolean top = position == TabPosition.TOP;

        place(bar, border, top ? border : border + innerHeight - barHeight, innerWidth, barHeight);

        double pad = getPadding();
        double contentX = border + pad;
        double contentY = border + (top ? barHeight : 0) + pad;
        double contentWidth = Math.max(0, innerWidth - 2 * pad);
        double contentHeight = Math.max(0, innerHeight - barHeight - 2 * pad);
        for (Tab tab : tabs) {
            place(tab.content, contentX, contentY, contentWidth, contentHeight);
        }

        bar.layout();
    }

    private static void place(Element element, double x, double y, double width, double height) {
        element.setBounds(x, y, width, height);
    }

    private Tab tabFor(Element content) {
        for (Tab tab : tabs) {
            if (tab.content == content) return tab;
        }
        return null;
    }

    private void tabsChanged() {
        bar.layout();
    }

    private void select(Tab tab) {
        if (tab == selected) return;

        if (selected != null) {
            selected.content.setEnabled(false);
            releaseFocusWithin(selected.content);
        }
        selected = tab;
        if (tab != null) {
            tab.content.setEnabled(true);
        }
        bar.revealSelected = true;
        bar.layout();

        if (tab != null && onTabSelected != null) {
            onTabSelected.accept(tab);
        }
    }

    private void detach(Tab tab) {
        int index = tabs.indexOf(tab);
        tabs.remove(index);
        releaseFocusWithin(tab.content);
        super.removeElement(tab.content); // no longer a tab, so this reaches Layout's removal
        tab.content.setEnabled(true);
        tab.container = null;

        if (tab == selected) {
            selected = null;
            select(neighbour(index));
        }
        tabsChanged();
    }

    /**
     * The tab to fall back on when the one at {@code index} was just removed: the next enabled one, else the previous.
     */
    private Tab neighbour(int index) {
        for (int i = index; i < tabs.size(); i++) {
            if (tabs.get(i).enabled) return tabs.get(i);
        }
        for (int i = Math.min(index, tabs.size()) - 1; i >= 0; i--) {
            if (tabs.get(i).enabled) return tabs.get(i);
        }
        return null;
    }

    /**
     * Clears keyboard focus if it is on {@code content} or something inside it, which is about to stop being reachable.
     */
    private void releaseFocusWithin(Element content) {
        Window window = getWindow();
        if (window == null) return;
        for (Element e = window.getFocusedElement(); e != null; e = e.owner) {
            if (e == content) {
                window.requestFocus(null);
                return;
            }
        }
    }

    /**
     * Selects the nearest enabled tab in {@code direction} (-1 or 1) from the selection. No wrap-around.
     */
    private void selectRelative(int direction) {
        int from = getSelectedIndex();
        for (int i = from + direction; i >= 0 && i < tabs.size(); i += direction) {
            if (tabs.get(i).enabled) {
                select(tabs.get(i));
                return;
            }
        }
    }

    /**
     * Selects the first (or last) enabled tab.
     */
    private void selectEdge(boolean last) {
        for (int n = 0; n < tabs.size(); n++) {
            Tab tab = tabs.get(last ? tabs.size() - 1 - n : n);
            if (tab.enabled) {
                select(tab);
                return;
            }
        }
    }

    /**
     * The tab strip: draws the tabs, and turns clicks, wheel and key presses into selection and closing. A child of
     * the container like any other, so hit-testing, hover, focus, cursor and tooltip need nothing special.
     */
    private final class TabBar extends Overlay implements Focusable, Scrollable, FontScalable {
        private static final float CLOSE_GAP = 6f;
        private static final float CLOSE_SLOP = 3f;

        private org.jetbrains.skia.Font font;
        private Typeface fontTypeface;
        private float fontSize;
        private double scrollOffset;
        private double maxScroll;
        // The part of the strip the tabs show in. The whole width, unless the tabs overflow and two arrow buttons take the ends.
        private boolean overflowing;
        private double viewportLeft;
        private double viewportWidth;
        private boolean revealSelected;
        private boolean focused;

        TabBar() {
            // Transparent as a baseline: the strip draws its own tabs. Non-pinning, like the other composites.
            getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
            getStyling().applyThemeHoverColor(Color.TRANSPARENT);
            getStyling().applyThemeBorderThickness(0f);
            setFocusTraversable(true);
            onMouseClick(this::handleClick);
        }

        // ----- geometry --------------------------------------------------------------------------

        private org.jetbrains.skia.Font font() {
            Typeface typeface = Font.getDefault();
            float size = style.getFontSize();
            if (font == null || typeface != fontTypeface || size != fontSize) {
                if (font != null) font.close();
                font = new org.jetbrains.skia.Font(typeface, size);
                fontTypeface = typeface;
                fontSize = size;
                for (Tab tab : tabs) tab.naturalWidth = -1f;
            }
            return font;
        }

        private float lineHeight() {
            FontMetricsCompat metrics = FontMetricsCompat.of(font());
            return -metrics.getAscent() + metrics.getDescent();
        }

        double preferredHeight() {
            if (style.getTabHeight() > 0) return style.getTabHeight();
            return Math.ceil(lineHeight() + 2 * style.getVerticalPadding());
        }

        /**
         * Space between a tab's right edge and its title: the padding, or room for the close button.
         */
        private float rightInset(Tab tab) {
            float padding = style.getHorizontalPadding();
            return tab.closable ? padding / 2 + style.getCloseButtonSize() + CLOSE_GAP : padding;
        }

        private float naturalWidth(Tab tab) {
            if (tab.naturalWidth < 0) {
                float wanted = style.getHorizontalPadding() + font().measureTextWidth(tab.title) + rightInset(tab);
                tab.naturalWidth = Math.max(style.getMinTabWidth(), Math.min(style.getMaxTabWidth(), wanted));
            }
            return tab.naturalWidth;
        }

        /**
         * Positions every tab within the strip, squeezing them to fit if needed, and keeps the scroll offset valid.
         */
        void layout() {
            double available = getWidth();
            double gaps = tabs.isEmpty() ? 0 : style.getSpacing() * (tabs.size() - 1);
            double natural = 0;
            for (Tab tab : tabs) natural += naturalWidth(tab);
            double scale = natural + gaps > available && natural > 0 ? Math.max(0, available - gaps) / natural : 1;

            double x = 0;
            for (Tab tab : tabs) {
                double width = naturalWidth(tab);
                if (scale < 1) {
                    width = Math.max(Math.min(width, style.getMinTabWidth()), width * scale);
                }
                tab.x = x;
                tab.width = width;
                x += width + style.getSpacing();
            }
            double total = tabs.isEmpty() ? 0 : x - style.getSpacing();
            overflowing = total > available + 0.5;
            viewportLeft = overflowing ? style.getScrollButtonWidth() : 0;
            viewportWidth = Math.max(0, overflowing ? available - 2 * style.getScrollButtonWidth() : available);
            maxScroll = Math.max(0, total - viewportWidth);

            if (revealSelected && selected != null && viewportWidth > 0) {
                if (selected.x < scrollOffset) {
                    scrollOffset = selected.x;
                } else if (selected.x + selected.width > scrollOffset + viewportWidth) {
                    scrollOffset = selected.x + selected.width - viewportWidth;
                }
                revealSelected = false;
            }
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        }

        /**
         * The tab under window x {@code px}, or -1. Points over the arrow buttons or outside the strip hit no tab.
         */
        private int tabAt(double px) {
            double local = px - getAbsoluteX();
            if (local < viewportLeft || local >= viewportLeft + viewportWidth) return -1;
            double strip = local - viewportLeft + scrollOffset;
            for (int i = 0; i < tabs.size(); i++) {
                Tab tab = tabs.get(i);
                if (strip >= tab.x && strip < tab.x + tab.width) return i;
            }
            return -1;
        }

        private boolean onCloseButton(Tab tab, double px) {
            if (!tab.closable) return false;
            double strip = px - getAbsoluteX() - viewportLeft + scrollOffset;
            double closeLeft = tab.x + tab.width - style.getHorizontalPadding() / 2 - style.getCloseButtonSize();
            return strip >= closeLeft - CLOSE_SLOP;
        }

        /**
         * -1 or 1 if window x {@code px} is over the left or right arrow button, else 0.
         */
        private int arrowAt(double px) {
            if (!overflowing) return 0;
            double local = px - getAbsoluteX();
            if (local >= 0 && local < viewportLeft) return -1;
            if (local >= viewportLeft + viewportWidth && local <= getWidth()) return 1;
            return 0;
        }

        private boolean canScroll(int direction) {
            return direction < 0 ? scrollOffset > 0.5 : scrollOffset < maxScroll - 0.5;
        }

        private void scrollByStep(int direction) {
            double step = style.getMinTabWidth() + style.getSpacing();
            scrollOffset = Math.max(0, Math.min(scrollOffset + direction * step, maxScroll));
        }

        private int hoveredArrow() {
            if (!isHovered()) return 0;
            double px = MouseTracker.getX(), py = MouseTracker.getY();
            return contains(px, py) ? arrowAt(px) : 0;
        }

        private int hoveredIndex() {
            if (!isHovered()) return -1;
            double px = MouseTracker.getX(), py = MouseTracker.getY();
            return contains(px, py) ? tabAt(px) : -1;
        }

        // ----- input -----------------------------------------------------------------------------

        private void handleClick(MouseClickEvent event) {
            int arrow = arrowAt(event.getMouseX());
            if (arrow != 0) {
                if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) scrollByStep(arrow);
                return;
            }
            int index = tabAt(event.getMouseX());
            if (index < 0) return;
            Tab tab = tabs.get(index);

            if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                if (tab.closable) closeTab(tab);
            } else if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (onCloseButton(tab, event.getMouseX())) {
                    closeTab(tab);
                } else if (tab.enabled) {
                    select(tab);
                }
            }
        }

        @Override
        public void update(float delta) {
            super.update(delta);
            style.advance(delta);
        }

        @Override
        public boolean canScroll(double deltaX, double deltaY) {
            double delta = Math.abs(deltaX) > Math.abs(deltaY) ? deltaX : deltaY;
            if (delta == 0) return false;
            return delta > 0 ? scrollOffset > 0.5 : scrollOffset < maxScroll - 0.5;
        }

        @Override
        public void onScroll(double deltaX, double deltaY) {
            double delta = Math.abs(deltaX) > Math.abs(deltaY) ? deltaX : deltaY;
            scrollOffset = Math.max(0, Math.min(scrollOffset - delta * WHEEL_STEP, maxScroll));
        }

        @Override
        public void onFocusGained() {
            focused = true;
        }

        @Override
        public void onFocusLost() {
            focused = false;
        }

        @Override
        public void onCharTyped(int codepoint) {
        }

        @Override
        public void onKeyPressed(int key, int action, int mods) {
            switch (key) {
                case GLFW.GLFW_KEY_LEFT -> selectRelative(-1);
                case GLFW.GLFW_KEY_RIGHT -> selectRelative(1);
                case GLFW.GLFW_KEY_HOME -> selectEdge(false);
                case GLFW.GLFW_KEY_END -> selectEdge(true);
                default -> {
                }
            }
        }

        @Override
        public CursorMode getCursorMode() {
            int arrow = hoveredArrow();
            if (arrow != 0) return canScroll(arrow) ? CursorMode.POINTER : null;
            int index = hoveredIndex();
            return index >= 0 && tabs.get(index).enabled ? CursorMode.POINTER : null;
        }

        @Override
        public String getTooltip() {
            int index = hoveredIndex();
            if (index < 0) return null;
            Tab tab = tabs.get(index);
            if (tab.tooltip != null) return tab.tooltip;
            return font().measureTextWidth(tab.title) > titleWidth(tab) ? tab.title : null;
        }

        // ----- font scaling (see ResizableContainer) ---------------------------------------------

        @Override
        public float[] getFontSizes() {
            return new float[]{style.getFontSize()};
        }

        @Override
        public void setFontSizes(float[] sizes) {
            style.setFontSize(sizes[0]);
        }

        // ----- drawing ---------------------------------------------------------------------------

        private float titleWidth(Tab tab) {
            return (float) tab.width - style.getHorizontalPadding() - rightInset(tab);
        }

        @Override
        public void render(Renderer renderer) {
            if (!isEnabled()) return;
            super.render(renderer);

            float left = (float) getX(), top = (float) getY();
            float width = (float) getWidth(), height = (float) getHeight();
            boolean atTop = position == TabPosition.TOP;
            float radius = style.getCornerRadius();
            float indicator = style.getIndicatorThickness();
            int hovered = hoveredIndex();
            org.jetbrains.skia.Font font = font();
            float textY = top + (height - lineHeight()) / 2f;
            float stripLeft = left + (float) viewportLeft, stripWidth = (float) viewportWidth;

            renderer.pushClip(left, top, width, height);
            renderer.pushClip(stripLeft, top, stripWidth, height);

            // Fills first, so the divider and the selected tab's indicator draw over them.
            for (int i = 0; i < tabs.size(); i++) {
                Tab tab = tabs.get(i);
                float tabX = stripLeft + (float) (tab.x - scrollOffset);
                if (tabX + tab.width < stripLeft || tabX > stripLeft + stripWidth) continue;

                Paint fill = tab == selected ? style.getSelectedColor()
                        : i == hovered && tab.enabled ? style.getHoverColor() : style.getTabColor();
                renderer.drawQuad(tabX, top, (float) tab.width, height, fill, radius);
            }

            renderer.popClip();
            renderer.drawQuad(left, atTop ? top + height - 1f : top, width, 1f, style.getDividerColor(), 0f);
            renderer.pushClip(stripLeft, top, stripWidth, height);

            for (int i = 0; i < tabs.size(); i++) {
                Tab tab = tabs.get(i);
                float tabX = stripLeft + (float) (tab.x - scrollOffset);
                float tabWidth = (float) tab.width;
                if (tabX + tabWidth < stripLeft || tabX > stripLeft + stripWidth) continue;

                boolean isSelected = tab == selected;
                Paint textColor = isSelected ? style.getSelectedTextColor() : style.getTextColor();
                if (!tab.enabled) renderer.pushAlpha(0.4f);

                if (isSelected) {
                    if (indicator > 0) {
                        renderer.drawQuad(tabX, atTop ? top + height - indicator : top, tabWidth, indicator,
                                style.getIndicatorColor(), 0f);
                    }
                    if (focused) {
                        renderer.drawBorder(tabX, top, tabWidth, height, style.getIndicatorColor(), 1f, radius);
                    }
                }

                renderer.drawText(fit(tab.title, font, titleWidth(tab)),
                        tabX + style.getHorizontalPadding(), textY, font, textColor);

                if (tab.closable) {
                    drawCloseButton(renderer, tab, tabX, top, height, textColor, i == hovered);
                }
                if (!tab.enabled) renderer.popAlpha();
            }

            renderer.popClip();
            if (overflowing) {
                drawArrow(renderer, -1, left, top, height);
                drawArrow(renderer, 1, left + (float) (viewportLeft + viewportWidth), top, height);
            }
            renderer.popClip();
        }

        /**
         * One of the two scroll buttons: a chevron, dimmed when there is nothing more to scroll to that way.
         */
        private void drawArrow(Renderer renderer, int direction, float x, float top, float height) {
            float width = style.getScrollButtonWidth();
            boolean enabled = canScroll(direction);
            if (enabled && hoveredArrow() == direction) {
                renderer.drawQuad(x, top, width, height, style.getHoverColor(), style.getCornerRadius());
            }

            Paint color = style.getTextColor();
            if (!enabled) renderer.pushAlpha(0.35f);
            float reach = 5f;
            float centerX = x + width / 2f, centerY = top + height / 2f;
            float tip = centerX + direction * reach * 0.5f, back = centerX - direction * reach * 0.5f;
            renderer.drawLine(back, centerY - reach, tip, centerY, color, 1.5f);
            renderer.drawLine(tip, centerY, back, centerY + reach, color, 1.5f);
            if (!enabled) renderer.popAlpha();
        }

        private void drawCloseButton(Renderer renderer, Tab tab, float tabX, float top, float height, Paint color, boolean tabHovered) {
            float size = style.getCloseButtonSize();
            float x = tabX + (float) tab.width - style.getHorizontalPadding() / 2f - size;
            float y = top + (height - size) / 2f;

            if (tabHovered && onCloseButton(tab, MouseTracker.getX())) {
                renderer.pushAlpha(0.2f);
                renderer.drawQuad(x, y, size, size, color, 3f);
                renderer.popAlpha();
            }
            float reach = size * 0.25f;
            float centerX = x + size / 2f, centerY = y + size / 2f;
            renderer.drawLine(centerX - reach, centerY - reach, centerX + reach, centerY + reach, color, 1.5f);
            renderer.drawLine(centerX - reach, centerY + reach, centerX + reach, centerY - reach, color, 1.5f);
        }

        /**
         * {@code text}, cut short with an ellipsis if it is wider than {@code maxWidth}.
         */
        private static String fit(String text, org.jetbrains.skia.Font font, float maxWidth) {
            return me.piitex.engine.ui.text.TextFit.ellipsize(text, font, maxWidth);
        }
    }
}
