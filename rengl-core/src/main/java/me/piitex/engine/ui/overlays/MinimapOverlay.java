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
public class MinimapOverlay extends Overlay implements Themeable, Draggable, Scrollable {
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

    public MinimapOverlay(double width, double height) {
        setWidth(width);
        setHeight(height);
        setCursorMode(CursorMode.POINTER);
        getStyling().applyThemeBorderThickness(1f);
        getStyling().applyThemeCornerRadius(4f);
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
            return;
        }
        navigating = true;
        navigate(mx, my);
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
