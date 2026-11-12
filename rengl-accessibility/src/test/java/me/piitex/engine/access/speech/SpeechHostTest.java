package me.piitex.engine.access.speech;

import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.input.InputProcessor;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ToastManager;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.LinkOverlay;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.RadioButtonOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What the narrator says for each control, and that a window with the host on speaks as Tab moves through it. */
class SpeechHostTest {
    /** Records what it was asked to say instead of making a sound. */
    private static final class Recorder implements Speaker {
        final List<String> said = new ArrayList<>();
        boolean closed;
        int stops;

        @Override
        public void speak(String text, boolean interrupt) {
            said.add((interrupt ? "!" : "") + text);
        }

        @Override
        public void stop() {
            stops++;
        }

        @Override
        public void setRate(int rate) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final Window window = new Window(new WindowOptions("test").setDimensions(400, 400));
    private final InputProcessor processor = new InputProcessor();
    private final Recorder voice = new Recorder();
    private final Narrator narrator = new Narrator();

    private void key(int key) {
        window.getInputManager().onKey(0, key, 0, GLFW.GLFW_PRESS, 0);
        processor.processEvents(window);
        window.getInputManager().onKey(0, key, 0, GLFW.GLFW_RELEASE, 0);
        processor.processEvents(window);
    }

    @Test
    void eachControlHasItsOwnSentence() {
        assertEquals("Save, button", narrator.describe(new ButtonOverlay("Save", 80, 30)));

        CheckBoxOverlay box = new CheckBoxOverlay(20);
        box.setAccessibleName("Fullscreen");
        assertEquals("Fullscreen, check box, not checked", narrator.describe(box));
        box.setChecked(true);
        assertEquals("Fullscreen, check box, checked", narrator.describe(box));

        RadioButtonOverlay radio = new RadioButtonOverlay(18);
        radio.setAccessibleName("Hard");
        assertEquals("Hard, radio button, not selected", narrator.describe(radio));

        ToggleSwitchOverlay toggle = new ToggleSwitchOverlay();
        toggle.setAccessibleName("Subtitles");
        assertEquals("Subtitles, switch, off", narrator.describe(toggle));

        LinkOverlay link = new LinkOverlay("Manual", "https://example.com");
        assertEquals("Manual, link", narrator.describe(link));

        ProgressBarOverlay progress = new ProgressBarOverlay(200, 12);
        progress.setProgress(0.25);
        progress.setAccessibleName("Loading");
        assertEquals("Loading, progress bar, 25 percent", narrator.describe(progress));
    }

    @Test
    void aSliderIsReadWithItsLabelAndValue() {
        TextOverlay label = new TextOverlay("Music volume", 14f, Color.BLACK);
        SliderOverlay slider = new SliderOverlay(200, 0, 100, 40);
        slider.setLabelledBy(label);
        assertEquals("Music volume, slider, 40", narrator.describe(slider));
        slider.setLabelFormatter(v -> Math.round(v) + "%");
        assertEquals("Music volume, slider, 40%", narrator.describe(slider));
        slider.setAccessibleDescription("Arrow keys change it");
        assertEquals("Music volume, slider, 40%, Arrow keys change it", narrator.describe(slider));
    }

    @Test
    void aTextFieldSaysItsTextOrThatItIsBlankAndNeverAPassword() {
        TextFieldOverlay name = new TextFieldOverlay(160, 28);
        name.setAccessibleName("Player name");
        assertEquals("Player name, edit text, blank", narrator.describe(name));
        name.setText("Alice");
        assertEquals("Player name, edit text, Alice", narrator.describe(name));

        TextFieldOverlay secret = new TextFieldOverlay(160, 28);
        secret.setAccessibleName("Password");
        secret.setPassword(true);
        secret.setText("hunter2");
        assertEquals("Password, edit text", narrator.describe(secret));
    }

    @Test
    void aDropdownSaysWhatIsChosen() {
        DropdownOverlay<String> difficulty = new DropdownOverlay<>(140, 28);
        difficulty.setAccessibleName("Difficulty");
        difficulty.setItems(List.of("Easy", "Normal", "Hard"));
        difficulty.setSelectedIndex(1);
        assertEquals("Difficulty, combo box, Normal", narrator.describe(difficulty));
    }

