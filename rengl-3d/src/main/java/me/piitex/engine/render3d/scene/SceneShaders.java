package me.piitex.engine.render3d.scene;

import java.util.List;

/**
 * The scene renderer's GLSL. One vertex and one fragment shader cover every material: classic or physically based
 * shading and unlit surfaces are chosen per draw from the instance data, so there is one program per kind of geometry
 * (static or skinned) and render state, not one per material.
 * <p>
 * Binding numbers are shared by every program: 0 is the frame block, 1 the joint matrices (skinned programs), 2 to 6
 * the material's textures (base colour, normal, metallic-roughness, occlusion, emissive), 7 the shadow map and 8 the
 * environment (the skybox, in programs that light from it). Programs that blend a mesh's blend shapes on the GPU also
 * read 9, the shapes' offsets as a texture, and 10, the shapes in use and their weights.
 */
final class SceneShaders {
    private SceneShaders() {
    }

    /**
     * Floats per instance in the instance stream: nine vec4s (the model matrix, the tint, the material's parameters, and the
     * texture transform). The normal matrix is worked out from the model matrix in the vertex shader.
     */
    static final int INSTANCE_FLOATS = 36;

    /**
     * The shadow views one frame can hold: up to {@link Scene3D#MAX_SHADOW_LIGHTS} lights of six (a point light's sides).
     */
    static final int MAX_SHADOW_VIEWS = Scene3D.MAX_SHADOW_LIGHTS * 6;
    /**
     * Bytes of one shadow view in the frame block: a matrix, the tile's place in the atlas, and the offsets.
     */
    static final int SHADOW_VIEW_BYTES = 96;

    static final int FLAG_NORMAL_MAP = 1;
    static final int FLAG_RECEIVE_SHADOW = 2;
    /**
     * The material's texture slots that read the second set of texture coordinates instead of the first, one bit each
     * from this one up: base colour, normal, metallic-roughness, occlusion, emissive.
     */
    static final int FLAG_UV1_SHIFT = 2;

    static final int MODE_CLASSIC = 0;
    static final int MODE_PBR = 1;
    static final int MODE_UNLIT = 2;

    /**
     * Per-vertex attributes at locations 0 to 2; instance attributes follow at 3 to 11, a second set of texture coordinates
     * at 12, and skin attributes at 14 and 15.
     */
    private static final String INPUTS = """
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUv;
            layout(location = 3) in vec4 iModel0;
            layout(location = 4) in vec4 iModel1;
            layout(location = 5) in vec4 iModel2;
            layout(location = 6) in vec4 iModel3;
            layout(location = 7) in vec4 iTint;
            layout(location = 8) in vec4 iParams;
            layout(location = 9) in vec4 iExtra;
            layout(location = 10) in vec4 iExtra2;
            layout(location = 11) in vec4 iUv;
            """;

    private static final String UV2_INPUT = """
            layout(location = 12) in vec2 aUv2;
            """;

    /**
     * The material's texture transform: scale, then turn (its cosine and sine are in the record), then offset.
     */
    private static final String UV_TRANSFORM = """
            vec2 transformUv(vec2 uv) {
                vec2 s = uv * iUv.zw;
                return vec2(iExtra2.z * s.x + iExtra2.w * s.y, -iExtra2.w * s.x + iExtra2.z * s.y) + iUv.xy;
            }
            """;

    private static final String SKIN_INPUTS = """
            layout(location = 14) in vec4 aJoints;
            layout(location = 15) in vec4 aWeights;
            layout(set = 0, binding = 1) uniform Joints { mat4 joints[""" + Skin.MAX_JOINTS + """
            ]; };
            """;

    /**
     * Most blend shapes one draw can mix on the GPU; a node with more shapes switched on is blended on the CPU.
     */
    static final int MAX_ACTIVE_MORPHS = 16;
    /**
     * Bytes of the morph block: one vec4 of facts, then a vec4 per shape in use.
     */
    static final int MORPH_BLOCK_SIZE = 16 * (1 + MAX_ACTIVE_MORPHS);

    /**
     * The blend shapes' offsets, two texels (position, then normal change) for each shape and vertex, a shape's vertices
     * one after the other, laid out in rows of {@code morphInfo.z} texels. {@code morphInfo} is the number of shapes in
     * use, the mesh's vertex count, the row width, and 1 when a shape changes normals; each of {@code morphSlots} is a
     * shape's number and its weight.
     */
    private static final String MORPH_INPUTS = """
            layout(set = 0, binding = 9) uniform sampler2D morphTexture;
            layout(set = 0, binding = 10) uniform Morph { vec4 morphInfo; vec4 morphSlots[""" + MAX_ACTIVE_MORPHS + """
            ]; };
            """;

