package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.*;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Draws a {@link Scene3D} with one {@link GpuDevice}: the shaders, the uploaded meshes and textures, and the uniform
 * buffers. Render thread only.
 * <p>
 * Every drawn node gets its own uniform buffer, because a buffer can only hold one value per frame and a draw reads it
 * when the GPU gets to it. The buffers are pooled and re-used from frame to frame. Meshes and textures are uploaded
 * when first drawn and freed some time after the scene stops drawing them.
 */
final class SceneRenderer {
    private static final Logger log = LogManager.getLogger("Engine");

    private static final int FRAME_BLOCK_SIZE = 64 + 16 * 3 + 16 * Scene3D.MAX_LIGHTS * 2;
    private static final int OBJECT_BLOCK_SIZE = 64 + 64 + 16 + 16;
    /**
     * Frames an uploaded mesh or texture may go undrawn before it is freed, and how often to look.
     */
    private static final int KEEP_FRAMES = 600;
    private static final int SWEEP_EVERY = 120;
    private static final Material DEFAULT_MATERIAL = new Material();

    private static final String SHADER_BLOCKS = """
            layout(set = 0, binding = 0) uniform Frame {
                mat4 viewProj;
                vec4 cameraPos;
                vec4 ambient;
                vec4 params;
                vec4 lightVec[8];
                vec4 lightColor[8];
            };
            layout(set = 0, binding = 1) uniform Object {
                mat4 model;
                mat4 normalMat;
                vec4 tint;
                vec4 surface;
            };
            """;

    private static final String VERTEX = "#version 450\n" + """
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUv;
            """ + SHADER_BLOCKS + """
            layout(location = 0) out vec3 vWorldPos;
            layout(location = 1) out vec3 vNormal;
            layout(location = 2) out vec2 vUv;
            void main() {
                vec4 world = model * vec4(aPos, 1.0);
                vWorldPos = world.xyz;
                vNormal = mat3(normalMat) * aNormal;
                vUv = aUv;
                gl_Position = viewProj * world;
            }
            """;

    private static final String FRAGMENT = "#version 450\n" + """
            layout(location = 0) in vec3 vWorldPos;
            layout(location = 1) in vec3 vNormal;
            layout(location = 2) in vec2 vUv;
            """ + SHADER_BLOCKS + """
            layout(set = 0, binding = 2) uniform sampler2D baseTexture;
            layout(location = 0) out vec4 outColor;
            void main() {
                vec4 tex = texture(baseTexture, vUv);
                vec3 albedo = pow(tex.rgb * tint.rgb, vec3(2.2));
                float alpha = tex.a * tint.a;
                if (alpha < surface.w) discard;
                vec3 lit = albedo;
                if (surface.z < 0.5) {
                    vec3 n = normalize(vNormal);
                    if (!gl_FrontFacing) n = -n;
                    vec3 v = normalize(cameraPos.xyz - vWorldPos);
                    lit = ambient.rgb * albedo;
                    int count = int(params.x);
                    for (int i = 0; i < 8; i++) {
                        if (i >= count) break;
                        vec3 l;
                        float atten = 1.0;
                        if (lightVec[i].w < 0.5) {
                            l = normalize(lightVec[i].xyz);
                        } else {
                            vec3 d = lightVec[i].xyz - vWorldPos;
                            float dist = length(d);
                            l = d / max(dist, 0.0001);
                            float x = clamp(1.0 - (dist * dist) / (lightColor[i].a * lightColor[i].a), 0.0, 1.0);
                            atten = x * x;
                        }
                        float ndl = max(dot(n, l), 0.0);
                        vec3 radiance = lightColor[i].rgb * atten;
                        lit += albedo * radiance * ndl;
                        if (ndl > 0.0) {
                            vec3 h = normalize(l + v);
                            lit += radiance * surface.x * pow(max(dot(n, h), 0.0), surface.y);
                        }
                    }
                }
                outColor = vec4(pow(lit, vec3(1.0 / 2.2)), alpha);
            }
            """;

    private static final class GpuMesh {
        final GpuBuffer vertices;
        final GpuBuffer indices;
        final int indexCount;
        long lastUsed;

        GpuMesh(GpuBuffer vertices, GpuBuffer indices, int indexCount) {
            this.vertices = vertices;
            this.indices = indices;
            this.indexCount = indexCount;
        }
    }

    private static final class GpuImage {
        final GpuTexture texture;
        long lastUsed;

        GpuImage(GpuTexture texture) {
            this.texture = texture;
        }
    }

    private record Draw(Node3D node, Mesh mesh, Material material, Mat4 world, float distance) {
    }

