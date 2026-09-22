package me.piitex.engine.ui.overlays;

import me.piitex.engine.Window;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Focusable;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.FontScalable;
import me.piitex.engine.ui.theme.Overridable;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.ui.theme.Themeable;
import org.jetbrains.skia.Typeface;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * A color swatch that opens a picker panel: a saturation/brightness square, a hue bar, an
 * optional opacity bar, a hex field and a row of preset swatches. The closed box shows the
 * current color (over a checkerboard while it is translucent) and, optionally, its hex code.
 * <p>
 * The panel is shown through {@link Window#showPopup}, so like {@link DropdownOverlay}'s
 * list it paints over everything, is not clipped by a parent scroll container, and closes
 * on an outside click or Escape. Dragging in the square/bars changes the color live and
 * fires {@link #onChange} continuously; typing a valid hex code ({@code #RGB}, {@code
 * #RRGGBB}, or {@code #RRGGBBAA} when opacity is enabled) does too.
 * <p>
 * The box is themed like {@link TextFieldOverlay}'s field, and the panel reuses this
 * overlay's own {@link #getStyling()} background and border, so pinning them restyles both.
 * This overlay registers its own {@link #onMouseClick} handler to open and close the
 * panel; replacing that handler stops the picker from opening, so use {@link #onChange}
 * instead. It is {@link Focusable} and a Tab stop, and Enter or Space opens it.
 * <p>
 * The color is held as hue/saturation/brightness, so dragging to black or grey and back keeps
 * your hue. {@link #getColor()} converts to a plain {@link Color}.
 */
public class ColorPickerOverlay extends Overlay implements Themeable, Focusable, FontScalable {
    private static final Color[] DEFAULT_PRESETS = {
            hex(0xE53935), hex(0xFB8C00), hex(0xFDD835), hex(0x43A047),
            hex(0x00ACC1), hex(0x1E88E5), hex(0x8E24AA), hex(0x000000)
    };

    private float hue;          // 0..360
    private float sat;          // 0..1
    private float val = 1f;     // 0..1
    private float alpha = 1f;   // 0..1
    private boolean alphaEnabled = true;
    private boolean showHex = true;
    private Color[] presets = DEFAULT_PRESETS;
    private Consumer<Color> onChange;
    private float fontSize = 14f;
    private final Overridable<Color> textColor = new Overridable<>(Color.BLACK);

    private org.jetbrains.skia.Font font;
    private Typeface fontTypeface;
    private PickerPopup popup;

    public ColorPickerOverlay(double width, double height) {
        this(width, height, Color.BLUE);
    }

    public ColorPickerOverlay(double width, double height, Color initial) {
        setWidth(width);
        setHeight(height);
        setCursorMode(CursorMode.POINTER);
        setFocusTraversable(true);
        setFromColor(initial == null ? Color.BLACK : initial);
        onMouseClick(event -> toggleOpen());
        ThemeManager.register(this);
    }

    /* Value */

    /**
     * The current color, converted from hue/saturation/brightness/opacity.
     */
    public Color getColor() {
        return fromHsv(hue, sat, val, alphaEnabled ? alpha : 1f);
    }

    /**
     * Sets the color programmatically. {@link #onChange} does not fire. Alpha is ignored while opacity is disabled.
     */
    public void setColor(Color color) {
        setFromColor(color == null ? Color.BLACK : color);
        if (popup != null) popup.syncHex();
    }

    /**
     * Fired with the new color whenever the USER changes it (dragging, typing a hex code, picking a preset). One listener is held at a time.
     */
    public void onChange(Consumer<Color> onChange) {
        this.onChange = onChange;
    }

    /**
     * {@code "#RRGGBB"}, or {@code "#RRGGBBAA"} when opacity is enabled and the color is translucent.
     */
    public String getHex() {
        return toHex(getColor(), alphaEnabled);
    }

    /**
     * Whether the panel offers an opacity bar and accepts 8-digit hex codes. Default true.
     */
    public void setAlphaEnabled(boolean alphaEnabled) {
        this.alphaEnabled = alphaEnabled;
        close();
    }

    public boolean isAlphaEnabled() {
        return alphaEnabled;
    }

    /**
     * Whether the closed box shows the hex code next to the swatch (otherwise the swatch fills the box). Default true.
     */
    public void setShowHex(boolean showHex) {
        this.showHex = showHex;
    }

    /**
     * The swatches in the panel's bottom rows; pass none to hide the presets. Eight per row.
     */
    public void setPresets(Color... presets) {
        this.presets = presets == null ? new Color[0] : presets.clone();
        close();
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

    public Color getTextColor() {
        return textColor.get();
    }

    public void setTextColor(Color color) {
        textColor.set(color);
    }

    /* Open / close */

    public boolean isOpen() {
        return popup != null;
    }

    public void toggleOpen() {
        if (isOpen()) close();
        else open();
    }

    public void open() {
        if (isOpen()) return;
        Window window = getWindow();
        if (window == null) return;

        PickerPopup p = new PickerPopup();
        float[] at = PopupPlacement.below(this, window, (float) p.getWidth(), (float) p.getHeight());
        p.setPosition(at[0], at[1]);
        popup = p;
        window.showPopup(p, () -> {
            if (window.getFocusedElement() == p.hexField) window.requestFocus(null); // it is about to be disposed
            p.dispose();
            if (popup == p) popup = null;
        });
    }

    public void close() {
        PickerPopup p = popup;
        if (p == null) return;
        Window window = getWindow();
        if (window != null && window.getPopup() == p) {
            window.closePopup(); // the dismiss callback clears `popup`
        }
        popup = null;
    }

    private void changedByUser() {
        if (onChange != null) onChange.accept(getColor());
    }

    /* Color math */

    private static Color hex(int rgb) {
        return new Color(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1f);
    }

    static Color fromHsv(float h, float s, float v, float a) {
        float c = v * s;
        float hp = (((h % 360f) + 360f) % 360f) / 60f;
        float x = c * (1f - Math.abs(hp % 2f - 1f));
        float r = 0, g = 0, b = 0;
        switch ((int) hp) {
            case 0 -> {
                r = c;
                g = x;
            }
            case 1 -> {
                r = x;
                g = c;
            }
            case 2 -> {
                g = c;
                b = x;
            }
            case 3 -> {
                g = x;
                b = c;
            }
            case 4 -> {
                r = x;
                b = c;
            }
            default -> {
                r = c;
                b = x;
            }
        }
        float m = v - c;
        return new Color(r + m, g + m, b + m, a);
    }

    /**
     * Sets HSV from {@code c}, keeping the current hue (and saturation) where the color is grey or black and so has none.
     */
    private void setFromColor(Color c) {
        float max = Math.max(c.getR(), Math.max(c.getG(), c.getB()));
        float min = Math.min(c.getR(), Math.min(c.getG(), c.getB()));
        float d = max - min;
        if (d > 1e-5f) {
            float h;
            if (max == c.getR()) h = ((c.getG() - c.getB()) / d) % 6f;
            else if (max == c.getG()) h = (c.getB() - c.getR()) / d + 2f;
            else h = (c.getR() - c.getG()) / d + 4f;
            hue = ((h * 60f) + 360f) % 360f;
            sat = max <= 0f ? 0f : d / max;
        } else {
            sat = 0f;
        }
        val = max;
        alpha = alphaEnabled ? c.getA() : 1f;
    }

    static String toHex(Color c, boolean withAlpha) {
        String s = String.format("#%02X%02X%02X", Math.round(c.getR() * 255), Math.round(c.getG() * 255), Math.round(c.getB() * 255));
        if (withAlpha && c.getA() < 0.999f) s += String.format("%02X", Math.round(c.getA() * 255));
        return s;
    }

    /**
     * Parses {@code #RGB}, {@code #RRGGBB} or {@code #RRGGBBAA} (the '#' is optional), or null if it is not one of those.
     */
    static Color parseHex(String text, boolean allowAlpha) {
        if (text == null) return null;
        String s = text.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 3) {
                int r = Integer.parseInt(s.substring(0, 1), 16) * 17;
                int g = Integer.parseInt(s.substring(1, 2), 16) * 17;
                int b = Integer.parseInt(s.substring(2, 3), 16) * 17;
                return new Color(r / 255f, g / 255f, b / 255f, 1f);
            }
            if (s.length() == 6 || (s.length() == 8 && allowAlpha)) {
                int r = Integer.parseInt(s.substring(0, 2), 16);
                int g = Integer.parseInt(s.substring(2, 4), 16);
                int b = Integer.parseInt(s.substring(4, 6), 16);
                int a = s.length() == 8 ? Integer.parseInt(s.substring(6, 8), 16) : 255;
                return new Color(r / 255f, g / 255f, b / 255f, a / 255f);
            }
        } catch (NumberFormatException ignored) {
            // fall through: not a valid code yet (the user is still typing)
        }
        return null;
    }

    /**
     * A light and dark grey checkerboard, clipped to the rectangle, which a translucent color is shown over.
     */
    static void drawChecker(Renderer renderer, float x, float y, float w, float h) {
        Color light = new Color(0.97f, 0.97f, 0.97f, 1f), dark = new Color(0.78f, 0.78f, 0.78f, 1f);
        float cell = 6f;
        renderer.pushClip(x, y, w, h);
        renderer.drawQuad(x, y, w, h, light, 0f);
        for (int row = 0; row * cell < h; row++) {
            for (int col = 0; col * cell < w; col++) {
                if ((row + col) % 2 == 1) renderer.drawQuad(x + col * cell, y + row * cell, cell, cell, dark, 0f);
            }
        }
        renderer.popClip();
    }

    /* Theming / rendering */

    @Override
    public void applyTheme(Theme theme) {
        getStyling().applyThemeBackgroundColor(theme.getFieldBackground());
        getStyling().applyThemeHoverColor(theme.getFieldHoverBackground());
        getStyling().applyThemeBorderColor(theme.getFieldBorder());
        getStyling().applyThemeBorderThickness(theme.getBorderThickness());
        getStyling().applyThemeCornerRadius(theme.getCornerRadius());
        textColor.applyTheme(theme.getText());
    }

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

    @Override
    public void render(Renderer renderer) {
        if (!isEnabled()) return;
        super.render(renderer);

        float x = (float) getX(), y = (float) getY(), w = (float) getWidth(), h = (float) getHeight();
        float pad = 4f;
        float sh = h - 2 * pad;
        float sw = showHex ? Math.min(sh, w - 2 * pad) : w - 2 * pad;
        float sx = x + pad, sy = y + pad;
        Color color = getColor();
        float radius = Math.max(2f, getStyling().getCornerRadius() - 2f);

        if (color.getA() < 0.999f) drawChecker(renderer, sx, sy, sw, sh);
        renderer.drawQuad(sx, sy, sw, sh, color, radius);
        if (getStyling().getBorderColor() instanceof Color border) {
            renderer.drawBorder(sx, sy, sw, sh, border, 1f, radius);
        }

        if (showHex && w - sw - 3 * pad > 20f) {
            org.jetbrains.skia.Font f = getFont();
            String hex = toHex(color, alphaEnabled);
            float textH = f.getMetrics().getHeight();
            renderer.pushClip(x, y, w - pad, h);
            renderer.drawText(hex, sx + sw + 8f, y + (h - textH) / 2f, f, resolveTextColor(textColor.get()));
            renderer.popClip();
        }
    }

    /* Keyboard */

    @Override
    public void onFocusGained() {
    }

    @Override
    public void onFocusLost() {
    }

    @Override
    public void onCharTyped(int codepoint) {
    }

    @Override
    public void onKeyPressed(int key, int action, int mods) {
        if (action != GLFW.GLFW_PRESS) return;
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) toggleOpen();
    }

    /**
     * The picker panel: a top-level Container (as {@link Window#showPopup} requires) that draws
     * the square and bars itself, hosts the hex {@link TextFieldOverlay} as a real child, and is
     * its own drag target for the square and bars.
     */
    private final class PickerPopup extends Container {
        private static final float W = 240f, PAD = 12f;
        private static final float SV_W = W - 2 * PAD, SV_H = 150f, BAR_H = 14f;
        private static final float PRESET = 20f, PRESET_GAP = 6f;
        private static final int PER_ROW = 8;

        private static final int NONE = 0, SV = 1, HUE = 2, ALPHA = 3;

        private final float hueY = PAD + SV_H + 10f;
        private final float alphaY = hueY + BAR_H + 8f;
        private final float previewY;
        private final float presetsY;
        private final Color original;
        private final TextFieldOverlay hexField;
        private int dragRegion = NONE;

        PickerPopup() {
            super(0, 0, W, 0);
            ColorPickerOverlay owner = ColorPickerOverlay.this;
            original = owner.getColor();

            previewY = (owner.alphaEnabled ? alphaY + BAR_H : hueY + BAR_H) + 12f;
            float rows = (float) Math.ceil(owner.presets.length / (double) PER_ROW);
            presetsY = previewY + 28f + 12f;
            float presetsH = rows == 0 ? 0f : rows * PRESET + (rows - 1) * PRESET_GAP;
            setHeight(rows == 0 ? previewY + 28f + PAD : presetsY + presetsH + PAD);

            Paint bg = owner.getStyling().getBackgroundColor();
            getStyling().setBackgroundColor(bg);
            getStyling().setHoverColor(bg); // hovering the panel must not tint it
            getStyling().setBorderColor(owner.getStyling().getBorderColor());
            getStyling().setBorderThickness(owner.getStyling().getBorderThickness());
            getStyling().setCornerRadius(owner.getStyling().getCornerRadius());
            setCursorMode(CursorMode.DEFAULT);

            hexField = new TextFieldOverlay(PAD + 52f, previewY, SV_W - 52f, 28);
            hexField.setMaxLength(9);
            hexField.setPlaceholder("#RRGGBB");
            hexField.onTextChanged(text -> {
                Color parsed = parseHex(text, owner.alphaEnabled);
                if (parsed == null) return;
                owner.setFromColor(parsed);
                owner.changedByUser();
            });
            addElement(hexField);
            syncHex();

            onMouseClick(event -> {
                if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
                int index = presetAt(event.getMouseX(), event.getMouseY());
                if (index >= 0) {
                    owner.setFromColor(owner.presets[index]);
                    syncHex();
                    owner.changedByUser();
                }
            });
        }

        void syncHex() {
            hexField.setText(toHex(getColor(), alphaEnabled));
        }

        void dispose() {
            hexField.dispose();
        }

        private int presetAt(double mx, double my) {
            double lx = mx - getX(), ly = my - getY() - presetsY;
            if (lx < PAD || ly < 0) return -1;
            int col = (int) ((lx - PAD) / (PRESET + PRESET_GAP));
            int row = (int) (ly / (PRESET + PRESET_GAP));
            double inCol = (lx - PAD) - col * (PRESET + PRESET_GAP), inRow = ly - row * (PRESET + PRESET_GAP);
            if (col >= PER_ROW || inCol > PRESET || inRow > PRESET) return -1;
            int index = row * PER_ROW + col;
            return index < presets.length ? index : -1;
        }

        private int regionAt(double mx, double my) {
            double lx = mx - getX(), ly = my - getY();
            if (lx >= PAD && lx <= PAD + SV_W) {
                if (ly >= PAD && ly <= PAD + SV_H) return SV;
                if (ly >= hueY - 2 && ly <= hueY + BAR_H + 2) return HUE;
                if (alphaEnabled && ly >= alphaY - 2 && ly <= alphaY + BAR_H + 2) return ALPHA;
            }
            return NONE;
        }

        @Override
        public boolean canDrag(double x, double y) {
            return regionAt(x, y) != NONE;
        }

        @Override
        public void onDragStart(double x, double y) {
            dragRegion = regionAt(x, y);
            applyDrag(x, y);
        }

        @Override
        public void onDragUpdate(double x, double y) {
            if (dragRegion != NONE) applyDrag(x, y);
        }

        @Override
        public void onDragEnd(double x, double y) {
            dragRegion = NONE;
        }

        private void applyDrag(double mx, double my) {
            ColorPickerOverlay owner = ColorPickerOverlay.this;
            float fx = clamp01((float) ((mx - getX() - PAD) / SV_W));
            switch (dragRegion) {
                case SV -> {
                    owner.sat = fx;
                    owner.val = 1f - clamp01((float) ((my - getY() - PAD) / SV_H));
                }
                case HUE -> owner.hue = fx * 360f;
                case ALPHA -> owner.alpha = fx;
                default -> {
                    return;
                }
            }
            syncHex();
            owner.changedByUser();
        }

        private float clamp01(float v) {
            return Math.max(0f, Math.min(1f, v));
        }

        @Override
        public void render(Renderer renderer) {
            renderBackground(renderer);
            ColorPickerOverlay owner = ColorPickerOverlay.this;
            float ax = (float) getX(), ay = (float) getY();
            Color border = owner.getStyling().getBorderColor() instanceof Color c ? c : Color.GRAY;

            // Saturation/brightness square: one vertical white-to-black strip per few pixels, at each saturation.
            float sx = ax + PAD, sy = ay + PAD;
            for (float i = 0; i < SV_W; i += 3f) {
                float strip = Math.min(3f, SV_W - i);
                Color top = fromHsv(owner.hue, (i + strip / 2f) / SV_W, 1f, 1f);
                renderer.drawGradient(sx + i, sy, strip, SV_H, new LinearGradient(top, Color.BLACK), 0f);
            }
            renderer.drawBorder(sx, sy, SV_W, SV_H, border, 1f, 0f);
            drawRingMarker(renderer, sx + owner.sat * SV_W, sy + (1f - owner.val) * SV_H);

            // Hue bar.
            float hy = ay + hueY;
            for (float i = 0; i < SV_W; i += 2f) {
                renderer.drawQuad(sx + i, hy, Math.min(2f, SV_W - i), BAR_H, fromHsv(i / SV_W * 360f, 1f, 1f, 1f), 0f);
            }
            renderer.drawBorder(sx, hy, SV_W, BAR_H, border, 1f, 0f);
            drawBarMarker(renderer, sx + owner.hue / 360f * SV_W, hy);

            // Opacity bar over a checkerboard.
            if (owner.alphaEnabled) {
                float oy = ay + alphaY;
                drawChecker(renderer, sx, oy, SV_W, BAR_H);
                for (float i = 0; i < SV_W; i += 2f) {
                    Color c = fromHsv(owner.hue, owner.sat, owner.val, i / SV_W);
                    renderer.drawQuad(sx + i, oy, Math.min(2f, SV_W - i), BAR_H, c, 0f);
                }
                renderer.drawBorder(sx, oy, SV_W, BAR_H, border, 1f, 0f);
                drawBarMarker(renderer, sx + owner.alpha * SV_W, oy);
            }

            // Preview: the color as it was when the panel opened on the left, the current one on the right.
            float py = ay + previewY;
            drawChecker(renderer, sx, py, 44f, 28f);
            renderer.pushClip(sx, py, 22f, 28f);
            renderer.drawQuad(sx, py, 44f, 28f, original, 4f);
            renderer.popClip();
            renderer.pushClip(sx + 22f, py, 22f, 28f);
            renderer.drawQuad(sx, py, 44f, 28f, owner.getColor(), 4f);
            renderer.popClip();
            renderer.drawBorder(sx, py, 44f, 28f, border, 1f, 4f);

            // Presets.
            for (int i = 0; i < owner.presets.length; i++) {
                float px = sx + (i % PER_ROW) * (PRESET + PRESET_GAP);
                float pyy = ay + presetsY + (i / PER_ROW) * (PRESET + PRESET_GAP);
                drawChecker(renderer, px, pyy, PRESET, PRESET);
                renderer.drawQuad(px, pyy, PRESET, PRESET, owner.presets[i], 4f);
                renderer.drawBorder(px, pyy, PRESET, PRESET, border, 1f, 4f);
            }

            renderChildren(renderer);
        }

        private void drawRingMarker(Renderer renderer, float cx, float cy) {
            renderer.drawBorder(cx - 7f, cy - 7f, 14f, 14f, new Color(0f, 0f, 0f, 0.55f), 1.5f, 7f);
            renderer.drawBorder(cx - 6f, cy - 6f, 12f, 12f, Color.WHITE, 2f, 6f);
        }

        private void drawBarMarker(Renderer renderer, float cx, float barY) {
            renderer.drawBorder(cx - 4f, barY - 3f, 8f, BAR_H + 6f, new Color(0f, 0f, 0f, 0.55f), 1.5f, 4f);
            renderer.drawBorder(cx - 3f, barY - 2f, 6f, BAR_H + 4f, Color.WHITE, 2f, 3f);
        }
    }
}
