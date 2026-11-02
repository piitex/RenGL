package me.piitex.engine.render3d;

/**
 * GLSL that traces rays in a fragment shader without ray queries, through the storage buffers of a software ray scene (see
 * {@link GpuDevice#softwareRayTracing()}): the shader side of the layout {@code VkSoftRay} writes. The 3D layer puts it where it
 * would put {@code rayQueryEXT}, so on a Mac, or on a GPU without ray queries, shadows, reflections, occlusion and bounced
 * light are still rays.
 * <p>
 * Everything is the walk a hardware ray query makes, done by hand: a hierarchy over the instances, where a leaf's instance takes
 * the ray into its geometry's own space (a matrix applied to the origin and the direction, the direction left unnormalized so a
 * distance is the same on both sides), a hierarchy over the triangles, and a triangle test. Both hierarchies are walked nearer
 * child first with a stack of {@value #STACK} places, which the builder's depth limit keeps from overflowing.
 */
public final class SoftRayShaders {
    private SoftRayShaders() {
    }

    /** Places on the stack of a walk: the deepest a hierarchy goes. */
    public static final int STACK = 40;

    /**
     * The two storage buffers and the function that walks them. {@code softTrace} finds the nearest triangle along a ray between
     * {@code tmin} and {@code tmax} that belongs to an instance whose mask shares a bit with {@code mask}, or with {@code anyHit}
     * the first one it comes to. A hit fills {@code SoftHit}: the distance, the instance's place in the scene, the triangle's
     * place in its geometry (the order the leaves hold them, which the indices of the geometry follow), and the barycentric
     * weights of the second and third corner.
     *
     * @param sceneBinding    the binding of the scene's buffer ({@code RenderPass#bindAccelerationStructure})
     * @param geometryBinding the binding of the buffer of every geometry ({@code RenderPass#bindRayGeometry})
     */
    public static String declarations(int sceneBinding, int geometryBinding) {
        return """
                layout(std430, set = 0, binding = %SCENE%) readonly buffer SoftScene { uint sceneWords[]; };
                layout(std430, set = 0, binding = %GEOMETRY%) readonly buffer SoftGeometry { uint geometryWords[]; };

                float sceneFloat(uint at) { return uintBitsToFloat(sceneWords[at]); }
                float geometryFloat(uint at) { return uintBitsToFloat(geometryWords[at]); }

                struct SoftHit {
                    float t;
                    uint instance;
                    uint triangle;
                    vec2 bary;
                };

                // One over the direction, with a component that is nearly zero held off zero: the slab test would otherwise
                // multiply an infinity by the zero of a ray that runs along a face of the box.
                vec3 softInverse(vec3 d) {
                    return 1.0 / mix(d, vec3(1e-9), lessThan(abs(d), vec3(1e-9)));
                }

                // Whether the ray crosses the box between tmin and tmax. The far side is widened by a hair so a ray that grazes
                // an edge is not lost to rounding.
                bool softSlab(vec3 lo, vec3 hi, vec3 o, vec3 inv, float tmin, float tmax) {
                    vec3 t0 = (lo - o) * inv, t1 = (hi - o) * inv;
                    vec3 entry = min(t0, t1), leave = max(t0, t1);
                    float enter = max(max(entry.x, entry.y), max(entry.z, tmin));
                    float exit = min(min(leave.x, leave.y), min(leave.z, tmax)) * 1.0000005;
                    return enter <= exit;
                }

                // The triangles of the geometry that starts at base (a place in geometryWords), by a ray in the geometry's own
                // space. A hit nearer than best replaces it, and the triangle and weights are set.
                bool softGeometryTrace(uint base, vec3 o, vec3 d, vec3 inv, float tmin, inout float best, bool anyHit,
                                       inout uint triangle, inout vec2 bary) {
                    uint nodes = base + 4u;
                    uint corners = base + geometryWords[base];
                    uint stack[%STACK%];
                    int sp = 0;
                    uint node = 0u;
                    bool found = false;
                    for (;;) {
                        uint at = nodes + node * 8u;
                        vec3 lo = vec3(geometryFloat(at), geometryFloat(at + 1u), geometryFloat(at + 2u));
                        vec3 hi = vec3(geometryFloat(at + 4u), geometryFloat(at + 5u), geometryFloat(at + 6u));
                        if (softSlab(lo, hi, o, inv, tmin, best)) {
                            uint tail = geometryWords[at + 7u];
                            if ((tail & 0x10000u) != 0u) {
                                uint right = geometryWords[at + 3u];
                                bool rightFirst = d[tail & 3u] < 0.0;
                                stack[sp++] = rightFirst ? node + 1u : right;
                                node = rightFirst ? right : node + 1u;
                                continue;
                            }
                            uint first = geometryWords[at + 3u];
                            for (uint k = 0u; k < tail; k++) {
                                uint c = corners + (first + k) * 9u;
                                vec3 p0 = vec3(geometryFloat(c), geometryFloat(c + 1u), geometryFloat(c + 2u));
                                vec3 e1 = vec3(geometryFloat(c + 3u), geometryFloat(c + 4u), geometryFloat(c + 5u)) - p0;
                                vec3 e2 = vec3(geometryFloat(c + 6u), geometryFloat(c + 7u), geometryFloat(c + 8u)) - p0;
                                vec3 pv = cross(d, e2);
                                float det = dot(e1, pv);
                                if (abs(det) < 1e-20) continue;
                                float idet = 1.0 / det;
                                vec3 tv = o - p0;
                                float u = dot(tv, pv) * idet;
                                if (u < -1e-6 || u > 1.000001) continue;
                                vec3 qv = cross(tv, e1);
                                float v = dot(d, qv) * idet;
                                if (v < -1e-6 || u + v > 1.000001) continue;
                                float t = dot(e2, qv) * idet;
                                if (t >= tmin && t < best) {
                                    best = t;
                                    triangle = first + k;
                                    bary = vec2(u, v);
                                    found = true;
                                    if (anyHit) return true;
                                }
                            }
                        }
                        if (sp == 0) break;
                        node = stack[--sp];
                    }
                    return found;
                }

                bool softTrace(vec3 o, vec3 d, float tmin, float tmax, uint mask, bool anyHit, out SoftHit hit) {
                    hit.t = tmax;
                    hit.instance = 0u;
                    hit.triangle = 0u;
                    hit.bary = vec2(0.0);
                    if (sceneWords[0] == 0u) return false;
                    uint nodes = sceneWords[1];
                    uint instances = sceneWords[2];
                    vec3 inv = softInverse(d);
                    float best = tmax;
                    bool found = false;
                    uint stack[%STACK%];
                    int sp = 0;
                    uint node = 0u;
                    for (;;) {
                        uint at = nodes + node * 8u;
                        vec3 lo = vec3(sceneFloat(at), sceneFloat(at + 1u), sceneFloat(at + 2u));
                        vec3 hi = vec3(sceneFloat(at + 4u), sceneFloat(at + 5u), sceneFloat(at + 6u));
                        if (softSlab(lo, hi, o, inv, tmin, best)) {
                            uint tail = sceneWords[at + 7u];
                            if ((tail & 0x10000u) != 0u) {
                                uint right = sceneWords[at + 3u];
                                bool rightFirst = d[tail & 3u] < 0.0;
                                stack[sp++] = rightFirst ? node + 1u : right;
                                node = rightFirst ? right : node + 1u;
                                continue;
                            }
                            uint first = sceneWords[at + 3u];
                            for (uint k = 0u; k < tail; k++) {
                                uint it = instances + (first + k) * 16u;
                                if ((sceneWords[it + 13u] & mask) == 0u) continue;
                                vec4 r0 = vec4(sceneFloat(it), sceneFloat(it + 1u), sceneFloat(it + 2u), sceneFloat(it + 3u));
                                vec4 r1 = vec4(sceneFloat(it + 4u), sceneFloat(it + 5u), sceneFloat(it + 6u), sceneFloat(it + 7u));
                                vec4 r2 = vec4(sceneFloat(it + 8u), sceneFloat(it + 9u), sceneFloat(it + 10u), sceneFloat(it + 11u));
                                vec3 lo2 = vec3(dot(r0.xyz, o) + r0.w, dot(r1.xyz, o) + r1.w, dot(r2.xyz, o) + r2.w);
                                vec3 ld = vec3(dot(r0.xyz, d), dot(r1.xyz, d), dot(r2.xyz, d));
                                uint triangle = 0u;
                                vec2 bary = vec2(0.0);
                                if (softGeometryTrace(sceneWords[it + 12u], lo2, ld, softInverse(ld), tmin, best, anyHit, triangle, bary)) {
                                    hit.t = best;
                                    hit.instance = first + k;
                                    hit.triangle = triangle;
                                    hit.bary = bary;
                                    found = true;
                                    if (anyHit) return true;
                                }
                            }
                        }
                        if (sp == 0) break;
                        node = stack[--sp];
                    }
                    return found;
                }
                """.replace("%SCENE%", "" + sceneBinding).replace("%GEOMETRY%", "" + geometryBinding).replace("%STACK%", "" + STACK);
    }

