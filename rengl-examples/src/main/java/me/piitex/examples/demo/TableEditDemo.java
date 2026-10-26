package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.TableView;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.TextAlign;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A {@link TableView} used as an order sheet: ticked rows apart from the selected one, and cells typed into in place.
 * <ul>
 *   <li>The first column is a box for each row, and the box in the header ticks or clears them all. Ticking is not selecting:
 *       click a row to select it, click its box (or press Space) to tick it. The line under the table totals the ticked rows.</li>
 *   <li>Double-click the Item, Quantity or Price column (or press F2 on the selected row) and type. Enter keeps the text,
 *       Escape throws it away, and a click elsewhere keeps it. The table never changes the model: the handler does, and calls
 *       {@code refresh()} so a sorted column follows.</li>
 *   <li>Quantity and Price refuse text that is not a number, by leaving the item as it was. Price shows "$4.50" but is edited as
 *       {@code 4.5} ({@code setEditText}).</li>
 *   <li>The buttons act on the ticked rows: remove them, mark them shipped, or add a new row and edit it straight away
 *       with {@code startEdit}.</li>
 * </ul>
 */
public class TableEditDemo {
    private static final class Line {
        String item;
        int quantity;
        double price;
        boolean shipped;

        Line(String item, int quantity, double price) {
            this.item = item;
            this.quantity = quantity;
            this.price = price;
        }

        double total() {
            return quantity * price;
        }
    }

    private static final List<Line> lines = new ArrayList<>(List.of(
            new Line("Copper wire, 50 m", 4, 18.50),
            new Line("Brass hinges", 24, 2.25),
            new Line("Oak planks", 12, 31.00),
            new Line("Wood glue", 6, 7.90),
            new Line("Sandpaper pack", 10, 4.50),
            new Line("Iron nails, 1 kg", 8, 5.75),
            new Line("Linseed oil", 3, 14.20),
            new Line("Clamps", 6, 12.00)));

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Table editing").setDimensions(860, 600));

        Container root = new Container(860, 600);
        window.addContainer(root);

        TextOverlay title = new TextOverlay("Order sheet", 24f, Color.WHITE);
        title.setPosition(16, 10);
        root.addElement(title);
        TextOverlay hint = new TextOverlay("Tick rows with their boxes. Double-click a cell, or press F2, to change it.", 13f, Color.GRAY);
        hint.setPosition(16, 44);
        root.addElement(hint);
        TextOverlay status = new TextOverlay("", 14f, Color.WHITE);
        status.setPosition(16, 556);
        root.addElement(status);

        TableView<Line> table = new TableView<>(828, 410);
        table.setPosition(16, 70);
        table.setCheckBoxes(true);
        table.setStriped(true);
        table.setItems(lines);

        table.addColumn("Item", line -> line.item).setWidth(330).setMinWidth(120)
                .setEditable((line, text) -> {
                    if (!text.isBlank()) line.item = text.strip();
                    table.refresh();
                })
                .setComparator(Comparator.comparing((Line line) -> line.item, String.CASE_INSENSITIVE_ORDER));
        table.addColumn("Quantity", line -> String.valueOf(line.quantity)).setWidth(110).setAlignment(TextAlign.RIGHT)
                .setEditable((line, text) -> {
                    try {
                        line.quantity = Math.max(0, Integer.parseInt(text.strip()));
                    } catch (NumberFormatException e) {
                        status.setText("\"" + text + "\" is not a whole number, so the quantity stays " + line.quantity);
                        return;
                    }
                    table.refresh();
                })
                .setComparator(Comparator.comparingInt(line -> line.quantity));
        table.addColumn("Price", line -> money(line.price)).setWidth(120).setAlignment(TextAlign.RIGHT)
                .setEditText(line -> String.valueOf(line.price))
                .setEditable((line, text) -> {
                    try {
                        line.price = Math.max(0, Double.parseDouble(text.strip().replace("$", "")));
                    } catch (NumberFormatException e) {
                        status.setText("\"" + text + "\" is not a price, so it stays " + money(line.price));
                        return;
                    }
                    table.refresh();
                })
                .setComparator(Comparator.comparingDouble(line -> line.price));
        table.addColumn("Total", line -> money(line.total())).setWidth(120).setAlignment(TextAlign.RIGHT)
                .setComparator(Comparator.comparingDouble(Line::total));
        table.addColumn("Status", line -> line.shipped ? "Shipped" : "Waiting").setWidth(100)
                .setComparator(Comparator.comparing((Line line) -> line.shipped));
        table.setDimmed(line -> line.shipped);
        root.addElement(table);

        Runnable totals = () -> {
            List<Line> ticked = table.getCheckedItems();
            double sum = 0;
            for (Line line : ticked) sum += line.total();
            status.setText(ticked.isEmpty() ? "Nothing ticked"
                    : ticked.size() + " of " + lines.size() + " ticked, worth " + money(sum));
        };
        table.onCheck((line, on) -> totals.run());
        totals.run();

        ButtonOverlay add = new ButtonOverlay("Add a row", 120, 32);
        add.setPosition(16, 494);
        add.onAction(() -> {
            Line fresh = new Line("New item", 1, 1.00);
            lines.add(fresh);
            table.setItems(lines);
            table.startEdit(fresh, table.getColumns().get(0));
            totals.run();
        });
        root.addElement(add);

        ButtonOverlay ship = new ButtonOverlay("Ship ticked", 130, 32);
        ship.setPosition(148, 494);
        ship.onAction(() -> {
            for (Line line : table.getCheckedItems()) line.shipped = true;
            table.clearChecked();
            table.refresh();
            totals.run();
        });
        root.addElement(ship);

        ButtonOverlay remove = new ButtonOverlay("Remove ticked", 140, 32);
        remove.setPosition(290, 494);
        remove.onAction(() -> {
            lines.removeAll(table.getCheckedItems());
            table.setItems(lines);
            totals.run();
        });
        root.addElement(remove);

        ButtonOverlay checkAll = new ButtonOverlay("Tick all waiting", 150, 32);
        checkAll.setPosition(442, 494);
        checkAll.onAction(() -> {
            table.setCheckedItems(lines.stream().filter(line -> !line.shipped).toList());
            totals.run();
        });
        root.addElement(checkAll);

        new Engine().start(window);
    }

    private static String money(double value) {
        return String.format(Locale.ROOT, "$%.2f", value);
    }
}