    /**
     * Declares {@code mPos} and {@code mNormal}, the vertex as the mesh has it with its blend shapes mixed in when the
     * program has them. Skinning and the model matrix act on these.
     */
    private static String morphed(boolean morph) {
        if (!morph) return """
                vec3 mPos = aPos;
                vec3 mNormal = aNormal;
                """;
        return """
                vec3 mPos = aPos;
                vec3 mNormal = aNormal;
                int rowWidth = int(morphInfo.z);
                for (int k = 0; k < int(morphInfo.x); k++) {
                    int texel = (int(morphSlots[k].x) * int(morphInfo.y) + gl_VertexIndex) * 2;
                    ivec2 at = ivec2(texel % rowWidth, texel / rowWidth);
                    mPos += morphSlots[k].y * texelFetch(morphTexture, at, 0).xyz;
                    if (morphInfo.w > 0.5) mNormal += morphSlots[k].y * texelFetch(morphTexture, at + ivec2(1, 0), 0).xyz;
                }
                if (morphInfo.w > 0.5 && dot(mNormal, mNormal) > 1e-16) mNormal = normalize(mNormal);
                """;
    }

    static final String FRAME_BLOCK = """
            struct ShadowView {
                mat4 viewProj;
                vec4 rect;
                vec4 params;
            };
            layout(set = 0, binding = 0) uniform Frame {
                mat4 viewProj;
                vec4 cameraPos;
                vec4 cameraFwd;
                vec4 ambient;
                vec4 params;
                vec4 shadowGlobal;
                vec4 lightVec[""" + Scene3D.MAX_LIGHTS + """
            ];
                vec4 lightColor[""" + Scene3D.MAX_LIGHTS + """
            ];
                vec4 lightDir[""" + Scene3D.MAX_LIGHTS + """
            ];
                vec4 lightCone[""" + Scene3D.MAX_LIGHTS + """
            ];
                vec4 lightShadow[""" + Scene3D.MAX_LIGHTS + """
            ];
                vec4 lightSplits[""" + Scene3D.MAX_LIGHTS + """
            ];
                ShadowView views[""" + MAX_SHADOW_VIEWS + """
            ];
                vec4 fog;
                vec4 fogParams;
                vec4 envParams;
                vec4 envSh[9];
                vec4 envTop;
                vec4 envHorizon;
                vec4 envBottom;
            };
            """;

    /**
     * The scene's own vertex shader. {@code skinned} adds the joint blend, which replaces the instance's model matrix.
     */
    static String vertex(boolean skinned, boolean uv2, boolean morph) {
        return "#version 450\n" + INPUTS + (uv2 ? UV2_INPUT : "") + (skinned ? SKIN_INPUTS : "") + (morph ? MORPH_INPUTS : "") + UV_TRANSFORM + FRAME_BLOCK + """
                layout(location = 0) out vec3 vWorldPos;
                layout(location = 1) out vec3 vNormal;
                layout(location = 2) out vec2 vUv;
                layout(location = 3) flat out vec4 vTint;
                layout(location = 4) flat out vec4 vParams;
                layout(location = 5) flat out vec4 vExtra;
                layout(location = 6) flat out vec4 vExtra2;
                layout(location = 7) out vec2 vUv1;
                void main() {
                """ + morphed(morph) + (skinned ? """
                    mat4 skin = aWeights.x * joints[int(aJoints.x)] + aWeights.y * joints[int(aJoints.y)]
                              + aWeights.z * joints[int(aJoints.z)] + aWeights.w * joints[int(aJoints.w)];
                    vec4 world = skin * vec4(mPos, 1.0);
                    vNormal = mat3(skin) * mNormal;
                """ : """
                    mat4 model = mat4(iModel0, iModel1, iModel2, iModel3);
                    vec4 world = model * vec4(mPos, 1.0);
                    // The inverse transpose keeps normals square to the surface under stretching.
                    mat3 m3 = mat3(model);
                    vNormal = (abs(determinant(m3)) < 1e-20 ? m3 : transpose(inverse(m3))) * mNormal;
                """) + """
                    vWorldPos = world.xyz;
                    vUv = transformUv(aUv);
                    vUv1 = transformUv(""" + (uv2 ? "aUv2" : "aUv") + """
                );
                    vTint = iTint;
                    vParams = iParams;
                    vExtra = iExtra;
                    vExtra2 = iExtra2;
                    gl_Position = viewProj * world;
                }
                """;
    }

