package me.piitex.engine.render3d.scene;

/**
 * The shaders of picture cards ({@link Mesh#setImpostor}): the two that take the pictures, and the pieces that turn the
 * scene's own shaders into ones that draw a card.
 * <p>
 * A card's pictures are taken from {@link #GRID} by {@link #GRID} directions spread over the whole sphere by an octahedral
 * map, each into its own tile of two atlases: one of the colour with its coverage, one of the direction the surface faces
 * (in the mesh's space) with how deep it is. Each view is a square around the mesh's bounding sphere seen straight on, the
 * tile's x along the view's right and its y along its up ({@code impostorBasis}). Drawn, a card is a square facing the
 * camera around the same sphere; each pixel blends the four views nearest the direction it is seen from, and is lit, shadowed
 * and placed in depth as the surface the pictures hold.
 */
final class ImpostorShaders {
    /** Views along each side of the octahedral map. */
    static final int GRID = 8;
    /** How much bigger than the bounding sphere each view's square is, so nothing touches the edge of its tile. */
    static final float MARGIN = 1.02f;
    /** The binding of the card's block: the mesh's sphere, the number of views, and whether the atlas rows run up. */
    static final int BLOCK_BINDING = 1;

    private ImpostorShaders() {
    }

    /** The octahedral map of directions to the unit square and back, and the right and up of a view. */
    private static final String FUNCTIONS = """
            vec2 impostorSignNotZero(vec2 v) {
                return vec2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0);
            }
            vec2 impostorEncode(vec3 d) {
                d /= abs(d.x) + abs(d.y) + abs(d.z);
                vec2 p = d.xz;
                if (d.y < 0.0) p = (1.0 - abs(p.yx)) * impostorSignNotZero(p);
                return p * 0.5 + 0.5;
            }
            vec3 impostorDecode(vec2 uv) {
                vec2 p = uv * 2.0 - 1.0;
                vec3 d = vec3(p.x, 1.0 - abs(p.x) - abs(p.y), p.y);
                if (d.y < 0.0) d.xz = (1.0 - abs(d.zx)) * impostorSignNotZero(d.xz);
                return normalize(d);
            }
            void impostorBasis(vec3 d, out vec3 right, out vec3 up) {
                vec3 ref = abs(d.y) > 0.999 ? vec3(0.0, 0.0, -sign(d.y)) : vec3(0.0, 1.0, 0.0);
                right = normalize(cross(ref, d));
                up = cross(d, right);
            }
            """;

    /**
     * Takes one view of the mesh into the tile the viewport is set to; the view's direction (toward the camera) comes per
     * instance. Depth runs from the near side of the sphere (0) to the far side (1).
     */
    static final String BAKE_VERTEX = "#version 450\n" + """
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUv;
            layout(location = 3) in vec4 iView;
            layout(set = 0, binding = 0) uniform Bake { vec4 sphere; vec4 uvPlace; vec4 uvTurn; };
            layout(location = 0) out vec2 vUv;
            layout(location = 1) out vec3 vNormal;
            layout(location = 2) out float vDepth;
            layout(location = 3) flat out vec3 vView;
            """ + FUNCTIONS + """
            void main() {
                vec3 d = iView.xyz, right, up;
                impostorBasis(d, right, up);
                vec3 p = aPos - sphere.xyz;
                float r = sphere.w * %MARGIN%;
                vDepth = 0.5 - 0.5 * dot(p, d) / r;
                gl_Position = vec4(dot(p, right) / r, dot(p, up) / r, vDepth, 1.0);
                vec2 s = aUv * uvPlace.zw;
                vUv = vec2(uvTurn.x * s.x + uvTurn.y * s.y, -uvTurn.y * s.x + uvTurn.x * s.y) + uvPlace.xy;
                vNormal = aNormal;
                vView = d;
            }
            """.replace("%MARGIN%", Float.toString(MARGIN));

