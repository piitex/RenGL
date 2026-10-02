package me.piitex.engine.render3d.scene;

/**
 * The scene renderer's GLSL. One vertex and one fragment shader cover every material: classic or physically based
 * shading and unlit surfaces are chosen per draw from the instance data, so there is one program per kind of geometry
 * (static or skinned) and render state, not one per material.
 * <p>
 * Binding numbers are shared by every program: 0 is the frame block, 1 the joint matrices (skinned programs), 2 to 6
 * the material's textures (base colour, normal, metallic-roughness, occlusion, emissive) and 7 the shadow map.
 */
final class SceneShaders {
    private SceneShaders() {
    }

    /**
     * Floats per instance in the instance stream: eleven vec4s.
     */
    static final int INSTANCE_FLOATS = 44;

    static final int FLAG_NORMAL_MAP = 1;
    static final int FLAG_RECEIVE_SHADOW = 2;

    static final int MODE_CLASSIC = 0;
    static final int MODE_PBR = 1;
    static final int MODE_UNLIT = 2;

    /**
     * Per-vertex attributes at locations 0 to 2; instance attributes follow at 3 to 13, skin attributes at 14 and 15.
     */
    private static final String INPUTS = """
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUv;
            layout(location = 3) in vec4 iModel0;
            layout(location = 4) in vec4 iModel1;
            layout(location = 5) in vec4 iModel2;
            layout(location = 6) in vec4 iModel3;
            layout(location = 7) in vec4 iNormal0;
            layout(location = 8) in vec4 iNormal1;
            layout(location = 9) in vec4 iNormal2;
            layout(location = 10) in vec4 iTint;
            layout(location = 11) in vec4 iParams;
            layout(location = 12) in vec4 iExtra;
            layout(location = 13) in vec4 iExtra2;
            """;

    private static final String SKIN_INPUTS = """
            layout(location = 14) in vec4 aJoints;
            layout(location = 15) in vec4 aWeights;
            layout(set = 0, binding = 1) uniform Joints { mat4 joints[256]; };
            """;

    static final String FRAME_BLOCK = """
            layout(set = 0, binding = 0) uniform Frame {
                mat4 viewProj;
                vec4 cameraPos;
                vec4 ambient;
                vec4 params;
                vec4 shadowA;
                vec4 shadowB;
                vec4 lightVec[8];
                vec4 lightColor[8];
                mat4 lightViewProj;
            };
            """;

    /**
     * The scene's own vertex shader. {@code skinned} adds the joint blend, which replaces the instance's model matrix.
     */
    static String vertex(boolean skinned) {
        return "#version 450\n" + INPUTS + (skinned ? SKIN_INPUTS : "") + FRAME_BLOCK + """
                layout(location = 0) out vec3 vWorldPos;
                layout(location = 1) out vec3 vNormal;
                layout(location = 2) out vec2 vUv;
                layout(location = 3) flat out vec4 vTint;
                layout(location = 4) flat out vec4 vParams;
                layout(location = 5) flat out vec4 vExtra;
                layout(location = 6) flat out vec4 vExtra2;
                void main() {
                """ + (skinned ? """
                    mat4 skin = aWeights.x * joints[int(aJoints.x)] + aWeights.y * joints[int(aJoints.y)]
                              + aWeights.z * joints[int(aJoints.z)] + aWeights.w * joints[int(aJoints.w)];
                    vec4 world = skin * vec4(aPos, 1.0);
                    vNormal = mat3(skin) * aNormal;
                """ : """
                    mat4 model = mat4(iModel0, iModel1, iModel2, iModel3);
                    vec4 world = model * vec4(aPos, 1.0);
                    vNormal = mat3(iNormal0.xyz, iNormal1.xyz, iNormal2.xyz) * aNormal;
                """) + """
                    vWorldPos = world.xyz;
                    vUv = aUv;
                    vTint = iTint;
                    vParams = iParams;
                    vExtra = iExtra;
                    vExtra2 = iExtra2;
                    gl_Position = viewProj * world;
                }
                """;
    }

