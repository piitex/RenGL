package me.piitex.engine;

import me.piitex.engine.utils.flags.GeneratedFragments;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The sound of the {@link RenGLSplash RenGL splash}: five short cues, each started when the splash's timeline reaches the
 * moment it belongs to, so they stay with the picture even when a long first frame holds the timeline back.
 * <ol>
 *   <li>a soft chord that swells under the whole introduction;</li>
 *   <li>a rising breath that follows the spark round the outer hexagon, with a pluck at each corner it passes;</li>
 *   <li>a low thump and a bell chord when the hexagon closes, the ring of light leaves and the R springs in;</li>
 *   <li>a run of high chimes as the light sweeps across the wordmark, from left to right;</li>
 *   <li>a soft whoosh down when the splash fades over the application.</li>
 * </ol>
 * Everything is made from sine waves and noise, here, so the engine ships no sound files. The samples are made and
 * uploaded on a thread of their own while the splash plays its first frames, because opening the audio device alone can
 * take a tenth of a second. A cue whose sound is not ready in time waits a little and is then skipped, rather than start
 * out of step with the picture.
 * <p>
 * It plays through the {@link SoundOutput} the audio module provides; without that module there is no sound.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
final class RenGLSplashSound {
    private static final Logger log = LogManager.getLogger("Splash");

    /**
     * Samples per second of each channel.
     */
    static final int RATE = 48000;

    /**
     * One sound and when it plays.
     *
     * @param name     for logs
     * @param at       the moment of the splash's timeline, in seconds, it starts after
     * @param lateness how far past that moment, in seconds of the timeline, it may still start if its sound was not
     *                 ready in time
     * @param peak     the loudest any sample of it gets, as a fraction of full scale
     */
    record Cue(String name, float at, float lateness, float peak) {
    }

    /**
     * The cues in the order they play. The moments are the splash's own (see {@link RenGLSplash#render}): the outer
     * hexagon traces from 0.2 s and closes at 1.05 s, which is when the ring of light leaves and the R pops; the light
     * crosses the wordmark from 1.7 s; the fade starts at {@link RenGLSplash#HOLD_END}.
     */
    static final Cue[] CUES = {
            // The chord fades in over more than a second, so starting it late is barely heard.
            new Cue("chord", 0f, 0.6f, 0.14f),
            new Cue("rise", 0.2f, 0.1f, 0.26f),
            new Cue("bloom", 1.05f, 0.08f, 0.5f),
            new Cue("chimes", 1.7f, 0.08f, 0.2f),
            new Cue("whoosh", RenGLSplash.HOLD_END, 0.15f, 0.12f),
    };

    /**
     * How long the outer hexagon takes to trace itself, in seconds (0.2 s to 1.05 s of the timeline).
     */
    private static final float TRACE = 0.85f;
    /**
     * How long the light takes to cross the wordmark, in seconds.
     */
    private static final float SWEEP = 0.55f;

    private static final double TWO_PI = 2 * Math.PI;

    private final SoundOutput output;
    /**
     * Each cue's sound once it has been made and uploaded; null before that.
     */
    private final AtomicReferenceArray<SoundOutput.Clip> clips = new AtomicReferenceArray<>(CUES.length);
    /**
     * The next cue to play or skip.
     */
    private int next;
    private boolean released;

    RenGLSplashSound(SoundOutput output) {
        this.output = output;
    }

