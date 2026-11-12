package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.access.speech.Accessibility;
import me.piitex.engine.access.speech.SpeechHost;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ReorderableGrid;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.RichTextAreaOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.TextChecker;
import me.piitex.engine.ui.text.TextIssue;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fifth accessibility page: the spell-checked text area and the grid you rearrange. Press Tab to move around.
 * <ul>
 *   <li><b>Notes.</b> Type, then press F8 to jump to the next misspelled word (Shift+F8 goes back). Ctrl and period (or
 *       Shift+F10) opens its corrections: Up and Down read each one, Enter takes it, Escape closes.
 *       The checker here knows "teh", "recieve" and "definately".</li>
 *   <li><b>Playlist.</b> Arrow keys move between tracks, M picks one up, the arrows move it, M or Enter puts it down, Escape
 *       puts it back. Enter on a track "plays" it.</li>
 *   <li><b>Save button.</b> Its arrow is only a picture; it is named "Save" and its tooltip is read as the help.</li>
 * </ul>
 */
public class AccessibilityEditingDemo {
    private static final Map<String, List<String>> FIXES = Map.of(
            "teh", List.of("the", "ten"),
            "recieve", List.of("receive"),
            "definately", List.of("definitely", "defiantly"));

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Accessibility Editing Demo").setDimensions(860, 560).setSplashEnabled(false));

        Container root = new Container(860, 560);
        window.addContainer(root);

        TextOverlay notesLabel = label(root, "Notes", 30, 20);
        RichTextAreaOverlay notes = new RichTextAreaOverlay(400, 200);
        notes.setPosition(30, 45);
        notes.setLabelledBy(notesLabel);
        notes.setChecker(new TextChecker() {
            @Override
            public List<TextIssue> check(String text) {
                List<TextIssue> issues = new ArrayList<>();
                Matcher words = Pattern.compile("[A-Za-z']+").matcher(text);
                while (words.find()) {
                    if (FIXES.containsKey(words.group().toLowerCase(Locale.ROOT))) {
                        issues.add(new TextIssue(words.start(), words.end(), TextIssue.Kind.SPELLING, null));
                    }
                }
                return issues;
            }

            @Override
            public List<String> suggest(String text, TextIssue issue) {
                return FIXES.getOrDefault(text.substring(issue.start(), issue.end()).toLowerCase(Locale.ROOT), List.of());
            }
        });
        notes.setText("I will definately recieve it by teh weekend.");
        root.addElement(notes);

        TextOverlay playlistLabel = label(root, "Playlist", 470, 20);
        ReorderableGrid playlist = new ReorderableGrid(360, 200, 170, 44, 10);
        playlist.setPosition(470, 45);
        playlist.setLabelledBy(playlistLabel);
        List<Container> tracks = new ArrayList<>();
        for (String name : List.of("Overture", "Harbour", "Night train", "Static", "Afterglow")) {
            Container track = new Container(170, 44);
            track.useTheme();
            TextOverlay title = new TextOverlay(name);
            title.setPosition(10, 12);
            track.addElement(title);
            tracks.add(track);
        }
        playlist.setItems(tracks);
        playlist.onItemClick(track -> System.out.println("[demo] play #" + (playlist.slotOf(track) + 1)));
        playlist.onReorder(order -> System.out.println("[demo] reordered, now " + order.size() + " tracks"));
        root.addElement(playlist);

        ButtonOverlay save = new ButtonOverlay("↓", 44, 36);
        save.setPosition(30, 270);
        save.setTooltip("Save the notes");
        save.setAccessibleName("Save");
        save.onAction(() -> System.out.println("[demo] saved"));
        root.addElement(save);

        SpeechHost speech = Accessibility.enable(window);
        System.out.println("[demo] ready; self-voicing " + (speech.isEnabled() ? "on" : "off"));
        new Engine().start(window);
    }

    private static TextOverlay label(Container root, String text, double x, double y) {
        TextOverlay label = new TextOverlay(text);
        label.setPosition(x, y);
        root.addElement(label);
        return label;
    }
}
