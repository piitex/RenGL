package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.TableView;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.ComboBoxOverlay;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.TextAlign;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A mixer as a {@link TableView} whose cells hold live controls ({@code Column.setCellWidget}): a slider for the
 * volume, a combo box for the bus, a switch for mute, a meter and a button. Two hundred tracks, but only the rows on
 * screen have controls, and they are handed to other rows as the table scrolls.
 * <ul>
 *   <li>Drag a slider, pick a bus or flip a switch; pressing a control selects its row.</li>
 *   <li>Click a header to sort; the controls follow their tracks.</li>
 *   <li>With the table focused, F2 moves the keyboard into the selected row's slider (the arrow keys move it) and
 *       Escape brings it back to the table.</li>
 * </ul>
 */
public class TableWidgetsDemo {
    private static final List<String> BUSES = List.of("Main", "Drums", "Vocals", "FX");

    private static final class Track {
        final String name;
        double volume;
        String bus;
        boolean muted;
        final double phase;

        Track(String name, double volume, String bus, double phase) {
            this.name = name;
            this.volume = volume;
            this.bus = bus;
            this.phase = phase;
        }
    }

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Table Widgets Demo").setDimensions(860, 560).setSplashEnabled(false));

        double[] time = {0};
        Runnable[] meters = {() -> { }};
        Container root = new Container(860, 560) {
            @Override
            public void update(float delta) {
                super.update(delta);
                time[0] += delta;
                meters[0].run();
            }
        };
        window.addContainer(root);
        TextOverlay title = new TextOverlay("Controls in table cells: 200 tracks, controls only for the rows on screen", 15f, Color.WHITE);
        title.setPosition(16, 12);
        root.addElement(title);
        TextOverlay status = new TextOverlay("", 13f, Color.GRAY);
        status.setPosition(16, 530);
        root.addElement(status);

        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            tracks.add(new Track(String.format("Track %03d", i + 1), (i * 37) % 101, BUSES.get(i % BUSES.size()), i * 0.7));
        }

        TableView<Track> table = new TableView<>(828, 460);
        table.setPosition(16, 40);
        table.setRowHeight(34);
        table.setStriped(true);
        table.addColumn("Name", t -> t.name).setWidth(130);
        table.addColumn("Volume", t -> String.valueOf(Math.round(t.volume))).setWidth(200)
                .setComparator(Comparator.comparingDouble(t -> t.volume))
                .setCellWidget(() -> new SliderOverlay(180, 0, 100), (slider, track) -> {
                    slider.setValue(track.volume);
                    slider.onValueChange(v -> {
                        track.volume = v;
                        status.setText(track.name + " volume " + Math.round(v));
                    });
                });
        table.addColumn("Bus", t -> t.bus).setWidth(140)
                .setCellWidget(() -> {
                    ComboBoxOverlay<String> box = new ComboBoxOverlay<>(120, 26);
                    box.setItems(BUSES);
                    return box;
                }, (box, track) -> {
                    box.setSelectedItem(track.bus);
                    box.onItemSelect(bus -> {
                        track.bus = bus;
                        status.setText(track.name + " to " + bus);
                    });
                });
        table.addColumn("Mute", t -> t.muted ? "muted" : "").setWidth(80).setAlignment(TextAlign.CENTER)
                .setComparator(Comparator.comparing(t -> t.muted))
                .setCellWidgetFill(false, false)
                .setCellWidget(() -> new ToggleSwitchOverlay(40, 22), (toggle, track) -> {
                    toggle.setOn(track.muted);
                    toggle.onToggle(on -> {
                        track.muted = on;
                        status.setText(track.name + (on ? " muted" : " unmuted"));
                    });
                });
        table.addColumn("Level", t -> "").setWidth(140).setSortable(false)
                .setCellWidget(() -> new ProgressBarOverlay(120, 8), (meter, track) -> { });
        table.addColumn("", t -> "").setWidth(90).setSortable(false)
                .setCellWidgetFill(false, false)
                .setCellWidget(() -> new ButtonOverlay("Solo", 64, 24), (button, track) -> button.onMouseClick(_ -> {
                    for (Track other : tracks) other.muted = other != track;
                    table.refresh();
                    status.setText("Solo " + track.name);
                }));
        table.setItems(tracks);
        table.onSelect(track -> status.setText(track == null ? "" : "Selected " + track.name));
        root.addElement(table);

        // the meters move every frame, each from the track it is bound to now
        TableView.Column<Track> level = table.getColumns().get(4);
        meters[0] = () -> {
            for (Track track : table.getItems()) {
                if (table.getCellWidget(track, level) instanceof ProgressBarOverlay meter) {
                    double wave = 0.5 + 0.5 * Math.sin(time[0] * 3 + track.phase);
                    meter.setProgress(track.muted ? 0 : wave * track.volume / 100);
                }
            }
        };

        new Engine().start(window);
    }
}
