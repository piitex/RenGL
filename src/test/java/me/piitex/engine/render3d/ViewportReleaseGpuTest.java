package me.piitex.engine.render3d;

import me.piitex.engine.RenderAPI;
import me.piitex.engine.ui.containers.Container;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A {@link Viewport3D} taken out of its container frees its GPU objects by itself, after the frame that removed it. Needs
 * a real window (so a GPU and a display); skips where there is none, and on macOS where OpenGL has no window.
 */
class ViewportReleaseGpuTest {
    /**
     * Counts what happens to it; draws nothing.
     */
    private static final class Counting implements Viewport3D.Content {
        final AtomicInteger inits = new AtomicInteger(), renders = new AtomicInteger(), disposes = new AtomicInteger();
        volatile int disposedAtFrame = -1;
        int frame;

        @Override
        public void init(GpuDevice device) {
            inits.incrementAndGet();
        }

        @Override
        public void resize(GpuDevice device, int width, int height) {
        }

        @Override
        public void render(GpuDevice device, RenderTarget target, float delta) {
            renders.incrementAndGet();
        }

        @Override
        public void dispose(GpuDevice device) {
            disposes.incrementAndGet();
        }
    }

    private void run(RenderAPI api) {
        Counting victimContent = new Counting();
        Viewport3D victim = new Viewport3D(32, 32);
        victim.setContent(victimContent);
        int[] frame = {0};
        int[] removedAt = {0};
        int[] disposedBy = {0};

        GpuHarness.Run run = GpuHarness.run(api, 96, viewport -> viewport.setContent(new Viewport3D.Content() {
            @Override
            public void init(GpuDevice device) {
            }

            @Override
            public void resize(GpuDevice device, int width, int height) {
            }

            @Override
            public void render(GpuDevice device, RenderTarget target, float delta) {
                frame[0]++;
                Container owner = viewport.owner;
                if (owner == null) return; // a headless run has no container
                if (frame[0] == 1) owner.addElement(victim);
                if (frame[0] == 4) {
                    removedAt[0] = victimContent.renders.get();
                    owner.removeElement(victim);
                }
                if (victimContent.disposes.get() > 0 && disposedBy[0] == 0) disposedBy[0] = frame[0];
            }

            @Override
            public void dispose(GpuDevice device) {
            }
        }), (device, target, recorder) -> {
            recorder.check("the removed viewport's content was set up once", victimContent.inits.get() == 1, "inits " + victimContent.inits);
            recorder.check("it drew before it was removed", removedAt[0] > 0, "renders at removal " + removedAt[0]);
            recorder.check("it was disposed by itself after the removal", victimContent.disposes.get() == 1, "disposes " + victimContent.disposes);
            recorder.check("and not before the removal", disposedBy[0] >= 4 || disposedBy[0] == 0, "noticed at frame " + disposedBy[0]);
            // (The frame that removed it may still have drawn it, if it came after the remover in the container.)
            recorder.check("it stopped drawing", victimContent.renders.get() <= removedAt[0] + 1, "renders " + victimContent.renders + " of " + removedAt[0]);
        });
        assumeTrue(run.available() && run.api() == api, run.reason() == null ? "the window ran on " + run.api() : run.reason());
        assumeTrue(frame[0] > 0 && removedAt[0] > 0 || run.results().isEmpty(), "no container to remove from (headless)");
        assumeTrue(removedAt[0] > 0, "no container to remove from (headless)");
        run.results().forEach((name, failure) -> assertNull(failure, name + ": " + failure));
    }

    @Test
    void vulkan() {
        run(RenderAPI.VULKAN);
    }

    @Test
    void openGl() {
        run(RenderAPI.OPENGL);
    }
}
