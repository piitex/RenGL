package me.piitex.engine.upscale.fsr;

import me.piitex.engine.render3d.vk.VkGpuDevice;
import me.piitex.engine.render3d.vk.VkImageInfo;
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
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;

/**
 * The FidelityFX API of AMD's FidelityFX SDK (version 2), called through the foreign function interface: the five
 * functions of its loader library ({@code ffxCreateContext}, {@code ffxDestroyContext}, {@code ffxConfigure},
 * {@code ffxQuery} and {@code ffxDispatch}) and the descriptor structs they are given, which are written into native
 * memory field by field ({@link CStruct}) following the SDK's public C headers.
 * <p>
 * The libraries are not part of this module. {@link #load()} looks for the loader ({@code amd_fidelityfx_loader_vk.dll})
 * with the upscaler library ({@code amd_fidelityfx_upscaler_vk.dll}) beside it, in the folder named by the system property
 * {@code rengl.fsr.dir} or the environment variable {@code RENGL_FSR_DIR}, then in the working directory, its
 * {@code natives} and {@code lib} folders, and the folders of {@code java.library.path}. The loader finds the effect library in its own folder.
 */
final class Ffx {
    private static final Logger log = LogManager.getLogger("Engine");

    static final String LOADER = "amd_fidelityfx_loader_vk.dll", UPSCALER = "amd_fidelityfx_upscaler_vk.dll";

    // Structure types: FFX_API_MAKE_EFFECT_SUB_ID(effect, n) puts the effect in bits 16 to 23, FFX_API_MAKE_BACKEND_SUB_ID(backend, n) in bits 24 to 31.
    static final long CREATE_UPSCALE = 0x00010000L, DISPATCH_UPSCALE = 0x00010001L, CREATE_UPSCALE_VERSION = 0x0001000BL;
    static final long CREATE_BACKEND_VK = 0x01000003L;
    static final long QUERY_GET_VERSIONS = 4L, OVERRIDE_VERSION = 5L, QUERY_GET_PROVIDER_VERSION = 6L;

    /** FFX_UPSCALER_MAKE_VERSION(4, 1, 2): (major << 22) | (minor << 12) | patch, the version of the header these structs follow. */
    static final int HEADER_VERSION = (4 << 22) | (1 << 12) | 2;

    // FfxApiCreateContextUpscaleFlags
    static final int ENABLE_HIGH_DYNAMIC_RANGE = 1, ENABLE_AUTO_EXPOSURE = 1 << 5, ENABLE_DEBUG_CHECKING = 1 << 7;

    // FfxApiResourceState and FfxApiResourceUsage
    static final int STATE_UNORDERED_ACCESS = 1 << 1, STATE_COMPUTE_READ = 1 << 2;
    private static final int TEXTURE_2D = 2;

    private static Ffx instance;
    private static boolean tried;
    private static String failure = "not looked for yet";

    private final MethodHandle create, destroy, query, dispatch;
    private final Path loader;

