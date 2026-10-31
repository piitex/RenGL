package me.piitex.engine.render3d;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spvc;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spvc.*;

/**
 * Turns the engine's one shader language into what each backend needs. Shaders are written once as GLSL 450 with
 * Vulkan-style resource bindings:
 * <pre>
 * layout(set = 0, binding = 0) uniform Globals { mat4 mvp; };
 * layout(set = 0, binding = 1) uniform sampler2D albedo;
 * </pre>
 * Vulkan takes the SPIR-V that shaderc compiles from it. OpenGL takes GLSL 330 that SPIRV-Cross writes back out of that
 * SPIR-V, with the depth range rewritten from Vulkan's 0..1 to OpenGL's -1..1 in the vertex shader, which is why
 * shaders on both backends can use the same projection matrices.
 * <p>
 * Reflection on the SPIR-V finds the uniform blocks and samplers, so the backends can build their descriptor layouts
 * and bind blocks and texture units without the shader author declaring anything twice.
 */
public final class ShaderCompiler {

    private ShaderCompiler() {

    }

    /**
     * A uniform block or sampler a shader uses.
     *
     * @param binding the {@code binding} number in the GLSL
     * @param sampler true for a texture sampler, false for a uniform block
     * @param glName  the name OpenGL knows it by: the block's name, or the sampler variable's name
     * @param accel   true for an acceleration structure ({@code accelerationStructureEXT}), which only Vulkan has
     */
    public record Binding(int binding, boolean sampler, String glName, boolean accel) {
        public Binding(int binding, boolean sampler, String glName) {
            this(binding, sampler, glName, false);
        }
    }

    public static final class Compiled {
        public final ByteBuffer vertexSpirv;
        public final ByteBuffer fragmentSpirv;
        public final List<Binding> bindings;

        Compiled(ByteBuffer vertexSpirv, ByteBuffer fragmentSpirv, List<Binding> bindings) {
            this.vertexSpirv = vertexSpirv;
            this.fragmentSpirv = fragmentSpirv;
            this.bindings = bindings;
        }

        /**
         * GLSL 330 for OpenGL, from the SPIR-V.
         */
        public String vertexGlsl() {
            return toGlsl(vertexSpirv, true);
        }

        public String fragmentGlsl() {
            return toGlsl(fragmentSpirv, false);
        }
    }

    /**
     * Compiles both stages. Throws {@link IllegalArgumentException} with the compiler's message and the stage's name
     * when the GLSL is wrong.
     */
    public static Compiled compile(PipelineDesc desc) {
        ByteBuffer vertex = toSpirv(desc.vertexSource(), shaderc_vertex_shader, "vertex");
        ByteBuffer fragment;
        try {
            fragment = toSpirv(desc.fragmentSource(), shaderc_fragment_shader, "fragment");
        } catch (RuntimeException e) {
            org.lwjgl.system.MemoryUtil.memFree(vertex);
            throw e;
        }
        TreeMap<Integer, Binding> merged = new TreeMap<>();
        for (Binding binding : reflect(vertex)) merged.put(binding.binding(), binding);
        for (Binding binding : reflect(fragment)) merged.putIfAbsent(binding.binding(), binding);
        return new Compiled(vertex, fragment, List.copyOf(merged.values()));
    }