    /** The colour picture: the material's texture as it is stored, with its coverage. */
    static final String BAKE_COLOR = "#version 450\n" + """
            layout(location = 0) in vec2 vUv;
            layout(location = 1) in vec3 vNormal;
            layout(location = 2) in float vDepth;
            layout(location = 3) flat in vec3 vView;
            layout(set = 0, binding = 0) uniform Bake { vec4 sphere; vec4 uvPlace; vec4 uvTurn; };
            layout(set = 0, binding = 2) uniform sampler2D baseTexture;
            layout(location = 0) out vec4 outColor;
            void main() {
                vec4 tex = texture(baseTexture, vUv);
                // A cut-out material keeps the holes its texture makes; any other covers its whole surface.
                if (uvTurn.z > 0.0 && tex.a < uvTurn.z) discard;
                outColor = vec4(tex.rgb, 1.0);
            }
            """;

    /** The surface picture: the direction it faces in the mesh's space, packed into 0..1, and its depth. */
    static final String BAKE_SURFACE = "#version 450\n" + """
            layout(location = 0) in vec2 vUv;
            layout(location = 1) in vec3 vNormal;
            layout(location = 2) in float vDepth;
            layout(location = 3) flat in vec3 vView;
            layout(set = 0, binding = 0) uniform Bake { vec4 sphere; vec4 uvPlace; vec4 uvTurn; };
            layout(set = 0, binding = 2) uniform sampler2D baseTexture;
            layout(location = 0) out vec4 outColor;
            void main() {
                if (uvTurn.z > 0.0 && texture(baseTexture, vUv).a < uvTurn.z) discard;
                vec3 n = normalize(vNormal);
                // The back of a two-sided leaf faces the camera too.
                if (dot(n, vView) < 0.0) n = -n;
                outColor = vec4(n * 0.5 + 0.5, vDepth);
            }
            """;

    /**
     * The scene's vertex shader for a card: a square of the bounding sphere's size facing the camera, placed by the instance's
     * transform; its corners are the quad mesh's positions, from -1 to 1. It hands on what the scene's fragment shader reads,
     * and for the card the point's place in the mesh's space, the direction it is seen from there, and how to turn the mesh's
     * directions into the scene's.
     */
    static String vertex(String inputs, String uvTransform, String frameBlock) {
        return "#version 450\n" + inputs + uvTransform + frameBlock + """
                layout(set = 0, binding = 1) uniform Impostor { vec4 sphere; vec4 grid; };
                layout(location = 0) out vec3 vWorldPos;
                layout(location = 1) out vec3 vNormal;
                layout(location = 2) out vec2 vUv;
                layout(location = 3) flat out vec4 vTint;
                layout(location = 4) flat out vec4 vParams;
                layout(location = 5) flat out vec4 vExtra;
                layout(location = 6) flat out vec4 vExtra2;
                layout(location = 7) out vec2 vUv1;
                layout(location = 8) out vec3 vCardLocal;
                layout(location = 9) out vec3 vCardView;
                layout(location = 10) flat out vec4 vCardCentre;
                layout(location = 11) flat out vec3 vCardNormal0;
                layout(location = 12) flat out vec3 vCardNormal1;
                layout(location = 13) flat out vec3 vCardNormal2;
                void main() {
                    mat4 model = mat4(iModel0, iModel1, iModel2, iModel3);
                    mat3 m3 = mat3(model);
                    vec3 centre = (model * vec4(sphere.xyz, 1.0)).xyz;
                    float scale = max(length(m3[0]), max(length(m3[1]), length(m3[2])));
                    float radius = sphere.w * %MARGIN% * scale;
                    vec3 toEye = cameraPos.xyz - centre;
                    vec3 seen = toEye / max(length(toEye), 1e-6);
                    vec3 ref = abs(seen.y) > 0.999 ? vec3(0.0, 0.0, -sign(seen.y)) : vec3(0.0, 1.0, 0.0);
                    vec3 right = normalize(cross(ref, seen));
                    vec3 up = cross(seen, right);
                    vec3 world = centre + (aPos.x * right + aPos.y * up) * radius;
                    mat3 inv = abs(determinant(m3)) < 1e-20 ? mat3(1.0) : inverse(m3);
                    vCardLocal = inv * (world - centre) / (sphere.w * %MARGIN%);
                    vCardView = inv * seen;
                    vCardCentre = vec4(centre, radius);
                    mat3 normals = abs(determinant(m3)) < 1e-20 ? m3 : transpose(inv);
                    vCardNormal0 = normals[0];
                    vCardNormal1 = normals[1];
                    vCardNormal2 = normals[2];
                    vWorldPos = world;
                    vNormal = seen;
                    vUv = aUv;
                    vUv1 = aUv;
                    vTint = iTint;
                    vParams = iParams;
                    vExtra = iExtra;
                    vExtra2 = iExtra2;
                    gl_Position = viewProj * vec4(world, 1.0);
                }
                """.replace("%MARGIN%", Float.toString(MARGIN));
    }

