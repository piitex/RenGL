package me.piitex.engine.ui.containers;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.ui.text.TextFit;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A stack of sections, each a clickable title bar over a body that folds away: the collapsible groups of a settings page,
 * the "Advanced" fold under a form, a sidebar of expandable panels.
 * <pre>{@code
 * AccordionContainer accordion = new AccordionContainer(320);
 * accordion.addSection("Graphics", graphicsPanel);
 * accordion.addSection("Audio", audioPanel);
 * accordion.addSection("Controls", controlsPanel, false);
 * accordion.setExclusive(true); // opening one closes the others
 * }</pre>
 * The body of a section is any {@link Element}, usually a {@link me.piitex.engine.ui.layout.Layout}. It is set to the
 * accordion's width (less the {@link #setContentPadding padding}) and keeps its own height, which may change while the
 * section is open. The accordion's height follows its sections: just the title bars when all are folded, taller as they
 * open, so put it in a {@link me.piitex.engine.ui.scroll.ScrollContainer} when the sections can outgrow the window. A body
 * slides open and closed over {@link #setAnimationSeconds}; a folded body takes no clicks and is not drawn.
 * <p>
 * The user clicks a title bar to open or fold its section, or Tabs to the accordion and uses Up and Down to move between
 * the bars and Enter or Space to toggle one. {@link #onToggle} hears about the user's clicks and keys; the
 * {@link Section#setExpanded setters} are silent.
 * <p>
 * It follows the theme like a {@link Container} that {@link #useTheme() uses the theme}, for its colors, corner radius and
 * border, and draws its title bars with the theme's text color.
 */
public class AccordionContainer extends Container implements Focusable, FontScalable {
    /**
     * One section of an accordion: its title and its body.
     */
    public final class Section {
        private String title;
        private final Element content;
        private boolean expanded;
        private float progress; // 0 folded, 1 open, in between while it slides

        private Section(String title, Element content, boolean expanded) {
            this.title = title;
            this.content = content;
            this.expanded = expanded;
            this.progress = expanded ? 1f : 0f;
        }

        public String getTitle() {
            return title;
        }

        public Section setTitle(String title) {
            this.title = title == null ? "" : title;
            return this;
        }

        public Element getContent() {
            return content;
        }

        public boolean isExpanded() {
            return expanded;
        }

        /**
         * Opens or folds the section, sliding. Silent: no {@link AccordionContainer#onToggle}. In an {@linkplain
         * AccordionContainer#setExclusive exclusive} accordion, opening a section folds the others.
         */
        public Section setExpanded(boolean expanded) {
            apply(this, expanded);
            return this;
        }

        public Section toggle() {
            return setExpanded(!expanded);
        }
    }

    private final List<Section> sections = new ArrayList<>();
    private boolean exclusive;
    private float headerHeight = 34f;
    private float contentPadding = 10f;
    private float spacing;
    private float animationSeconds = 0.18f;
    private float fontSize = 16f;
    private BiConsumer<Section, Boolean> onToggle;

    private final Overridable<Paint> textColor = new Overridable<>(Color.BLACK);
    private final Overridable<Paint> accentColor = new Overridable<>(Color.GRAY);

    private org.jetbrains.skia.Font font;
    private Typeface fontTypeface;
    private boolean focused;
    private int focusedIndex;

    public AccordionContainer(double width) {
        super(width, 0);
        setFocusTraversable(true);
        setBuiltInClickListener(event -> {
            if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            int index = headerAt(event.getMouseY());
            if (index >= 0) {
                focusedIndex = index;
                toggleByUser(sections.get(index));
            }
        });
        ThemeManager.register(this);
        relayout();
    }

    /* Sections */

    /**
     * Adds a folded section at the bottom.
     */
    public Section addSection(String title, Element content) {
        return addSection(title, content, false);
    }

    /**
     * Adds a section at the bottom, open or folded. In an {@linkplain #setExclusive exclusive} accordion that already has
     * an open section, adding one open folds the others.
     */
    public Section addSection(String title, Element content, boolean expanded) {
        Section section = new Section(title == null ? "" : title, content, expanded);
        sections.add(section);
        addElement(content);
        if (expanded) apply(section, true);
        syncContent(section);
        relayout();
        return section;
    }

    public void removeSection(Section section) {
        if (!sections.remove(section)) return;
        removeElement(section.content);
        section.content.setEnabled(true);
        focusedIndex = Math.min(focusedIndex, Math.max(0, sections.size() - 1));
        relayout();
    }

    public List<Section> getSections() {
        return List.copyOf(sections);
    }

    /**
     * Opens every section, silently. Does nothing in an {@linkplain #setExclusive exclusive} accordion.
     */
    public void expandAll() {
        if (exclusive) return;
        for (Section section : sections) apply(section, true);
    }

    public void collapseAll() {
        for (Section section : sections) apply(section, false);
    }

    public boolean isExclusive() {
        return exclusive;
    }

    /**
     * Whether opening a section folds the others, so at most one is open. Off by default. Turning it on folds all but the
     * first open section.
     */
    public void setExclusive(boolean exclusive) {
        this.exclusive = exclusive;
        if (!exclusive) return;
        boolean seen = false;
        for (Section section : sections) {
            if (section.expanded && seen) section.expanded = false;
            seen |= section.expanded;
        }
    }

    /**
     * Fired with the section and whether it is now open when the user clicks or keys one open or shut. Not fired by the
     * setters.
     */
    public void onToggle(BiConsumer<Section, Boolean> onToggle) {
        this.onToggle = onToggle;
    }

    private void toggleByUser(Section section) {
        apply(section, !section.expanded);
        if (onToggle != null) onToggle.accept(section, section.expanded);
        notifyAccessibleChange();
    }

    // --- Accessibility ---

    @Override
    protected me.piitex.engine.access.AccessibleRole defaultAccessibleRole() {
        return me.piitex.engine.access.AccessibleRole.GROUP;
    }

    /**
     * The section the keyboard is on, with whether it is open and where it sits, such as "Graphics, expanded, section 1
     * of 3".
     */
    @Override
    public String getAccessibleValue() {
        if (sections.isEmpty()) return "";
        int index = Math.max(0, Math.min(focusedIndex, sections.size() - 1));
        Section section = sections.get(index);
        return section.title + ", " + (section.expanded ? "expanded" : "collapsed") + ", section " + (index + 1) + " of " + sections.size();
    }

    private void apply(Section section, boolean expanded) {
        section.expanded = expanded;
        if (expanded && exclusive) {
            for (Section other : sections) {
                if (other != section) other.expanded = false;
            }
        }
        if (animationSeconds <= 0f) {
            for (Section s : sections) s.progress = s.expanded ? 1f : 0f;
        }
        for (Section s : sections) syncContent(s);
        relayout();
    }

    /* Look */

    public float getHeaderHeight() {
        return headerHeight;
    }

    /**
     * The height of each title bar. Default 34.
     */
    public void setHeaderHeight(float headerHeight) {
        this.headerHeight = Math.max(1f, headerHeight);
        relayout();
    }

    public float getContentPadding() {
        return contentPadding;
    }

    /**
     * The gap between an open section's body and the edges of its area. Default 10.
     */
    public void setContentPadding(float contentPadding) {
        this.contentPadding = Math.max(0f, contentPadding);
        relayout();
    }

    public float getSpacing() {
        return spacing;
    }

    /**
     * The gap between one section and the next. Default 0.
     */
    public void setSpacing(float spacing) {
        this.spacing = Math.max(0f, spacing);
        relayout();
    }

    public float getAnimationSeconds() {
        return animationSeconds;
    }

    /**
     * How long a body takes to slide open or shut; 0 for instantly. Default 0.18.
     */
    public void setAnimationSeconds(float animationSeconds) {
        this.animationSeconds = Math.max(0f, animationSeconds);
        if (this.animationSeconds <= 0f) {
            for (Section s : sections) s.progress = s.expanded ? 1f : 0f;
            for (Section s : sections) syncContent(s);
            relayout();
        }
    }

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
        closeFont();
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

    /**
     * Pins the title color against theme switches.
     */
    public void setTextColor(Paint color) {
        textColor.set(color);
    }

    public Paint getAccentColor() {
        return accentColor.get();
    }

    /**
     * Pins the color of the focus outline against theme switches.
     */
    public void setAccentColor(Paint color) {
        accentColor.set(color);
    }

    @Override
    public void applyTheme(Theme theme) {
        super.applyTheme(theme);
        textColor.applyTheme(theme.getText());
        accentColor.applyTheme(theme.getAccent());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        textColor.clearExplicit();
        accentColor.clearExplicit();
    }

    /* Layout */

    private float visibleBody(Section section) {
        float full = (float) section.content.getHeight() + 2 * contentPadding;
        return full * ease(section.progress);
    }

    private static float ease(float t) {
        return t * t * (3f - 2f * t);
    }

    /**
     * The top of section {@code index}'s title bar, in this container.
     */
    private float headerTop(int index) {
        float top = 0f;
        for (int i = 0; i < index; i++) top += headerHeight + visibleBody(sections.get(i)) + spacing;
        return top;
    }

    /**
     * The section whose title bar is under a window y, or -1.
     */
    private int headerAt(double windowY) {
        double y = toLocalY(windowY) - getY();
        for (int i = 0; i < sections.size(); i++) {
            float top = headerTop(i);
            if (y >= top && y < top + headerHeight) return i;
        }
        return -1;
    }

    private void syncContent(Section section) {
        section.content.setEnabled(section.progress > 0f || section.expanded);
    }

    /**
     * Puts every body under its title bar and makes the accordion as tall as its sections. Runs every frame; call it to see a
     * change at once.
     */
    public void relayout() {
        float inner = (float) getWidth() - 2 * contentPadding;
        float top = 0f;
        for (Section section : sections) {
            if (inner > 0 && section.content.getWidth() != inner) section.content.setWidth(inner);
            section.content.setX(contentPadding);
            section.content.setY(top + headerHeight + contentPadding);
            top += headerHeight + visibleBody(section) + spacing;
        }
        double height = sections.isEmpty() ? 0 : top - spacing;
        if (getHeight() != height) setHeight(height);
    }

    @Override
    public void update(float delta) {
        for (Section section : sections) {
            float target = section.expanded ? 1f : 0f;
            if (section.progress != target) {
                float step = animationSeconds <= 0f ? 1f : delta / animationSeconds;
                section.progress = section.progress < target ? Math.min(target, section.progress + step) : Math.max(target, section.progress - step);
            }
            syncContent(section);
        }
        relayout();
        super.update(delta);
        AnimatedPaint.advanceIfAnimated(textColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(accentColor.get(), delta);
    }

    /* Keys */

    public boolean isFocused() {
        return focused;
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
        if (action == GLFW.GLFW_RELEASE || sections.isEmpty()) return;
        focusedIndex = Math.max(0, Math.min(focusedIndex, sections.size() - 1));
        int before = focusedIndex;
        switch (key) {
            case GLFW.GLFW_KEY_UP -> focusedIndex = Math.max(0, focusedIndex - 1);
            case GLFW.GLFW_KEY_DOWN -> focusedIndex = Math.min(sections.size() - 1, focusedIndex + 1);
            case GLFW.GLFW_KEY_HOME -> focusedIndex = 0;
            case GLFW.GLFW_KEY_END -> focusedIndex = sections.size() - 1;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (action == GLFW.GLFW_PRESS) toggleByUser(sections.get(focusedIndex));
            }
            default -> {
            }
        }
        if (focusedIndex != before) notifyAccessibleChange();
    }

    /* Rendering */

    private org.jetbrains.skia.Font getFont() {
        Typeface typeface = Font.getDefault();
        if (font == null || typeface != fontTypeface) {
            closeFont();
            font = new org.jetbrains.skia.Font(typeface, fontSize);
            fontTypeface = typeface;
        }
        return font;
    }

    private void closeFont() {
        if (font != null) {
            font.close();
            font = null;
        }
    }

    /**
     * Frees the font. The accordion can still be drawn afterwards; it makes a new one.
     */
    public void dispose() {
        closeFont();
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        renderBackground(renderer);
        renderHeaders(renderer);
        renderChildren(renderer);
    }

    private void renderHeaders(Renderer renderer) {
        float x = (float) getX(), y = (float) getY(), w = (float) getWidth();
        org.jetbrains.skia.Font f = getFont();
        float textH = FontMetricsCompat.of(f).getHeight();
        Paint text = textColor.get();
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            float top = y + headerTop(i);
            renderer.pushAlpha(0.07f);
            renderer.drawQuad(x, top, w, headerHeight, text, 0f);
            renderer.popAlpha();
            if (focused && i == focusedIndex) {
                renderer.drawBorder(x + 1f, top + 1f, w - 2f, headerHeight - 2f, accentColor.get(), 2f, 3f);
            }
            drawChevron(renderer, x + 18f, top + headerHeight / 2f, section.progress, text);
            float left = x + 34f, right = x + w - 12f;
            if (right > left) {
                String title = TextFit.ellipsize(section.title, f, right - left);
                renderer.drawText(title, left, top + (headerHeight - textH) / 2f, f, text);
            }
            if (visibleBody(section) > 0f) {
                renderer.pushAlpha(0.25f);
                renderer.drawLine(x, top + headerHeight - 0.5f, x + w, top + headerHeight - 0.5f, text, 1f);
                renderer.popAlpha();
            }
        }
    }

    /**
     * A chevron that points right when folded and down when open, turning as the body slides.
     */
    private static void drawChevron(Renderer renderer, float cx, float cy, float progress, Paint color) {
        float r = 4.5f, t = ease(progress);
        // Right-pointing arm ends, rotated by t * 90 degrees about the centre.
        double angle = Math.PI / 2 * t;
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        float[][] pts = {{-r * 0.5f, -r}, {r * 0.5f, 0f}, {-r * 0.5f, r}};
        float[] px = new float[3], py = new float[3];
        for (int i = 0; i < 3; i++) {
            px[i] = cx + pts[i][0] * cos - pts[i][1] * sin;
            py[i] = cy + pts[i][0] * sin + pts[i][1] * cos;
        }
        renderer.drawLine(px[0], py[0], px[1], py[1], color, 2f);
        renderer.drawLine(px[1], py[1], px[2], py[2], color, 2f);
    }

    /**
     * Draws each body clipped to the part of its section that has slid into view.
     */
    @Override
    protected void renderChildren(Renderer renderer) {
        renderer.pushTranslate((float) (getX() + childOffsetX()), (float) (getY() + childOffsetY()));
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            float body = visibleBody(section);
            if (body <= 0f || !section.content.isEnabled()) continue;
            renderer.pushClip(0f, headerTop(i) + headerHeight, (float) getWidth(), body);
            section.content.renderWithTransitions(renderer);
            renderer.popClip();
        }
        renderer.popTranslate();
    }

    /**
     * A pointer over a title bar, nothing special over a body.
     */
    @Override
    public CursorMode getCursorMode() {
        if (MouseTracker.isInWindow() && headerAt(MouseTracker.getY()) >= 0 && contains(MouseTracker.getX(), MouseTracker.getY())) {
            return CursorMode.POINTER;
        }
        return super.getCursorMode();
    }
}