    /**
     * What the reflection shader reads of an instance in place of the table a buffer reference reaches: the same blocks and
     * textures, and a function for each of the record's vec4s.
     */
    public static String reflectionDeclarations(int blockBinding, int texturesBinding, int textureCount) {
        return """
                layout(set = 0, binding = %BLOCK%) uniform RayBlock {
                    uvec2 unusedTable;
                    vec4 rayA; // x: which frame this is (the pattern the rays are scattered in turns with it), y: how far a reflection ray goes
                    vec4 rayB; // x: the roughness up to which a surface reflects by ray
                };
                layout(set = 0, binding = %TEXTURES%) uniform sampler2D rayTextures[%COUNT%];

                vec4 recordVec(int id, uint field) {
                    uint at = sceneWords[3] + uint(id) * 24u + 4u + field * 4u;
                    return vec4(sceneFloat(at), sceneFloat(at + 1u), sceneFloat(at + 2u), sceneFloat(at + 3u));
                }
                vec4 rayTint(int id) { return recordVec(id, 0u); }
                vec4 rayParams(int id) { return recordVec(id, 1u); }
                vec4 rayEmissive(int id) { return recordVec(id, 2u); }
                vec4 rayUvA(int id) { return recordVec(id, 3u); }
                vec4 rayUvB(int id) { return recordVec(id, 4u); }
                """.replace("%BLOCK%", "" + blockBinding).replace("%TEXTURES%", "" + texturesBinding).replace("%COUNT%", "" + textureCount);
    }

