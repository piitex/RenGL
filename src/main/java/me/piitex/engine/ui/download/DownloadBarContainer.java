package me.piitex.engine.ui.download;

import me.piitex.engine.io.download.Download;
import me.piitex.engine.io.download.DownloadManager;
import me.piitex.engine.io.download.DownloadState;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.StripeStyle;
import me.piitex.engine.ui.layout.HorizontalLayout;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;

/**
 * A compact download "card" in two rows: the file name and status text share the top line, and the progress bar
 * shares the bottom line with small Pause/Cancel buttons.
 * <pre>
 * backend.zip  45% - 18.0 MB / 40.0 MB - 3.2 MB/s - ETA 6s
 * [██████████░░░░░░░░░░░░░░░░░░░░░░]  [Pause] [Cancel]
 * </pre>
 * It is a {@link VerticalLayout} drawn as a themed panel (the theme's container background, border and corner
 * radius), so it follows theme switches. It is an ordinary container: restyle it with {@link #getStyling()}, drop it in
 * a {@link me.piitex.engine.ui.scroll.ScrollContainer}, or stack several. The parts are exposed
 * ({@link #getNameText()}, {@link #getBar()}, {@link #getStatsText()}) for restyling, and {@link #getFormat()}
 * chooses what the status text shows. Tighten or loosen the card with {@link #setPadding} and {@link #setSpacing}
 * (the card refits itself).
 * <p>
 * The bar sweeps (indeterminate) while the size is unknown and fills once it is known. While bytes are flowing it
 * is a moving green and light-gray striped bar; it settles to solid green on completion, red on failure and orange
 * while paused. Override those with {@link #setActiveColor}, {@link #getStripeStyle()} (or {@link #setStripesEnabled} / {@link #setStripesAnimated} to toggle them),
 * {@link #setDoneColor}, {@link #setFailedColor} and {@link #setPausedColor}. Before the transfer starts (queued,
 * connecting) the bar uses its own themed fill.
 * <p>
 * <b>Controls.</b> <i>Pause</i> becomes <i>Resume</i> while paused and <i>Retry</i> after a failure or cancel, and
 * <i>Cancel</i> discards the partial file. Both vanish once the download completes. Switch them off with
 * {@link #setShowControls}. If the download belongs to a {@link DownloadManager}, pass it to {@link #setManager} so
 * resuming respects the manager's concurrency limit ({@link DownloadListContainer} does this for its cards).
 * <p>
 * The card reads the download once per frame in {@link #update}, so it needs no wiring. Its height follows its
 * content; only the {@code width} is yours to choose.
 */
public class DownloadBarContainer extends VerticalLayout {
    private static final double BUTTON_WIDTH = 58;

    private enum Look { WAITING, DOWNLOADING, DONE, FAILED, PAUSED }

    private final Download download;
    private final DownloadFormat format = new DownloadFormat();
    private final TextOverlay nameText;
    private final ProgressBarOverlay bar;
    private final TextOverlay statsText;
    private final HorizontalLayout titleRow;
    private final HorizontalLayout barRow;
    private final ButtonOverlay pauseButton;
    private final ButtonOverlay cancelButton;
    private DownloadManager manager;
    private boolean showControls = true;

    private final Color themedFill;
    private Color activeColor = new Color(0.20f, 0.70f, 0.30f, 1f);
    private Color doneColor = new Color(0.20f, 0.70f, 0.30f, 1f);
    private Color failedColor = Color.RED;
    private Color pausedColor = Color.ORANGE;
    private StripeStyle stripeStyle = new StripeStyle().setColor(new Color(0.65f, 0.65f, 0.65f, 1f));
    private Look look = Look.WAITING;

    public DownloadBarContainer(Download download, double width) {
        super(width, 0);
        this.download = download;

        super.setPadding(6f);
        super.setSpacing(3f);
        useTheme(); // a themed panel; a bare Container is opaque white, which vanishes white text in a dark theme

        nameText = new TextOverlay(download.getDisplayName(), 14f, Color.BLACK);
        statsText = new TextOverlay("", 12f, Color.GRAY);
        titleRow = row(20);
        titleRow.setSpacing(10f);
        titleRow.addElement(nameText);
        titleRow.addElement(statsText);

        bar = new ProgressBarOverlay(100, 8);
        pauseButton = new ButtonOverlay("Pause", 11f, Color.BLACK, BUTTON_WIDTH, 20);
        pauseButton.onAction(this::togglePause);
        cancelButton = new ButtonOverlay("Cancel", 11f, Color.BLACK, BUTTON_WIDTH, 20);
        cancelButton.onAction(download::cancel);
        barRow = row(20);
        barRow.setSpacing(6f);
        barRow.addElement(bar);
        barRow.addElement(pauseButton);
        barRow.addElement(cancelButton);

        addElement(titleRow);
        addElement(barRow);

        themedFill = bar.getFillColor();
        refresh();
        fitLayout();
    }

    private HorizontalLayout row(double height) {
        HorizontalLayout row = new HorizontalLayout(100, height);
        row.setAlignment(Layout.Alignment.CENTER_LEFT);
        row.getStyling().setBackgroundColor(Color.TRANSPARENT);
        row.getStyling().setHoverColor(Color.TRANSPARENT);
        row.getStyling().setBorderThickness(0f);
        return row;
    }

    /** Sizes the rows, the bar and the card itself from the current width, padding and spacing. */
    private void fitLayout() {
        double inner = getWidth() - 2 * getPadding();
        titleRow.setWidth(inner);
        barRow.setWidth(inner);
        double buttons = showControls ? 2 * (BUTTON_WIDTH + barRow.getSpacing()) : 0;
        bar.setWidth(Math.max(20, inner - buttons));

        double titleHeight = Math.max(20, Math.max(nameText.getHeight(), statsText.getHeight()));
        titleRow.setHeight(titleHeight);
        setHeight(2 * getPadding() + getSpacing() + titleHeight + barRow.getHeight());
    }