    /**
     * Reads the light grid: the cell a point is in, the lights listed for it, and one light's parameters. Everything is read
     * with {@code texelFetch} from RGBA8 textures; a float is its four bytes, put back together. See {@link LightClusters}.
     */
    private static final String CLUSTER_FUNCTIONS = """
            layout(set = 0, binding = 11) uniform sampler2D clusterLights;
            layout(set = 0, binding = 12) uniform sampler2D clusterHeaders;
            layout(set = 0, binding = 13) uniform sampler2D clusterIndices;

            float clusterFloat(int row, int texel) {
                uvec4 u = uvec4(texelFetch(clusterLights, ivec2(texel, row), 0) * 255.0 + 0.5);
                return uintBitsToFloat(u.x | (u.y << 8) | (u.z << 16) | (u.w << 24));
            }

            // The cell the point is in: where its offset into the index list starts and how many lights it lists.
            bool clusterCell(vec3 worldPos, out int offset, out int count) {
                vec4 clip = viewProj * vec4(worldPos, 1.0);
                vec2 ndc = clip.xy / clip.w;
                float viewZ = dot(worldPos - cameraPos.xyz, cameraFwd.xyz);
                int tx = clamp(int(floor((ndc.x * 0.5 + 0.5) * %TILES_X%.0)), 0, %TILES_X% - 1);
                int ty = clamp(int(floor((ndc.y * 0.5 + 0.5) * %TILES_Y%.0)), 0, %TILES_Y% - 1);
                int sl = clamp(int(floor(log(max(viewZ, params.y) / params.y) * params.z)), 0, %SLICES% - 1);
                int cell = (sl * %TILES_Y% + ty) * %TILES_X% + tx;
                uvec4 h = uvec4(texelFetch(clusterHeaders, ivec2(cell % %HEADER_WIDTH%, cell / %HEADER_WIDTH%), 0) * 255.0 + 0.5);
                offset = int(h.x | (h.y << 8) | (h.z << 16));
                count = int(h.w);
                return count > 0;
            }

            int clusterIndex(int entry) {
                int texel = entry >> 1;
                uvec4 u = uvec4(texelFetch(clusterIndices, ivec2(texel % %INDEX_WIDTH%, texel / %INDEX_WIDTH%), 0) * 255.0 + 0.5);
                return (entry & 1) == 0 ? int(u.x | (u.y << 8)) : int(u.z | (u.w << 8));
            }

            // The same as lightAt, for a light in the grid: the direction to it, its colour, and how much of it arrives.
            float clusterLight(int row, vec3 worldPos, out vec3 l, out vec3 rgb) {
                vec3 pos = vec3(clusterFloat(row, 0), clusterFloat(row, 1), clusterFloat(row, 2));
                float range = clusterFloat(row, 3);
                uvec4 c = uvec4(texelFetch(clusterLights, ivec2(4, row), 0) * 255.0 + 0.5);
                rgb = vec3(c.xyz) / 255.0 * clusterFloat(row, 5);
                vec3 d = pos - worldPos;
                float dist = length(d);
                l = d / max(dist, 0.0001);
                float atten = falloff(int(c.w >> 1), dist, range);
                if (atten > 0.0 && (c.w & 1u) != 0u) {
                    vec3 dir = vec3(clusterFloat(row, 6), clusterFloat(row, 7), clusterFloat(row, 8));
                    float outerCos = clusterFloat(row, 9);
                    float innerCos = clusterFloat(row, 10);
                    atten *= clamp((dot(-l, dir) - outerCos) / max(innerCos - outerCos, 1e-4), 0.0, 1.0);
                }
                return atten;
            }
            """.replace("%TILES_X%", "" + LightClusters.TILES_X).replace("%TILES_Y%", "" + LightClusters.TILES_Y)
            .replace("%SLICES%", "" + LightClusters.SLICES).replace("%HEADER_WIDTH%", "" + LightClusters.HEADER_WIDTH)
            .replace("%INDEX_WIDTH%", "" + LightClusters.INDEX_WIDTH);

    private static final String CLUSTER_PBR = """
                        int clusterOffset, clusterCount;
                        if (clusterCell(vWorldPos, clusterOffset, clusterCount)) {
                            for (int j = 0; j < clusterCount; j++) {
                                vec3 l, lightRgb;
                                float atten = clusterLight(clusterIndex(clusterOffset + j), vWorldPos, l, lightRgb);
                                if (atten <= 0.0) continue;
                                float nl = max(dot(n, l), 0.0);
                                if (nl <= 0.0) continue;
                                vec3 h = normalize(l + v);
                                float nh = max(dot(n, h), 0.0);
                                float vh = max(dot(v, h), 0.0);
                                vec3 f = f0 + (1.0 - f0) * pow(1.0 - vh, 5.0);
                                float g = (nv / (nv * (1.0 - k) + k)) * (nl / (nl * (1.0 - k) + k));
                                vec3 spec = distributionGGX(nh, a2) * g * f / max(4.0 * nv * nl, 1e-4);
                                lit += ((vec3(1.0) - f) * diffuseColor + PI * spec) * lightRgb * atten * nl;
                            }
                        }
            """;

    private static final String CLUSTER_CLASSIC = """
                        int clusterOffset, clusterCount;
                        if (clusterCell(vWorldPos, clusterOffset, clusterCount)) {
                            for (int j = 0; j < clusterCount; j++) {
                                vec3 l, lightRgb;
                                float atten = clusterLight(clusterIndex(clusterOffset + j), vWorldPos, l, lightRgb);
                                if (atten <= 0.0) continue;
                                float ndl = max(dot(n, l), 0.0);
                                if (ndl <= 0.0) continue;
                                vec3 radiance = lightRgb * atten;
                                lit += albedo * radiance * ndl;
                                vec3 h = normalize(l + v);
                                lit += radiance * vParams.x * pow(max(dot(n, h), 0.0), vParams.y);
                            }
                        }
            """;

    static final int ENV_NONE = 0, ENV_GRADIENT = 1, ENV_CUBE = 2, ENV_EQUIRECT = 3, ENV_EQUIRECT_HDR = 4;

    /**
     * The scene's fragment shader. {@code env} says what lights the scene from the sky: nothing, or a gradient, cube or
     * equirectangular skybox, each its own program.
     */
    static String fragment(int env) {
        return fragment(env, List.of(), List.of());
    }