    /**
     * {@code traceScene} for the reflection shader: the nearest thing a ray hits, with the position, the normal turned to face the
     * ray, the texture coordinates and the instance. A hit is read from the instance's geometry (three vertices of eight
     * floats: position, normal, texture coordinates), the way the table's buffer references read it.
     */
    public static final String TRACE_SCENE = """
            bool traceScene(vec3 origin, vec3 dir, float tmin, float tmax, out RayHit hit) {
                SoftHit h;
                if (!softTrace(origin, dir, tmin, tmax, 2u, false, h)) return false;
                hit.id = int(h.instance);
                hit.t = h.t;
                hit.pos = origin + dir * hit.t;
                uint base = sceneWords[sceneWords[3] + h.instance * 24u];
                uint indices = base + geometryWords[base + 1u] + h.triangle * 3u;
                uint vertices = base + geometryWords[base + 2u];
                uint stride = geometryWords[base + 3u];
                uint o0 = vertices + geometryWords[indices] * stride;
                uint o1 = vertices + geometryWords[indices + 1u] * stride;
                uint o2 = vertices + geometryWords[indices + 2u] * stride;
                vec3 w = vec3(1.0 - h.bary.x - h.bary.y, h.bary.x, h.bary.y);
                vec3 n0 = vec3(geometryFloat(o0 + 3u), geometryFloat(o0 + 4u), geometryFloat(o0 + 5u));
                vec3 n1 = vec3(geometryFloat(o1 + 3u), geometryFloat(o1 + 4u), geometryFloat(o1 + 5u));
                vec3 n2 = vec3(geometryFloat(o2 + 3u), geometryFloat(o2 + 4u), geometryFloat(o2 + 5u));
                hit.uv = vec2(geometryFloat(o0 + 6u), geometryFloat(o0 + 7u)) * w.x + vec2(geometryFloat(o1 + 6u), geometryFloat(o1 + 7u)) * w.y
                       + vec2(geometryFloat(o2 + 6u), geometryFloat(o2 + 7u)) * w.z;
                vec3 local = n0 * w.x + n1 * w.y + n2 * w.z;
                // The matrix in the scene takes the world into the geometry, so it carries a normal back out transposed: that
                // keeps the normal square to the surface under stretching, like the vertex shader.
                uint it = sceneWords[2] + h.instance * 16u;
                vec3 r0 = vec3(sceneFloat(it), sceneFloat(it + 1u), sceneFloat(it + 2u));
                vec3 r1 = vec3(sceneFloat(it + 4u), sceneFloat(it + 5u), sceneFloat(it + 6u));
                vec3 r2 = vec3(sceneFloat(it + 8u), sceneFloat(it + 9u), sceneFloat(it + 10u));
                vec3 world = r0 * local.x + r1 * local.y + r2 * local.z;
                hit.n = dot(world, world) > 1e-20 ? normalize(world) : -dir;
                // Both sides of a surface are shaded as the side the ray came from.
                if (dot(hit.n, dir) > 0.0) hit.n = -hit.n;
                return true;
            }
            """;

    /** {@code rayShadow}: 1 when nothing solid is between the point and the light, as the ray query version says. */
    public static final String SHADOW = """
            float rayShadow(int i, vec3 worldPos, vec3 n, vec3 l) {
                float reach = lightVec[i].w < 0.5 ? 100000.0 : max(length(lightVec[i].xyz - worldPos) - 0.02, 0.0);
                float eps = 0.005 + 0.0005 * length(worldPos - cameraPos.xyz);
                SoftHit h;
                return softTrace(worldPos + n * eps, l, eps, reach, 1u, true, h) ? 0.0 : 1.0;
            }
            """;
}
