package me.piitex.engine.ui.overlays;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Draggable;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.scroll.Scrollable;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.Image;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A small map of a larger world, with markers on it: the corner mini-map of an action game, the overview of a strategy or
 * tycoon map, the "you are here" of a level.
 * <pre>{@code
 * MinimapOverlay map = new MinimapOverlay(200, 200);
 * map.setWorld(0, 0, 4000, 4000);                 // the area it shows, in your game's units
 * map.setBackdrop(terrainImage);                  // an image of that area; optional
 * MinimapOverlay.Marker me = map.addMarker(playerX, playerY, Color.WHITE);
 * me.setShape(MinimapOverlay.Shape.ARROW);
 * map.setFollow(me);                              // keep the view centered on the player
 * map.setViewport(camX, camY, camW, camH);        // outline what the main view shows
 * map.onNavigate((x, y) -> camera.lookAt(x, y));  // a click on the map
 * }</pre>
 * World coordinates are the game's own, and {@code y} grows <b>down</b> the map like on screen, unless {@link #setFlipY} says
 * the game's y grows up. The map shows the whole world, fitted inside its box, until zoomed ({@link #setZoom}, or the wheel);
 * a zoomed map shows the part around its center, which moves with {@link #centerOn} or follows a marker with {@link
 * #setFollow}, and never leaves the world. Markers are moved by the game (set {@link Marker#setPosition} each frame); the map
 * keeps no list of what exists.
 * <p>
 * Pressing or dragging on the map calls {@link #onNavigate} with the world position under the pointer, except a press on a
 * marker, which calls {@link #onMarkerClick}. A box shaped {@linkplain Shape#CIRCLE round} ({@link #setRound}) clips the map
 * to a circle, as a compass-style minimap does. For something other than an image behind the markers, give {@link
 * #setBackdropPainter} and draw it in world coordinates through {@link #worldToScreenX} and {@link #worldToScreenY}.
 * <p>
 * Colors come from the theme: the box from the field background and border, the camera outline from the accent.
 */
public class MinimapOverlay extends Overlay implements Themeable, Draggable, Scrollable, me.piitex.engine.ui.Focusable {
    /**
     * What a {@link Marker} looks like.
     */
    public enum Shape {
        DOT, SQUARE, DIAMOND,
        /** A triangle pointing along the marker's {@linkplain Marker#setHeading heading}. */
        ARROW
    }

    /**
     * Draws behind the markers, after the {@linkplain #setBackdrop backdrop image}, if any.
     */
    @FunctionalInterface
    public interface BackdropPainter {
        void paint(Renderer renderer, MinimapOverlay map);
    }

    /**
     * One thing on the map. Hold on to the marker the map returns to move or remove it.
     */
    public static final class Marker {
        private double x;
        private double y;
        private Paint paint;
        private Shape shape = Shape.DOT;
        private float size = 4f;
        private double heading; // degrees, clockwise on screen from pointing right
        private boolean visible = true;
        private String label = "";
        private Object data;

        private Marker(double x, double y, Paint paint) {
            this.x = x;
            this.y = y;
            this.paint = paint;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        /**
         * Moves the marker, in world coordinates.
         */
        public Marker setPosition(double x, double y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Paint getPaint() {
            return paint;
        }

        public Marker setPaint(Paint paint) {
            this.paint = paint;
            return this;
        }

        public Shape getShape() {
            return shape;
        }

        public Marker setShape(Shape shape) {
            this.shape = shape == null ? Shape.DOT : shape;
            return this;
        }

        public float getSize() {
            return size;
        }

        /**
         * The marker's radius in points on the map, whatever the zoom; 4 by default.
         */
        public Marker setSize(float size) {
            this.size = Math.max(1f, size);
            return this;
        }

        public double getHeading() {
            return heading;
        }

        /**
         * The way an {@link Shape#ARROW} points, in degrees clockwise on screen from pointing right (0 right, 90 down, 180
         * left, 270 up), after {@link #setFlipY} has been applied.
         */
        public Marker setHeading(double heading) {
            this.heading = heading;
            return this;
        }

        public boolean isVisible() {
            return visible;
        }

        /**
         * A hidden marker is not drawn and cannot be clicked. Visible by default.
         */
        public Marker setVisible(boolean visible) {
            this.visible = visible;
            return this;
        }

        public String getLabel() {
            return label;
        }

        /**
         * What the marker is called, such as "Shop" or "Player": read aloud for someone who cannot see the map, which lists
         * the markers that have one. A marker with no label is left out of that.
         */
        public Marker setLabel(String label) {
            this.label = label == null ? "" : label;
            return this;
        }

        public Object getData() {
            return data;
        }

        /**
         * Anything the game wants to find again from {@link #onMarkerClick}, such as the unit this marker stands for.
         */
        public Marker setData(Object data) {
            this.data = data;
            return this;
        }
    }

    private static final double ZOOM_PER_NOTCH = 1.25;

    private double worldX;
    private double worldY;
    private double worldWidth = 1;
    private double worldHeight = 1;
    private boolean flipY;
    private boolean round;

    private double zoom = 1;
    private double minZoom = 1;
    private double maxZoom = 16;
    private double centerX = 0.5;
    private double centerY = 0.5;
    private Marker follow;

    private Image backdrop;
    private BackdropPainter backdropPainter;
    private final List<Marker> markers = new ArrayList<>();
    private double[] viewport; // x, y, w, h in world units

    private final Overridable<Paint> viewportColor = new Overridable<>(Color.WHITE);

    private BiConsumer<Double, Double> onNavigate;
    private Consumer<Marker> onMarkerClick;
    private boolean navigating;
    private Marker chosen; // the marker Page Up / Page Down or a click is on, for someone listening
    private double cursorX = Double.NaN; // the keyboard's spot on the map, in world units; NaN until used
    private double cursorY = Double.NaN;

    public MinimapOverlay(double width, double height) {
        setWidth(width);
        setHeight(height);
        setCursorMode(CursorMode.POINTER);
        getStyling().applyThemeBorderThickness(1f);
        getStyling().applyThemeCornerRadius(4f);
        setFocusTraversable(true);
        ThemeManager.register(this);
    }

    /* The world */

    /**
     * The area of the game world the map shows, as its top-left corner and size, in the game's units. Resets the view to the
     * whole of it.
     */
    public void setWorld(double x, double y, double width, double height) {
        this.worldX = x;
        this.worldY = y;
        this.worldWidth = Math.max(1e-9, width);
        this.worldHeight = Math.max(1e-9, height);
        this.zoom = 1;
        this.centerX = x + this.worldWidth / 2.0;
        this.centerY = y + this.worldHeight / 2.0;
    }

    public boolean isFlipY() {
        return flipY;
    }

    /**
     * Whether the game's y grows up (a physics or math convention) rather than down the map. Off by default.
     */
    public void setFlipY(boolean flipY) {
        this.flipY = flipY;
    }

    public boolean isRound() {
        return round;
    }

    /**
     * Clips the map, its background and border to a circle (using the smaller side as the diameter). Off by default.
     */
    public void setRound(boolean round) {
        this.round = round;
        getStyling().applyThemeCornerRadius(round ? (float) (Math.min(getWidth(), getHeight()) / 2.0) : 4f);
    }

    /**
     * An image of the whole world area, stretched over it and drawn under the markers. Null for none. The map does not own
     * the image and never closes it.
     */
    public void setBackdrop(Image backdrop) {
        this.backdrop = backdrop;
    }

    public void setBackdropPainter(BackdropPainter backdropPainter) {
        this.backdropPainter = backdropPainter;
    }

    /* The view */

    public double getZoom() {
        return zoom;
    }

    /**
     * How many times larger than the fitted whole world the map draws, between {@link #setZoomLimits the limits}. 1, the
     * default, shows all of it. The view stays on its center.
     */
    public void setZoom(double zoom) {
        this.zoom = Math.max(minZoom, Math.min(maxZoom, zoom));
        clampCenter();
    }

    /**
     * The least and most zoom the wheel and {@link #setZoom} allow; 1 and 16 by default.
     */
    public void setZoomLimits(double min, double max) {
        this.minZoom = Math.max(1e-3, Math.min(min, max));
        this.maxZoom = Math.max(this.minZoom, Math.max(min, max));
        setZoom(zoom);
    }

    /**
     * Moves the view's center to a world position (kept inside the world). Ends {@link #setFollow following}.
     */
    public void centerOn(double x, double y) {
        this.follow = null;
        this.centerX = x;
        this.centerY = y;
        clampCenter();
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterY() {
        return centerY;
    }

    /**
     * Keeps the view centered on a marker as it moves, as a player-centered mini-map does. Null stops following and leaves
     * the view where it is.
     */
    public void setFollow(Marker marker) {
        this.follow = marker;
        if (marker != null) {
            centerX = marker.x;
            centerY = marker.y;
            clampCenter();
        }
    }

    /**
     * Outlines a rectangle of the world, the area the main view shows. Pass new numbers each frame as the camera moves.
     */
    public void setViewport(double x, double y, double width, double height) {
        this.viewport = new double[]{x, y, width, height};
    }

    public void clearViewport() {
        this.viewport = null;
    }

    /**
     * Points on the map per world unit now.
     */
    public double getPointsPerUnit() {
        return fitScale() * zoom;
    }

    private double fitScale() {
        return Math.min(getWidth() / worldWidth, getHeight() / worldHeight);
    }

    /** The visible part of the world, in world units, across and down. */
    private double visibleWidth() {
        return getWidth() / getPointsPerUnit();
    }

    private double visibleHeight() {
        return getHeight() / getPointsPerUnit();
    }

    private void clampCenter() {
        double halfW = visibleWidth() / 2.0, halfH = visibleHeight() / 2.0;
        centerX = halfW * 2 >= worldWidth ? worldX + worldWidth / 2.0 : Math.max(worldX + halfW, Math.min(worldX + worldWidth - halfW, centerX));
        centerY = halfH * 2 >= worldHeight ? worldY + worldHeight / 2.0 : Math.max(worldY + halfH, Math.min(worldY + worldHeight - halfH, centerY));
    }

    /* Coordinates */

    /**
     * Where a world x lands on screen, in the coordinates the element draws in.
     */
    public double worldToScreenX(double wx) {
        return getX() + getWidth() / 2.0 + (wx - centerX) * getPointsPerUnit();
    }

    public double worldToScreenY(double wy) {
        double dy = wy - centerY;
        return getY() + getHeight() / 2.0 + (flipY ? -dy : dy) * getPointsPerUnit();
    }

    /**
     * The world x under a screen x, in the same coordinates as {@link #worldToScreenX}.
     */
    public double screenToWorldX(double sx) {
        return centerX + (sx - getX() - getWidth() / 2.0) / getPointsPerUnit();
    }

    public double screenToWorldY(double sy) {
        double dy = (sy - getY() - getHeight() / 2.0) / getPointsPerUnit();
        return centerY + (flipY ? -dy : dy);
    }

    /* Markers */

    /**
     * Adds a dot at a world position and returns it to be moved, restyled or removed.
     */
    public Marker addMarker(double x, double y, Paint paint) {
        Marker marker = new Marker(x, y, paint);
        markers.add(marker);
        return marker;
    }

    public boolean removeMarker(Marker marker) {
        if (follow == marker) follow = null;
        return markers.remove(marker);
    }

    public void clearMarkers() {
        markers.clear();
        follow = null;
    }

    public List<Marker> getMarkers() {
        return List.copyOf(markers);
    }

    /**
     * The topmost visible marker within reach of a point on screen, or null. Later markers are above earlier ones.
     */
    public Marker markerAt(double screenX, double screenY) {
        for (int i = markers.size() - 1; i >= 0; i--) {
            Marker m = markers.get(i);
            if (!m.visible) continue;
            double reach = m.size + 3;
            if (Math.hypot(worldToScreenX(m.x) - screenX, worldToScreenY(m.y) - screenY) <= reach) return m;
        }
        return null;
    }

    /**
     * Fired with the world position under the pointer when the user presses or drags on the map. A press on a marker calls
     * {@link #onMarkerClick} instead.
     */
    public void onNavigate(BiConsumer<Double, Double> onNavigate) {
        this.onNavigate = onNavigate;
    }

    public void onMarkerClick(Consumer<Marker> onMarkerClick) {
        this.onMarkerClick = onMarkerClick;
    }

    /* Interaction */

    @Override
    public void onDragStart(double x, double y) {
        double mx = toLocalX(x), my = toLocalY(y);
        if (round && Math.hypot(mx - (getX() + getWidth() / 2.0), my - (getY() + getHeight() / 2.0)) > Math.min(getWidth(), getHeight()) / 2.0) {
            return; // the corner outside a round map is not on the map
        }
        Marker hit = markerAt(mx, my);
        if (hit != null) {
            if (onMarkerClick != null) onMarkerClick.accept(hit);
            if (!hit.label.isEmpty()) {
                chosen = hit;
                notifyAccessibleChange();
            }
            return;
        }
        navigating = true;
        navigate(mx, my);
        // Say where the press landed once, not at every step of the drag that follows.
        chosen = null;
        cursorX = Math.max(worldX, Math.min(worldX + worldWidth, screenToWorldX(mx)));
        cursorY = Math.max(worldY, Math.min(worldY + worldHeight, screenToWorldY(my)));
        notifyAccessibleChange();
    }

    @Override
    public void onDragUpdate(double x, double y) {
        if (navigating) navigate(toLocalX(x), toLocalY(y));
    }

    @Override
    public void onDragEnd(double x, double y) {
        navigating = false;
    }

    private void navigate(double screenX, double screenY) {
        double wx = Math.max(worldX, Math.min(worldX + worldWidth, screenToWorldX(screenX)));
        double wy = Math.max(worldY, Math.min(worldY + worldHeight, screenToWorldY(screenY)));
        if (onNavigate != null) onNavigate.accept(wx, wy);
    }

    @Override
    public void onScroll(double deltaX, double deltaY) {
        if (deltaY == 0) return;
        // Zoom about the center, which a follow keeps on the marker and a free view keeps where it is.
        setZoom(zoom * Math.pow(ZOOM_PER_NOTCH, Math.signum(deltaY)));
    }

    @Override
    public boolean canScroll(double deltaX, double deltaY) {
        return deltaY > 0 ? zoom < maxZoom : deltaY < 0 && zoom > minZoom;
    }

    /* Accessibility and the keyboard */

    private static final String[] COMPASS = {"east", "north east", "north", "north west", "west", "south west", "south", "south east"};
    private static final double CURSOR_STEP = 0.05; // of the world, per arrow press

    @Override
    protected me.piitex.engine.access.AccessibleRole defaultAccessibleRole() {
        return me.piitex.engine.access.AccessibleRole.IMAGE;
    }

    @Override
    protected String defaultAccessibleName() {
        return "Map";
    }

    /**
     * What a listener is on: the marker chosen, else the spot the keyboard cursor is at, else how many markers have a label.
     */
    @Override
    public String getAccessibleValue() {
        if (chosen != null && chosen.visible && markers.contains(chosen)) return describeMarker(chosen);
        if (!Double.isNaN(cursorX)) return describeSpot(cursorX, cursorY);
        int named = labelled().size();
        return named == 0 ? "" : named == 1 ? "1 marker" : named + " markers";
    }

    @Override
    public String getAccessibleDescription() {
        String set = super.getAccessibleDescription();
        return set != null ? set
                : "Page Up and Page Down move between markers, arrow keys move a cursor, Enter goes there, plus and minus zoom";
    }

    private List<Marker> labelled() {
        List<Marker> out = new ArrayList<>();
        for (Marker m : markers) {
            if (m.visible && !m.label.isEmpty()) out.add(m);
        }
        return out;
    }

    /**
     * "Shop, bottom right", and how far and which way from the marker the view follows (the player) when there is one.
     */
    private String describeMarker(Marker m) {
        StringBuilder text = new StringBuilder(m.label.isEmpty() ? "Marker" : m.label);
        text.append(", ").append(region(m.x, m.y));
        if (follow != null && follow != m) {
            double dx = m.x - follow.x, dy = flipY ? m.y - follow.y : follow.y - m.y; // dy up
            long distance = Math.round(Math.hypot(dx, dy));
            int sector = (int) Math.round(Math.toDegrees(Math.atan2(dy, dx)) / 45.0);
            text.append(", ").append(distance).append(distance == 1 ? " unit " : " units ")
                    .append(COMPASS[((sector % 8) + 8) % 8]).append(" of you");
        }
        return text.toString();
    }

    /**
     * Where the cursor is: the region of the map, and the nearest labelled marker if one is close.
     */
    private String describeSpot(double x, double y) {
        String text = region(x, y);
        Marker nearest = null;
        double best = Math.max(worldWidth, worldHeight) * 0.08;
        for (Marker m : labelled()) {
            double d = Math.hypot(m.x - x, m.y - y);
            if (d <= best) {
                best = d;
                nearest = m;
            }
        }
        return nearest == null ? text : text + ", near " + nearest.label;
    }

    /**
     * A position as a part of the map: "top left", "center", "bottom".
     */
    private String region(double x, double y) {
        double fx = (x - worldX) / worldWidth, fy = (y - worldY) / worldHeight;
        if (flipY) fy = 1.0 - fy;
        String h = fx < 1.0 / 3 ? "left" : fx > 2.0 / 3 ? "right" : "middle";
        String v = fy < 1.0 / 3 ? "top" : fy > 2.0 / 3 ? "bottom" : "middle";
        if (h.equals("middle") && v.equals("middle")) return "center";
        if (h.equals("middle")) return v;
        if (v.equals("middle")) return h;
        return v + " " + h;
    }

    private void say(String text) {
        me.piitex.engine.Window window = getWindow();
        if (window != null) window.announce(text);
    }

    @Override
    public void onFocusGained() {
    }

    @Override
    public void onFocusLost() {
    }

    @Override
    public void onCharTyped(int codepoint) {
    }

    /**
     * The wheel zooms and a press navigates, so a click must not take the keyboard from the game: only Tab focuses the map,
     * and then the arrows, Page Up and Down, Home, Enter, Space, plus and minus are its own.
     */
    @Override
    public boolean grabsKeyboard() {
        return false;
    }

    @Override
    public boolean handlesKey(int key, int mods) {
        return switch (key) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, org.lwjgl.glfw.GLFW.GLFW_KEY_UP,
                 org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP, org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN,
                 org.lwjgl.glfw.GLFW.GLFW_KEY_HOME, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER,
                 org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE, org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ADD,
                 org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_SUBTRACT -> true;
            default -> false;
        };
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (action == org.lwjgl.glfw.GLFW.GLFW_RELEASE) return;
        switch (key) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT -> moveCursor(-1, 0);
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT -> moveCursor(1, 0);
            case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> moveCursor(0, flipY ? 1 : -1);
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> moveCursor(0, flipY ? -1 : 1);
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN -> cycleMarker(1);
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP -> cycleMarker(-1);
            case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> {
                chosen = null;
                cursorX = centerX;
                cursorY = centerY;
                notifyAccessibleChange();
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE -> {
                if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS) activate();
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ADD -> zoomBy(ZOOM_PER_NOTCH);
            case org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_SUBTRACT -> zoomBy(1.0 / ZOOM_PER_NOTCH);
            default -> {
            }
        }
    }

    private void moveCursor(int right, int down) {
        if (Double.isNaN(cursorX)) {
            cursorX = centerX;
            cursorY = centerY;
        } else if (chosen != null) {
            cursorX = chosen.x; // leave from the marker that was read out
            cursorY = chosen.y;
        }
        chosen = null;
        cursorX = Math.max(worldX, Math.min(worldX + worldWidth, cursorX + right * worldWidth * CURSOR_STEP));
        cursorY = Math.max(worldY, Math.min(worldY + worldHeight, cursorY + down * worldHeight * CURSOR_STEP));
        notifyAccessibleChange();
    }

    private void cycleMarker(int direction) {
        List<Marker> named = labelled();
        if (named.isEmpty()) {
            say("No markers to move between");
            return;
        }
        int at = chosen == null ? -1 : named.indexOf(chosen);
        int next = at < 0 ? (direction > 0 ? 0 : named.size() - 1) : ((at + direction) % named.size() + named.size()) % named.size();
        chosen = named.get(next);
        cursorX = chosen.x;
        cursorY = chosen.y;
        notifyAccessibleChange();
    }

    /**
     * Enter: the marker that was read out is clicked, or the map is navigated to the cursor, as a mouse press would.
     */
    private void activate() {
        if (chosen != null && chosen.visible && markers.contains(chosen)) {
            if (onMarkerClick != null) onMarkerClick.accept(chosen);
            say("Selected " + chosen.label);
            return;
        }
        if (Double.isNaN(cursorX)) {
            say("Move the cursor first with the arrow keys");
            return;
        }
        if (onNavigate != null) onNavigate.accept(cursorX, cursorY);
        say("Going to the " + region(cursorX, cursorY));
    }

    private void zoomBy(double factor) {
        double before = zoom;
        setZoom(zoom * factor);
        if (zoom != before) say("Zoom " + Math.round(zoom * 10) / 10.0 + " times");
    }

    /* Theme */

    public Paint getViewportColor() {
        return viewportColor.get();
    }

    /**
     * The color of the camera outline.
     */
    public void setViewportColor(Paint viewportColor) {
        this.viewportColor.set(viewportColor);
    }

    @Override
    public void applyTheme(Theme theme) {
        getStyling().applyThemeBackgroundColor(theme.getFieldBackground());
        getStyling().applyThemeHoverColor(theme.getFieldBackground());
        getStyling().applyThemeBorderColor(theme.getFieldBorder());
        viewportColor.applyTheme(theme.getAccent());
    }

    @Override
    public void followTheme() {
        super.followTheme();
        viewportColor.clearExplicit();
    }

    @Override
    public void update(float delta) {
        super.update(delta);
        if (follow != null) {
            centerX = follow.x;
            centerY = follow.y;
            clampCenter();
        }
        AnimatedPaint.advanceIfAnimated(viewportColor.get(), delta);
        for (Marker m : markers) AnimatedPaint.advanceIfAnimated(m.paint, delta);
    }

    /* Rendering */

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer); // the box and its border

        float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
        float radius = round ? Math.min(w, h) / 2f : getStyling().getCornerRadius();
        renderer.pushClip(x, y, w, h, radius);

        if (backdrop != null) {
            float left = (float) worldToScreenX(worldX), right = (float) worldToScreenX(worldX + worldWidth);
            float a = (float) worldToScreenY(worldY), b = (float) worldToScreenY(worldY + worldHeight);
            renderer.drawImage(backdrop, Math.min(left, right), Math.min(a, b), Math.abs(right - left), Math.abs(b - a), 0f, 1f);
        }
        if (backdropPainter != null) backdropPainter.paint(renderer, this);

        if (viewport != null) {
            float vx0 = (float) worldToScreenX(viewport[0]), vx1 = (float) worldToScreenX(viewport[0] + viewport[2]);
            float vy0 = (float) worldToScreenY(viewport[1]), vy1 = (float) worldToScreenY(viewport[1] + viewport[3]);
            renderer.drawBorder(Math.min(vx0, vx1), Math.min(vy0, vy1), Math.abs(vx1 - vx0), Math.abs(vy1 - vy0), viewportColor.get(), 1.5f, 0f);
        }

        for (Marker m : markers) {
            if (!m.visible) continue;
            float mx = (float) worldToScreenX(m.x), my = (float) worldToScreenY(m.y);
            if (mx < x - m.size || mx > x + w + m.size || my < y - m.size || my > y + h + m.size) continue;
            drawMarker(renderer, m, mx, my);
        }
        renderer.popClip();
    }

    private void drawMarker(Renderer renderer, Marker m, float mx, float my) {
        float s = m.size;
        Paint paint = m.paint == null ? Color.WHITE : m.paint;
        switch (m.shape) {
            case DOT -> renderer.drawCircle(mx, my, s, paint);
            case SQUARE -> renderer.drawQuad(mx - s, my - s, s * 2f, s * 2f, paint, 0f);
            case DIAMOND -> renderer.drawPolygon(new float[]{mx, my - s * 1.3f, mx + s * 1.3f, my, mx, my + s * 1.3f, mx - s * 1.3f, my}, 4, solid(paint));
            case ARROW -> {
                double a = Math.toRadians(m.heading);
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                // Tip ahead, two corners behind: a triangle whose point is the heading.
                float tipX = mx + cos * s * 1.5f, tipY = my + sin * s * 1.5f;
                float lx = mx - cos * s + sin * s, ly = my - sin * s - cos * s;
                float rx = mx - cos * s - sin * s, ry = my - sin * s + cos * s;
                renderer.drawPolygon(new float[]{tipX, tipY, lx, ly, rx, ry}, 3, solid(paint));
            }
        }
    }

    /**
     * The color a polygon is filled with: the paint itself if it is a plain color, else its first color.
     */
    private static Color solid(Paint paint) {
        return switch (AnimatedPaint.resolve(paint)) {
            case Color c -> c;
            case me.piitex.engine.ui.color.LinearGradient g -> g.getColors().get(0);
            case null, default -> Color.WHITE;
        };
    }
}
