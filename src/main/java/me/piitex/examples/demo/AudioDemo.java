package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.audio.Audio;
import me.piitex.engine.audio.AudioChannel;
import me.piitex.engine.audio.Playback;
import me.piitex.engine.audio.Sound;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.scheduler.Task;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.File;

/**
 * Shows the audio API. It needs no audio files: the effects are generated tones. Pass the path of a {@code .ogg}
 * or {@code .wav} as the first program argument to also get "Play music" and "Stop music" buttons that crossfade
 * a looping track.
 * <p>
 * "Orbit a sound" places a tone in the world and moves it around the listener with {@link Playback#position}: you
 * should hear it travel from left to right and get quieter as it moves away.
 * <p>
 * The sliders drive the channel volumes, so dragging "Music" while a track plays changes it live.
 */
public class AudioDemo {

    static void main(String[] args) {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED
        Window window = new Window(new WindowOptions("Audio demo").setDimensions(520, 470));
        Font.setDefaultFont(new File("src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(520, 470);
        root.getStyling().setBackgroundColor(Color.TRANSPARENT);
        root.getStyling().setBorderThickness(0f);

        // Sounds are decoded once and can be played any number of times, even overlapping.
        Sound beep = Sound.tone(660, 0.25f);
        Sound low = Sound.tone(220, 0.4f).channel(AudioChannel.UI);

        int y = 20;
        y = button(root, "Beep", y, () -> beep.play());
        y = button(root, "Beep (random pitch)", y, () -> beep.play().pitch(0.5f + (float) Math.random() * 1.5f));
        y = button(root, "Low tone on the UI channel, panned left", y, () -> low.play().pan(-1f));

        Playback[] hum = new Playback[1];
        y = button(root, "Toggle looping hum", y, () -> {
            if (hum[0] != null && !hum[0].isFinished()) {
                hum[0].stop(0.5f); // fade out, then stop
            } else {
                hum[0] = Sound.tone(110, 1f).loop();
            }
        });

        Playback[] orbit = new Playback[1];
        y = button(root, "Orbit a sound around the listener", y, () -> {
            if (orbit[0] != null) orbit[0].stop();
            Playback p = Sound.tone(330, 1f).loopAt(3f, 0f, 0f);
            orbit[0] = p;
            float[] angle = {0f};
            Task[] mover = new Task[1];
            mover[0] = Scheduler.every(1f / 30f, () -> {
                if (p.isFinished()) {
                    mover[0].cancel();
                    return;
                }
                angle[0] += 0.12f;
                // A circle in the x-z plane that swings from 2 to 8 units away.
                float distance = 5f + 3f * (float) Math.sin(angle[0] * 0.5f);
                p.position((float) Math.sin(angle[0]) * distance, 0f, -(float) Math.cos(angle[0]) * distance);
            });
            Scheduler.after(6f, () -> p.stop(0.5f));
        });

        if (args.length > 0) {
            y = button(root, "Play music (crossfades)", y, () -> Audio.playMusic(args[0]));
            y = button(root, "Stop music", y, Audio::stopMusic);
        }

        slider(root, "Master", y, null);
        y += 40;
        for (AudioChannel channel : AudioChannel.values()) {
            slider(root, channel.name(), y, channel);
            y += 40;
        }

        window.addContainer(root);
        new Engine().start(window);
    }

    private static int button(Container root, String text, int y, Runnable action) {
        ButtonOverlay button = new ButtonOverlay(text, 360, 32);
        button.setPosition(20, y);
        button.onAction(action);
        root.addElement(button);
        return y + 40;
    }

    /** A 0-100 slider; a null channel drives the master volume. */
    private static void slider(Container root, String label, int y, AudioChannel channel) {
        TextOverlay text = new TextOverlay(label, 16, Color.WHITE);
        text.setPosition(20, y + 6);
        root.addElement(text);

        SliderOverlay slider = new SliderOverlay(300, 0, 100, 100);
        slider.setPosition(110, y);
        slider.onValueChange(value -> {
            float volume = (float) (value / 100.0);
            if (channel == null) Audio.setMasterVolume(volume);
            else Audio.setVolume(channel, volume);
        });
        root.addElement(slider);
    }
}
