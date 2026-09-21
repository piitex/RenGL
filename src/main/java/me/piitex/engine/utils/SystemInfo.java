package me.piitex.engine.utils;

import com.sun.management.OperatingSystemMXBean;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Collects OS / CPU / GPU / memory / disk info for startup diagnostics.
 * <p>
 * Everything here comes from the JDK's own ManagementFactory bean or a single
 * native call per OS, with no OSHI and no JNA. The one exception is the GPU renderer
 * string, which is cheap but requires a current GL context (see gpuFromGL) or,
 * on the Metal path, the device's name (see gpuFromMetalDevice).
 * <p>
 * Call collect() once at startup, after a render context exists, and log() the
 * result. Keep it startup-only: the CPU/disk calls below shell out to the OS
 * and are not fast enough to call per-frame.
 */
@GeneratedClass(
        prompter = "piitex",
        model = "Opus 4.5",
        reviewed = true
)
public final class SystemInfo {
    private static final Logger log = LogManager.getLogger("Engine");

    public final String osName;
    public final String osVersion;
    public final String osArch;

    public final String cpuModel;
    public final int cpuLogicalCores;
    public final double cpuLoadPercent; // -1 if unavailable on first sample

    public final String gpuRenderer;

    public final long totalMemoryMB;
    public final long freeMemoryMB;

    public final long diskTotalGB;
    public final long diskFreeGB;

    private SystemInfo(String osName, String osVersion, String osArch,
                       String cpuModel, int cpuLogicalCores, double cpuLoadPercent,
                       String gpuRenderer,
                       long totalMemoryMB, long freeMemoryMB,
                       long diskTotalGB, long diskFreeGB) {
        this.osName = osName;
        this.osVersion = osVersion;
        this.osArch = osArch;
        this.cpuModel = cpuModel;
        this.cpuLogicalCores = cpuLogicalCores;
        this.cpuLoadPercent = cpuLoadPercent;
        this.gpuRenderer = gpuRenderer;
        this.totalMemoryMB = totalMemoryMB;
        this.freeMemoryMB = freeMemoryMB;
        this.diskTotalGB = diskTotalGB;
        this.diskFreeGB = diskFreeGB;
    }

    /**
     * @param gpuRenderer Pass the result of {@link #gpuFromGL()} on the GL path (call it
     *                    right after glfwMakeContextCurrent/GL.createCapabilities, on the
     *                    render thread), or {@link #gpuFromMetalDevice(long)} on the Metal
     *                    path. Passing null falls back to "Unknown".
     */
    public static SystemInfo collect(String gpuRenderer) {
        String osName = System.getProperty("os.name", "Unknown");
        String osVersion = System.getProperty("os.version", "Unknown");
        String osArch = System.getProperty("os.arch", "Unknown");

        // com.sun.management.OperatingSystemMXBean is part of every mainstream JDK
        // (HotSpot, OpenJ9, Microsoft build, Temurin) even though it's in the
        // com.sun namespace. It's the only JDK-native way to get physical RAM
        // and system-wide (not just this-process) CPU load.
        OperatingSystemMXBean osBean =
                (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        int cores = osBean.getAvailableProcessors();
        long totalMemMB = osBean.getTotalMemorySize() / (1024 * 1024);
        long freeMemMB = osBean.getFreeMemorySize() / (1024 * 1024);

        // The first call to getCpuLoad() or getSystemCpuLoad() often returns -1,
        // since it needs two samples internally. That is acceptable for a one-shot
        // startup log, which may therefore print -1.0%.
        double load = osBean.getCpuLoad() * 100.0;

        String cpuModel = readCpuModel(osName);
        String gpu = (gpuRenderer != null && !gpuRenderer.isBlank()) ? gpuRenderer : "Unknown";

        long diskTotalGB = 0, diskFreeGB = 0;
        try {
            FileStore store = Files.getFileStore(Path.of(".").toAbsolutePath());
            diskTotalGB = store.getTotalSpace() / (1024L * 1024 * 1024);
            diskFreeGB = store.getUsableSpace() / (1024L * 1024 * 1024);
        } catch (IOException e) {
            log.warn("Could not read disk space info: {}", e.getMessage());
        }

        return new SystemInfo(osName, osVersion, osArch, cpuModel, cores, load, gpu,
                totalMemMB, freeMemMB, diskTotalGB, diskFreeGB);
    }

    /**
     * Call on the render thread once a GL context is current.
     */
    public static String gpuFromGL() {
        try {
            // GL11.GL_RENDERER = 0x1F01, avoids importing the GL11 class here.
            String renderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            String vendor = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR);
            if (renderer == null) return null;
            return vendor != null ? vendor + " " + renderer : renderer;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Metal path: pass the MTLDevice pointer from your MetalContext.
     */
    public static String gpuFromMetalDevice(long mtlDevicePtr) {
        try {
            long objc_msgSend = org.lwjgl.system.macosx.ObjCRuntime.getLibrary()
                    .getFunctionAddress("objc_msgSend");
            long nsString = org.lwjgl.system.JNI.invokePPP(
                    mtlDevicePtr,
                    org.lwjgl.system.macosx.ObjCRuntime.sel_getUid("name"),
                    objc_msgSend);
            long utf8Ptr = org.lwjgl.system.JNI.invokePPP(
                    nsString,
                    org.lwjgl.system.macosx.ObjCRuntime.sel_getUid("UTF8String"),
                    objc_msgSend);
            return utf8Ptr == 0L ? null : org.lwjgl.system.MemoryUtil.memUTF8(utf8Ptr);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readCpuModel(String osName) {
        String os = osName.toLowerCase();
        try {
            if (os.contains("win")) {
                // Reliably set by the OS itself, no registry query needed.
                String id = System.getenv("PROCESSOR_IDENTIFIER");
                return id != null ? id : "Unknown";
            } else if (os.contains("mac")) {
                return runCommand("sysctl", "-n", "machdep.cpu.brand_string");
            } else if (os.contains("nux") || os.contains("nix")) {
                return readLinuxCpuModel();
            }
        } catch (Exception e) {
            log.warn("Could not read CPU model: {}", e.getMessage());
        }
        return "Unknown";
    }

    private static String readLinuxCpuModel() throws IOException {
        File cpuinfo = new File("/proc/cpuinfo");
        if (!cpuinfo.exists()) return "Unknown";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(cpuinfo.toPath())))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("model name")) {
                    int idx = line.indexOf(':');
                    if (idx >= 0) return line.substring(idx + 1).trim();
                }
            }
        }
        return "Unknown";
    }

    private static String runCommand(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.readLine();
        }
        process.waitFor();
        return output != null ? output.trim() : "Unknown";
    }

    public void log() {
        log.info("=== System Info ===");
        log.info("OS:     {} {} ({})", osName, osVersion, osArch);
        log.info("CPU:    {} ({} logical cores, {}% load)",
                cpuModel, cpuLogicalCores,
                cpuLoadPercent < 0 ? "n/a" : String.format("%.1f", cpuLoadPercent));
        log.info("GPU:    {}", gpuRenderer);
        log.info("Memory: {} MB free / {} MB total", freeMemoryMB, totalMemoryMB);
        log.info("Disk:   {} GB free / {} GB total", diskFreeGB, diskTotalGB);
        log.info("=== END LOG ===");
    }
}