    /**
     * Starts making and uploading the sounds on a thread of their own.
     */
    void loadInBackground() {
        Thread thread = new Thread(this::load, "RenGL-Splash-Sound");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Makes and uploads each cue's sound in the order they play, so the first is ready first. The device opens on
     * another thread meanwhile, since opening it takes about as long as making the sounds; the first upload waits for it.
     */
    void load() {
        Thread.ofVirtual().name("RenGL-Splash-Sound-Device").start(this::openDevice);
        try {
            for (int index = 0; index < CUES.length; index++) {
                short[] samples = synthesize(index);
                if (!output.isAvailable()) return;
                SoundOutput.Clip clip = output.load("splash:" + CUES[index].name(), samples, 2, RATE);
                synchronized (this) {
                    if (released) {
                        clip.release();
                        return;
                    }
                    clips.set(index, clip);
                }
                log.debug("Splash sound {} ready", CUES[index].name());
            }
        } catch (RuntimeException e) {
            log.warn("The splash will play without sound: {}", e.toString());
        }
    }

    private void openDevice() {
        try {
            output.isAvailable();
        } catch (RuntimeException e) {
            log.warn("The splash will play without sound: {}", e.toString());
        }
    }

    /**
     * Starts the cues the timeline has passed now that it is at {@code time} seconds. Called on the render thread after
     * every update.
     */
    void advance(float time) {
        while (next < CUES.length && time > CUES[next].at()) {
            Cue cue = CUES[next];
            // Too far past its moment (its sound came late, or an earlier cue was waiting for its own), so it is skipped.
            boolean tooLate = time - cue.at() > cue.lateness();
            SoundOutput.Clip clip = clips.get(next);
            if (!tooLate) {
                if (clip == null) return; // not made yet, and there is still time to wait for it
                clip.play(1f);
                log.debug("Splash sound {} at {} s", cue.name(), time);
            } else {
                log.debug("Splash sound {} skipped: {} s late", cue.name(), time - cue.at());
            }
            next++;
        }
    }

    /**
     * Frees the sounds once they have finished playing; any still on their way are freed as soon as they arrive.
     */
    synchronized void release() {
        released = true;
        for (int index = 0; index < CUES.length; index++) {
            SoundOutput.Clip clip = clips.getAndSet(index, null);
            if (clip != null) clip.release();
        }
    }

    /**
     * The stereo samples of cue {@code index}, left then right, scaled so its loudest sample is the cue's peak.
     */
    static short[] synthesize(int index) {
        Stereo sound = switch (index) {
            case 0 -> chord();
            case 1 -> rise();
            case 2 -> bloom();
            case 3 -> chimes();
            case 4 -> whoosh();
            default -> throw new IllegalArgumentException("no cue " + index);
        };
        return sound.toPcm(CUES[index].peak());
    }

    /**
     * A D chord with an added ninth (D, A, D, A, E, F sharp, from low to high), each note two slightly detuned voices
     * spread left and right. It swells in over 1.4 s, breathes slowly, and lets go from 2.4 s to nothing at 3.6 s.
     */
    private static Stereo chord() {
        float[] notes = {73.42f, 110f, 146.83f, 220f, 329.63f, 369.99f};
        // Lower notes carry the chord; the upper ones only colour it.
        float[] levels = {0.9f, 0.8f, 0.7f, 0.5f, 0.32f, 0.28f};
        float[] pans = {0f, -0.25f, 0.25f, -0.45f, 0.45f, -0.1f};
        // Four cents is a frequency ratio of 2^(4/1200); a voice a little sharp and one a little flat beat slowly
        // against each other, which is what makes the chord sound wide rather than like one organ pipe.
        float detune = (float) Math.pow(2, 4 / 1200.0);
        float length = 3.6f;
        Stereo out = new Stereo(length);
        for (int note = 0; note < notes.length; note++) {
            for (int voice = 0; voice < 2; voice++) {
                float frequency = notes[note] * (voice == 0 ? 1f / detune : detune);
                float pan = Math.clamp(pans[note] + (voice == 0 ? -0.35f : 0.35f), -1f, 1f);
                double phase = 0;
                double step = TWO_PI * frequency / RATE;
                for (int i = 0; i < out.frames(); i++) {
                    float t = i / (float) RATE;
                    float attack = ease(Math.min(t / 1.4f, 1f));
                    float letGo = 1f - ease(progress(t, 2.4f, 1.2f));
                    // Each note swells and ebbs at 0.4 times a second, out of step with the others.
                    float breath = 0.85f + 0.15f * (float) Math.sin(TWO_PI * 0.4 * t + note * 1.3);
                    // A sine with a little of its second and third harmonics is soft but not hollow.
                    float wave = (float) (Math.sin(phase) + 0.18 * Math.sin(2 * phase) + 0.06 * Math.sin(3 * phase));
                    out.add(i, wave * levels[note] * attack * letGo * breath, pan);
                    phase += step;
                }
            }
        }
        return reverb(out, 0.35f, 1.2f);
    }

    /**
     * Breath that rises as the spark traces the outer hexagon, panned to follow the spark across the window, and a
     * pluck at each of the five corners it passes before the hexagon closes. The notes climb D, E, F sharp, A, B; the
     * sixth corner, back at the top, is the bloom.
     */
    private static Stereo rise() {
        Stereo out = new Stereo(TRACE);
        Random random = new Random(0x52656E47L);
        // A state-variable filter turns the white noise into a band around a centre that sweeps up from 300 Hz.
        float low = 0f, band = 0f, noise = 0f;
        for (int i = 0; i < out.frames(); i++) {
            float fraction = i / (float) out.frames();
            // The splash traces the hexagon along an eased curve, so the sound follows the same one.
            float traced = ease(fraction);
            float centre = 300f * (float) Math.pow(4500.0 / 300.0, traced);
            float tuning = 2f * (float) Math.sin(Math.PI * centre / RATE);
            noise = soften(noise, random);
            low += tuning * band;
            float high = noise - low - 0.5f * band;
            band += tuning * high;
            // It grows with the square of the time, so it is mostly felt at the end, and the last 20 ms fade out.
            float remaining = TRACE - i / (float) RATE;
            float swell = fraction * fraction * Math.min(1f, remaining / 0.02f);
            // The spark starts at the top and goes clockwise, so its distance across the window from the centre is the
            // sine of the angle it has turned.
            float pan = 0.7f * (float) Math.sin(TWO_PI * traced);
            out.add(i, band * swell * 0.9f, pan);
        }
        float[] notes = {293.66f, 329.63f, 369.99f, 440f, 493.88f};
        for (int corner = 1; corner <= notes.length; corner++) {
            // The spark is at corner k when the eased trace has covered k sixths of the way round.
            float when = TRACE * easeInverse(corner / 6f);
            float pan = 0.6f * (float) Math.sin(TWO_PI * corner / 6.0);
            pluck(out, when, notes[corner - 1], 0.22f + 0.03f * corner, 0.45f, pan);
        }
        return reverb(out, 0.4f, 1.4f);
    }

    /**
     * The hexagon closing: a low thump that drops in pitch, and a bell chord (D, A, D, F sharp, A and a high E) struck
     * from the lowest note up, 12 ms apart, spread across the stereo field.
     */
    private static Stereo bloom() {
        Stereo out = new Stereo(2.6f);
        double phase = 0;
        for (int i = 0; i < out.frames(); i++) {
            float t = i / (float) RATE;
            // Starts at 70 Hz and falls to 42 Hz within a tenth of a second or so, which the ear hears as weight.
            double frequency = 42 + 28 * Math.exp(-t / 0.06);
            phase += TWO_PI * frequency / RATE;
            float attack = Math.min(1f, t / 0.002f);
            out.add(i, (float) (Math.sin(phase) * Math.exp(-t / 0.3)) * attack * 0.7f, 0f);
        }
        float[] notes = {293.66f, 440f, 587.33f, 739.99f, 880f, 1318.51f};
        float[] levels = {0.5f, 0.45f, 0.4f, 0.32f, 0.26f, 0.16f};
        float[] pans = {-0.1f, 0.25f, -0.35f, 0.4f, -0.5f, 0.55f};
        for (int note = 0; note < notes.length; note++) {
            bell(out, note * 0.012f, notes[note], levels[note], pans[note]);
        }
        return reverb(out, 0.5f, 1.6f);
    }

    /**
     * Five high chimes (A, C sharp, E, F sharp, A), each struck as the band of light reaches a letter of "RenGL" and
     * panned where that letter is.
     */
    private static Stereo chimes() {
        Stereo out = new Stereo(SWEEP + 0.6f);
        float[] notes = {880f, 1108.73f, 1318.51f, 1479.98f, 1760f};
        for (int letter = 0; letter < notes.length; letter++) {
            // The band travels from 80 units left of the word to 80 units right of it, a run of about 335 units at the
            // design size for a word about 175 units wide, so the middle of letter i is (80 + 175 * (i + 0.5) / 5) / 335
            // of the way along. The band moves along an eased curve, so the moment it gets there is the inverse of that.
            float along = (80f + 175f * (letter + 0.5f) / 5f) / 335f;
            float when = SWEEP * easeInverse(along);
            float pan = (letter - 2) * 0.3f;
            chime(out, when, notes[letter], 0.6f - 0.06f * letter, pan);
        }
        return reverb(out, 0.55f, 1.6f);
    }

    /**
     * A breath of noise that falls from 2.5 kHz to 300 Hz, swelling and then dying away over 0.7 s.
     */
    private static Stereo whoosh() {
        float length = 0.7f;
        Stereo out = new Stereo(length);
        Random random = new Random(0x474CL);
        float low = 0f, band = 0f, noise = 0f;
        for (int i = 0; i < out.frames(); i++) {
            float fraction = i / (float) out.frames();
            float centre = 2500f * (float) Math.pow(300.0 / 2500.0, fraction);
            float tuning = 2f * (float) Math.sin(Math.PI * centre / RATE);
            noise = soften(noise, random);
            low += tuning * band;
            // A damping of 0.6 keeps the band fairly narrow, so it is heard as air moving rather than as hiss.
            float high = noise - low - 0.6f * band;
            band += tuning * high;
            // Half a sine wave, squared: up from silence and back down to it, smoothly at both ends.
            float swell = (float) Math.pow(Math.sin(Math.PI * fraction), 2);
            out.add(i, band * swell, 0f);
        }
        return reverb(out, 0.3f, 1f);
    }

    /**
     * The next sample of softened noise: white noise through a one-pole low-pass that moves 30% of the way to each new
     * random value, which takes the hiss off above about 2.7 kHz. White noise fed straight into the band filters below
     * keeps too much of its top end, because they fall away only gently outside their band.
     */
    private static float soften(float previous, Random random) {
        float white = random.nextFloat() * 2f - 1f;
        return previous + 0.3f * (white - previous);
    }

    /**
     * A plucked note from {@code start} seconds: six harmonics, each quieter than the last and dying away faster.
     */
    private static void pluck(Stereo out, float start, float frequency, float level, float decay, float pan) {
        int from = (int) (start * RATE);
        int length = Math.min(out.frames() - from, (int) (decay * 6f * RATE));
        for (int harmonic = 1; harmonic <= 6; harmonic++) {
            float amplitude = level / (float) Math.pow(harmonic, 1.5);
            float lifetime = decay / (float) Math.pow(harmonic, 0.7);
            double step = TWO_PI * frequency * harmonic / RATE;
            for (int i = 0; i < length; i++) {
                float t = i / (float) RATE;
                float attack = Math.min(1f, t / 0.003f);
                out.add(from + i, (float) (Math.sin(step * i) * Math.exp(-t / lifetime)) * amplitude * attack, pan);
            }
        }
    }

    /**
     * A struck bell from {@code start} seconds. Its partials sit at 1, 2, 3, 4.16 and 5.43 times the note; the last two
     * are a little off the harmonic series, which is what makes it ring like metal rather than sing like a string. Higher
     * partials, and higher notes, fade sooner. The left and right sides are tuned 0.05% apart so the note shimmers.
     */
    private static void bell(Stereo out, float start, float frequency, float level, float pan) {
        float[] ratios = {1f, 2f, 3f, 4.16f, 5.43f};
        float[] amplitudes = {1f, 0.45f, 0.22f, 0.12f, 0.06f};
        float[] lifetimes = {1.6f, 0.9f, 0.55f, 0.35f, 0.25f};
        // A note an octave above 440 Hz rings about a fifth less long.
        float pitchShortening = (float) Math.pow(440.0 / frequency, 0.35);
        int from = (int) (start * RATE);
        int length = out.frames() - from;
        // Equal-power panning: the left and right gains are the cosine and sine of an angle from 0 to a quarter turn.
        double angle = (pan + 1) * Math.PI / 4;
        float leftGain = (float) Math.cos(angle), rightGain = (float) Math.sin(angle);
        for (int partial = 0; partial < ratios.length; partial++) {
            double leftStep = TWO_PI * frequency * ratios[partial] * 0.99975 / RATE;
            double rightStep = TWO_PI * frequency * ratios[partial] * 1.00025 / RATE;
            float lifetime = lifetimes[partial] * pitchShortening;
            for (int i = 0; i < length; i++) {
                float t = i / (float) RATE;
                float envelope = (float) Math.exp(-t / lifetime) * Math.min(1f, t / 0.004f) * level * amplitudes[partial];
                out.left[from + i] += (float) Math.sin(leftStep * i) * envelope * leftGain;
                out.right[from + i] += (float) Math.sin(rightStep * i) * envelope * rightGain;
            }
        }
    }

    /**
     * A small glassy chime from {@code start} seconds: the note with a touch of its octave and twelfth.
     */
    private static void chime(Stereo out, float start, float frequency, float level, float pan) {
        int from = (int) (start * RATE);
        int length = out.frames() - from;
        double step = TWO_PI * frequency / RATE;
        for (int i = 0; i < length; i++) {
            float t = i / (float) RATE;
            double phase = step * i;
            float wave = (float) (Math.sin(phase) + 0.25 * Math.sin(2 * phase) + 0.08 * Math.sin(3 * phase));
            out.add(from + i, wave * (float) Math.exp(-t / 0.35) * Math.min(1f, t / 0.002f) * level, pan);
        }
    }

    /**
     * The sound followed by the room it plays in: Freeverb's design, eight echoing delay lines that each lose a little
     * of the top end on every pass, then four that smear what comes out of them, with the right side's lines a few
     * samples longer than the left's so the two sides differ. Each side then takes a share of the other's (Freeverb's
     * width control), because two wholly unrelated sides sound wider than a room and partly cancel on one speaker.
     * {@code wet} is how much room there is against the dry
     * sound; {@code tail} is how many seconds are added at the end for it to die away in.
     */
    static Stereo reverb(Stereo dry, float wet, float tail) {
        // Freeverb's delay lengths, in samples at 44.1 kHz, scaled to this rate. They share no common factor, so their
        // echoes never line up into a pitch.
        int[] combLengths = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        int[] allPassLengths = {556, 441, 341, 225};
        int spread = 23;
        Comb[] leftCombs = new Comb[combLengths.length], rightCombs = new Comb[combLengths.length];
        for (int c = 0; c < combLengths.length; c++) {
            leftCombs[c] = new Comb(scaled(combLengths[c]));
            rightCombs[c] = new Comb(scaled(combLengths[c] + spread));
        }
        AllPass[] leftPasses = new AllPass[allPassLengths.length], rightPasses = new AllPass[allPassLengths.length];
        for (int a = 0; a < allPassLengths.length; a++) {
            leftPasses[a] = new AllPass(scaled(allPassLengths[a]));
            rightPasses[a] = new AllPass(scaled(allPassLengths[a] + spread));
        }
        Stereo out = new Stereo(dry.frames() + (int) (tail * RATE));
        // Eight combs add up to several times their input, so the room is fed a small share of it.
        float send = 0.12f * wet;
        // Freeverb's width of 0.6: each side is 80% its own room and 20% the other's.
        float width = 0.6f;
        float own = send * (width / 2f + 0.5f), other = send * ((1f - width) / 2f);
        for (int i = 0; i < out.frames(); i++) {
            boolean inside = i < dry.frames();
            float input = inside ? (dry.left[i] + dry.right[i]) * 0.5f : 0f;
            float left = 0f, right = 0f;
            for (int c = 0; c < leftCombs.length; c++) {
                left += leftCombs[c].process(input);
                right += rightCombs[c].process(input);
            }
            for (int a = 0; a < leftPasses.length; a++) {
                left = leftPasses[a].process(left);
                right = rightPasses[a].process(right);
            }
            out.left[i] = (inside ? dry.left[i] : 0f) + left * own + right * other;
            out.right[i] = (inside ? dry.right[i] : 0f) + right * own + left * other;
        }
        out.fadeOut(Math.min(0.4f, tail));
        return out;
    }

    private static int scaled(int samplesAt44k) {
        return Math.round(samplesAt44k * RATE / 44100f);
    }

    /**
     * A delay line that feeds its output back in, a little quieter and a little darker each time: a train of echoes.
     */
    private static final class Comb {
        /**
         * How much of each echo comes round again. 0.88 every 25 to 35 ms dies away by 60 dB in about a second and a half.
         */
        private static final float FEEDBACK = 0.88f;
        /**
         * How much of the previous echo is mixed into this one, a gentle low-pass that makes the highs die first.
         */
        private static final float DAMPING = 0.25f;
        private final float[] line;
        private int position;
        private float filtered;

        Comb(int length) {
            line = new float[length];
        }

        float process(float input) {
            float output = line[position];
            filtered = output * (1f - DAMPING) + filtered * DAMPING;
            line[position] = input + filtered * FEEDBACK;
            if (++position == line.length) position = 0;
            return output;
        }
    }

    /**
     * A delay line that passes every frequency at the same level but smears it in time, turning echoes into a wash.
     */
    private static final class AllPass {
        private final float[] line;
        private int position;

        AllPass(int length) {
            line = new float[length];
        }

        float process(float input) {
            float delayed = line[position];
            line[position] = input + delayed * 0.5f;
            if (++position == line.length) position = 0;
            return delayed - input;
        }
    }

    /**
     * Two channels of float samples, where a sound is built before it becomes 16-bit.
     */
    static final class Stereo {
        final float[] left, right;

        Stereo(float seconds) {
            this((int) Math.ceil(seconds * RATE));
        }

        Stereo(int frames) {
            left = new float[frames];
            right = new float[frames];
        }

        int frames() {
            return left.length;
        }

        /**
         * Adds {@code value} at frame {@code frame}, placed between left (-1) and right (1) by equal-power panning, so a
         * sound keeps its loudness as it moves: the gains are the cosine and sine of an angle from 0 to a quarter turn.
         */
        void add(int frame, float value, float pan) {
            if (frame < 0 || frame >= left.length) return;
            double angle = (pan + 1) * Math.PI / 4;
            left[frame] += value * (float) Math.cos(angle);
            right[frame] += value * (float) Math.sin(angle);
        }

        /**
         * Brings the last {@code seconds} down to silence along a half cosine, so the sound does not end on a click.
         */
        void fadeOut(float seconds) {
            int length = Math.min(left.length, (int) (seconds * RATE));
            int start = left.length - length;
            for (int i = 0; i < length; i++) {
                float gain = 0.5f + 0.5f * (float) Math.cos(Math.PI * (i + 1) / length);
                left[start + i] *= gain;
                right[start + i] *= gain;
            }
        }

        /**
         * 16-bit samples, left then right, scaled so the loudest sample is {@code peak} of full scale.
         */
        short[] toPcm(float peak) {
            float loudest = 1e-9f;
            for (int i = 0; i < left.length; i++) {
                loudest = Math.max(loudest, Math.max(Math.abs(left[i]), Math.abs(right[i])));
            }
            float gain = peak / loudest * Short.MAX_VALUE;
            short[] pcm = new short[left.length * 2];
            for (int i = 0; i < left.length; i++) {
                pcm[i * 2] = (short) Math.round(left[i] * gain);
                pcm[i * 2 + 1] = (short) Math.round(right[i] * gain);
            }
            return pcm;
        }
    }

    /**
     * How far {@code seconds} is through a stretch that starts at {@code start} and lasts {@code duration}: 0 before
     * it, 1 after it, a straight line between.
     */
    private static float progress(float seconds, float start, float duration) {
        return Math.clamp((seconds - start) / duration, 0f, 1f);
    }

    /**
     * Smoothstep, 3x^2 - 2x^3, the easing the splash moves by.
     */
    private static float ease(float progress) {
        return progress * progress * (3f - 2f * progress);
    }

    /**
     * The x in 0..1 whose {@link #ease} is {@code eased}: when an eased movement reaches a point. Smoothstep only rises,
     * so halving the range 30 times finds it to well under a sample.
     */
    static float easeInverse(float eased) {
        float low = 0f, high = 1f;
        for (int i = 0; i < 30; i++) {
            float middle = (low + high) / 2f;
            if (ease(middle) < eased) low = middle;
            else high = middle;
        }
        return (low + high) / 2f;
    }
}