    static final String FRAGMENT = "#version 450\n" + """
            layout(location = 0) in vec3 vWorldPos;
            layout(location = 1) in vec3 vNormal;
            layout(location = 2) in vec2 vUv;
            layout(location = 3) flat in vec4 vTint;
            layout(location = 4) flat in vec4 vParams;
            layout(location = 5) flat in vec4 vExtra;
            layout(location = 6) flat in vec4 vExtra2;
            """ + FRAME_BLOCK + """
            layout(set = 0, binding = 2) uniform sampler2D baseTexture;
            layout(set = 0, binding = 3) uniform sampler2D normalTexture;
            layout(set = 0, binding = 4) uniform sampler2D metalRoughTexture;
            layout(set = 0, binding = 5) uniform sampler2D occlusionTexture;
            layout(set = 0, binding = 6) uniform sampler2D emissiveTexture;
            layout(set = 0, binding = 7) uniform sampler2D shadowMap;
            layout(location = 0) out vec4 outColor;

            const float PI = 3.14159265;

            float unpackDepth(vec3 c) {
                return dot(c, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
            }

            // 1 where the light reaches the point, 0 where something is in the way, with a soft edge.
            float shadow(vec3 worldPos, vec3 n, vec3 l) {
                float ndl = clamp(dot(n, l), 0.0, 1.0);
                vec3 p = worldPos + n * (shadowB.x * (1.0 - ndl * 0.5));
                vec4 lp = lightViewProj * vec4(p, 1.0);
                vec3 proj = lp.xyz / lp.w;
                vec2 uv = vec2(proj.x * 0.5 + 0.5, shadowA.y > 0.5 ? proj.y * 0.5 + 0.5 : 0.5 - proj.y * 0.5);
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0 || proj.z > 1.0) return 1.0;
                float bias = shadowA.w * (1.0 + (1.0 - ndl) * 2.0);
                float lit = 0.0;
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 1; y++) {
                        float stored = unpackDepth(textureLod(shadowMap, uv + vec2(float(x), float(y)) * shadowA.z, 0.0).rgb);
                        lit += (proj.z - bias > stored) ? 0.0 : 1.0;
                    }
                }
                return lit / 9.0;
            }

            float distributionGGX(float nh, float a2) {
                float d = nh * nh * (a2 - 1.0) + 1.0;
                return a2 / (PI * d * d);
            }

            void main() {
                vec4 tex = texture(baseTexture, vUv);
                vec3 albedo = pow(tex.rgb * vTint.rgb, vec3(2.2));
                float alpha = tex.a * vTint.a;
                if (alpha < vParams.w) discard;
                int mode = int(vParams.z + 0.5);
                int flags = int(vExtra.w + 0.5);

                vec3 lit = albedo;
                if (mode != 2) {
                    vec3 n = normalize(vNormal);
                    if (!gl_FrontFacing) n = -n;
                    vec3 dp1 = dFdx(vWorldPos);
                    vec3 dp2 = dFdy(vWorldPos);
                    vec2 duv1 = dFdx(vUv);
                    vec2 duv2 = dFdy(vUv);
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
                        vec3 tn = texture(normalTexture, vUv).xyz * 2.0 - 1.0;
                        tn.xy *= vExtra2.x;
                        n = normalize(mat3(t * invMax, b * invMax, n) * tn);
                    }
                    vec3 v = normalize(cameraPos.xyz - vWorldPos);
                    int count = int(params.x);
                    int shadowLight = int(shadowA.x);
                    bool receives = (flags & 2) != 0;

                    if (mode == 1) {
                        vec4 mr = texture(metalRoughTexture, vUv);
                        float roughness = clamp(vParams.y * mr.g, 0.04, 1.0);
                        float metallic = clamp(vParams.x * mr.b, 0.0, 1.0);
                        float occlusion = texture(occlusionTexture, vUv).r;
                        vec3 f0 = mix(vec3(0.04), albedo, metallic);
                        vec3 diffuseColor = albedo * (1.0 - metallic);
                        float a = roughness * roughness;
                        float a2 = a * a;
                        float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
                        float nv = max(dot(n, v), 1e-4);
                        lit = ambient.rgb * occlusion * (diffuseColor + f0 * mix(1.0, 0.3, roughness));
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
                            float nl = max(dot(n, l), 0.0);
                            if (nl <= 0.0) continue;
                            if (i == shadowLight && receives) atten *= shadow(vWorldPos, n, l);
                            vec3 h = normalize(l + v);
                            float nh = max(dot(n, h), 0.0);
                            float vh = max(dot(v, h), 0.0);
                            vec3 f = f0 + (1.0 - f0) * pow(1.0 - vh, 5.0);
                            float g = (nv / (nv * (1.0 - k) + k)) * (nl / (nl * (1.0 - k) + k));
                            vec3 spec = distributionGGX(nh, a2) * g * f / max(4.0 * nv * nl, 1e-4);
                            // The light's intensity is irradiance over pi, so a white light of 1 lights a white surface to 1.
                            lit += ((vec3(1.0) - f) * diffuseColor + PI * spec) * lightColor[i].rgb * atten * nl;
                        }
                        lit += vExtra.xyz * pow(texture(emissiveTexture, vUv).rgb, vec3(2.2));
                    } else {
                        lit = ambient.rgb * albedo;
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
                            if (ndl <= 0.0) continue;
                            if (i == shadowLight && receives) atten *= shadow(vWorldPos, n, l);
                            vec3 radiance = lightColor[i].rgb * atten;
                            lit += albedo * radiance * ndl;
                            vec3 h = normalize(l + v);
                            lit += radiance * vParams.x * pow(max(dot(n, h), 0.0), vParams.y);
                        }
                    }
                }
                outColor = vec4(pow(lit, vec3(1.0 / 2.2)), alpha);
            }
            """;