    @Test
    void aDisabledControlSaysSo() {
        ButtonOverlay load = new ButtonOverlay("Load", 80, 30);
        load.setDisabled(true);
        assertEquals("Load, button, unavailable", narrator.describe(load));
    }

    @Test
    void wordsCanBeTranslated() {
        narrator.setWord("role.button", "Schaltfläche");
        narrator.setWord("state.disabled", "nicht verfügbar");
        ButtonOverlay load = new ButtonOverlay("Laden", 80, 30);
        load.setDisabled(true);
        assertEquals("Laden, Schaltfläche, nicht verfügbar", narrator.describe(load));
    }

    @Test
    void tabbingThroughAWindowReadsEachStopAndTheKeyboardChangesAreSpoken() {
        ButtonOverlay save = new ButtonOverlay("Save", 80, 30);
        save.setPosition(10, 10);
        CheckBoxOverlay box = new CheckBoxOverlay(20);
        box.setPosition(10, 60);
        box.setAccessibleName("Fullscreen");
        SliderOverlay slider = new SliderOverlay(200, 0, 100, 50);
        slider.setPosition(10, 100);
        slider.setAccessibleName("Volume");
        Container panel = new Container(0, 0, 400, 400);
        panel.addElement(save);
        panel.addElement(box);
        panel.addElement(slider);
        window.addContainer(panel);

        Accessibility.enable(window, voice);
        key(GLFW.GLFW_KEY_TAB);
        key(GLFW.GLFW_KEY_TAB);
        key(GLFW.GLFW_KEY_SPACE);
        key(GLFW.GLFW_KEY_TAB);
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_RIGHT);
        window.announce("Game saved");
        window.announce("Low health", true);

        assertEquals(List.of("!Save, button", "!Fullscreen, check box, not checked", "!checked",
                "!Volume, slider, 50", "!51", "!52", "Game saved", "!Low health"), voice.said);
    }

    @Test
    void disablingSilencesAndClosesTheVoice() {
        SpeechHost host = Accessibility.enable(window, voice);
        host.setEnabled(false);
        window.announce("ignored");
        assertEquals(1, voice.stops);
        assertTrue(voice.said.isEmpty());
        Accessibility.disable(window);
        assertTrue(voice.closed);
        assertNull(window.getAccessibilityHost());
    }

    @Test
    void replacingTheHostClosesTheOldVoice() {
        Accessibility.enable(window, voice);
        Recorder other = new Recorder();
        Accessibility.enable(window, other);
        assertTrue(voice.closed);
        assertFalse(other.closed);
    }

    @Test
    void aToastIsSpokenWithoutAskingAndAnErrorCutsIn() {
        Accessibility.enable(window, voice);
        ToastManager.of(window).success("Saved", "Slot 1 written");
        ToastManager.of(window).info("Autosave on");
        ToastManager.of(window).error("Upload failed");
        assertEquals(List.of("Saved. Slot 1 written", "Autosave on", "!Upload failed"), voice.said);
    }

    @Test
    void aMeterThatKeepsMovingWaitsItsTurnInsteadOfCuttingItselfOff() throws Exception {
        SpeechHost host = new SpeechHost(voice, narrator);
        host.setLiveInterval(150);
        window.setAccessibilityHost(host);
        Container panel = new Container(0, 0, 400, 400);
        window.addContainer(panel);
        me.piitex.engine.ui.overlays.GaugeOverlay gauge = new me.piitex.engine.ui.overlays.GaugeOverlay(80, 0, 100);
        gauge.setAccessibleName("Engine heat");
        panel.addElement(gauge);
        key(GLFW.GLFW_KEY_TAB);
        assertEquals(1, voice.said.size(), "the sentence for landing on it");
        gauge.setValue(10);
        gauge.setValue(20);
        gauge.setValue(30); // the user is still hearing the sentence: only the newest is kept
        assertEquals(1, voice.said.size(), voice.said.toString());
        Thread.sleep(400);
        assertEquals(2, voice.said.size(), voice.said.toString());
        assertEquals("30", voice.said.get(1), "newest reading, and it does not interrupt");
        host.close();
    }
}
