package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;

import java.util.List;

/**
 * Plays {@link AnimationClip}s: every frame it sets the position, rotation and scale of the nodes the clip animates.
 * Register it with {@link Scene3D#animate} and it advances once per frame, before the update handler.
 * <pre>
 * Model model = Model.load(Path.of("assets/soldier.glb"));
 * scene.add(model.root());
 * AnimationPlayer player = model.player();
 * scene.animate(player);
 * player.play("Walk");
 * </pre>
 * Switching clips with a fade ({@link #play(String, float)}) blends the old pose into the new one. Animating moves
 * nodes, so the viewport must draw every frame ({@link me.piitex.engine.render3d.Viewport3D#setContinuous
 * continuous}, the default) or be asked to with {@code requestRender()}.
 */
public final class AnimationPlayer {
    private final List<AnimationClip> clips;
    private AnimationClip current, previous;
    private float time, previousTime;
    private float fade, fadeLength;
    private float speed = 1f;
    private boolean loop = true;
    private boolean playing;

    public AnimationPlayer(List<AnimationClip> clips) {
        this.clips = List.copyOf(clips);
    }

    public List<AnimationClip> clips() {
        return clips;
    }

    /**
     * The clip with this name, or null.
     */
    public AnimationClip clip(String name) {
        for (AnimationClip clip : clips) {
            if (name.equals(clip.name())) return clip;
        }
        return null;
    }

    /**
     * Starts the named clip from its beginning.
     *
     * @throws IllegalArgumentException if there is no such clip
     */
    public AnimationPlayer play(String name) {
        return play(name, 0f);
    }

    /**
     * Starts the named clip, fading from the current pose over {@code fadeSeconds}.
     */
    public AnimationPlayer play(String name, float fadeSeconds) {
        AnimationClip clip = clip(name);
        if (clip == null) throw new IllegalArgumentException("No animation named " + name + "; there are " + clips.stream().map(AnimationClip::name).toList());
        return play(clip, fadeSeconds);
    }

    public AnimationPlayer play(AnimationClip clip, float fadeSeconds) {
        if (fadeSeconds > 0f && current != null) {
            previous = current;
            previousTime = time;
            fade = 0f;
            fadeLength = fadeSeconds;
        } else {
            previous = null;
        }
        current = clip;
        time = 0f;
        playing = true;
        return this;
    }

    /**
     * Stops playing and leaves the nodes in the pose they have.
     */
    public AnimationPlayer stop() {
        playing = false;
        current = null;
        previous = null;
        return this;
    }

    public boolean isPlaying() {
        return playing;
    }

    /**
     * Pauses or resumes the current clip.
     */
    public AnimationPlayer setPlaying(boolean playing) {
        this.playing = playing && current != null;
        return this;
    }

    public boolean isLoop() {
        return loop;
    }

    /**
     * Whether a clip starts again when it ends (true by default) or stops on its last pose.
     */
    public AnimationPlayer setLoop(boolean loop) {
        this.loop = loop;
        return this;
    }

    public float getSpeed() {
        return speed;
    }

    /**
     * 1 plays at the recorded speed, 2 at double, negative backwards.
     */
    public AnimationPlayer setSpeed(float speed) {
        this.speed = speed;
        return this;
    }

    public AnimationClip getCurrent() {
        return current;
    }

    public float getTime() {
        return time;
    }

    /**
     * Jumps to a time in the current clip and poses the nodes there.
     */
    public AnimationPlayer setTime(float seconds) {
        time = seconds;
        if (current != null) apply();
        return this;
    }

    /**
     * Advances the clip by {@code delta} seconds (times {@link #getSpeed()}) and poses the nodes. The scene calls this
     * for a player registered with {@link Scene3D#animate}.
     */
    public void update(float delta) {
        if (current == null || !playing) return;
        float step = delta * speed;
        time += step;
        if (previous != null) {
            previousTime += step;
            fade += Math.abs(delta);
            if (fade >= fadeLength) previous = null;
        }
        float duration = current.duration();
        if (duration > 0f && (time >= duration || time < 0f)) {
            if (loop) {
                time = ((time % duration) + duration) % duration;
            } else {
                time = Math.max(0f, Math.min(duration, time));
                playing = false;
            }
        }
        apply();
    }

    private void apply() {
        float blend = previous == null ? 1f : Math.min(1f, fade / fadeLength);
        if (previous != null) sample(previous, wrap(previous, previousTime), 1f);
        sample(current, time, blend);
    }

    private float wrap(AnimationClip clip, float t) {
        float d = clip.duration();
        return d <= 0f ? 0f : ((t % d) + d) % d;
    }

    /**
     * Poses the clip's nodes at {@code t}, mixed {@code weight} of the way from the pose they have now.
     */
    private static void sample(AnimationClip clip, float t, float weight) {
        for (AnimationClip.Channel channel : clip.channels()) {
            float[] times = channel.times();
            int last = times.length - 1;
            int i = 0;
            while (i < last && times[i + 1] <= t) i++;
            int j = Math.min(i + 1, last);
            float f = 0f;
            if (channel.interpolation() == AnimationClip.Interpolation.LINEAR && j != i && t > times[i]) {
                f = Math.min(1f, (t - times[i]) / (times[j] - times[i]));
            }
            float[] v = channel.values();
            int w = channel.path().width;
            Node3D node = channel.target();
            switch (channel.path()) {
                case TRANSLATION -> {
                    Vec3 a = new Vec3(v[i * w], v[i * w + 1], v[i * w + 2]);
                    Vec3 b = new Vec3(v[j * w], v[j * w + 1], v[j * w + 2]);
                    Vec3 value = a.lerp(b, f);
                    node.setPosition(weight >= 1f ? value : node.getPosition().lerp(value, weight));
                }
                case SCALE -> {
                    Vec3 a = new Vec3(v[i * w], v[i * w + 1], v[i * w + 2]);
                    Vec3 b = new Vec3(v[j * w], v[j * w + 1], v[j * w + 2]);
                    Vec3 value = a.lerp(b, f);
                    node.setScale(weight >= 1f ? value : node.getScale().lerp(value, weight));
                }
                case ROTATION -> {
                    Quat a = new Quat(v[i * w], v[i * w + 1], v[i * w + 2], v[i * w + 3]);
                    Quat b = new Quat(v[j * w], v[j * w + 1], v[j * w + 2], v[j * w + 3]);
                    Quat value = a.slerp(b, f);
                    node.setRotation(weight >= 1f ? value : node.getRotation().slerp(value, weight));
                }
            }
        }
    }
}
