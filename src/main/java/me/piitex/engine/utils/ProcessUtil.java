package me.piitex.engine.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Cross-platform process termination built on {@link ProcessHandle}, so it behaves the
 * same on Windows, macOS and Linux with no shelling out to {@code taskkill} / {@code kill}.
 * <p>
 * The PID-based call always verifies the process name first: PIDs get recycled by the OS,
 * so a stale PID can point at an unrelated process. If the name does not match (or cannot
 * be read, e.g. a process owned by another user), nothing is killed.
 * <p>
 * Names are compared against the executable's file name, not its full command line. On
 * Windows the comparison is case-insensitive and a trailing {@code .exe} is optional.
 * Note that interpreted programs report their interpreter ({@code java}, {@code python3}).
 */
public final class ProcessUtil {
    private static final Logger log = LogManager.getLogger("Engine");
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** How long to wait for a graceful exit before escalating (when force is enabled). */
    private static final long GRACE_MILLIS = 3000;

    private ProcessUtil() {}

    public enum Result {
        /** The process was found, matched, and is no longer running. */
        TERMINATED,
        /** No process with that PID (or name) exists. */
        NOT_FOUND,
        /** The PID exists but its executable name differs from the expected name, or could not be read. */
        NAME_MISMATCH,
        /** Refused: the target is this JVM, or a PID the OS reserves (0/1). */
        REFUSED,
        /** The process matched but survived (insufficient permission, or force was not requested). */
        FAILED
    }

    /**
     * Ends the process with the given PID, but only if its executable name matches {@code name}.
     *
     * @param pid   the process id
     * @param name  expected executable name, e.g. {@code "notepad"} or {@code "notepad.exe"}
     * @param force if true, escalate to a forcible kill ({@code SIGKILL} / {@code TerminateProcess})
     *              when the process does not exit gracefully
     */
    public static Result kill(long pid, String name, boolean force) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A process name is required");
        }
        if (pid <= 1 || pid == ProcessHandle.current().pid()) {
            return Result.REFUSED;
        }

        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null || !handle.isAlive()) {
            return Result.NOT_FOUND;
        }
        if (!nameMatches(handle, name)) {
            log.warn("Refusing to kill PID {}: name does not match '{}'", pid, name);
            return Result.NAME_MISMATCH;
        }
        return terminate(handle, force);
    }

    /** Graceful-only overload of {@link #kill(long, String, boolean)}. */
    public static Result kill(long pid, String name) {
        return kill(pid, name, false);
    }

    /**
     * Ends every process whose executable name matches {@code name}. Never touches this JVM.
     *
     * @return the number of processes that were terminated
     */
    public static int killByName(String name, boolean force) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A process name is required");
        }
        long self = ProcessHandle.current().pid();
        int killed = 0;
        for (ProcessHandle handle : findByName(name)) {
            if (handle.pid() <= 1 || handle.pid() == self) continue;
            if (terminate(handle, force) == Result.TERMINATED) killed++;
        }
        return killed;
    }

    /** Returns all live processes whose executable name matches {@code name}. */
    public static List<ProcessHandle> findByName(String name) {
        return ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(h -> nameMatches(h, name))
                .toList();
    }

    /** True if a process with this PID is alive and its executable name matches. */
    public static boolean isRunning(long pid, String name) {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).filter(h -> nameMatches(h, name)).isPresent();
    }

    private static Result terminate(ProcessHandle handle, boolean force) {
        try {
            handle.destroy();
            if (waitForExit(handle, GRACE_MILLIS)) return Result.TERMINATED;
            if (!force) return Result.FAILED;

            handle.destroyForcibly();
            return waitForExit(handle, GRACE_MILLIS) ? Result.TERMINATED : Result.FAILED;
        } catch (SecurityException e) {
            log.warn("Not permitted to kill PID {}", handle.pid());
            return Result.FAILED;
        }
    }

    private static boolean waitForExit(ProcessHandle handle, long millis) {
        try {
            handle.onExit().get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !handle.isAlive();
        } catch (Exception e) {
            return !handle.isAlive();
        }
    }

    private static boolean nameMatches(ProcessHandle handle, String expected) {
        // command() is empty when the OS hides it (other user's process, protected process).
        // Treat unreadable as a mismatch so the PID check can never be bypassed.
        String actual = handle.info().command().map(ProcessUtil::fileName).orElse(null);
        if (actual == null) return false;
        return normalize(actual).equals(normalize(fileName(expected)));
    }

    private static String fileName(String command) {
        try {
            Path name = Path.of(command).getFileName();
            return name == null ? command : name.toString();
        } catch (RuntimeException e) {
            return command; // invalid path characters; compare as-is
        }
    }

    private static String normalize(String name) {
        if (!WINDOWS) return name;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".exe") ? lower.substring(0, lower.length() - 4) : lower;
    }
}