    private Ffx(Path loader, SymbolLookup library) {
        this.loader = loader;
        Linker linker = Linker.nativeLinker();
        create = linker.downcallHandle(library.find("ffxCreateContext").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        destroy = linker.downcallHandle(library.find("ffxDestroyContext").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        query = linker.downcallHandle(library.find("ffxQuery").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        dispatch = linker.downcallHandle(library.find("ffxDispatch").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    }

    /**
     * The loaded API, or null where the libraries were not found or would not load ({@link #failure()} says why).
     */
    static synchronized Ffx load() {
        if (tried) return instance;
        tried = true;
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            failure = "the FidelityFX libraries are only built for Windows";
            return null;
        }
        Path loader = locate();
        if (loader == null) {
            failure = LOADER + " and " + UPSCALER + " were not found (put them in one folder and name it with -Drengl.fsr.dir)";
            log.info("FSR is not available: {}", failure);
            return null;
        }
        try {
            instance = new Ffx(loader, SymbolLookup.libraryLookup(loader, Arena.global()));
            failure = null;
            log.info("FidelityFX found at {}", loader.getParent());
        } catch (Throwable t) {
            failure = "could not load " + loader + ": " + t;
            log.warn("FSR is not available: {}", failure);
        }
        return instance;
    }

    /**
     * Why {@link #load()} gave nothing, or null if it did.
     */
    static synchronized String failure() {
        return instance == null ? failure : null;
    }

    private static Path locate() {
        List<Path> folders = new ArrayList<>();
        String configured = System.getProperty("rengl.fsr.dir", System.getenv("RENGL_FSR_DIR"));
        if (configured != null && !configured.isBlank()) folders.add(Path.of(configured));
        Path here = Path.of("").toAbsolutePath();
        folders.add(here);
        folders.add(here.resolve("natives"));
        folders.add(here.resolve("lib"));
        String libraryPath = System.getProperty("java.library.path", "");
        for (String entry : libraryPath.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) folders.add(Path.of(entry));
        }
        for (Path folder : folders) {
            try {
                Path loader = folder.resolve(LOADER);
                if (Files.isRegularFile(loader) && Files.isRegularFile(folder.resolve(UPSCALER))) return loader.toAbsolutePath();
            } catch (RuntimeException ignored) {
                // a malformed entry of the path
            }
        }
        return null;
    }

    Path loader() {
        return loader;
    }

    // ---- the five functions -------------------------------------------------------------------------------------

    private int call(MethodHandle function, MemorySegment... arguments) {
        try {
            return switch (arguments.length) {
                case 2 -> (int) function.invokeExact(arguments[0], arguments[1]);
                case 3 -> (int) function.invokeExact(arguments[0], arguments[1], arguments[2]);
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    int rawCreate(MemorySegment context, MemorySegment description) {
        return call(create, context, description, MemorySegment.NULL);
    }

    int destroyContext(MemorySegment context) {
        return call(destroy, context, MemorySegment.NULL);
    }

    /**
     * @param context a context, or {@link MemorySegment#NULL} for a query that is not about one
     */
    int query(MemorySegment context, MemorySegment description) {
        return call(query, context, description);
    }

    int dispatch(MemorySegment context, MemorySegment description) {
        return call(dispatch, context, description);
    }

    // ---- messages from the library ------------------------------------------------------------------------------

    private static MemorySegment messageCallback;

    private static void onMessage(int type, MemorySegment message) {
        String text = message.reinterpret(Integer.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
        if (type == 0) log.error("[FidelityFX] {}", text);
        else log.warn("[FidelityFX] {}", text);
    }

    private static synchronized MemorySegment messageCallback() {
        if (messageCallback == null) {
            try {
                MethodHandle target = MethodHandles.lookup().findStatic(Ffx.class, "onMessage",
                        MethodType.methodType(void.class, int.class, MemorySegment.class));
                messageCallback = Linker.nativeLinker().upcallStub(target, FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS), Arena.global());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return messageCallback;
    }

    // ---- the structs --------------------------------------------------------------------------------------------

    /**
     * A {@code VkDeviceContext}: the device, the GPU and the address of {@code vkGetDeviceProcAddr}.
     */
    static CStruct deviceContext(Arena arena, VkGpuDevice device) {
        return new CStruct(arena, 24).ptr(device.vkDevice().address()).ptr(device.vkPhysicalDevice().address())
                .ptr(device.deviceProcAddr()).end();
    }

    /**
     * An {@code ffxCreateBackendVKDesc}, which carries the device context by value.
     */
    static CStruct backend(Arena arena, VkGpuDevice device) {
        return new CStruct(arena, 40).header(CREATE_BACKEND_VK).ptr(device.vkDevice().address())
                .ptr(device.vkPhysicalDevice().address()).ptr(device.deviceProcAddr()).end();
    }

    /**
     * An {@code ffxCreateContextDescUpscale}.
     */
    static CStruct createUpscale(Arena arena, int flags, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                                 MemorySegment messages) {
        return new CStruct(arena, 48).header(CREATE_UPSCALE).u32(flags).u32(renderWidth).u32(renderHeight)
                .u32(displayWidth).u32(displayHeight).ptr(messages).end();
    }

    /**
     * An {@code ffxCreateContextDescUpscaleVersion}: the header version the structs here follow.
     */
    static CStruct headerVersion(Arena arena) {
        return new CStruct(arena, 24).header(CREATE_UPSCALE_VERSION).u32(HEADER_VERSION).end();
    }

    /**
     * An {@code ffxOverrideVersion}: use the provider with this id.
     */
    static CStruct overrideVersion(Arena arena, long id) {
        return new CStruct(arena, 24).header(OVERRIDE_VERSION).u64(id).end();
    }

    /**
     * An {@code ffxQueryDescGetVersions}; the arrays are null for a query that only counts.
     */
    static CStruct getVersions(Arena arena, MemorySegment deviceContext, MemorySegment count, MemorySegment ids, MemorySegment names) {
        return new CStruct(arena, 56).header(QUERY_GET_VERSIONS).u64(CREATE_UPSCALE).ptr(deviceContext).ptr(count)
                .ptr(ids).ptr(names).end();
    }

    /**
     * The FFX surface format of a Vulkan format, 0 (unknown) for one the upscaler does not take.
     */
    static int surfaceFormat(int vkFormat) {
        if (vkFormat == VK_FORMAT_R16G16B16A16_SFLOAT) return 4;
        if (vkFormat == VK_FORMAT_D32_SFLOAT || vkFormat == VK_FORMAT_R32_SFLOAT) return 28;
        if (vkFormat == VK_FORMAT_R8G8B8A8_UNORM) return 10;
        return 0;
    }

    /**
     * The FFX resource usage flags (render target 1, unordered access 2, depth target 4) of a Vulkan image's usage.
     */
    static int resourceUsage(int vkUsage) {
        int usage = 0;
        if ((vkUsage & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0) usage |= 1;
        if ((vkUsage & VK_IMAGE_USAGE_STORAGE_BIT) != 0) usage |= 2;
        if ((vkUsage & VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) usage |= 4;
        return usage;
    }

    /**
     * Writes an {@code FfxApiResource} (48 bytes): the image, its description, and the state it is in when the dispatch begins
     * and is to be left in when it ends.
     */
    static void resource(CStruct into, VkImageInfo image, int state) {
        into.ptr(image.image())
                .u32(TEXTURE_2D).u32(surfaceFormat(image.format())).u32(image.width()).u32(image.height()).u32(1).u32(1).u32(0)
                .u32(resourceUsage(image.usage()))
                .u32(state).end();
    }

    // ---- a context ----------------------------------------------------------------------------------------------

    /**
     * One version of the upscaler the library offers.
     */
    record Version(long id, String name) {
    }

    /**
     * The versions of the upscaler the library offers on this device, in the order it lists them.
     */
    List<Version> versions(Arena arena, VkGpuDevice device) {
        MemorySegment deviceContext = deviceContext(arena, device).segment();
        MemorySegment count = arena.allocate(8, 8);
        if (query(MemorySegment.NULL, getVersions(arena, deviceContext, count, MemorySegment.NULL, MemorySegment.NULL).segment()) != 0) {
            return List.of();
        }
        long n = count.get(JAVA_LONG, 0);
        if (n <= 0 || n > 64) return List.of();
        MemorySegment ids = arena.allocate(8 * n, 8), names = arena.allocate(8 * n, 8);
        if (query(MemorySegment.NULL, getVersions(arena, deviceContext, count, ids, names).segment()) != 0) return List.of();
        List<Version> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, count.get(JAVA_LONG, 0)); i++) {
            MemorySegment name = names.getAtIndex(ADDRESS, i);
            out.add(new Version(ids.getAtIndex(JAVA_LONG, i), name.equals(MemorySegment.NULL) ? "?" : name.reinterpret(256).getString(0)));
        }
        return out;
    }

    /**
     * A live upscaler context.
     */
    static final class Context implements AutoCloseable {
        private final Ffx ffx;
        private final Arena arena;
        private final MemorySegment handle;
        /** The provider the library chose: its id and its name, such as "AMD FSR 3.1.5". */
        final long versionId;
        final String versionName;
        private boolean closed;

        private Context(Ffx ffx, Arena arena, MemorySegment handle, long versionId, String versionName) {
            this.ffx = ffx;
            this.arena = arena;
            this.handle = handle;
            this.versionId = versionId;
            this.versionName = versionName;
        }

        int dispatch(CStruct description) {
            return ffx.dispatch(handle, description.segment());
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            int result = ffx.destroyContext(handle);
            if (result != 0) log.warn("ffxDestroyContext returned {}", result);
            arena.close();
        }
    }

    /**
     * Makes an upscaler context for these sizes. Without a version being asked for, the library picks its newest provider
     * (FSR 4 where the GPU can run it); where that fails, the others it lists are tried in turn.
     *
     * @param only the text of a version's name to use and no other (the system property {@code rengl.fsr.version} does the
     *             same), or null
     * @throws IllegalStateException when no provider would make a context
     */
    Context createContext(VkGpuDevice device, int flags, int renderWidth, int renderHeight, int displayWidth, int displayHeight, String only) {
        if (only == null) only = System.getProperty("rengl.fsr.version");
        boolean debug = Boolean.getBoolean("rengl.fsr.debug");
        if (debug) flags |= ENABLE_DEBUG_CHECKING;
        Arena arena = Arena.ofShared();
        try {
            List<Version> offered = versions(arena, device);
            if (!offered.isEmpty()) log.info("FidelityFX upscaler versions: {}", offered.stream().map(Version::name).toList());
            List<Long> attempts = new ArrayList<>();
            if (only == null || only.isBlank()) {
                attempts.add(null);
                for (Version v : offered) attempts.add(v.id());
            } else {
                for (Version v : offered) if (v.name().toLowerCase().contains(only.toLowerCase())) attempts.add(v.id());
                if (attempts.isEmpty()) throw new IllegalStateException("No FidelityFX upscaler version matches \"" + only + "\" among " + offered);
            }
            StringBuilder errors = new StringBuilder();
            for (Long id : attempts) {
                MemorySegment handle = arena.allocate(8, 8);
                CStruct create = createUpscale(arena, flags, renderWidth, renderHeight, displayWidth, displayHeight, messageCallback());
                CStruct backend = backend(arena, device);
                CStruct version = headerVersion(arena);
                create.chain(backend);
                backend.chain(version);
                if (id != null) version.chain(overrideVersion(arena, id));
                int result = rawCreate(handle, create.segment());
                if (result == 0) {
                    CStruct provider = new CStruct(arena, 32).header(QUERY_GET_PROVIDER_VERSION).u64(0).ptr(MemorySegment.NULL).end();
                    long chosen = 0;
                    String name = "FidelityFX";
                    if (query(handle, provider.segment()) == 0) {
                        chosen = provider.segment().get(JAVA_LONG, 16);
                        MemorySegment text = provider.segment().get(ADDRESS, 24);
                        if (!text.equals(MemorySegment.NULL)) name = text.reinterpret(256).getString(0);
                    }
                    log.info("FidelityFX upscaler: {} ({}x{} to {}x{})", name, renderWidth, renderHeight, displayWidth, displayHeight);
                    return new Context(this, arena, handle, chosen, name);
                }
                errors.append(id == null ? " [default: " : " [" + id + ": ").append(result).append(']');
            }
            throw new IllegalStateException("FidelityFX would not make an upscaler context (ffxCreateContext returned" + errors + ")");
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
    }
}