    @Override
    public void setPadding(float padding) {
        super.setPadding(padding);
        if (titleRow != null) fitLayout();
    }

    @Override
    public void setSpacing(float spacing) {
        super.setSpacing(spacing);
        if (titleRow != null) fitLayout();
    }

    private void togglePause() {
        switch (download.getState()) {
            case PAUSED, FAILED, CANCELLED -> {
                if (manager != null) manager.resume(download);
                else download.start();
            }
            case COMPLETED, VERIFYING -> {
            }
            default -> download.pause();
        }
    }

    /** Shows or hides the Pause/Resume and Cancel buttons; the bar stretches to fill the freed space. Default true. */
    public void setShowControls(boolean show) {
        if (show == showControls) return;
        showControls = show;
        if (show) {
            barRow.addElement(pauseButton);
            barRow.addElement(cancelButton);
        } else {
            barRow.removeElement(pauseButton);
            barRow.removeElement(cancelButton);
        }
        fitLayout();
    }

    /** The manager the download belongs to, so Resume/Retry re-queue through it. Null (default) restarts the download directly. */
    public void setManager(DownloadManager manager) {
        this.manager = manager;
    }

    public ButtonOverlay getPauseButton() {
        return pauseButton;
    }

    public ButtonOverlay getCancelButton() {
        return cancelButton;
    }

    public Download getDownload() {
        return download;
    }

    /** Which status-line parts show (percent, size, speed, ETA). */
    public DownloadFormat getFormat() {
        return format;
    }

    public TextOverlay getNameText() {
        return nameText;
    }

    public ProgressBarOverlay getBar() {
        return bar;
    }

    public TextOverlay getStatsText() {
        return statsText;
    }

    /** Fill color while downloading, under the stripes. Default: green. */
    public void setActiveColor(Color color) {
        this.activeColor = color;
        if (look == Look.DOWNLOADING) bar.setFillColor(color);
    }

    /**
     * The stripes shown while downloading: color, width, speed, and the {@code enabled}/{@code animated} switches.
     * Edit it at any time, even mid-download, and the bar follows.
     */
    public StripeStyle getStripeStyle() {
        return stripeStyle;
    }

    /**
     * Uses {@code style} for the stripes while downloading instead of this card's own. Share one style between
     * several cards (or a settings panel) to change them all at once.
     */
    public void setStripeStyle(StripeStyle style) {
        if (style == null) return;
        this.stripeStyle = style;
        if (look == Look.DOWNLOADING) bar.setStripes(style);
    }

    /** Shows or hides the stripes while downloading (a solid {@link #setActiveColor} bar when off). Default on. */
    public void setStripesEnabled(boolean enabled) {
        stripeStyle.setEnabled(enabled);
    }

    /** Whether the stripes move while downloading; off leaves them static. Default on. */
    public void setStripesAnimated(boolean animated) {
        stripeStyle.setAnimated(animated);
    }

    /** Color of the stripes while downloading. Default: light gray. */
    public void setStripeColor(Color color) {
        stripeStyle.setColor(color);
    }

    /** Fill color once completed. Default: green. */
    public void setDoneColor(Color color) {
        this.doneColor = color;
    }

    /** Fill color after a failure. Default: red. */
    public void setFailedColor(Color color) {
        this.failedColor = color;
    }

    /** Fill color while paused. Default: orange. */
    public void setPausedColor(Color color) {
        this.pausedColor = color;
    }

    private void refresh() {
        String status = format.format(download);
        if (!status.equals(statsText.getText())) {
            boolean wasEmpty = statsText.getHeight() == 0;
            statsText.setText(status);
            if (wasEmpty && titleRow != null) fitLayout(); // an empty text measures 0 tall until it has content
        }

        DownloadState state = download.getState();
        boolean indeterminate = !download.isSizeKnown()
                && (state == DownloadState.CONNECTING || state == DownloadState.DOWNLOADING);
        if (indeterminate) {
            bar.setIndeterminate(true);
        } else {
            bar.setIndeterminate(false);
            bar.setProgress(download.getProgress());
        }

        String pauseLabel = switch (state) {
            case PAUSED -> "Resume";
            case FAILED, CANCELLED -> "Retry";
            default -> "Pause";
        };
        if (!pauseLabel.equals(pauseButton.getText())) pauseButton.setText(pauseLabel);
        pauseButton.setEnabled(state != DownloadState.COMPLETED && state != DownloadState.VERIFYING);
        cancelButton.setEnabled(!state.isFinished() && state != DownloadState.VERIFYING);

        Look wanted = switch (state) {
            case COMPLETED -> Look.DONE;
            case FAILED -> Look.FAILED;
            case PAUSED -> Look.PAUSED;
            case DOWNLOADING -> Look.DOWNLOADING;
            default -> Look.WAITING;
        };
        if (wanted != look) { // only touch the fill on a change: setFillColor pins it against theme switches
            look = wanted;
            if (wanted == Look.DOWNLOADING) bar.setStripes(stripeStyle);
            else bar.clearStripes();
            bar.setFillColor(switch (wanted) {
                case DONE -> doneColor;
                case FAILED -> failedColor;
                case PAUSED -> pausedColor;
                case DOWNLOADING -> activeColor;
                case WAITING -> themedFill;
            });
        }
    }

    @Override
    public void update(float delta) {
        refresh();
        super.update(delta);
    }
}
