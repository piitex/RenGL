package me.piitex.engine.access.speech;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.BiFunction;

/**
 * Speaks by running a command-line synthesizer once per sentence, such as macOS' {@code say} or Linux' {@code espeak-ng},
 * one after another on a thread of its own. An interrupt kills the one running and drops the queue.
 * <p>
 * Untested on macOS and Linux: it was written from those tools' documented options on a Windows machine.
 */
public final class CommandSpeaker implements Speaker {
    private static final Logger LOG = LogManager.getLogger("Accessibility");

    private final String name;
    private final BiFunction<String, Integer, List<String>> command;
    private final List<String> cancelCommand;
    private final LinkedBlockingDeque<String> queue = new LinkedBlockingDeque<>();
    private final Thread worker;
    private volatile Process running;
    private volatile int rate;
    private volatile boolean closed, failed;

    /**
     * @param name          the program, for the log
     * @param command       builds the command line that says a text at a rate (-10 to 10)
     * @param cancelCommand a command that silences a speech server that outlives the command, or empty
     */
    public CommandSpeaker(String name, BiFunction<String, Integer, List<String>> command, List<String> cancelCommand) {
        this.name = name;
        this.command = command;
        this.cancelCommand = cancelCommand;
        worker = new Thread(this::run, "RenGL speech");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Whether {@code program} runs here (is on the PATH).
     */
    static boolean exists(String program) {
        try {
            Process probe = new ProcessBuilder(program, "--version")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            probe.destroy();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void speak(String text, boolean interrupt) {
        if (text == null || text.isBlank() || closed || failed) return;
        if (interrupt) stop();
        queue.add(text.replace('\r', ' ').replace('\n', ' ').strip());
    }

    @Override
    public void stop() {
        queue.clear();
        Process current = running;
        if (current != null) current.destroy();
        if (!cancelCommand.isEmpty()) {
            try {
                new ProcessBuilder(cancelCommand).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void setRate(int rate) {
        this.rate = Math.max(-10, Math.min(10, rate));
    }

    @Override
    public void close() {
        closed = true;
        stop();
        worker.interrupt();
    }

    private void run() {
        while (!closed) {
            String text;
            try {
                text = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                Process process = new ProcessBuilder(command.apply(text, rate))
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                running = process;
                process.waitFor();
            } catch (IOException e) {
                failed = true;
                queue.clear();
                LOG.warn("Speech is off: could not run {} ({})", name, e.toString());
                return;
            } catch (InterruptedException e) {
                return;
            } finally {
                running = null;
            }
        }
    }
}
