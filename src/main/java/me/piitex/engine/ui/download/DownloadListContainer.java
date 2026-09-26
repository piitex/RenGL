package me.piitex.engine.ui.download;

import me.piitex.engine.io.download.Download;
import me.piitex.engine.io.download.DownloadManager;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.TextOverlay;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A live list of every download in a {@link DownloadManager}: one {@link DownloadBarContainer} card per download,
 * with an optional summary line on top ({@code "2 active - 5 total - 6.4 MB/s - 71%"}). Cards appear when a
 * download is added to the manager and disappear when it is removed ({@code manager.remove} / {@code clearFinished}),
 * so the list needs no bookkeeping from you.
 * <p>
 * By default the container resizes its own height to fit its cards, which is what a
 * {@link me.piitex.engine.ui.scroll.ScrollContainer} needs to know how far it can scroll; turn that off with
 * {@link #setAutoHeight} to keep a fixed size. Configure the cards with {@link #setCardCustomizer}.
 * <pre>{@code
 * DownloadManager manager = new DownloadManager(2);
 * ScrollContainer scroll = new ScrollContainer(400, 300);
 * scroll.addElement(new DownloadListContainer(manager, 400));
 * manager.add(URI.create("https://example.com/a.zip"), dir.resolve("a.zip"));
 * }</pre>
 */
public class DownloadListContainer extends VerticalLayout {
    private final DownloadManager manager;
    private final Map<Download, DownloadBarContainer> cards = new LinkedHashMap<>();
    private final TextOverlay summary = new TextOverlay("", 13f, Color.GRAY);
    private boolean showSummary = true;
    private boolean autoHeight = true;
    private java.util.function.Consumer<DownloadBarContainer> cardCustomizer;

    public DownloadListContainer(DownloadManager manager, double width) {
        this(manager, width, 200);
    }

    public DownloadListContainer(DownloadManager manager, double width, double height) {
        super(width, height);
        this.manager = manager;
        setPadding(4f);
        setSpacing(6f);
        // Just a stack of cards: no panel of its own. applyTheme below keeps it bare even if it is themed.
        getStyling().applyThemeBackgroundColor(Color.TRANSPARENT);
        getStyling().applyThemeHoverColor(Color.TRANSPARENT);
        getStyling().applyThemeBorderThickness(0f);
        addElement(summary, -1); // negative key: always sorts ahead of the cards
    }

    public DownloadManager getManager() {
        return manager;
    }

    /**
     * Shows or hides the summary line above the cards. Default true.
     */
    public void setShowSummary(boolean showSummary) {
        if (showSummary == this.showSummary) return;
        this.showSummary = showSummary;
        if (showSummary) addElement(summary, -1);
        else removeElement(summary);
    }

    public TextOverlay getSummaryText() {
        return summary;
    }

    /**
     * Whether the container's height follows its content. Default true.
     */
    public void setAutoHeight(boolean autoHeight) {
        this.autoHeight = autoHeight;
    }

    /**
     * Called once for each card as it is created: restyle it, change its format or colors. Applies to future cards only.
     */
    public void setCardCustomizer(java.util.function.Consumer<DownloadBarContainer> customizer) {
        this.cardCustomizer = customizer;
    }

    private void sync() {
        Set<Download> present = new HashSet<>(manager.getDownloads());

        for (var it = cards.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (!present.contains(entry.getKey())) {
                removeElement(entry.getValue());
                it.remove();
            }
        }
        for (Download download : manager.getDownloads()) {
            if (cards.containsKey(download)) continue;
            DownloadBarContainer card = new DownloadBarContainer(download, getWidth() - 2 * getPadding());
            card.setManager(manager);
            if (cardCustomizer != null) cardCustomizer.accept(card);
            cards.put(download, card);
            addElement(card);
        }

        if (showSummary) {
            String text = manager.getActiveCount() + " active - " + manager.getDownloads().size() + " total - "
                    + DownloadFormat.speed(manager.getTotalBytesPerSecond()) + " - "
                    + Math.round(manager.getOverallProgress() * 100) + "%";
            if (!text.equals(summary.getText())) summary.setText(text);
        }

        if (autoHeight) {
            double content = 2 * getPadding();
            int count = 0;
            for (Element child : getElements().values()) {
                content += child.getHeight();
                count++;
            }
            if (count > 1) content += getSpacing() * (count - 1);
            if (content != getHeight()) setHeight(content);
        }
    }

    @Override
    public void update(float delta) {
        sync();
        super.update(delta);
    }

    /**
     * Leaves the box alone so the list stays bare, even after {@link #useTheme()}.
     */
    @Override
    public void applyTheme(Theme theme) {
    }
}
