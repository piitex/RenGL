package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.access.speech.Accessibility;
import me.piitex.engine.access.speech.SpeechHost;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ToastManager;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.RadioButtonOverlay;
import me.piitex.engine.ui.overlays.RadioGroup;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;
import java.util.List;

/**
 * A settings screen for trying accessibility:
 * <ul>
 *   <li><b>Keyboard.</b> Tab and Shift+Tab move between the controls, Space or Enter use the one with the ring around it,
 *       and the arrows move the slider.</li>
 *   <li><b>Self-voicing.</b> The window reads each control as Tab lands on it.</li>
 *   <li><b>Screen readers.</b> Start NVDA or Narrator (Win+Ctrl+Enter) and it reads, presses and sets the same controls
 *       through UI Automation.</li>
 * </ul>
 * Everything that happens is also written to the console, so what a screen reader did can be seen without hearing it.
 */
public class AccessibilityDemo {
    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());
        Window window = new Window(new WindowOptions("Accessibility Demo").setDimensions(640, 480));

        Container root = new Container(640, 480);
        window.addContainer(root);

        TextOverlay title = new TextOverlay("Settings");
        title.setPosition(30, 20);
        root.addElement(title);

        TextOverlay status = new TextOverlay("Nothing pressed yet");
        status.setPosition(30, 440);
        status.setAccessibleRole(me.piitex.engine.access.AccessibleRole.STATUS);
        root.addElement(status);

        TextFieldOverlay name = new TextFieldOverlay(240, 32);
        name.setPosition(30, 70);
        name.setAccessibleName("Player name");
        name.setText("Alice");
        root.addElement(name);

        CheckBoxOverlay fullscreen = new CheckBoxOverlay(24);
        fullscreen.setPosition(30, 120);
        fullscreen.setAccessibleName("Fullscreen");
        fullscreen.onToggle(on -> {
            System.out.println("[demo] Fullscreen " + (on ? "on" : "off"));
            status.setText("Fullscreen " + (on ? "on" : "off"));
        });
        root.addElement(fullscreen);
        TextOverlay fullscreenLabel = new TextOverlay("Fullscreen");
        fullscreenLabel.setPosition(64, 122);
        fullscreenLabel.setAccessibleHidden(true); // the check box carries the name
        root.addElement(fullscreenLabel);

        ToggleSwitchOverlay subtitles = new ToggleSwitchOverlay();
        subtitles.setPosition(30, 170);
        subtitles.setAccessibleName("Subtitles");
        subtitles.onToggle(on -> {
            System.out.println("[demo] Subtitles " + (on ? "on" : "off"));
            status.setText("Subtitles " + (on ? "on" : "off"));
        });
        root.addElement(subtitles);

        TextOverlay volumeLabel = new TextOverlay("Music volume");
        volumeLabel.setPosition(30, 215);
        root.addElement(volumeLabel);
        SliderOverlay volume = new SliderOverlay(240, 0, 100, 40);
        volume.setPosition(30, 245);
        volume.setLabelledBy(volumeLabel);
        volume.onValueChange(v -> {
            System.out.println("[demo] Volume " + Math.round(v));
            status.setText("Volume " + Math.round(v));
        });
        root.addElement(volume);

        RadioButtonOverlay easy = new RadioButtonOverlay(22), hard = new RadioButtonOverlay(22);
        easy.setPosition(30, 300);
        easy.setAccessibleName("Easy");
        hard.setPosition(130, 300);
        hard.setAccessibleName("Hard");
        RadioGroup difficulty = new RadioGroup();
        difficulty.add(easy);
        difficulty.add(hard);
        difficulty.onChange(chosen -> System.out.println("[demo] " + (chosen == easy ? "Easy" : "Hard") + " chosen"));
        root.addElement(easy);
        root.addElement(hard);

        DropdownOverlay<String> language = new DropdownOverlay<>(200, 32);
        language.setPosition(30, 345);
        language.setAccessibleName("Language");
        language.setItems(List.of("English", "Deutsch", "Español"));
        language.setSelectedIndex(0);
        root.addElement(language);

        ButtonOverlay save = new ButtonOverlay("Save settings", 160, 40);
        save.setPosition(30, 390);
        save.onAction(() -> {
            System.out.println("[demo] Save pressed");
            status.setText("Saved");
            ToastManager.of(window).success("Saved", "Your settings were written");
        });
        root.addElement(save);

        SpeechHost speech = Accessibility.enable(window);
        System.out.println("[demo] ready; self-voicing " + (speech.isEnabled() ? "on" : "off"));
        new Engine().start(window);
    }
}
