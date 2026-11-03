package me.piitex.engine.ui.charts;

import me.piitex.engine.ui.text.TextShaping;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.MouseTracker;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.overlays.Overlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.ui.text.TextAlign;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;

/**
 * What the charts have in common: a title, a legend that switches series on and off, a color palette, a hover tooltip,
 * number formatting, theming, and the easing that grows a chart in when it appears and slides it to new numbers when
 * they change. {@link LineChartOverlay}, {@link BarChartOverlay} and {@link PieChartOverlay} add the drawing.
 * <p>
 * A chart is drawn entirely by the engine's {@link Renderer}, so it has no dependency beyond the UI, scales to any size
 * and takes part in layouts, scroll containers and snapshots like any other overlay. It reads its data every frame,
 * so a live chart is updated by changing the data and nothing else.
 * <p>
 * The chart is transparent by default and takes its text and grid colors from the theme; set a background through
 * {@link #getStyling()} for a panel. The tooltip is drawn by the chart itself, not through {@link #setTooltip}, since
 * it follows the pointer from point to point.
 */
public abstract class ChartOverlay extends Overlay implements Themeable, FontScalable {
    /**
     * The colors series (and pie slices) take in turn, unless they have one of their own. Chosen to stay apart from each other, and to be readable on light and dark backgrounds alike.
     */
    public static final List<Color> DEFAULT_PALETTE = List.of(
            rgb(0x4E79A7), rgb(0xF28E2B), rgb(0xE15759), rgb(0x76B7B2), rgb(0x59A14F),
            rgb(0xEDC948), rgb(0xB07AA1), rgb(0xFF9DA7), rgb(0x9C755F), rgb(0xBAB0AC));

    private static final float LEGEND_SWATCH = 10f;

    protected final List<ChartSeries> series = new ArrayList<>();
    protected final List<String> categories = new ArrayList<>();

    private String title = "";
    private LegendPosition legendPosition = LegendPosition.BOTTOM;
    private List<Color> palette = DEFAULT_PALETTE;
    private final Overridable<Color> textColor = new Overridable<>(Color.GRAY);
    private final Overridable<Color> gridColor = new Overridable<>(new Color(0.5f, 0.5f, 0.5f, 0.2f));
    private Paint tooltipBackground = new Color(0.2f, 0.2f, 0.22f, 0.97f);
    private Paint tooltipBorder = new Color(0.4f, 0.4f, 0.44f, 1f);
    private Paint tooltipText = Color.WHITE;
    private boolean animated = true;
    private float animationSpeed = 6f;
    private float padding = 14f;
    private DoubleFunction<String> valueFormat;
    private Consumer<ChartHit> onPointClick;

    private float titleSize = 16f, labelSize = 12f;
    private org.jetbrains.skia.Font titleFont, labelFont;
    private float titleHeight, labelHeight;
    private boolean disposed;

    // layout, in the chart's own coordinates (0, 0 is its top-left corner)
    private boolean layoutDirty = true;
    private long lastSignature;
    private double lastWidth = -1, lastHeight = -1;
    private float[] legendX = new float[0], legendY = new float[0], legendWidth = new float[0];
    private float legendRowHeight;
    protected double dataScale = 1;

    // pointer and easing state
    private boolean pointerOverride;
    private double overrideX = Double.NaN, overrideY = Double.NaN;
    protected double pointerX = Double.NaN, pointerY = Double.NaN;
    private int legendHover = -1;
    private float[] focus = new float[0];
    private boolean tooltipWanted;
    private float tooltipAlpha;

