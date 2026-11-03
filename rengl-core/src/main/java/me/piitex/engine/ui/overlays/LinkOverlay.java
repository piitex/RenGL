package me.piitex.engine.ui.overlays;

import me.piitex.engine.ui.text.TextShaping;
import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.text.FontMetricsCompat;
import me.piitex.engine.utils.Browser;

/**
 * Underlined text that opens a web address in the operating system's default browser when
 * clicked (see {@link Browser#open}). It shows a pointer cursor on hover, and the text changes to
 * {@link me.piitex.engine.ui.color.Styling#getTextHoverColor()} while hovered if one is set.
 * <p>
 * The overlay registers its own {@link #onMouseClick} handler, so calling {@code onMouseClick(...)}
 * afterward replaces it and the link stops opening, the same trade-off {@link ButtonOverlay} makes.
 */
public class LinkOverlay extends TextOverlay {
    private String url;

    /**
     * A link that shows {@code url} itself as its text.
     */
    public LinkOverlay(String url) {
        this(url, url);
    }

    public LinkOverlay(String text, String url) {
        this(text, url, 16f, new Color(0.2f, 0.4f, 0.9f, 1f));
    }

    public LinkOverlay(String text, String url, float fontSize, Paint textColor) {
        super(text, fontSize, textColor);
        this.url = url;
        setCursorMode(CursorMode.POINTER);
        setBuiltInClickListener(event -> open());
        setClickOnRelease(true);
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Opens {@link #getUrl()} now, as a click does.
     *
     * @return false if the address was refused or could not be opened
     */
    public boolean open() {
        return Browser.open(url);
    }

    @Override
    protected void drawText(Renderer renderer) {
        super.drawText(renderer);
        String text = getText();
        if (text == null || text.isEmpty()) return;
        Paint paint = resolveTextColor(getTextColor());
        if (!(paint instanceof Color color)) return;

        org.jetbrains.skia.Font font = getOrBuildFont();
        FontMetricsCompat m = FontMetricsCompat.of(font);
        float textWidth = TextShaping.width(font, text);
        float x = (float) getX() + Math.max(0f, (float) getWidth() - textWidth) * textAlignH(0f);
        float y = (float) getY();
        if (getTextAlignment() != null) {
            y += Math.max(0f, (float) getHeight() - (-m.getAscent() + m.getDescent())) * textAlignV(0f);
        }
        float baseline = y - m.getAscent();
        float thickness = Math.max(1f, getFontSize() / 14f);
        renderer.drawLine(x, baseline + thickness * 1.5f, x + textWidth, baseline + thickness * 1.5f, color, thickness);
    }
}
