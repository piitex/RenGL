package me.piitex.engine.access.speech;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Speaks through Windows' System.Speech (SAPI) voices, from a PowerShell process kept running so the first word is not
 * paid for with a second of start-up each time. The process starts on the first {@link #speak} and reads one command per
 * line on its standard input; closing the speaker, or the application ending, closes that and the process leaves.
 */
public final class WindowsSpeaker implements Speaker {
    private static final Logger LOG = LogManager.getLogger("Accessibility");

    private static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            [Console]::InputEncoding = [Text.Encoding]::UTF8
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Speech
            $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            if ($env:RENGL_SPEECH_SILENT -eq '1') { $s.SetOutputToNull() }
            Write-Output ('ready ' + $s.GetInstalledVoices().Count)
            while ($true) {
              $l = [Console]::In.ReadLine()
              if ($null -eq $l) { break }
              if ($l.Length -eq 0) { continue }
              $t = $l.Substring(1)
              switch ($l[0]) {
                'X' { $s.SpeakAsyncCancelAll() }
                'I' { $s.SpeakAsyncCancelAll(); [void]$s.SpeakAsync($t) }
                'Q' { [void]$s.SpeakAsync($t) }
                'R' { $s.Rate = [int]$t }
              }
              Write-Output 'ok'
            }
            $s.Dispose()
            """;

    private final boolean silent;
    private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    private Process process;
    private BufferedWriter in;
    private boolean failed, closed;
    private int rate;

    /**
     * @param silent true to run the voice into nothing: the same commands, no sound. For tests.
     */
    public WindowsSpeaker(boolean silent) {
        this.silent = silent;
    }

    @Override
    public synchronized void speak(String text, boolean interrupt) {
        if (text == null || text.isBlank()) return;
        send((interrupt ? "I" : "Q") + oneLine(text));
    }

    /**
     * Starts the PowerShell helper now, on another thread, so loading the voice (a second or more, longer when a virus
     * scanner looks at powershell.exe) is over by the time the first word is wanted.
     */
    @Override
    public void warmUp() {
        Thread starter = new Thread(() -> {
            synchronized (this) {
                if (process != null || failed || closed) return;
                try {
                    start();
                } catch (IOException e) {
                    fail(e);
                }
            }
        }, "RenGL speech start-up");
        starter.setDaemon(true);
        starter.start();
    }

    @Override
    public synchronized void stop() {
        if (process != null) send("X");
    }

    @Override
    public synchronized void setRate(int rate) {
        this.rate = Math.max(-10, Math.min(10, rate));
        if (process != null) send("R" + this.rate);
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (process == null) return;
        try {
            in.close(); // end of input is what tells the script to finish
        } catch (IOException ignored) {
        }
        process.destroy();
        process = null;
    }

    /**
     * What the helper process has printed since the last call, one entry per line: {@code ready <voices>} once it is up,
     * then {@code ok} after each command it handled. For tests.
     */
    BlockingQueue<String> replies() {
        return replies;
    }

    private void send(String line) {
        if (failed || closed) return;
        try {
            if (process == null) start();
            in.write(line);
            in.write('\n');
            in.flush();
        } catch (IOException e) {
            fail(e);
        }
    }

    private void start() throws IOException {
        String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        if (silent) builder.environment().put("RENGL_SPEECH_SILENT", "1");
        Process started = builder.start();
        process = started;
        in = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = out.readLine()) != null; ) {
                    replies.offer(line);
                    if (replies.size() > 256) replies.poll(); // nobody is listening outside tests
                }
            } catch (IOException ignored) {
            }
        }, "RenGL speech reader");
        reader.setDaemon(true);
        reader.start();
        if (rate != 0) {
            in.write("R" + rate + "\n");
        }
    }

    private void fail(IOException e) {
        failed = true;
        LOG.warn("Speech is off: could not run PowerShell's speech synthesizer ({})", e.toString());
        if (process != null) process.destroy();
        process = null;
    }

    private static String oneLine(String text) {
        return text.replace('\r', ' ').replace('\n', ' ').strip();
    }
}
