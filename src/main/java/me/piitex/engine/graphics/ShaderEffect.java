package me.piitex.engine.graphics;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.gl.renderer.SkiaImages;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.jetbrains.skia.FilterTileMode;
import org.jetbrains.skia.RuntimeEffect;
import org.jetbrains.skia.RuntimeShaderBuilder;
import org.jetbrains.skia.Shader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A shader written in SkSL, Skia's shading language, together with the values of its uniforms. SkSL is close to
 * GLSL: the shader is a {@code half4 main(float2 coord)} function returning the color of the point {@code coord}.
 * It runs the same on every backend, the CPU renderer included.
 * <p>
 * An effect is used in two ways:
 * <ul>
 *   <li>{@link Renderer#drawShader} fills a rectangle with it, for procedural backgrounds, water, glows and the
 *       like. {@code coord} runs from (0, 0) at the rectangle's top-left corner to its width and height.</li>
 *   <li>{@link Renderer#pushEffect} and {@link Renderer#popEffect}, or {@link me.piitex.engine.ui.Element#setEffect},
 *       pass everything drawn in between through it: post-processing. The drawing is handed to the shader as
 *       {@code uniform shader content;}, which it must declare, and {@code content.eval(coord)} reads it. Here
 *       {@code coord} is in the coordinates the drawing was made in, so the top-left of the window is (0, 0).</li>
 * </ul>
 * <pre>{@code
 * ShaderEffect vignette = ShaderEffect.compile("""
 *         uniform shader content;
 *         uniform float2 size;
 *         uniform float strength;
 *         half4 main(float2 coord) {
 *             half4 color = content.eval(coord);
 *             float edge = distance(coord / size, float2(0.5)) * strength;
 *             return half4(color.rgb * (1 - edge * edge), color.a);
 *         }
 *         """);
 * vignette.set("size", width, height).set("strength", 1.4f);
 * }</pre>
 * Uniform values stay set until changed, so set a time uniform each frame and the rest once. Setting a uniform the
 * shader doesn't declare, or with the wrong number of values, throws, naming the uniforms it does declare. A
 * {@code shader} uniform other than {@code content} is given a {@link Texture} with {@link #set(String, Texture)}.
 * <p>
 * Render thread only. An effect is not closed by the renderer; {@link #close()} it when it is no longer used, or leave
 * it to the garbage collector.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class ShaderEffect implements AutoCloseable {
    /**
     * The name of the child shader {@link Renderer#pushEffect} hands the drawing to.
     */
    public static final String CONTENT = "content";

    private static final Pattern COMMENTS = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    // [layout(...)] uniform <type> <name>[<n>] [, <name>[<n>]]* ;
    private static final Pattern UNIFORM = Pattern.compile(
            "(?:layout\\s*\\([^)]*\\)\\s*)?\\buniform\\s+(\\w+)\\s+([^;]+);");
    private static final Pattern DECLARATOR = Pattern.compile("\\s*(\\w+)\\s*(?:\\[\\s*(\\d+)\\s*])?\\s*");

    private final String source;
    private final RuntimeEffect effect;
    private final RuntimeShaderBuilder builder;
    private final Map<String, Uniform> uniforms;
    private final Map<String, Shader> children = new HashMap<>();
    private boolean closed;

    private record Uniform(String name, String type, int count, boolean isInt, boolean isChild) {
        String describe() {
            return type + " " + name + (count > 1 && !isChild && baseSize(type) != count ? "[" + count / baseSize(type) + "]" : "");
        }
    }

    private ShaderEffect(String source) {
        this.source = source;
        try {
            this.effect = RuntimeEffect.Companion.makeForShader(source);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("SkSL shader did not compile: " + e.getMessage().strip(), e);
        }
        this.builder = new RuntimeShaderBuilder(effect);
        this.uniforms = parseUniforms(source);
    }

    /**
     * Compiles an SkSL shader. Throws {@link IllegalArgumentException} with the compiler's message, which gives the
     * line and marks the problem, if it doesn't compile.
     */
    public static ShaderEffect compile(String sksl) {
        if (sksl == null) throw new NullPointerException("sksl");
        return new ShaderEffect(sksl);
    }

    /**
     * Reads and compiles an SkSL file. Errors name the file.
     */
    public static ShaderEffect load(Path path) {
        String source;
        try {
            source = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read shader " + path, e);
        }
        try {
            return new ShaderEffect(source);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ": " + e.getMessage(), e);
        }
    }

    /**
     * As {@link #load(Path)}.
     */
    public static ShaderEffect load(String path) {
        return load(Path.of(path));
    }

    /**
     * Sets a float uniform: {@code float}, {@code float2} to {@code float4}, a matrix ({@code float2x2} to
     * {@code float4x4}, in column order), or an array of any of these. {@code half} types are the same. Pass exactly
     * as many values as the uniform holds.
     */
    public ShaderEffect set(String name, float... values) {
        Uniform uniform = uniform(name);
        if (uniform.isChild || uniform.isInt) throw wrongKind(uniform, "floats");
        checkCount(uniform, values.length);
        switch (values.length) {
            case 1 -> builder.uniform(name, values[0]);
            case 2 -> builder.uniform(name, values[0], values[1]);
            case 3 -> builder.uniform(name, values[0], values[1], values[2]);
            case 4 -> builder.uniform(name, values[0], values[1], values[2], values[3]);
            default -> builder.uniform(name, values.clone());
        }
        return this;
    }

    /**
     * Sets an int uniform: {@code int} to {@code int4}. Int arrays aren't supported; use floats.
     */
    public ShaderEffect set(String name, int... values) {
        Uniform uniform = uniform(name);
        if (uniform.isChild || !uniform.isInt) throw wrongKind(uniform, "ints");
        checkCount(uniform, values.length);
        switch (values.length) {
            case 1 -> builder.uniform(name, values[0]);
            case 2 -> builder.uniform(name, values[0], values[1]);
            case 3 -> builder.uniform(name, values[0], values[1], values[2]);
            case 4 -> builder.uniform(name, values[0], values[1], values[2], values[3]);
            default -> throw new IllegalArgumentException("int arrays aren't supported; declare " + name + " as floats");
        }
        return this;
    }

    /**
     * Sets a {@code float4} or {@code half4} uniform to a color's red, green, blue and alpha, each 0 to 1.
     */
    public ShaderEffect set(String name, Color color) {
        if (color == null) throw new NullPointerException("color");
        return set(name, color.getR(), color.getG(), color.getB(), color.getA());
    }

    /**
     * Gives a {@code shader} uniform a texture. {@code name.eval(p)} then reads the texture's pixel at {@code p}, in
     * the texture's own pixels, blending neighbours or not as {@link Texture#isSmooth()} says at the time of this
     * call. Outside the texture it repeats the edge pixels. Pass null to unset it, which makes it read as
     * transparent. {@code content} is reserved for {@link Renderer#pushEffect}.
     */
    public ShaderEffect set(String name, Texture texture) {
        Uniform uniform = uniform(name);
        if (!uniform.isChild) throw wrongKind(uniform, "a texture");
        if (name.equals(CONTENT)) {
            throw new IllegalArgumentException("'" + CONTENT + "' is filled by pushEffect with what is drawn; use another name for a texture");
        }
        Shader shader = texture == null ? Shader.Companion.makeEmpty() // Skia takes no null child; empty reads transparent
                : SkiaImages.imageShader(texture.getImage(), texture.isSmooth(), FilterTileMode.CLAMP);
        builder.child(name, shader);
        Shader old = children.put(name, shader);
        if (old != null) old.close();
        return this;
    }

    /**
     * Whether the shader declares a uniform of this name, of any type.
     */
    public boolean has(String name) {
        return uniforms.containsKey(name);
    }

    /**
     * Whether the shader declares {@code uniform shader content;}, which {@link Renderer#pushEffect} needs.
     */
    public boolean hasContent() {
        Uniform content = uniforms.get(CONTENT);
        return content != null && content.isChild;
    }

    /**
     * The uniforms the shader declares, as they appear in the source: {@code "float2 size"}, {@code "shader content"}.
     */
    public List<String> getUniforms() {
        List<String> names = new ArrayList<>();
        for (Uniform uniform : uniforms.values()) names.add(uniform.describe());
        return Collections.unmodifiableList(names);
    }

    public String getSource() {
        return source;
    }

    /**
     * The Skia builder holding the compiled shader and its uniform values. For {@link Renderer} implementations.
     */
    public RuntimeShaderBuilder getBuilder() {
        if (closed) throw new IllegalStateException("ShaderEffect is closed");
        return builder;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Frees the compiled shader and the textures' shaders. The effect can't be drawn afterwards.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Shader child : children.values()) child.close();
        children.clear();
        builder.close();
        effect.close();
    }

    private Uniform uniform(String name) {
        if (closed) throw new IllegalStateException("ShaderEffect is closed");
        Uniform uniform = uniforms.get(name);
        if (uniform == null) {
            throw new IllegalArgumentException("The shader has no uniform '" + name + "'"
                    + (uniforms.isEmpty() ? "; it declares none" : "; it declares: " + String.join(", ", getUniforms())));
        }
        return uniform;
    }

    private static void checkCount(Uniform uniform, int given) {
        if (given != uniform.count) {
            throw new IllegalArgumentException(uniform.describe() + " takes " + uniform.count
                    + (uniform.count == 1 ? " value" : " values") + ", not " + given);
        }
    }

    private static IllegalArgumentException wrongKind(Uniform uniform, String given) {
        String wants = uniform.isChild ? "a texture" : uniform.isInt ? "ints" : "floats";
        return new IllegalArgumentException(uniform.describe() + " takes " + wants + ", not " + given);
    }

    private static Map<String, Uniform> parseUniforms(String source) {
        Map<String, Uniform> result = new LinkedHashMap<>();
        Matcher matcher = UNIFORM.matcher(COMMENTS.matcher(source).replaceAll(" "));
        while (matcher.find()) {
            String type = matcher.group(1);
            boolean child = type.equals("shader") || type.equals("colorFilter") || type.equals("blender");
            boolean isInt = type.startsWith("int");
            int size = child ? 1 : baseSize(type);
            if (size == 0) continue; // a type this doesn't know; the compiler accepted it, so leave it alone
            for (String declarator : matcher.group(2).split(",")) {
                Matcher d = DECLARATOR.matcher(declarator);
                if (!d.matches()) continue;
                int length = d.group(2) == null ? 1 : Integer.parseInt(d.group(2));
                result.put(d.group(1), new Uniform(d.group(1), type, size * length, isInt, child));
            }
        }
        return result;
    }

    /**
     * Values in one uniform of this type: float3 is 3, float3x3 is 9. 0 for a type this doesn't know.
     */
    private static int baseSize(String type) {
        String base = type.startsWith("float") ? type.substring(5)
                : type.startsWith("half") ? type.substring(4)
                : type.startsWith("int") ? type.substring(3) : null;
        if (base == null) return 0;
        if (base.isEmpty()) return 1;
        if (base.length() == 1 && base.charAt(0) >= '2' && base.charAt(0) <= '4') return base.charAt(0) - '0';
        if (base.length() == 3 && base.charAt(1) == 'x' && !type.startsWith("int")) {
            int columns = base.charAt(0) - '0', rows = base.charAt(2) - '0';
            if (columns >= 2 && columns <= 4 && rows >= 2 && rows <= 4) return columns * rows;
        }
        return 0;
    }
}