    protected ChartOverlay(double width, double height) {
        setWidth(width);
        setHeight(height);
        rebuildFonts();

        // A chart draws its own plot; the box around it is the owner's choice.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        setBuiltInClickListener(event -> {
            if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            double x = toLocalX(event.getMouseX()) - getX(), y = toLocalY(event.getMouseY()) - getY();
            int entry = legendAt(x, y);
            if (entry >= 0) {
                toggleLegend(entry);
                layoutDirty = true;
                return;
            }
            if (onPointClick == null) return;
            pointerMoved(x, y); // what is under the click itself, not under wherever the pointer was last frame
            ChartHit hit = hitAt(x, y);
            if (hit != null) onPointClick.accept(hit);
        });
        ThemeManager.register(this);
    }

    // ---- data shared by every chart -----------------------------------------------------------------------------

    /**
     * The text above the plot, or empty (the default) for none.
     */
    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
        layoutDirty = true;
    }

    /**
     * Replaces the category labels: the names along the bottom of a line or bar chart, or the pie's slices. A chart
     * with more data than labels numbers the rest from 1.
     */
    public void setCategories(String... labels) {
        setCategories(List.of(labels));
    }

    public void setCategories(List<String> labels) {
        categories.clear();
        categories.addAll(labels);
    }

    /**
     * Adds one label at the end. For a chart that grows, add the label and then a value to each series.
     */
    public void addCategory(String label) {
        categories.add(label == null ? "" : label);
    }

    /**
     * Removes the first {@code count} labels, for a chart that scrolls. Remove the same number from each series with
     * {@link ChartSeries#removeFirst}.
     */
    public void removeFirstCategories(int count) {
        count = Math.clamp(count, 0, categories.size());
        categories.subList(0, count).clear();
    }

    /**
     * The labels as a read-only list.
     */
    public List<String> getCategories() {
        return List.copyOf(categories);
    }

    /**
     * The label of category {@code index}: the one that was set, or its number counting from 1.
     */
    public String categoryLabel(int index) {
        return index >= 0 && index < categories.size() ? categories.get(index) : String.valueOf(index + 1);
    }

    /**
     * How many categories there are: the labels, or the longest series if there are more values than labels.
     */
    public int categoryCount() {
        int count = categories.size();
        for (ChartSeries s : series) count = Math.max(count, s.size());
        return count;
    }

    // ---- look ---------------------------------------------------------------------------------------------------

    public LegendPosition getLegendPosition() {
        return legendPosition;
    }

    public void setLegendPosition(LegendPosition position) {
        this.legendPosition = position == null ? LegendPosition.NONE : position;
        layoutDirty = true;
    }

    public List<Color> getPalette() {
        return palette;
    }

    /**
     * The colors series take in turn, repeating when there are more series than colors.
     */
    public void setPalette(List<Color> palette) {
        if (palette == null || palette.isEmpty()) throw new IllegalArgumentException("A palette needs at least one color");
        this.palette = List.copyOf(palette);
    }

    public void setPalette(Color... colors) {
        setPalette(List.of(colors));
    }

    /**
     * The color of series {@code index}: its own, or the palette's.
     */
    public Color seriesColor(int index) {
        Color own = index >= 0 && index < series.size() ? series.get(index).getColor() : null;
        return own != null ? own : palette.get(Math.floorMod(index, palette.size()));
    }

    public Color getTextColor() {
        return textColor.get();
    }

    /**
     * Pins the color of the title, labels and legend against theme switches. See {@link Overridable}.
     */
    public void setTextColor(Color color) {
        textColor.set(color);
    }

    public Color getGridColor() {
        return gridColor.get();
    }

    /**
     * Pins the color of the grid lines against theme switches.
     */
    public void setGridColor(Color color) {
        gridColor.set(color);
    }

    public float getPadding() {
        return padding;
    }

    /**
     * The space between the edge of the chart and what it draws. Default 14.
     */
    public void setPadding(float padding) {
        this.padding = Math.max(0f, padding);
        layoutDirty = true;
    }

    public DoubleFunction<String> getValueFormat() {
        return valueFormat;
    }

    /**
     * How numbers are written in axis labels, tooltips and data labels. See {@link ChartFormat} for ready-made ones;
     * null (the default) is {@link ChartFormat#plain}.
     */
    public void setValueFormat(DoubleFunction<String> format) {
        this.valueFormat = format;
        layoutDirty = true;
    }

    protected String formatValue(double value) {
        return valueFormat != null ? valueFormat.apply(value) : ChartFormat.plain(value);
    }

    public boolean isAnimated() {
        return animated;
    }

    /**
     * Whether the chart grows in and eases to new data (the default) or jumps straight there.
     */
    public void setAnimated(boolean animated) {
        this.animated = animated;
    }

    public float getAnimationSpeed() {
        return animationSpeed;
    }

    /**
     * How fast the chart eases, as a rate: at 6 (the default) it covers about 90% of the distance in 0.4 seconds, at 12 in 0.2.
     */
    public void setAnimationSpeed(float speed) {
        this.animationSpeed = Math.max(0.1f, speed);
    }

    /**
     * Skips whatever easing is left: the chart shows its data as it is now, and the tooltip is in its final state.
     * For tests and snapshots.
     */
    public void finishAnimation() {
        refreshLayout();
        advance(1f, 0f);
        updatePointer(0f, 1f);
        advance(1f, 0f); // again, so what the pointer is over has settled too
        tooltipAlpha = tooltipWanted ? 1f : 0f;
    }

    /**
     * Called with the point under a left click, if there is one, once a listener is set. Replaces the previous listener.
     */
    public void onPointClick(Consumer<ChartHit> listener) {
        this.onPointClick = listener;
    }

    // ---- pointer ------------------------------------------------------------------------------------------------

    /**
     * Acts as if the pointer were at ({@code x}, {@code y}) in the chart's own coordinates (0, 0 is its top-left
     * corner) until {@link #clearHover()}. For tests and snapshots, where there is no mouse.
     */
    public void hoverAt(double x, double y) {
        pointerOverride = true;
        overrideX = x;
        overrideY = y;
    }

    public void clearHover() {
        pointerOverride = false;
    }

    // ---- hooks for the chart types ------------------------------------------------------------------------------

    /**
     * Works out where the plot goes in what is left after the title and legend, and anything else that depends on the data or the size.
     */
    protected abstract void layoutPlot(float left, float top, float right, float bottom);

    protected abstract void drawPlot(Renderer renderer);

    /**
     * Where a value that is new begins easing from, or NaN to begin at the value.
     */
    protected double baseline() {
        return Double.NaN;
    }

    /**
     * A distance small enough that easing stops and snaps.
     */
    protected double epsilon() {
        return dataScale * 1e-4;
    }

    protected void advanceExtra(float k, float delta) {
    }

    /**
     * The pointer moved to ({@code x}, {@code y}) in chart coordinates, or both NaN when it is off the chart.
     * Work out what it is over, and say whether a tooltip should show with {@link #showTooltip}.
     */
    protected void pointerMoved(double x, double y) {
    }

    protected ChartHit hitAt(double x, double y) {
        return null;
    }

    /**
     * The legend entry that stays at full strength while the rest fade back, or -1 for none. The legend entry under the pointer by default.
     */
    protected int focusedEntry() {
        return legendHover;
    }

    protected long signature() {
        long hash = 17;
        hash = hash * 31 + categories.hashCode();
        hash = hash * 31 + valueFormatKey();
        for (ChartSeries s : series) {
            hash = hash * 31 + s.getName().hashCode();
            hash = hash * 31 + (s.isVisible() ? 1 : 0);
            hash = hash * 31 + s.size();
            for (int i = 0; i < s.size(); i++) hash = hash * 31 + Double.hashCode(s.get(i));
        }
        return hash;
    }

    private int valueFormatKey() {
        return valueFormat == null ? 0 : System.identityHashCode(valueFormat);
    }

    protected int legendCount() {
        return series.size();
    }

    protected String legendLabel(int index) {
        return series.get(index).getName();
    }

    protected Color legendColor(int index) {
        return seriesColor(index);
    }

    protected boolean legendOn(int index) {
        return series.get(index).isVisible();
    }

    protected void toggleLegend(int index) {
        ChartSeries s = series.get(index);
        s.setVisible(!s.isVisible());
    }

    /**
     * How strongly the entries that are not in focus are drawn, 0 to 1.
     */
    protected float focusDim() {
        return 0.28f;
    }

    /**
     * How strongly series {@code index} is drawn, 1 normally and lower while another legend entry is being pointed at.
     */
    protected float focus(int index) {
        return index >= 0 && index < focus.length ? focus[index] : 1f;
    }

    // ---- helpers for the chart types ----------------------------------------------------------------------------

    protected org.jetbrains.skia.Font labelFont() {
        return labelFont;
    }

    protected float labelHeight() {
        return labelHeight;
    }

    protected float titleHeight() {
        return titleHeight;
    }

    protected float textWidth(String text) {
        return text.isEmpty() ? 0f : TextShaping.width(labelFont, text);
    }

    protected Color text() {
        return textColor.get();
    }

    protected Color grid() {
        return gridColor.get();
    }

    protected void drawLabel(Renderer renderer, String text, float x, float y, TextAlign align) {
        renderer.drawText(text, x, y, labelFont, textColor.get(), align);
    }

    protected float width() {
        return (float) getWidth();
    }

    protected float height() {
        return (float) getHeight();
    }

    protected void showTooltip(boolean show) {
        tooltipWanted = show;
    }

    protected float tooltipAlpha() {
        return tooltipAlpha;
    }

    /**
     * One line of a tooltip: a colored square (or none) and some text.
     */
    protected record TooltipRow(Color swatch, String text) {
    }

    /**
     * Draws the tooltip bubble next to ({@code anchorX}, {@code anchorY}), flipped and nudged to stay inside the chart.
     */
    protected void drawTooltipBox(Renderer renderer, String header, List<TooltipRow> rows, float anchorX, float anchorY) {
        float pad = 8f, swatch = 8f, swatchGap = 6f, lineGap = 3f;
        float rowHeight = labelHeight + lineGap;
        float inner = header == null ? 0f : textWidth(header);
        for (TooltipRow row : rows) {
            inner = Math.max(inner, textWidth(row.text()) + (row.swatch() != null ? swatch + swatchGap : 0f));
        }
        float boxW = inner + pad * 2f;
        float boxH = pad * 2f - lineGap + rowHeight * (rows.size() + (header == null ? 0 : 1)) + (header != null ? 2f : 0f);
        float x = anchorX + 14f;
        if (x + boxW > width() - 4f) x = anchorX - 14f - boxW;
        x = Math.max(4f, x);
        float y = Math.clamp(anchorY - boxH / 2f, 4f, Math.max(4f, height() - boxH - 4f));

        renderer.pushAlpha(tooltipAlpha);
        renderer.drawQuad(x, y, boxW, boxH, tooltipBackground, 6f);
        renderer.drawBorder(x, y, boxW, boxH, tooltipBorder, 1f, 6f);
        float ty = y + pad;
        if (header != null) {
            renderer.drawText(header, x + pad, ty, labelFont, tooltipText, TextAlign.LEFT);
            ty += rowHeight + 2f;
        }
        for (TooltipRow row : rows) {
            float tx = x + pad;
            if (row.swatch() != null) {
                renderer.drawQuad(tx, ty + (labelHeight - swatch) / 2f, swatch, swatch, row.swatch(), 2f);
                tx += swatch + swatchGap;
            }
            renderer.drawText(row.text(), tx, ty, labelFont, tooltipText, TextAlign.LEFT);
            ty += rowHeight;
        }
        renderer.popAlpha();
    }

    /**
     * Black or white, whichever is easier to read on {@code background}.
     */
    protected static Color contrastOn(Color background) {
        float luminance = 0.299f * background.getR() + 0.587f * background.getG() + 0.114f * background.getB();
        return luminance > 0.6f ? new Color(0.1f, 0.1f, 0.12f, 1f) : Color.WHITE;
    }

    // ---- frame --------------------------------------------------------------------------------------------------

    @Override
    public void update(float delta) {
        super.update(delta);
        if (disposed) return;
        refreshLayout();
        float k = animated ? (float) (1 - Math.exp(-delta * animationSpeed)) : 1f;
        advance(k, delta);
        updatePointer(delta, animated ? (float) (1 - Math.exp(-delta * 14f)) : 1f);
    }

    private void advance(float k, float delta) {
        double base = baseline(), eps = epsilon();
        for (ChartSeries s : series) s.advance(k, base, eps);
        advanceExtra(k, delta);
    }

    private void updatePointer(float delta, float fastK) {
        double x = Double.NaN, y = Double.NaN;
        if (pointerOverride) {
            x = overrideX;
            y = overrideY;
        } else if (isHovered() && MouseTracker.isInWindow()) {
            x = toLocalX(MouseTracker.getX()) - getX();
            y = toLocalY(MouseTracker.getY()) - getY();
        }
        if (!(x >= 0 && y >= 0 && x <= getWidth() && y <= getHeight())) {
            x = Double.NaN;
            y = Double.NaN;
        }
        pointerX = x;
        pointerY = y;
        legendHover = Double.isNaN(x) ? -1 : legendAt(x, y);
        tooltipWanted = false;
        pointerMoved(x, y);

        int count = legendCount();
        if (focus.length != count) {
            float[] grown = new float[count];
            java.util.Arrays.fill(grown, 1f);
            System.arraycopy(focus, 0, grown, 0, Math.min(focus.length, count));
            focus = grown;
        }
        int focused = focusedEntry();
        for (int i = 0; i < count; i++) {
            float goal = focused < 0 || focused == i ? 1f : focusDim();
            focus[i] += (goal - focus[i]) * fastK;
            if (Math.abs(goal - focus[i]) < 0.005f) focus[i] = goal;
        }
        float goal = tooltipWanted ? 1f : 0f;
        tooltipAlpha += (goal - tooltipAlpha) * fastK;
        if (Math.abs(goal - tooltipAlpha) < 0.01f) tooltipAlpha = goal;
    }

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled() || disposed) return;
        super.render(renderer); // background, border, then the owner's onRender hook
        refreshLayout();

        renderer.pushTranslate((float) getX(), (float) getY());
        renderer.pushClip(0f, 0f, width(), height());
        if (!title.isEmpty()) {
            renderer.drawText(title, padding, padding, titleFont, textColor.get(), TextAlign.LEFT);
        }
        drawLegend(renderer);
        drawPlot(renderer);
        renderer.popClip();
        renderer.popTranslate();
    }

    private void drawLegend(Renderer renderer) {
        int count = legendCount();
        if (legendPosition == LegendPosition.NONE || count == 0 || legendX.length != count) return;
        Color text = textColor.get();
        for (int i = 0; i < count; i++) {
            float x = legendX[i], y = legendY[i];
            if (i == legendHover) {
                renderer.drawQuad(x - 6f, y, legendWidth[i] + 12f, legendRowHeight, text.withAlpha(0.10f), 5f);
            }
            renderer.pushAlpha(legendOn(i) ? 1f : 0.4f);
            renderer.drawQuad(x, y + (legendRowHeight - LEGEND_SWATCH) / 2f, LEGEND_SWATCH, LEGEND_SWATCH, legendColor(i), 3f);
            renderer.drawText(legendLabel(i), x + LEGEND_SWATCH + 6f, y + (legendRowHeight - labelHeight) / 2f, labelFont, text, TextAlign.LEFT);
            renderer.popAlpha();
        }
    }

    // ---- layout -------------------------------------------------------------------------------------------------

    private void refreshLayout() {
        long signature = signature();
        if (layoutDirty || signature != lastSignature || getWidth() != lastWidth || getHeight() != lastHeight) {
            lastSignature = signature;
            lastWidth = getWidth();
            lastHeight = getHeight();
            layoutDirty = false;
            layout();
        }
    }

    /**
     * Asks for the layout to be worked out again before the next frame. The chart does this itself when its data or
     * settings change; a chart type calls it when one of its own settings does.
     */
    protected void invalidateLayout() {
        layoutDirty = true;
    }

    private void layout() {
        double scale = 0;
        for (ChartSeries s : series) {
            for (int i = 0; i < s.size(); i++) {
                double v = s.get(i);
                if (!Double.isNaN(v)) scale = Math.max(scale, Math.abs(v));
            }
        }
        dataScale = Math.max(scale, 1e-9);

        float left = padding, top = padding, right = width() - padding, bottom = height() - padding;
        if (!title.isEmpty()) top += titleHeight + 8f;

        int count = legendCount();
        legendX = new float[count];
        legendY = new float[count];
        legendWidth = new float[count];
        legendRowHeight = labelHeight + 8f;
        if (legendPosition != LegendPosition.NONE && count > 0) {
            float gap = 16f;
            float[] itemWidth = new float[count];
            float widest = 0f;
            for (int i = 0; i < count; i++) {
                itemWidth[i] = LEGEND_SWATCH + 6f + textWidth(legendLabel(i));
                widest = Math.max(widest, itemWidth[i]);
            }
            if (legendPosition == LegendPosition.RIGHT) {
                float x = right - widest;
                float y = top;
                for (int i = 0; i < count; i++) {
                    legendX[i] = x;
                    legendY[i] = y + i * legendRowHeight;
                    legendWidth[i] = itemWidth[i];
                }
                right -= widest + 20f;
            } else {
                float available = Math.max(1f, right - left);
                int[] rowOf = new int[count];
                float[] rowWidth = new float[count];
                int rows = 0;
                float used = 0f;
                for (int i = 0; i < count; i++) {
                    float needed = itemWidth[i] + (used > 0f ? gap : 0f);
                    if (used > 0f && used + needed > available) {
                        rows++;
                        used = 0f;
                        needed = itemWidth[i];
                    }
                    rowOf[i] = rows;
                    used += needed;
                    rowWidth[rows] = used;
                }
                rows++;
                float blockHeight = rows * legendRowHeight;
                float y0 = legendPosition == LegendPosition.TOP ? top : bottom - blockHeight;
                float[] cursor = new float[rows];
                for (int i = 0; i < count; i++) {
                    int row = rowOf[i];
                    float start = left + (available - rowWidth[row]) / 2f;
                    if (cursor[row] > 0f) cursor[row] += gap;
                    legendX[i] = Math.max(left, start) + cursor[row];
                    legendY[i] = y0 + row * legendRowHeight;
                    legendWidth[i] = itemWidth[i];
                    cursor[row] += itemWidth[i];
                }
                if (legendPosition == LegendPosition.TOP) top += blockHeight + 4f;
                else bottom -= blockHeight + 4f;
            }
        }
        layoutPlot(left, top, right, bottom);
    }

    /**
     * Where legend entry {@code index} is, as {x, y, width, height} in chart coordinates. Clicking inside it switches
     * the series or slice off and on. Null when there is no legend or no such entry.
     */
    public float[] legendBounds(int index) {
        refreshLayout();
        if (legendPosition == LegendPosition.NONE || index < 0 || index >= legendX.length) return null;
        return new float[]{legendX[index], legendY[index], legendWidth[index], legendRowHeight};
    }

    private int legendAt(double x, double y) {
        if (legendPosition == LegendPosition.NONE) return -1;
        for (int i = 0; i < legendX.length; i++) {
            if (x >= legendX[i] - 6 && x <= legendX[i] + legendWidth[i] + 6 && y >= legendY[i] && y <= legendY[i] + legendRowHeight) {
                return i;
            }
        }
        return -1;
    }

    // ---- theme and fonts ----------------------------------------------------------------------------------------

    @Override
    public void applyTheme(Theme theme) {
        getStyling().applyThemeCornerRadius(theme.getCornerRadius());
        textColor.applyTheme(theme.getText());
        gridColor.applyTheme(theme.getText().withAlpha(0.14f));
        tooltipBackground = theme.getTooltipBackground();
        tooltipBorder = theme.getTooltipBorder();
        tooltipText = theme.getTooltipText();
    }

    @Override
    public void followTheme() {
        super.followTheme();
        textColor.clearExplicit();
        gridColor.clearExplicit();
    }

    @Override
    public float[] getFontSizes() {
        return new float[]{titleSize, labelSize};
    }

    @Override
    public void setFontSizes(float[] sizes) {
        if (sizes.length < 2) return;
        titleSize = Math.max(1f, sizes[0]);
        labelSize = Math.max(1f, sizes[1]);
        rebuildFonts();
        layoutDirty = true;
    }

    /**
     * Sets the title and label sizes, in points. 16 and 12 by default.
     */
    public void setFontSizes(float titleSize, float labelSize) {
        setFontSizes(new float[]{titleSize, labelSize});
    }

    /**
     * Frees the chart's fonts. Call it when the chart is thrown away for good; it draws nothing afterwards.
     */
    public void dispose() {
        disposed = true;
        if (titleFont != null) titleFont.close();
        if (labelFont != null) labelFont.close();
        titleFont = null;
        labelFont = null;
        onDispose();
    }

    /**
     * Frees whatever a chart type holds besides the fonts the base class has.
     */
    protected void onDispose() {
    }

    private void rebuildFonts() {
        if (disposed) return;
        if (titleFont != null) titleFont.close();
        if (labelFont != null) labelFont.close();
        titleFont = Font.sized(titleSize);
        labelFont = Font.sized(labelSize);
        titleHeight = FontMetricsCompat.of(titleFont).getHeight();
        labelHeight = FontMetricsCompat.of(labelFont).getHeight();
    }

    private static Color rgb(int hex) {
        return new Color(((hex >> 16) & 0xFF) / 255f, ((hex >> 8) & 0xFF) / 255f, (hex & 0xFF) / 255f, 1f);
    }
}