    /**
     * The scene's fragment shader holding the given custom shadow filters and light falloffs, which take ids 3 and up in
     * the order given (0 to 2 are the built-in ones). A body that doesn't compile fails the program, so the caller checks.
     */
    static String fragment(int env, List<CustomShadowFilter> filters, List<CustomLightFalloff> falloffs) {
        return fragment(env, filters, falloffs, false);
    }

    /**
     * The same, and with {@code clustered} the lights listed for the pixel's cell of the light grid ({@link LightClusters})
     * are shaded after the ones every pixel tests. The grid is three textures at bindings 11 to 13.
     */
    static String fragment(int env, List<CustomShadowFilter> filters, List<CustomLightFalloff> falloffs, boolean clustered) {
        StringBuilder filterFunctions = new StringBuilder(), filterCases = new StringBuilder();
        for (int i = 0; i < filters.size(); i++) {
            filterFunctions.append("float customFilter").append(i).append("(vec2 uv, float depth, vec2 texel, float softness) {\n")
                    .append(filters.get(i).glsl()).append("\n}\n");
            filterCases.append("if (id == ").append(3 + i).append(") return customFilter").append(i).append("(uv, depth, texel, softness);\n");
        }
        StringBuilder falloffFunctions = new StringBuilder(), falloffCases = new StringBuilder();
        for (int i = 0; i < falloffs.size(); i++) {
            falloffFunctions.append("float customFalloff").append(i).append("(float dist, float range) {\n")
                    .append(falloffs.get(i).glsl()).append("\n}\n");
            falloffCases.append("if (id == ").append(3 + i).append(") return customFalloff").append(i).append("(dist, range);\n");
        }
        return "#version 450\n#define ENV_KIND " + env + "\n" + FRAGMENT_BODY
                .replace("//@CLUSTER_FUNCTIONS", clustered ? CLUSTER_FUNCTIONS : "")
                .replace("//@CLUSTER_PBR", clustered ? CLUSTER_PBR : "")
                .replace("//@CLUSTER_CLASSIC", clustered ? CLUSTER_CLASSIC : "")
                .replace("//@FILTER_FUNCTIONS", filterFunctions)
                .replace("//@FILTER_CASES", filterCases)
                .replace("//@FALLOFF_FUNCTIONS", falloffFunctions)
                .replace("//@FALLOFF_CASES", falloffCases);
    }

    /**
     * The tone mappers: 0 cuts off at white, 1 is c / (1 + c), 2 a fit of the ACES film curve (Narkowicz), which expects
     * the exposure already in.
     */
    static final String TONE_MAP = """
            vec3 toneMap(vec3 c, int mode) {
                if (mode == 1) return c / (1.0 + c);
                if (mode == 2) return clamp((c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
                return c;
            }
            """;