    private final GpuDevice device;
    private final VertexLayout layout = VertexLayout.of(VertexFormat.FLOAT3, VertexFormat.FLOAT3, VertexFormat.FLOAT2);
    private final Map<Integer, Pipeline> pipelines = new HashMap<>();
    private final Map<Mesh, GpuMesh> meshes = new HashMap<>();
    private final Map<TextureData, GpuImage> images = new HashMap<>();
    private final List<GpuBuffer> objectBuffers = new ArrayList<>();
    private final GpuBuffer frameBuffer;
    private final GpuTexture whiteTexture;
    private final ByteBuffer scratch = MemoryUtil.memAlloc(Math.max(FRAME_BLOCK_SIZE, OBJECT_BLOCK_SIZE)).order(ByteOrder.nativeOrder());
    private long frame;
    private boolean warnedLights;

    SceneRenderer(GpuDevice device) {
        this.device = device;
        frameBuffer = device.createDynamicBuffer(BufferType.UNIFORM, FRAME_BLOCK_SIZE);
        ByteBuffer white = MemoryUtil.memAlloc(4);
        white.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
        whiteTexture = device.createTexture(1, 1, white);
        MemoryUtil.memFree(white);
        pipeline(false, false); // the common one, so a broken shader shows up at init instead of mid-frame
    }

    private Pipeline pipeline(boolean cull, boolean blend) {
        int key = (cull ? 1 : 0) | (blend ? 2 : 0);
        return pipelines.computeIfAbsent(key, k -> device.createPipeline(
                new PipelineDesc(VERTEX, FRAGMENT, layout, true, cull, blend)));
    }

    void render(Scene3D scene, RenderTarget target) {
        frame++;
        float aspect = (float) target.width() / Math.max(1, target.height());
        Camera camera = scene.camera();
        Mat4 view = camera.view();
        Mat4 viewProj = camera.projection(aspect).mul(view);

        List<Draw> opaque = new ArrayList<>();
        List<Draw> blended = new ArrayList<>();
        collect(scene.root(), Mat4.identity(), camera.getPosition(), opaque, blended);
        blended.sort((a, b) -> Float.compare(b.distance, a.distance));

        writeFrameBlock(scene, camera, viewProj);
        int slot = 0;
        for (List<Draw> list : List.of(opaque, blended)) {
            for (Draw draw : list) writeObjectBlock(slot++, draw);
        }

        Color bg = scene.getBackground();
        try (RenderPass pass = device.beginPass(target, bg.getR(), bg.getG(), bg.getB(), 1f)) {
            int key = -1;
            slot = 0;
            for (List<Draw> list : List.of(opaque, blended)) {
                for (Draw draw : list) {
                    GpuMesh mesh = gpuMesh(draw.mesh);
                    Material material = draw.material;
                    boolean cull = !material.isDoubleSided();
                    boolean blend = material.isBlend();
                    int wanted = (cull ? 1 : 0) | (blend ? 2 : 0);
                    if (wanted != key) {
                        pass.bindPipeline(pipeline(cull, blend));
                        pass.bindUniformBuffer(0, frameBuffer);
                        key = wanted;
                    }
                    pass.bindUniformBuffer(1, objectBuffers.get(slot++));
                    pass.bindTexture(2, material.getTexture() == null ? whiteTexture : gpuImage(material.getTexture()));
                    pass.bindVertexBuffer(mesh.vertices);
                    pass.bindIndexBuffer(mesh.indices);
                    pass.drawIndexed(mesh.indexCount);
                }
            }
        }
        if (frame % SWEEP_EVERY == 0) sweep();
    }

    private void collect(Node3D node, Mat4 parentWorld, Vec3 eye, List<Draw> opaque, List<Draw> blended) {
        if (!node.isVisible()) return;
        Mat4 world = parentWorld.mul(node.localMatrix());
        Mesh mesh = node.getMesh();
        if (mesh != null) {
            Material material = node.getMaterial() != null ? node.getMaterial() : DEFAULT_MATERIAL;
            Vec3 origin = world.transformPoint(Vec3.ZERO);
            Draw draw = new Draw(node, mesh, material, world, origin.sub(eye).length());
            (material.isBlend() ? blended : opaque).add(draw);
        }
        for (Node3D child : node.getChildren()) collect(child, world, eye, opaque, blended);
    }

    private static float linear(float srgb) {
        return (float) Math.pow(Math.max(0f, srgb), 2.2);
    }

