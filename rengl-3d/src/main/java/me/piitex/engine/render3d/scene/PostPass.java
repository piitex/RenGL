package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.BufferType;
import me.piitex.engine.render3d.GpuBuffer;
import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.GpuTexture;
import me.piitex.engine.render3d.Pipeline;
import me.piitex.engine.render3d.PipelineDesc;
import me.piitex.engine.render3d.RayScene;
import me.piitex.engine.render3d.RenderPass;
import me.piitex.engine.render3d.RenderTarget;
import me.piitex.engine.render3d.VertexFormat;
import me.piitex.engine.render3d.VertexLayout;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.upscale.JitterSequence;
import me.piitex.engine.render3d.upscale.UpscaleInputs;
import me.piitex.engine.render3d.upscale.Upscaler;
import me.piitex.engine.render3d.upscale.UpscalerProvider;
import org.lwjgl.system.MemoryUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Everything done to the finished picture: bloom, ambient occlusion, a lens's colour fringes and vignette, and the colour
 * grade.
 * <p>
 * The scene is drawn into an offscreen target of its own instead of the one the viewport shows, in linear light and in
 * 16-bit floating point, so a lamp ten times brighter than white is still ten times brighter when this reads it. The
 * target is multisampled like the viewport's and resolved when the scene is done. Then this takes the picture through a few
 * full-screen passes:
 * <ul>
 *   <li>Bloom keeps only what is brighter than a threshold, blurs that at half the size and again at a quarter of it
 *   (a wide soft glow and a tight one). The blurs are separable (a row pass, then a column pass), nine taps each.</li>
 *   <li>Ambient occlusion reads a depth picture of the solid objects (drawn by the scene renderer, one pass over the same
 *   geometry), works out at half the size how much of the space around each point is filled by other surfaces, and
 *   blurs that without smearing it across an edge.</li>
 *   <li>The last pass draws the picture, the glow and the occlusion into the viewport's target together: tone mapped and
 *   gamma encoded there, with the colour fringes, the grade and the vignette.</li>
 * </ul>
 * When the scene is drawn smaller than it is shown, or an {@link Upscaler} is on, the scene target has the render size and
 * everything after it the display size: the upscaler (or the composite's own smooth filtering, without one) enlarges the
 * picture first, in linear light, and the effects that follow work at the size the picture is shown at.
 * <p>
 * One of these belongs to each render call of a scene (like the buffers the scene fills every frame), so a scene shown by
 * two viewports in a frame doesn't have one overwrite what the other is about to read.
 */
final class PostPass {
    private static final Logger log = LogManager.getLogger("Engine");

    /**
     * What the finishing pass does, as the scene asks for it.
     *
     * @param toneMap the scene's tone mapper, by its number in the shader
     */
    record Settings(float bloom, float bloomThreshold, float bloomRadius, int toneMap,
                    float saturation, float contrast, float[] shadowTint, float[] highlightTint,
                    float chromaticAberration, float vignette, float occlusion, float occlusionRadius,
                    float tanHalfFovX, float tanHalfFovY, float near, float far) {
        static Settings of(Scene3D scene, float aspect) {
            Camera camera = scene.camera();
            float tanY = (float) Math.tan(Math.toRadians(camera.getFovYDegrees()) * 0.5);
            return new Settings(scene.getBloom(), scene.getBloomThreshold(), scene.getBloomRadius(), scene.getToneMapping().ordinal(),
                    scene.getSaturation(), scene.getContrast(), rgb(scene.getShadowTint()), rgb(scene.getHighlightTint()),
                    scene.getChromaticAberration(), scene.getVignette(), scene.getAmbientOcclusion(), scene.getAmbientOcclusionRadius(),
                    tanY * aspect, tanY, camera.getNear(), camera.getFar());
        }

        private static float[] rgb(me.piitex.engine.ui.color.Color c) {
            return new float[]{c.getR(), c.getG(), c.getB()};
        }

        boolean glows() {
            return bloom > 0f;
        }

        boolean occludes() {
            return occlusion > 0f;
        }
    }

    private static final String VERTEX = """
            #version 450
            layout(location = 0) in vec2 aPos;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """;

    private static final String HEAD = """
            #version 450
            layout(set = 0, binding = 0) uniform Post {
                vec4 a;
                vec4 b;
                vec4 c;
                vec4 d;
            };
            layout(set = 0, binding = 1) uniform sampler2D src;
            layout(location = 0) out vec4 outColor;
            """;

    /**
     * Keeps the part of each pixel above the threshold, with a soft shoulder so a pixel doesn't switch on at a hard edge:
     * a.z is the threshold, a.w the width of the shoulder.
     */
    private static final String BRIGHT = HEAD + """
            void main() {
                // Capped so one blinding pixel (a sun, a specular spike) doesn't flood the whole glow.
                vec3 c = min(texture(src, gl_FragCoord.xy * a.xy).rgb, vec3(64.0));
                float peak = max(c.r, max(c.g, c.b));
                float soft = clamp(peak - a.z + a.w, 0.0, 2.0 * a.w);
                soft = soft * soft / (4.0 * a.w + 1e-5);
                float keep = max(soft, peak - a.z) / max(peak, 1e-4);
                outColor = vec4(c * keep, 1.0);
            }
            """;

    /**
     * One direction of a gaussian blur: b.xy is the step between taps, in texture coordinates, along that direction.
     */
    private static final String BLUR = HEAD + """
            void main() {
                vec2 uv = gl_FragCoord.xy * a.xy;
                vec3 sum = texture(src, uv).rgb * 0.227027;
                sum += (texture(src, uv + b.xy).rgb + texture(src, uv - b.xy).rgb) * 0.1945946;
                sum += (texture(src, uv + b.xy * 2.0).rgb + texture(src, uv - b.xy * 2.0).rgb) * 0.1216216;
                sum += (texture(src, uv + b.xy * 3.0).rgb + texture(src, uv - b.xy * 3.0).rgb) * 0.054054;
                sum += (texture(src, uv + b.xy * 4.0).rgb + texture(src, uv - b.xy * 4.0).rgb) * 0.016216;
                outColor = vec4(sum, 1.0);
            }
            """;

    /**
     * Reads the packed depth the scene renderer's depth pass wrote (the shadow map's packing: the distance along the view
     * axis, between the near and far plane, over three 8-bit channels). {@code b.x} and {@code b.y} are the planes. A
     * negative result means nothing was drawn there.
     */
    private static final String DEPTH = """
            float viewDepth(ivec2 p) {
                p = clamp(p, ivec2(0), textureSize(src, 0) - 1);
                float code = dot(texelFetch(src, p, 0).rgb, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                return code >= 0.9999 ? -1.0 : b.x + code * (b.y - b.x);
            }
            """;

    /**
     * Ambient occlusion at half the size. src is the packed depth at the full size. a.xy is one over the full size, a.zw
     * the tangent of half the field of view across and up; b.x and b.y the near and far plane, b.z the radius, b.w +1 where
     * the image's rows run bottom-up (OpenGL) and -1 where they run top-down (Vulkan).
     * <p>
     * Each pixel's normal comes from the depth of its neighbours (taking the closer side on each axis, so an edge doesn't
     * bend it); fourteen points are put in the hemisphere above the surface, turned a little differently at every pixel, and
     * each counts as blocked if the depth picture shows a surface in front of it. A blocker much farther from the surface
     * than the radius counts for less, so a post in front of a wall doesn't darken the wall around it.
     */
    private static final String OCCLUSION = HEAD + DEPTH + """
            vec3 viewPos(ivec2 p, float d) {
                vec2 uv = (vec2(p) + 0.5) * a.xy;
                vec2 ndc = vec2(uv.x * 2.0 - 1.0, (uv.y * 2.0 - 1.0) * b.w);
                return vec3(ndc.x * a.z * d, ndc.y * a.w * d, -d);
            }
            void main() {
                ivec2 p = ivec2(gl_FragCoord.xy) * 2;
                float d = viewDepth(p);
                if (d < 0.0) {
                    outColor = vec4(1.0);
                    return;
                }
                vec3 pos = viewPos(p, d);
                float dl = viewDepth(p + ivec2(-1, 0)), dr = viewDepth(p + ivec2(1, 0));
                float du = viewDepth(p + ivec2(0, -1)), dd = viewDepth(p + ivec2(0, 1));
                if (dl < 0.0) dl = d;
                if (dr < 0.0) dr = d;
                if (du < 0.0) du = d;
                if (dd < 0.0) dd = d;
                vec3 px = abs(dr - d) < abs(dl - d) ? viewPos(p + ivec2(1, 0), dr) - pos : pos - viewPos(p + ivec2(-1, 0), dl);
                vec3 py = abs(dd - d) < abs(du - d) ? viewPos(p + ivec2(0, 1), dd) - pos : pos - viewPos(p + ivec2(0, -1), du);
                vec3 n = normalize(cross(px, py));
                if (dot(n, pos) > 0.0) n = -n;
                vec3 t = normalize(abs(n.y) < 0.9 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
                vec3 bt = cross(n, t);
                float rot = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) * 6.2831853;
                float radius = b.z;
                // The depth picture is sampled a pixel at a time, so on a surface seen at a slant a point just above it can
                // read as behind it. The bias is the size of a pixel at this depth, stretched by the slant, plus a share of the
                // radius; a radius smaller than a few pixels does nothing rather than speckle every slope.
                float footprint = d * a.w * 2.0 * a.y;
                float bias = 0.03 * radius + 1.5 * footprint / max(dot(n, normalize(-pos)), 0.15);
                float blocked = 0.0;
                for (int i = 0; i < 14; i++) {
                    float h = (float(i) + 0.5) / 14.0;
                    float ang = float(i) * 2.399963 + rot;
                    float r = sqrt(1.0 - h * h);
                    vec3 dir = t * (cos(ang) * r) + bt * (sin(ang) * r) + n * h;
                    float s = fract((float(i) + 0.5) * 0.618034);
                    vec3 sp = pos + dir * (radius * mix(0.15, 1.0, s * s));
                    if (sp.z >= -0.001) continue;
                    vec2 ndc = vec2(sp.x / (a.z * -sp.z), sp.y / (a.w * -sp.z));
                    vec2 uv = vec2(ndc.x * 0.5 + 0.5, ndc.y * b.w * 0.5 + 0.5);
                    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) continue;
                    float sd = viewDepth(ivec2(uv / a.xy));
                    if (sd < 0.0) continue;
                    float in_front = -sp.z - sd;
                    float reach = smoothstep(0.0, 1.0, radius / max(abs(d - sd), 1e-4));
                    blocked += in_front > bias ? reach : 0.0;
                }
                // A sample aimed away from a blocker can never be blocked, so a right-angle corner blocks about half of them at
                // most; twice the share makes that corner fully dark, and the strength setting takes it from there.
                outColor = vec4(vec3(1.0 - clamp(blocked * 2.0 / 14.0, 0.0, 1.0)), 1.0);
            }
            """;

    /**
     * Ambient occlusion by rays, in place of {@link #OCCLUSION}, at half the size too: src is the packed depth, binding 2 the
     * scene's acceleration structure. The same depth gives each pixel's place and normal, which are put in the world with the
     * camera's axes (right, up and forward); eight rays spread over the hemisphere above it, thicker toward the surface's normal,
     * go as far as the radius, and each that hits something counts for more the nearer the hit is. Where the pattern of rays
     * starts turns from pixel to pixel (and from frame to frame with a temporal upscaler), and the blur that follows takes the
     * grain out. c.xyz is the camera's position and c.w the frame; a, b as in {@link #OCCLUSION}.
     */
    private static final String OCCLUSION_RAYS = """
            #version 460
            #extension GL_EXT_ray_query : require
            layout(set = 0, binding = 0) uniform PostRays {
                vec4 a;
                vec4 b;
                vec4 c;
                vec4 right;
                vec4 up;
                vec4 forward;
            };
            layout(set = 0, binding = 1) uniform sampler2D src;
            layout(set = 0, binding = 2) uniform accelerationStructureEXT world;
            layout(location = 0) out vec4 outColor;
            """ + DEPTH + """
            vec3 viewPos(ivec2 p, float d) {
                vec2 uv = (vec2(p) + 0.5) * a.xy;
                vec2 ndc = vec2(uv.x * 2.0 - 1.0, (uv.y * 2.0 - 1.0) * b.w);
                return vec3(ndc.x * a.z * d, ndc.y * a.w * d, -d);
            }
            const int RAYS = 8;
            void main() {
                ivec2 p = ivec2(gl_FragCoord.xy) * 2;
                float d = viewDepth(p);
                if (d < 0.0) {
                    outColor = vec4(1.0);
                    return;
                }
                vec3 pos = viewPos(p, d);
                float dl = viewDepth(p + ivec2(-1, 0)), dr = viewDepth(p + ivec2(1, 0));
                float du = viewDepth(p + ivec2(0, -1)), dd = viewDepth(p + ivec2(0, 1));
                if (dl < 0.0) dl = d;
                if (dr < 0.0) dr = d;
                if (du < 0.0) du = d;
                if (dd < 0.0) dd = d;
                vec3 px = abs(dr - d) < abs(dl - d) ? viewPos(p + ivec2(1, 0), dr) - pos : pos - viewPos(p + ivec2(-1, 0), dl);
                vec3 py = abs(dd - d) < abs(du - d) ? viewPos(p + ivec2(0, 1), dd) - pos : pos - viewPos(p + ivec2(0, -1), du);
                vec3 n = normalize(cross(px, py));
                if (dot(n, pos) > 0.0) n = -n;
                // View space looks down -z; the world is reached by the camera's axes.
                vec3 nw = normalize(right.xyz * n.x + up.xyz * n.y - forward.xyz * n.z);
                float radius = b.z;
                // Off the surface by what the depth picture's precision allows, so a ray never starts inside the surface it left.
                vec3 origin = c.xyz + right.xyz * pos.x + up.xyz * pos.y - forward.xyz * pos.z + nw * (0.004 * d + 0.01 * radius);
                vec3 t = normalize(abs(nw.y) < 0.9 ? cross(nw, vec3(0.0, 1.0, 0.0)) : cross(nw, vec3(1.0, 0.0, 0.0)));
                vec3 bt = cross(nw, t);
                float rot = fract(52.9829189 * fract(dot(gl_FragCoord.xy + c.w * 5.588238, vec2(0.06711056, 0.00583715)))) * 6.2831853;
                float blocked = 0.0;
                for (int i = 0; i < RAYS; i++) {
                    float h = (float(i) + 0.5) / float(RAYS);
                    float ang = float(i) * 2.399963 + rot;
                    float r = sqrt(h);
                    vec3 dir = t * (cos(ang) * r) + bt * (sin(ang) * r) + nw * sqrt(1.0 - h);
                    rayQueryEXT query;
                    rayQueryInitializeEXT(query, world, gl_RayFlagsOpaqueEXT, 0x02, origin, 0.0, dir, radius);
                    while (rayQueryProceedEXT(query)) { }
                    if (rayQueryGetIntersectionTypeEXT(query, true) != gl_RayQueryCommittedIntersectionNoneEXT) {
                        float x = rayQueryGetIntersectionTEXT(query, true) / radius;
                        blocked += 1.0 - x * x;
                    }
                }
                outColor = vec4(vec3(1.0 - blocked / float(RAYS)), 1.0);
            }
            """;

    /**
     * One direction of a blur of the occlusion that doesn't cross an edge: src is the occlusion at half size, the packed
     * depth is the second texture. a.xy is one over the half size and a.zw the near and far plane; b.xy the step between
     * taps in texture coordinates along the direction, b.z the radius (depth differences below half of it blur); c.xy the
     * same step in half-size pixels.
     */
    private static final String OCCLUSION_BLUR = HEAD.replace("layout(location = 0) out", """
            layout(set = 0, binding = 2) uniform sampler2D depthTex;
            layout(location = 0) out""") + """
            float depthAt(ivec2 cell) {
                ivec2 p = clamp(cell * 2, ivec2(0), textureSize(depthTex, 0) - 1);
                float code = dot(texelFetch(depthTex, p, 0).rgb, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                return code >= 0.9999 ? -1.0 : a.z + code * (a.w - a.z);
            }
            void main() {
                ivec2 here = ivec2(gl_FragCoord.xy);
                float d0 = depthAt(here);
                vec2 uv = gl_FragCoord.xy * a.xy;
                if (d0 < 0.0) {
                    outColor = vec4(1.0);
                    return;
                }
                float sum = texture(src, uv).r * 0.227027, weight = 0.227027;
                float taps[4] = float[4](0.1945946, 0.1216216, 0.054054, 0.016216);
                for (int k = 1; k <= 4; k++) {
                    for (int side = 0; side < 2; side++) {
                        float sgn = side == 0 ? 1.0 : -1.0;
                        float dk = depthAt(here + ivec2(c.xy * (sgn * float(k))));
                        if (dk < 0.0) continue;
                        float w = taps[k - 1] * exp(-abs(dk - d0) / max(0.5 * b.z, 1e-4));
                        sum += texture(src, uv + b.xy * (sgn * float(k))).r * w;
                        weight += w;
                    }
                }
                outColor = vec4(vec3(sum / weight), 1.0);
            }
            """;

    /**
     * The picture, the glow and the occlusion added in linear light, then tone mapped like the scene shader does it (the
     * exposure is already in), gamma encoded, and graded.
     * <p>
     * a.z is the strength of the glow, a.w the tone mapper (see {@code Scene3D#setToneMapping}); b is the saturation,
     * contrast, colour fringe and vignette; c holds the shadow tint and the occlusion strength; d the highlight tint.
     */
    private static final String COMPOSITE = HEAD.replace("layout(location = 0) out", """
            layout(set = 0, binding = 2) uniform sampler2D wide;
            layout(set = 0, binding = 3) uniform sampler2D tight;
            layout(set = 0, binding = 4) uniform sampler2D occlusion;
            layout(location = 0) out""") + SceneShaders.TONE_MAP + """
            void main() {
                vec2 uv = gl_FragCoord.xy * a.xy;
                vec2 centre = uv - 0.5;
                vec3 col = texture(src, uv).rgb;
                if (b.z > 0.0) {
                    // Red and blue pulled apart along the line from the centre, more toward the edge.
                    vec2 off = centre * (b.z * length(centre) * 2.0) * 0.5;
                    col = vec3(texture(src, uv + off).r, col.g, texture(src, uv - off).b);
                }
                if (c.w > 0.0) col *= pow(texture(occlusion, uv).r, c.w);
                vec3 glow = texture(wide, uv).rgb + texture(tight, uv).rgb;
                vec3 lit = pow(max(toneMap(col + glow * a.z, int(a.w + 0.5)), vec3(0.0)), vec3(1.0 / 2.2));
                float luma = dot(lit, vec3(0.2126, 0.7152, 0.0722));
                lit = mix(vec3(luma), lit, b.x);
                lit = (lit - 0.5) * b.y + 0.5;
                lit *= mix(c.rgb, d.rgb, smoothstep(0.1, 0.9, luma));
                float edge = dot(centre, centre) * 2.0;
                lit *= 1.0 - clamp(b.w * edge * edge, 0.0, 1.0);
                outColor = vec4(clamp(lit, 0.0, 1.0), 1.0);
            }
            """;

    /**
     * What the two passes that filter the indirect light read. a.xy is one over the size and a.zw the tangent of half the field of
     * view across and up; b.x and b.y the near and far plane, b.z +1 where the image's rows run bottom-up and -1 where they run
     * top-down, b.w the step between taps (spatial) or the share of this frame in the picture (temporal). eye, right, up and
     * forward are the camera's, lastEye to lastForward last frame's, and last holds last frame's tangents and, in z, 1 where it has
     * a picture to reproject. src is the packed depth, noisy the light the rays found (the depth at its pixel is in alpha too).
     */
    private static final String GI_HEAD = """
            #version 450
            layout(set = 0, binding = 0) uniform GiBlock {
                vec4 a;
                vec4 b;
                vec4 eye;
                vec4 right;
                vec4 up;
                vec4 forward;
                vec4 lastEye;
                vec4 lastRight;
                vec4 lastUp;
                vec4 lastForward;
                vec4 last;
            };
            layout(set = 0, binding = 1) uniform sampler2D src;
            layout(set = 0, binding = 2) uniform sampler2D noisy;
            //@HISTORY
            layout(location = 0) out vec4 outColor;
            """ + DEPTH + """
            vec3 viewPos(ivec2 p, float d) {
                vec2 uv = (vec2(p) + 0.5) * a.xy;
                vec2 ndc = vec2(uv.x * 2.0 - 1.0, (uv.y * 2.0 - 1.0) * b.z);
                return vec3(ndc.x * a.z * d, ndc.y * a.w * d, -d);
            }
            // The normal of the surface from the depth of its neighbours, taking the closer side on each axis so an edge doesn't bend it.
            vec3 viewNormal(ivec2 p, float d, vec3 pos) {
                float dl = viewDepth(p + ivec2(-1, 0)), dr = viewDepth(p + ivec2(1, 0));
                float du = viewDepth(p + ivec2(0, -1)), dd = viewDepth(p + ivec2(0, 1));
                if (dl < 0.0) dl = d;
                if (dr < 0.0) dr = d;
                if (du < 0.0) du = d;
                if (dd < 0.0) dd = d;
                vec3 px = abs(dr - d) < abs(dl - d) ? viewPos(p + ivec2(1, 0), dr) - pos : pos - viewPos(p + ivec2(-1, 0), dl);
                vec3 py = abs(dd - d) < abs(du - d) ? viewPos(p + ivec2(0, 1), dd) - pos : pos - viewPos(p + ivec2(0, -1), du);
                vec3 n = normalize(cross(px, py));
                return dot(n, pos) > 0.0 ? -n : n;
            }
            """;

    /**
     * Where a pixel was last frame (its place in the world seen from last frame's camera): the texel at the corner of the 2 by 2
     * block around that place, the fraction across it, and the depth along last frame's view, for matching it against the
     * history's. False where there is no history to look in, or the place is behind last frame's camera.
     */
    private static final String GI_REPROJECT = GI_HEAD + """
            bool reproject(ivec2 p, float d, out ivec2 base, out vec2 f, out float z) {
                base = ivec2(0);
                f = vec2(0.0);
                z = 0.0;
                if (last.z < 0.5) return false;
                vec3 pos = viewPos(p, d);
                vec3 world = eye.xyz + right.xyz * pos.x + up.xyz * pos.y - forward.xyz * pos.z;
                vec3 seen = world - lastEye.xyz;
                z = dot(seen, lastForward.xyz);
                if (z <= 0.0) return false;
                vec2 ndc = vec2(dot(seen, lastRight.xyz) / (last.x * z), dot(seen, lastUp.xyz) / (last.y * z));
                vec2 at = vec2(ndc.x * 0.5 + 0.5, ndc.y * b.z * 0.5 + 0.5) / a.xy - 0.5;
                base = ivec2(floor(at));
                f = at - vec2(base);
                return true;
            }
            """;

    /**
     * Averages the light the rays found over frames: each pixel is found where it was last frame (its place in the world seen
     * from last frame's camera), and last frame's filtered light there is blended with this frame's, the share of this frame
     * being b.w. A pixel whose last place shows a different surface (the depth along the view differs) has no history and takes
     * this frame's light alone, for the spatial pass to smooth. Alpha is the depth.
     */
    private static final String GI_TEMPORAL = GI_REPROJECT.replace("//@HISTORY", "layout(set = 0, binding = 3) uniform sampler2D history;\n"
            + "layout(set = 0, binding = 4) uniform sampler2D counts;") + """
            void main() {
                ivec2 p = ivec2(gl_FragCoord.xy);
                float d = viewDepth(p);
                if (d < 0.0) {
                    outColor = vec4(0.0, 0.0, 0.0, -1.0);
                    return;
                }
                vec3 current = texelFetch(noisy, p, 0).rgb;
                vec3 result = current;
                // The share of this frame is one over the number of frames the history holds, down to b.w: a pixel that just
                // showed up takes its first frames as they come, and one that has been there a while settles.
                float count = texelFetch(counts, p, 0).r;
                ivec2 base;
                vec2 f;
                float z;
                if (count > 1.5 && reproject(p, d, base, f, z)) {
                    ivec2 size = textureSize(history, 0);
                    vec3 sum = vec3(0.0);
                    float weight = 0.0;
                    for (int j = 0; j < 2; j++) {
                        for (int i = 0; i < 2; i++) {
                            ivec2 q = base + ivec2(i, j);
                            if (q.x < 0 || q.y < 0 || q.x >= size.x || q.y >= size.y) continue;
                            vec4 h = texelFetch(history, q, 0);
                            if (abs(h.a - z) > 0.03 * z + 0.01) continue;
                            float w = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y);
                            sum += h.rgb * w;
                            weight += w;
                        }
                    }
                    if (weight > 0.01) result = mix(sum / weight, current, max(1.0 / count, b.w));
                }
                outColor = vec4(result, d);
            }
            """;

    /**
     * How many frames the history of each pixel holds: the count of the places it was last frame (the history's depth must
     * match, as for the temporal pass, and a pixel with no match starts again at 1), weighed as the temporal pass weighs
     * them, plus one. Written in red.
     */
    private static final String GI_COUNT = GI_REPROJECT.replace("//@HISTORY", "layout(set = 0, binding = 3) uniform sampler2D history;\n"
            + "layout(set = 0, binding = 4) uniform sampler2D counts;") + """
            void main() {
                ivec2 p = ivec2(gl_FragCoord.xy);
                float d = viewDepth(p);
                float count = 1.0;
                ivec2 base;
                vec2 f;
                float z;
                if (d >= 0.0 && reproject(p, d, base, f, z)) {
                    ivec2 size = textureSize(history, 0);
                    float sum = 0.0;
                    float weight = 0.0;
                    for (int j = 0; j < 2; j++) {
                        for (int i = 0; i < 2; i++) {
                            ivec2 q = base + ivec2(i, j);
                            if (q.x < 0 || q.y < 0 || q.x >= size.x || q.y >= size.y) continue;
                            vec4 h = texelFetch(history, q, 0);
                            if (abs(h.a - z) > 0.03 * z + 0.01) continue;
                            float w = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y);
                            sum += texelFetch(counts, q, 0).r * w;
                            weight += w;
                        }
                    }
                    if (weight > 0.01) count = min(sum / weight + 1.0, 255.0);
                }
                outColor = vec4(count, 0.0, 0.0, 1.0);
            }
            """;

    /**
     * One step of an edge-stopping wavelet filter (a 5 by 5 spread of taps b.w pixels apart): a tap counts for its place in the
     * B-spline kernel, less the farther its surface is from the plane of this pixel's, so light never crosses from one surface
     * to another. Alpha is the depth.
     */
    private static final String GI_SPATIAL = GI_HEAD.replace("//@HISTORY", "") + """
            void main() {
                ivec2 p = ivec2(gl_FragCoord.xy);
                float d = viewDepth(p);
                if (d < 0.0) {
                    outColor = vec4(0.0, 0.0, 0.0, -1.0);
                    return;
                }
                vec3 pos = viewPos(p, d);
                vec3 n = viewNormal(p, d, pos);
                float reach = 0.004 * d + 0.01;
                int stride = int(b.w + 0.5);
                ivec2 size = textureSize(noisy, 0);
                float kernel[3] = float[3](1.0, 0.6666667, 0.1666667);
                vec3 centre = texelFetch(noisy, p, 0).rgb;
                // With last.w set, a tap also counts for how much its brightness differs from this pixel's, in steps of the
                // noise around the pixel (the spread of the 3 by 3 pixels about it): a bright patch keeps its edge, and
                // noise, which is what the spread measures, is averaged away.
                float lc = dot(centre, vec3(0.299, 0.587, 0.114));
                float sigma = 1.0;
                if (last.w > 0.5) {
                    float m1 = 0.0, m2 = 0.0;
                    for (int j = -1; j <= 1; j++) {
                        for (int i = -1; i <= 1; i++) {
                            float l = dot(texelFetch(noisy, clamp(p + ivec2(i, j), ivec2(0), size - 1), 0).rgb, vec3(0.299, 0.587, 0.114));
                            m1 += l;
                            m2 += l * l;
                        }
                    }
                    m1 /= 9.0;
                    m2 /= 9.0;
                    sigma = 4.0 * sqrt(max(m2 - m1 * m1, 0.0)) + 0.01 + 0.05 * m1;
                }
                vec3 sum = centre;
                float weight = 1.0;
                for (int j = -2; j <= 2; j++) {
                    for (int i = -2; i <= 2; i++) {
                        if (i == 0 && j == 0) continue;
                        ivec2 q = p + ivec2(i, j) * stride;
                        if (q.x < 0 || q.y < 0 || q.x >= size.x || q.y >= size.y) continue;
                        float dq = viewDepth(q);
                        if (dq < 0.0) continue;
                        float off = abs(dot(n, viewPos(q, dq) - pos));
                        float w = kernel[abs(i)] * kernel[abs(j)] * exp(-off / reach);
                        vec3 tap = texelFetch(noisy, q, 0).rgb;
                        if (last.w > 0.5) w *= exp(-abs(dot(tap, vec3(0.299, 0.587, 0.114)) - lc) / sigma);
                        sum += tap * w;
                        weight += w;
                    }
                }
                outColor = vec4(sum / weight, d);
            }
            """;

    /** The share of a frame's own light in the picture the next frame starts from: about the last ten frames count. */
    private static final float GI_BLEND = 0.1f;
    /** The same for path tracing, whose picture is noisier: about the last twenty frames count. */
    private static final float PATH_BLEND = 0.05f;
    private static final int GI_BLOCK_BYTES = 192;

    private static final int UNIFORM_BYTES = 64;
    private static final int PASSES = 12;
    private static final float REFERENCE_HEIGHT = 1080f;

    private final GpuDevice device;
    private final GpuBuffer triangle;
    private final Pipeline bright, blur, composite, occlusion, occlusionBlur;
    /** The occlusion shader that traces rays, made when first wanted; null until then or when it does not compile here. */
    private Pipeline rayOcclusion;
    private boolean rayOcclusionBroken;
    private GpuBuffer rayOcclusionBlock;
    /**
     * The indirect light: the rays' own noisy picture (with the depth buffer the surfaces are drawn with), the filtered one of
     * this frame and last frame's taking turns as the history, and the one the spatial passes pass through. Made when first
     * wanted, with the pipelines, which are null until then or when they do not compile here.
     */
    private RenderTarget giRaw, giFilter;
    private final RenderTarget[] giHistory = new RenderTarget[2];
    private int giWidth, giHeight, giCurrent;
    /** How many frames each pixel of the history holds, taking turns like the history. */
    private final RenderTarget[] giCount = new RenderTarget[2];
    private Pipeline giTemporal, giSpatial, giCounting;
    private boolean giBroken;
    private final GpuBuffer[] giBlocks = new GpuBuffer[5];
    /** Last frame's camera (position, right, up, forward) and field of view, for finding where a pixel was; valid once a frame has been filtered. */
    private final float[] giLastCamera = new float[14];
    private boolean giHistoryValid;
    private int giSeenResets = -1;
    private final GpuBuffer[] uniforms = new GpuBuffer[PASSES];
    private final ByteBuffer scratch = MemoryUtil.memAlloc(UNIFORM_BYTES).order(ByteOrder.nativeOrder());
    private RenderTarget scene, half1, half2, quarter1, quarter2, depth, aoA, aoB;
    /** The size the scene is drawn at. */
    private int width, height, samples;
    private int depthWidth, depthHeight;
    /** The size of the picture shown, which the bloom and the upscaler's output follow. */
    private int displayWidth, displayHeight;

    /**
     * The pictures an upscaler that denoises is guided by (diffuse albedo, specular albedo, normal and roughness), at the render
     * size, each with a depth buffer of its own to be drawn with. Made when first asked for.
     */
    private final RenderTarget[] guideTargets = new RenderTarget[3];
    private int guideWidth, guideHeight;
    /** The depth and motion of the solid objects at the render size, for a temporal upscaler. */
    private RenderTarget velocity;
    private int velocityWidth, velocityHeight;
    /** The upscaler's output, this frame's and last frame's, taking turns. */
    private final RenderTarget[] upscaled = new RenderTarget[2];
    private int upscaledWidth, upscaledHeight, current;
    private boolean upscaledStorage;
    private UpscalerProvider provider;
    private Upscaler upscaler;
    private final TemporalState temporal = new TemporalState();
    private int seenResets;
    private final float[] jitter = new float[2];

    PostPass(GpuDevice device, GpuBuffer fullScreenTriangle) {
        this.device = device;
        this.triangle = fullScreenTriangle;
        VertexLayout layout = VertexLayout.of(VertexFormat.FLOAT2);
        bright = device.createPipeline(new PipelineDesc(VERTEX, BRIGHT, layout, false, false, false));
        blur = device.createPipeline(new PipelineDesc(VERTEX, BLUR, layout, false, false, false));
        composite = device.createPipeline(new PipelineDesc(VERTEX, COMPOSITE, layout, false, false, false));
        occlusion = device.createPipeline(new PipelineDesc(VERTEX, OCCLUSION, layout, false, false, false));
        occlusionBlur = device.createPipeline(new PipelineDesc(VERTEX, OCCLUSION_BLUR, layout, false, false, false));
        for (int i = 0; i < PASSES; i++) uniforms[i] = device.createDynamicBuffer(BufferType.UNIFORM, UNIFORM_BYTES);
    }

    /**
     * What one frame is drawn with: the size to draw the scene at, the upscaler (null for none), and for a temporal one the
     * camera's jitter this frame.
     *
     * @param reset the history is to be ignored this frame
     */
    record Frame(int renderWidth, int renderHeight, int displayWidth, int displayHeight, Upscaler upscaler, boolean temporal,
                 float jitterX, float jitterY, boolean reset) {
    }

    /**
     * Decides how the frame is drawn: at what size, with which upscaler, and where the camera is moved to. Makes the
     * upscaler when the scene's provider is not the one this pass has, and drops the history when anything it was built on
     * changed.
     */
    Frame beginFrame(Scene3D scene, int displayWidth, int displayHeight) {
        UpscalerProvider wanted = scene.getUpscaler();
        boolean reset = false;
        if (wanted != provider) {
            if (upscaler != null) upscaler.close();
            upscaler = null;
            provider = wanted;
            if (wanted != null) upscaler = wanted.create(device);
            reset = true;
        }
        float scale = scene.getRenderScale();
        if (provider != null && scale < 1f) scale = provider.supportedRenderScale(scale, displayWidth, displayHeight);
        int renderWidth = scale >= 1f ? displayWidth : Math.max(1, Math.round(displayWidth * scale));
        int renderHeight = scale >= 1f ? displayHeight : Math.max(1, Math.round(displayHeight * scale));
        if (renderWidth != width || renderHeight != height || displayWidth != this.displayWidth || displayHeight != this.displayHeight) reset = true;
        int resets = scene.temporalResets();
        if (resets != seenResets) {
            seenResets = resets;
            reset = true;
        }
        if (reset) temporal.reset();
        boolean isTemporal = upscaler != null && upscaler.isTemporal();
        if (isTemporal) {
            JitterSequence.offset(temporal.frame, upscaler.jitterPhases(renderWidth, displayWidth), jitter);
        } else {
            jitter[0] = jitter[1] = 0f;
        }
        return new Frame(renderWidth, renderHeight, displayWidth, displayHeight, upscaler, isTemporal, jitter[0], jitter[1], reset || temporal.frame == 0);
    }

    TemporalState temporal() {
        return temporal;
    }

    /**
     * The target the scene is drawn into, at the size the frame wants, with floating point colour and the same
     * multisampling (the GPU's limit permitting). Its picture, resolved to one sample per pixel, is what the later passes
     * read.
     */
    RenderTarget sceneTarget(Frame frame, int samples) {
        int width = frame.renderWidth(), height = frame.renderHeight();
        if (scene == null || this.width != width || this.height != height || this.samples != samples
                || displayWidth != frame.displayWidth() || displayHeight != frame.displayHeight()) {
            closeTargets();
            this.width = width;
            this.height = height;
            this.samples = samples;
            displayWidth = frame.displayWidth();
            displayHeight = frame.displayHeight();
            scene = device.createSampledTarget(width, height, true, true, true, samples);
            // The glow follows the size the picture is shown at, not the size it was drawn at.
            int hw = Math.max(1, (displayWidth + 1) / 2), hh = Math.max(1, (displayHeight + 1) / 2);
            int qw = Math.max(1, (hw + 1) / 2), qh = Math.max(1, (hh + 1) / 2);
            half1 = device.createSampledTarget(hw, hh, false, true, true, 1);
            half2 = device.createSampledTarget(hw, hh, false, true, true, 1);
            quarter1 = device.createSampledTarget(qw, qh, false, true, true, 1);
            quarter2 = device.createSampledTarget(qw, qh, false, true, true, 1);
        }
        return scene;
    }

    /**
     * What a denoising upscaler is given besides the picture: the pictures drawn for it, and the camera's view matrix.
     */
    record Guides(GpuTexture diffuse, GpuTexture specular, GpuTexture normal, float[] worldToView) {
    }

    /**
     * The three targets the guides are drawn into, in the order diffuse albedo, specular albedo, normal and roughness: the size of
     * the scene's, 16-bit floating point colour. Call {@link #sceneTarget} first.
     */
    RenderTarget[] guideTargets() {
        if (guideTargets[0] == null || guideWidth != width || guideHeight != height) {
            closeGuides();
            for (int i = 0; i < guideTargets.length; i++) guideTargets[i] = device.createSampledTarget(width, height, true, false, true, 1);
            guideWidth = width;
            guideHeight = height;
        }
        return guideTargets;
    }

    private void closeGuides() {
        for (int i = 0; i < guideTargets.length; i++) {
            if (guideTargets[i] != null) guideTargets[i].close();
            guideTargets[i] = null;
        }
        guideWidth = guideHeight = 0;
    }

    /**
     * The target the velocity pass draws into: motion in the colour, depth in the readable depth buffer, at the render size.
     */
    RenderTarget velocityTarget() {
        if (velocity == null || velocityWidth != width || velocityHeight != height) {
            if (velocity != null) velocity.close();
            velocity = device.createDepthSampledTarget(width, height, false, true);
            velocityWidth = width;
            velocityHeight = height;
        }
        return velocity;
    }

    /**
     * The target the depth of the solid objects is drawn into for occlusion: the size of the scene's, one sample per
     * pixel, with the shadow map's packing in its colour. Made when first asked for, so a scene without occlusion has none.
     */
    RenderTarget depthTarget() {
        if (depth == null || depthWidth != width || depthHeight != height) {
            if (depth != null) depth.close();
            if (aoA != null) aoA.close();
            if (aoB != null) aoB.close();
            depth = device.createSampledTarget(width, height, true, false);
            depthWidth = width;
            depthHeight = height;
            int hw = Math.max(1, (width + 1) / 2), hh = Math.max(1, (height + 1) / 2);
            aoA = device.createSampledTarget(hw, hh, false, true, false, 1);
            aoB = device.createSampledTarget(hw, hh, false, true, false, 1);
        }
        return depth;
    }

    /**
     * Turns the scene target's picture into the shown picture.
     *
     * @param hasDepth whether {@link #depthTarget()} was drawn this frame, which occlusion needs
     * @param frame    how the frame was drawn
     * @param motion   whether {@link #velocityTarget()} was drawn this frame, which a temporal upscaler needs
     * @param rays     the scene placed for rays, to work the occlusion out with them in place of the depth picture, or null
     * @param rayFrame which frame this is, for the pattern the occlusion rays start from
     * @param guides   the pictures that go with a path traced scene that the upscaler is to denoise, or null
     * @return whether the occlusion was traced
     */
    boolean apply(RenderTarget output, Settings s, boolean hasDepth, Frame frame, boolean motion, Camera camera, float delta,
                  RayScene rays, int rayFrame, Guides guides) {
        int pass = 0;
        boolean traced = false;
        GpuTexture source = scene.texture();
        if (frame.upscaler() != null) source = upscale(frame, motion, camera, delta, output.isBottomLeftOrigin(), guides);
        boolean glow = s.glows();
        if (glow) {
            float shoulder = 0.5f * Math.max(s.bloomThreshold(), 0.05f);
            // Bright parts, at half size.
            fullScreen(half1, bright, uniforms[pass++], new float[]{1f / half1.width(), 1f / half1.height(), s.bloomThreshold(), shoulder}, null, null,
                    source, null, null, null);
            // A soft glow at half size, then a wide one at a quarter.
            // The spread is a share of the picture's height, not a count of pixels: the same glow on a high-density display
            // as on a plain one, and in a small viewport as in a full-screen one. A 1080 pixel high picture has a step of one texel.
            float step = s.bloomRadius() * Math.clamp(output.height() / REFERENCE_HEIGHT, 0.1f, 4f);
            fullScreen(half2, blur, uniforms[pass++], new float[]{1f / half2.width(), 1f / half2.height(), 0f, 0f}, new float[]{step / half1.width(), 0f, 0f, 0f}, null,
                    half1.texture(), null, null, null);
            fullScreen(half1, blur, uniforms[pass++], new float[]{1f / half1.width(), 1f / half1.height(), 0f, 0f}, new float[]{0f, step / half2.height(), 0f, 0f}, null,
                    half2.texture(), null, null, null);
            fullScreen(quarter2, blur, uniforms[pass++], new float[]{1f / quarter2.width(), 1f / quarter2.height(), 0f, 0f}, new float[]{step / quarter2.width(), 0f, 0f, 0f}, null,
                    half1.texture(), null, null, null);
            fullScreen(quarter1, blur, uniforms[pass++], new float[]{1f / quarter1.width(), 1f / quarter1.height(), 0f, 0f}, new float[]{0f, step / quarter2.height(), 0f, 0f}, null,
                    quarter2.texture(), null, null, null);
        }
        boolean occluded = hasDepth && s.occludes() && depth != null;
        if (occluded) {
            float ySign = output.isBottomLeftOrigin() ? 1f : -1f;
            traced = rays != null && traceOcclusion(aoA, s, ySign, rays, camera, rayFrame);
            if (!traced) {
                fullScreen(aoA, occlusion, uniforms[pass++], new float[]{1f / depth.width(), 1f / depth.height(), s.tanHalfFovX(), s.tanHalfFovY()},
                        new float[]{s.near(), s.far(), s.occlusionRadius(), ySign}, null, depth.texture(), null, null, null);
            }
            float halfW = 1f / aoA.width(), halfH = 1f / aoA.height();
            fullScreen(aoB, occlusionBlur, uniforms[pass++], new float[]{halfW, halfH, s.near(), s.far()},
                    new float[]{halfW, 0f, s.occlusionRadius(), 0f}, new float[]{1f, 0f, 0f, 0f}, aoA.texture(), depth.texture(), null, null);
            fullScreen(aoA, occlusionBlur, uniforms[pass++], new float[]{halfW, halfH, s.near(), s.far()},
                    new float[]{0f, halfH, s.occlusionRadius(), 0f}, new float[]{0f, 1f, 0f, 0f}, aoB.texture(), depth.texture(), null, null);
        }
        // And the picture with the glow and the occlusion in.
        GpuTexture picture = source;
        float[] shadow = s.shadowTint(), highlight = s.highlightTint();
        fullScreen(output, composite, uniforms[pass++],
                new float[]{1f / output.width(), 1f / output.height(), glow ? s.bloom() : 0f, s.toneMap()},
                new float[]{s.saturation(), s.contrast(), s.chromaticAberration(), s.vignette()},
                new float[]{shadow[0], shadow[1], shadow[2], occluded ? s.occlusion() : 0f},
                picture, glow ? half1.texture() : picture, glow ? quarter1.texture() : picture, occluded ? aoA.texture() : picture,
                new float[]{highlight[0], highlight[1], highlight[2], 0f});
        return traced;
    }

    /**
     * Fills {@code into} with the occlusion worked out by rays through {@code rays}. False, with nothing drawn, where the
     * shader does not compile on this GPU (reported once), and then the occlusion is read from the depth picture.
     */
    private boolean traceOcclusion(RenderTarget into, Settings s, float ySign, RayScene rays, Camera camera, int rayFrame) {
        if (rayOcclusionBroken) return false;
        if (rayOcclusion == null) {
            try {
                rayOcclusion = device.createPipeline(new PipelineDesc(VERTEX, OCCLUSION_RAYS, VertexLayout.of(VertexFormat.FLOAT2), false, false, false));
            } catch (RuntimeException e) {
                rayOcclusionBroken = true;
                log.error("The ray traced occlusion shader does not compile on this GPU; occlusion is read from the depth picture instead.", e);
                return false;
            }
            rayOcclusionBlock = device.createDynamicBuffer(BufferType.UNIFORM, 96);
        }
        Vec3 eye = camera.getPosition();
        Vec3 forward = camera.getTarget().sub(eye).normalized();
        Vec3 right = forward.cross(camera.getUp()).normalized();
        Vec3 up = right.cross(forward);
        ByteBuffer block = MemoryUtil.memAlloc(96).order(ByteOrder.nativeOrder());
        try {
            block.putFloat(1f / depth.width()).putFloat(1f / depth.height()).putFloat(s.tanHalfFovX()).putFloat(s.tanHalfFovY());
            block.putFloat(s.near()).putFloat(s.far()).putFloat(s.occlusionRadius()).putFloat(ySign);
            block.putFloat(eye.x()).putFloat(eye.y()).putFloat(eye.z()).putFloat(rayFrame);
            block.putFloat(right.x()).putFloat(right.y()).putFloat(right.z()).putFloat(0f);
            block.putFloat(up.x()).putFloat(up.y()).putFloat(up.z()).putFloat(0f);
            block.putFloat(forward.x()).putFloat(forward.y()).putFloat(forward.z()).putFloat(0f);
            block.flip();
            rayOcclusionBlock.update(block);
        } finally {
            MemoryUtil.memFree(block);
        }
        try (RenderPass pass = device.beginPass(into, 0f, 0f, 0f, 1f)) {
            pass.bindPipeline(rayOcclusion);
            pass.bindUniformBuffer(0, rayOcclusionBlock);
            pass.bindTexture(1, depth.texture());
            pass.bindAccelerationStructure(2, rays);
            pass.bindVertexBuffer(0, triangle);
            pass.draw(3);
        }
        return true;
    }

    /**
     * Whether the passes that filter the indirect light compile on this GPU (made on the first ask, and reported once if not).
     */
    boolean giAvailable() {
        if (giBroken) return false;
        if (giTemporal == null) {
            try {
                VertexLayout layout = VertexLayout.of(VertexFormat.FLOAT2);
                giTemporal = device.createPipeline(new PipelineDesc(VERTEX, GI_TEMPORAL, layout, false, false, false));
                giSpatial = device.createPipeline(new PipelineDesc(VERTEX, GI_SPATIAL, layout, false, false, false));
                giCounting = device.createPipeline(new PipelineDesc(VERTEX, GI_COUNT, layout, false, false, false));
            } catch (RuntimeException e) {
                giBroken = true;
                if (giTemporal != null) giTemporal.close();
                if (giSpatial != null) giSpatial.close();
                giTemporal = giSpatial = giCounting = null;
                log.error("The indirect light filter does not compile on this GPU; global illumination is off.", e);
                return false;
            }
            for (int i = 0; i < giBlocks.length; i++) giBlocks[i] = device.createDynamicBuffer(BufferType.UNIFORM, GI_BLOCK_BYTES);
        }
        return true;
    }

    /**
     * The target the rays' light is drawn into, at the size of the scene's target: colour in half floats, and a depth buffer for
     * the surfaces to be drawn with. Call {@link #sceneTarget} first.
     */
    RenderTarget giTarget() {
        if (giRaw == null || giWidth != width || giHeight != height) {
            closeGi();
            giRaw = device.createSampledTarget(width, height, true, false, true, 1);
            giFilter = device.createSampledTarget(width, height, false, false, true, 1);
            giHistory[0] = device.createSampledTarget(width, height, false, false, true, 1);
            giHistory[1] = device.createSampledTarget(width, height, false, false, true, 1);
            giCount[0] = device.createSampledTarget(width, height, false, false, true, 1);
            giCount[1] = device.createSampledTarget(width, height, false, false, true, 1);
            giWidth = width;
            giHeight = height;
            giHistoryValid = false;
        }
        return giRaw;
    }

    /**
     * Cleans up the light the rays found (in {@link #giTarget()}): blends it with last frame's, found where each pixel was, then
     * smooths it three times without crossing an edge. Needs {@link #depthTarget()} drawn this frame. The picture it returns holds
     * the light in colour and the depth along the view in alpha.
     *
     * @param resets how many times the scene has asked for its history to be dropped; a change starts the average again
     * @param path   whether the rays are whole paths: the picture is noisier, so the history is longer and the smoothing also
     *               weighs the pixels it blends by how much their brightness differs
     */
    GpuTexture denoiseGi(Camera camera, Settings s, float ySign, int resets, boolean path) {
        if (resets != giSeenResets) {
            giSeenResets = resets;
            giHistoryValid = false;
        }
        Vec3 eye = camera.getPosition();
        Vec3 forward = camera.getTarget().sub(eye).normalized();
        Vec3 right = forward.cross(camera.getUp()).normalized();
        Vec3 up = right.cross(forward);
        RenderTarget out = giHistory[giCurrent], before = giHistory[1 - giCurrent];
        RenderTarget counts = giCount[giCurrent], countsBefore = giCount[1 - giCurrent];
        // With no history the shaders do not read it, but it still has to be bound to something that has been drawn into.
        GpuTexture past = giHistoryValid ? before.texture() : giRaw.texture();
        GpuTexture pastCounts = giHistoryValid ? countsBefore.texture() : giRaw.texture();
        float edges = path ? 1f : 0f;
        giPass(counts, giCounting, giBlocks[4], 0f, 0f, s, ySign, eye, right, up, forward, depth.texture(), giRaw.texture(), past, pastCounts);
        giPass(out, giTemporal, giBlocks[0], path ? PATH_BLEND : GI_BLEND, 0f, s, ySign, eye, right, up, forward, depth.texture(), giRaw.texture(),
                past, counts.texture());
        giPass(giFilter, giSpatial, giBlocks[1], 1f, edges, s, ySign, eye, right, up, forward, depth.texture(), out.texture(), null, null);
        giPass(giRaw, giSpatial, giBlocks[2], 2f, edges, s, ySign, eye, right, up, forward, depth.texture(), giFilter.texture(), null, null);
        giPass(giFilter, giSpatial, giBlocks[3], 4f, edges, s, ySign, eye, right, up, forward, depth.texture(), giRaw.texture(), null, null);
        float[] now = {eye.x(), eye.y(), eye.z(), right.x(), right.y(), right.z(), up.x(), up.y(), up.z(), forward.x(), forward.y(), forward.z(),
                s.tanHalfFovX(), s.tanHalfFovY()};
        System.arraycopy(now, 0, giLastCamera, 0, now.length);
        giHistoryValid = true;
        giCurrent = 1 - giCurrent;
        return giFilter.texture();
    }

    private void giPass(RenderTarget into, Pipeline pipeline, GpuBuffer block, float step, float edges, Settings s, float ySign,
                        Vec3 eye, Vec3 right, Vec3 up, Vec3 forward, GpuTexture packedDepth, GpuTexture noisy, GpuTexture history,
                        GpuTexture counts) {
        ByteBuffer data = MemoryUtil.memAlloc(GI_BLOCK_BYTES).order(ByteOrder.nativeOrder());
        try {
            data.putFloat(1f / into.width()).putFloat(1f / into.height()).putFloat(s.tanHalfFovX()).putFloat(s.tanHalfFovY());
            data.putFloat(s.near()).putFloat(s.far()).putFloat(ySign).putFloat(step);
            for (Vec3 v : new Vec3[]{eye, right, up, forward}) data.putFloat(v.x()).putFloat(v.y()).putFloat(v.z()).putFloat(0f);
            for (int i = 0; i < 4; i++) data.putFloat(giLastCamera[i * 3]).putFloat(giLastCamera[i * 3 + 1]).putFloat(giLastCamera[i * 3 + 2]).putFloat(0f);
            data.putFloat(giLastCamera[12]).putFloat(giLastCamera[13]).putFloat(giHistoryValid ? 1f : 0f).putFloat(edges);
            data.flip();
            block.update(data);
        } finally {
            MemoryUtil.memFree(data);
        }
        try (RenderPass pass = device.beginPass(into, 0f, 0f, 0f, 0f)) {
            pass.bindPipeline(pipeline);
            pass.bindUniformBuffer(0, block);
            pass.bindTexture(1, packedDepth);
            pass.bindTexture(2, noisy);
            if (history != null) pass.bindTexture(3, history);
            if (counts != null) pass.bindTexture(4, counts);
            pass.bindVertexBuffer(0, triangle);
            pass.draw(3);
        }
    }

    /** A frame went by without indirect light, so what the history holds no longer follows the camera. */
    void dropGiHistory() {
        giHistoryValid = false;
    }

    private void closeGi() {
        for (RenderTarget t : new RenderTarget[]{giRaw, giFilter, giHistory[0], giHistory[1], giCount[0], giCount[1]}) if (t != null) t.close();
        giRaw = giFilter = giHistory[0] = giHistory[1] = giCount[0] = giCount[1] = null;
        giWidth = giHeight = 0;
        giHistoryValid = false;
    }

    /**
     * Runs the upscaler on the scene's picture and returns the picture it made.
     */
    private GpuTexture upscale(Frame frame, boolean motion, Camera camera, float delta, boolean bottomLeft, Guides guides) {
        boolean storage = upscaler.writesStorageImage();
        if (upscaled[0] == null || upscaledWidth != displayWidth || upscaledHeight != displayHeight || upscaledStorage != storage) {
            for (int i = 0; i < 2; i++) {
                if (upscaled[i] != null) upscaled[i].close();
                upscaled[i] = storage ? device.createStorageTarget(displayWidth, displayHeight)
                        : device.createSampledTarget(displayWidth, displayHeight, false, true, true, 1);
            }
            upscaledWidth = displayWidth;
            upscaledHeight = displayHeight;
            upscaledStorage = storage;
        }
        RenderTarget output = upscaled[current], before = upscaled[1 - current];
        boolean fresh = frame.reset();
        Vec3 forward = camera.getTarget().sub(camera.getPosition()).normalized();
        Vec3 right = forward.cross(camera.getUp()).normalized();
        upscaler.upscale(new UpscaleInputs(device, scene.texture(), motion ? velocity.depthTexture() : null, motion ? velocity.texture() : null,
                output, fresh ? null : before, width, height, displayWidth, displayHeight, frame.jitterX(), frame.jitterY(),
                camera.getNear(), camera.getFar(), (float) Math.toRadians(camera.getFovYDegrees()), camera.getPosition(), right,
                right.cross(forward), forward, delta, temporal.frame, fresh, bottomLeft,
                guides == null ? null : guides.diffuse(), guides == null ? null : guides.specular(), guides == null ? null : guides.normal(),
                guides == null ? null : guides.worldToView()));
        current = 1 - current;
        return output.texture();
    }

    private void fullScreen(RenderTarget into, Pipeline pipeline, GpuBuffer uniform, float[] a, float[] b, float[] c,
                            GpuTexture first, GpuTexture second, GpuTexture third, GpuTexture fourth) {
        fullScreen(into, pipeline, uniform, a, b, c, first, second, third, fourth, null);
    }

    private void fullScreen(RenderTarget into, Pipeline pipeline, GpuBuffer uniform, float[] a, float[] b, float[] c,
                            GpuTexture first, GpuTexture second, GpuTexture third, GpuTexture fourth, float[] d) {
        scratch.clear();
        for (float[] block : new float[][]{a, b, c, d}) {
            for (int i = 0; i < 4; i++) scratch.putFloat(block == null ? 0f : block[i]);
        }
        scratch.flip();
        uniform.update(scratch);
        try (RenderPass pass = device.beginPass(into, 0f, 0f, 0f, 1f)) {
            pass.bindPipeline(pipeline);
            pass.bindUniformBuffer(0, uniform);
            pass.bindTexture(1, first);
            if (second != null) pass.bindTexture(2, second);
            if (third != null) pass.bindTexture(3, third);
            if (fourth != null) pass.bindTexture(4, fourth);
            pass.bindVertexBuffer(0, triangle);
            pass.draw(3);
        }
    }

    private void closeTargets() {
        for (RenderTarget t : new RenderTarget[]{scene, half1, half2, quarter1, quarter2, depth, aoA, aoB, velocity, upscaled[0], upscaled[1]}) if (t != null) t.close();
        scene = half1 = half2 = quarter1 = quarter2 = depth = aoA = aoB = velocity = null;
        upscaled[0] = upscaled[1] = null;
        closeGi();
        closeGuides();
        depthWidth = depthHeight = velocityWidth = velocityHeight = upscaledWidth = upscaledHeight = 0;
    }

    void close() {
        closeTargets();
        if (upscaler != null) upscaler.close();
        upscaler = null;
        bright.close();
        blur.close();
        composite.close();
        occlusion.close();
        occlusionBlur.close();
        if (rayOcclusion != null) rayOcclusion.close();
        if (rayOcclusionBlock != null) rayOcclusionBlock.close();
        if (giTemporal != null) giTemporal.close();
        if (giSpatial != null) giSpatial.close();
        if (giCounting != null) giCounting.close();
        for (GpuBuffer b : giBlocks) if (b != null) b.close();
        for (GpuBuffer b : uniforms) b.close();
        MemoryUtil.memFree(scratch);
    }
}