    private static final String FRAGMENT_BODY = """
            layout(location = 0) in vec3 vWorldPos;
            layout(location = 1) in vec3 vNormal;
            layout(location = 2) in vec2 vUv;
            layout(location = 3) flat in vec4 vTint;
            layout(location = 4) flat in vec4 vParams;
            layout(location = 5) flat in vec4 vExtra;
            layout(location = 6) flat in vec4 vExtra2;
            layout(location = 7) in vec2 vUv1;
            """ + FRAME_BLOCK + """
            layout(set = 0, binding = 2) uniform sampler2D baseTexture;
            layout(set = 0, binding = 3) uniform sampler2D normalTexture;
            layout(set = 0, binding = 4) uniform sampler2D metalRoughTexture;
            layout(set = 0, binding = 5) uniform sampler2D occlusionTexture;
            layout(set = 0, binding = 6) uniform sampler2D emissiveTexture;
            layout(set = 0, binding = 7) uniform sampler2D shadowMap;
            #if ENV_KIND == 2
            layout(set = 0, binding = 8) uniform samplerCube envMap;
            #elif ENV_KIND >= 3
            layout(set = 0, binding = 8) uniform sampler2D envMap;
            #endif
            layout(location = 0) out vec4 outColor;

            const float PI = 3.14159265;
            """ + TONE_MAP + """

            #if ENV_KIND != 0
            // The light the sky gives a surface facing n, divided by pi: spherical harmonics worked out on the CPU.
            vec3 envDiffuse(vec3 n) {
                vec3 e = envSh[0].rgb + envSh[1].rgb * n.y + envSh[2].rgb * n.z + envSh[3].rgb * n.x
                       + envSh[4].rgb * (n.x * n.y) + envSh[5].rgb * (n.y * n.z) + envSh[6].rgb * (3.0 * n.z * n.z - 1.0)
                       + envSh[7].rgb * (n.x * n.z) + envSh[8].rgb * (n.x * n.x - n.y * n.y);
                return max(e, vec3(0.0)) * envParams.x;
            }

            // The sky seen along r, blurred as much as roughness asks for, in linear colour.
            vec3 envSpecular(vec3 r, float roughness) {
            #if ENV_KIND == 1
                float t = mix(r.y, 0.0, roughness * roughness * 0.8);
                vec3 c = t >= 0.0 ? mix(envHorizon.rgb, envTop.rgb, pow(t, 0.6)) : mix(envHorizon.rgb, envBottom.rgb, pow(-t, 0.6));
            #elif ENV_KIND == 2
                vec3 c = textureLod(envMap, r, roughness * envParams.z).rgb;
            #else
                vec2 uv = vec2(atan(r.x, -r.z) / (2.0 * PI) + 0.5, acos(clamp(r.y, -1.0, 1.0)) / PI);
                vec3 c = textureLod(envMap, uv, roughness * envParams.z).rgb;
            #endif
            #if ENV_KIND == 4
                return c * envParams.w * envParams.x; // already linear light
            #else
                return pow(c, vec3(2.2)) * envParams.w * envParams.x;
            #endif
            }

            // How much of a surface's reflection the sky supplies: a fit to the split-sum integral.
            vec3 envBrdf(vec3 f0, float roughness, float nv) {
                const vec4 c0 = vec4(-1.0, -0.0275, -0.572, 0.022);
                const vec4 c1 = vec4(1.0, 0.0425, 1.04, -0.04);
                vec4 r = roughness * c0 + c1;
                float a004 = min(r.x * r.x, exp2(-9.28 * nv)) * r.x + r.y;
                vec2 ab = vec2(-1.04, 1.04) * a004 + r.zw;
                return f0 * ab.x + ab.y;
            }
            #endif

            float unpackDepth(vec3 c) {
                return dot(c, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
            }

            // The part of the shadow atlas the current lookup may read, in texture coordinates, so a soft edge never
            // reaches into a neighbouring tile.
            vec2 tileMin;
            vec2 tileMax;

            // 1 if a point at the given depth is lit as seen through the atlas at tap, 0 if something is in front of it.
            float shadowLit(vec2 tap, float depth) {
                tap = clamp(tap, tileMin + 0.5 * shadowGlobal.xy, tileMax - 0.5 * shadowGlobal.xy);
                float stored = unpackDepth(textureLod(shadowMap, tap, 0.0).rgb);
                return depth > stored ? 0.0 : 1.0;
            }

            //@FILTER_FUNCTIONS

            // The edge of a shadow worked out from the map: 0 is one lookup, 1 a 4x4 block weighted like a bilinear
            // filter so the edge moves smoothly instead of in steps, 2 a wider 6x6 block. The pattern is stretched about
            // the point by the light's softness, 1 for the plain filter.
            float filterShadow(int id, vec2 uv, float depth, vec2 texel, float softness) {
                //@FILTER_CASES
                if (id == 0) return shadowLit(uv, depth);
                vec2 size = 1.0 / texel;
                vec2 t = uv * size - 0.5;
                vec2 base = (floor(t) + 0.5) / size;
                vec2 f = fract(t);
                int r = id == 2 ? 2 : 1;
                float lit = 0.0;
                float total = 0.0;
                for (int x = -r; x <= r + 1; x++) {
                    float wx = x == -r ? 1.0 - f.x : (x == r + 1 ? f.x : 1.0);
                    for (int y = -r; y <= r + 1; y++) {
                        float wy = y == -r ? 1.0 - f.y : (y == r + 1 ? f.y : 1.0);
                        vec2 tap = uv + ((base - uv) + vec2(float(x), float(y)) * texel) * softness;
                        lit += wx * wy * shadowLit(tap, depth);
                        total += wx * wy;
                    }
                }
                return lit / total;
            }

            // 1 where light i reaches the point, 0 where something is in the way, with a soft edge. Picks the light's view:
            // a directional light's cascade by how far the point is from the camera, a point light's side by the direction
            // from the light. A spot or point light's map is perspective and holds the distance along the view axis scaled
            // to 0..1; a directional light's holds depth.
            float shadowFor(int i, vec3 worldPos, vec3 n, vec3 l) {
                vec4 info = lightShadow[i];
                int first = int(info.x + 0.5);
                int count = int(info.y + 0.5);
                int kind = int(lightVec[i].w + 0.5);
                int view = first;
                if (kind == 0) {
                    if (count > 1) {
                        float depthAlong = dot(worldPos - cameraPos.xyz, cameraFwd.xyz);
                        view = -1;
                        for (int k = 0; k < 4; k++) {
                            if (k >= count) break;
                            if (depthAlong <= lightSplits[i][k]) {
                                view = first + k;
                                break;
                            }
                        }
                        if (view < 0) return 1.0;
                    }
                } else if (kind == 1) {
                    vec3 d = worldPos - lightVec[i].xyz;
                    vec3 a = abs(d);
                    int face = (a.x >= a.y && a.x >= a.z) ? (d.x > 0.0 ? 0 : 1) : (a.y >= a.z ? (d.y > 0.0 ? 2 : 3) : (d.z > 0.0 ? 4 : 5));
                    view = first + face;
                }
                ShadowView sv = views[view];
                bool persp = kind != 0;
                float ndl = clamp(dot(n, l), 0.0, 1.0);
                float scale = persp ? (sv.viewProj * vec4(worldPos, 1.0)).w : 1.0;
                vec3 p = worldPos + n * (sv.params.x * scale * (1.0 - ndl * 0.5));
                vec4 lp = sv.viewProj * vec4(p, 1.0);
                if (persp && lp.w <= sv.params.z) return 1.0;
                vec3 proj = lp.xyz / lp.w;
                // Where the point lands in its tile, from the tile's top-left corner.
                vec2 inTile = vec2(proj.x * 0.5 + 0.5, 0.5 - proj.y * 0.5);
                float depth = persp ? (lp.w - sv.params.z) / (sv.params.w - sv.params.z) : proj.z;
                if (inTile.x < 0.0 || inTile.x > 1.0 || inTile.y < 0.0 || inTile.y > 1.0 || depth > 1.0) return 1.0;
                float bias = sv.params.y * scale * (1.0 + (1.0 - ndl) * 2.0);
                // Atlas coordinates run from the top; an OpenGL texture's rows run from the bottom.
                vec2 uv = sv.rect.xy + inTile * sv.rect.zw;
                tileMin = sv.rect.xy;
                tileMax = sv.rect.xy + sv.rect.zw;
                if (shadowGlobal.z > 0.5) {
                    uv.y = 1.0 - uv.y;
                    float t = tileMin.y;
                    tileMin.y = 1.0 - tileMax.y;
                    tileMax.y = 1.0 - t;
                }
                return filterShadow(int(info.z + 0.5), uv, depth - bias, shadowGlobal.xy, info.w);
            }

            //@FALLOFF_FUNCTIONS

            // How much of a light's strength is left at a distance from it: 0 is a smooth window to nothing at the
            // range, 1 a straight line to nothing at the range, 2 no fading.
            float falloff(int id, float dist, float range) {
                //@FALLOFF_CASES
                if (id == 1) return clamp(1.0 - dist / range, 0.0, 1.0);
                if (id == 2) return 1.0;
                float x = clamp(1.0 - (dist * dist) / (range * range), 0.0, 1.0);
                return x * x;
            }

            // The direction to light i from a point, and how much of it arrives before the surface's angle and shadow.
            float lightAt(int i, vec3 worldPos, out vec3 l) {
                if (lightVec[i].w < 0.5) {
                    l = normalize(lightVec[i].xyz);
                    return 1.0;
                }
                vec3 d = lightVec[i].xyz - worldPos;
                float dist = length(d);
                l = d / max(dist, 0.0001);
                float atten = falloff(int(lightCone[i].y + 0.5), dist, lightColor[i].a);
                if (lightVec[i].w > 1.5) {
                    float c = dot(-l, lightDir[i].xyz);
                    atten *= clamp((c - lightDir[i].w) / max(lightCone[i].x - lightDir[i].w, 1e-4), 0.0, 1.0);
                }
                return atten;
            }

            //@CLUSTER_FUNCTIONS

            float distributionGGX(float nh, float a2) {
                float d = nh * nh * (a2 - 1.0) + 1.0;
                return a2 / (PI * d * d);
            }

            void main() {
                int mode = int(vParams.z + 0.5);
                int flags = int(vExtra.w + 0.5);
                // Each texture reads the first set of texture coordinates or the second, as its material says.
                vec2 uvBase = (flags & 4) != 0 ? vUv1 : vUv;
                vec2 uvNormal = (flags & 8) != 0 ? vUv1 : vUv;
                vec2 uvMetalRough = (flags & 16) != 0 ? vUv1 : vUv;
                vec2 uvOcclusion = (flags & 32) != 0 ? vUv1 : vUv;
                vec2 uvEmissive = (flags & 64) != 0 ? vUv1 : vUv;
                vec4 tex = texture(baseTexture, uvBase);
                vec3 albedo = pow(tex.rgb * vTint.rgb, vec3(2.2));
                float alpha = tex.a * vTint.a;
                if (alpha < vParams.w) discard;

                vec3 lit = albedo;
                if (mode != 2) {
                    vec3 n = normalize(vNormal);
                    if (!gl_FrontFacing) n = -n;
                    vec3 dp1 = dFdx(vWorldPos);
                    vec3 dp2 = dFdy(vWorldPos);
                    vec2 duv1 = dFdx(uvNormal);
                    vec2 duv2 = dFdy(uvNormal);
                    if ((flags & 1) != 0) {
                        // The surface's own directions of increasing u and v, solved from the screen-space derivatives
                        // (the same on both APIs, whichever way the screen's y runs). Image rows run down, so the
                        // bitangent, the direction of increasing green, is minus the direction of increasing v.
                        float det = duv1.x * duv2.y - duv1.y * duv2.x;
                        float invDet = abs(det) < 1e-20 ? 0.0 : 1.0 / det;
                        vec3 t = (dp1 * duv2.y - dp2 * duv1.y) * invDet;
                        vec3 b = -((dp2 * duv1.x - dp1 * duv2.x) * invDet);
                        t = t - n * dot(n, t);
                        b = b - n * dot(n, b);
                        float invMax = inversesqrt(max(max(dot(t, t), dot(b, b)), 1e-20));
                        vec3 tn = texture(normalTexture, uvNormal).xyz * 2.0 - 1.0;
                        tn.xy *= vExtra2.x;
                        n = normalize(mat3(t * invMax, b * invMax, n) * tn);
                    }
                    vec3 v = normalize(cameraPos.xyz - vWorldPos);
                    int count = int(params.x);
                    bool receives = (flags & 2) != 0;

                    if (mode == 1) {
                        vec4 mr = texture(metalRoughTexture, uvMetalRough);
                        float roughness = clamp(vParams.y * mr.g, 0.04, 1.0);
                        float metallic = clamp(vParams.x * mr.b, 0.0, 1.0);
                        float occlusion = texture(occlusionTexture, uvOcclusion).r;
                        vec3 f0 = mix(vec3(0.04), albedo, metallic);
                        vec3 diffuseColor = albedo * (1.0 - metallic);
                        float a = roughness * roughness;
                        float a2 = a * a;
                        float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
                        float nv = max(dot(n, v), 1e-4);
            #if ENV_KIND != 0
                        vec3 refl = reflect(-v, n);
                        refl = normalize(mix(refl, n, a));
                        vec3 reflectance = envBrdf(f0, roughness, nv);
                        lit = occlusion * (envDiffuse(n) * diffuseColor * (1.0 - reflectance) + envSpecular(refl, roughness) * reflectance);
            #else
                        lit = ambient.rgb * occlusion * (diffuseColor + f0 * mix(1.0, 0.3, roughness));
            #endif
                        for (int i = 0; i < """ + Scene3D.MAX_LIGHTS + """
            ; i++) {
                            if (i >= count) break;
                            vec3 l;
                            float atten = lightAt(i, vWorldPos, l);
                            if (atten <= 0.0) continue;
                            float nl = max(dot(n, l), 0.0);
                            if (nl <= 0.0) continue;
                            if (receives && lightShadow[i].y > 0.5) atten *= shadowFor(i, vWorldPos, n, l);
                            vec3 h = normalize(l + v);
                            float nh = max(dot(n, h), 0.0);
                            float vh = max(dot(v, h), 0.0);
                            vec3 f = f0 + (1.0 - f0) * pow(1.0 - vh, 5.0);
                            float g = (nv / (nv * (1.0 - k) + k)) * (nl / (nl * (1.0 - k) + k));
                            vec3 spec = distributionGGX(nh, a2) * g * f / max(4.0 * nv * nl, 1e-4);
                            // The light's intensity is irradiance over pi, so a white light of 1 lights a white surface to 1.
                            lit += ((vec3(1.0) - f) * diffuseColor + PI * spec) * lightColor[i].rgb * atten * nl;
                        }
                        //@CLUSTER_PBR
                        lit += vExtra.xyz * pow(texture(emissiveTexture, uvEmissive).rgb, vec3(2.2));
                    } else {
            #if ENV_KIND != 0
                        lit = envDiffuse(n) * albedo;
            #else
                        lit = ambient.rgb * albedo;
            #endif
                        for (int i = 0; i < """ + Scene3D.MAX_LIGHTS + """
            ; i++) {
                            if (i >= count) break;
                            vec3 l;
                            float atten = lightAt(i, vWorldPos, l);
                            if (atten <= 0.0) continue;
                            float ndl = max(dot(n, l), 0.0);
                            if (ndl <= 0.0) continue;
                            if (receives && lightShadow[i].y > 0.5) atten *= shadowFor(i, vWorldPos, n, l);
                            vec3 radiance = lightColor[i].rgb * atten;
                            lit += albedo * radiance * ndl;
                            vec3 h = normalize(l + v);
                            lit += radiance * vParams.x * pow(max(dot(n, h), 0.0), vParams.y);
                        }
                        //@CLUSTER_CLASSIC
                    }
                }
                if (fog.a > 0.0) {
                    float dist = length(cameraPos.xyz - vWorldPos);
                    float height = 0.5 * (cameraPos.y + vWorldPos.y);
                    float k = dist * fog.a * exp(-fogParams.x * max(height, 0.0));
                    float amount = 1.0 - exp(-k * k);
                    lit = vExtra2.y > 0.5 ? lit * (1.0 - amount) : mix(lit, fog.rgb, amount);
                }
                vec3 exposed = lit * fogParams.y;
                // Bloom keeps the light as it is (fogParams.w is 1) and tone maps the whole picture afterwards.
                outColor = fogParams.w > 0.5 ? vec4(min(exposed, vec3(65000.0)), alpha)
                        : vec4(pow(toneMap(exposed, int(fogParams.z + 0.5)), vec3(1.0 / 2.2)), alpha);
            }
            """;