    // ---- shadow pass -----------------------------------------------------------------------------------------

    static String shadowVertex(boolean skinned) {
        return "#version 450\n" + INPUTS + (skinned ? SKIN_INPUTS : "") + """
                layout(set = 0, binding = 0) uniform ShadowFrame { mat4 lightViewProj; };
                layout(location = 0) out vec2 vUv;
                layout(location = 1) flat out vec4 vTint;
                layout(location = 2) flat out vec4 vParams;
                void main() {
                """ + (skinned ? """
                    mat4 skin = aWeights.x * joints[int(aJoints.x)] + aWeights.y * joints[int(aJoints.y)]
                              + aWeights.z * joints[int(aJoints.z)] + aWeights.w * joints[int(aJoints.w)];
                    vec4 world = skin * vec4(aPos, 1.0);
                """ : """
                    vec4 world = mat4(iModel0, iModel1, iModel2, iModel3) * vec4(aPos, 1.0);
                """) + """
                    vUv = aUv;
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
            layout(set = 0, binding = 2) uniform sampler2D baseTexture;
            layout(location = 0) out vec4 outColor;
            void main() {
                if (vParams.w > 0.0 && texture(baseTexture, vUv).a * vTint.a < vParams.w) discard;
                float d = gl_FragCoord.z;
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
            """;

    static final String SKY_GRADIENT_FRAGMENT = "#version 450\n" + SKY_BLOCK + """
            void main() {
                float t = normalize(vDir).y;
                vec3 color = t >= 0.0 ? mix(horizon.rgb, top.rgb, pow(t, 0.6)) : mix(horizon.rgb, bottom.rgb, pow(-t, 0.6));
                outColor = vec4(color * top.a, 1.0);
            }
            """;

    static final String SKY_CUBE_FRAGMENT = "#version 450\n" + SKY_BLOCK + """
            layout(set = 0, binding = 1) uniform samplerCube skyTexture;
            void main() {
                outColor = vec4(texture(skyTexture, normalize(vDir)).rgb * top.a, 1.0);
            }
            """;

    static final String SKY_EQUIRECT_FRAGMENT = "#version 450\n" + SKY_BLOCK + """
            layout(set = 0, binding = 1) uniform sampler2D skyTexture;
            void main() {
                vec3 d = normalize(vDir);
                vec2 uv = vec2(atan(d.x, -d.z) / (2.0 * 3.14159265) + 0.5, acos(clamp(d.y, -1.0, 1.0)) / 3.14159265);
                outColor = vec4(texture(skyTexture, uv).rgb * top.a, 1.0);
            }
            """;
}
