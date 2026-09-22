package me.piitex.engine.ui.download;

import me.piitex.engine.io.download.Download;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.overlays.TextOverlay;

import java.util.function.Function;

/**
 * The most compact download display: a single line of text that keeps itself current, such as
 * {@code "backend.zip - 45% - 18.0 MB / 40.0 MB - 3.2 MB/s - ETA 6s"}. Drop it in a status bar, a footer or a
 * list row. Which parts show is controlled through {@link #getFormat()}; for a completely custom line, give
 * {@link #setFormatter} a function of the {@link Download}.
 * <pre>{@code
 * DownloadTextOverlay line = new DownloadTextOverlay(download);
 * line.getFormat().setShowEta(false);
 * container.addElement(line);
 * }</pre>
 * It reads the download once per frame from {@link #update}, so nothing has to be wired up and the render thread
 * never blocks on the transfer.
 */
public class DownloadTextOverlay extends TextOverlay {
    private final Download download;
    private final DownloadFormat format = new DownloadFormat();
    private boolean showName = true;
    private Function<Download, String> formatter;

    public DownloadTextOverlay(Download download) {
        this(download, 16f, Color.BLACK);
    }

    public DownloadTextOverlay(Download download, float fontSize, Paint textColor) {
        super("", fontSize, textColor);
        this.download = download;
        refresh();
    }

    public Download getDownload() {
        return download;
    }

    /**
     * The status-part switches (percent, size, speed, ETA). Ignored if a {@link #setFormatter} function is set.
     */
    public DownloadFormat getFormat() {
        return format;
    }

    /**
     * Whether the file name leads the line. Default true.
     */
    public void setShowName(boolean showName) {
        this.showName = showName;
    }

    /**
     * Replaces the whole line with your own text, or null to go back to the built-in format.
     */
    public void setFormatter(Function<Download, String> formatter) {
        this.formatter = formatter;
    }

    private void refresh() {
        String text;
        if (formatter != null) {
            text = formatter.apply(download);
        } else {
            String status = format.format(download);
            text = showName ? download.getDisplayName() + "  " + status : status;
        }
        if (!text.equals(getText())) setText(text); // setText re-measures; skip it while nothing changed
    }

    @Override
    public void update(float delta) {
        refresh();
        super.update(delta);
    }
}