    // ---- shadow pass -----------------------------------------------------------------------------------------

    static String shadowVertex(boolean skinned, boolean morph) {
        return "#version 450\n" + INPUTS + (skinned ? SKIN_INPUTS : "") + (morph ? MORPH_INPUTS : "") + UV_TRANSFORM + """
                layout(set = 0, binding = 0) uniform ShadowFrame { mat4 lightViewProj; vec4 shadowMode; };
                layout(location = 0) out vec2 vUv;
                layout(location = 1) flat out vec4 vTint;
                layout(location = 2) flat out vec4 vParams;
                void main() {
                """ + morphed(morph) + (skinned ? """
                    mat4 skin = aWeights.x * joints[int(aJoints.x)] + aWeights.y * joints[int(aJoints.y)]
                              + aWeights.z * joints[int(aJoints.z)] + aWeights.w * joints[int(aJoints.w)];
                    vec4 world = skin * vec4(mPos, 1.0);
                """ : """
                    vec4 world = mat4(iModel0, iModel1, iModel2, iModel3) * vec4(mPos, 1.0);
                """) + """
                    vUv = transformUv(aUv);
                    vTint = iTint;
                    vParams = iParams;
                    gl_Position = lightViewProj * world;
                }
                """;
    }

    /**
     * Writes the depth, packed into the three colour channels because the shadow map is an ordinary colour target.
     */
    static final String SHADOW_FRAGMENT = "#version 450\n" + """
            layout(location = 0) in vec2 vUv;
            layout(location = 1) flat in vec4 vTint;
            layout(location = 2) flat in vec4 vParams;
            layout(set = 0, binding = 0) uniform ShadowFrame { mat4 lightViewProj; vec4 shadowMode; };
            layout(set = 0, binding = 2) uniform sampler2D baseTexture;
            layout(location = 0) out vec4 outColor;
            void main() {
                if (vParams.w > 0.0 && texture(baseTexture, vUv).a * vTint.a < vParams.w) discard;
                float d = gl_FragCoord.z;
                // A perspective (spot light) map stores the distance along the axis, scaled to 0..1, so the bias is even.
                if (shadowMode.x > 0.5) d = clamp((1.0 / gl_FragCoord.w - shadowMode.y) / (shadowMode.z - shadowMode.y), 0.0, 1.0);
                vec3 enc = fract(d * vec3(1.0, 255.0, 65025.0));
                enc -= enc.yzz * vec3(1.0 / 255.0, 1.0 / 255.0, 0.0);
                outColor = vec4(enc, 1.0);
            }
            """;