    private void writeFrameBlock(Scene3D scene, Camera camera, Mat4 viewProj) {
        scratch.clear();
        viewProj.put(scratch);
        Vec3 eye = camera.getPosition();
        scratch.putFloat(eye.x()).putFloat(eye.y()).putFloat(eye.z()).putFloat(1f);
        Color ambient = scene.getAmbient();
        float ai = scene.getAmbientIntensity();
        scratch.putFloat(linear(ambient.getR()) * ai).putFloat(linear(ambient.getG()) * ai).putFloat(linear(ambient.getB()) * ai).putFloat(1f);

        List<Light> lights = scene.lights();
        if (lights.size() > Scene3D.MAX_LIGHTS && !warnedLights) {
            warnedLights = true;
            log.warn("A Scene3D uses at most {} lights; the other {} are ignored.", Scene3D.MAX_LIGHTS, lights.size() - Scene3D.MAX_LIGHTS);
        }
        int count = Math.min(lights.size(), Scene3D.MAX_LIGHTS);
        scratch.putFloat(count).putFloat(0f).putFloat(0f).putFloat(0f);
        for (int i = 0; i < Scene3D.MAX_LIGHTS; i++) {
            if (i < count) {
                Light light = lights.get(i);
                Vec3 v = light.getType() == Light.Type.DIRECTIONAL ? light.getVector().negate() : light.getVector();
                scratch.putFloat(v.x()).putFloat(v.y()).putFloat(v.z()).putFloat(light.getType() == Light.Type.POINT ? 1f : 0f);
            } else {
                scratch.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f);
            }
        }
        for (int i = 0; i < Scene3D.MAX_LIGHTS; i++) {
            if (i < count) {
                Light light = lights.get(i);
                Color c = light.getColor();
                float k = light.getIntensity();
                scratch.putFloat(linear(c.getR()) * k).putFloat(linear(c.getG()) * k).putFloat(linear(c.getB()) * k).putFloat(light.getRange());
            } else {
                scratch.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f);
            }
        }
        scratch.flip();
        frameBuffer.update(scratch);
    }

    private void writeObjectBlock(int slot, Draw draw) {
        while (objectBuffers.size() <= slot) objectBuffers.add(device.createDynamicBuffer(BufferType.UNIFORM, OBJECT_BLOCK_SIZE));
        scratch.clear();
        draw.world.put(scratch);
        draw.world.normalMatrix().put(scratch);
        Material m = draw.material;
        scratch.putFloat(m.getRed()).putFloat(m.getGreen()).putFloat(m.getBlue()).putFloat(m.getAlpha());
        scratch.putFloat(m.getSpecular()).putFloat(Math.max(1f, m.getShininess())).putFloat(m.isUnlit() ? 1f : 0f).putFloat(m.getAlphaCutoff());
        scratch.flip();
        objectBuffers.get(slot).update(scratch);
    }

    private GpuMesh gpuMesh(Mesh mesh) {
        GpuMesh gpu = meshes.get(mesh);
        if (gpu == null) {
            float[] vertices = mesh.vertices();
            int[] indices = mesh.indices();
            ByteBuffer vb = MemoryUtil.memAlloc(vertices.length * 4);
            ByteBuffer ib = MemoryUtil.memAlloc(indices.length * 4);
            try {
                vb.order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices);
                ib.order(ByteOrder.nativeOrder()).asIntBuffer().put(indices);
                gpu = new GpuMesh(device.createBuffer(BufferType.VERTEX, vb), device.createBuffer(BufferType.INDEX, ib), indices.length);
            } finally {
                MemoryUtil.memFree(vb);
                MemoryUtil.memFree(ib);
            }
            meshes.put(mesh, gpu);
        }
        gpu.lastUsed = frame;
        return gpu;
    }

    private GpuTexture gpuImage(TextureData data) {
        GpuImage image = images.get(data);
        if (image == null) {
            ByteBuffer pixels = data.toBuffer();
            try {
                image = new GpuImage(device.createTexture(data.width(), data.height(), pixels));
            } finally {
                MemoryUtil.memFree(pixels);
            }
            images.put(data, image);
        }
        image.lastUsed = frame;
        return image.texture;
    }

    /**
     * Frees what the scene hasn't drawn for a while, so a model that was swapped out gives its memory back.
     */
    private void sweep() {
        for (Iterator<GpuMesh> it = meshes.values().iterator(); it.hasNext(); ) {
            GpuMesh mesh = it.next();
            if (frame - mesh.lastUsed > KEEP_FRAMES) {
                mesh.vertices.close();
                mesh.indices.close();
                it.remove();
            }
        }
        for (Iterator<GpuImage> it = images.values().iterator(); it.hasNext(); ) {
            GpuImage image = it.next();
            if (frame - image.lastUsed > KEEP_FRAMES) {
                image.texture.close();
                it.remove();
            }
        }
    }

    void close() {
        for (GpuMesh mesh : meshes.values()) {
            mesh.vertices.close();
            mesh.indices.close();
        }
        for (GpuImage image : images.values()) image.texture.close();
        for (GpuBuffer buffer : objectBuffers) buffer.close();
        for (Pipeline pipeline : pipelines.values()) pipeline.close();
        frameBuffer.close();
        whiteTexture.close();
        meshes.clear();
        images.clear();
        objectBuffers.clear();
        pipelines.clear();
        MemoryUtil.memFree(scratch);
    }
}