    /**
     * Compiled SPIR-V, in malloc'd memory the caller frees (see {@link #free}).
     */
    private static ByteBuffer toSpirv(String source, int kind, String stage) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == NULL) throw new IllegalStateException("shaderc could not start");
        long options = shaderc_compile_options_initialize();
        long result = NULL;
        try {
            if (source.contains("GL_EXT_ray_query")) {
                // Ray queries are Vulkan 1.2 and the SPIR-V that goes with it (1.5); a shader that uses one never goes to OpenGL.
                shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            } else {
                shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_0);
            }
            result = shaderc_compile_into_spv(compiler, source, kind, stage + ".glsl", "main", options);
            if (result == NULL) throw new IllegalStateException("shaderc failed on the " + stage + " shader");
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new IllegalArgumentException("The " + stage + " shader does not compile:\n"
                        + shaderc_result_get_error_message(result));
            }
            ByteBuffer bytes = shaderc_result_get_bytes(result);
            ByteBuffer copy = org.lwjgl.system.MemoryUtil.memAlloc(bytes.remaining());
            copy.put(bytes).flip();
            return copy;
        } finally {
            if (result != NULL) shaderc_result_release(result);
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    public static void free(ByteBuffer spirv) {
        if (spirv != null) org.lwjgl.system.MemoryUtil.memFree(spirv);
    }

    private interface SpvcWork<T> {
        T run(MemoryStack stack, long context, long compiler);
    }

    private static <T> T withCompiler(ByteBuffer spirv, SpvcWork<T> work) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer out = stack.mallocPointer(1);
            checkSpvc(0, spvc_context_create(out), "create a context");
            long context = out.get(0);
            try {
                IntBuffer words = spirv.asIntBuffer();
                checkSpvc(context, spvc_context_parse_spirv(context, words, words.remaining(), out), "parse the SPIR-V");
                long parsed = out.get(0);
                checkSpvc(context, spvc_context_create_compiler(context, SPVC_BACKEND_GLSL, parsed,
                        SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "create a compiler");
                return work.run(stack, context, out.get(0));
            } finally {
                spvc_context_destroy(context);
            }
        }
    }

    private static String toGlsl(ByteBuffer spirv, boolean vertexStage) {
        return withCompiler(spirv, (stack, context, compiler) -> {
            PointerBuffer out = stack.mallocPointer(1);
            checkSpvc(context, spvc_compiler_create_compiler_options(compiler, out), "create options");
            long options = out.get(0);
            spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_GLSL_VERSION, 330);
            spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_GLSL_ES, false);
            // GLSL 330 has no binding= qualifier (and macOS's GL 4.1 lacks the extension); the device binds by name.
            spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_GLSL_ENABLE_420PACK_EXTENSION, false);
            // Vulkan clip space has depth 0..1, OpenGL's is -1..1; only the vertex stage writes gl_Position.
            spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FIXUP_DEPTH_CONVENTION, vertexStage);
            checkSpvc(context, spvc_compiler_install_compiler_options(compiler, options), "install options");
            checkSpvc(context, spvc_compiler_compile(compiler, out), "write GLSL");
            return org.lwjgl.system.MemoryUtil.memUTF8(out.get(0));
        });
    }

    private static List<Binding> reflect(ByteBuffer spirv) {
        return withCompiler(spirv, (stack, context, compiler) -> {
            PointerBuffer out = stack.mallocPointer(1);
            checkSpvc(context, spvc_compiler_create_shader_resources(compiler, out), "reflect");
            long resources = out.get(0);
            List<Binding> found = new ArrayList<>();
            collect(stack, context, compiler, resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, false, found);
            collect(stack, context, compiler, resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, true, found);
            collect(stack, context, compiler, resources, SPVC_RESOURCE_TYPE_ACCELERATION_STRUCTURE, false, found);
            return found;
        });
    }

    private static void collect(MemoryStack stack, long context, long compiler, long resources, int type,
                                boolean sampler, List<Binding> into) {
        PointerBuffer list = stack.mallocPointer(1);
        PointerBuffer count = stack.mallocPointer(1);
        checkSpvc(context, spvc_resources_get_resource_list_for_type(resources, type, list, count), "list resources");
        int size = (int) count.get(0);
        if (size == 0) return;
        org.lwjgl.util.spvc.SpvcReflectedResource.Buffer buffer =
                org.lwjgl.util.spvc.SpvcReflectedResource.create(list.get(0), size);
        for (int i = 0; i < size; i++) {
            org.lwjgl.util.spvc.SpvcReflectedResource resource = buffer.get(i);
            int binding = spvc_compiler_get_decoration(compiler, resource.id(), org.lwjgl.util.spvc.Spv.SpvDecorationBinding);
            // OpenGL finds a block by the name of its type, and a sampler by its variable's name.
            String name = sampler ? spvc_compiler_get_name(compiler, resource.id())
                    : spvc_compiler_get_name(compiler, resource.base_type_id());
            into.add(new Binding(binding, sampler, name, type == SPVC_RESOURCE_TYPE_ACCELERATION_STRUCTURE));
        }
    }

    private static void checkSpvc(long context, int result, String what) {
        if (result == SPVC_SUCCESS) return;
        String detail = context == 0 ? "" : ": " + spvc_context_get_last_error_string(context);
        throw new IllegalStateException("SPIRV-Cross could not " + what + detail);
    }
}