    // ---- skybox ----------------------------------------------------------------------------------------------

    static final String SKY_VERTEX = "#version 450\n" + """
            layout(location = 0) in vec2 aPos;
            layout(set = 0, binding = 0) uniform Sky {
                vec4 forward;
                vec4 right;
                vec4 up;
                vec4 top;
                vec4 horizon;
                vec4 bottom;
            };
            layout(location = 0) out vec3 vDir;
            void main() {
                vDir = forward.xyz + aPos.x * right.xyz * right.w + aPos.y * up.xyz * up.w;
                gl_Position = vec4(aPos, 1.0, 1.0);
            }
            """;

    private static final String SKY_BLOCK = """
            layout(location = 0) in vec3 vDir;
            layout(set = 0, binding = 0) uniform Sky {
                vec4 forward;
                vec4 right;
                vec4 up;
                vec4 top;
                vec4 horizon;
                vec4 bottom;
            };
            layout(location = 0) out vec4 outColor;
            // forward.w is 1 when bloom keeps the light linear: a sky of 8-bit colours is decoded then, and tone mapped
            // with the rest of the picture.
            vec3 fromDisplay(vec3 c) { return forward.w > 0.5 ? pow(c, vec3(2.2)) : c; }
            """;

    static final String SKY_GRADIENT_FRAGMENT = "#version 450\n" + SKY_BLOCK + """
            void main() {
                float t = normalize(vDir).y;
                vec3 color = t >= 0.0 ? mix(horizon.rgb, top.rgb, pow(t, 0.6)) : mix(horizon.rgb, bottom.rgb, pow(-t, 0.6));
                outColor = vec4(fromDisplay(color * top.a), 1.0);
            }
            """;

