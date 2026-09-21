package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.input.FileDialog;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextAreaOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shows {@link FileDialog}: Import loads a text file into the box, Export writes the box to a file you choose,
 * and Folder just reports the folder you pick. Cancelling any dialog leaves everything as it was.
 */
public class FileDialogDemo {

    static void main() {
        // To reduce console spam add these jvm flags
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny

        Window window = new Window(new WindowOptions("File dialogs").setDimensions(700, 520));
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(700, 520);
        root.getStyling().setBackgroundColor(Color.TRANSPARENT);
        root.getStyling().setHoverColor(Color.TRANSPARENT);
        root.getStyling().setBorderThickness(0f);
        window.addContainer(root);

        TextAreaOverlay editor = new TextAreaOverlay(30, 80, 640, 340);
        editor.setPlaceholder("Type something, or import a text file.");
        root.addElement(editor);

        TextOverlay status = new TextOverlay("Ready.", 14f, Color.WHITE);
        status.setPosition(30, 440);
        root.addElement(status);

        ButtonOverlay importButton = new ButtonOverlay("Import...", 110, 34);
        importButton.setPosition(30, 30);
        importButton.onAction(() -> FileDialog.open()
                .filter("Text files", "txt", "md")
                .defaultPath(Path.of(System.getProperty("user.home")))
                .onSelect(path -> {
                    try {
                        editor.setText(Files.readString(path));
                        status.setText("Imported " + path);
                    } catch (IOException e) {
                        status.setText("Could not read " + path.getFileName() + ": " + e.getMessage());
                    }
                })
                .onCancel(() -> status.setText("Import cancelled."))
                .onError(status::setText)
                .show());
        root.addElement(importButton);

        ButtonOverlay exportButton = new ButtonOverlay("Export...", 110, 34);
        exportButton.setPosition(150, 30);
        exportButton.onAction(() -> FileDialog.save()
                .filter("Text files", "txt")
                .defaultName("notes.txt")
                .onSelect(path -> {
                    try {
                        Files.writeString(path, editor.getText());
                        status.setText("Exported to " + path);
                    } catch (IOException e) {
                        status.setText("Could not write " + path.getFileName() + ": " + e.getMessage());
                    }
                })
                .onCancel(() -> status.setText("Export cancelled."))
                .onError(status::setText)
                .show());
        root.addElement(exportButton);

        ButtonOverlay folderButton = new ButtonOverlay("Folder...", 110, 34);
        folderButton.setPosition(270, 30);
        folderButton.onAction(() -> FileDialog.pickFolder()
                .onSelect(path -> status.setText("Folder: " + path))
                .onCancel(() -> status.setText("Folder cancelled."))
                .onError(status::setText)
                .show());
        root.addElement(folderButton);

        new Engine().start(window);
    }
}