    /**
     * Declarations the scene's fragment shader gets for a card. Its position and normal inputs become plain variables the card
     * fills in from the pictures, so the rest of the shader lights the surface they hold.
     */
    static final String FRAGMENT_DECLARATIONS = """
            layout(location = 8) in vec3 vCardLocal;
            layout(location = 9) in vec3 vCardView;
            layout(location = 10) flat in vec4 vCardCentre;
            layout(location = 11) flat in vec3 vCardNormal0;
            layout(location = 12) flat in vec3 vCardNormal1;
            layout(location = 13) flat in vec3 vCardNormal2;
            layout(set = 0, binding = 1) uniform Impostor { vec4 sphere; vec4 grid; };
            vec3 vWorldPos;
            vec3 vNormal;
            """ + FUNCTIONS;

    /**
     * Where the card is read: the colour, coverage, facing and depth blended from the four views nearest the direction the
     * pixel is seen from (each weighted by how near, and the colour by its coverage too), then the surface point and its depth.
     */
    static final String FRAGMENT_SURFACE = """
                {
                    float views = grid.x;
                    vec2 g = impostorEncode(normalize(vCardView)) * (views - 1.0);
                    vec2 cellBase = clamp(floor(g), vec2(0.0), vec2(views - 2.0));
                    vec2 f = clamp(g - cellBase, 0.0, 1.0);
                    vec3 rgb = vec3(0.0);
                    vec4 surface = vec4(0.0);
                    float covered = 0.0, weights = 0.0, coloured = 0.0;
                    for (int k = 0; k < 4; k++) {
                        vec2 corner = vec2(float(k & 1), float(k >> 1));
                        vec2 cell = cellBase + corner;
                        float w = mix(1.0 - f.x, f.x, corner.x) * mix(1.0 - f.y, f.y, corner.y);
                        if (w <= 0.0) continue;
                        vec3 d = impostorDecode(cell / (views - 1.0)), right, up;
                        impostorBasis(d, right, up);
                        vec2 t = vec2(dot(vCardLocal, right), dot(vCardLocal, up));
                        weights += w;
                        if (abs(t.x) > 1.0 || abs(t.y) > 1.0) continue;
                        vec2 uv = (cell + vec2(t.x * 0.5 + 0.5, 0.5 - t.y * 0.5)) / views;
                        if (grid.y > 0.5) uv.y = 1.0 - uv.y;
                        vec4 c = textureLod(baseTexture, uv, 0.0);
                        covered += w * c.a;
                        rgb += w * c.a * c.rgb;
                        surface += w * c.a * textureLod(normalTexture, uv, 0.0);
                        coloured += w * c.a;
                    }
                    if (weights <= 0.0 || covered < 0.5 * weights) discard;
                    rgb /= coloured;
                    surface /= coloured;
                    albedo = pow(rgb * vTint.rgb, vec3(2.2));
                    alpha = vTint.a;
                    vNormal = normalize(mat3(vCardNormal0, vCardNormal1, vCardNormal2) * (surface.xyz * 2.0 - 1.0));
                    // How far in front of the card's middle the surface is, along the line of sight.
                    vec3 seen = normalize(cameraPos.xyz - vCardCentre.xyz);
                    vWorldPos = vCardWorld + seen * (1.0 - 2.0 * surface.w) * vCardCentre.w;
                    vec4 clip = viewProj * vec4(vWorldPos, 1.0);
                    gl_FragDepth = clamp(clip.z / clip.w, 0.0, 1.0);
                }
            """;
}