    static final String SKY_CUBE_FRAGMENT = "#version 450\n" + SKY_BLOCK + """
            layout(set = 0, binding = 1) uniform samplerCube skyTexture;
            void main() {
                outColor = vec4(fromDisplay(texture(skyTexture, normalize(vDir)).rgb * top.a), 1.0);
            }
            """;

    /**
     * A sky of linear light with no upper limit: it takes the scene's exposure and tone mapping (the sky block's horizon
     * holds them: x the exposure, y the tone mapper) and the sky's brightness (top.a), and is gamma encoded at the end.
     */
    static final String SKY_EQUIRECT_HDR_FRAGMENT = "#version 450\n" + SKY_BLOCK + """
            layout(set = 0, binding = 1) uniform sampler2D skyTexture;
            """ + TONE_MAP + """
            void main() {
                vec3 d = normalize(vDir);
                vec2 uv = vec2(atan(d.x, -d.z) / (2.0 * 3.14159265) + 0.5, acos(clamp(d.y, -1.0, 1.0)) / 3.14159265);
                vec3 c = textureLod(skyTexture, uv, 0.0).rgb * top.a * horizon.x;
                outColor = forward.w > 0.5 ? vec4(min(c, vec3(65000.0)), 1.0)
                        : vec4(pow(toneMap(c, int(horizon.y + 0.5)), vec3(1.0 / 2.2)), 1.0);
            }
            """;

    static final String SKY_EQUIRECT_FRAGMENT = "#version 450\n" + SKY_BLOCK + """
            layout(set = 0, binding = 1) uniform sampler2D skyTexture;
            void main() {
                vec3 d = normalize(vDir);
                vec2 uv = vec2(atan(d.x, -d.z) / (2.0 * 3.14159265) + 0.5, acos(clamp(d.y, -1.0, 1.0)) / 3.14159265);
                outColor = vec4(fromDisplay(textureLod(skyTexture, uv, 0.0).rgb * top.a), 1.0);
            }
            """;
}
