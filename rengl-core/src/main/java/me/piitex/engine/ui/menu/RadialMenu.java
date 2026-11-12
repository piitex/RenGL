package me.piitex.engine.ui.menu;

import me.piitex.engine.ui.text.TextShaping;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.PopupHost;
import me.piitex.engine.ui.PopupKeyListener;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.image.IconCache;
import me.piitex.engine.ui.overlays.Arcs;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.ui.text.TextFit;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A pie menu: the choices are wedges of a ring around the pointer, picked by the direction the pointer moves rather than by
 * aiming at a small row. It suits a game's quick actions (emotes, weapons, build tools) because the choice is the same
 * short flick from anywhere, and a player learns "up is the hammer".
 * <pre>{@code
 * RadialMenu tools = new RadialMenu();
 * tools.addItem("Build", Feather.HOME, () -> mode(BUILD));
 * tools.addItem("Repair", Feather.TOOL, () -> mode(REPAIR));
 * tools.addItem("Demolish", Feather.TRASH, () -> mode(DEMOLISH)).setEnabled(canDemolish);
 * tools.show(window, MouseTracker.getX(), MouseTracker.getY());       // centered on the pointer
 * }</pre>
 * The first item is at the top and the rest follow clockwise, evenly. The menu opens through the window's popup layer, so it
 * paints over everything and closes on an outside click or Escape. The wedge under the pointer lights up; a click on it
 * runs its action and closes the menu. A click on the hub in the middle closes it without choosing. With the keyboard, the
 * arrow keys move around the ring, Enter or Space picks, the digits 1 to 9 pick that item, and Escape closes.
 * <p>
 * A menu can be {@linkplain #show shown} again after it closes, and its items changed between times. Disabled items are
 * drawn dim and cannot be picked.
 * <p>
 * Colors come from the theme: the wedges from the container background, the lit wedge from the accent, the text from the
 * theme's text color.
 */
public class RadialMenu extends Container implements PopupKeyListener, Themeable, FontScalable {
    /**
     * One wedge of the menu. Add them with {@link RadialMenu#addItem}.
     */
    public static final class Item {
        private final String label;
        private final IconAsset icon;
        private final Runnable action;
        private boolean enabled = true;

        private Item(String label, IconAsset icon, Runnable action) {
            this.label = label == null ? "" : label;
            this.icon = icon;
            this.action = action;
        }

        public String getLabel() {
            return label;
        }

        public IconAsset getIcon() {
            return icon;
        }

        public boolean isEnabled() {
            return enabled;
        }

        /**
         * A disabled item is drawn dim and ignores clicks and keys. Enabled by default.
         */
        public Item setEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
    }

    private static final float GAP_DEGREES = 2.5f;

    private final List<Item> items = new ArrayList<>();
    private float outerRadius = 110f;
    private float innerRadius = 36f;

    private int keyboardIndex = -1;
    private int spokenIndex = -1; // the wedge last read out, so each is said once as it is entered
    private boolean useKeyboard;
    private double lastMouseX = Double.NaN;
    private double lastMouseY = Double.NaN;
    private PopupHost host;
    private Runnable onClose;

    private float fontSize = 13f;
    private final Typeface typeface = Font.getDefault();
    private org.jetbrains.skia.Font font;
    private boolean fontDirty = true;

    private final Overridable<Paint> wedgeColor = new Overridable<>(new Color(0.15f, 0.15f, 0.15f, 0.92f));
    private final Overridable<Paint> edgeColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> litColor = new Overridable<>(Color.GRAY);
    private final Overridable<Paint> textColor = new Overridable<>(Color.WHITE);

    public RadialMenu() {
        super(0, 0);
        setCursorMode(CursorMode.POINTER);
        // Only the ring is drawn; the square the popup occupies stays clear.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        onMouseClick(event -> {
            if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            int index = wedgeAt(event.getMouseX(), event.getMouseY());
            if (index >= 0) pick(index);
            else close(); // the hub, or a corner of the square outside the ring
        });
        resize();
        ThemeManager.register(this);
    }

    /* Items */

    /**
     * Adds a wedge with text only.
     */
    public Item addItem(String label, Runnable action) {
        return addItem(label, null, action);
    }

    /**
     * Adds a wedge with an icon over its label. The icon is tinted with the text color.
     */
    public Item addItem(String label, IconAsset icon, Runnable action) {
        Item item = new Item(label, icon, action);
        items.add(item);
        return item;
    }

    public void clearItems() {
        items.clear();
        keyboardIndex = -1;
    }

    public List<Item> getItems() {
        return List.copyOf(items);
    }

    public float getOuterRadius() {
        return outerRadius;
    }

    public float getInnerRadius() {
        return innerRadius;
    }

    /**
     * The size of the ring: its outer radius and the radius of the hub left empty in the middle (110 and 36 by default).
     */
    public void setRadii(float innerRadius, float outerRadius) {
        this.innerRadius = Math.max(0f, innerRadius);
        this.outerRadius = Math.max(this.innerRadius + 10f, outerRadius);
        resize();
    }

    private void resize() {
        setWidth(outerRadius * 2);
        setHeight(outerRadius * 2);
    }

    /**
     * Called whenever the menu closes, by a pick, a click away or Escape.
     */
    public void onClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /* Showing */

    /**
     * Opens the menu with its center at ({@code x}, {@code y}) in the host's coordinates (the window's, for a window), moved
     * inward if that would put the ring outside the room the host has. Does nothing for a menu with no items.
     */
    public void show(PopupHost host, double x, double y) {
        if (items.isEmpty()) return;
        this.host = host;
        double[] area = host.getPopupArea();
        double cx = Math.max(area[0] + outerRadius, Math.min(area[0] + area[2] - outerRadius, x));
        double cy = Math.max(area[1] + outerRadius, Math.min(area[1] + area[3] - outerRadius, y));
        setBounds(cx - outerRadius, cy - outerRadius, outerRadius * 2, outerRadius * 2);
        keyboardIndex = -1;
        useKeyboard = false;
        spokenIndex = -1;
        lastMouseX = lastMouseY = Double.NaN;
        host.showPopup(this, null, () -> {
            this.host = null;
            if (onClose != null) onClose.run();
        });
        say("Radial menu, " + items.size() + (items.size() == 1 ? " choice" : " choices")
                + ". Arrow keys to choose, Enter to select, Escape to close.", true);
    }

    /**
     * Whether the menu is open.
     */
    public boolean isOpen() {
        return host != null && host.getPopup() == this;
    }

    public void close() {
        PopupHost h = host;
        if (h != null && h.getPopup() == this) h.closePopup();
    }

    /* Choosing */

    /**
     * Which item a point is over, in the host's coordinates, or -1 for the hub, the gap outside the ring, or a disabled item
     * too (use {@link #getItems} to tell).
     */
    public int wedgeAt(double x, double y) {
        int index = rawWedgeAt(x, y);
        return index >= 0 && items.get(index).enabled ? index : -1;
    }

    private int rawWedgeAt(double x, double y) {
        if (items.isEmpty()) return -1;
        double dx = x - (getX() + getWidth() / 2.0), dy = y - (getY() + getHeight() / 2.0);
        double r = Math.hypot(dx, dy);
        if (r < innerRadius || r > outerRadius) return -1;
        double slice = 360.0 / items.size();
        double fromTop = Math.toDegrees(Math.atan2(dy, dx)) + 90.0; // 0 is straight up, clockwise
        fromTop = ((fromTop % 360.0) + 360.0) % 360.0;
        return (int) Math.round(fromTop / slice) % items.size();
    }

    /**
     * Runs item {@code index}'s action and closes the menu. Does nothing for a disabled item or a bad index.
     */
    public void pick(int index) {
        if (index < 0 || index >= items.size() || !items.get(index).enabled) return;
        Runnable action = items.get(index).action;
        say("Chose " + items.get(index).label, true);
        close();
        if (action != null) action.run();
    }

    @Override
    public void onPopupKey(int key, int mods) {
        int n = items.size();
        if (n == 0) return;
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                say("Menu closed", true);
                close();
            }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_DOWN -> step(+1);
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_UP -> step(-1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> pick(keyboardIndex);
            default -> {
                if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) pick(key - GLFW.GLFW_KEY_1);
            }
        }
    }

    /**
     * The item the keyboard has lit, or -1. Moves to the next enabled item in a direction, around the ring.
     */
    private void step(int direction) {
        int n = items.size();
        int from = keyboardIndex < 0 ? (direction > 0 ? -1 : 0) : keyboardIndex;
        for (int i = 1; i <= n; i++) {
            int candidate = ((from + direction * i) % n + n) % n;
            if (items.get(candidate).enabled) {
                keyboardIndex = candidate;
                useKeyboard = true;
                spokenIndex = candidate;
                sayItem(candidate);
                return;
            }
        }
    }

    /* Accessibility */

    private me.piitex.engine.Window speechWindow() {
        return host instanceof me.piitex.engine.Window window ? window : getWindow();
    }

    private void say(String text, boolean urgent) {
        me.piitex.engine.Window window = speechWindow();
        if (window != null) window.announce(text, urgent);
    }

    /**
     * "Attack, 2 of 6": the wedge's label and where it is in the ring. Urgent, so stepping quickly does not queue up.
     */
    private void sayItem(int index) {
        if (index < 0 || index >= items.size()) return;
        say(items.get(index).label + ", " + (index + 1) + " of " + items.size(), true);
    }

    /**
     * The item lit now: the keyboard's while it was the last thing used, else the one under the pointer. -1 for none.
     */
    public int getLitIndex() {
        if (useKeyboard) return keyboardIndex;
        return MouseTracker.isInWindow() ? wedgeAt(MouseTracker.getX(), MouseTracker.getY()) : -1;
    }

    /* Look */

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

    public Paint getWedgeColor() {
        return wedgeColor.get();
    }

    public void setWedgeColor(Paint wedgeColor) {
        this.wedgeColor.set(wedgeColor);
    }

    /**
     * The color of the outline around each wedge.
     */
    public void setEdgeColor(Paint edgeColor) {
        this.edgeColor.set(edgeColor);
    }

    public Paint getLitColor() {
        return litColor.get();
    }

    public void setLitColor(Paint litColor) {
        this.litColor.set(litColor);
    }

    public Paint getTextColor() {
        return textColor.get();
    }

    public void setTextColor(Paint textColor) {
        this.textColor.set(textColor);
    }

    @Override
    public void applyTheme(Theme theme) {
        // The field color, not the panel's: the ring floats over panels and has to stand out from them.
        wedgeColor.applyTheme(theme.getFieldBackground().withAlpha(0.95f));
        edgeColor.applyTheme(theme.getFieldBorder());
        litColor.applyTheme(theme.getAccent());
        textColor.applyTheme(theme.getText());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        wedgeColor.clearExplicit();
        edgeColor.clearExplicit();
        litColor.clearExplicit();
        textColor.clearExplicit();
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        // The pointer moving takes the lit wedge back from the keyboard.
        if (MouseTracker.isInWindow() && (MouseTracker.getX() != lastMouseX || MouseTracker.getY() != lastMouseY)) {
            if (!Double.isNaN(lastMouseX)) useKeyboard = false;
            lastMouseX = MouseTracker.getX();
            lastMouseY = MouseTracker.getY();
        }
        // The wedge under the pointer is read out as it changes, the way the keyboard's is.
        int lit = isOpen() ? getLitIndex() : -1;
        if (lit != spokenIndex) {
            spokenIndex = lit;
            if (lit >= 0 && !useKeyboard) sayItem(lit);
        }
        AnimatedPaint.advanceIfAnimated(wedgeColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(edgeColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(litColor.get(), delta);
        AnimatedPaint.advanceIfAnimated(textColor.get(), delta);
    }

    /* Rendering */

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled() || items.isEmpty()) return;
        float cx = (float) (getX() + getWidth() / 2.0), cy = (float) (getY() + getHeight() / 2.0);
        int lit = getLitIndex();
        double slice = 360.0 / items.size();
        org.jetbrains.skia.Font f = getFont();

        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            float centerDeg = (float) (-90.0 + slice * i);
            float span = (float) slice - (items.size() > 1 ? GAP_DEGREES : 0f);
            float[] ring = wedgePolygon(cx, cy, centerDeg - span / 2f, span);
            Paint fill = i == lit ? litColor.get() : wedgeColor.get();
            if (!item.enabled) renderer.pushAlpha(0.45f);
            renderer.drawPolygon(ring, ring.length / 2, solid(fill));
            renderer.drawPolygonOutline(ring, ring.length / 2, solid(edgeColor.get()), 1.5f);
            if (!item.enabled) renderer.popAlpha();
            drawLabel(renderer, item, cx, cy, centerDeg, f, !item.enabled);
        }
        // The hub: shows the lit item's name, or nothing, and closes the menu when clicked.
        renderer.drawCircle(cx, cy, innerRadius - 4f, wedgeColor.get());
        if (lit >= 0) {
            String name = items.get(lit).label;
            String shown = TextFit.ellipsize(name, f, (innerRadius - 8f) * 2f);
            FontMetricsCompat m = FontMetricsCompat.of(f);
            float w = TextShaping.width(f, shown), h = -m.getAscent() + m.getDescent();
            renderer.drawText(shown, cx - w / 2f, cy - h / 2f, f, textColor.get());
        }
    }

    /**
     * An annular sector as one polygon: out along the outer arc, back along the inner one.
     */
    private float[] wedgePolygon(float cx, float cy, float startDeg, float spanDeg) {
        float[] outer = Arcs.arc(cx, cy, outerRadius - 2f, startDeg, spanDeg);
        float[] inner = Arcs.arc(cx, cy, innerRadius + 2f, startDeg + spanDeg, -spanDeg);
        float[] points = new float[outer.length + inner.length];
        System.arraycopy(outer, 0, points, 0, outer.length);
        System.arraycopy(inner, 0, points, outer.length, inner.length);
        return points;
    }

    private void drawLabel(Renderer renderer, Item item, float cx, float cy, float centerDeg, org.jetbrains.skia.Font f, boolean dim) {
        double a = Math.toRadians(centerDeg);
        float mid = (innerRadius + outerRadius) / 2f;
        float x = cx + (float) Math.cos(a) * mid, y = cy + (float) Math.sin(a) * mid;
        float maxWidth = Math.max(40f, (outerRadius - innerRadius) * 1.1f);
        FontMetricsCompat m = FontMetricsCompat.of(f);
        float textH = -m.getAscent() + m.getDescent();
        if (dim) renderer.pushAlpha(0.5f);
        boolean icon = item.icon != null;
        float iconSize = 24f;
        if (icon) {
            int px = Math.round(iconSize);
            Color tint = textColor.get() instanceof Color c ? c : Color.WHITE;
            renderer.drawImage(IconCache.loadResource(item.icon.resourcePath(), px, px), x - iconSize / 2f, y - iconSize / 2f - textH / 2f - 2f,
                    iconSize, iconSize, 0f, 1f, tint);
        }
        if (!item.label.isEmpty()) {
            String shown = TextFit.ellipsize(item.label, f, maxWidth);
            float w = TextShaping.width(f, shown);
            float ty = icon ? y + iconSize / 2f - textH / 2f + 2f : y - textH / 2f;
            renderer.drawText(shown, x - w / 2f, ty, f, textColor.get());
        }
        if (dim) renderer.popAlpha();
    }

    private org.jetbrains.skia.Font getFont() {
        if (fontDirty || font == null) {
            if (font != null) font.close();
            font = new org.jetbrains.skia.Font(typeface, fontSize);
            fontDirty = false;
        }
        return font;
    }

    private static Color solid(Paint paint) {
        return switch (AnimatedPaint.resolve(paint)) {
            case Color c -> c;
            case me.piitex.engine.ui.color.LinearGradient g -> g.getColors().get(0);
            case null, default -> Color.BLACK;
        };
    }

    /**
     * Frees the font used for the labels, if one was drawn.
     */
    public void dispose() {
        if (font != null) {
            font.close();
            font = null;
        }
    }
}
