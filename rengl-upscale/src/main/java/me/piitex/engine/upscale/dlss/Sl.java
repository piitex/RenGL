package me.piitex.engine.upscale.dlss;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * NVIDIA Streamline (version 2), called through the foreign function interface: the C functions exported by
 * {@code sl.interposer.dll}, and the typed, versioned structs they are given, which are written into native memory at the
 * offsets a C++ compiler gives them on a 64-bit target (see the struct builders below, and {@code SlStructTest}). Streamline
 * runs NVIDIA's NGX (DLSS) from inside its own libraries, which is why it is used rather than NGX itself.
 * <p>
 * The libraries are not part of this module. {@link #load()} looks for {@code sl.interposer.dll}, with {@code sl.common.dll}
 * and {@code sl.dlss.dll} beside it (from the Streamline SDK's {@code bin/x64}), in the folder named by the system property
 * {@code rengl.dlss.dir} or the environment variable {@code RENGL_DLSS_DIR}, then in the working directory, its
 * {@code natives} and {@code lib} folders, and the folders of {@code java.library.path}.
 */
final class Sl {
    private static final Logger log = LogManager.getLogger("Engine");

    static final String INTERPOSER = "sl.interposer.dll";
    static final int OK = 0;

    /** sl::Feature */
    static final int FEATURE_DLSS = 0, FEATURE_REFLEX = 3, FEATURE_PCL = 4, FEATURE_DLSS_G = 1000, FEATURE_DLSS_RR = 1001;
    /** sl::BufferType */
    static final int BUFFER_DEPTH = 0, BUFFER_MOTION_VECTORS = 1, BUFFER_SCALING_INPUT_COLOR = 3, BUFFER_SCALING_OUTPUT_COLOR = 4, BUFFER_BACKBUFFER = 53,
            BUFFER_ALBEDO = 7, BUFFER_SPECULAR_ALBEDO = 8, BUFFER_NORMAL_ROUGHNESS = 14;
    /** The libraries Ray Reconstruction needs besides the ones of DLSS. */
    static final String[] RAY_RECONSTRUCTION_LIBRARIES = {"sl.dlss_d.dll", "nvngx_dlssd.dll"};
    /** sl::ResourceLifecycle */
    static final int VALID_UNTIL_PRESENT = 1, VALID_UNTIL_EVALUATE = 2;
    /** The libraries frame generation needs besides the ones of DLSS (Reflex and the latency markers are required by it). */
    static final String[] FRAME_GENERATION_LIBRARIES = {"sl.dlss_g.dll", "nvngx_dlssg.dll", "sl.reflex.dll", "sl.pcl.dll"};
    /** sl::PreferenceFlags */
    static final long FLAG_DISABLE_CL_STATE_TRACKING = 1L, FLAG_USE_MANUAL_HOOKING = 1L << 2, FLAG_USE_FRAME_BASED_RESOURCE_TAGGING = 1L << 7;
    /** sl::kSDKVersion: major 2, minor 14, patch 16 and the magic number. The libraries refuse a host built for another major version. */
    static final long SDK_VERSION = (2L << 48) | (14L << 32) | (16L << 16) | 0xfedcL;
    /** sl::Boolean */
    static final byte FALSE = 0, TRUE = 1;
    /** sl::INVALID_FLOAT */
    static final float INVALID_FLOAT = 3.40282346638528859811704183484516925440e38f;

    private static final String[] RESULTS = {"eOk", "eErrorIO", "eErrorDriverOutOfDate", "eErrorOSOutOfDate", "eErrorOSDisabledHWS",
            "eErrorDeviceNotCreated", "eErrorNoSupportedAdapterFound", "eErrorAdapterNotSupported", "eErrorNoPlugins", "eErrorVulkanAPI",
            "eErrorDXGIAPI", "eErrorD3DAPI", "eErrorNRDAPI", "eErrorNVAPI", "eErrorReflexAPI", "eErrorNGXFailed", "eErrorJSONParsing",
            "eErrorMissingProxy", "eErrorMissingResourceState", "eErrorInvalidIntegration", "eErrorMissingInputParameter",
            "eErrorNotInitialized", "eErrorComputeFailed", "eErrorInitNotCalled", "eErrorExceptionHandler", "eErrorInvalidParameter",
            "eErrorMissingConstants", "eErrorDuplicatedConstants", "eErrorMissingOrInvalidAPI", "eErrorCommonConstantsMissing",
            "eErrorUnsupportedInterface", "eErrorFeatureMissing", "eErrorFeatureNotSupported", "eErrorFeatureMissingHooks",
            "eErrorFeatureFailedToLoad", "eErrorFeatureWrongPriority", "eErrorFeatureMissingDependency", "eErrorFeatureManagerInvalidState",
            "eErrorInvalidState", "eWarnOutOfVRAM"};

    /** What a feature needs from the Vulkan instance and device, as Streamline reports it. */
    record Requirements(List<String> instanceExtensions, List<String> deviceExtensions, List<String> features12, List<String> features13,
                        int computeQueues, int graphicsQueues) {
        static final Requirements NONE = new Requirements(List.of(), List.of(), List.of(), List.of(), 0, 0);

        /** What both need. */
        Requirements and(Requirements other) {
            return new Requirements(union(instanceExtensions, other.instanceExtensions), union(deviceExtensions, other.deviceExtensions),
                    union(features12, other.features12), union(features13, other.features13),
                    computeQueues + other.computeQueues, graphicsQueues + other.graphicsQueues);
        }

        private static List<String> union(List<String> a, List<String> b) {
            java.util.Set<String> all = new java.util.LinkedHashSet<>(a);
            all.addAll(b);
            return List.copyOf(all);
        }
    }

    private static Sl instance;
    private static boolean tried;
    private static String failure = "not looked for yet";

    private final Path folder;
    private final MethodHandle init, shutdown, getRequirements, setVulkanInfo, newFrameToken, setConstants, setTagForFrame, evaluate,
            freeResources, getFeatureFunction, isFeatureSupported;
    private final SymbolLookup lib;
    private boolean started, frameGeneration, rayReconstruction;
    private String startFailure, frameGenerationFailure, rayReconstructionFailure;
    private Requirements requirements = Requirements.NONE;

    private Sl(Path interposer, SymbolLookup lib) {
        folder = interposer.getParent();
        this.lib = lib;
        Linker linker = Linker.nativeLinker();
        init = function(linker, lib, "slInit", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
        shutdown = function(linker, lib, "slShutdown", FunctionDescriptor.of(JAVA_INT));
        getRequirements = function(linker, lib, "slGetFeatureRequirements", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        setVulkanInfo = function(linker, lib, "slSetVulkanInfo", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        newFrameToken = function(linker, lib, "slGetNewFrameToken", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        setConstants = function(linker, lib, "slSetConstants", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        setTagForFrame = function(linker, lib, "slSetTagForFrame", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        evaluate = function(linker, lib, "slEvaluateFeature", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        freeResources = function(linker, lib, "slFreeResources", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        getFeatureFunction = function(linker, lib, "slGetFeatureFunction", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        isFeatureSupported = function(linker, lib, "slIsFeatureSupported", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
    }

    private static MethodHandle function(Linker linker, SymbolLookup lib, String name, FunctionDescriptor descriptor) {
        return linker.downcallHandle(lib.find(name).orElseThrow(() -> new IllegalStateException(name + " is not in " + INTERPOSER)), descriptor);
    }

    /** The loaded library, or null where it was not found or would not load ({@link #failure()} says why). */
    static synchronized Sl load() {
        if (tried) return instance;
        tried = true;
        if ("false".equalsIgnoreCase(System.getProperty("rengl.dlss"))) {
            failure = "DLSS is turned off (rengl.dlss=false)";
            return null;
        }
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            failure = "Streamline's DLSS is only supported on Windows";
            return null;
        }
        Path found = locate();
        if (found == null) {
            failure = INTERPOSER + " was not found (copy it with sl.common.dll and sl.dlss.dll from the Streamline SDK's bin/x64 into one folder and name it with -Drengl.dlss.dir)";
            log.info("DLSS is not available: {}", failure);
            return null;
        }
        try {
            instance = new Sl(found, SymbolLookup.libraryLookup(found, Arena.global()));
            failure = null;
            log.info("Streamline found at {}", found.getParent());
        } catch (Throwable t) {
            failure = "could not load " + found + ": " + t;
            log.warn("DLSS is not available: {}", failure);
        }
        return instance;
    }

    static synchronized String failure() {
        return instance == null ? failure : null;
    }

    private static Path locate() {
        List<Path> folders = new ArrayList<>();
        String configured = System.getProperty("rengl.dlss.dir", System.getenv("RENGL_DLSS_DIR"));
        if (configured != null && !configured.isBlank()) folders.add(Path.of(configured));
        Path here = Path.of("").toAbsolutePath();
        folders.add(here);
        folders.add(here.resolve("natives"));
        folders.add(here.resolve("lib"));
        for (String entry : System.getProperty("java.library.path", "").split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) folders.add(Path.of(entry));
        }
        for (Path folder : folders) {
            try {
                Path interposer = folder.resolve(INTERPOSER);
                if (Files.isRegularFile(interposer) && Files.isRegularFile(folder.resolve("sl.common.dll"))
                        && Files.isRegularFile(folder.resolve("sl.dlss.dll"))) {
                    return interposer.toAbsolutePath();
                }
            } catch (RuntimeException ignored) {
                // a malformed entry of the path
            }
        }
        return null;
    }

    // ---- start-up -----------------------------------------------------------------------------------------------------

    private static void onMessage(int type, MemorySegment message) {
        String text = message.reinterpret(Integer.MAX_VALUE).getString(0).stripTrailing();
        if (type == 2) log.error("[Streamline] {}", text);
        else if (type == 1) log.warn("[Streamline] {}", text);
        else if (Boolean.getBoolean("rengl.dlss.log")) log.info("[Streamline] {}", text);
        else log.debug("[Streamline] {}", text);
    }

    private static MemorySegment messageCallback;

    private static synchronized MemorySegment messageCallback() {
        if (messageCallback == null) {
            try {
                MethodHandle target = MethodHandles.lookup().findStatic(Sl.class, "onMessage",
                        MethodType.methodType(void.class, int.class, MemorySegment.class));
                messageCallback = Linker.nativeLinker().upcallStub(target, FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS), Arena.global());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return messageCallback;
    }

    /**
     * Starts Streamline for Vulkan with DLSS, once, and asks what DLSS needs from the instance and device. It has to be done
     * before the Vulkan device is made, which is why the window's device asks for it ({@link DlssExtensions}).
     *
     * @return the requirements, or {@link Requirements#NONE} where Streamline would not start ({@link #startFailure()} says why)
     */
    synchronized Requirements start() {
        if (started || startFailure != null) return requirements;
        try {
            Arena arena = Arena.global(); // Streamline keeps the pointers it is given
            MemorySegment prefs = struct(arena, 144, "1CA10965-BF8E-432B-8DA1-6716D879FB14", 1);
            prefs.set(JAVA_INT, 36, 1); // LogLevel::eDefault
            MemorySegment paths = arena.allocate(8);
            paths.set(ADDRESS, 0, wide(arena, folder.toString()));
            prefs.set(ADDRESS, 40, paths);
            prefs.set(JAVA_INT, 48, 1);
            Path data = Path.of(System.getProperty("java.io.tmpdir"), "rengl-streamline");
            Files.createDirectories(data);
            prefs.set(ADDRESS, 56, wide(arena, data.toString()));
            prefs.set(ADDRESS, 80, messageCallback());
            prefs.set(JAVA_LONG, 88, FLAG_DISABLE_CL_STATE_TRACKING | FLAG_USE_MANUAL_HOOKING | FLAG_USE_FRAME_BASED_RESOURCE_TAGGING);
            boolean framesWanted = frameGenerationRequested(), reconstructionWanted = rayReconstructionRequested();
            List<Integer> list = new ArrayList<>(List.of(FEATURE_DLSS));
            if (reconstructionWanted) list.add(FEATURE_DLSS_RR);
            if (framesWanted) list.addAll(List.of(FEATURE_REFLEX, FEATURE_PCL, FEATURE_DLSS_G));
            int[] wanted = list.stream().mapToInt(Integer::intValue).toArray();
            MemorySegment features = arena.allocate(4L * wanted.length);
            for (int i = 0; i < wanted.length; i++) features.setAtIndex(JAVA_INT, i, wanted[i]);
            prefs.set(ADDRESS, 96, features);
            prefs.set(JAVA_INT, 104, wanted.length);
            prefs.set(ADDRESS, 120, arena.allocateFrom("1.0"));
            prefs.set(ADDRESS, 128, arena.allocateFrom("6b4c2d1e-7a0f-4f2e-9d53-52656e474c33"));
            prefs.set(JAVA_INT, 136, 2); // RenderAPI::eVulkan
            int result = (int) init.invokeExact(prefs, SDK_VERSION);
            if (result != OK) {
                startFailure = "slInit returned " + describe(result);
                log.warn("DLSS is not available: {}", startFailure);
                return requirements;
            }
            Requirements all = Requirements.NONE;
            for (int feature : wanted) {
                MemorySegment fr = struct(arena, 192, "66714097-AC6D-4BC6-8915-1E0F55A6B61F", 2);
                result = (int) getRequirements.invokeExact(feature, fr);
                if (result != OK) {
                    String reason = "slGetFeatureRequirements(" + feature + ") returned " + describe(result);
                    if (feature == FEATURE_DLSS) {
                        startFailure = reason;
                        log.warn("DLSS is not available: {}", startFailure);
                        shutdownQuietly();
                        return requirements;
                    }
                    if (feature == FEATURE_DLSS_RR) {
                        rayReconstructionFailure = reason;
                        log.warn("DLSS Ray Reconstruction is not available: {}", reason);
                    } else {
                        frameGenerationFailure = reason;
                        log.warn("DLSS frame generation is not available: {}", reason);
                    }
                    continue;
                }
                if (Boolean.getBoolean("rengl.dlss.log")) {
                    log.info("[Streamline] feature {} wants {} graphics and {} compute queues, {} optical flow, device extensions {}",
                            feature, fr.get(JAVA_INT, 108), fr.get(JAVA_INT, 104), fr.get(JAVA_INT, 176), strings(fr, 112, 120));
                }
                all = all.and(new Requirements(strings(fr, 128, 136), strings(fr, 112, 120), strings(fr, 144, 152), strings(fr, 160, 168),
                        fr.get(JAVA_INT, 104), fr.get(JAVA_INT, 108)));
                if (feature == FEATURE_DLSS_G) frameGeneration = true;
                if (feature == FEATURE_DLSS_RR) rayReconstruction = true;
            }
            if (!framesWanted && frameGenerationFailure == null) frameGenerationFailure = "frame generation is off (set -Drengl.dlss.framegen=true and put "
                    + String.join(", ", FRAME_GENERATION_LIBRARIES) + " beside " + INTERPOSER + ")";
            requirements = all;
            started = true;
        } catch (Throwable t) {
            startFailure = "Streamline would not start: " + t;
            log.warn("DLSS is not available: {}", startFailure);
            if (t instanceof Error e && !(t instanceof LinkageError)) throw e;
        }
        return requirements;
    }

    synchronized String startFailure() {
        return startFailure;
    }

    synchronized boolean started() {
        return started;
    }

    /** Whether Streamline was started with frame generation (and the libraries it wants) loaded. */
    synchronized boolean frameGenerationLoaded() {
        return started && frameGeneration;
    }

    synchronized String frameGenerationFailure() {
        return frameGeneration ? null : frameGenerationFailure != null ? frameGenerationFailure : startFailure;
    }

    /** Whether Streamline was started with Ray Reconstruction (and the libraries it wants) loaded. */
    synchronized boolean rayReconstructionLoaded() {
        return started && rayReconstruction;
    }

    synchronized String rayReconstructionFailure() {
        return rayReconstruction ? null : rayReconstructionFailure != null ? rayReconstructionFailure : startFailure;
    }

    /**
     * Ray Reconstruction is loaded when its libraries are beside the interposer, unless {@code -Drengl.dlss.rr=false}. It costs
     * nothing until a path traced scene is upscaled.
     */
    private boolean rayReconstructionRequested() {
        if ("false".equalsIgnoreCase(System.getProperty("rengl.dlss.rr"))) {
            rayReconstructionFailure = "Ray Reconstruction is turned off (rengl.dlss.rr=false)";
            return false;
        }
        for (String library : RAY_RECONSTRUCTION_LIBRARIES) {
            if (!Files.isRegularFile(folder.resolve(library))) {
                rayReconstructionFailure = library + " is not beside " + INTERPOSER + " (Ray Reconstruction needs "
                        + String.join(", ", RAY_RECONSTRUCTION_LIBRARIES) + ")";
                log.info("DLSS Ray Reconstruction is not available: {}", rayReconstructionFailure);
                return false;
            }
        }
        return true;
    }

    /**
     * Frame generation is wanted ({@code -Drengl.dlss.framegen=true}) and the libraries it needs are beside the interposer. It
     * costs extra queues, an off-screen swapchain and a load of more libraries, so it is only started when asked for.
     */
    private boolean frameGenerationRequested() {
        if (!Boolean.getBoolean("rengl.dlss.framegen")) return false;
        for (String library : FRAME_GENERATION_LIBRARIES) {
            if (!Files.isRegularFile(folder.resolve(library))) {
                frameGenerationFailure = library + " is not beside " + INTERPOSER + " (frame generation needs " + String.join(", ", FRAME_GENERATION_LIBRARIES) + ")";
                log.warn("DLSS frame generation is not available: {}", frameGenerationFailure);
                return false;
            }
        }
        return true;
    }

    private void shutdownQuietly() {
        try {
            int ignored = (int) shutdown.invokeExact();
        } catch (Throwable ignored) {
            // nothing to do
        }
    }

    // ---- structs: a header (next, the GUID, the version) and the fields at fixed offsets ---------------------------

    /**
     * Zeroed memory for a struct of {@code size} bytes with the header every Streamline struct starts with: the pointer to the
     * next struct of a chain (null), the type's GUID and the version of the struct.
     */
    static MemorySegment struct(Arena arena, long size, String guid, long version) {
        MemorySegment s = arena.allocate(size, 8);
        String[] parts = guid.split("-");
        s.set(JAVA_INT, 8, (int) Long.parseLong(parts[0], 16));
        s.set(JAVA_SHORT, 12, (short) Integer.parseInt(parts[1], 16));
        s.set(JAVA_SHORT, 14, (short) Integer.parseInt(parts[2], 16));
        String tail = parts[3] + parts[4];
        for (int i = 0; i < 8; i++) s.set(JAVA_BYTE, 16 + i, (byte) Integer.parseInt(tail.substring(i * 2, i * 2 + 2), 16));
        s.set(JAVA_LONG, 24, version);
        return s;
    }

    static MemorySegment wide(Arena arena, String text) {
        return arena.allocateFrom(text, StandardCharsets.UTF_16LE);
    }

    private static List<String> strings(MemorySegment struct, long countOffset, long arrayOffset) {
        int n = struct.get(JAVA_INT, countOffset);
        if (n <= 0) return List.of();
        MemorySegment array = struct.get(ADDRESS, arrayOffset).reinterpret(8L * n);
        List<String> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(array.getAtIndex(ADDRESS, i).reinterpret(256).getString(0));
        return List.copyOf(list);
    }

    /** An {@code sl::ViewportHandle}: 40 bytes, the id at 32. */
    static MemorySegment viewport(Arena arena, int id) {
        MemorySegment s = struct(arena, 40, "171B6435-9B3C-4FC8-9994-FBE52569AAA4", 1);
        s.set(JAVA_INT, 32, id);
        return s;
    }

    /** An {@code sl::VulkanInfo} (version 3) for a device whose one queue family does graphics and compute and that has no extra queues. */
    static MemorySegment vulkanInfo(Arena arena, long device, long instance, long physicalDevice, int queueFamily) {
        MemorySegment s = struct(arena, 96, "0EED6FD5-82CD-43A9-BDB5-47A5BA2F45D6", 3);
        s.set(ADDRESS, 32, MemorySegment.ofAddress(device));
        s.set(ADDRESS, 40, MemorySegment.ofAddress(instance));
        s.set(ADDRESS, 48, MemorySegment.ofAddress(physicalDevice));
        s.set(JAVA_INT, 56, 0);           // computeQueueIndex
        s.set(JAVA_INT, 60, queueFamily); // computeQueueFamily
        s.set(JAVA_INT, 64, 0);           // graphicsQueueIndex
        s.set(JAVA_INT, 68, queueFamily); // graphicsQueueFamily
        s.set(JAVA_INT, 72, 0);
        s.set(JAVA_INT, 76, queueFamily); // opticalFlowQueueFamily
        return s;
    }

    /**
     * The same where Streamline's own queues are among those the device was made with: its graphics queues start at
     * {@code graphicsQueueIndex} and its compute queues at {@code computeQueueIndex}, all of {@code queueFamily}.
     */
    static MemorySegment vulkanInfo(Arena arena, long device, long instance, long physicalDevice, int queueFamily,
                                    int graphicsQueueIndex, int computeQueueIndex) {
        MemorySegment s = vulkanInfo(arena, device, instance, physicalDevice, queueFamily);
        s.set(JAVA_INT, 56, computeQueueIndex);
        s.set(JAVA_INT, 64, graphicsQueueIndex);
        return s;
    }

    /** An {@code sl::AdapterInfo} naming a Vulkan GPU. */
    static MemorySegment adapter(Arena arena, long physicalDevice) {
        MemorySegment s = struct(arena, 56, "0677315F-A746-4492-9F42-CB6142C9C3D4", 1);
        s.set(ADDRESS, 48, MemorySegment.ofAddress(physicalDevice));
        return s;
    }

    /**
     * An {@code sl::Resource} (version 1) for a Vulkan image: type 2D, the image, its memory (not given), its view, the layout it
     * is in when used, its size, format and usage.
     */
    static MemorySegment resource(Arena arena, me.piitex.engine.render3d.vk.VkImageInfo image, int layout) {
        MemorySegment s = struct(arena, 112, "3A9D70CF-2418-4B72-8391-13F8721C7261", 1);
        s.set(JAVA_BYTE, 32, (byte) 0); // ResourceType::eTex2d
        s.set(ADDRESS, 40, MemorySegment.ofAddress(image.image()));
        s.set(ADDRESS, 56, MemorySegment.ofAddress(image.view()));
        s.set(JAVA_INT, 64, layout);
        s.set(JAVA_INT, 68, image.width());
        s.set(JAVA_INT, 72, image.height());
        s.set(JAVA_INT, 76, image.format());
        s.set(JAVA_INT, 80, 1);
        s.set(JAVA_INT, 84, 1);
        s.set(JAVA_INT, 100, image.usage());
        return s;
    }

    /** An {@code sl::ResourceTag} (64 bytes): the resource, what it is for, how long it stays valid, and no extent (all of it). */
    static MemorySegment tag(Arena arena, MemorySegment resource, int bufferType) {
        return tag(arena, resource, bufferType, VALID_UNTIL_EVALUATE);
    }

    static MemorySegment tag(Arena arena, MemorySegment resource, int bufferType, int lifecycle) {
        MemorySegment s = struct(arena, 64, "4C6A5AAD-B445-496C-87FF-1AF3845BE653", 1);
        s.set(ADDRESS, 32, resource);
        s.set(JAVA_INT, 40, bufferType);
        s.set(JAVA_INT, 44, lifecycle);
        return s;
    }

    /** The same with the part of the resource to use named: from the corner, {@code width} by {@code height}. */
    static MemorySegment tag(Arena arena, MemorySegment resource, int bufferType, int lifecycle, int width, int height) {
        MemorySegment s = tag(arena, resource, bufferType, lifecycle);
        s.set(JAVA_INT, 56, width);
        s.set(JAVA_INT, 60, height);
        return s;
    }

    /** An {@code sl::ReflexOptions} (48 bytes): the mode (0 off, 1 low latency, 2 with boost) and no frame limit. */
    static MemorySegment reflexOptions(Arena arena, int mode) {
        MemorySegment s = struct(arena, 48, "F03AF81A-6D0B-4902-A651-C4965E215434", 1);
        s.set(JAVA_INT, 32, mode);
        return s;
    }

    /**
     * An {@code sl::DLSSGOptions} (version 5, 120 bytes): the mode (0 off, 1 on), how many frames to make between two drawn
     * ones, and the flags. Everything else is left for Streamline to find out.
     */
    static MemorySegment dlssgOptions(Arena arena, int mode, int framesToGenerate, int flags) {
        MemorySegment s = struct(arena, 120, "FAC5F1CB-2DFD-4F36-A1E6-3A9E865256C5", 5);
        s.set(JAVA_INT, 32, mode);
        s.set(JAVA_INT, 36, framesToGenerate);
        s.set(JAVA_INT, 40, flags);
        s.set(JAVA_BYTE, 104, (byte) 2); // bReserved15: eInvalid, as the header defaults it
        return s;
    }

    /** An empty {@code sl::DLSSGState} (version 4, 88 bytes) for {@code slDLSSGGetState} to fill. */
    static MemorySegment dlssgState(Arena arena) {
        return struct(arena, 88, "CC8AC8E1-A179-44F5-97FA-E74112F9BC61", 4);
    }

    // ---- calls --------------------------------------------------------------------------------------------------------

    static String describe(int result) {
        return result >= 0 && result < RESULTS.length ? RESULTS[result] + " (" + result + ")" : "result " + result;
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    int setVulkanInfo(MemorySegment info) {
        try {
            return (int) setVulkanInfo.invokeExact(info);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    boolean isSupported(int feature, MemorySegment adapter) {
        return support(feature, adapter) == OK;
    }

    /** What {@code slIsFeatureSupported} says of the feature on the adapter ({@link #describe(int)} turns it into words). */
    int support(int feature, MemorySegment adapter) {
        try {
            return (int) isFeatureSupported.invokeExact(feature, adapter);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** A frame token: Streamline's own object, valid for the frame. */
    MemorySegment newFrameToken(Arena arena, int frameIndex) {
        try {
            MemorySegment out = arena.allocate(8), index = arena.allocate(4);
            index.set(JAVA_INT, 0, frameIndex);
            int result = (int) newFrameToken.invokeExact(out, index);
            if (result != OK) throw new IllegalStateException("slGetNewFrameToken returned " + describe(result));
            return out.get(ADDRESS, 0).reinterpret(64);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    int setConstants(MemorySegment constants, MemorySegment frame, MemorySegment viewport) {
        try {
            return (int) setConstants.invokeExact(constants, frame, viewport);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    int setTagForFrame(MemorySegment frame, MemorySegment viewport, MemorySegment tags, int count, long commands) {
        try {
            return (int) setTagForFrame.invokeExact(frame, viewport, tags, count, MemorySegment.ofAddress(commands));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    int evaluate(int feature, MemorySegment frame, MemorySegment inputs, int count, long commands) {
        try {
            return (int) evaluate.invokeExact(feature, frame, inputs, count, MemorySegment.ofAddress(commands));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    int freeResources(int feature, MemorySegment viewport) {
        try {
            return (int) freeResources.invokeExact(feature, viewport);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** A function of a feature ({@code slDLSSSetOptions}), as a handle taking pointers and returning the result. */
    MethodHandle featureFunction(int feature, String name, int pointerArguments) {
        return featureFunction(feature, name, pointerArguments == 1 ? FunctionDescriptor.of(JAVA_INT, ADDRESS)
                : FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    }

    /** A function of a feature with the given signature; only after {@code slSetVulkanInfo}. */
    MethodHandle featureFunction(int feature, String name, FunctionDescriptor descriptor) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(8);
            int result = (int) getFeatureFunction.invokeExact(feature, arena.allocateFrom(name), out);
            if (result != OK) throw new IllegalStateException("slGetFeatureFunction(" + name + ") returned " + describe(result));
            return Linker.nativeLinker().downcallHandle(out.get(ADDRESS, 0), descriptor);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** What Streamline's {@code vkGetInstanceProcAddr} gives for a function of the instance (the surface calls it stands in for). */
    MemorySegment instanceProcAddress(long instance, String name) {
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle get = Linker.nativeLinker().downcallHandle(lib.find("vkGetInstanceProcAddr")
                    .orElseThrow(() -> new IllegalStateException("vkGetInstanceProcAddr is not in " + INTERPOSER)),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            MemorySegment function = (MemorySegment) get.invokeExact(MemorySegment.ofAddress(instance), arena.allocateFrom(name));
            return function.equals(MemorySegment.NULL) ? null : function;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * What Streamline's {@code vkGetDeviceProcAddr} gives for a Vulkan function: the driver's own, except for the few it stands
     * in for (the swapchain calls). Null if there is none.
     */
    MemorySegment deviceProcAddress(long device, String name) {
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle get = Linker.nativeLinker().downcallHandle(lib.find("vkGetDeviceProcAddr")
                    .orElseThrow(() -> new IllegalStateException("vkGetDeviceProcAddr is not in " + INTERPOSER)),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            MemorySegment function = (MemorySegment) get.invokeExact(MemorySegment.ofAddress(device), arena.allocateFrom(name));
            return function.equals(MemorySegment.NULL) ? null : function;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Stops Streamline; the libraries want this before the Vulkan device is destroyed. */
    synchronized void stop() {
        if (!started) return;
        started = false;
        frameGeneration = false;
        rayReconstruction = false;
        shutdownQuietly();
    }
}
