package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.TableView;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.menu.MenuItem;
import me.piitex.engine.ui.menu.PopupMenu;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.TextAlign;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.utils.Browser;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A {@link TableView} as a file browser's details view, with the other pieces an archive utility needs:
 * <ul>
 *   <li>Click a header to sort by it and again to reverse; drag the edge between two headers to resize.</li>
 *   <li>Click, Shift-click and Cmd/Ctrl-click select rows; Cmd/Ctrl+A selects all.</li>
 *   <li>Drop files or a folder from Finder / Explorer onto the window ({@code Window.onFileDrop}) to list that folder.</li>
 *   <li>Double-click (or Enter) opens a row with the OS ({@code Browser.open}); right-click offers Show in Finder
 *       ({@code Browser.reveal}).</li>
 * </ul>
 */
public class TableDemo {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private record Entry(Path path, boolean folder, long size, FileTime modified) {
        String name() {
            return path.getFileName() == null ? path.toString() : path.getFileName().toString();
        }
    }

    private static Path directory = Path.of("").toAbsolutePath(); // the folder the demo was started in

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Table Demo").setDimensions(860, 560));

        Container root = new Container(860, 560);
        window.addContainer(root);

        TextOverlay path = new TextOverlay("", 15f, Color.WHITE);
        path.setPosition(16, 12);
        root.addElement(path);
        TextOverlay status = new TextOverlay("", 13f, Color.GRAY);
        status.setPosition(16, 530);
        root.addElement(status);

        TableView<Entry> table = new TableView<>(828, 460);
        table.setPosition(16, 40);
        table.setMultiSelect(true);
        table.setStriped(true);
        table.addColumn("Name", Entry::name).setWidth(380).setMinWidth(120)
                .setIconProvider(entry -> entry.folder() ? Feather.FOLDER : Feather.FILE)
                .setComparator(Comparator.comparing((Entry e) -> !e.folder()).thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        table.addColumn("Size", entry -> entry.folder() ? "" : size(entry.size())).setWidth(110).setAlignment(TextAlign.RIGHT)
                .setComparator(Comparator.comparingLong(Entry::size));
        table.addColumn("Modified", entry -> DATE.format(entry.modified().toInstant())).setWidth(170)
                .setComparator(Comparator.comparing(Entry::modified));
        table.setDimmed(entry -> entry.name().startsWith("."));
        root.addElement(table);

        Runnable listDirectory = () -> {
            List<Entry> entries = new ArrayList<>();
            try (Stream<Path> children = Files.list(directory)) {
                for (Path child : children.toList()) {
                    try {
                        boolean folder = Files.isDirectory(child);
                        entries.add(new Entry(child, folder, folder ? 0 : Files.size(child), Files.getLastModifiedTime(child)));
                    } catch (IOException ignored) {
                        // unreadable entry: skip it
                    }
                }
            } catch (IOException e) {
                status.setText("Cannot read " + directory + ": " + e.getMessage());
            }
            table.setItems(entries);
            table.sortBy(table.getColumns().getFirst(), true);
            path.setText(directory.toString());
        };
        listDirectory.run();

        table.onSelectionChange(selected -> status.setText(selected.size() + " of " + table.getItems().size() + " selected"));
        table.onActivate(entry -> {
            if (entry.folder()) {
                directory = entry.path();
                listDirectory.run();
            } else {
                Browser.open(entry.path());
            }
        });
        table.onKey((key, mods) -> {
            if (key == GLFW.GLFW_KEY_BACKSPACE && directory.getParent() != null) {
                directory = directory.getParent();
                listDirectory.run();
                return true;
            }
            return false;
        });

        PopupMenu menu = new PopupMenu(
                new MenuItem("Open", () -> {
                    if (table.getSelected() != null) Browser.open(table.getSelected().path());
                }),
                new MenuItem("Show in Finder / Explorer", () -> {
                    if (table.getSelected() != null) Browser.reveal(table.getSelected().path());
                }));
        table.setContextMenu(menu);

        window.onFileDrop(paths -> {
            Path first = paths.getFirst();
            directory = Files.isDirectory(first) ? first : first.toAbsolutePath().getParent();
            listDirectory.run();
            table.setSelectedItems(table.getItems().stream().filter(entry -> paths.contains(entry.path())).toList());
            status.setText("Dropped " + paths.size() + (paths.size() == 1 ? " item" : " items"));
        });

        new Engine().start(window);
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }
}
